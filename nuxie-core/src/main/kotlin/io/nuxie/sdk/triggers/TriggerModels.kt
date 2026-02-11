package io.nuxie.sdk.triggers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Progressive trigger updates emitted by `trigger(...)`.
 *
 * Mirrors iOS `TriggerUpdate`.
 */
sealed class TriggerUpdate {
  data class Decision(val decision: TriggerDecision) : TriggerUpdate()
  data class Entitlement(val entitlement: EntitlementUpdate) : TriggerUpdate()
  data class Journey(val journey: JourneyUpdate) : TriggerUpdate()
  data class Error(val error: TriggerError) : TriggerUpdate()
}

sealed class TriggerDecision {
  data object NoMatch : TriggerDecision()
  data class Suppressed(val reason: SuppressReason) : TriggerDecision()
  data class JourneyStarted(val ref: JourneyRef) : TriggerDecision()
  data class JourneyResumed(val ref: JourneyRef) : TriggerDecision()
  data class FlowShown(val ref: JourneyRef) : TriggerDecision()
  data object AllowedImmediate : TriggerDecision()
  data object DeniedImmediate : TriggerDecision()
}

sealed class EntitlementUpdate {
  data object Pending : EntitlementUpdate()
  data class Allowed(val source: GateSource) : EntitlementUpdate()
  data object Denied : EntitlementUpdate()
}

data class JourneyRef(
  val journeyId: String,
  val campaignId: String,
  val flowId: String?,
)

sealed class SuppressReason {
  data object AlreadyActive : SuppressReason()
  data object ReentryLimited : SuppressReason()
  data object Holdout : SuppressReason()
  data object NoFlow : SuppressReason()
  data class Unknown(val value: String) : SuppressReason()
}

data class JourneyUpdate(
  val journeyId: String,
  val campaignId: String,
  val flowId: String?,
  val exitReason: JourneyExitReason,
  val goalMet: Boolean,
  val goalMetAtEpochMillis: Long?,
  val durationSeconds: Double?,
  val flowExitReason: String?,
)

@Serializable
enum class JourneyExitReason {
  @SerialName("completed")
  COMPLETED,

  @SerialName("goal_met")
  GOAL_MET,

  @SerialName("trigger_unmatched")
  TRIGGER_UNMATCHED,

  @SerialName("expired")
  EXPIRED,

  @SerialName("error")
  ERROR,

  @SerialName("cancelled")
  CANCELLED,
}

enum class GateSource {
  CACHE,
  PURCHASE,
  RESTORE,
}

data class TriggerError(
  val code: String,
  override val message: String,
) : Exception(message)
