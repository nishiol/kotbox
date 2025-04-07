package ru.nishiol.kotbox.spring.transaction

import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.nishiol.kobject.MutableKObject
import ru.nishiol.kobject.mutableKObjectOf
import ru.nishiol.kotbox.transaction.TransactionContext
import java.sql.Connection
import javax.sql.DataSource

class SpringTransactionContext(
    private val dataSource: DataSource
) : TransactionContext {
    override val data: MutableKObject = mutableKObjectOf()

    override val connection: Connection get() = DataSourceUtils.getConnection(dataSource)

    override fun addActionBeforeCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    action()
                }
            }
        )
    }

    override fun addActionAfterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            }
        )
    }
}