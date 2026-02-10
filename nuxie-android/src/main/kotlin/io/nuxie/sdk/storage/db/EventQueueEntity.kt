package io.nuxie.sdk.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nuxie.sdk.events.queue.QueuedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Entity(tableName = "nuxie_event_queue")
internal data class EventQueueEntity(
  @PrimaryKey
  val id: String,
  val name: String,
  val distinctId: String,
  val anonDistinctId: String?,
  val timestamp: String,
  val propertiesJson: String,
  val value: Double?,
  val entityId: String?,
  val createdAtMs: Long,
) {
  fun toQueuedEvent(json: Json): QueuedEvent {
    val props = try {
      (json.parseToJsonElement(propertiesJson) as? JsonObject) ?: JsonObject(emptyMap())
    } catch (_: Exception) {
      JsonObject(emptyMap())
    }
    return QueuedEvent(
      id = id,
      name = name,
      distinctId = distinctId,
      anonDistinctId = anonDistinctId,
      timestamp = timestamp,
      properties = props,
      value = value,
      entityId = entityId,
    )
  }

  companion object {
    fun from(event: QueuedEvent, json: Json, createdAtMs: Long): EventQueueEntity {
      val propsJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), event.properties)
      return EventQueueEntity(
        id = event.id,
        name = event.name,
        distinctId = event.distinctId,
        anonDistinctId = event.anonDistinctId,
        timestamp = event.timestamp,
        propertiesJson = propsJson,
        value = event.value,
        entityId = event.entityId,
        createdAtMs = createdAtMs,
      )
    }
  }
}
