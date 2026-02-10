package io.nuxie.sdk.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Canonical internal representation for an event that will be enqueued and sent.
 *
 * This is the Android counterpart to iOS `NuxieEvent` + `EventRequest` layering.
 */
@Serializable
data class NuxieEvent(
  val event: String,
  val distinctId: String,
  val anonDistinctId: String? = null,
  val timestampIso8601: String? = null,
  val properties: Map<String, JsonElement>? = null,
  val idempotencyKey: String? = null,
  val value: Double? = null,
  val entityId: String? = null,
)

