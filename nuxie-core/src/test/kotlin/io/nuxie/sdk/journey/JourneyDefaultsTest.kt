package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.ConversionAnchor
import io.nuxie.sdk.campaigns.ConversionWindowDefaults
import io.nuxie.sdk.campaigns.EventTriggerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyDefaultsTest {

  private fun campaign(
    conversionAnchor: String? = null,
    campaignType: String? = "paywall",
  ): Campaign {
    return Campaign(
      id = "camp_1",
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "app_opened")),
      goal = null,
      exitPolicy = null,
      conversionAnchor = conversionAnchor,
      campaignType = campaignType,
    )
  }

  @Test
  fun defaultsToFourteenDayWindowAndLastFlowShownAnchor() {
    val journey = Journey(campaign = campaign(), distinctId = "user_1")

    assertEquals(
      ConversionWindowDefaults.DEFAULT_WINDOW_SECONDS,
      journey.conversionWindowSeconds,
      0.0,
    )
    assertEquals(ConversionAnchor.LAST_FLOW_SHOWN, journey.conversionAnchor)
  }

  @Test
  fun preservesExplicitConversionAnchorWhenProvided() {
    val journey = Journey(
      campaign = campaign(conversionAnchor = "journey_start"),
      distinctId = "user_1",
    )

    assertEquals(ConversionAnchor.JOURNEY_START, journey.conversionAnchor)
  }
}
