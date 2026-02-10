package io.nuxie.sdk.events

import io.nuxie.sdk.util.UuidV7
import io.nuxie.sdk.util.Iso8601

/**
 * Enhanced event model for SDK hooks (beforeSend, sanitizers) and internal routing.
 *
 * Mirrors iOS `NuxieEvent` shape and semantics.
 */
data class NuxieEvent(
  val id: String = UuidV7.generateString(),
  val name: String,
  val distinctId: String,
  val properties: Map<String, Any?> = emptyMap(),
  /** ISO8601 timestamp string (UTC). */
  val timestamp: String = Iso8601.now(),
)
