package ru.nishiol.kotbox.test

import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.task.KotboxTask
import ru.nishiol.kotbox.task.KotboxUnknownTaskHandler
import java.util.*

class TestUnknownTaskHandler : KotboxUnknownTaskHandler {
    val unknownEntries: MutableList<KotboxEntry> = Collections.synchronizedList(mutableListOf())

    override fun handle(entry: KotboxEntry): KotboxTask.OnFailureAction {
        unknownEntries += entry
        return KotboxTask.OnFailureAction.Ignore
    }

    fun reset() {
        unknownEntries.clear()
    }
}