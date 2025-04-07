package ru.nishiol.kotbox

import java.time.Instant

data class KotboxEntry(
    /**
     * Unique identifier of the Kotbox entry.
     */
    val id: Long,
    /**
     * Version of the entry used for optimistic locking.
     */
    val version: Int,
    /**
     * Unique key ensuring idempotency.
     */
    val idempotencyKey: String,
    /**
     * Identifier of the task definition associated with the entry.
     */
    val taskDefinitionId: String,
    /**
     * Current status of the entry.
     */
    val status: Status,
    /**
     * Topic associated with the task.
     */
    val topic: String?,
    /**
     * Timestamp when the entry was created.
     */
    val creationTime: Instant,
    /**
     * Timestamp of the last attempt to process the entry.
     */
    val lastAttemptTime: Instant?,
    /**
     * Timestamp when the entry will be retried.
     */
    val nextAttemptTime: Instant,
    /**
     * Number of attempts made to process the entry.
     */
    val attempts: Int,
    /**
     * JSON-serialized payload of the task.
     */
    val payload: String
) {
    enum class Status {
        PENDING,
        BLOCKED,
        PROCESSED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KotboxEntry

        return idempotencyKey == other.idempotencyKey
    }

    override fun hashCode(): Int = idempotencyKey.hashCode()
}