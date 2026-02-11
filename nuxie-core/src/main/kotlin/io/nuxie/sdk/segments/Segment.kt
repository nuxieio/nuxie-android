package io.nuxie.sdk.segments

import io.nuxie.sdk.ir.IREnvelope
import kotlinx.serialization.Serializable

/**
 * Segment definition returned by `/profile`.
 *
 * Mirrors iOS `Segment`.
 */
@Serializable
data class Segment(
  val id: String,
  val name: String,
  val condition: IREnvelope,
)

