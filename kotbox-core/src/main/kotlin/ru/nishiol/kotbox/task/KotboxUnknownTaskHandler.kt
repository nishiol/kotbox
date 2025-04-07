package ru.nishiol.kotbox.task

import ru.nishiol.kotbox.KotboxEntry

/**
 * Handles unknown tasks encountered in the Kotbox kotbox.
 */
fun interface KotboxUnknownTaskHandler {
    /**
     * Handles an unknown Kotbox entry.
     *
     * @param entry The unknown Kotbox entry.
     * @return The action to take for the unknown entry.
     */
    fun handle(entry: KotboxEntry): KotboxTask.OnFailureAction
}