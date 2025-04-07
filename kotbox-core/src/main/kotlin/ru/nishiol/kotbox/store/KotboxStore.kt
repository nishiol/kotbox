package ru.nishiol.kotbox.store

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.nishiol.kotbox.*
import ru.nishiol.kotbox.OptimisticLockException
import ru.nishiol.kotbox.transaction.TransactionContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Handles the persistence of Kotbox entries in the database.
 **/
class KotboxStore(
    private val dialectProvider: DialectProvider,
    properties: KotboxProperties
) {
    private val tableSchema = "\"${properties.table.schema}\""
    private val tableName = "\"${properties.table.name}\""
    private val tableQualifiedName = "$tableSchema.$tableName"

    private val logger = KotlinLogging.logger {}

    private val dialect by lazy { dialectProvider.getDialect() }

    private val insertSql by lazy {
        dialect.insert
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val updateSql by lazy {
        dialect.update
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val tryLockSql by lazy {
        dialect.tryLock
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val deleteProcessedExpiredSql by lazy {
        dialect.deleteProcessedAndExpired
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val fetchAllEntries by lazy {
        dialect.fetchAll
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val fetchWithoutTopic by lazy {
        dialect.fetchWithoutTopic
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val fetchNextInAllTopics by lazy {
        dialect.fetchNextInAllTopics
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val findAndLockById by lazy {
        dialect.findAndLockById
            .replace(Placeholders.TABLE_NAME, tableName)
            .replace(Placeholders.TABLE_SCHEMA, tableSchema)
    }

    private val createTable by lazy {
        dialect.createKotboxTable.map {
            it.replace(Placeholders.TABLE_NAME, tableName)
                .replace(Placeholders.TABLE_SCHEMA, tableSchema)
        }
    }

    /**
     * Creates the Kotbox table if it does not exist.
     *
     * This method executes the necessary SQL statements to create the table and indexes.
     * It ensures that the database schema is correctly initialized before using Kotbox.
     *
     * @param transactionContext The transaction context to execute the SQL command.
     */
    fun createKotboxTableIfNotExists(transactionContext: TransactionContext) {
        logger.info { "Creating $tableQualifiedName if not exists" }
        val connection = transactionContext.connection
        createTable.forEach { stmt ->
            connection.prepareStatement(stmt).use { it.execute() }
        }
        logger.info { "Finished creating $tableQualifiedName" }
    }

    /**
     * Fetches all Kotbox entries from the database.
     *
     * @param transactionContext The transaction context to execute the query.
     * @return A list of all stored Kotbox entries.
     */
    fun fetchAll(transactionContext: TransactionContext): List<KotboxEntry> {
        logger.debug { "fetchAll()" }
        return transactionContext.connection.executeQuery(fetchAllEntries) { it.toEntries() }
    }

    /**
     * Fetches Kotbox entries that have no assigned topic and are ready for processing.
     *
     * @param now The current timestamp used for filtering entries ready for processing.
     * @param batchSize The maximum number of entries to fetch in a single query.
     * @param transactionContext The transaction context to execute the query.
     * @return A list of Kotbox entries without a topic that are due for processing.
     */
    fun fetchWithoutTopic(
        now: Instant,
        batchSize: Int,
        transactionContext: TransactionContext
    ): List<KotboxEntry> {
        logger.debug { "fetchWithoutTopic: now=$now, batchSize=$batchSize" }
        return transactionContext.connection.executeQuery(fetchWithoutTopic, Timestamp.from(now), batchSize) {
            it.toEntries()
        }
    }

    /**
     * Fetches the next available Kotbox entry for each topic, ensuring ordering in task processing.
     *
     * @param now The current timestamp used for filtering entries ready for processing.
     * @param batchSize The maximum number of entries to fetch in a single query.
     * @param transactionContext The transaction context to execute the query.
     * @return A list of the next available Kotbox entries for each topic.
     */
    fun fetchNextInAllTopics(
        now: Instant,
        batchSize: Int,
        transactionContext: TransactionContext
    ): List<KotboxEntry> {
        logger.debug { "fetchNextInAllTopics: now=$now, batchSize=$batchSize" }
        return transactionContext.connection.executeQuery(fetchNextInAllTopics, Timestamp.from(now), batchSize) {
            it.toEntries()
        }
    }

    /**
     * Attempts to find and lock a Kotbox entry by its ID.
     *
     * @param entryId The ID of the entry to be locked.
     * @param transactionContext The transaction context to execute the query.
     * @return The locked Kotbox entry if found, otherwise null.
     */
    fun findAndLockById(entryId: Long, transactionContext: TransactionContext): KotboxEntry? {
        logger.debug { "findAndLockById: entryId=$entryId" }
        return transactionContext.connection.executeQuery(findAndLockById, entryId) { it.toEntries() }.firstOrNull()
    }

    /**
     * Inserts a collection of Kotbox entries into the database.
     *
     * If an entry with the same idempotency key already exists, it will not be inserted.
     *
     * @param entries The collection of entries to insert.
     * @param transactionContext The transaction context to execute the query.
     */
    fun insert(entries: Collection<KotboxEntry>, transactionContext: TransactionContext) {
        if (entries.isEmpty()) return
        logger.debug { "insert: entries=$entries" }
        transactionContext.connection.executeBatch(
            insertSql,
            entries.map { dialect.sqlArgsForColumns(it, Column.INSERT_COLUMNS) })
    }

    /**
     * Updates an existing Kotbox entry in the database.
     *
     * @param entry The entry to update.
     * @param transactionContext The transaction context to execute the query.
     * @return The updated Kotbox entry.
     * @throws OptimisticLockException If the entry was modified by another transaction before this update.
     */
    fun update(entry: KotboxEntry, transactionContext: TransactionContext): KotboxEntry =
        update(listOf(entry), transactionContext).firstOrNull() ?: throw OptimisticLockException()

    /**
     * Updates multiple Kotbox entries in the database in a batch operation.
     *
     * If an entry was modified by another transaction, it will not be included in the updated list.
     *
     * @param entries The list of entries to update.
     * @param transactionContext The transaction context to execute the query.
     * @return A list of successfully updated Kotbox entries.
     */
    fun update(
        entries: List<KotboxEntry>,
        transactionContext: TransactionContext
    ): List<KotboxEntry> {
        if (entries.isEmpty()) return emptyList()
        logger.debug { "update: entries=$entries" }
        val updateCounts = transactionContext.connection.executeBatch(
            updateSql,
            entries.map {
                dialect.sqlArgsForColumns(
                    kotboxEntry = it,
                    columns = Column.UPDATE_COLUMNS,
                    additionalArgs = arrayOf(
                        it.id,
                        it.version
                    ),
                    newVersion = it.version + 1
                )
            }
        )
        return entries.mapIndexedNotNull { idx, entry ->
            if (updateCounts[idx] == 1) {
                entry.copy(version = entry.version + 1)
            } else {
                null
            }
        }
    }

    /**
     * Deletes Kotbox entries that are processed and expired.
     *
     * @param now The current timestamp used to determine expired entries.
     * @param transactionContext The transaction context to execute the query.
     * @return The number of deleted entries.
     */
    fun deleteProcessedAndExpired(now: Instant, transactionContext: TransactionContext): Int {
        logger.debug { "deleteProcessedAndExpired: now=$now" }
        return transactionContext.connection.executeUpdate(deleteProcessedExpiredSql, Timestamp.from(now))
    }

    /**
     * Attempts to acquire a lock on a given Kotbox entry.
     *
     * @param entry The Kotbox entry to lock.
     * @param transactionContext The transaction context to execute the query.
     * @return True if the lock was successfully acquired, false otherwise.
     */
    fun tryLock(entry: KotboxEntry, transactionContext: TransactionContext): Boolean {
        logger.debug { "tryLock: entry=$entry" }
        return transactionContext.connection.executeQuery(tryLockSql, entry.id, entry.version) { it.next() }
    }

    private fun ResultSet.toEntries(): List<KotboxEntry> {
        val entries = mutableListOf<KotboxEntry>()
        while (next()) {
            entries.add(dialect.toEntry(this))
        }
        return entries
    }
}