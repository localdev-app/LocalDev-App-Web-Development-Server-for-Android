package com.webrun.php.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PhpRuntimeManager(private val context: Context) {

    data class Diagnostics(
        val available: Boolean,
        val version: String = "",
        val phpVersionId: Int = 0,
        val pdoSqlite: Boolean = false,
        val sqlite3: Boolean = false,
        val curl: Boolean = false,
        val openssl: Boolean = false,
        val zip: Boolean = false,
        val session: Boolean = false,
        val pdoMysql: Boolean = false,
        val mysqli: Boolean = false,
        val raw: String = "",
        val error: String = ""
    ) {
        val allTargetExtensionsReady: Boolean
            get() = pdoSqlite && sqlite3 && curl && openssl && zip

        val nativeMysqlDriverReady: Boolean
            get() = pdoMysql || mysqli

        val nativePdoMysqlReady: Boolean
            get() = pdoMysql
    }

    val binary: File
        get() {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            val preferred = File(dir, "liblocaldev_php.so")
            if (preferred.exists()) return preferred
            // Compatibility with older test builds.
            return File(dir, "libwebrun_php.so")
        }

    val runtimeDir: File
        get() = File(context.filesDir, "php-runtime").apply { mkdirs() }

    val sessionDir: File
        get() = File(runtimeDir, "sessions").apply { mkdirs() }

    val tempDir: File
        get() = File(runtimeDir, "tmp").apply { mkdirs() }

    val logFile: File
        get() = File(runtimeDir, "php-error.log")

    val iniFile: File
        get() = File(runtimeDir, "php.ini")

    val caBundleFile: File
        get() = File(runtimeDir, "cacert.pem")

    fun ensureCaBundle(): File {
        val out = caBundleFile
        val bytes = context.assets.open("cacert.pem").use { it.readBytes() }
        if (!out.exists() || !out.readBytes().contentEquals(bytes)) {
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        return out
    }

    fun ensureIni(): File {
        val ini = iniFile
        val content = """
            display_errors=1
            display_startup_errors=1
            html_errors=1
            log_errors=1
            error_reporting=E_ALL
            error_log=${escapeIni(logFile.absolutePath)}
            session.save_path=${escapeIni(sessionDir.absolutePath)}
            upload_tmp_dir=${escapeIni(tempDir.absolutePath)}
            sys_temp_dir=${escapeIni(tempDir.absolutePath)}
            short_open_tag=0
            allow_url_fopen=1
            default_socket_timeout=20
            mysqli.default_host=localhost
            mysqli.default_port=3306
            mysqli.default_socket=${escapeIni(File(context.filesDir, "mariadb-runtime/run/mariadb.sock").absolutePath)}
            pdo_mysql.default_socket=${escapeIni(File(context.filesDir, "mariadb-runtime/run/mariadb.sock").absolutePath)}
            curl.cainfo=${escapeIni(ensureCaBundle().absolutePath)}
            openssl.cafile=${escapeIni(ensureCaBundle().absolutePath)}
            max_execution_time=30
            memory_limit=192M
            post_max_size=32M
            upload_max_filesize=32M
            date.timezone=UTC
        """.trimIndent() + "\n"
        if (!ini.exists() || ini.readText() != content) ini.writeText(content)
        return ini
    }

    fun ensureBridgeRunner(): File {
        val out = File(runtimeDir, "localdev_php_bridge.php")
        val bytes = context.assets.open("localdev_php_bridge.php").use { it.readBytes() }
        if (!out.exists() || !out.readBytes().contentEquals(bytes)) {
            out.writeBytes(bytes)
        }
        return out
    }

    fun environment(): MutableMap<String, String> = mutableMapOf(
        "PHPRC" to ensureIni().absolutePath,
        "TMPDIR" to tempDir.absolutePath,
        "HOME" to runtimeDir.absolutePath,
        "SSL_CERT_FILE" to ensureCaBundle().absolutePath,
        "CURL_CA_BUNDLE" to ensureCaBundle().absolutePath
    )

    fun diagnostics(timeoutSeconds: Long = 7): Diagnostics {
        if (!binary.exists()) {
            return Diagnostics(
                available = false,
                error = "PHP runtime tidak tersedia pada build LocalDev ini."
            )
        }

        return try {
            val php = """
                echo json_encode([
                  'version' => PHP_VERSION,
                  'version_id' => PHP_VERSION_ID,
                  'pdo_sqlite' => extension_loaded('pdo_sqlite'),
                  'sqlite3' => extension_loaded('sqlite3'),
                  'curl' => extension_loaded('curl'),
                  'openssl' => extension_loaded('openssl'),
                  'zip' => extension_loaded('zip'),
                  'session' => extension_loaded('session'),
                  'pdo_mysql' => extension_loaded('pdo_mysql'),
                  'mysqli' => extension_loaded('mysqli')
                ], JSON_UNESCAPED_SLASHES);
            """.trimIndent()

            val pb = ProcessBuilder(
                binary.absolutePath,
                "-c", ensureIni().absolutePath,
                "-r", php
            )
            pb.environment().putAll(environment())
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Diagnostics(available = false, error = "Pemeriksaan runtime timeout.")
            }

            val raw = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() != 0) {
                return Diagnostics(available = false, raw = raw, error = "Runtime keluar dengan kode ${process.exitValue()}.")
            }

            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end >= start) { "Output runtime bukan JSON: $raw" }
            val obj = JSONObject(raw.substring(start, end + 1))
            Diagnostics(
                available = true,
                version = obj.optString("version"),
                phpVersionId = obj.optInt("version_id"),
                pdoSqlite = obj.optBoolean("pdo_sqlite"),
                sqlite3 = obj.optBoolean("sqlite3"),
                curl = obj.optBoolean("curl"),
                openssl = obj.optBoolean("openssl"),
                zip = obj.optBoolean("zip"),
                session = obj.optBoolean("session"),
                pdoMysql = obj.optBoolean("pdo_mysql"),
                mysqli = obj.optBoolean("mysqli"),
                raw = raw
            )
        } catch (t: Throwable) {
            Diagnostics(available = false, error = t.message ?: t.javaClass.simpleName)
        }
    }


    data class MysqlTcpTest(
        val ok: Boolean,
        val driver: String = "",
        val version: String = "",
        val database: String = "",
        val prepared: String = "",
        val error: String = "",
        val raw: String = ""
    )

    /**
     * End-to-end PDO MySQL test against LocalDevDB / MariaDB over TCP.
     * Using 127.0.0.1 is deliberate: PDO treats host=localhost as a Unix-socket request.
     */
    fun testMysqlTcp(
        host: String = "127.0.0.1",
        port: Int = 3306,
        database: String = "localdev",
        user: String = "localdev",
        password: String = "localdev",
        timeoutSeconds: Long = 7
    ): MysqlTcpTest {
        if (!binary.exists()) return MysqlTcpTest(false, error = "PHP runtime tidak tersedia.")
        val php = """
            try {
                ${'$'}dsn = 'mysql:host=${host};port=${port};dbname=${database};charset=utf8mb4';
                ${'$'}pdo = new PDO(${'$'}dsn, '${user.replace("'", "\'")}', '${password.replace("'", "\'")}', [
                    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                    PDO::ATTR_EMULATE_PREPARES => false,
                ]);
                ${'$'}version = (string) ${'$'}pdo->query('SELECT VERSION()')->fetchColumn();
                ${'$'}db = (string) ${'$'}pdo->query('SELECT DATABASE()')->fetchColumn();
                ${'$'}st = ${'$'}pdo->prepare('SELECT ? AS localdev_probe');
                ${'$'}st->execute(['prepared-ok']);
                ${'$'}prepared = (string) ${'$'}st->fetchColumn();
                echo json_encode([
                    'ok' => true,
                    'driver' => (string) ${'$'}pdo->getAttribute(PDO::ATTR_DRIVER_NAME),
                    'version' => ${'$'}version,
                    'database' => ${'$'}db,
                    'prepared' => ${'$'}prepared,
                ], JSON_UNESCAPED_SLASHES);
            } catch (Throwable ${'$'}e) {
                echo json_encode(['ok' => false, 'error' => ${'$'}e->getMessage()], JSON_UNESCAPED_SLASHES);
                exit(2);
            }
        """.trimIndent()
        return try {
            val pb = ProcessBuilder(binary.absolutePath, "-c", ensureIni().absolutePath, "-r", php)
            pb.environment().putAll(environment())
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return MysqlTcpTest(false, error = "PDO MySQL TCP test timeout.")
            }
            val raw = process.inputStream.bufferedReader().readText().trim()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) return MysqlTcpTest(false, error = "Output test bukan JSON.", raw = raw)
            val obj = JSONObject(raw.substring(start, end + 1))
            MysqlTcpTest(
                ok = obj.optBoolean("ok"),
                driver = obj.optString("driver"),
                version = obj.optString("version"),
                database = obj.optString("database"),
                prepared = obj.optString("prepared"),
                error = obj.optString("error"),
                raw = raw
            )
        } catch (t: Throwable) {
            MysqlTcpTest(false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun escapeIni(path: String): String = "\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
