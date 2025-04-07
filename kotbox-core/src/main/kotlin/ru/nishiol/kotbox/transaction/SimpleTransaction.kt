package ru.nishiol.kotbox.transaction

import ru.nishiol.kobject.MutableKObject
import ru.nishiol.kobject.mutableKObjectOf
import java.sql.Connection

class SimpleTransaction(
    override val connection: Connection
) : TransactionContext, AutoCloseable {
    override val data: MutableKObject = mutableKObjectOf()

    private val actionsBeforeCommit: MutableList<() -> Unit> = mutableListOf()

    private val actionsAfterCommit: MutableList<() -> Unit> = mutableListOf()

    private val isAutoCommit: Boolean = connection.autoCommit

    override fun addActionBeforeCommit(action: () -> Unit) {
        actionsBeforeCommit += action
    }

    override fun addActionAfterCommit(action: () -> Unit) {
        actionsAfterCommit += action
    }

    fun begin() {
        if (isAutoCommit) {
            connection.autoCommit = false
        }
    }

    fun commit() {
        actionsBeforeCommit.forEach { it() }
        connection.commit()
        actionsAfterCommit.forEach { it() }
    }

    fun rollback() {
        connection.rollback()
    }

    override fun close() {
        if (isAutoCommit) {
            connection.autoCommit = true
        }
        connection.close()
    }
}