package io.nuxie.sdk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProfileRequest(
  @SerialName("distinct_id")
  val distinctId: String,
  val locale: String? = null,
  val groups: JsonObject? = null,
  val version: Int? = 1,
)

@Serializable
data class EventRequest(
  val event: String,
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("\$anon_distinct_id")
  val anonDistinctId: String? = null,
  val timestamp: String? = null,
  val properties: JsonObject? = null,
  val uuid: String? = null,
  val value: Double? = null,
  val entityId: String? = null,
)

@Serializable
data class BatchEventItem(
  val event: String,
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("\$anon_distinct_id")
  val anonDistinctId: String? = null,
  val timestamp: String? = null,
  val properties: JsonObject? = null,
  val uuid: String? = null,
  val value: Double? = null,
  val entityId: String? = null,
)

@Serializable
data class BatchRequest(
  @SerialName("historical_migration")
  val historicalMigration: Boolean? = null,
  val batch: List<BatchEventItem>,
)

