package ru.nishiol.kotbox.transaction

import ru.nishiol.kobject.MutableKObject
import java.sql.Connection

/**
 * Represents a transaction context of the current transaction.
 */
interface TransactionContext {
    /**
     * Stores arbitrary transactional data that can be shared within the transaction lifecycle.
     */
    val data: MutableKObject

    /**
     * Provides access to the database connection associated with the transaction.
     */
    val connection: Connection

    /**
     * Registers an action to be executed before the transaction is committed.
     *
     * @param action The action to execute.
     */
    fun addActionBeforeCommit(action: () -> Unit)

    /**
     * Registers an action to be executed after the transaction is committed.
     *
     * @param action The action to execute.
     */
    fun addActionAfterCommit(action: () -> Unit)
}
