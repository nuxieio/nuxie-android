package io.nuxie.sdk.events.queue

import io.nuxie.sdk.util.UuidV7
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Persisted representation of an event waiting to be delivered.
 *
 * This is the Android counterpart to iOS "network queue" items.
 */
@Serializable
data class QueuedEvent(
  val id: String = UuidV7.generateString(),
  val name: String,
  val distinctId: String,
  val anonDistinctId: String? = null,
  val timestamp: String,
  val properties: JsonObject,
  val value: Double? = null,
  val entityId: String? = null,
)

