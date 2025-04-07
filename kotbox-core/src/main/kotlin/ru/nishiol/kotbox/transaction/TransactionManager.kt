package ru.nishiol.kotbox.transaction

/**
 * Manages database transactions, providing methods to execute operations
 * within a new or existing transaction context.
 */
interface TransactionManager {
    /**
     * Executes the given block within a new transaction.
     *
     * @param block The code block to be executed in a new transaction.
     * @return The result of the block execution.
     */
    fun <T> doInNewTransaction(block: (TransactionContext) -> T): T

    /**
     * Executes the given block within the current transaction.
     *
     * @param block The code block to be executed in the current transaction.
     * @return The result of the block execution.
     */
    fun <T> doInCurrentTransaction(block: (TransactionContext) -> T): T
}