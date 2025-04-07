package ru.nishiol.kotbox

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.withLoggingContext
import ru.nishiol.kobject.KObject
import ru.nishiol.kobject.get
import ru.nishiol.kobject.set
import ru.nishiol.kotbox.store.KotboxStore
import ru.nishiol.kotbox.task.KotboxTask
import ru.nishiol.kotbox.task.KotboxUnknownTaskHandler
import ru.nishiol.kotbox.transaction.TransactionContext
import ru.nishiol.kotbox.transaction.TransactionManager
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

class Kotbox(
    private val tasksProvider: () -> Collection<KotboxTask<*>>,
    private val payloadSerializer: PayloadSerializer,
    private val kotboxStore: KotboxStore,
    private val kotboxUnknownTaskHandler: KotboxUnknownTaskHandler,
    private val transactionManager: TransactionManager,
    private val clock: Clock,
    private val taskExecutorService: ExecutorService,
    private val properties: KotboxProperties
) {
    private class EntriesToInsertHolder(
        val entries: MutableSet<KotboxEntry> = mutableSetOf()
    ) {
        companion object : KObject.Key<EntriesToInsertHolder>
    }

    private val logger = KotlinLogging.logger {}

    private val tasksByDefinitionId by lazy {
        collectTasksByDefinitionId()
    }

    private inner class NotEmptyState {
        private val lock = ReentrantLock()
        private val notEmptyCondition = lock.newCondition()
        private var empty = false

        fun notifyNotEmpty() {
            logger.debug { "Notifying Kotbox is not empty" }
            lock.withLock {
                empty = false
                notEmptyCondition.signalAll()
            }
        }

        fun waitNotEmptyAndResetState() = lock.withLock {
            if (empty) {
                logger.debug { "Waiting until Kotbox is not empty" }
                var sleepDurationNanos = properties.poll.sleepDuration.toNanos()
                while (empty && sleepDurationNanos > 0) {
                    sleepDurationNanos = notEmptyCondition.awaitNanos(sleepDurationNanos)
                }
                if (!empty) {
                    logger.debug { "Finish waiting until Kotbox is not empty by notification" }
                }
                if (sleepDurationNanos <= 0) {
                    logger.debug { "Finish waiting until Kotbox is not empty by timeout" }
                }
            }
            empty = true
        }
    }

    private val notEmptyState = NotEmptyState()

    private val running = AtomicBoolean(false)

    private val pollExecutor = ThreadPoolExecutor(
        /* corePoolSize = */ 1,
        /* maximumPoolSize = */ 1,
        /* keepAliveTime = */ 0L,
        /* unit = */ TimeUnit.MILLISECONDS,
        /* workQueue = */ ArrayBlockingQueue(1),
        /* threadFactory = */ {
            Thread(it, "kotbox-poll")
        },
        /* handler = */ DiscardPolicy()
    )

    /**
     * Schedules a new Kotbox task.
     *
     * @param taskDefinition The task definition.
     * @param payload The payload associated with the task.
     * @param idempotencyKey The unique idempotency key for deduplication.
     * @param topic The topic associated with the task. All tasks in the topic are processed in order.
     * @param atTime The time at which the task should be executed.
     */
    fun <T : KotboxTask.Definition<P>, P : Any> schedule(
        taskDefinition: T,
        payload: P,
        idempotencyKey: String = UUID.randomUUID().toString(),
        topic: String? = null,
        atTime: Instant? = null
    ) = withMdc(
        entryId = null,
        entryIdempotencyKey = idempotencyKey,
        entryTopic = topic,
        taskDefinitionId = taskDefinition.id
    ) {
        check(running.get()) { "Kotbox is not running" }
        transactionManager.doInCurrentTransaction { transactionContext ->
            logger.info { "Schedule taskDefinitionId=${taskDefinition.id} with idempotencyKey=$idempotencyKey" }
            val entriesToInsertHolder = transactionContext.obtainEntriesToInsertHolder()
            val now = Instant.now(clock)
            entriesToInsertHolder.entries += KotboxEntry(
                id = -1,
                version = 1,
                idempotencyKey = idempotencyKey,
                taskDefinitionId = taskDefinition.id,
                status = KotboxEntry.Status.PENDING,
                topic = topic,
                creationTime = now,
                lastAttemptTime = null,
                nextAttemptTime = atTime ?: now,
                attempts = 0,
                payload = payloadSerializer.serialize(payload)
            )
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        check(!pollExecutor.isTerminating && !pollExecutor.isTerminated) {
            "Kotbox is stopping or stopped already"
        }

        logger.info { "Starting Kotbox" }

        // init lazy property
        tasksByDefinitionId.size

        if (properties.table.createSchema) {
            transactionManager.doInNewTransaction {
                kotboxStore.createKotboxTableIfNotExists(it)
            }
        }

        pollExecutor.execute {
            while (running.get()) {
                notEmptyState.waitNotEmptyAndResetState()
                if (tryPoll() > 0) {
                    notEmptyState.notifyNotEmpty()
                }
            }
            logger.info { "Kotbox is paused" }
        }
    }

    fun shutdown() {
        pause()
        logger.info { "Stopping Kotbox" }
        pollExecutor.shutdown()
        logger.info { "Kotbox is stopped" }
    }

    fun pause() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        logger.info { "Pausing Kotbox" }
    }

    /**
     * Unblocks Kotbox entry by id and schedules it for processing.
     */
    fun unblock(entryId: Long): Unit = withMdc(entryId = entryId) {
        logger.info { "Unblocking entry with entryId=$entryId" }
        transactionManager.doInCurrentTransaction { transactionContext ->
            val entry = kotboxStore.findAndLockById(entryId, transactionContext)
            if (entry == null) {
                logger.info { "No entry found for entryId=$entryId" }
                return@doInCurrentTransaction
            }
            withMdc(entry) {
                makePending(entry, transactionContext)
                notEmptyState.notifyNotEmpty()
            }
        }
    }

    fun tryPoll(): Int = try {
        poll()
    } catch (ex: InterruptedException) {
        throw ex
    } catch (ex: Exception) {
        logger.warn(ex) { "Exception was thrown on poll" }
        0
    }

    private fun poll(): Int {
        logger.info { "Polling Kotbox" }

        val now = Instant.now(clock)

        val entriesToProcess = transactionManager.doInNewTransaction { transactionContext ->
            kotboxStore.deleteProcessedAndExpired(now, transactionContext)
            makeInvisible(
                entries = kotboxStore.fetchWithoutTopic(now, properties.poll.batchSize, transactionContext) +
                        kotboxStore.fetchNextInAllTopics(now, properties.poll.batchSize, transactionContext),
                now = now,
                transactionContext = transactionContext
            )
        }

        if (entriesToProcess.isEmpty()) {
            logger.info { "No entries to process" }
        } else {
            logger.info { "Submitting ${entriesToProcess.size} entries for processing" }
        }

        entriesToProcess.forEach {
            withMdc(it) {
                logger.debug { "Submitting entry for processing: $it" }
                taskExecutorService.execute {
                    tryProcessEntry(it)
                }
            }
        }

        return entriesToProcess.size
    }

    private fun tryProcessEntry(entry: KotboxEntry) = withMdc(entry) {
        logger.debug { "Starting processing entry: $entry" }
        val now = Instant.now(clock)
        val task = tasksByDefinitionId[entry.taskDefinitionId]
        if (task == null) {
            handleUnknownTask(entry, now)
            return@withMdc
        }
        lateinit var payload: Any
        try {
            transactionManager.doInNewTransaction { transactionContext ->
                if (!kotboxStore.tryLock(entry, transactionContext)) {
                    logger.debug { "Entry is locked, skipping: $entry" }
                    return@doInNewTransaction
                }
                payload = tryDeserializePayload(entry.payload, task.definition.payloadClass)
                tryExecuteTask(task, payload, entry)
                makeProcessed(entry, now, transactionContext)
            }
        } catch (ex: PayloadDeserializationException) {
            handleDeserializationFailure(ex, task, entry, now)
            return@withMdc
        } catch (ex: TaskExecutionException) {
            handleTaskExecutionFailure(ex, task, payload, entry, now)
            return@withMdc
        }
        // the next entry of the topic could become visible, so wakeup polling thread
        if (entry.topic != null) {
            notEmptyState.notifyNotEmpty()
        }
    }

    private fun tryDeserializePayload(payload: String, payloadClass: KClass<*>): Any = try {
        payloadSerializer.deserialize(payload, payloadClass)
    } catch (ex: Throwable) {
        throw PayloadDeserializationException(ex)
    }

    private fun handleDeserializationFailure(
        ex: PayloadDeserializationException,
        task: KotboxTask<Any>,
        entry: KotboxEntry,
        now: Instant
    ) {
        logger.error(ex.cause) { "Payload deserialization exception was thrown on entry processing" }
        applyOnFailureAction(entry, now) {
            task.onPayloadDeserializationFailure(entry, ex.cause)
        }
    }

    private fun tryExecuteTask(task: KotboxTask<Any>, payload: Any, entry: KotboxEntry) {
        logger.debug { "Executing task for entry: $entry" }
        try {
            task.execute(payload, entry.topic)
        } catch (ex: InterruptedException) {
            throw ex
        } catch (ex: Throwable) {
            throw TaskExecutionException(ex)
        }
        logger.debug { "Finish executing task for entry: $entry" }
    }

    private fun handleTaskExecutionFailure(
        ex: TaskExecutionException,
        task: KotboxTask<Any>,
        payload: Any,
        entry: KotboxEntry,
        now: Instant
    ) {
        logger.warn(ex.cause) { "Task execution exception was thrown on entry processing" }
        applyOnFailureAction(entry, now) {
            task.onFailure(payload, entry, ex)
        }
    }

    private fun handleUnknownTask(entry: KotboxEntry, now: Instant) {
        logger.warn { "Handle unknown taskDefinitionId=${entry.taskDefinitionId}" }
        applyOnFailureAction(entry, now) {
            kotboxUnknownTaskHandler.handle(entry)
        }
    }

    private fun TransactionContext.obtainEntriesToInsertHolder(): EntriesToInsertHolder {
        val existingEntriesToInsertHolder = data[EntriesToInsertHolder]
        if (existingEntriesToInsertHolder != null) {
            return existingEntriesToInsertHolder
        }
        return initEntriesToInsertHolder()
    }

    private fun applyOnFailureAction(
        entry: KotboxEntry,
        now: Instant,
        actionProvider: () -> KotboxTask.OnFailureAction
    ) {
        transactionManager.doInNewTransaction { transactionContext ->
            when (val action = actionProvider()) {
                KotboxTask.OnFailureAction.Block -> applyBlockAction(entry, now, transactionContext)
                is KotboxTask.OnFailureAction.Retry -> applyRetryAction(
                    entry,
                    action,
                    now,
                    transactionContext
                )

                KotboxTask.OnFailureAction.Ignore -> makeProcessed(entry, now, transactionContext)
            }
        }
    }

    private fun applyBlockAction(
        entry: KotboxEntry,
        now: Instant,
        transactionContext: TransactionContext
    ) {
        logger.debug { "Blocking entry: $entry" }
        kotboxStore.update(
            entry.copy(
                status = KotboxEntry.Status.BLOCKED,
                attempts = entry.attempts + 1,
                lastAttemptTime = now
            ),
            transactionContext
        )
    }

    private fun applyRetryAction(
        entry: KotboxEntry,
        retry: KotboxTask.OnFailureAction.Retry,
        now: Instant,
        transactionContext: TransactionContext
    ) {
        logger.debug { "Schedule entry to retry at ${retry.atTime}: $entry" }
        kotboxStore.update(
            entry.copy(
                status = KotboxEntry.Status.PENDING,
                attempts = entry.attempts + 1,
                lastAttemptTime = now,
                nextAttemptTime = retry.atTime
            ),
            transactionContext
        )
    }

    private fun makeInvisible(
        entries: List<KotboxEntry>,
        now: Instant,
        transactionContext: TransactionContext
    ): List<KotboxEntry> {
        if (entries.isNotEmpty()) {
            logger.debug { "Making entries invisible: $entries" }
        }
        val invisibleEntries = kotboxStore.update(
            entries.map { it.copy(nextAttemptTime = now + properties.entryVisibilityTimeout) },
            transactionContext
        )
        if (invisibleEntries.isNotEmpty()) {
            logger.debug { "Entries made invisible: $invisibleEntries" }
        } else {
            logger.debug { "No entries made invisible" }
        }
        return invisibleEntries
    }

    private fun makeProcessed(
        entry: KotboxEntry,
        now: Instant,
        transactionContext: TransactionContext
    ): KotboxEntry {
        logger.debug { "Making entry processed: $entry" }
        return kotboxStore.update(
            entry.copy(
                status = KotboxEntry.Status.PROCESSED,
                attempts = entry.attempts + 1,
                lastAttemptTime = now,
                nextAttemptTime = now + properties.retentionDuration
            ),
            transactionContext
        )
    }

    private fun makePending(
        entry: KotboxEntry,
        transactionContext: TransactionContext
    ): KotboxEntry {
        logger.debug { "Making entry pending: $entry" }
        return kotboxStore.update(
            entry.copy(
                status = KotboxEntry.Status.PENDING,
                attempts = 0,
                lastAttemptTime = null,
                nextAttemptTime = Instant.now(clock)
            ),
            transactionContext
        )
    }

    private fun TransactionContext.initEntriesToInsertHolder(): EntriesToInsertHolder {
        val holder = EntriesToInsertHolder()
        data[EntriesToInsertHolder] = holder
        addActionBeforeCommit {
            kotboxStore.insert(holder.entries, this)
        }
        addActionAfterCommit {
            if (holder.entries.isNotEmpty()) {
                notEmptyState.notifyNotEmpty()
            }
        }
        return holder
    }

    private fun collectTasksByDefinitionId(): Map<String, KotboxTask<Any>> {
        val tasksByDefinitionId = tasksProvider().groupBy { it.definition.id }
        tasksByDefinitionId.forEach { (taskDefinitionId, tasks) ->
            check(tasks.size == 1) {
                "Multiple tasks found for taskDefinitionId=$taskDefinitionId: ${tasks.map { it::class.qualifiedName }} "
            }
        }
        @Suppress("UNCHECKED_CAST")
        return tasksByDefinitionId.mapValues { it.value.first() as KotboxTask<Any> }
    }

    private fun <T> withMdc(entry: KotboxEntry, body: () -> T): T = withMdc(
        entryId = entry.id,
        entryIdempotencyKey = entry.idempotencyKey,
        entryTopic = entry.topic,
        taskDefinitionId = entry.taskDefinitionId,
        body = body
    )

    private fun <T> withMdc(
        entryId: Long? = null,
        entryIdempotencyKey: String? = null,
        entryTopic: String? = null,
        taskDefinitionId: String? = null,
        body: () -> T
    ): T = withLoggingContext(
        "kotboxEntryId" to entryId?.toString(),
        "kotboxEntryIdempotencyKey" to entryIdempotencyKey,
        "kotboxEntryTopic" to entryTopic,
        "kotboxTaskDefinitionId" to taskDefinitionId,
        body = body
    )
}