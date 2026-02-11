package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.ConversionAnchor
import io.nuxie.sdk.campaigns.ConversionWindowDefaults
import io.nuxie.sdk.campaigns.ExitPolicy
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.campaigns.Window
import io.nuxie.sdk.campaigns.WindowUnit
import io.nuxie.sdk.flows.FlowViewModelRuntime
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.triggers.JourneyExitReason
import io.nuxie.sdk.util.UuidV7
import io.nuxie.sdk.util.toJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class FlowPendingActionKind {
  @SerialName("delay")
  DELAY,

  @SerialName("timeWindow")
  TIME_WINDOW,

  @SerialName("waitUntil")
  WAIT_UNTIL,

  @SerialName("remoteRetry")
  REMOTE_RETRY,
}

@Serializable
data class FlowPendingAction(
  val interactionId: String,
  val screenId: String? = null,
  val componentId: String? = null,
  val actionIndex: Int,
  val kind: FlowPendingActionKind,
  val resumeAtEpochMillis: Long? = null,
  val condition: IREnvelope? = null,
  val maxTimeMs: Int? = null,
  val startedAtEpochMillis: Long,
)

@Serializable
data class FlowJourneyState(
  var currentScreenId: String? = null,
  var navigationStack: MutableList<String> = mutableListOf(),
  var viewModelSnapshot: FlowViewModelRuntime.FlowViewModelSnapshot? = null,
  var pendingAction: FlowPendingAction? = null,
)

@Serializable
enum class JourneyStatus {
  @SerialName("pending")
  PENDING,

  @SerialName("active")
  ACTIVE,

  @SerialName("paused")
  PAUSED,

  @SerialName("completed")
  COMPLETED,

  @SerialName("expired")
  EXPIRED,

  @SerialName("cancelled")
  CANCELLED;

  val isActive: Boolean
    get() {
      return when (this) {
        PENDING, ACTIVE, PAUSED -> true
        COMPLETED, EXPIRED, CANCELLED -> false
      }
    }

  val isTerminal: Boolean
    get() = !isActive

  val isLive: Boolean
    get() {
      return when (this) {
        ACTIVE, PAUSED -> true
        PENDING, COMPLETED, EXPIRED, CANCELLED -> false
      }
    }
}

/**
 * User journey through a campaign flow.
 *
 * Mirrors iOS `Journey` (shape + semantics).
 */
@Serializable
class Journey(
  val id: String = UuidV7.generateString(),
  val campaignId: String,
  val flowId: String,
  val distinctId: String,
  var status: JourneyStatus = JourneyStatus.PENDING,
  var context: MutableMap<String, JsonElement> = mutableMapOf(),
  var flowState: FlowJourneyState = FlowJourneyState(),
  val startedAtEpochMillis: Long,
  var updatedAtEpochMillis: Long,
  var completedAtEpochMillis: Long? = null,
  var exitReason: JourneyExitReason? = null,
  var resumeAtEpochMillis: Long? = null,
  var expiresAtEpochMillis: Long? = null,

  // Goal / conversion snapshots
  var goalSnapshot: GoalConfig? = null,
  var exitPolicySnapshot: ExitPolicy? = null,
  /** seconds */
  var conversionWindowSeconds: Double,
  var conversionAnchor: ConversionAnchor,
  var conversionAnchorAtEpochMillis: Long,
  var convertedAtEpochMillis: Long? = null,
) {

  constructor(
    campaign: Campaign,
    distinctId: String,
    id: String? = null,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ) : this(
    id = id ?: UuidV7.generateString(),
    campaignId = campaign.id,
    flowId = campaign.flowId,
    distinctId = distinctId,
    status = JourneyStatus.PENDING,
    context = mutableMapOf(),
    flowState = FlowJourneyState(),
    startedAtEpochMillis = nowEpochMillis,
    updatedAtEpochMillis = nowEpochMillis,
    completedAtEpochMillis = null,
    exitReason = null,
    resumeAtEpochMillis = null,
    expiresAtEpochMillis = null,
    goalSnapshot = campaign.goal,
    exitPolicySnapshot = campaign.exitPolicy,
    conversionWindowSeconds = campaign.goal?.window ?: ConversionWindowDefaults.defaultWindowSeconds(campaign.campaignType),
    conversionAnchor = parseConversionAnchor(campaign.conversionAnchor),
    conversionAnchorAtEpochMillis = nowEpochMillis,
    convertedAtEpochMillis = null,
  )

  fun shouldResume(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
    val resumeAt = resumeAtEpochMillis ?: return false
    return status == JourneyStatus.PAUSED && nowEpochMillis >= resumeAt
  }

  fun hasExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
    val expiresAt = expiresAtEpochMillis ?: return false
    return nowEpochMillis >= expiresAt
  }

  fun complete(reason: JourneyExitReason, nowEpochMillis: Long = System.currentTimeMillis()) {
    status = JourneyStatus.COMPLETED
    exitReason = reason
    completedAtEpochMillis = nowEpochMillis
    updatedAtEpochMillis = nowEpochMillis
  }

  fun pause(untilEpochMillis: Long?, nowEpochMillis: Long = System.currentTimeMillis()) {
    status = JourneyStatus.PAUSED
    resumeAtEpochMillis = untilEpochMillis
    updatedAtEpochMillis = nowEpochMillis
  }

  fun resume(nowEpochMillis: Long = System.currentTimeMillis()) {
    status = JourneyStatus.ACTIVE
    resumeAtEpochMillis = null
    updatedAtEpochMillis = nowEpochMillis
  }

  fun cancel(nowEpochMillis: Long = System.currentTimeMillis()) {
    status = JourneyStatus.CANCELLED
    exitReason = JourneyExitReason.CANCELLED
    completedAtEpochMillis = nowEpochMillis
    updatedAtEpochMillis = nowEpochMillis
  }

  fun setContext(key: String, value: Any?, nowEpochMillis: Long = System.currentTimeMillis()) {
    context[key] = toJsonElement(value)
    updatedAtEpochMillis = nowEpochMillis
  }

  fun setContextJson(key: String, value: JsonElement, nowEpochMillis: Long = System.currentTimeMillis()) {
    context[key] = value
    updatedAtEpochMillis = nowEpochMillis
  }

  fun getContext(key: String): JsonElement? = context[key]

  fun getContextObject(key: String): JsonObject? = context[key] as? JsonObject

  companion object {
    private fun parseConversionAnchor(raw: String?): ConversionAnchor {
      return when (raw?.lowercase()) {
        "last_flow_shown" -> ConversionAnchor.LAST_FLOW_SHOWN
        "last_flow_interaction" -> ConversionAnchor.LAST_FLOW_INTERACTION
        "journey_start" -> ConversionAnchor.JOURNEY_START
        else -> ConversionAnchor.JOURNEY_START
      }
    }

    private fun windowIntervalSeconds(window: Window): Long {
      return when (window.unit) {
        WindowUnit.MINUTE -> window.amount.toLong() * 60L
        WindowUnit.HOUR -> window.amount.toLong() * 3600L
        WindowUnit.DAY -> window.amount.toLong() * 86400L
        WindowUnit.WEEK -> window.amount.toLong() * 604800L
      }
    }
  }
}

@Serializable
data class JourneyCompletionRecord(
  val campaignId: String,
  val distinctId: String,
  val journeyId: String,
  val completedAtEpochMillis: Long,
  val exitReason: JourneyExitReason,
) {
  constructor(journey: Journey, nowEpochMillis: Long = System.currentTimeMillis()) : this(
    campaignId = journey.campaignId,
    distinctId = journey.distinctId,
    journeyId = journey.id,
    completedAtEpochMillis = journey.completedAtEpochMillis ?: nowEpochMillis,
    exitReason = journey.exitReason ?: JourneyExitReason.COMPLETED,
  )
}
