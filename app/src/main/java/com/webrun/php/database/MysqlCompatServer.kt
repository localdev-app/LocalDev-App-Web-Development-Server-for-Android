package com.webrun.php.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.webrun.php.sql.SqlDialect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * LocalDevDB: a small MySQL-wire compatibility server backed by the project's
 * LocalDev SQLite database.
 *
 * Goal: let normal PHP code using PDO MySQL / mysqli connect to
 * 127.0.0.1:3306 without rewriting the project's PHP source. This is NOT
 * MariaDB and does not claim full MySQL compatibility. It implements the
 * subset required by common local-development CRUD projects, including native
 * prepared statements used by PDO with ATTR_EMULATE_PREPARES=false.
 */
class MysqlCompatServer {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 3306
        const val VERSION = "8.0.36-LocalDevDB-0.9.6"

        private const val COM_QUIT = 0x01
        private const val COM_INIT_DB = 0x02
        private const val COM_QUERY = 0x03
        private const val COM_FIELD_LIST = 0x04
        private const val COM_PING = 0x0e
        private const val COM_STMT_PREPARE = 0x16
        private const val COM_STMT_EXECUTE = 0x17
        private const val COM_STMT_SEND_LONG_DATA = 0x18
        private const val COM_STMT_CLOSE = 0x19
        private const val COM_STMT_RESET = 0x1a

        private const val CLIENT_LONG_PASSWORD = 0x00000001
        private const val CLIENT_FOUND_ROWS = 0x00000002
        private const val CLIENT_LONG_FLAG = 0x00000004
        private const val CLIENT_CONNECT_WITH_DB = 0x00000008
        private const val CLIENT_PROTOCOL_41 = 0x00000200
        private const val CLIENT_TRANSACTIONS = 0x00002000
        private const val CLIENT_SECURE_CONNECTION = 0x00008000
        private const val CLIENT_MULTI_RESULTS = 0x00020000
        private const val CLIENT_PLUGIN_AUTH = 0x00080000
        private const val CLIENT_CONNECT_ATTRS = 0x00100000
        private const val CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x00200000

        private const val SERVER_STATUS_AUTOCOMMIT = 0x0002
        private const val SERVER_STATUS_IN_TRANS = 0x0001

        private const val MYSQL_TYPE_DECIMAL = 0x00
        private const val MYSQL_TYPE_TINY = 0x01
        private const val MYSQL_TYPE_SHORT = 0x02
        private const val MYSQL_TYPE_LONG = 0x03
        private const val MYSQL_TYPE_FLOAT = 0x04
        private const val MYSQL_TYPE_DOUBLE = 0x05
        private const val MYSQL_TYPE_NULL = 0x06
        private const val MYSQL_TYPE_TIMESTAMP = 0x07
        private const val MYSQL_TYPE_LONGLONG = 0x08
        private const val MYSQL_TYPE_INT24 = 0x09
        private const val MYSQL_TYPE_DATE = 0x0a
        private const val MYSQL_TYPE_TIME = 0x0b
        private const val MYSQL_TYPE_DATETIME = 0x0c
        private const val MYSQL_TYPE_YEAR = 0x0d
        private const val MYSQL_TYPE_VARCHAR = 0x0f
        private const val MYSQL_TYPE_BIT = 0x10
        private const val MYSQL_TYPE_NEWDECIMAL = 0xf6
        private const val MYSQL_TYPE_ENUM = 0xf7
        private const val MYSQL_TYPE_SET = 0xf8
        private const val MYSQL_TYPE_TINY_BLOB = 0xf9
        private const val MYSQL_TYPE_MEDIUM_BLOB = 0xfa
        private const val MYSQL_TYPE_LONG_BLOB = 0xfb
        private const val MYSQL_TYPE_BLOB = 0xfc
        private const val MYSQL_TYPE_VAR_STRING = 0xfd
        private const val MYSQL_TYPE_STRING = 0xfe

        private const val CHARSET_UTF8MB4_GENERAL_CI = 45
        private const val ER_PARSE_ERROR = 1064
        private const val ER_NO_SUCH_TABLE = 1146
        private const val ER_BAD_DB_ERROR = 1049
        private const val ER_ACCESS_DENIED_ERROR = 1045
        private const val ER_UNKNOWN_ERROR = 1105
    }

    data class Diagnostics(
        val running: Boolean,
        val portOpen: Boolean,
        val databaseFile: String?,
        val clients: Int,
        val queries: Long,
        val lastError: String
    )

    private data class Prepared(
        val id: Int,
        val sql: String,
        val paramCount: Int,
        val columnNames: List<String>,
        var parameterTypes: IntArray = IntArray(paramCount) { MYSQL_TYPE_VAR_STRING },
        val longData: MutableMap<Int, ByteArrayOutputStream> = mutableMapOf()
    )

    private data class QueryOutput(
        val columns: List<String> = emptyList(),
        val rows: List<List<Any?>> = emptyList(),
        val affected: Long = 0,
        val lastInsertId: Long = 0
    )

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var databaseFile: File? = null
    @Volatile private var lastError: String = ""
    private val clientCount = AtomicInteger(0)
    private val queryCount = java.util.concurrent.atomic.AtomicLong(0)
    private val statementIds = AtomicInteger(100)
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun start(database: File, onLog: ((String) -> Unit)? = null): Boolean {
        require(database.isFile) { "Database compatibility belum dibuat." }
        if (isRunning()) {
            if (databaseFile?.canonicalPath == database.canonicalPath) return true
            stop()
        }

        databaseFile = database
        lastError = ""
        val socket = ServerSocket(PORT, 12, InetAddress.getByName(HOST))
        socket.reuseAddress = true
        serverSocket = socket
        onLog?.invoke("LocalDevDB: MySQL-compatible server listening on $HOST:$PORT")

        thread(name = "LocalDevDB-accept", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    pool.execute { handleClient(client, database, onLog) }
                } catch (t: Throwable) {
                    if (!socket.isClosed) {
                        lastError = t.message ?: t.javaClass.simpleName
                        onLog?.invoke("LocalDevDB ERROR: $lastError")
                    }
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        databaseFile = null
    }

    fun isRunning(): Boolean = serverSocket?.let { !it.isClosed } == true

    fun diagnostics(): Diagnostics = Diagnostics(
        running = isRunning(),
        portOpen = isRunning(),
        databaseFile = databaseFile?.absolutePath,
        clients = clientCount.get(),
        queries = queryCount.get(),
        lastError = lastError
    )

    private fun handleClient(socket: Socket, database: File, onLog: ((String) -> Unit)?) {
        clientCount.incrementAndGet()
        socket.use {
            val input = BufferedInputStream(it.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(it.getOutputStream(), 64 * 1024)
            val connId = (System.nanoTime() and 0x7fffffff).toInt()
            val salt = ByteArray(20).also { bytes -> SecureRandom().nextBytes(bytes) }
            val db = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            var currentDb = "localdev"
            var inTransaction = false
            val prepared = ConcurrentHashMap<Int, Prepared>()
            try {
                writePacket(output, handshakePacket(connId, salt), 0)
                val hello = readPacket(input)
                val auth = parseHandshakeResponse(hello.payload)
                if (auth.database.isNotBlank()) currentDb = auth.database
                // LocalDevDB is loopback-only. Credentials are accepted so imported
                // hosting projects can run without source rewriting.
                writePacket(output, okPacket(status = SERVER_STATUS_AUTOCOMMIT), hello.sequence + 1)

                while (true) {
                    val packet = try { readPacket(input) } catch (_: EOFException) { break }
                    if (packet.payload.isEmpty()) continue
                    val cmd = packet.payload[0].toInt() and 0xff
                    val body = packet.payload.copyOfRange(1, packet.payload.size)
                    try {
                        when (cmd) {
                            COM_QUIT -> break
                            COM_PING -> writePacket(output, okPacket(status = statusFor(inTransaction)), 1)
                            COM_INIT_DB -> {
                                currentDb = body.toString(Charsets.UTF_8).ifBlank { "localdev" }
                                writePacket(output, okPacket(status = statusFor(inTransaction)), 1)
                            }
                            COM_QUERY -> {
                                val sql = body.toString(Charsets.UTF_8)
                                queryCount.incrementAndGet()
                                val result = execute(db, sql, currentDb, null, inTransaction)
                                inTransaction = updateTransactionState(sql, inTransaction, db)
                                sendTextResult(output, result, currentDb, statusFor(inTransaction))
                            }
                            COM_STMT_PREPARE -> {
                                val sql = body.toString(Charsets.UTF_8)
                                val id = statementIds.incrementAndGet()
                                val params = countPlaceholders(sql)
                                val cols = describeColumns(db, sql, currentDb, params)
                                val stmt = Prepared(id, sql, params, cols)
                                prepared[id] = stmt
                                sendPrepareResponse(output, stmt, currentDb, statusFor(inTransaction))
                            }
                            COM_STMT_EXECUTE -> {
                                val exec = parseExecute(body, prepared)
                                val stmt = exec.first
                                val values = exec.second
                                queryCount.incrementAndGet()
                                val result = execute(db, stmt.sql, currentDb, values, inTransaction)
                                inTransaction = updateTransactionState(stmt.sql, inTransaction, db)
                                sendBinaryResult(output, result, currentDb, statusFor(inTransaction))
                            }
                            COM_STMT_SEND_LONG_DATA -> handleLongData(body, prepared)
                            COM_STMT_CLOSE -> if (body.size >= 4) prepared.remove(readUInt32LE(body, 0).toInt())
                            COM_STMT_RESET -> {
                                if (body.size >= 4) prepared[readUInt32LE(body, 0).toInt()]?.longData?.clear()
                                writePacket(output, okPacket(status = statusFor(inTransaction)), 1)
                            }
                            COM_FIELD_LIST -> sendEmptyResult(output, statusFor(inTransaction))
                            else -> writePacket(output, errorPacket(ER_UNKNOWN_ERROR, "HY000", "LocalDevDB: command 0x${cmd.toString(16)} belum didukung"), 1)
                        }
                    } catch (t: Throwable) {
                        val msg = t.message ?: t.javaClass.simpleName
                        lastError = msg
                        onLog?.invoke("LocalDevDB SQL ERROR: $msg")
                        val code = when {
                            msg.contains("no such table", true) -> ER_NO_SUCH_TABLE
                            msg.contains("syntax", true) -> ER_PARSE_ERROR
                            else -> ER_UNKNOWN_ERROR
                        }
                        writePacket(output, errorPacket(code, "HY000", msg.take(1400)), 1)
                    }
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                onLog?.invoke("LocalDevDB CLIENT ERROR: $lastError")
            } finally {
                if (inTransaction) runCatching { db.endTransaction() }
                db.close()
                clientCount.decrementAndGet()
            }
        }
    }

    private fun statusFor(inTransaction: Boolean): Int =
        SERVER_STATUS_AUTOCOMMIT or if (inTransaction) SERVER_STATUS_IN_TRANS else 0

    private fun updateTransactionState(sql: String, wasInTransaction: Boolean, db: SQLiteDatabase): Boolean {
        val t = sql.trim().uppercase()
        return when {
            t == "BEGIN" || t.startsWith("START TRANSACTION") -> {
                if (!wasInTransaction && !db.inTransaction()) db.beginTransaction()
                true
            }
            t == "COMMIT" -> {
                if (db.inTransaction()) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                }
                false
            }
            t == "ROLLBACK" -> {
                if (db.inTransaction()) db.endTransaction()
                false
            }
            else -> wasInTransaction
        }
    }

    private fun execute(
        db: SQLiteDatabase,
        originalSql: String,
        currentDb: String,
        params: List<Any?>?,
        inTransaction: Boolean
    ): QueryOutput {
        var sql = originalSql.trim().trimEnd(';').trim()
        if (sql.isEmpty()) return QueryOutput()
        if (params != null) sql = bindSql(sql, params)

        val upper = sql.uppercase()
        if (upper == "BEGIN" || upper.startsWith("START TRANSACTION") || upper == "COMMIT" || upper == "ROLLBACK") {
            return QueryOutput()
        }
        if (upper.startsWith("SET ")) return QueryOutput()
        if (upper.startsWith("USE ")) return QueryOutput()
        if (upper.startsWith("CREATE DATABASE") || upper.startsWith("DROP DATABASE")) return QueryOutput()
        if (upper.startsWith("SELECT DATABASE()")) return QueryOutput(listOf("DATABASE()"), listOf(listOf(currentDb)))
        if (upper.startsWith("SELECT VERSION()") || upper.startsWith("SELECT @@VERSION")) return QueryOutput(listOf("VERSION()"), listOf(listOf(VERSION)))
        if (upper.startsWith("SELECT @@")) return QueryOutput(listOf("@@localdev"), listOf(listOf("0")))
        if (upper.startsWith("SHOW TABLES")) return showTables(db, currentDb)
        if (Regex("(?i)^SHOW\\s+(?:FULL\\s+)?COLUMNS\\s+FROM\\s+").containsMatchIn(sql) || Regex("(?i)^DESCRIBE\\s+").containsMatchIn(sql)) {
            val table = Regex("(?i)(?:FROM|DESCRIBE)\\s+[`\"]?([A-Za-z0-9_]+)").find(sql)?.groupValues?.getOrNull(1)
                ?: throw IllegalArgumentException("SHOW COLUMNS: nama tabel tidak ditemukan")
            return showColumns(db, table)
        }
        if (upper.contains("INFORMATION_SCHEMA.COLUMNS")) return informationSchemaColumns(db, sql)

        sql = runtimeTranslate(sql)
        sql = ensurePseudoAutoIncrement(db, sql)

        val read = Regex("(?is)^\\s*(SELECT|WITH|PRAGMA|EXPLAIN)\\b").containsMatchIn(sql)
        if (read) {
            db.rawQuery(sql, null).use { c ->
                val columns = c.columnNames.toList()
                val rows = mutableListOf<List<Any?>>()
                while (c.moveToNext()) {
                    rows += (0 until c.columnCount).map { index -> cursorValue(c, index) }
                    if (rows.size >= 5000) break
                }
                return QueryOutput(columns, rows)
            }
        }

        val before = queryLong(db, "SELECT changes()")
        db.execSQL(sql)
        val affected = queryLong(db, "SELECT changes()").let { if (it == before) it else it }
        val lastId = queryLong(db, "SELECT last_insert_rowid()")
        return QueryOutput(affected = affected, lastInsertId = lastId)
    }

    private fun runtimeTranslate(input: String): String {
        var s = input.trim()
        s = s.replace(Regex("(?i)^INSERT\\s+IGNORE\\s+INTO"), "INSERT OR IGNORE INTO")
        s = s.replace(Regex("(?i)\\bNOW\\(\\)"), "CURRENT_TIMESTAMP")
        s = s.replace(Regex("(?i)\\bCURDATE\\(\\)"), "DATE('now')")
        s = s.replace(Regex("(?i)\\bGREATEST\\s*\\("), "MAX(")
        s = s.replace(Regex("(?i)\\s+COLLATE\\s+[A-Za-z0-9_]+"), "")

        // TIMESTAMPDIFF(SECOND, created_at, NOW()) is common in cooldown/chat flows.
        s = s.replace(
            Regex("(?i)TIMESTAMPDIFF\\s*\\(\\s*SECOND\\s*,\\s*([A-Za-z0-9_`.]+)\\s*,\\s*CURRENT_TIMESTAMP\\s*\\)"),
        ) { m ->
            "CAST((strftime('%s', CURRENT_TIMESTAMP) - strftime('%s', ${m.groupValues[1]})) AS INTEGER)"
        }

        // DATE_SUB/DATE_ADD with literal INTERVAL, common in PHP projects.
        s = s.replace(Regex("(?i)DATE_SUB\\(\\s*(CURRENT_TIMESTAMP|DATE\\('now'\\))\\s*,\\s*INTERVAL\\s+(\\d+)\\s+(SECOND|MINUTE|HOUR|DAY)\\s*\\)")) { m ->
            val base = if (m.groupValues[1].startsWith("DATE", true)) "DATE('now')" else "CURRENT_TIMESTAMP"
            val unit = sqliteIntervalUnit(m.groupValues[3], m.groupValues[2])
            "datetime($base, '-$unit')"
        }
        s = s.replace(Regex("(?i)DATE_ADD\\(\\s*(CURRENT_TIMESTAMP|DATE\\('now'\\))\\s*,\\s*INTERVAL\\s+(\\d+)\\s+(SECOND|MINUTE|HOUR|DAY)\\s*\\)")) { m ->
            val base = if (m.groupValues[1].startsWith("DATE", true)) "DATE('now')" else "CURRENT_TIMESTAMP"
            val unit = sqliteIntervalUnit(m.groupValues[3], m.groupValues[2])
            "datetime($base, '+$unit')"
        }

        // MySQL UPSERT -> SQLite UPSERT. SQLite permits omitted conflict target.
        if (Regex("(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b").containsMatchIn(s)) {
            s = s.replace(Regex("(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b"), "ON CONFLICT DO UPDATE SET")
            s = s.replace(Regex("(?i)\\bVALUES\\s*\\(\\s*[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\)")) { m -> "excluded.`${m.groupValues[1]}`" }
        }

        // MySQL IF(condition, yes, no) -> SQLite CASE expression. Keep this
        // conservative so complex nested functions fail visibly instead of being guessed.
        s = s.replace(
            Regex("(?i)IF\\s*\\(\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)"),
        ) { m ->
            "(CASE WHEN ${m.groupValues[1].trim()} THEN ${m.groupValues[2].trim()} ELSE ${m.groupValues[3].trim()} END)"
        }

        // ALTER ... ADD COLUMN is supported by SQLite after removing MySQL-only pieces.
        if (Regex("(?i)^ALTER\\s+TABLE\\b").containsMatchIn(s) && Regex("(?i)\\bADD\\s+(?:COLUMN\\s+)?").containsMatchIn(s)) {
            s = s.replace(Regex("(?i)\\s+AFTER\\s+[`\"]?[A-Za-z0-9_]+[`\"]?\\s*$"), "")
            s = s.replace(Regex("(?i)\\bENUM\\s*\\([^)]*\\)"), "TEXT")
            s = s.replace(Regex("(?i)\\bUNSIGNED\\b"), "")
        }

        // Runtime CREATE TABLE from project helpers.
        if (Regex("(?i)^CREATE\\s+TABLE").containsMatchIn(s)) {
            val translated = SqlDialect.translateMysqlToSqlite(s)
            if (!translated.sql.isNullOrBlank()) return translated.sql
        }
        return s
    }

    private fun sqliteIntervalUnit(unit: String, count: String): String {
        val plural = when (unit.uppercase()) {
            "SECOND" -> "seconds"
            "MINUTE" -> "minutes"
            "HOUR" -> "hours"
            else -> "days"
        }
        return "$count $plural"
    }

    /**
     * phpMyAdmin dumps often add AUTO_INCREMENT in a later ALTER statement,
     * while SQLite compatibility import has an ordinary integer `id` column.
     * For common INSERT ... (columns) VALUES (...) statements, inject max(id)+1
     * when the table has an id column but the INSERT omitted it.
     */
    private fun ensurePseudoAutoIncrement(db: SQLiteDatabase, sql: String): String {
        val m = Regex("(?is)^\\s*INSERT(?:\\s+OR\\s+IGNORE)?\\s+INTO\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\(").find(sql)
            ?: return sql
        val table = m.groupValues[1]
        val cols = splitCsv(m.groupValues[2]).map { it.trim().trim('`', '"') }
        if (cols.any { it.equals("id", true) }) return sql
        if (!tableHasColumn(db, table, "id")) return sql
        val insertStart = m.range.first
        val valuesOpen = m.range.last
        val originalCols = m.groupValues[2]
        val before = sql.substring(0, m.range.first) + m.value.substringBefore('(')
        val afterOpen = sql.substring(valuesOpen + 1)
        return "$before(id, $originalCols) VALUES((SELECT COALESCE(MAX(id),0)+1 FROM `${escapeIdent(table)}`), $afterOpen"
    }

    private fun splitCsv(value: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (quote != null) {
                cur.append(c)
                if (c == quote && value.getOrNull(i - 1) != '\\') quote = null
            } else {
                when (c) {
                    '\'', '"', '`' -> { quote = c; cur.append(c) }
                    '(' -> { depth++; cur.append(c) }
                    ')' -> { if (depth > 0) depth--; cur.append(c) }
                    ',' -> if (depth == 0) { out += cur.toString(); cur.setLength(0) } else cur.append(c)
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    private fun showTables(db: SQLiteDatabase, currentDb: String): QueryOutput {
        val rows = mutableListOf<List<Any?>>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
            while (c.moveToNext()) rows += listOf(c.getString(0))
        }
        return QueryOutput(listOf("Tables_in_$currentDb"), rows)
    }

    private fun showColumns(db: SQLiteDatabase, table: String): QueryOutput {
        val rows = mutableListOf<List<Any?>>()
        db.rawQuery("PRAGMA table_info(`${escapeIdent(table)}`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type")).ifBlank { "text" }
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) != 0
                val defaultValue = if (c.isNull(c.getColumnIndexOrThrow("dflt_value"))) null else c.getString(c.getColumnIndexOrThrow("dflt_value"))
                val pk = c.getInt(c.getColumnIndexOrThrow("pk")) != 0
                rows += listOf(name, type, if (notNull) "NO" else "YES", if (pk) "PRI" else "", defaultValue, "")
            }
        }
        return QueryOutput(listOf("Field", "Type", "Null", "Key", "Default", "Extra"), rows)
    }

    private fun informationSchemaColumns(db: SQLiteDatabase, sql: String): QueryOutput {
        val table = Regex("(?i)TABLE_NAME\\s*=\\s*['\"]([^'\"]+)['\"]").find(sql)?.groupValues?.getOrNull(1)
        val column = Regex("(?i)COLUMN_NAME\\s*=\\s*['\"]([^'\"]+)['\"]").find(sql)?.groupValues?.getOrNull(1)
        if (Regex("(?i)SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)").containsMatchIn(sql)) {
            val exists = table != null && (column == null || tableHasColumn(db, table, column)) && tableExists(db, table)
            return QueryOutput(listOf("COUNT(*)"), listOf(listOf(if (exists) 1 else 0)))
        }
        if (table != null) return showColumns(db, table)
        return QueryOutput(emptyList(), emptyList())
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { return it.moveToFirst() }
    }

    private fun tableHasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        if (!tableExists(db, table)) return false
        db.rawQuery("PRAGMA table_info(`${escapeIdent(table)}`)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(idx).equals(column, true)) return true
        }
        return false
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun cursorValue(c: Cursor, index: Int): Any? = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
        else -> c.getString(index)
    }

    private fun bindSql(sql: String, params: List<Any?>): String {
        val out = StringBuilder(sql.length + params.size * 8)
        var p = 0
        var single = false
        var double = false
        var backtick = false
        var escape = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (escape) { out.append(c); escape = false; i++; continue }
            if ((single || double) && c == '\\') { out.append(c); escape = true; i++; continue }
            when (c) {
                '\'' -> { if (!double && !backtick) single = !single; out.append(c) }
                '"' -> { if (!single && !backtick) double = !double; out.append(c) }
                '`' -> { if (!single && !double) backtick = !backtick; out.append(c) }
                '?' -> if (!single && !double && !backtick && p < params.size) out.append(sqlLiteral(params[p++])) else out.append(c)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun sqlLiteral(value: Any?): String = when (value) {
        null -> "NULL"
        is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        is Boolean -> if (value) "1" else "0"
        is ByteArray -> "X'" + value.joinToString("") { "%02x".format(it) } + "'"
        else -> "'" + value.toString().replace("'", "''") + "'"
    }

    private fun describeColumns(db: SQLiteDatabase, sql: String, currentDb: String, paramCount: Int): List<String> {
        val bound = bindSql(sql, List(paramCount) { null })
        val upper = bound.trim().uppercase()
        if (upper.contains("INFORMATION_SCHEMA.COLUMNS")) return listOf("COUNT(*)")
        if (!Regex("(?is)^\\s*(SELECT|WITH|PRAGMA|EXPLAIN|SHOW|DESCRIBE)\\b").containsMatchIn(bound)) return emptyList()
        if (upper.startsWith("SHOW TABLES")) return listOf("Tables_in_$currentDb")
        if (upper.startsWith("SHOW COLUMNS") || upper.startsWith("DESCRIBE")) return listOf("Field", "Type", "Null", "Key", "Default", "Extra")
        if (upper.startsWith("SELECT DATABASE()")) return listOf("DATABASE()")
        if (upper.startsWith("SELECT VERSION()") || upper.startsWith("SELECT @@")) return listOf("VERSION()")
        return runCatching {
            val translated = runtimeTranslate(bound)
            db.rawQuery(translated, null).use { it.columnNames.toList() }
        }.getOrDefault(emptyList())
    }

    private fun countPlaceholders(sql: String): Int {
        var count = 0
        var single = false
        var double = false
        var backtick = false
        var escape = false
        for (c in sql) {
            if (escape) { escape = false; continue }
            if ((single || double) && c == '\\') { escape = true; continue }
            when (c) {
                '\'' -> if (!double && !backtick) single = !single
                '"' -> if (!single && !backtick) double = !double
                '`' -> if (!single && !double) backtick = !backtick
                '?' -> if (!single && !double && !backtick) count++
            }
        }
        return count
    }

    private data class Packet(val sequence: Int, val payload: ByteArray)

    private fun readPacket(input: BufferedInputStream): Packet {
        val h0 = input.read(); if (h0 < 0) throw EOFException()
        val h1 = input.read(); val h2 = input.read(); val seq = input.read()
        if (h1 < 0 || h2 < 0 || seq < 0) throw EOFException()
        val len = h0 or (h1 shl 8) or (h2 shl 16)
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return Packet(seq, data)
    }

    private fun writePacket(output: BufferedOutputStream, payload: ByteArray, sequence: Int) {
        val len = payload.size
        output.write(len and 0xff)
        output.write((len ushr 8) and 0xff)
        output.write((len ushr 16) and 0xff)
        output.write(sequence and 0xff)
        output.write(payload)
        output.flush()
    }

    private fun handshakePacket(connectionId: Int, salt: ByteArray): ByteArray {
        val caps = CLIENT_LONG_PASSWORD or CLIENT_FOUND_ROWS or CLIENT_LONG_FLAG or CLIENT_CONNECT_WITH_DB or
            CLIENT_PROTOCOL_41 or CLIENT_TRANSACTIONS or CLIENT_SECURE_CONNECTION or CLIENT_MULTI_RESULTS or
            CLIENT_PLUGIN_AUTH or CLIENT_CONNECT_ATTRS or CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
        val out = ByteArrayOutputStream()
        out.write(0x0a)
        out.writeCString(VERSION)
        out.writeUInt32LE(connectionId.toLong())
        out.write(salt.copyOfRange(0, 8))
        out.write(0)
        out.writeUInt16LE(caps and 0xffff)
        out.write(CHARSET_UTF8MB4_GENERAL_CI)
        out.writeUInt16LE(SERVER_STATUS_AUTOCOMMIT)
        out.writeUInt16LE((caps ushr 16) and 0xffff)
        out.write(21)
        repeat(10) { out.write(0) }
        out.write(salt.copyOfRange(8, 20))
        out.write(0)
        out.writeCString("mysql_native_password")
        return out.toByteArray()
    }

    private data class AuthInfo(val username: String, val database: String)

    private fun parseHandshakeResponse(data: ByteArray): AuthInfo {
        if (data.size < 32) return AuthInfo("", "")
        val caps = readUInt32LE(data, 0).toInt()
        var p = 4 + 4 + 1 + 23
        val userRead = readCString(data, p); val user = userRead.first; p = userRead.second
        if (p >= data.size) return AuthInfo(user, "")
        p = when {
            caps and CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA != 0 -> {
                val l = readLenEncInt(data, p); p = l.second; (p + l.first.toInt()).coerceAtMost(data.size)
            }
            caps and CLIENT_SECURE_CONNECTION != 0 -> {
                val len = data[p].toInt() and 0xff; (p + 1 + len).coerceAtMost(data.size)
            }
            else -> readCString(data, p).second
        }
        var db = ""
        if (caps and CLIENT_CONNECT_WITH_DB != 0 && p < data.size) {
            val r = readCString(data, p); db = r.first
        }
        return AuthInfo(user, db)
    }

    private fun okPacket(affected: Long = 0, lastInsertId: Long = 0, status: Int = SERVER_STATUS_AUTOCOMMIT): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.writeLenEncInt(affected)
        out.writeLenEncInt(lastInsertId)
        out.writeUInt16LE(status)
        out.writeUInt16LE(0)
        return out.toByteArray()
    }

    private fun eofPacket(status: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xfe)
        out.writeUInt16LE(0)
        out.writeUInt16LE(status)
        return out.toByteArray()
    }

    private fun errorPacket(code: Int, state: String, message: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xff)
        out.writeUInt16LE(code)
        out.write('#'.code)
        out.write(state.padEnd(5, '0').take(5).toByteArray(Charsets.US_ASCII))
        out.write(message.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun sendTextResult(output: BufferedOutputStream, result: QueryOutput, dbName: String, status: Int) {
        if (result.columns.isEmpty()) {
            writePacket(output, okPacket(result.affected, result.lastInsertId, status), 1)
            return
        }
        var seq = 1
        writePacket(output, lenEncIntBytes(result.columns.size.toLong()), seq++)
        result.columns.forEach { name -> writePacket(output, columnDefinition(name, dbName), seq++) }
        writePacket(output, eofPacket(status), seq++)
        result.rows.forEach { row ->
            val p = ByteArrayOutputStream()
            row.forEach { value -> writeTextValue(p, value) }
            writePacket(output, p.toByteArray(), seq++)
        }
        writePacket(output, eofPacket(status), seq)
    }

    private fun sendBinaryResult(output: BufferedOutputStream, result: QueryOutput, dbName: String, status: Int) {
        if (result.columns.isEmpty()) {
            writePacket(output, okPacket(result.affected, result.lastInsertId, status), 1)
            return
        }
        var seq = 1
        writePacket(output, lenEncIntBytes(result.columns.size.toLong()), seq++)
        result.columns.forEach { name -> writePacket(output, columnDefinition(name, dbName), seq++) }
        writePacket(output, eofPacket(status), seq++)
        result.rows.forEach { row ->
            val p = ByteArrayOutputStream()
            p.write(0x00)
            val nullBitmap = ByteArray((result.columns.size + 7 + 2) / 8)
            row.forEachIndexed { index, v -> if (v == null) {
                val bit = index + 2
                nullBitmap[bit / 8] = (nullBitmap[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            } }
            p.write(nullBitmap)
            row.forEach { value -> if (value != null) writeBinaryStringValue(p, value) }
            writePacket(output, p.toByteArray(), seq++)
        }
        writePacket(output, eofPacket(status), seq)
    }

    private fun sendEmptyResult(output: BufferedOutputStream, status: Int) {
        writePacket(output, eofPacket(status), 1)
    }

    private fun sendPrepareResponse(output: BufferedOutputStream, stmt: Prepared, dbName: String, status: Int) {
        var seq = 1
        val head = ByteArrayOutputStream()
        head.write(0x00)
        head.writeUInt32LE(stmt.id.toLong())
        head.writeUInt16LE(stmt.columnNames.size)
        head.writeUInt16LE(stmt.paramCount)
        head.write(0)
        head.writeUInt16LE(0)
        writePacket(output, head.toByteArray(), seq++)
        repeat(stmt.paramCount) { idx -> writePacket(output, columnDefinition("param${idx + 1}", dbName), seq++) }
        if (stmt.paramCount > 0) writePacket(output, eofPacket(status), seq++)
        stmt.columnNames.forEach { name -> writePacket(output, columnDefinition(name, dbName), seq++) }
        if (stmt.columnNames.isNotEmpty()) writePacket(output, eofPacket(status), seq)
    }

    private fun columnDefinition(name: String, dbName: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLenEncString("def")
        out.writeLenEncString(dbName)
        out.writeLenEncString("")
        out.writeLenEncString("")
        out.writeLenEncString(name)
        out.writeLenEncString(name)
        out.write(0x0c)
        out.writeUInt16LE(CHARSET_UTF8MB4_GENERAL_CI)
        out.writeUInt32LE(1024L * 1024L)
        out.write(MYSQL_TYPE_VAR_STRING)
        out.writeUInt16LE(0)
        out.write(0)
        out.writeUInt16LE(0)
        return out.toByteArray()
    }

    private fun writeTextValue(out: ByteArrayOutputStream, value: Any?) {
        if (value == null) { out.write(0xfb); return }
        val bytes = when (value) {
            is ByteArray -> value
            else -> value.toString().toByteArray(Charsets.UTF_8)
        }
        out.writeLenEncBytes(bytes)
    }

    private fun writeBinaryStringValue(out: ByteArrayOutputStream, value: Any) {
        val bytes = when (value) {
            is ByteArray -> value
            else -> value.toString().toByteArray(Charsets.UTF_8)
        }
        out.writeLenEncBytes(bytes)
    }

    private fun handleLongData(body: ByteArray, prepared: Map<Int, Prepared>) {
        if (body.size < 6) return
        val stmt = prepared[readUInt32LE(body, 0).toInt()] ?: return
        val param = readUInt16LE(body, 4)
        stmt.longData.getOrPut(param) { ByteArrayOutputStream() }.write(body, 6, body.size - 6)
    }

    private fun parseExecute(body: ByteArray, prepared: Map<Int, Prepared>): Pair<Prepared, List<Any?>> {
        require(body.size >= 9) { "COM_STMT_EXECUTE packet terlalu pendek" }
        val id = readUInt32LE(body, 0).toInt()
        val stmt = prepared[id] ?: throw IllegalArgumentException("Prepared statement $id tidak ditemukan")
        if (stmt.paramCount == 0) return stmt to emptyList()
        var p = 4 + 1 + 4
        val nullLen = (stmt.paramCount + 7) / 8
        require(p + nullLen + 1 <= body.size) { "Parameter bitmap tidak lengkap" }
        val nullMap = body.copyOfRange(p, p + nullLen); p += nullLen
        val newTypes = body[p].toInt() and 0xff; p++
        if (newTypes != 0) {
            require(p + stmt.paramCount * 2 <= body.size) { "Parameter type data tidak lengkap" }
            val types = IntArray(stmt.paramCount)
            repeat(stmt.paramCount) { i -> types[i] = body[p].toInt() and 0xff; p += 2 /* skip unsigned flag */ }
            stmt.parameterTypes = types
        }
        val values = MutableList<Any?>(stmt.paramCount) { null }
        repeat(stmt.paramCount) { i ->
            if (((nullMap[i / 8].toInt() ushr (i % 8)) and 1) != 0) {
                values[i] = null
            } else if (stmt.longData.containsKey(i)) {
                values[i] = stmt.longData.remove(i)?.toByteArray()
            } else {
                val decoded = decodeParameter(body, p, stmt.parameterTypes.getOrElse(i) { MYSQL_TYPE_VAR_STRING })
                values[i] = decoded.first
                p = decoded.second
            }
        }
        return stmt to values
    }

    private fun decodeParameter(data: ByteArray, start: Int, type: Int): Pair<Any?, Int> {
        var p = start
        fun requireBytes(n: Int) { require(p + n <= data.size) { "Parameter value terpotong" } }
        return when (type) {
            MYSQL_TYPE_NULL -> null to p
            MYSQL_TYPE_TINY -> { requireBytes(1); (data[p].toInt()) to (p + 1) }
            MYSQL_TYPE_SHORT, MYSQL_TYPE_YEAR -> { requireBytes(2); readInt16LE(data, p) to (p + 2) }
            MYSQL_TYPE_LONG, MYSQL_TYPE_INT24 -> { requireBytes(4); readUInt32LE(data, p).toLong() to (p + 4) }
            MYSQL_TYPE_LONGLONG -> { requireBytes(8); readInt64LE(data, p) to (p + 8) }
            MYSQL_TYPE_FLOAT -> { requireBytes(4); ByteBuffer.wrap(data, p, 4).order(ByteOrder.LITTLE_ENDIAN).float to (p + 4) }
            MYSQL_TYPE_DOUBLE -> { requireBytes(8); ByteBuffer.wrap(data, p, 8).order(ByteOrder.LITTLE_ENDIAN).double to (p + 8) }
            MYSQL_TYPE_DATE, MYSQL_TYPE_DATETIME, MYSQL_TYPE_TIMESTAMP -> decodeDateTime(data, p)
            MYSQL_TYPE_TIME -> decodeTime(data, p)
            MYSQL_TYPE_DECIMAL, MYSQL_TYPE_NEWDECIMAL, MYSQL_TYPE_VARCHAR, MYSQL_TYPE_VAR_STRING, MYSQL_TYPE_STRING,
            MYSQL_TYPE_ENUM, MYSQL_TYPE_SET, MYSQL_TYPE_TINY_BLOB, MYSQL_TYPE_MEDIUM_BLOB, MYSQL_TYPE_LONG_BLOB, MYSQL_TYPE_BLOB,
            MYSQL_TYPE_BIT -> {
                val l = readLenEncInt(data, p); val len = l.first.toInt(); p = l.second
                requireBytes(len)
                data.copyOfRange(p, p + len).toString(Charsets.UTF_8) to (p + len)
            }
            else -> {
                val l = readLenEncInt(data, p); val len = l.first.toInt(); p = l.second
                requireBytes(len)
                data.copyOfRange(p, p + len).toString(Charsets.UTF_8) to (p + len)
            }
        }
    }

    private fun decodeDateTime(data: ByteArray, start: Int): Pair<Any?, Int> {
        if (start >= data.size) return null to start
        val len = data[start].toInt() and 0xff
        if (len == 0) return "0000-00-00 00:00:00" to (start + 1)
        var p = start + 1
        if (p + len > data.size) throw IllegalArgumentException("Datetime parameter terpotong")
        val year = readUInt16LE(data, p); p += 2
        val month = data[p++].toInt() and 0xff
        val day = data[p++].toInt() and 0xff
        if (len == 4) return "%04d-%02d-%02d".format(year, month, day) to p
        val hour = data[p++].toInt() and 0xff
        val minute = data[p++].toInt() and 0xff
        val second = data[p++].toInt() and 0xff
        if (len == 7) return "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second) to p
        val micros = readUInt32LE(data, p); p += 4
        return "%04d-%02d-%02d %02d:%02d:%02d.%06d".format(year, month, day, hour, minute, second, micros) to p
    }

    private fun decodeTime(data: ByteArray, start: Int): Pair<Any?, Int> {
        if (start >= data.size) return null to start
        val len = data[start].toInt() and 0xff
        if (len == 0) return "00:00:00" to (start + 1)
        var p = start + 1
        val negative = data[p++].toInt() != 0
        val days = readUInt32LE(data, p); p += 4
        val hours = data[p++].toInt() and 0xff
        val minutes = data[p++].toInt() and 0xff
        val seconds = data[p++].toInt() and 0xff
        val totalHours = days * 24 + hours
        val prefix = if (negative) "-" else ""
        if (len > 8) p += 4
        return "$prefix%02d:%02d:%02d".format(totalHours, minutes, seconds) to p
    }

    private fun escapeIdent(v: String): String = v.replace("`", "``")

    private fun readUInt16LE(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8)

    private fun readInt16LE(data: ByteArray, off: Int): Int =
        ByteBuffer.wrap(data, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private fun readUInt32LE(data: ByteArray, off: Int): Long =
        (data[off].toLong() and 0xff) or
            ((data[off + 1].toLong() and 0xff) shl 8) or
            ((data[off + 2].toLong() and 0xff) shl 16) or
            ((data[off + 3].toLong() and 0xff) shl 24)

    private fun readInt64LE(data: ByteArray, off: Int): Long =
        ByteBuffer.wrap(data, off, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun readCString(data: ByteArray, start: Int): Pair<String, Int> {
        var end = start
        while (end < data.size && data[end].toInt() != 0) end++
        return data.copyOfRange(start, end).toString(Charsets.UTF_8) to (end + 1).coerceAtMost(data.size)
    }

    private fun readLenEncInt(data: ByteArray, start: Int): Pair<Long, Int> {
        if (start >= data.size) return 0L to start
        return when (val first = data[start].toInt() and 0xff) {
            in 0..250 -> first.toLong() to (start + 1)
            0xfc -> readUInt16LE(data, start + 1).toLong() to (start + 3)
            0xfd -> {
                val v = (data[start + 1].toLong() and 0xff) or ((data[start + 2].toLong() and 0xff) shl 8) or ((data[start + 3].toLong() and 0xff) shl 16)
                v to (start + 4)
            }
            0xfe -> readInt64LE(data, start + 1) to (start + 9)
            else -> 0L to (start + 1)
        }
    }

    private fun ByteArrayOutputStream.writeUInt16LE(v: Int) {
        write(v and 0xff); write((v ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt32LE(v: Long) {
        write((v and 0xff).toInt()); write(((v ushr 8) and 0xff).toInt()); write(((v ushr 16) and 0xff).toInt()); write(((v ushr 24) and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.writeCString(s: String) {
        write(s.toByteArray(Charsets.UTF_8)); write(0)
    }

    private fun ByteArrayOutputStream.writeLenEncString(s: String) = writeLenEncBytes(s.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.writeLenEncBytes(bytes: ByteArray) {
        writeLenEncInt(bytes.size.toLong()); write(bytes)
    }

    private fun ByteArrayOutputStream.writeLenEncInt(v: Long) {
        when {
            v < 251 -> write(v.toInt())
            v <= 0xffff -> { write(0xfc); writeUInt16LE(v.toInt()) }
            v <= 0xffffff -> { write(0xfd); write((v and 0xff).toInt()); write(((v ushr 8) and 0xff).toInt()); write(((v ushr 16) and 0xff).toInt()) }
            else -> {
                write(0xfe)
                repeat(8) { shift -> write(((v ushr (8 * shift)) and 0xff).toInt()) }
            }
        }
    }

    private fun lenEncIntBytes(v: Long): ByteArray = ByteArrayOutputStream().apply { writeLenEncInt(v) }.toByteArray()
}
