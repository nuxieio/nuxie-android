package io.nuxie.sdk.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun toJsonElement(value: Any?): JsonElement {
  return when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value.toDouble())
    is Map<*, *> -> {
      val content = value.entries
        .mapNotNull { (k, v) -> (k as? String)?.let { it to toJsonElement(v) } }
        .toMap()
      JsonObject(content)
    }
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
  }
}

fun toJsonObject(value: Map<String, Any?>): JsonObject {
  return JsonObject(value.mapValues { (_, v) -> toJsonElement(v) })
}

fun fromJsonElement(value: JsonElement): Any? {
  return when (value) {
    JsonNull -> null
    is JsonPrimitive -> {
      if (value.isString) return value.content
      value.booleanOrNull
        ?: value.longOrNull
        ?: value.doubleOrNull
        ?: value.content
    }
    is JsonObject -> value.mapValues { (_, v) -> fromJsonElement(v) }
    is JsonArray -> value.map { fromJsonElement(it) }
    else -> null
  }
}
