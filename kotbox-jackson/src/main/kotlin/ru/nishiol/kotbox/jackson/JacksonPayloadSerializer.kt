package ru.nishiol.kotbox.jackson

import com.fasterxml.jackson.databind.ObjectMapper
import ru.nishiol.kotbox.PayloadSerializer
import kotlin.reflect.KClass

class JacksonPayloadSerializer(
    private val objectMapper: ObjectMapper
) : PayloadSerializer {
    override fun serialize(payload: Any): String = objectMapper.writeValueAsString(payload)

    override fun <T : Any> deserialize(payload: String, payloadClass: KClass<T>): T =
        objectMapper.readValue(payload, payloadClass.java)
}