package ru.nishiol.kotbox.test

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.nishiol.kotbox.KotboxEntry
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

fun KotboxEntry.assertEquals(
    other: KotboxEntry
) {
    assertEquals(other.id, id)
    assertEquals(other.version, version)
    assertEquals(other.idempotencyKey, idempotencyKey)
    assertEquals(other.taskDefinitionId, taskDefinitionId)
    assertEquals(other.status, status)
    assertEquals(other.topic, topic)
    assertEquals(other.creationTime.truncatedTo(ChronoUnit.MILLIS), creationTime.truncatedTo(ChronoUnit.MILLIS))
    assertEquals(other.lastAttemptTime?.truncatedTo(ChronoUnit.MILLIS), lastAttemptTime?.truncatedTo(ChronoUnit.MILLIS))
    assertEquals(other.nextAttemptTime.truncatedTo(ChronoUnit.MILLIS), nextAttemptTime.truncatedTo(ChronoUnit.MILLIS))
    assertEquals(other.attempts, attempts)
    val objectMapper = jacksonObjectMapper()
    assertEquals(objectMapper.readTree(other.payload), objectMapper.readTree(payload))
}