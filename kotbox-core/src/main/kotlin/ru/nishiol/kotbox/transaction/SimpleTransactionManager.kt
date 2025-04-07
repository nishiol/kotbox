package ru.nishiol.kotbox.transaction

import java.sql.Connection
import java.util.*

class SimpleTransactionManager(
    private val connectionProvider: () -> Connection
) : TransactionManager {
    private val transactions = ThreadLocal.withInitial { ArrayDeque<SimpleTransaction>() }

    private val currentTransaction: SimpleTransaction?
        get() = transactions.get().peekLast()

    override fun <T> doInNewTransaction(block: (TransactionContext) -> T): T {
        val transaction = SimpleTransaction(connectionProvider())
        pushTransaction(transaction)
        return transaction.use {
            try {
                transaction.begin()
                val result = block(transaction)
                transaction.commit()
                result
            } catch (ex: Exception) {
                transaction.rollback()
                throw ex
            } finally {
                popTransaction()
            }
        }
    }

    override fun <T> doInCurrentTransaction(block: (TransactionContext) -> T): T {
        val transaction = checkNotNull(currentTransaction) { "There is no current transaction" }
        return block(transaction)
    }

    private fun pushTransaction(transaction: SimpleTransaction) {
        transactions.get().offer(transaction)
    }

    private fun popTransaction(): SimpleTransaction {
        return transactions.get().removeLast()
    }
}