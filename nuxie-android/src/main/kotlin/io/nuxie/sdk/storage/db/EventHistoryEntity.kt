package io.nuxie.sdk.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.util.fromJsonElement
import io.nuxie.sdk.util.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Entity(tableName = "nuxie_event_history")
internal data class EventHistoryEntity(
  @PrimaryKey
  val id: String,
  val name: String,
  val distinctId: String,
  val timestampEpochMillis: Long,
  val propertiesJson: String,
  val createdAtMs: Long,
) {
  fun toStoredEvent(json: Json): StoredEvent {
    val obj = try {
      (json.parseToJsonElement(propertiesJson) as? JsonObject) ?: JsonObject(emptyMap())
    } catch (_: Exception) {
      JsonObject(emptyMap())
    }
    val props: Map<String, Any?> = obj.mapValues { (_, v) -> fromJsonElement(v) }
    return StoredEvent(
      id = id,
      name = name,
      distinctId = distinctId,
      timestampEpochMillis = timestampEpochMillis,
      properties = props,
    )
  }

  companion object {
    fun from(event: StoredEvent, json: Json, createdAtMs: Long): EventHistoryEntity {
      val propsObj = toJsonObject(event.properties)
      val propsJson = json.encodeToString(JsonElement.serializer(), propsObj)
      return EventHistoryEntity(
        id = event.id,
        name = event.name,
        distinctId = event.distinctId,
        timestampEpochMillis = event.timestampEpochMillis,
        propertiesJson = propsJson,
        createdAtMs = createdAtMs,
      )
    }
  }
}

