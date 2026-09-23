package com.webrun.php.database

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * LocalDev native MariaDB service manager.
 *
 * Runtime contract (arm64-v8a):
 * - nativeLibraryDir/liblocaldev_mariadbd.so   -> MariaDB server executable
 * - nativeLibraryDir/liblocaldev_mariadb.so    -> MariaDB CLI executable
 * - assets/localdev-mariadb/seed/...           -> initialized MariaDB datadir seed
 *
 * Executables are packaged as native libraries on purpose. Android 10+ does not
 * allow apps to execute arbitrary writable files from app-private storage.
 */
class NativeDatabaseManager(private val context: Context) {

    companion object {
        const val HOST = "127.0.0.1"
        const val TCP_HOST = "127.0.0.1"
        const val PORT = 3306
        const val DATABASE = "localdev"
        const val USER = "localdev"
        const val PASSWORD = "localdev"
        private const val START_TIMEOUT_MS = 12_000L
    }

    data class Diagnostics(
        val serverBundled: Boolean,
        val clientBundled: Boolean,
        val seedBundled: Boolean,
        val dataInitialized: Boolean,
        val running: Boolean,
        val portOpen: Boolean,
        val serverPath: String,
        val clientPath: String,
        val socketPath: String,
        val logTail: String
    ) {
        val runtimeBundled: Boolean get() = serverBundled && clientBundled && seedBundled
        val ready: Boolean get() = runtimeBundled && dataInitialized && running && portOpen
    }

    data class SetupReport(
        val started: Boolean,
        val databaseCreated: Boolean,
        val importedSql: Boolean,
        val sqlFile: String?,
        val message: String
    )

    private var process: Process? = null

    val runtimeRoot: File get() = File(context.filesDir, "mariadb-runtime").apply { mkdirs() }
    val dataDir: File get() = File(runtimeRoot, "data").apply { mkdirs() }
    val runDir: File get() = File(runtimeRoot, "run").apply { mkdirs() }
    val tempDir: File get() = File(runtimeRoot, "tmp").apply { mkdirs() }
    val socketFile: File get() = File(runDir, "mariadb.sock")
    val pidFile: File get() = File(runDir, "mariadb.pid")
    val logFile: File get() = File(runtimeRoot, "mariadb-error.log")
    val cnfFile: File get() = File(runtimeRoot, "my.cnf")
    val initMarker: File get() = File(dataDir, ".localdev-seed-ready")

    val serverBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "liblocaldev_mariadbd.so")

    val clientBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "liblocaldev_mariadb.so")

    fun configSnippet(): String = """
        'host' => '$HOST',
        'name' => '$DATABASE',
        'user' => '$USER',
        'pass' => '$PASSWORD',
        'charset' => 'utf8mb4',
    """.trimIndent()

    fun dsnExample(): String = "mysql:host=$HOST;dbname=$DATABASE;charset=utf8mb4"

    fun ensureConfig(): File {
        val content = """
            [client]
            host=$TCP_HOST
            port=$PORT
            socket=${socketFile.absolutePath}
            protocol=tcp
            default-character-set=utf8mb4

            [mariadb]
            datadir=${dataDir.absolutePath}
            socket=${socketFile.absolutePath}
            pid-file=${pidFile.absolutePath}
            port=$PORT
            bind-address=$TCP_HOST
            skip-name-resolve
            skip-networking=0
            skip-grant-tables
            character-set-server=utf8mb4
            collation-server=utf8mb4_unicode_ci
            max-connections=20
            innodb-buffer-pool-size=32M
            tmpdir=${tempDir.absolutePath}
            log-error=${logFile.absolutePath}
            log-bin=0
            performance-schema=0
        """.trimIndent() + "\n"
        val file = cnfFile
        if (!file.exists() || file.readText() != content) file.writeText(content)
        return file
    }

    /** Copies an initialized MariaDB seed datadir from APK assets on first use. */
    @Synchronized
    fun ensureSeed(): Boolean {
        if (initMarker.isFile) return true
        if (!seedBundled()) return false

        if (dataDir.exists()) dataDir.deleteRecursively()
        dataDir.mkdirs()
        copyAssetTree("localdev-mariadb/seed", dataDir)
        initMarker.writeText("LocalDev MariaDB seed initialized\n")
        return true
    }

    @Synchronized
    fun start(onLog: ((String) -> Unit)? = null): Boolean {
        if (isPortOpen()) return true
        require(serverBinary.isFile) { "MariaDB ARM64 server belum dibundel di APK." }
        require(clientBinary.isFile) { "MariaDB client ARM64 belum dibundel di APK." }
        require(ensureSeed()) { "MariaDB seed datadir belum dibundel di APK." }
        ensureConfig()

        process?.destroy()
        process = null

        val pb = ProcessBuilder(
            serverBinary.absolutePath,
            "--defaults-file=${cnfFile.absolutePath}"
        )
        pb.directory(runtimeRoot)
        pb.environment()["HOME"] = runtimeRoot.absolutePath
        pb.environment()["TMPDIR"] = tempDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        pb.redirectErrorStream(true)
        val p = pb.start()
        process = p

        Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onLog?.invoke("MariaDB: $line") }
                }
            }
        }.start()

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen()) return true
            if (!p.isAlive) break
            Thread.sleep(160)
        }

        if (!isPortOpen()) {
            val tail = logTail()
            throw IllegalStateException(
                "MariaDB gagal start pada $TCP_HOST:$PORT" + if (tail.isBlank()) "." else ".\n$tail"
            )
        }
        return true
    }

    @Synchronized
    fun stop() {
        runCatching {
            if (isPortOpen() && clientBinary.isFile) {
                executeClient("SHUTDOWN;", database = null, timeoutSeconds = 3)
            }
        }
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                runCatching { p.waitFor(2, TimeUnit.SECONDS) }
                if (p.isAlive) p.destroyForcibly()
            }
        }
        process = null
    }

    fun setupDefaultDatabase(sqlFile: File?, onLog: ((String) -> Unit)? = null): SetupReport {
        start(onLog)
        val create = "CREATE DATABASE IF NOT EXISTS `$DATABASE` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        val createResult = executeClient(create, database = null, timeoutSeconds = 8)
        if (createResult.exitCode != 0) {
            throw IllegalStateException("Gagal membuat database $DATABASE: ${createResult.output.takeLast(1200)}")
        }

        var imported = false
        if (sqlFile != null) {
            require(sqlFile.isFile) { "File SQL tidak ditemukan: ${sqlFile.absolutePath}" }
            require(sqlFile.length() <= 256L * 1024L * 1024L) { "File SQL lebih dari 256 MB." }
            val importResult = importSql(sqlFile, DATABASE)
            if (importResult.exitCode != 0) {
                throw IllegalStateException("Import ${sqlFile.name} gagal: ${importResult.output.takeLast(1800)}")
            }
            imported = true
        }

        return SetupReport(
            started = true,
            databaseCreated = true,
            importedSql = imported,
            sqlFile = sqlFile?.name,
            message = if (imported) "MariaDB READY • ${sqlFile?.name} imported" else "MariaDB READY • database $DATABASE tersedia"
        )
    }

    fun importSql(sqlFile: File, database: String = DATABASE): ClientResult {
        require(clientBinary.isFile) { "MariaDB client belum dibundel." }
        val args = mutableListOf(
            clientBinary.absolutePath,
            "--protocol=TCP",
            "--host=$TCP_HOST",
            "--port=$PORT",
            "--user=$USER",
            "--password=$PASSWORD",
            "--default-character-set=utf8mb4",
            database
        )
        val pb = ProcessBuilder(args)
        pb.directory(runtimeRoot)
        pb.environment()["HOME"] = runtimeRoot.absolutePath
        pb.environment()["TMPDIR"] = tempDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val outputFile = File(tempDir, "mariadb-import-${System.nanoTime()}.log")
        pb.redirectInput(sqlFile)
        pb.redirectErrorStream(true)
        pb.redirectOutput(outputFile)
        val p = pb.start()
        val done = p.waitFor(90, TimeUnit.SECONDS)
        if (!done) {
            p.destroyForcibly()
            val out = runCatching { outputFile.readText() }.getOrDefault("")
            outputFile.delete()
            return ClientResult(124, "Import SQL timeout.\n$out")
        }
        val out = runCatching { outputFile.readText() }.getOrDefault("")
        outputFile.delete()
        return ClientResult(p.exitValue(), out)
    }

    data class ClientResult(val exitCode: Int, val output: String)

    fun executeClient(sql: String, database: String? = DATABASE, timeoutSeconds: Long = 10): ClientResult {
        require(clientBinary.isFile) { "MariaDB client belum dibundel." }
        val args = mutableListOf(
            clientBinary.absolutePath,
            "--protocol=TCP",
            "--host=$TCP_HOST",
            "--port=$PORT",
            "--user=$USER",
            "--password=$PASSWORD",
            "--default-character-set=utf8mb4",
            "--batch",
            "--skip-column-names"
        )
        if (!database.isNullOrBlank()) args += database
        args += listOf("-e", sql)

        val pb = ProcessBuilder(args)
        pb.directory(runtimeRoot)
        pb.environment()["HOME"] = runtimeRoot.absolutePath
        pb.environment()["TMPDIR"] = tempDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val outputFile = File(tempDir, "mariadb-client-${System.nanoTime()}.log")
        pb.redirectErrorStream(true)
        pb.redirectOutput(outputFile)
        val p = pb.start()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            val out = runCatching { outputFile.readText() }.getOrDefault("")
            outputFile.delete()
            return ClientResult(124, "MariaDB client timeout.\n$out")
        }
        val out = runCatching { outputFile.readText() }.getOrDefault("")
        outputFile.delete()
        return ClientResult(p.exitValue(), out)
    }

    fun diagnostics(): Diagnostics {
        val port = isPortOpen()
        return Diagnostics(
            serverBundled = serverBinary.isFile,
            clientBundled = clientBinary.isFile,
            seedBundled = seedBundled(),
            dataInitialized = initMarker.isFile,
            running = process?.isAlive == true || port,
            portOpen = port,
            serverPath = serverBinary.absolutePath,
            clientPath = clientBinary.absolutePath,
            socketPath = socketFile.absolutePath,
            logTail = logTail()
        )
    }

    fun resetData() {
        stop()
        if (runtimeRoot.exists()) runtimeRoot.deleteRecursively()
    }

    private fun isPortOpen(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(TCP_HOST, PORT), 160)
                true
            }
        }.getOrDefault(false)
    }

    private fun seedBundled(): Boolean {
        return runCatching { context.assets.list("localdev-mariadb/seed")?.isNotEmpty() == true }.getOrDefault(false)
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            return
        }
        dest.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(dest, child)) }
    }

    private fun logTail(): String {
        if (!logFile.isFile) return ""
        return runCatching {
            val text = logFile.readText()
            if (text.length <= 3000) text else text.takeLast(3000)
        }.getOrDefault("")
    }
}
