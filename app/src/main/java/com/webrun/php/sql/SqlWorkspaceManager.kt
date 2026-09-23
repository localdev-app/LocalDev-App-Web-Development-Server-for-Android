package com.webrun.php.sql

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

class SqlWorkspaceManager(private val context: Context) {

    data class ProjectSqlInfo(
        val sqlFiles: List<File>,
        val mysqlReferences: Int,
        val pdoMysqlReferences: Int,
        val mysqliReferences: Int,
        val probableDatabaseNames: List<String>
    ) {
        val hasSql: Boolean get() = sqlFiles.isNotEmpty()
        val likelyNeedsMysql: Boolean get() = mysqlReferences > 0 || pdoMysqlReferences > 0 || mysqliReferences > 0
    }

    data class ImportReport(
        val databaseFile: File,
        val sourceFile: File,
        val totalStatements: Int,
        val executed: Int,
        val skipped: Int,
        val failed: Int,
        val tableCount: Int,
        val errors: List<String>
    )

    data class TableInfo(val name: String, val rows: Long)

    data class QueryResult(
        val text: String,
        val rows: Int = 0,
        val columns: Int = 0
    )

    data class PatchReport(
        val changedFiles: List<File>,
        val backupRoot: File,
        val adapterFile: File,
        val warnings: List<String>
    )


    data class MysqlTcpProfileReport(
        val changedFiles: List<File>,
        val backupRoot: File,
        val notes: List<String>
    )

    fun analyze(root: File): ProjectSqlInfo {
        val sqlFiles = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("sql", true) && it.length() <= 64L * 1024L * 1024L }
            .sortedWith(compareBy<File>({ !it.name.equals("database.sql", true) }, { it.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.US) }))
            .toList()

        var mysqlRefs = 0
        var pdoMysqlRefs = 0
        var mysqliRefs = 0
        val dbNames = linkedSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("php", true) && it.length() <= 2L * 1024L * 1024L }
            .take(500)
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                mysqlRefs += Regex("(?i)mysql:host=|mysql\\s*:").findAll(text).count()
                pdoMysqlRefs += Regex("(?i)pdo_mysql|PDO::MYSQL_ATTR_|new\\s+PDO\\s*\\(\\s*['\"]mysql:|mysql:host=").findAll(text).count()
                mysqliRefs += Regex("(?i)mysqli(?:_|\\s*\\()").findAll(text).count()
                Regex("(?i)dbname\\s*=\\s*([A-Za-z0-9_-]+)").findAll(text).forEach { m -> dbNames += m.groupValues[1] }
                Regex("(?i)['\"](?:database|dbname|name)['\"]\\s*=>\\s*['\"]([A-Za-z0-9_-]+)['\"]").findAll(text).forEach { m -> dbNames += m.groupValues[1] }
            }

        return ProjectSqlInfo(sqlFiles, mysqlRefs, pdoMysqlRefs, mysqliRefs, dbNames.toList())
    }

    fun localDevDir(root: File): File = File(root, ".localdev").apply { mkdirs() }

    fun databaseFile(root: File): File = File(localDevDir(root), "localdev.sqlite")

    fun hasDatabase(root: File): Boolean = databaseFile(root).isFile

    fun importSqlToSqlite(root: File, sqlFile: File): ImportReport {
        require(sqlFile.isFile) { "SQL file tidak ditemukan." }
        require(sqlFile.length() <= 64L * 1024L * 1024L) { "SQL file lebih dari 64 MB." }
        val target = databaseFile(root)
        if (target.exists()) target.delete()
        target.parentFile?.mkdirs()

        val statements = SqlDialect.splitStatements(sqlFile.readText())
        var executed = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val db = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.beginTransaction()
            try {
                statements.forEachIndexed { index, original ->
                    val alterIndexes = SqlDialect.translateAlterIndexes(original)
                    if (alterIndexes.isNotEmpty()) {
                        alterIndexes.forEach { indexSql ->
                            try {
                                db.execSQL(indexSql)
                                executed++
                            } catch (t: Throwable) {
                                failed++
                                if (errors.size < 20) {
                                    errors += "#${index + 1}: ${t.message ?: t.javaClass.simpleName} | ${indexSql.replace('\n', ' ').take(180)}"
                                }
                            }
                        }
                        return@forEachIndexed
                    }

                    val translated = SqlDialect.translateMysqlToSqlite(original)
                    val sql = translated.sql
                    if (sql.isNullOrBlank()) {
                        skipped++
                        return@forEachIndexed
                    }
                    try {
                        db.execSQL(sql)
                        executed++
                    } catch (t: Throwable) {
                        failed++
                        if (errors.size < 20) {
                            errors += "#${index + 1}: ${t.message ?: t.javaClass.simpleName} | ${sql.replace('\n', ' ').take(180)}"
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        return ImportReport(
            databaseFile = target,
            sourceFile = sqlFile,
            totalStatements = statements.size,
            executed = executed,
            skipped = skipped,
            failed = failed,
            tableCount = tables(target).size,
            errors = errors
        )
    }

    fun tables(database: File): List<TableInfo> {
        if (!database.isFile) return emptyList()
        val out = mutableListOf<TableInfo>()
        val db = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).orEmpty()
                    val count = runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM `${name.replace("`", "``")}`", null).use { c ->
                            if (c.moveToFirst()) c.getLong(0) else 0L
                        }
                    }.getOrDefault(-1L)
                    out += TableInfo(name, count)
                }
            }
        } finally {
            db.close()
        }
        return out
    }

    fun query(database: File, sql: String, maxRows: Int = 100): QueryResult {
        require(database.isFile) { "Database LocalDev belum dibuat." }
        val trimmed = sql.trim()
        require(trimmed.isNotEmpty()) { "Query kosong." }
        val db = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val read = Regex("(?i)^(SELECT|PRAGMA|WITH|EXPLAIN)\\b").containsMatchIn(trimmed)
            if (!read) {
                db.execSQL(trimmed)
                return QueryResult("Query berhasil dijalankan.")
            }
            db.rawQuery(trimmed, null).use { cursor ->
                return cursorToText(cursor, maxRows)
            }
        } finally {
            db.close()
        }
    }

    fun generatePhpAdapter(root: File): File {
        val adapter = File(localDevDir(root), "localdev-db.php")
        adapter.writeText(
            """<?php
/** LocalDev generated SQLite adapter. Keep production config separate. */
function localdev_db_path(): string {
    ${'$'}env = getenv('LOCALDEV_SQLITE_PATH');
    if (is_string(${'$'}env) && ${'$'}env !== '') return ${'$'}env;
    return __DIR__ . '/localdev.sqlite';
}
function localdev_pdo(): PDO {
    ${'$'}pdo = new PDO('sqlite:' . localdev_db_path());
    ${'$'}pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    ${'$'}pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    ${'$'}pdo->exec('PRAGMA foreign_keys=ON');
    return ${'$'}pdo;
}
"""
        )
        return adapter
    }

    /**
     * Opt-in compatibility patch for common PDO projects.
     * Backups are always written first. v0.8.2 also understands a common config.php
     * pattern that only returns an array with db host/name/user/pass and contains no PDO.
     */
    fun patchCommonPdoConfig(root: File): PatchReport {
        val backupRoot = File(root, ".localdev-backup/${System.currentTimeMillis()}").apply { mkdirs() }
        val changed = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        val adapter = generatePhpAdapter(root)

        val candidates = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("php", true) && it.length() <= 2L * 1024L * 1024L }
            .take(500)
            .toList()

        // 1) Patch config-only files such as:
        // return array('db' => array('host' => ..., 'name' => ..., ...), 'site_url' => ...);
        // These files contain no PDO call, so older LocalDev versions could not patch them.
        for (file in candidates) {
            val original = runCatching { file.readText() }.getOrNull() ?: continue
            if (original.contains("LOCALDEV_SQLITE_PATH")) continue
            if (!Regex("(?i)['\\\"]db['\\\"]\\s*=>").containsMatchIn(original)) continue
            if (!Regex("(?i)['\\\"]host['\\\"]\\s*=>").containsMatchIn(original)) continue
            if (!Regex("(?i)['\\\"](?:name|dbname|database)['\\\"]\\s*=>").containsMatchIn(original)) continue

            val returnMatch = Regex("(?s)\\breturn\\s+(array\\s*\\(|\\[)").find(original) ?: continue
            val lastSemicolon = original.lastIndexOf(';')
            if (lastSemicolon <= returnMatch.range.last) continue

            val prefix = original.substring(0, returnMatch.range.first)
            // Do not use a regex replacement string here. The PHP variable begins with "$",
            // which java.util.regex treats as a capture-group reference and throws
            // "Illegal group reference". The first capture already contains exactly
            // the opening array expression ("array(" or "[").
            val expressionStart = "${'$'}__localdev_config = " + returnMatch.groupValues[1]
            val middle = original.substring(returnMatch.range.last + 1, lastSemicolon + 1)
            val suffix = original.substring(lastSemicolon + 1)

            val override = """

// LocalDev local database override. Production values above stay untouched.
${'$'}__localdev_sqlite = getenv('LOCALDEV_SQLITE_PATH');
if (is_string(${'$'}__localdev_sqlite) && ${'$'}__localdev_sqlite !== '' && is_array(${'$'}__localdev_config)) {
    if (!isset(${'$'}__localdev_config['db']) || !is_array(${'$'}__localdev_config['db'])) {
        ${'$'}__localdev_config['db'] = array();
    }
    ${'$'}__localdev_config['db']['driver'] = 'sqlite';
    ${'$'}__localdev_config['db']['path'] = ${'$'}__localdev_sqlite;
    ${'$'}__localdev_config['site_url'] = 'http://127.0.0.1:8080';
}
return ${'$'}__localdev_config;
""".trimIndent()

            val patched = prefix + expressionStart + middle + override + suffix
            if (patched != original) {
                backupFile(root, backupRoot, file, original)
                file.writeText(patched)
                changed += file
            }
        }

        // 2) Patch actual connection/bootstrap files.
        for (file in candidates) {
            val original = runCatching { file.readText() }.getOrNull() ?: continue
            if (!original.contains("PDO")) continue
            if (!Regex("(?i)mysql:host=|mysql:").containsMatchIn(original)) continue

            var patched = original
            var dsnReplaced = false

            // Multiline: $dsn = sprintf('mysql:host=%s;dbname=%s;charset=%s', ...);
            val sprintfPattern = Regex(
                "(?s)(\\${'$'}dsn\\s*=\\s*)sprintf\\s*\\(\\s*(['\\\"])mysql:[\\s\\S]*?\\);"
            )
            patched = sprintfPattern.replace(patched) { match ->
                dsnReplaced = true
                val assignment = match.groupValues[1]
                val originalExpr = match.value.substringAfter('=').trim().removeSuffix(";")
                assignment + "(((${'$'}db['driver'] ?? 'mysql') === 'sqlite') ? ('sqlite:' . ${'$'}db['path']) : " + originalExpr + ");"
            }

            // Single-line variable DSN.
            if (!dsnReplaced) {
                val lines = mutableListOf<String>()
                for (line in patched.lines()) {
                    val lower = line.lowercase(Locale.US)
                    if (line.contains("${'$'}dsn") && lower.contains("mysql:") &&
                        (lower.contains("sprintf") || lower.contains("mysql:host="))) {
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        lines += indent + "${'$'}dsn = (((${'$'}db['driver'] ?? 'mysql') === 'sqlite') ? ('sqlite:' . ${'$'}db['path']) : " +
                            line.substringAfter('=').trim().removeSuffix(";") + ");"
                        dsnReplaced = true
                    } else {
                        lines += line
                    }
                }
                patched = lines.joinToString("\n")
            }

            // Direct new PDO('mysql:...') fallback.
            if (!dsnReplaced) {
                val directPattern = Regex("(?i)new\\s+PDO\\s*\\(\\s*(['\\\"])mysql:[^'\\\"]*\\1")
                val replaced = directPattern.replace(patched) {
                    dsnReplaced = true
                    "new PDO((getenv('LOCALDEV_SQLITE_PATH') ? ('sqlite:' . getenv('LOCALDEV_SQLITE_PATH')) : " + it.value.substringAfter("new PDO(") + ")"
                }
                patched = replaced
            }

            // mysql-only options can fatal when pdo_mysql is not present. Remove common standalone lines.
            patched = patched.lines()
                .filterNot { Regex("PDO::MYSQL_ATTR_[A-Z0-9_]+").containsMatchIn(it) }
                .joinToString("\n")

            if (dsnReplaced && patched != original) {
                if (!changed.contains(file)) backupFile(root, backupRoot, file, original)
                file.writeText(patched)
                if (!changed.contains(file)) changed += file
            } else if (!dsnReplaced) {
                warnings += "Tidak dipatch otomatis: ${file.relativeTo(root).invariantSeparatorsPath} (pola DSN kompleks)"
            }
        }

        if (changed.isEmpty()) {
            warnings += "Tidak ada config PDO/MySQL umum yang aman untuk dipatch otomatis. Gunakan adapter .localdev/localdev-db.php secara manual."
        }
        return PatchReport(changed, backupRoot, adapter, warnings)
    }


    /**
     * Makes only the imported LocalDev copy use the TCP database endpoint.
     * PDO MySQL special-cases host=localhost as a Unix socket, while LocalDevDB
     * listens on loopback TCP. Production values are backed up before changes.
     *
     * We intentionally do NOT rewrite db name/user/password: LocalDevDB accepts
     * the project's existing local credentials and maps the selected schema to
     * the same project database. This keeps the patch small and reversible.
     */
    fun applyMysqlTcpProfile(root: File): MysqlTcpProfileReport {
        val backupRoot = File(root, ".localdev-backup/mysql-profile-${System.currentTimeMillis()}").apply { mkdirs() }
        val changed = mutableListOf<File>()
        val notes = mutableListOf<String>()

        val candidates = root.walkTopDown()
            .filter {
                it.isFile && it.length() <= 2L * 1024L * 1024L &&
                    (it.name.equals("config.php", true) || it.name.equals(".env", true) || it.name.equals(".env.local", true))
            }
            .take(40)
            .toList()

        for (file in candidates) {
            val original = runCatching { file.readText() }.getOrNull() ?: continue
            var patched = original

            if (file.name.startsWith(".env", true)) {
                patched = replaceEnv(patched, "DB_HOST", "127.0.0.1")
                patched = replaceEnv(patched, "DB_PORT", "3306")
                patched = replaceEnv(patched, "APP_URL", "http://127.0.0.1:8080")
            } else {
                // Only touch the host key after a db/database section marker when possible.
                val dbMarker = Regex("(?i)['\\\"](?:db|database)['\\\"]\\s*=>").find(patched)
                if (dbMarker != null) {
                    val tailStart = dbMarker.range.first
                    val tailEnd = (tailStart + 2200).coerceAtMost(patched.length)
                    val tail = patched.substring(tailStart, tailEnd)
                    val hostMatch = Regex("(?i)(['\\\"]host['\\\"]\\s*=>\\s*)(['\\\"])([^'\\\"]*)(\\2)").find(tail)
                    if (hostMatch != null) {
                        val absoluteStart = tailStart + hostMatch.range.first
                        val absoluteEnd = tailStart + hostMatch.range.last + 1
                        val quote = hostMatch.groupValues[2]
                        val replacement = hostMatch.groupValues[1] + quote + "127.0.0.1" + quote
                        patched = patched.substring(0, absoluteStart) + replacement + patched.substring(absoluteEnd)
                    }
                }

                // Direct DSNs are safe to rewrite because only the literal host changes.
                patched = Regex("(?i)(mysql:host=)localhost(?=;|['\"]|$)").replace(patched) { m -> m.groupValues[1] + "127.0.0.1" }

                // Keep assets, redirects and AJAX inside the LocalDev preview rather than
                // sending the browser back to the production domain.
                patched = replacePhpArrayUrlKey(patched, "site_url", "http://127.0.0.1:8080")
                patched = replacePhpArrayUrlKey(patched, "base_url", "http://127.0.0.1:8080")
            }

            if (patched != original) {
                backupFile(root, backupRoot, file, original)
                file.writeText(patched)
                changed += file
            }
        }

        if (changed.isEmpty()) {
            notes += "Config lokal tidak diubah otomatis. Jika PDO memakai host=localhost, gunakan 127.0.0.1 agar koneksi memakai TCP."
        } else {
            notes += "Profile lokal diterapkan hanya pada project yang di-import ke LocalDev."
            notes += "Backup production config: ${backupRoot.relativeTo(root).invariantSeparatorsPath}"
        }
        return MysqlTcpProfileReport(changed, backupRoot, notes)
    }

    fun restoreLatestMysqlTcpProfile(root: File): Int {
        val base = File(root, ".localdev-backup")
        val latest = base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("mysql-profile-") }
            ?.maxByOrNull { it.lastModified() }
            ?: return 0
        var restored = 0
        latest.walkTopDown().filter { it.isFile && it.name.endsWith(".bak") }.forEach { backup ->
            val rel = backup.relativeTo(latest).invariantSeparatorsPath.removeSuffix(".bak")
            val target = File(root, rel)
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
            restored++
        }
        return restored
    }

    private fun replaceEnv(text: String, key: String, value: String): String {
        val regex = Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=.*$")
        return if (regex.containsMatchIn(text)) regex.replace(text, "$key=$value") else text
    }

    private fun replacePhpArrayUrlKey(text: String, key: String, value: String): String {
        val regex = Regex("(?i)(['\\\"]" + Regex.escape(key) + "['\\\"]\\s*=>\\s*)(['\\\"])(https?://[^'\\\"]+)(\\2)")
        return regex.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + value + m.groupValues[2] }
    }

    private fun backupFile(root: File, backupRoot: File, file: File, original: String) {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val backup = File(backupRoot, "$relative.bak")
        backup.parentFile?.mkdirs()
        if (!backup.exists()) backup.writeText(original)
    }

    private fun cursorToText(cursor: Cursor, maxRows: Int): QueryResult {
        val names = cursor.columnNames.toList()
        val out = StringBuilder()
        out.append(names.joinToString(" | ")).append('\n')
        out.append(names.joinToString(" | ") { "---" }).append('\n')
        var rows = 0
        while (cursor.moveToNext() && rows < maxRows) {
            val values = (0 until cursor.columnCount).map { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "NULL"
                    Cursor.FIELD_TYPE_BLOB -> "<BLOB ${cursor.getBlob(index)?.size ?: 0} bytes>"
                    else -> cursor.getString(index)?.replace('\n', ' ')?.take(160) ?: "NULL"
                }
            }
            out.append(values.joinToString(" | ")).append('\n')
            rows++
        }
        if (!cursor.isAfterLast) out.append("… hasil dipotong ke $maxRows baris\n")
        return QueryResult(out.toString().trimEnd(), rows, names.size)
    }
}
