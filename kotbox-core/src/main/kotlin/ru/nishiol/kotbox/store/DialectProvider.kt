package ru.nishiol.kotbox.store

/**
 * Interface for providing a [Dialect] instance.
 */
interface DialectProvider {
    fun getDialect(): Dialect
}