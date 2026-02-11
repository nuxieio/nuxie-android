package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.triggers.JourneyExitReason

/**
 * Internal journey event tracking helpers.
 *
 * Mirrors iOS `JourneyEvents` names + property shapes.
 */
object JourneyEvents {

  // MARK: - Event Names

  const val journeyStarted: String = "\$journey_started"
  const val journeyPaused: String = "\$journey_paused"
  const val journeyResumed: String = "\$journey_resumed"
  const val journeyErrored: String = "\$journey_errored"
  const val journeyGoalMet: String = "\$journey_goal_met"
  const val journeyExited: String = "\$journey_exited"
  const val journeyAction: String = "\$journey_action"

  const val flowShown: String = "\$flow_shown"
  const val flowDismissed: String = "\$flow_dismissed"
  const val flowPurchased: String = "\$flow_purchased"
  const val flowTimedOut: String = "\$flow_timed_out"
  const val flowErrored: String = "\$flow_errored"

  const val customerUpdated: String = "\$customer_updated"
  const val eventSent: String = "\$event_sent"
  const val delegateCalled: String = "\$delegate_called"
  const val experimentExposure: String = "\$experiment_exposure"

  // MARK: - Properties Builders

  fun journeyStartedProperties(
    journey: Journey,
    campaign: Campaign,
    triggerEvent: NuxieEvent? = null,
    entryScreenId: String? = null,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to campaign.id,
      "campaign_name" to campaign.name,
      "flow_id" to campaign.flowId,
    )

    if (!entryScreenId.isNullOrBlank()) {
      props["entry_screen_id"] = entryScreenId
    }

    when (val trigger = campaign.trigger) {
      is CampaignTrigger.Event -> {
        props["trigger_type"] = "event"
        props["trigger_event_name"] = trigger.config.eventName
        if (triggerEvent != null) {
          props["trigger_event_properties"] = triggerEvent.properties
        }
      }
      is CampaignTrigger.Segment -> {
        props["trigger_type"] = "segment"
        props["trigger_segment"] = true
      }
    }

    return props
  }

  fun journeyPausedProperties(
    journey: Journey,
    screenId: String?,
    resumeAtEpochMillis: Long?,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    if (resumeAtEpochMillis != null) {
      props["resume_at"] = resumeAtEpochMillis / 1000.0
    }
    return props
  }

  fun journeyResumedProperties(
    journey: Journey,
    screenId: String?,
    resumeReason: String,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "resume_reason" to resumeReason,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    return props
  }

  fun journeyErroredProperties(
    journey: Journey,
    screenId: String?,
    errorMessage: String?,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    if (!errorMessage.isNullOrBlank()) {
      props["error_message"] = errorMessage
    }
    return props
  }

  fun journeyExitedProperties(
    journey: Journey,
    reason: JourneyExitReason,
    screenId: String?,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "exit_reason" to exitReasonValue(reason),
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    return props
  }

  fun journeyActionProperties(
    journey: Journey,
    screenId: String?,
    interactionId: String?,
    actionType: String,
    error: String?,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "action_type" to actionType,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    if (!interactionId.isNullOrBlank()) {
      props["interaction_id"] = interactionId
    }
    if (!error.isNullOrBlank()) {
      props["error_message"] = error
    }
    return props
  }

  fun flowShownProperties(flowId: String, journey: Journey): Map<String, Any?> {
    return mapOf(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
    )
  }

  fun flowDismissedProperties(flowId: String, journey: Journey): Map<String, Any?> {
    return mapOf(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
    )
  }

  fun flowPurchasedProperties(flowId: String, journey: Journey, productId: String?): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
    )
    if (!productId.isNullOrBlank()) {
      props["product_id"] = productId
    }
    return props
  }

  fun flowTimedOutProperties(flowId: String, journey: Journey): Map<String, Any?> {
    return mapOf(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
    )
  }

  fun flowErroredProperties(flowId: String, journey: Journey, errorMessage: String?): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
    )
    if (!errorMessage.isNullOrBlank()) {
      props["error_message"] = errorMessage
    }
    return props
  }

  fun customerUpdatedProperties(
    journey: Journey,
    screenId: String?,
    attributesUpdated: List<String>,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "attributes_updated" to attributesUpdated,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    return props
  }

  fun eventSentProperties(
    journey: Journey,
    screenId: String?,
    eventName: String,
    eventProperties: Map<String, Any?>,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "event_name" to eventName,
      "event_properties" to eventProperties,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    return props
  }

  fun delegateCalledProperties(
    journey: Journey,
    screenId: String?,
    message: String,
    payload: Any?,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "message" to message,
    )
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }
    if (payload != null) {
      props["payload"] = payload
    }
    return props
  }

  fun experimentExposureProperties(
    journey: Journey,
    experimentKey: String,
    variantKey: String,
    flowId: String?,
    isHoldout: Boolean,
    assignmentSource: String? = null,
  ): Map<String, Any?> {
    val props = mutableMapOf<String, Any?>(
      "journey_id" to journey.id,
      "campaign_id" to journey.campaignId,
      "flow_id" to flowId,
      "experiment_key" to experimentKey,
      "variant_key" to variantKey,
      "is_holdout" to isHoldout,
    )
    if (!assignmentSource.isNullOrBlank()) {
      props["assignment_source"] = assignmentSource
    }
    return props
  }

  private fun exitReasonValue(reason: JourneyExitReason): String {
    return when (reason) {
      JourneyExitReason.COMPLETED -> "completed"
      JourneyExitReason.GOAL_MET -> "goal_met"
      JourneyExitReason.TRIGGER_UNMATCHED -> "trigger_unmatched"
      JourneyExitReason.EXPIRED -> "expired"
      JourneyExitReason.ERROR -> "error"
      JourneyExitReason.CANCELLED -> "cancelled"
    }
  }
}

