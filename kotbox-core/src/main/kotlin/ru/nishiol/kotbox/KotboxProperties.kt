package ru.nishiol.kotbox

import java.time.Duration

/**
 * Represents configuration properties for Kotbox.
 */
data class KotboxProperties(
    /**
     * Configuration for the Kotbox table.
     */
    val table: Table = Table(),

    /**
     * Configuration for polling Kotbox entries.
     */
    val poll: Poll = Poll(),

    /**
     * Duration for which processed entries are retained before deletion.
     */
    val retentionDuration: Duration = Duration.ofDays(7),

    /**
     * Time during which an entry remains invisible before being retried.
     */
    val entryVisibilityTimeout: Duration = Duration.ofMinutes(1),
) {
    /**
     * Defines the database table configuration for Kotbox.
     */
    data class Table(
        /**
         * The name of the Kotbox table.
         */
        val name: String = "kotbox",

        /**
         * The schema where the Kotbox table is located.
         */
        val schema: String = "public",

        /**
         * If set to true, then the Kotbox table will be automatically created on start.
         */
        val createSchema: Boolean = true
    )

    /**
     * Defines the polling configuration for processing Kotbox entries.
     */
    data class Poll(
        /**
         * The maximum number of entries to fetch in a single batch.
         */
        val batchSize: Int = 4096,

        /**
         * Sleep duration before polling for new Kotbox entries.
         */
        val sleepDuration: Duration = Duration.ofMinutes(1)
    )
}
