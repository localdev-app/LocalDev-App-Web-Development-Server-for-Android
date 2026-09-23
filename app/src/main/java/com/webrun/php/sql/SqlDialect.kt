package com.webrun.php.sql

/**
 * Small MySQL/MariaDB -> SQLite compatibility translator used by LocalDev SQL Lab.
 * It is intentionally conservative: unsupported server/admin statements are skipped
 * instead of pretending that SQLite can execute every MySQL feature.
 */
object SqlDialect {

    data class Translation(
        val sql: String?,
        val skippedReason: String? = null
    )

    fun splitStatements(input: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var single = false
        var double = false
        var backtick = false
        var lineComment = false
        var blockComment = false
        var escape = false
        var i = 0

        fun flush() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) out += s
            current.setLength(0)
        }

        while (i < input.length) {
            val c = input[i]
            val n = input.getOrNull(i + 1)

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false
                    current.append(c)
                }
                i++
                continue
            }
            if (blockComment) {
                if (c == '*' && n == '/') {
                    blockComment = false
                    i += 2
                } else i++
                continue
            }

            if (!single && !double && !backtick) {
                if (c == '-' && n == '-' && (i == 0 || input.getOrNull(i - 1)?.isWhitespace() == true)) {
                    lineComment = true
                    i += 2
                    continue
                }
                if (c == '#') {
                    lineComment = true
                    i++
                    continue
                }
                if (c == '/' && n == '*') {
                    blockComment = true
                    i += 2
                    continue
                }
            }

            current.append(c)

            if (escape) {
                escape = false
                i++
                continue
            }
            if ((single || double) && c == '\\') {
                escape = true
                i++
                continue
            }

            when (c) {
                '\'' -> if (!double && !backtick) single = !single
                '"' -> if (!single && !backtick) double = !double
                '`' -> if (!single && !double) backtick = !backtick
                ';' -> if (!single && !double && !backtick) flush()
            }
            i++
        }
        flush()
        return out
    }


    /**
     * Converts phpMyAdmin-style ALTER TABLE index statements that are emitted
     * after CREATE TABLE into SQLite CREATE INDEX statements. MySQL PRIMARY KEY
     * is represented as a UNIQUE index in compatibility mode; LocalDevDB adds
     * pseudo-auto-increment behavior for common integer `id` inserts.
     */
    fun translateAlterIndexes(statement: String): List<String> {
        val src = statement.trim().removeSuffix(";").trim()
        val m = Regex("""(?is)^ALTER\s+TABLE\s+[`"]?([A-Za-z0-9_]+)[`"]?\s+(.+)$""").find(src) ?: return emptyList()
        val table = m.groupValues[1]
        val clauses = splitTopLevel(m.groupValues[2])
        val out = mutableListOf<String>()
        var serial = 0
        for (raw in clauses) {
            val clause = raw.trim()
            val primary = Regex("""(?is)^ADD\s+PRIMARY\s+KEY\s*\((.+)\)$""").find(clause)
            if (primary != null) {
                val cols = normalizeIdentifierList(primary.groupValues[1])
                out += "CREATE UNIQUE INDEX IF NOT EXISTS `localdev_pk_${table}` ON `${table}` ($cols)"
                continue
            }
            val unique = Regex("""(?is)^ADD\s+UNIQUE\s+(?:KEY|INDEX)\s+[`"]?([A-Za-z0-9_]+)[`"]?\s*\((.+)\)$""").find(clause)
            if (unique != null) {
                val name = unique.groupValues[1]
                val cols = normalizeIdentifierList(unique.groupValues[2])
                out += "CREATE UNIQUE INDEX IF NOT EXISTS `${name}` ON `${table}` ($cols)"
                continue
            }
            val normal = Regex("""(?is)^ADD\s+(?:KEY|INDEX)\s+[`"]?([A-Za-z0-9_]+)[`"]?\s*\((.+)\)$""").find(clause)
            if (normal != null) {
                val name = normal.groupValues[1]
                val cols = normalizeIdentifierList(normal.groupValues[2])
                out += "CREATE INDEX IF NOT EXISTS `${name}` ON `${table}` ($cols)"
                continue
            }
            val unnamed = Regex("""(?is)^ADD\s+(?:KEY|INDEX)\s*\((.+)\)$""").find(clause)
            if (unnamed != null) {
                serial++
                val cols = normalizeIdentifierList(unnamed.groupValues[1])
                out += "CREATE INDEX IF NOT EXISTS `localdev_idx_${table}_${serial}` ON `${table}` ($cols)"
            }
        }
        return out
    }

    fun translateMysqlToSqlite(statement: String): Translation {
        var s = statement.trim().removeSuffix(";").trim()
        if (s.isBlank()) return Translation(null, "empty")

        // phpMyAdmin / mysqldump control statements that have no SQLite equivalent.
        val upper = s.uppercase()
        val skipPrefixes = listOf(
            "SET ", "LOCK TABLES", "UNLOCK TABLES", "CREATE DATABASE", "DROP DATABASE",
            "USE ", "DELIMITER ", "START TRANSACTION", "COMMIT", "ROLLBACK"
        )
        if (skipPrefixes.any { upper.startsWith(it) }) {
            return Translation(null, "server/control statement")
        }
        if (upper.startsWith("ALTER TABLE") && (
                upper.contains(" ADD KEY ") || upper.contains(" ADD INDEX ") ||
                    upper.contains(" ADD CONSTRAINT ") || upper.contains(" DROP KEY ") ||
                    upper.contains(" DROP INDEX ")
                )) {
            return Translation(null, "MySQL index/constraint ALTER")
        }
        // phpMyAdmin commonly emits a second ALTER TABLE ... MODIFY id ... AUTO_INCREMENT
        // after indexes have already been created. SQLite cannot execute MODIFY COLUMN,
        // and LocalDevDB provides pseudo auto-increment for imported integer id columns.
        if (upper.startsWith("ALTER TABLE") && (upper.contains(" MODIFY ") || upper.contains(" AUTO_INCREMENT="))) {
            return Translation(null, "MySQL MODIFY/AUTO_INCREMENT ALTER")
        }
        if (upper.startsWith("CREATE TABLE")) return translateCreateTable(s)

        s = s.replace(Regex("(?i)^INSERT\\s+IGNORE\\s+INTO"), "INSERT OR IGNORE INTO")
        s = s.replace(Regex("(?i)\\bNOW\\(\\)"), "CURRENT_TIMESTAMP")
        s = s.replace(Regex("(?i)\\bCURRENT_TIMESTAMP\\(\\)"), "CURRENT_TIMESTAMP")
        s = s.replace(Regex("(?i)\\bTRUE\\b"), "1")
        s = s.replace(Regex("(?i)\\bFALSE\\b"), "0")
        s = s.replace(Regex("(?i)\\s+COLLATE\\s+[A-Za-z0-9_]+"), "")
        s = convertMysqlStringEscapes(s)

        if (Regex("""(?i)\bON\s+DUPLICATE\s+KEY\s+UPDATE\b""").containsMatchIn(s)) {
            s = s.replace(Regex("""(?i)\bON\s+DUPLICATE\s+KEY\s+UPDATE\b"""), "ON CONFLICT DO UPDATE SET")
            s = s.replace(Regex("""(?i)\bVALUES\s*\(\s*[`"]?([A-Za-z0-9_]+)[`"]?\s*\)""")) { m ->
                "excluded.`${m.groupValues[1]}`"
            }
        }
        return Translation(s)
    }

    private fun translateCreateTable(statement: String): Translation {
        val open = statement.indexOf('(')
        val close = statement.lastIndexOf(')')
        if (open < 0 || close <= open) return Translation(null, "CREATE TABLE tidak dapat diparse")

        val header = statement.substring(0, open)
        val tableMatch = Regex("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?").find(header)
            ?: return Translation(null, "nama tabel tidak ditemukan")
        val table = tableMatch.groupValues[1]
        val body = statement.substring(open + 1, close)
        val parts = splitTopLevel(body)

        val primaryColumns = mutableListOf<String>()
        parts.forEach { part ->
            Regex("(?i)^PRIMARY\\s+KEY\\s*\\((.+)\\)").find(part.trim())?.let { m ->
                primaryColumns += m.groupValues[1].split(',').map { cleanIdentifier(it) }
            }
        }

        val columnSql = mutableListOf<String>()
        val tableConstraints = mutableListOf<String>()

        for (raw in parts) {
            val part = raw.trim()
            if (part.isBlank()) continue
            val upper = part.uppercase()
            when {
                upper.startsWith("PRIMARY KEY") -> continue
                upper.startsWith("KEY ") || upper.startsWith("INDEX ") -> continue
                upper.startsWith("UNIQUE KEY") || upper.startsWith("UNIQUE INDEX") -> {
                    val cols = Regex("\\((.+)\\)").find(part)?.groupValues?.getOrNull(1)
                    if (!cols.isNullOrBlank()) tableConstraints += "UNIQUE (${normalizeIdentifierList(cols)})"
                    continue
                }
                upper.startsWith("CONSTRAINT ") || upper.startsWith("FOREIGN KEY") -> {
                    // Foreign-key syntax often survives unchanged once MySQL-specific names are removed.
                    val normalized = part.replace(Regex("(?i)^CONSTRAINT\\s+[`\"]?[A-Za-z0-9_]+[`\"]?\\s+"), "")
                    if (normalized.uppercase().startsWith("FOREIGN KEY")) tableConstraints += normalized
                    continue
                }
            }

            val m = Regex("^[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).find(part)
                ?: continue
            val name = m.groupValues[1]
            val definition = m.groupValues[2]
            val isPrimary = primaryColumns.size == 1 && primaryColumns[0].equals(name, true)
            val auto = Regex("(?i)\\bAUTO_INCREMENT\\b").containsMatchIn(definition)
            val notNull = Regex("(?i)\\bNOT\\s+NULL\\b").containsMatchIn(definition)
            val unique = Regex("(?i)\\bUNIQUE\\b").containsMatchIn(definition)
            val type = sqliteType(definition)
            val defaultSql = parseDefault(definition)

            val col = StringBuilder("`").append(name).append("` ")
            if (isPrimary && type == "INTEGER") {
                col.append("INTEGER PRIMARY KEY")
                if (auto) col.append(" AUTOINCREMENT")
            } else {
                col.append(type)
                if (notNull) col.append(" NOT NULL")
                if (unique) col.append(" UNIQUE")
                if (defaultSql != null) col.append(" DEFAULT ").append(defaultSql)
            }
            columnSql += col.toString()
        }

        if (primaryColumns.size > 1) {
            tableConstraints += "PRIMARY KEY (${primaryColumns.joinToString(", ") { "`$it`" }})"
        } else if (primaryColumns.size == 1 && columnSql.none { it.startsWith("`${primaryColumns[0]}` INTEGER PRIMARY KEY") }) {
            tableConstraints += "PRIMARY KEY (`${primaryColumns[0]}`)"
        }

        if (columnSql.isEmpty()) return Translation(null, "tidak ada kolom yang dapat diterjemahkan")
        val all = (columnSql + tableConstraints).joinToString(",\n  ")
        return Translation("CREATE TABLE IF NOT EXISTS `$table` (\n  $all\n)")
    }

    private fun sqliteType(definition: String): String {
        val t = definition.trim().lowercase()
        return when {
            Regex("^(tinyint|smallint|mediumint|int|integer|bigint|bit|bool|boolean)\\b").containsMatchIn(t) -> "INTEGER"
            Regex("^(decimal|numeric|float|double|real)\\b").containsMatchIn(t) -> "REAL"
            Regex("^(blob|binary|varbinary|tinyblob|mediumblob|longblob)\\b").containsMatchIn(t) -> "BLOB"
            else -> "TEXT"
        }
    }

    private fun parseDefault(definition: String): String? {
        val m = Regex("(?i)\\bDEFAULT\\s+((?:'[^']*(?:''[^']*)*')|(?:\"[^\"]*\")|(?:NULL)|(?:CURRENT_TIMESTAMP(?:\\(\\))?)|(?:[-+]?[0-9]+(?:\\.[0-9]+)?))").find(definition)
            ?: return null
        return m.groupValues[1].replace(Regex("(?i)CURRENT_TIMESTAMP\\(\\)"), "CURRENT_TIMESTAMP")
    }

    private fun splitTopLevel(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var single = false
        var double = false
        var backtick = false
        var escape = false
        for (c in input) {
            if (escape) {
                cur.append(c)
                escape = false
                continue
            }
            if ((single || double) && c == '\\') {
                cur.append(c)
                escape = true
                continue
            }
            when (c) {
                '\'' -> if (!double && !backtick) single = !single
                '"' -> if (!single && !backtick) double = !double
                '`' -> if (!single && !double) backtick = !backtick
                '(' -> if (!single && !double && !backtick) depth++
                ')' -> if (!single && !double && !backtick && depth > 0) depth--
                ',' -> if (!single && !double && !backtick && depth == 0) {
                    out += cur.toString()
                    cur.setLength(0)
                    continue
                }
            }
            cur.append(c)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    private fun cleanIdentifier(value: String): String = value.trim().removeSurrounding("`").removeSurrounding("\"").substringBefore('(').trim()

    private fun normalizeIdentifierList(value: String): String = value.split(',').joinToString(", ") { "`${cleanIdentifier(it)}`" }

    /** Converts common MySQL backslash escapes inside single-quoted strings to SQLite-safe literals. */
    private fun convertMysqlStringEscapes(sql: String): String {
        val out = StringBuilder(sql.length)
        var single = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (!single) {
                out.append(c)
                if (c == '\'') single = true
                i++
                continue
            }
            if (c == '\'') {
                // SQL doubled quote: remain inside string.
                if (sql.getOrNull(i + 1) == '\'') {
                    out.append("''")
                    i += 2
                } else {
                    out.append(c)
                    single = false
                    i++
                }
                continue
            }
            if (c == '\\' && i + 1 < sql.length) {
                val n = sql[i + 1]
                when (n) {
                    '\'' -> out.append("''")
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '0' -> out.append('\u0000')
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
