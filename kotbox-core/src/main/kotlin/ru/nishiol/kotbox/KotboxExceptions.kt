package ru.nishiol.kotbox

abstract class KotboxException(cause: Throwable? = null) : RuntimeException(cause)

class OptimisticLockException : KotboxException()

class PayloadDeserializationException(cause: Throwable) : KotboxException(cause) {
    override val cause: Throwable get() = super.cause!!
}

class TaskExecutionException(cause: Throwable) : KotboxException(cause) {
    override val cause: Throwable get() = super.cause!!
}