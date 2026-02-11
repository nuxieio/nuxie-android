package io.nuxie.sdk.events.store

/**
 * Locally persisted event record.
 *
 * Mirrors iOS `StoredEvent` (shape, not storage implementation).
 */
data class StoredEvent(
  val id: String,
  val name: String,
  val distinctId: String,
  val timestampEpochMillis: Long,
  val properties: Map<String, Any?>,
)

