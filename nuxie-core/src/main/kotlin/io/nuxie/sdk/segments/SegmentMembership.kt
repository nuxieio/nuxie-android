package io.nuxie.sdk.segments

import kotlinx.serialization.Serializable

@Serializable
data class SegmentMembership(
  val segmentId: String,
  val segmentName: String,
  val enteredAtEpochMillis: Long,
  val lastEvaluatedEpochMillis: Long,
)

