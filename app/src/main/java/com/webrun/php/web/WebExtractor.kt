package com.webrun.php.web

import android.content.Context
import com.webrun.php.runtime.PhpRuntimeManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WebExtractor(private val context: Context) {

    data class Report(
        val ok: Boolean,
        val sourceUrl: String = "",
        val finalUrl: String = "",
        val title: String = "",
        val htmlFile: File? = null,
        val cssFiles: List<File> = emptyList(),
        val assetCount: Int = 0,
        val assetBytes: Long = 0,
        val warnings: List<String> = emptyList(),
        val error: String = "",
        val raw: String = ""
    )

    private val runtime = PhpRuntimeManager(context)

    fun extract(
        url: String,
        destination: File,
        downloadAssets: Boolean,
        timeoutSeconds: Long = 120
    ): Report {
        val diagnostics = runtime.diagnostics(timeoutSeconds = 7)
        if (!diagnostics.available) {
            return Report(false, error = diagnostics.error.ifBlank { "PHP runtime tidak tersedia." })
        }
        if (!diagnostics.curl) {
            return Report(false, error = "Web Extractor membutuhkan extension PHP cURL.")
        }

        return try {
            if (destination.exists()) destination.deleteRecursively()
            destination.mkdirs()
            require(destination.isDirectory) { "Folder output Web Extractor tidak dapat dibuat." }

            val script = ensureScript()
            val pb = ProcessBuilder(
                runtime.binary.absolutePath,
                "-c", runtime.ensureIni().absolutePath,
                script.absolutePath,
                url.trim(),
                destination.absolutePath,
                if (downloadAssets) "1" else "0"
            )
            pb.environment().putAll(runtime.environment())
            pb.redirectErrorStream(true)

            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Report(false, error = "Web Extractor timeout setelah ${timeoutSeconds} detik.")
            }

            val raw = process.inputStream.bufferedReader().readText().trim()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) {
                return Report(false, error = "Output Web Extractor bukan JSON.", raw = raw)
            }

            val obj = JSONObject(raw.substring(start, end + 1))
            val warnings = buildList {
                val array = obj.optJSONArray("warnings")
                if (array != null) {
                    for (i in 0 until array.length()) add(array.optString(i))
                }
            }

            if (!obj.optBoolean("ok")) {
                return Report(
                    ok = false,
                    warnings = warnings,
                    error = obj.optString("error").ifBlank { "Web Extractor gagal." },
                    raw = raw
                )
            }

            val htmlRelative = obj.optString("html_file", "index.html")
            val html = File(destination, htmlRelative)
            val css = buildList {
                val array = obj.optJSONArray("css_files")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val rel = array.optString(i)
                        if (rel.isNotBlank()) {
                            val file = File(destination, rel)
                            if (file.isFile) add(file)
                        }
                    }
                }
            }

            if (!html.isFile) {
                return Report(false, error = "index.html hasil ekstrak tidak ditemukan.", raw = raw)
            }

            Report(
                ok = true,
                sourceUrl = obj.optString("source_url"),
                finalUrl = obj.optString("final_url"),
                title = obj.optString("title"),
                htmlFile = html,
                cssFiles = css,
                assetCount = obj.optInt("asset_count", 0),
                assetBytes = obj.optLong("asset_bytes", 0L),
                warnings = warnings,
                raw = raw
            )
        } catch (t: Throwable) {
            Report(false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun ensureScript(): File {
        val dir = File(context.filesDir, "web-extractor-runtime").apply { mkdirs() }
        val out = File(dir, "web_extractor.php")
        val bytes = context.assets.open("web_extractor.php").use { it.readBytes() }
        if (!out.exists() || !out.readBytes().contentEquals(bytes)) {
            out.writeBytes(bytes)
        }
        return out
    }
}
