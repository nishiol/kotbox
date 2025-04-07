package ru.nishiol.kotbox.store

import ru.nishiol.kotbox.KotboxEntry
import java.sql.ResultSet

interface Dialect {
    val createKotboxTable: List<String>

    val fetchAll: String

    val fetchWithoutTopic: String

    val fetchNextInAllTopics: String

    val findAndLockById: String

    val insert: String

    val update: String

    val deleteProcessedAndExpired: String

    val tryLock: String

    fun sqlArgsForColumns(
        kotboxEntry: KotboxEntry,
        columns: Set<Column>,
        vararg additionalArgs: Any?,
        newVersion: Int = kotboxEntry.version
    ): Collection<Any?>

    fun toEntry(resultSet: ResultSet): KotboxEntry
}