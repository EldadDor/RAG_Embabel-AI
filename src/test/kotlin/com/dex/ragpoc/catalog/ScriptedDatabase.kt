package com.dex.ragpoc.catalog

import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** Executes real Spring JDBC mapping and transaction code against an offline SQL boundary. */
class ScriptedDatabase {
    data class Call(
        val sql: String,
        val args: Map<Int, Any?>,
    )

    val calls = mutableListOf<Call>()
    val events = mutableListOf<String>()
    var reads: (Call) -> List<Map<String, Any?>> = { emptyList() }
    var writes: (Call) -> Int = { 1 }
    val connection = mockk<Connection>(relaxed = true)
    val dataSource = mockk<DataSource>()
    private var autoCommit = true
    private var readOnly = false
    private var isolation = Connection.TRANSACTION_READ_COMMITTED

    init {
        every { dataSource.connection } answers {
            events += "acquire"
            connection
        }
        every { connection.autoCommit } answers { autoCommit }
        every { connection.setAutoCommit(any()) } answers {
            autoCommit = firstArg()
            events += "autoCommit=$autoCommit"
        }
        every { connection.isReadOnly } answers { readOnly }
        every { connection.setReadOnly(any()) } answers {
            readOnly = firstArg()
            events += "readOnly=$readOnly"
        }
        every { connection.transactionIsolation } answers { isolation }
        every { connection.setTransactionIsolation(any()) } answers {
            isolation = firstArg()
            events += "isolation=$isolation"
        }
        every { connection.commit() } answers { events += "commit" }
        every { connection.rollback() } answers { events += "rollback" }
        every { connection.close() } answers { events += "close" }
        every { connection.abort(any()) } answers { events += "abort" }
        every { connection.prepareStatement(any<String>()) } answers { statement(firstArg()) }
        every { connection.createStatement() } answers {
            mockk<java.sql.Statement>(relaxed = true) {
                every { executeQuery(any()) } answers {
                    val call = Call(firstArg(), emptyMap())
                    calls += call
                    result(reads(call))
                }
            }
        }
    }

    private fun statement(sql: String): PreparedStatement {
        val args = linkedMapOf<Int, Any?>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { statement.setString(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setObject(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setObject(any(), any(), any<Int>()) } answers { args[firstArg()] = secondArg() }
        every { statement.setTimestamp(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setLong(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setInt(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setBoolean(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.setNull(any(), any()) } answers { args[firstArg()] = null }
        every { statement.setBytes(any(), any()) } answers { args[firstArg()] = secondArg() }
        every { statement.executeQuery() } answers {
            val call = Call(sql, args.toMap())
            calls += call
            result(reads(call))
        }
        every { statement.executeUpdate() } answers {
            val call = Call(sql, args.toMap())
            calls += call
            writes(call)
        }
        return statement
    }

    private fun result(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false

        fun value(column: Any): Any? {
            val result = if (column is Int) rows[index].values.elementAt(column - 1) else rows[index][column]
            wasNull = result == null
            return result
        }

        fun timestamp(value: Any?): Timestamp? =
            when (value) {
                is Instant -> Timestamp.from(value)
                else -> value as Timestamp?
            }
        val metadata = mockk<ResultSetMetaData>(relaxed = true)
        every { metadata.columnCount } returns (rows.firstOrNull()?.size ?: 1)
        return mockk<ResultSet>(relaxed = true) {
            every { next() } answers { ++index < rows.size }
            every { wasNull() } answers { wasNull }
            every { getMetaData() } returns metadata
            every { getString(any<String>()) } answers { value(firstArg()) as String? }
            every { getString(any<Int>()) } answers { value(firstArg()) as String? }
            every { getLong(any<String>()) } answers { (value(firstArg()) as Number?)?.toLong() ?: 0 }
            every { getLong(any<Int>()) } answers { (value(firstArg()) as Number?)?.toLong() ?: 0 }
            every { getBoolean(any<Int>()) } answers { value(firstArg()) as Boolean? ?: false }
            every { getTimestamp(any<String>()) } answers { timestamp(value(firstArg())) }
            every { getTimestamp(any<Int>()) } answers { timestamp(value(firstArg())) }
            every { getInt(any<String>()) } answers { (value(firstArg()) as Number?)?.toInt() ?: 0 }
            every { getObject(any<Int>()) } answers { value(firstArg()) }
            every { getObject(any<String>(), any<Class<*>>()) } answers { value(firstArg()) }
            every { getObject(any<Int>(), any<Class<*>>()) } answers { value(firstArg()) }
        }
    }
}
