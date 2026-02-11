package io.nuxie.sdk.campaigns

import io.nuxie.sdk.ir.IREnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Campaign models returned by `/profile`.
 *
 * Mirrors iOS `Campaign` + related trigger/reentry models.
 */

@Serializable
data class EventTriggerConfig(
  val eventName: String,
  val condition: IREnvelope? = null,
)

@Serializable
data class SegmentTriggerConfig(
  val condition: IREnvelope,
)

@Serializable
sealed interface CampaignTrigger {
  @Serializable
  @SerialName("event")
  data class Event(val config: EventTriggerConfig) : CampaignTrigger

  @Serializable
  @SerialName("segment")
  data class Segment(val config: SegmentTriggerConfig) : CampaignTrigger
}

@Serializable
data class Window(
  val amount: Int,
  val unit: WindowUnit,
)

@Serializable
enum class WindowUnit {
  @SerialName("minute")
  MINUTE,

  @SerialName("hour")
  HOUR,

  @SerialName("day")
  DAY,

  @SerialName("week")
  WEEK,
}

@Serializable
sealed interface CampaignReentry {
  @Serializable
  @SerialName("one_time")
  data object OneTime : CampaignReentry

  @Serializable
  @SerialName("every_time")
  data object EveryTime : CampaignReentry

  @Serializable
  @SerialName("once_per_window")
  data class OncePerWindow(val window: Window) : CampaignReentry
}

@Serializable
data class GoalConfig(
  val kind: Kind,
  val eventName: String? = null,
  val eventFilter: IREnvelope? = null,
  val segmentId: String? = null,
  val attributeExpr: IREnvelope? = null,
  /** seconds */
  val window: Double? = null,
) {
  @Serializable
  enum class Kind {
    @SerialName("event")
    EVENT,

    @SerialName("segment_enter")
    SEGMENT_ENTER,

    @SerialName("segment_leave")
    SEGMENT_LEAVE,

    @SerialName("attribute")
    ATTRIBUTE,
  }
}

@Serializable
data class ExitPolicy(val mode: Mode) {
  @Serializable
  enum class Mode {
    @SerialName("on_goal")
    ON_GOAL,

    @SerialName("on_stop_matching")
    ON_STOP_MATCHING,

    @SerialName("on_goal_or_stop")
    ON_GOAL_OR_STOP,

    @SerialName("never")
    NEVER,
  }
}

@Serializable
enum class ConversionAnchor {
  @SerialName("journey_start")
  JOURNEY_START,

  @SerialName("last_flow_shown")
  LAST_FLOW_SHOWN,

  @SerialName("last_flow_interaction")
  LAST_FLOW_INTERACTION,
}

object ConversionWindowDefaults {
  /** 21 days */
  const val PAYWALL_WINDOW_SECONDS: Double = 21.0 * 24.0 * 60.0 * 60.0

  /** 10 days */
  const val ONBOARDING_WINDOW_SECONDS: Double = 10.0 * 24.0 * 60.0 * 60.0

  fun defaultWindowSeconds(campaignType: String?): Double {
    return when (campaignType?.lowercase()) {
      "paywall" -> PAYWALL_WINDOW_SECONDS
      "onboarding" -> ONBOARDING_WINDOW_SECONDS
      else -> PAYWALL_WINDOW_SECONDS
    }
  }
}

@Serializable
data class Campaign(
  val id: String,
  val name: String,
  val flowId: String,
  val flowNumber: Int,
  val flowName: String? = null,
  val reentry: CampaignReentry,
  val publishedAt: String,
  val trigger: CampaignTrigger,
  val goal: GoalConfig? = null,
  val exitPolicy: ExitPolicy? = null,
  val conversionAnchor: String? = null,
  val campaignType: String? = null,
)

