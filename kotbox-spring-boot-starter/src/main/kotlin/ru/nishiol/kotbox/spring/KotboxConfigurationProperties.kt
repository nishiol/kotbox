package ru.nishiol.kotbox.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.nishiol.kotbox.KotboxProperties
import java.time.Duration

@ConfigurationProperties(prefix = "kotbox")
class KotboxConfigurationProperties {
    var enabled: Boolean = true

    var autoStart: Boolean = true

    var table: Table = Table()

    var poll: Poll = Poll()

    /**
     * Duration for which processed entries are retained before deletion.
     */
    var retentionDuration: Duration = Duration.ofDays(7)

    /**
     * Time during which an entry remains invisible before being retried.
     */
    var entryVisibilityTimeout: Duration = Duration.ofMinutes(1)

    class Table {
        /**
         * The name of the Kotbox table.
         */
        var name: String = "kotbox"

        /**
         * The schema where the Kotbox table is located.
         */
        var schema: String = "public"

        /**
         * If set to true, then the Kotbox table will be automatically created on start.
         */
        var createSchema: Boolean = true
    }

    class Poll {
        /**
         * The maximum number of entries to fetch in a single batch.
         */
        var batchSize: Int = 4096

        /**
         * Sleep duration before polling for new Kotbox entries.
         */
        var sleepDuration: Duration = Duration.ofMinutes(1)
    }

    fun toKotboxProperties(): KotboxProperties = KotboxProperties(
        table = KotboxProperties.Table(
            name = table.name,
            schema = table.schema,
            createSchema = table.createSchema
        ),
        poll = KotboxProperties.Poll(
            batchSize = poll.batchSize,
            sleepDuration = poll.sleepDuration
        ),
        retentionDuration = retentionDuration,
        entryVisibilityTimeout = entryVisibilityTimeout
    )
}