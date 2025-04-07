package ru.nishiol.kotbox

import kotlin.reflect.KClass

/**
 * Interface for serializing and deserializing [KotboxEntry] payloads.
 *
 * Implementations of this interface should provide a way to convert objects into a string representation
 * and reconstruct them back into their original form.
 */
interface PayloadSerializer {

    /**
     * Serializes an object into its string representation.
     *
     * @param payload The object to serialize. It must be a valid serializable type.
     * @return A string representation of the given payload.
     */
    fun serialize(payload: Any): String

    /**
     * Deserializes a string representation of an object back into its original form.
     *
     * @param payload The string representation of the object.
     * @param payloadClass The class type of the object to deserialize into.
     * @return The deserialized object of type [T].
     */
    fun <T : Any> deserialize(payload: String, payloadClass: KClass<T>): T
}
