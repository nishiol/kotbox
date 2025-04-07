@file:Suppress("SqlSourceToSinkFlow")

package ru.nishiol.kotbox

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

fun <T> Connection.executeQuery(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): T =
    prepareStatement(sql).use { stmt ->
        stmt.setArgs(args.toList())
        stmt.executeQuery().use(mapper)
    }

fun Connection.executeBatch(sql: String, batchArgs: Collection<Collection<Any?>>): IntArray =
    prepareStatement(sql).use { stmt ->
        batchArgs.forEach { args ->
            stmt.setArgs(args)
            stmt.addBatch()
        }
        stmt.executeBatch()
    }

fun Connection.executeUpdate(sql: String, vararg args: Any?): Int {
    prepareStatement(sql).use { stmt ->
        stmt.setArgs(args.toList())
        return stmt.executeUpdate()
    }
}

private fun PreparedStatement.setArgs(args: Collection<Any?>) {
    args.forEachIndexed { idx, arg ->
        setObject(idx + 1, arg)
    }
}