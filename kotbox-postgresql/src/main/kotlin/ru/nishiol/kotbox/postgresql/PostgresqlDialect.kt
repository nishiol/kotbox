package ru.nishiol.kotbox.postgresql

import org.postgresql.util.PGobject
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.store.Column
import ru.nishiol.kotbox.store.Dialect
import ru.nishiol.kotbox.store.Placeholders
import java.sql.ResultSet
import java.sql.Timestamp

class PostgresqlDialect : Dialect {
    override val createKotboxTable: List<String> = listOf(
        """
            CREATE SCHEMA IF NOT EXISTS ${Placeholders.TABLE_SCHEMA}
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS ${Placeholders.TABLE_NAME} (
                ${Column.ID} BIGSERIAL PRIMARY KEY,
                ${Column.VERSION} INT NOT NULL,
                ${Column.IDEMPOTENCY_KEY} TEXT NOT NULL UNIQUE,
                ${Column.TASK_DEFINITION_ID} TEXT NOT NULL,
                ${Column.STATUS} TEXT NOT NULL,
                ${Column.TOPIC} TEXT,
                ${Column.CREATION_TIME} TIMESTAMP NOT NULL,
                ${Column.LAST_ATTEMPT_TIME} TIMESTAMP,
                ${Column.NEXT_ATTEMPT_TIME} TIMESTAMP NOT NULL,
                ${Column.ATTEMPTS} INT NOT NULL,
                ${Column.PAYLOAD} JSONB NOT NULL
            )
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS idx_kotbox_status ON ${Placeholders.TABLE_QUALIFIED_NAME}(${Column.STATUS})
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS idx_kotbox_next_attempt_time ON ${Placeholders.TABLE_QUALIFIED_NAME}(${Column.NEXT_ATTEMPT_TIME})
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS idx_kotbox_topic ON ${Placeholders.TABLE_QUALIFIED_NAME}(${Column.TOPIC})
        """.trimIndent()
    )

    override val fetchAll: String = """
        SELECT ${Column.entries.joinToString(", ")} 
        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
    """.trimIndent()

    override val fetchWithoutTopic: String = """
        SELECT ${Column.entries.joinToString(", ")} 
        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
        WHERE ${Column.NEXT_ATTEMPT_TIME} < ? 
            AND ${Column.STATUS} = '${KotboxEntry.Status.PENDING.name}'
            AND ${Column.TOPIC} IS NULL
            FOR UPDATE SKIP LOCKED
            LIMIT ?
    """.trimIndent()

    // the most effective method for postgresql
    // https://stackoverflow.com/questions/3800551/select-first-row-in-each-group-by-group#answer-34715134
    override val fetchNextInAllTopics: String = """
        WITH RECURSIVE cte AS ((SELECT ${Column.ID}, ${Column.TOPIC}
                                FROM ${Placeholders.TABLE_QUALIFIED_NAME}
                                WHERE ${Column.STATUS} <> '${KotboxEntry.Status.PROCESSED.name}'
                                    AND ${Column.TOPIC} IS NOT NULL
                                ORDER BY ${Column.TOPIC}, ${Column.ID}
                                LIMIT 1)
                               UNION ALL
                               SELECT u.*
                               FROM cte c,
                                    LATERAL (
                                        SELECT ${Column.ID}, ${Column.TOPIC}
                                        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
                                        WHERE ${Column.TOPIC} > c.${Column.TOPIC}
                                            AND ${Column.STATUS} <> '${KotboxEntry.Status.PROCESSED.name}'
                                            AND ${Column.TOPIC} IS NOT NULL
                                        ORDER BY ${Column.TOPIC}, ${Column.ID}
                                        LIMIT 1
                                    ) u)
        SELECT ${Column.entries.joinToString(", ")}
        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
        WHERE ${Column.ID} IN (SELECT ${Column.ID} FROM cte)
            AND ${Column.NEXT_ATTEMPT_TIME} < ?
            AND ${Column.STATUS} = '${KotboxEntry.Status.PENDING.name}'
        FOR UPDATE SKIP LOCKED
        LIMIT ?
    """.trimIndent()

    override val findAndLockById: String = """
        SELECT ${Column.entries.joinToString(", ")}
        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
        WHERE ${Column.ID} = ?
        FOR UPDATE
    """.trimIndent()

    override val insert: String = """
        INSERT INTO ${Placeholders.TABLE_QUALIFIED_NAME} 
        (${Column.INSERT_COLUMNS.joinToString(", ")}) 
        VALUES 
        (${Column.INSERT_COLUMNS.joinToString(", ") { "?" }})
        ON CONFLICT (${Column.IDEMPOTENCY_KEY}) DO NOTHING
    """.trimIndent()

    override val update: String = """
        UPDATE ${Placeholders.TABLE_QUALIFIED_NAME}
        SET ${Column.UPDATE_COLUMNS.joinToString(", ") { "$it = ?" }}
        WHERE ${Column.ID} = ?
            AND ${Column.VERSION} = ?
    """.trimIndent()

    override val deleteProcessedAndExpired: String = """
        DELETE FROM ${Placeholders.TABLE_QUALIFIED_NAME}
        WHERE ${Column.STATUS} = '${KotboxEntry.Status.PROCESSED.name}'
            AND ${Column.NEXT_ATTEMPT_TIME} < ?
    """.trimIndent()

    override val tryLock: String = """
        SELECT 1
        FROM ${Placeholders.TABLE_QUALIFIED_NAME}
        WHERE ${Column.ID} = ?
            AND ${Column.VERSION} = ?
        FOR UPDATE SKIP LOCKED
    """.trimIndent()

    override fun sqlArgsForColumns(
        kotboxEntry: KotboxEntry,
        columns: Set<Column>,
        vararg additionalArgs: Any?,
        newVersion: Int
    ): Collection<Any?> {
        val columnArgs = columns.map { column ->
            when (column) {
                Column.ID -> kotboxEntry.id
                Column.VERSION -> newVersion
                Column.IDEMPOTENCY_KEY -> kotboxEntry.idempotencyKey
                Column.TASK_DEFINITION_ID -> kotboxEntry.taskDefinitionId
                Column.STATUS -> kotboxEntry.status.name
                Column.TOPIC -> kotboxEntry.topic
                Column.CREATION_TIME -> Timestamp.from(kotboxEntry.creationTime)
                Column.LAST_ATTEMPT_TIME -> kotboxEntry.lastAttemptTime?.let { Timestamp.from(it) }
                Column.NEXT_ATTEMPT_TIME -> Timestamp.from(kotboxEntry.nextAttemptTime)
                Column.ATTEMPTS -> kotboxEntry.attempts
                Column.PAYLOAD -> PGobject().apply {
                    type = "jsonb"
                    value = kotboxEntry.payload
                }
            }
        }
        return columnArgs + additionalArgs
    }

    override fun toEntry(resultSet: ResultSet): KotboxEntry = KotboxEntry(
        id = resultSet.getLong(Column.ID.columnName),
        version = resultSet.getInt(Column.VERSION.columnName),
        idempotencyKey = resultSet.getString(Column.IDEMPOTENCY_KEY.columnName),
        taskDefinitionId = resultSet.getString(Column.TASK_DEFINITION_ID.columnName),
        status = KotboxEntry.Status.valueOf(resultSet.getString(Column.STATUS.columnName)),
        topic = resultSet.getString(Column.TOPIC.columnName),
        creationTime = resultSet.getTimestamp(Column.CREATION_TIME.columnName).toInstant(),
        lastAttemptTime = resultSet.getTimestamp(Column.LAST_ATTEMPT_TIME.columnName)?.toInstant(),
        nextAttemptTime = resultSet.getTimestamp(Column.NEXT_ATTEMPT_TIME.columnName).toInstant(),
        attempts = resultSet.getInt(Column.ATTEMPTS.columnName),
        payload = resultSet.getObject(Column.PAYLOAD.columnName, PGobject::class.java).value!!
    )
}