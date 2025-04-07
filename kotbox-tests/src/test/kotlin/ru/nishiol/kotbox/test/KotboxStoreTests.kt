package ru.nishiol.kotbox.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.OptimisticLockException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class KotboxStoreTests : AbstractIntegrationTest(
    shouldStartKotbox = false
) {
    private val visiblePendingEntryWithoutTopicKeys = (1..10).map {
        "visible-pending-no-topic-$it"
    }

    private val invisibleOrNotPendingEntryWithoutTopicKeys = (1..5).map {
        "invisible-or-not-pending-no-topic-$it"
    }

    private val visiblePendingEntryWithTopicKeysByTopic = (1..10).map { topicIdx ->
        "topic-$topicIdx" to (1..10).map { "visible-pending-with-topic-$topicIdx-$it" }
    }.associate { it.first to it.second }
    private val visiblePendingFirstEntryInTopicKeys =
        visiblePendingEntryWithTopicKeysByTopic.map { it.value.first() }

    private val invisibleOrNotPendingEntryWithTopicKeysByTopic = (1..10).map { topicIdx ->
        "topic-$topicIdx" to (1..5).map { "invisible-or-not-pending-with-topic-$topicIdx-$it" }
    }.associate { it.first to it.second }

    private val blockedTopicEntryKeys = (1..5).map {
        "blocked-topic-$it"
    }

    private val allKeys = visiblePendingEntryWithoutTopicKeys +
            invisibleOrNotPendingEntryWithoutTopicKeys +
            blockedTopicEntryKeys +
            visiblePendingEntryWithTopicKeysByTopic.values.flatten() +
            invisibleOrNotPendingEntryWithTopicKeysByTopic.values.flatten()

    @BeforeEach
    fun insertFixtures() {
        transactionManager.doInNewTransaction {
            kotboxStore.insert(
                entries = generateVisiblePendingEntriesWithoutTopic() +
                        generateInvisibleOrNotPendingEntriesWithoutTopic() +
                        generateVisiblePendingEntriesWithTopic() +
                        generateInvisibleOrNotPendingEntriesWithTopic() +
                        generateBlockedTopicEntries(),
                transactionContext = it
            )
        }
    }

    private fun generateVisiblePendingEntriesWithoutTopic(): List<KotboxEntry> =
        visiblePendingEntryWithoutTopicKeys.mapIndexed { idx, key ->
            entry(
                topic = null,
                idempotencyKey = key,
                status = KotboxEntry.Status.PENDING,
                visibleAt = clock.instant.minus(idx + 1L, ChronoUnit.MINUTES)
            )
        }

    private fun generateInvisibleOrNotPendingEntriesWithoutTopic(): List<KotboxEntry> {
        val keys = invisibleOrNotPendingEntryWithoutTopicKeys
        return listOf(
            visibleEntry(topic = null, idempotencyKey = keys[0], status = KotboxEntry.Status.PROCESSED),
            visibleEntry(topic = null, idempotencyKey = keys[1], status = KotboxEntry.Status.BLOCKED),
            invisibleEntry(topic = null, idempotencyKey = keys[2], status = KotboxEntry.Status.PENDING),
            invisibleEntry(topic = null, idempotencyKey = keys[3], status = KotboxEntry.Status.PROCESSED),
            invisibleEntry(topic = null, idempotencyKey = keys[4], status = KotboxEntry.Status.BLOCKED)
        )
    }

    private fun generateVisiblePendingEntriesWithTopic(): List<KotboxEntry> =
        visiblePendingEntryWithTopicKeysByTopic.keys.flatMapIndexed { topicIdx, topic ->
            visiblePendingEntryWithTopicKeysByTopic.getValue(topic).mapIndexed { keyIdx, key ->
                entry(
                    topic = topic,
                    idempotencyKey = key,
                    status = KotboxEntry.Status.PENDING,
                    visibleAt = if (keyIdx == 0) {
                        // each topic becomes visible at different minutes
                        clock.instant.minus(topicIdx + 1L, ChronoUnit.MINUTES)
                    } else {
                        // makes non-first topic entries "visible" to check that the first entry in the topic controls the visibility of the whole topic
                        clock.instant.minus(1, ChronoUnit.DAYS)
                    }
                )
            }
        }

    private fun generateInvisibleOrNotPendingEntriesWithTopic(): List<KotboxEntry> {
        val keysByTopic = invisibleOrNotPendingEntryWithTopicKeysByTopic
        return keysByTopic.keys.flatMap {
            val keys = keysByTopic.getValue(it)
            listOf(
                visibleEntry(topic = it, idempotencyKey = keys[0], status = KotboxEntry.Status.PROCESSED),
                visibleEntry(topic = it, idempotencyKey = keys[1], status = KotboxEntry.Status.BLOCKED),
                invisibleEntry(topic = it, idempotencyKey = keys[2], status = KotboxEntry.Status.PENDING),
                invisibleEntry(
                    topic = it,
                    idempotencyKey = keys[3],
                    status = KotboxEntry.Status.PROCESSED
                ),
                invisibleEntry(topic = it, idempotencyKey = keys[4], status = KotboxEntry.Status.BLOCKED)
            )
        }
    }

    private fun generateBlockedTopicEntries(): List<KotboxEntry> {
        val keys = blockedTopicEntryKeys
        return listOf(
            visibleEntry(
                topic = "blocked-topic",
                idempotencyKey = keys[0],
                status = KotboxEntry.Status.BLOCKED
            ),
            visibleEntry(
                topic = "blocked-topic",
                idempotencyKey = keys[1],
                status = KotboxEntry.Status.PENDING
            ),
            visibleEntry(
                topic = "blocked-topic",
                idempotencyKey = keys[2],
                status = KotboxEntry.Status.PENDING
            ),
            visibleEntry(
                topic = "blocked-topic",
                idempotencyKey = keys[3],
                status = KotboxEntry.Status.PENDING
            ),
            visibleEntry(
                topic = "blocked-topic",
                idempotencyKey = keys[4],
                status = KotboxEntry.Status.PENDING
            )
        )
    }

    @Test
    fun fetchAllEntries() {
        // when
        val entriesByKey = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).associateBy { it.idempotencyKey }
        }


        // then
        assertThat(entriesByKey).containsOnlyKeys(allKeys)
    }

    @Test
    fun fetchWithoutTopic() {
        // when
        val entriesByKey =
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.fetchWithoutTopic(clock.instant, Int.MAX_VALUE, transactionContext)
                    .associateBy { it.idempotencyKey }
            }

        // then
        assertThat(entriesByKey).containsOnlyKeys(visiblePendingEntryWithoutTopicKeys)
    }

    @Test
    fun `fetchWithoutTopic() respects batchSize`() {
        // given
        val batchSize = 3

        // when
        val entriesByKey =
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.fetchWithoutTopic(clock.instant, batchSize, transactionContext)
                    .associateBy { it.idempotencyKey }
            }

        // then
        assertThat(entriesByKey).hasSize(batchSize)
        assertThat(visiblePendingEntryWithoutTopicKeys).containsAll(entriesByKey.keys)
    }

    @Test
    fun `fetchWithoutTopic() respects now`() {
        // given
        val now = clock.instant.minus(1, ChronoUnit.MINUTES)

        // when
        val entriesByKey =
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.fetchWithoutTopic(now, Int.MAX_VALUE, transactionContext)
                    .associateBy { it.idempotencyKey }
            }

        // then
        assertThat(entriesByKey).hasSize(visiblePendingEntryWithoutTopicKeys.size - 1)
        assertThat(entriesByKey.map { it.value.idempotencyKey }).doesNotContain("visible-pending-no-topic-1")
        assertThat(visiblePendingEntryWithoutTopicKeys).containsAll(entriesByKey.keys)
    }

    @Test
    fun `fetchWithoutTopic() skips locked entries`() {
        // given
        val fetchWithoutTopicByFirstThreadCompleted = CountDownLatch(1)
        val fetchWithoutTopicBySecondThreadCompleted = CountDownLatch(1)

        // when
        val entriesBeforeLock = AtomicReference<List<KotboxEntry>>()
        val thread1 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                val entries = kotboxStore.fetchWithoutTopic(clock.instant, 3, transactionContext)
                entriesBeforeLock.set(entries)
                fetchWithoutTopicByFirstThreadCompleted.countDown()
                fetchWithoutTopicBySecondThreadCompleted.await()
            }
        }.apply { start() }

        val entriesAfterLock = AtomicReference<List<KotboxEntry>>()
        val thread2 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                fetchWithoutTopicByFirstThreadCompleted.await()
                val entries = kotboxStore.fetchWithoutTopic(clock.instant, Int.MAX_VALUE, transactionContext)
                entriesAfterLock.set(entries)
                fetchWithoutTopicBySecondThreadCompleted.countDown()
            }
        }.apply { start() }

        thread1.join()
        thread2.join()

        val entryBeforeLockKeys = entriesBeforeLock.get().map { it.idempotencyKey }
        val entryAfterLockKeys = entriesAfterLock.get().map { it.idempotencyKey }

        // then
        assertThat(entryBeforeLockKeys).hasSize(3).doesNotContainAnyElementsOf(entryAfterLockKeys)
        assertThat(entryAfterLockKeys).hasSize(7).doesNotContainAnyElementsOf(entryBeforeLockKeys)

        val allEntryKeys = entryBeforeLockKeys + entryAfterLockKeys
        assertThat(allEntryKeys).containsExactlyInAnyOrderElementsOf(visiblePendingEntryWithoutTopicKeys)
    }

    @Test
    fun fetchNextInAllTopics() {
        // when
        val entriesByKey =
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.fetchNextInAllTopics(clock.instant, Int.MAX_VALUE, transactionContext)
                    .associateBy { it.idempotencyKey }
            }

        // then
        assertThat(entriesByKey).containsOnlyKeys(visiblePendingFirstEntryInTopicKeys)
    }

    @Test
    fun `fetchNextInAllTopics() respects batchSize`() {
        // given
        val batchSize = 3

        // when
        val entriesByKey = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchNextInAllTopics(clock.instant, batchSize, transactionContext)
                .associateBy { it.idempotencyKey }
        }

        // then
        assertThat(entriesByKey).hasSize(batchSize)
        assertThat(visiblePendingFirstEntryInTopicKeys).containsAll(entriesByKey.keys)
    }

    @Test
    fun `fetchNextInAllTopics() respects now`() {
        // given
        val now = clock.instant.minus(1, ChronoUnit.MINUTES)

        // when
        val entriesByKey =
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.fetchNextInAllTopics(now, Int.MAX_VALUE, transactionContext)
                    .associateBy { it.idempotencyKey }
            }

        // then
        assertThat(entriesByKey).hasSize(visiblePendingFirstEntryInTopicKeys.size - 1)
        assertThat(entriesByKey.map { it.value.topic }).doesNotContain("topic-1")
        assertThat(visiblePendingFirstEntryInTopicKeys).containsAll(entriesByKey.keys)
    }

    @Test
    fun `fetchNextInAllTopics() skips locked entries`() {
        // given
        val fetchNextInAllTopicsByFirstThreadCompleted = CountDownLatch(1)
        val fetchNextInAllTopicsBySecondThreadCompleted = CountDownLatch(1)

        // when
        val entriesBeforeLock = AtomicReference<List<KotboxEntry>>()
        val thread1 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                val entries = kotboxStore.fetchNextInAllTopics(clock.instant, 3, transactionContext)
                entriesBeforeLock.set(entries)
                fetchNextInAllTopicsByFirstThreadCompleted.countDown()
                fetchNextInAllTopicsBySecondThreadCompleted.await()
            }
        }.apply { start() }

        val entriesAfterLock = AtomicReference<List<KotboxEntry>>()
        val thread2 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                fetchNextInAllTopicsByFirstThreadCompleted.await()
                val entries =
                    kotboxStore.fetchNextInAllTopics(clock.instant, Int.MAX_VALUE, transactionContext)
                entriesAfterLock.set(entries)
                fetchNextInAllTopicsBySecondThreadCompleted.countDown()
            }
        }.apply { start() }

        thread1.join()
        thread2.join()

        val entryBeforeLockKeys = entriesBeforeLock.get().map { it.idempotencyKey }
        val entryAfterLockKeys = entriesAfterLock.get().map { it.idempotencyKey }

        // then
        assertThat(entryBeforeLockKeys).hasSize(3).doesNotContainAnyElementsOf(entryAfterLockKeys)
        assertThat(entryAfterLockKeys).hasSize(7).doesNotContainAnyElementsOf(entryBeforeLockKeys)

        val allEntryKeys = entryBeforeLockKeys + entryAfterLockKeys
        assertThat(allEntryKeys).containsExactlyInAnyOrderElementsOf(visiblePendingFirstEntryInTopicKeys)
    }

    @Test
    fun update() {
        // given
        val entriesById = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext)
                .filter { it.idempotencyKey in visiblePendingEntryWithoutTopicKeys }
                .associateBy { it.id }
        }

        val now = Instant.now()
        val updatedEntriesById = entriesById.mapValues {
            it.value.copy(
                idempotencyKey = UUID.randomUUID().toString(), // should be ignored
                taskDefinitionId = UUID.randomUUID().toString(), // should be ignored
                status = KotboxEntry.Status.PROCESSED,
                topic = UUID.randomUUID().toString(), // should be ignored
                creationTime = Instant.EPOCH, // should be ignored
                lastAttemptTime = now,
                nextAttemptTime = clock.instant.plusSeconds(10),
                attempts = 2,
                payload = """{"test": "test"}""" // should be ignored
            )
        }

        // when
        val actualUpdatedEntriesById = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.update(updatedEntriesById.values.toList(), transactionContext).associateBy { it.id }
        }
        val updatedEntriesFromFetchAllById = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).filter { it.id in entriesById.keys }
                .associateBy { it.id }
        }

        // then

        // ignored fields new values are returned from update, but they are not saved to store
        actualUpdatedEntriesById.forEach { (id, entry) ->
            entry.assertEquals(
                updatedEntriesById.getValue(id)
                    .let { it.copy(version = it.version + 1) }) // version is increased on update,
        }

        updatedEntriesFromFetchAllById.forEach { (id, entry) ->
            entry.assertEquals(
                entriesById.getValue(id).let {
                    it.copy(
                        version = it.version + 1, // version is increased on update
                        status = KotboxEntry.Status.PROCESSED,
                        lastAttemptTime = now,
                        nextAttemptTime = clock.instant.plusSeconds(10),
                        attempts = 2
                    )
                }
            )
        }
    }

    @Test
    fun `single update() throw OptimisticLockException if entry's version differs`() {
        // given
        val entry = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).first()
        }

        // when and then
        assertFailsWith<OptimisticLockException> {
            transactionManager.doInNewTransaction { transactionContext ->
                kotboxStore.update(entry.copy(version = entry.version + 1), transactionContext)
            }
        }
    }

    @Test
    fun `batch update() shouldn't update entries with different version`() {
        // given
        val (firstEntry, secondEntry) = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).take(2)
        }

        // when
        val updatedEntries = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.update(
                listOf(
                    firstEntry.copy(version = firstEntry.version + 1),
                    secondEntry.copy(attempts = secondEntry.attempts + 1)
                ),
                transactionContext
            )
        }

        // then
        assertThat(updatedEntries).hasSize(1)
        val updatedEntry = updatedEntries.first()
        updatedEntry.assertEquals(
            secondEntry.copy(version = secondEntry.version + 1, attempts = secondEntry.attempts + 1)
        )
    }

    @Test
    fun tryLock() {
        // given
        val tryLockByFirstThreadCompleted = CountDownLatch(1)
        val tryLockBySecondThreadCompleted = CountDownLatch(1)

        val entry = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).first()
        }

        // when
        val thread1LockResult = AtomicBoolean()
        val thread1 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                thread1LockResult.set(kotboxStore.tryLock(entry, transactionContext))
                tryLockByFirstThreadCompleted.countDown()
                tryLockBySecondThreadCompleted.await()
            }
        }.apply { start() }

        val thread2LockResult = AtomicBoolean()
        val thread2 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                tryLockByFirstThreadCompleted.await()
                thread2LockResult.set(kotboxStore.tryLock(entry, transactionContext))
                tryLockBySecondThreadCompleted.countDown()
            }
        }.apply { start() }

        thread1.join()
        thread2.join()

        // then
        assertTrue(thread1LockResult.get())
        assertFalse(thread2LockResult.get())
    }

    @Test
    fun `tryLock() should return false if entry's version differs`() {
        // given
        val entry = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).first()
        }

        // when
        val result = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.tryLock(entry.copy(version = entry.version + 1), transactionContext)
        }

        // then
        assertFalse(result)
    }

    @Test
    fun deleteProcessedAndExpired() {
        // given
        val expectedDeletedKeys = invisibleOrNotPendingEntryWithTopicKeysByTopic.map { it.value.first() } +
                invisibleOrNotPendingEntryWithoutTopicKeys.first()

        // when
        val result = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.deleteProcessedAndExpired(clock.instant, transactionContext)
        }
        val entriesAfterDelete = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext)
        }

        // then
        assertEquals(11, result)
        assertThat(entriesAfterDelete.map { it.idempotencyKey }).containsExactlyInAnyOrderElementsOf(allKeys - expectedDeletedKeys.toSet())
    }

    @Test
    fun findAndLockById() {
        // given
        val entry = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.fetchAll(transactionContext).first()
        }
        val entryFindAndLockCompletedByThread1 = CountDownLatch(1)
        val entryTryLockCompletedByThread2 = CountDownLatch(1)

        // when
        val foundAndLockedEntry = AtomicReference<KotboxEntry>()
        val thread1 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                foundAndLockedEntry.set(kotboxStore.findAndLockById(entry.id, transactionContext))
                entryFindAndLockCompletedByThread1.countDown()
                entryTryLockCompletedByThread2.await()
            }
        }.apply { start() }

        val thread2LockResult = AtomicBoolean()
        val thread2 = Thread {
            transactionManager.doInNewTransaction { transactionContext ->
                entryFindAndLockCompletedByThread1.await()
                thread2LockResult.set(kotboxStore.tryLock(entry, transactionContext))
                entryTryLockCompletedByThread2.countDown()
            }
        }.apply { start() }

        thread1.join()
        thread2.join()

        // then
        entry.assertEquals(foundAndLockedEntry.get())
        assertFalse(thread2LockResult.get())

    }

    private fun visibleEntry(
        topic: String?,
        idempotencyKey: String,
        status: KotboxEntry.Status
    ) = entry(
        topic = topic,
        idempotencyKey = idempotencyKey,
        visibleAt = clock.instant.minus(5, ChronoUnit.MINUTES),
        status = status
    )

    private fun invisibleEntry(
        topic: String?,
        idempotencyKey: String,
        status: KotboxEntry.Status
    ) = entry(
        topic = topic,
        idempotencyKey = idempotencyKey,
        visibleAt = clock.instant.plus(5, ChronoUnit.MINUTES),
        status = status
    )

    private fun entry(
        topic: String?,
        idempotencyKey: String,
        visibleAt: Instant,
        status: KotboxEntry.Status
    ) = KotboxEntry(
        id = -1,
        version = 1,
        idempotencyKey = idempotencyKey,
        taskDefinitionId = "taskDefinitionId",
        status = status,
        topic = topic,
        creationTime = Instant.now(),
        lastAttemptTime = null,
        nextAttemptTime = visibleAt,
        attempts = 0,
        payload = "{}"
    )
}