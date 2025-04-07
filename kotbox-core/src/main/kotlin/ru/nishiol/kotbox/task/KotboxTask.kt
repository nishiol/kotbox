package ru.nishiol.kotbox.task

import ru.nishiol.kotbox.KotboxEntry
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Defines a Kotbox task with execution logic.
 *
 * @param P The payload type, which must be serializable/deserializable to JSON.
 */
interface KotboxTask<P : Any> {
    /**
     * Definition of a Kotbox task.
     */
    interface Definition<P : Any> {
        /**
         * The unique identifier of the task definition.
         */
        val id: String

        /**
         * The class type of the payload associated with the task.
         */
        val payloadClass: KClass<P>
    }

    /**
     * Defines actions to take when a failure occurs during task execution.
     */
    sealed interface OnFailureAction {
        /**
         * Retries the failed task at the specified time.
         *
         * @param atTime The time at which the task should be retried.
         */
        data class Retry(val atTime: Instant) : OnFailureAction

        /**
         * Blocks further execution of the failed task.
         */
        data object Block : OnFailureAction

        /**
         * Ignores the failed task and marks it as processed.
         */
        data object Ignore : OnFailureAction
    }

    /**
     * The definition of this task.
     */
    val definition: Definition<P>

    /**
     * Executes the task with the given payload in a transaction.
     *
     * @param payload The payload of the task.
     * @param topic The topic associated with the task.
     */
    fun execute(payload: P, topic: String?)

    /**
     * Handles task execution failures in a transaction.
     *
     * @param payload The payload of the failed task.
     * @param entry The Kotbox entry.
     * @param cause The cause of the failure.
     * @return The action to take upon failure.
     */
    fun onFailure(payload: P, entry: KotboxEntry, cause: Throwable): OnFailureAction

    /**
     * Handles failures during payload deserialization in a transaction.
     *
     * @param entry The Kotbox entry.
     * @param cause The cause of the failure.
     * @return The action to take upon failure. Defaults to blocking further execution.
     */
    fun onPayloadDeserializationFailure(entry: KotboxEntry, cause: Throwable): OnFailureAction =
        OnFailureAction.Block
}
