package ru.nishiol.kotbox.test

import ru.nishiol.kotbox.Kotbox
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.task.KotboxTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class TestTask(
    private val kotbox: Kotbox
) : KotboxTask<TestTask.Payload> {
    data class Payload(val message: String)

    companion object : KotboxTask.Definition<Payload> {
        override val id: String = "emailServiceTask"
        override val payloadClass: KClass<Payload> = Payload::class
    }

    override val definition: KotboxTask.Definition<Payload> = TestTask

    val payloads: MutableMap<String, MutableList<Payload>> = ConcurrentHashMap()
    val failurePayloads: MutableMap<String, MutableList<Payload>> = ConcurrentHashMap()
    val deserializationFailurePayloads: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    private var onFailureAction: KotboxTask.OnFailureAction = KotboxTask.OnFailureAction.Block
    private var beforeExecute: (Payload, Kotbox) -> Unit = { _, _ -> }

    fun init(
        beforeExecute: (Payload, Kotbox) -> Unit = { _, _ -> },
        onFailureAction: KotboxTask.OnFailureAction = KotboxTask.OnFailureAction.Block
    ) {
        this.beforeExecute = beforeExecute
        this.onFailureAction = onFailureAction
    }

    override fun execute(payload: Payload, topic: String?) {
        beforeExecute(payload, kotbox)
        val payloads = payloads.computeIfAbsent(topic.orEmpty()) { Collections.synchronizedList(mutableListOf()) }
        payloads += payload
    }

    override fun onFailure(
        payload: Payload,
        entry: KotboxEntry,
        cause: Throwable
    ): KotboxTask.OnFailureAction {
        val failurePayloads =
            failurePayloads.computeIfAbsent(entry.topic.orEmpty()) { Collections.synchronizedList(mutableListOf()) }
        failurePayloads += payload
        return onFailureAction
    }

    override fun onPayloadDeserializationFailure(
        entry: KotboxEntry,
        cause: Throwable
    ): KotboxTask.OnFailureAction {
        val deserializationFailurePayloads =
            deserializationFailurePayloads.computeIfAbsent(entry.topic.orEmpty()) {
                Collections.synchronizedList(mutableListOf())
            }
        deserializationFailurePayloads += entry.payload
        return onFailureAction
    }

    fun reset() {
        payloads.clear()
        failurePayloads.clear()
        deserializationFailurePayloads.clear()
        init()
    }
}