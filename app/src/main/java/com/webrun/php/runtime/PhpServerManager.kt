package com.webrun.php.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class PhpServerManager(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8080
        const val URL = "http://127.0.0.1:8080/"
        private const val MAX_HEADER_LINE = 16 * 1024
        private const val MAX_REQUEST_BODY = 32 * 1024 * 1024
        private const val PHP_TIMEOUT_SECONDS = 35L
        private const val RESPONSE_MARKER = "===LOCALDEV_RESPONSE==="
    }

    private data class Request(
        val method: String,
        val target: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap()
    )

    private val runtime = PhpRuntimeManager(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requestExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val processOutputExecutor: ExecutorService = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var activeProjectRoot: File? = null
    // Fallback cookie jar for localhost testing. Real browser cookies still win; this is only
    // used when a client drops the Cookie header between requests (common with some Android
    // WebView/external-browser transitions). LocalDev is a single-user local server, and the
    // jar is cleared whenever the PHP server/project restarts.
    private val fallbackCookies = ConcurrentHashMap<String, String>()
    // Sticky PHP session for the active local project. The CLI bridge may be used with
    // browsers/WebViews that do not persist Set-Cookie reliably. The bridge reports the
    // actual session id after every PHP request; LocalDev then re-injects it on the next
    // request. This is safe for LocalDev's single-user 127.0.0.1 server and is cleared
    // whenever the server/project restarts.
    @Volatile private var stickySessionName: String = "PHPSESSID"
    @Volatile private var stickySessionId: String = ""

    fun start(documentRoot: File, projectRoot: File = documentRoot, onReady: () -> Unit) {
        stop(silent = true)
        acceptExecutor.execute {
            val diag = runtime.diagnostics()
            if (!diag.available) {
                emitState("Runtime PHP tidak siap")
                emitLog("ERROR: ${diag.error}\n${diag.raw}")
                return@execute
            }

            val root = runCatching { documentRoot.canonicalFile }.getOrElse {
                emitState("Project PHP tidak valid")
                emitLog("ERROR: ${it.message ?: it.javaClass.simpleName}")
                return@execute
            }
            activeProjectRoot = runCatching { projectRoot.canonicalFile }.getOrDefault(root)
            fallbackCookies.clear()
            stickySessionName = "PHPSESSID"
            stickySessionId = ""

            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(HOST), PORT))
                }
                serverSocket = socket
                running = true

                emitState("PHP ${diag.version} • $URL")
                emitLog("LocalDev PHP bridge aktif • CLI mode • $URL")
                main.post(onReady)

                while (running && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        requestExecutor.execute { handleClient(client, root) }
                    } catch (t: Throwable) {
                        if (running) emitLog("HTTP accept error: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                running = false
                serverSocket = null
                emitState("PHP server gagal start")
                emitLog("ERROR: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stop() = stop(silent = false)

    private fun stop(silent: Boolean) {
        val wasRunning = running || serverSocket != null
        running = false
        val socket = serverSocket
        serverSocket = null
        activeProjectRoot = null
        fallbackCookies.clear()
        stickySessionName = "PHPSESSID"
        stickySessionId = ""
        runCatching { socket?.close() }
        if (!silent && wasRunning) emitState("PHP server berhenti")
    }

    fun isRunning(): Boolean = running && serverSocket?.isClosed == false

    fun close() {
        stop(silent = true)
        acceptExecutor.shutdownNow()
        requestExecutor.shutdownNow()
        processOutputExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket, documentRoot: File) {
        socket.use { client ->
            try {
                client.soTimeout = 12_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readRequest(input)
                if (request == null) {
                    writeResponse(output, Response(400, "text/plain; charset=UTF-8", "Bad Request".toByteArray()))
                    return
                }
                val response = route(request, documentRoot)
                writeResponse(output, response, headOnly = request.method.equals("HEAD", true))
            } catch (t: Throwable) {
                emitLog("HTTP request error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readHttpLine(input) ?: return null
        val parts = requestLine.trim().split(' ', limit = 3)
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }

        val length = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (length > MAX_REQUEST_BODY) {
            return Request(method, target, "/", "", headers, ByteArray(0))
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) break
            offset += read
        }
        val actualBody = if (offset == body.size) body else body.copyOf(offset)

        val uri = runCatching { URI(target) }.getOrNull()
        val rawPath = uri?.rawPath ?: target.substringBefore('?')
        val rawQuery = uri?.rawQuery ?: target.substringAfter('?', "")
        val decodedPath = runCatching {
            URLDecoder.decode(if (rawPath.isBlank()) "/" else rawPath, StandardCharsets.UTF_8.name())
        }.getOrDefault("/")

        return Request(method, target, decodedPath, rawQuery, headers, actualBody)
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_HEADER_LINE) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun withFallbackCookies(request: Request): Request {
        if (fallbackCookies.isEmpty()) return request

        // v0.9.4 only used the fallback jar when the browser sent *no* Cookie header at all.
        // That still breaks after session_regenerate_id(): Chrome/WebView can briefly send the
        // previous PHPSESSID while LocalDev already knows the new one. The stale browser value
        // then wins and the next protected page immediately redirects back to login.
        //
        // LocalDev is a single-user loopback server, so the server-side jar is authoritative for
        // cookies that LocalDev itself most recently emitted. Merge the browser cookies and then
        // overlay the remembered values. This keeps unrelated project cookies intact while
        // guaranteeing that PHPSESSID follows the newest regenerated session id.
        val merged = linkedMapOf<String, String>()
        val incoming = request.headers["cookie"].orEmpty()
        if (incoming.isNotBlank()) {
            incoming.split(';').forEach { part ->
                val item = part.trim()
                val eq = item.indexOf('=')
                if (eq > 0) {
                    val name = item.substring(0, eq).trim()
                    val value = item.substring(eq + 1).trim()
                    if (name.isNotBlank()) merged[name] = value
                }
            }
        }

        val stickyName = stickySessionName.ifBlank { "PHPSESSID" }
        val stickyId = stickySessionId
        if (stickyId.isNotBlank() && merged[stickyName] != stickyId) {
            merged[stickyName] = stickyId
        }

        var synced = false
        fallbackCookies.forEach { (name, value) ->
            if (merged[name] != value) synced = true
            merged[name] = value
        }

        val cookieHeader = merged.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        if (cookieHeader.isBlank()) return request
        val headers = LinkedHashMap(request.headers)
        headers["cookie"] = cookieHeader
        if (synced || stickyId.isNotBlank()) {
            val names = (fallbackCookies.keys + listOf(stickyName)).distinct().joinToString()
            emitLog("Cookie session disinkronkan • $names")
        }
        return request.copy(headers = headers)
    }

    private fun rememberSetCookie(value: String) {
        val first = value.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return
        val name = first.substring(0, eq).trim()
        val cookieValue = first.substring(eq + 1).trim()
        if (name.isBlank()) return
        if (cookieValue.isBlank()) fallbackCookies.remove(name) else fallbackCookies[name] = cookieValue
    }

    private data class RouteResolution(
        val file: File,
        val request: Request,
        val description: String? = null
    )

    private fun route(request: Request, documentRoot: File): Response {
        val request = withFallbackCookies(request)
        if ((request.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_REQUEST_BODY) {
            return textResponse(413, "Request body terlalu besar.")
        }

        val cleanRelative = request.path.removePrefix("/").trimStart('/')
        var target = runCatching { File(documentRoot, cleanRelative).canonicalFile }.getOrNull()
            ?: return textResponse(400, "Path tidak valid.")

        if (!isInside(documentRoot, target)) return textResponse(403, "Forbidden")

        if (target.isDirectory) {
            target = listOf("index.php", "index.html", "index.htm")
                .map { File(target, it) }
                .firstOrNull { it.isFile }
                ?: return textResponse(404, "Index file tidak ditemukan.")
        }

        if (target.exists()) {
            return serveResolved(RouteResolution(target, request), documentRoot)
        }

        // Static requests must never fall through to index.php. A missing PNG/CSS/JS that
        // receives HTML is both confusing in WebView and hides the real missing-asset bug.
        if (looksLikeStaticPath(cleanRelative)) {
            val fallback = resolveMissingAssetFallback(cleanRelative, documentRoot)
            if (fallback != null) {
                emitLog("Asset fallback • /$cleanRelative → /${fallback.relativeTo(documentRoot).invariantSeparatorsPath}")
                return serveStatic(fallback)
            }
            emitLog("Asset missing • /$cleanRelative")
            return textResponse(404, "Asset tidak ditemukan: /$cleanRelative")
        }

        // Common Apache/LiteSpeed behaviour for URLs such as /admin/login -> /admin/login.php.
        resolveExtensionlessPhp(cleanRelative, request, documentRoot)?.let {
            emitRoute(it)
            return serveResolved(it, documentRoot)
        }

        // Apply simple RewriteRule directives when a project actually ships them.
        resolveHtaccessRewrite(cleanRelative, request, documentRoot)?.let {
            emitRoute(it)
            return serveResolved(it, documentRoot)
        }

        // Compatibility routes used by many classic PHP portal/CMS sources. These mappings are
        // only activated when the destination PHP file exists, so unrelated projects are not touched.
        resolvePrettyPhpRoute(cleanRelative, request, documentRoot)?.let {
            emitRoute(it)
            return serveResolved(it, documentRoot)
        }

        // Last resort for front-controller applications (Laravel-like/custom routers).
        val frontController = File(documentRoot, "index.php")
        if (frontController.isFile) {
            return executePhp(request, frontController.canonicalFile, documentRoot)
        }
        return textResponse(404, "File atau route tidak ditemukan: ${request.path}")
    }

    private fun serveResolved(resolution: RouteResolution, documentRoot: File): Response {
        val target = runCatching { resolution.file.canonicalFile }.getOrNull()
            ?: return textResponse(400, "Path tidak valid.")
        if (!isInside(documentRoot, target)) return textResponse(403, "Forbidden")
        return if (target.extension.equals("php", ignoreCase = true)) {
            executePhp(resolution.request, target, documentRoot)
        } else {
            serveStatic(target)
        }
    }

    private fun emitRoute(resolution: RouteResolution) {
        resolution.description?.let { emitLog("Pretty route • $it") }
    }

    private fun resolveExtensionlessPhp(
        cleanRelative: String,
        request: Request,
        documentRoot: File
    ): RouteResolution? {
        if (cleanRelative.isBlank() || cleanRelative.endsWith('/')) return null
        val candidate = runCatching { File(documentRoot, "$cleanRelative.php").canonicalFile }.getOrNull() ?: return null
        if (!isInside(documentRoot, candidate) || !candidate.isFile) return null
        return RouteResolution(candidate, request, "/$cleanRelative → /$cleanRelative.php")
    }

    private fun resolvePrettyPhpRoute(
        cleanRelative: String,
        request: Request,
        documentRoot: File
    ): RouteResolution? {
        val clean = cleanRelative.trim('/')
        if (clean.isBlank()) return null
        val parts = clean.split('/').filter { it.isNotBlank() }
        val first = parts.firstOrNull()?.lowercase(Locale.US) ?: return null

        fun php(fileName: String, queryKey: String? = null, queryValue: String? = null, label: String): RouteResolution? {
            val file = runCatching { File(documentRoot, fileName).canonicalFile }.getOrNull() ?: return null
            if (!isInside(documentRoot, file) || !file.isFile) return null
            val effective = if (queryKey != null && queryValue != null) {
                request.copy(query = appendQuery(request.query, queryKey, queryValue))
            } else request
            return RouteResolution(file, effective, label)
        }

        if (parts.size >= 2) {
            val value = parts.drop(1).joinToString("/")
            when (first) {
                "apk", "app" -> return php("app.php", "slug", value, "/$clean → /app.php?slug=$value")
                "kategori", "category" -> return php("category.php", "slug", value, "/$clean → /category.php?slug=$value")
                "page", "halaman" -> return php("page.php", "slug", value, "/$clean → /page.php?slug=$value")
            }
        }

        val legalSlug = when (first) {
            "tentang-kami", "tentang", "about" -> "about"
            "kontak", "contact" -> "contact"
            "kebijakan-privasi", "privacy", "privacy-policy" -> "privacy"
            "syarat-ketentuan", "terms", "terms-conditions" -> "terms"
            "disclaimer" -> "disclaimer"
            "dmca" -> "dmca"
            else -> null
        }
        if (parts.size == 1 && legalSlug != null) {
            return php("page.php", "slug", legalSlug, "/$clean → /page.php?slug=$legalSlug")
        }
        if (parts.size == 1 && first == "sitemap.xml") {
            return php("sitemap.php", label = "/sitemap.xml → /sitemap.php")
        }
        if (parts.size == 1 && first == "robots.txt") {
            return php("robots.php", label = "/robots.txt → /robots.php")
        }
        return null
    }

    private fun resolveHtaccessRewrite(
        cleanRelative: String,
        request: Request,
        documentRoot: File
    ): RouteResolution? {
        val htaccess = File(documentRoot, ".htaccess")
        if (!htaccess.isFile || htaccess.length() > 512 * 1024) return null
        val lines = runCatching { htaccess.readLines() }.getOrNull() ?: return null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#') || !line.startsWith("RewriteRule", ignoreCase = true)) continue
            val pieces = line.split(Regex("\\s+"), limit = 4)
            if (pieces.size < 3) continue
            val pattern = pieces[1]
            val replacement = pieces[2]
            if (replacement == "-" || replacement.startsWith("http://") || replacement.startsWith("https://")) continue
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
            val match = regex.matchEntire(cleanRelative) ?: continue
            var rewritten = replacement
            for (i in 1 until match.groupValues.size) rewritten = rewritten.replace("\$$i", match.groupValues[i])
            val pathPart = rewritten.substringBefore('?').removePrefix("/")
            val queryPart = rewritten.substringAfter('?', "")
            val target = runCatching { File(documentRoot, pathPart).canonicalFile }.getOrNull() ?: continue
            if (!isInside(documentRoot, target) || !target.isFile) continue
            val effectiveQuery = mergeQuery(queryPart, request.query)
            return RouteResolution(
                target,
                request.copy(query = effectiveQuery),
                "/$cleanRelative → /$rewritten (.htaccess)"
            )
        }
        return null
    }

    private fun appendQuery(existing: String, key: String, value: String): String {
        val encodedKey = java.net.URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        val encodedValue = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val addition = "$encodedKey=$encodedValue"
        return if (existing.isBlank()) addition else "$existing&$addition"
    }

    private fun mergeQuery(primary: String, secondary: String): String = when {
        primary.isBlank() -> secondary
        secondary.isBlank() -> primary
        else -> "$primary&$secondary"
    }

    private fun looksLikeStaticPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf(
            "css", "js", "mjs", "json", "xml", "svg", "png", "jpg", "jpeg", "gif", "webp", "ico",
            "woff", "woff2", "ttf", "otf", "mp4", "webm", "mp3", "wav", "ogg", "pdf", "map", "txt"
        )
    }

    private fun resolveMissingAssetFallback(path: String, documentRoot: File): File? {
        val requested = File(documentRoot, path)
        val parent = requested.parentFile ?: return null
        if (!isInside(documentRoot, parent) || !parent.isDirectory) return null
        val name = requested.name
        val ext = requested.extension.lowercase(Locale.US)
        if (ext !in setOf("svg", "png", "jpg", "jpeg", "gif", "webp", "ico")) return null

        // Database dumps often refer to a previous hashed upload while the ZIP contains a newer
        // file with the same semantic prefix (site-icon-*, home-banner-*, favicon-*).
        val stem = name.substringBeforeLast('.', name)
        val prefix = stem.replace(Regex("-[0-9a-fA-F]{8,}$"), "")
        if (prefix != stem) {
            val sibling = parent.listFiles()
                ?.filter { it.isFile && it.extension.equals(ext, true) && it.nameWithoutExtension.startsWith("$prefix-") }
                ?.maxByOrNull { it.lastModified() }
            if (sibling != null) return sibling.canonicalFile
        }

        // An exported database may contain app-icon rows while uploads/icons was not included.
        // Use the project's own placeholder for preview, but log the missing original path.
        val normalized = path.replace('\\', '/').lowercase(Locale.US)
        if (normalized.startsWith("uploads/icons/")) {
            val placeholder = File(documentRoot, "assets/default-icon.svg")
            if (placeholder.isFile) return placeholder.canonicalFile
        }
        return null
    }

    private fun executePhp(request: Request, script: File, documentRoot: File): Response {
        val bodyFile = File(runtime.tempDir, "request-${System.nanoTime()}.bin")
        return try {
            bodyFile.writeBytes(request.body)
            val headersJson = JSONObject(request.headers).toString()
            val headersB64 = Base64.getEncoder().encodeToString(headersJson.toByteArray(StandardCharsets.UTF_8))
            val scriptName = "/" + script.relativeTo(documentRoot).invariantSeparatorsPath

            val pb = ProcessBuilder(
                runtime.binary.absolutePath,
                "-c", runtime.ensureIni().absolutePath,
                runtime.ensureBridgeRunner().absolutePath
            )
            pb.directory(script.parentFile)
            pb.environment().putAll(runtime.environment())
            pb.environment()["LOCALDEV_SCRIPT"] = script.absolutePath
            pb.environment()["LOCALDEV_DOCUMENT_ROOT"] = documentRoot.absolutePath
            pb.environment()["LOCALDEV_REQUEST_METHOD"] = request.method
            pb.environment()["LOCALDEV_REQUEST_URI"] = request.target
            pb.environment()["LOCALDEV_QUERY_STRING"] = request.query
            pb.environment()["LOCALDEV_SCRIPT_NAME"] = scriptName
            pb.environment()["LOCALDEV_BODY_FILE"] = bodyFile.absolutePath
            pb.environment()["LOCALDEV_HEADERS_B64"] = headersB64
            pb.environment()["LOCALDEV_SESSION_DIR"] = runtime.sessionDir.absolutePath
            if (stickySessionId.isNotBlank()) {
                pb.environment()["LOCALDEV_FORCED_SESSION_NAME"] = stickySessionName.ifBlank { "PHPSESSID" }
                pb.environment()["LOCALDEV_FORCED_SESSION_ID"] = stickySessionId
            }
            val sqlite = File(activeProjectRoot ?: documentRoot, ".localdev/localdev.sqlite")
            if (sqlite.isFile) pb.environment()["LOCALDEV_SQLITE_PATH"] = sqlite.absolutePath
            pb.redirectErrorStream(true)

            val process = pb.start()
            val outputFuture = processOutputExecutor.submit<String> {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }

            if (!process.waitFor(PHP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return textResponse(504, "PHP timeout setelah ${PHP_TIMEOUT_SECONDS}s.")
            }

            val raw = runCatching { outputFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            if (process.exitValue() != 0 && !raw.contains(RESPONSE_MARKER)) {
                emitLog("PHP exit=${process.exitValue()} • ${raw.takeLast(1800)}")
                return Response(500, "text/html; charset=UTF-8", errorPage("PHP exit=${process.exitValue()}", raw).toByteArray())
            }

            parsePhpResponse(raw)
        } catch (t: Throwable) {
            emitLog("PHP bridge error: ${t.message ?: t.javaClass.simpleName}")
            Response(500, "text/html; charset=UTF-8", errorPage("PHP bridge error", t.message.orEmpty()).toByteArray())
        } finally {
            runCatching { bodyFile.delete() }
        }
    }

    private fun parsePhpResponse(raw: String): Response {
        val markerIndex = raw.lastIndexOf(RESPONSE_MARKER)
        if (markerIndex < 0) {
            emitLog("PHP response protocol missing • ${raw.takeLast(1600)}")
            return Response(500, "text/html; charset=UTF-8", errorPage("PHP response protocol missing", raw).toByteArray())
        }

        val payload = raw.substring(markerIndex + RESPONSE_MARKER.length).trimStart('\r', '\n')
        val divider = payload.indexOf("\n\n")
        val headerBlock: String
        val body: String
        if (divider >= 0) {
            headerBlock = payload.substring(0, divider)
            body = payload.substring(divider + 2)
        } else {
            headerBlock = payload
            body = ""
        }

        var status = 200
        var contentType = "text/html; charset=UTF-8"
        val headers = linkedMapOf<String, String>()
        headerBlock.lines().forEach { line ->
            when {
                line.startsWith("LOCALDEV_STATUS:") -> status = line.substringAfter(':').trim().toIntOrNull() ?: 200
                line.startsWith("LOCALDEV_CONTENT_TYPE:") -> contentType = line.substringAfter(':').trim().ifBlank { contentType }
                line.startsWith("LOCALDEV_SET_COOKIE:") -> {
                    val cookie = line.substringAfter(':').trim()
                    headers["Set-Cookie"] = cookie
                    rememberSetCookie(cookie)
                }
                line.startsWith("LOCALDEV_LOCATION:") -> headers["Location"] = line.substringAfter(':').trim()
                line.startsWith("LOCALDEV_SESSION_NAME:") -> {
                    val name = line.substringAfter(':').trim()
                    if (name.isNotBlank()) stickySessionName = name
                }
                line.startsWith("LOCALDEV_SESSION_ID:") -> {
                    val id = line.substringAfter(':').trim()
                    if (id.isNotBlank()) {
                        stickySessionId = id
                        fallbackCookies[stickySessionName.ifBlank { "PHPSESSID" }] = id
                    }
                }
            }
        }
        if (status in 300..399 && headers["Location"].isNullOrBlank() && body.isBlank()) {
            emitLog("PHP redirect tidak membawa Location • CLI SAPI • status=$status")
            val diagnostic = errorPage(
                "Redirect PHP belum dapat dibaca",
                "PHP CLI menerima header('Location: ...') tetapi SAPI CLI tidak mengekspos nilai Location melalui headers_list(). " +
                    "Jika halaman ini muncul setelah login, periksa Console LocalDev. v0.9.6 sudah menyinkronkan PHPSESSID agar halaman terlindungi tidak salah kembali ke login."
            )
            return Response(502, "text/html; charset=UTF-8", diagnostic.toByteArray(StandardCharsets.UTF_8), headers)
        }
        return Response(status, contentType, body.toByteArray(StandardCharsets.UTF_8), headers)
    }

    private fun serveStatic(file: File): Response {
        if (!file.isFile) return textResponse(404, "File tidak ditemukan.")
        if (file.length() > 48L * 1024L * 1024L) return textResponse(413, "Asset terlalu besar untuk preview LocalDev.")
        val mime = when (file.extension.lowercase(Locale.US)) {
            "html", "htm" -> "text/html; charset=UTF-8"
            "css" -> "text/css; charset=UTF-8"
            "js", "mjs" -> "text/javascript; charset=UTF-8"
            "json" -> "application/json; charset=UTF-8"
            "xml" -> "application/xml; charset=UTF-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
        return Response(200, mime, file.readBytes())
    }

    private fun writeResponse(output: BufferedOutputStream, response: Response, headOnly: Boolean = false) {
        val statusText = when (response.status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            504 -> "Gateway Timeout"
            else -> "LocalDev"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.status} $statusText\r\n")
            append("Server: LocalDev/0.9.6\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(header)
        if (!headOnly) output.write(response.body)
        output.flush()
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        return childPath == rootPath || childPath.startsWith(rootPath + File.separator)
    }

    private fun textResponse(status: Int, message: String) =
        Response(status, "text/plain; charset=UTF-8", message.toByteArray(StandardCharsets.UTF_8))

    private fun errorPage(title: String, details: String): String {
        val safeTitle = html(title)
        val safeDetails = html(details.takeLast(8000))
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:system-ui;background:#071018;color:#f4f8fb;padding:24px}pre{white-space:pre-wrap;background:#0d1822;border:1px solid #21384a;border-radius:14px;padding:16px;color:#ff9bab}</style></head>
            <body><h2>$safeTitle</h2><pre>$safeDetails</pre></body></html>
        """.trimIndent()
    }

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun emitLog(message: String) = main.post { onLog(message) }
    private fun emitState(message: String) = main.post { onState(message) }
}
