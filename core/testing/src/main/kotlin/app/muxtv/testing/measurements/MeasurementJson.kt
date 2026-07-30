package app.muxtv.testing.measurements

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal class StrictJsonObject internal constructor(
    private val value: JsonObject,
) {
    fun requireExactFields(vararg names: String) {
        val expected = names.toSet()
        if (value.keys != expected) {
            failAdaptation(MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA)
        }
    }

    fun requireElement(name: String): JsonElement = value[name]
        ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireObject(name: String): StrictJsonObject =
        (requireElement(name) as? JsonObject)
            ?.let(::StrictJsonObject)
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireArray(name: String): JsonArray =
        requireElement(name) as? JsonArray
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireString(name: String): String {
        val primitive = requireElement(name) as? JsonPrimitive
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
        if (!primitive.isString) {
            failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
        }
        return primitive.content
    }

    fun requireInt(name: String): Int =
        (requireElement(name) as? JsonPrimitive)?.intOrNull
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireLong(name: String): Long =
        (requireElement(name) as? JsonPrimitive)?.longOrNull
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireBoolean(name: String): Boolean =
        (requireElement(name) as? JsonPrimitive)?.booleanOrNull
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

    fun requireNullableLong(name: String): Long? {
        val element = requireElement(name)
        if (element === JsonNull) return null
        return (element as? JsonPrimitive)?.longOrNull
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
    }

    fun requireNullableObject(name: String): StrictJsonObject? {
        val element = requireElement(name)
        if (element === JsonNull) return null
        return (element as? JsonObject)?.let(::StrictJsonObject)
            ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
    }
}

internal fun parseStrictJsonObject(bytes: ByteArray): StrictJsonObject {
    val text = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        failAdaptation(MeasurementReportAdaptationFailure.INVALID_JSON)
    }

    val element = try {
        STRICT_JSON.parseToJsonElement(text)
    } catch (_: SerializationException) {
        failAdaptation(MeasurementReportAdaptationFailure.INVALID_JSON)
    } catch (_: IllegalArgumentException) {
        failAdaptation(MeasurementReportAdaptationFailure.INVALID_JSON)
    }

    return (element as? JsonObject)?.let(::StrictJsonObject)
        ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_JSON)
}

internal fun JsonElement.requireObjectValue(): StrictJsonObject =
    (this as? JsonObject)?.let(::StrictJsonObject)
        ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

internal fun JsonElement.requireStringValue(): String {
    val primitive = this as? JsonPrimitive
        ?: failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
    if (!primitive.isString) {
        failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
    }
    return primitive.content
}

private val STRICT_JSON = Json {
    isLenient = false
    allowTrailingComma = false
    ignoreUnknownKeys = false
    explicitNulls = true
}
