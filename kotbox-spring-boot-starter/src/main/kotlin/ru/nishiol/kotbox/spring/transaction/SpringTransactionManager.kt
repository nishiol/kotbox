package ru.nishiol.kotbox.spring.transaction

import org.springframework.beans.factory.ObjectFactory
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.nishiol.kotbox.transaction.TransactionContext
import ru.nishiol.kotbox.transaction.TransactionManager

open class SpringTransactionManager(
    private  val transactionContextProvider: ObjectFactory<SpringTransactionContext>
) : TransactionManager {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun <T> doInNewTransaction(block: (TransactionContext) -> T): T =
        block(transactionContextProvider.`object`)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun <T> doInCurrentTransaction(block: (TransactionContext) -> T): T =
        block(transactionContextProvider.`object`)
}