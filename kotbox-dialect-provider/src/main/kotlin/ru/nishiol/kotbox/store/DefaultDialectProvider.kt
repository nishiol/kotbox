package ru.nishiol.kotbox.store

import ru.nishiol.kotbox.postgresql.PostgresqlDialect
import ru.nishiol.kotbox.transaction.TransactionManager

class DefaultDialectProvider(
    private val transactionManager: TransactionManager
) : DialectProvider {

    override fun getDialect(): Dialect = transactionManager.doInCurrentTransaction {
        val databaseName = it.connection.metaData.databaseProductName.lowercase()
        when {
            databaseName.contains("postgresql") && isClassPresent("ru.nishiol.kotbox.postgresql.PostgresqlDialect") -> PostgresqlDialect()
            else -> throw IllegalArgumentException("Unsupported database: $databaseName")
        }
    }

    private fun isClassPresent(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}