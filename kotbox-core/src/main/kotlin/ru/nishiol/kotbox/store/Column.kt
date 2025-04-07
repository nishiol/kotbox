package ru.nishiol.kotbox.store

enum class Column(val columnName: String) {
    ID("id"),
    VERSION("version"),
    IDEMPOTENCY_KEY("idempotency_key"),
    TASK_DEFINITION_ID("task_definition_id"),
    STATUS("status"),
    TOPIC("topic"),
    CREATION_TIME("creation_time"),
    LAST_ATTEMPT_TIME("last_attempt_time"),
    NEXT_ATTEMPT_TIME("next_attempt_time"),
    ATTEMPTS("attempts"),
    PAYLOAD("payload");

    override fun toString(): String = columnName

    companion object {
        val INSERT_COLUMNS: Set<Column> = setOf(
            VERSION,
            IDEMPOTENCY_KEY,
            TASK_DEFINITION_ID,
            STATUS,
            TOPIC,
            CREATION_TIME,
            LAST_ATTEMPT_TIME,
            NEXT_ATTEMPT_TIME,
            ATTEMPTS,
            PAYLOAD
        )

        val UPDATE_COLUMNS: Set<Column> = setOf(
            VERSION,
            STATUS,
            LAST_ATTEMPT_TIME,
            NEXT_ATTEMPT_TIME,
            ATTEMPTS
        )
    }
}