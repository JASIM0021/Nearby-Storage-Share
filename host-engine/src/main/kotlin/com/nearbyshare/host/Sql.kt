package com.nearbyshare.host

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A deliberately tiny JDBC helper: one SQLite connection, every call serialised, transactions via [tx]. */
class Sql(file: File) : AutoCloseable {
    private val conn: Connection
    private val lock = ReentrantLock()

    init {
        file.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=NORMAL")
        }
    }

    private fun bind(ps: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is Boolean -> ps.setInt(idx, if (a) 1 else 0)
                is Int -> ps.setInt(idx, a)
                is Long -> ps.setLong(idx, a)
                is Enum<*> -> ps.setString(idx, a.name)
                else -> ps.setString(idx, a.toString())
            }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = lock.withLock {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }
    }

    fun update(sql: String, vararg args: Any?): Int = lock.withLock {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    fun execute(sql: String) = lock.withLock { conn.createStatement().use { it.execute(sql) } }

    /** Runs [block] atomically; everything is rolled back if it throws. */
    fun <T> tx(block: () -> T): T = lock.withLock {
        val wasAuto = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            r
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = wasAuto
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }
}

fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }
fun ResultSet.stringOrNull(col: String): String? = getString(col)
fun ResultSet.bool(col: String): Boolean = getInt(col) != 0
