package ru.nishiol.kotbox.test

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.task.KotboxTask
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Testcontainers
class KotboxTests : AbstractIntegrationTest(shouldStartKotbox = true) {
    @Test
    fun `should schedule an entry`() {
        // given
        val idempotencyKey = UUID.randomUUID().toString()
        val payload = TestTask.Payload(message = "Hello world!")
        val topic = "test"
        val atTime = clock.instant.plusMillis(1000 * 60)

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = payload,
                idempotencyKey = idempotencyKey,
                topic = topic,
                atTime = atTime
            )
        }

        // then
        val entries = transactionManager.doInNewTransaction {
            kotboxStore.fetchAll(it)
        }
        assertEquals(1, entries.size)
        val entry = entries.single()
        entry.assertEquals(
            KotboxEntry(
                id = entry.id, // generated
                version = 1,
                idempotencyKey = idempotencyKey,
                taskDefinitionId = TestTask.id,
                status = KotboxEntry.Status.PENDING,
                topic = topic,
                creationTime = clock.instant,
                lastAttemptTime = null,
                nextAttemptTime = atTime,
                attempts = 0,
                payload = payloadSerializer.serialize(payload)
            )
        )
    }

    @Test
    fun `should ignore duplicates if scheduled in the same transaction`() {
        // given
        val idempotencyKey = UUID.randomUUID().toString()

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = TestTask.Payload(""),
                idempotencyKey = idempotencyKey
            )
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = TestTask.Payload(""),
                idempotencyKey = idempotencyKey
            )
        }

        // then
        val entries = transactionManager.doInNewTransaction {
            kotboxStore.fetchAll(it)
        }
        assertEquals(1, entries.size)
    }

    @Test
    fun `should ignore duplicates if scheduled in different transactions`() {
        // given
        val idempotencyKey = UUID.randomUUID().toString()

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = TestTask.Payload(""),
                idempotencyKey = idempotencyKey
            )
        }
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = TestTask.Payload(""),
                idempotencyKey = idempotencyKey
            )
        }

        // then
        val entries = transactionManager.doInNewTransaction {
            kotboxStore.fetchAll(it)
        }
        assertEquals(1, entries.size)
    }

    @Test
    fun `should process single scheduled task without topic`() {
        // given
        val startTime = clock.instant
        val payload = TestTask.Payload("message")
        val idempotencyKey = UUID.randomUUID().toString()
        val atTime = clock.instant.plusSeconds(5)

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = payload,
                idempotencyKey = idempotencyKey,
                atTime = atTime
            )
        }

        //then
        assertEquals(0, kotbox.tryPoll())

        clock.instant = atTime.plusSeconds(1)

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.failurePayloads).isEmpty()
        }

        val entries = transactionManager.doInNewTransaction {
            kotboxStore.fetchAll(it)
        }
        assertEquals(1, entries.size)

        val entry = entries.first()

        entry.assertEquals(
            KotboxEntry(
                id = entry.id,
                version = 3,
                idempotencyKey = idempotencyKey,
                taskDefinitionId = TestTask.id,
                status = KotboxEntry.Status.PROCESSED,
                topic = null,
                creationTime = startTime,
                lastAttemptTime = clock.instant,
                nextAttemptTime = clock.instant + properties.retentionDuration,
                attempts = 1,
                payload = payloadSerializer.serialize(payload)
            )
        )
    }

    @Test
    fun `should process scheduled tasks without topic`() {
        // given
        val payloads = (1..100).map { TestTask.Payload("message-$it") }

        // when
        payloads.forEach { payload ->
            transactionManager.doInNewTransaction {
                kotbox.schedule(
                    taskDefinition = TestTask,
                    payload = payload
                )
            }
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[""].orEmpty()).containsExactlyInAnyOrderElementsOf(payloads)
            assertThat(testTask.failurePayloads).isEmpty()
        }
    }

    @Test
    fun `should process scheduled tasks with topic in order`() {
        // given
        val payloadsInOrderByTopic = (1..100).associate { topicIdx ->
            val topic = "topic-$topicIdx"
            topic to (1..10).map {
                TestTask.Payload(message = "$topic-message-$it")
            }
        }

        // when
        payloadsInOrderByTopic.forEach { (topic, payloads) ->
            payloads.forEach { payload ->
                transactionManager.doInNewTransaction {
                    kotbox.schedule(
                        taskDefinition = TestTask,
                        payload = payload,
                        topic = topic
                    )
                }
            }
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            payloadsInOrderByTopic.forEach { (topic, payloads) ->
                assertThat(testTask.payloads[topic].orEmpty()).containsExactlyElementsOf(payloads)
                assertThat(testTask.failurePayloads).isEmpty()
            }
        }
    }

    @Test
    fun `should block entry without topic on failure`() {
        // given
        val startTime = clock.instant
        testTask.init(
            beforeExecute = { _, _ -> throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Block
        )
        val payload = TestTask.Payload("message")

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = payload
            )
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = assertNotNull(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }.singleOrNull())
            entry.assertEquals(
                KotboxEntry(
                    id = entry.id,
                    version = 3,
                    idempotencyKey = entry.idempotencyKey,
                    taskDefinitionId = TestTask.id,
                    status = KotboxEntry.Status.BLOCKED,
                    topic = null,
                    creationTime = startTime,
                    lastAttemptTime = clock.instant,
                    nextAttemptTime = clock.instant + properties.entryVisibilityTimeout,
                    attempts = 1,
                    payload = payloadSerializer.serialize(payload)
                )
            )
            assertThat(testTask.failurePayloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.payloads.isEmpty())
        }
    }

    @Test
    fun `should block entire topic when first entry of topic is blocked`() {
        // given
        testTask.init(
            beforeExecute = { _, _ -> throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Block
        )
        val topic = "topic"
        val payloads = (1..5).map {
            TestTask.Payload("message-$it")
        }

        // when
        payloads.forEach { payload ->
            transactionManager.doInNewTransaction {
                kotbox.schedule(
                    taskDefinition = TestTask,
                    payload = payload,
                    topic = topic
                )
            }
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.failurePayloads["topic"].orEmpty()).containsExactly(payloads.first())
            assertThat(testTask.payloads.isEmpty())
            assertThat(
                transactionManager.doInNewTransaction { transactionContext ->
                    kotboxStore.fetchNextInAllTopics(
                        now = clock.instant,
                        batchSize = Int.MAX_VALUE,
                        transactionContext = transactionContext
                    )
                }
            ).isEmpty()
        }
    }

    @Test
    fun `should retry entry without topic on failure`() {
        // given
        val startTime = clock.instant
        val retryAtTime = clock.instant.plusSeconds(10)
        val shouldFail = AtomicBoolean(true)
        testTask.init(
            beforeExecute = { _, _ -> if (shouldFail.get()) throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Retry(retryAtTime)
        )
        val payload = TestTask.Payload("message")

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = payload
            )
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = assertNotNull(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }.singleOrNull())
            entry.assertEquals(
                KotboxEntry(
                    id = entry.id,
                    version = 3,
                    idempotencyKey = entry.idempotencyKey,
                    taskDefinitionId = TestTask.id,
                    status = KotboxEntry.Status.PENDING,
                    topic = null,
                    creationTime = startTime,
                    lastAttemptTime = clock.instant,
                    nextAttemptTime = retryAtTime,
                    attempts = 1,
                    payload = payloadSerializer.serialize(payload)
                )
            )
            assertThat(testTask.failurePayloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.payloads.isEmpty())
        }

        shouldFail.set(false)
        testTask.failurePayloads.clear()
        clock.instant = retryAtTime.plusSeconds(1)

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = assertNotNull(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }.singleOrNull())
            entry.assertEquals(
                KotboxEntry(
                    id = entry.id,
                    version = 5,
                    idempotencyKey = entry.idempotencyKey,
                    taskDefinitionId = TestTask.id,
                    status = KotboxEntry.Status.PROCESSED,
                    topic = null,
                    creationTime = startTime,
                    lastAttemptTime = clock.instant,
                    nextAttemptTime = clock.instant + properties.retentionDuration,
                    attempts = 2,
                    payload = payloadSerializer.serialize(payload)
                )
            )
            assertThat(testTask.payloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.failurePayloads.isEmpty())
        }
    }

    @Test
    fun `should retry entry with topic on failure`() {
        // given
        val retryAtTime = clock.instant.plusSeconds(10)
        val shouldFail = AtomicBoolean(true)
        testTask.init(
            beforeExecute = { _, _ -> if (shouldFail.get()) throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Retry(retryAtTime)
        )
        val topic = "topic"
        val payloads = (1..5).map {
            TestTask.Payload("message-$it")
        }

        // when
        payloads.forEach { payload ->
            transactionManager.doInNewTransaction {
                kotbox.schedule(
                    taskDefinition = TestTask,
                    payload = payload,
                    topic = topic
                )
            }
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(
                transactionManager.doInNewTransaction { transactionContext ->
                    kotboxStore.fetchNextInAllTopics(
                        now = clock.instant,
                        batchSize = Int.MAX_VALUE,
                        transactionContext = transactionContext
                    )
                }
            ).isEmpty()
            assertThat(testTask.failurePayloads[topic].orEmpty()).containsExactly(payloads.first())
        }

        shouldFail.set(false)
        testTask.failurePayloads.clear()
        clock.instant = retryAtTime.plusSeconds(1)

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[topic].orEmpty()).containsExactlyElementsOf(payloads)
            assertThat(testTask.failurePayloads.isEmpty())
        }
    }

    @Test
    fun `should ignore entry without topic on failure`() {
        // given
        val startTime = clock.instant
        testTask.init(
            beforeExecute = { _, _ -> throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Ignore
        )
        val payload = TestTask.Payload("message")

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(
                taskDefinition = TestTask,
                payload = payload
            )
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = assertNotNull(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }.singleOrNull())
            entry.assertEquals(
                KotboxEntry(
                    id = entry.id,
                    version = 3,
                    idempotencyKey = entry.idempotencyKey,
                    taskDefinitionId = TestTask.id,
                    status = KotboxEntry.Status.PROCESSED,
                    topic = null,
                    creationTime = startTime,
                    lastAttemptTime = clock.instant,
                    nextAttemptTime = clock.instant + properties.retentionDuration,
                    attempts = 1,
                    payload = payloadSerializer.serialize(payload)
                )
            )
            assertThat(testTask.failurePayloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.payloads.isEmpty())
        }
    }

    @Test
    fun `should ignore entry with topic on failure`() {
        // given
        testTask.init(
            beforeExecute = { payload, _ -> if (payload.message == "message-1") throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Ignore
        )
        val topic = "topic"
        val payloads = (1..5).map {
            TestTask.Payload("message-$it")
        }

        // when
        payloads.forEach { payload ->
            transactionManager.doInNewTransaction {
                kotbox.schedule(
                    taskDefinition = TestTask,
                    payload = payload,
                    topic = topic
                )
            }
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[topic].orEmpty()).containsExactlyElementsOf(payloads.drop(1))
            assertThat(testTask.failurePayloads[topic].orEmpty()).containsExactly(payloads.first())
        }
    }

    @Test
    fun `should handle deserialization failure`() {
        // given
        val payload = """{"error": "error"}"""

        // when
        transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.insert(
                listOf(
                    KotboxEntry(
                        id = -1,
                        version = 0,
                        idempotencyKey = UUID.randomUUID().toString(),
                        taskDefinitionId = TestTask.id,
                        status = KotboxEntry.Status.PENDING,
                        topic = null,
                        creationTime = clock.instant,
                        lastAttemptTime = null,
                        nextAttemptTime = clock.instant,
                        attempts = 0,
                        payload = payload
                    )
                ),
                transactionContext
            )
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.deserializationFailurePayloads[""].orEmpty()).containsExactly(payload)
        }
    }

    @Test
    fun `should handle unknown task`() {
        // given
        val unknownTaskEntry = KotboxEntry(
            id = -1,
            version = 0,
            idempotencyKey = UUID.randomUUID().toString(),
            taskDefinitionId = "unknownTaskDefinitionId",
            status = KotboxEntry.Status.PENDING,
            topic = null,
            creationTime = clock.instant,
            lastAttemptTime = null,
            nextAttemptTime = clock.instant,
            attempts = 0,
            payload = "{}"
        )

        // when
        transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.insert(listOf(unknownTaskEntry), transactionContext)
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = assertNotNull(kotboxUnknownTaskHandler.unknownEntries.singleOrNull())
            entry.assertEquals(
                unknownTaskEntry.copy(
                    id = entry.id,
                    version = entry.version,
                    nextAttemptTime = clock.instant + properties.entryVisibilityTimeout
                )
            )
        }
    }

    @Test
    fun `should delete processed and expired entries`() {
        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(TestTask, payload = TestTask.Payload("test"))
        }
        clock.instant = clock.instant.plusSeconds(1)
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }).hasSize(1)
            assertThat(testTask.payloads).hasSize(1)
        }

        // then
        clock.instant = clock.instant.plusSeconds(5) + properties.retentionDuration
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(transactionManager.doInNewTransaction {
                kotboxStore.fetchAll(it)
            }).isEmpty()
        }
    }

    @Test
    fun `should process entry when it's unblocked`() {
        // given
        val shouldFail = AtomicBoolean(true)
        testTask.init(
            beforeExecute = { _, _ -> if (shouldFail.get()) throw RuntimeException() },
            onFailureAction = KotboxTask.OnFailureAction.Block
        )
        val payload = TestTask.Payload("test")
        transactionManager.doInNewTransaction {
            kotbox.schedule(TestTask, payload = payload)
        }
        clock.instant = clock.instant.plusSeconds(1)
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.failurePayloads[""].orEmpty()).containsExactly(payload)
        }
        testTask.reset()
        val entryId = transactionManager.doInNewTransaction {
            kotboxStore.fetchAll(it)
        }.single().id

        // when
        shouldFail.set(false)
        transactionManager.doInNewTransaction {
            kotbox.unblock(entryId)
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[""].orEmpty()).containsExactly(payload)
            assertThat(testTask.failurePayloads).isEmpty()
        }
    }

    @Test
    fun `should be possible to use Kotbox from the task`() {
        // given
        val payload1 = TestTask.Payload("message-1")
        val payload2 = TestTask.Payload("message-2")
        val payload2AtTime = clock.instant.plusSeconds(10)
        testTask.init(
            beforeExecute = { payload, kotbox ->
                if (payload == payload1) kotbox.schedule(TestTask, payload2, atTime = payload2AtTime)
            })

        // when
        transactionManager.doInNewTransaction {
            kotbox.schedule(TestTask, payload = payload1)
        }
        clock.instant = clock.instant.plusSeconds(1)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[""].orEmpty()).containsExactly(payload1)
        }

        clock.instant = payload2AtTime.plusSeconds(1)

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(testTask.payloads[""].orEmpty()).containsExactly(payload1, payload2)
        }
    }

    override val jdbcUrl: String get() = postgresql.jdbcUrl
    override val jdbcUsername: String get() = postgresql.username
    override val jdbcPassword: String get() = postgresql.password

    companion object {
        @Container
        @JvmStatic
        val postgresql = PostgreSQLContainer("postgres:17-alpine")
    }
}