package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.triggers.JourneyExitReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileJourneyStoreTest {

  private fun tmpDir(): File = Files.createTempDirectory("nuxie_journeys_test").toFile()

  private fun campaign(id: String = "camp_1"): Campaign {
    return Campaign(
      id = id,
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "app_opened")),
      goal = null,
      exitPolicy = null,
      conversionAnchor = null,
      campaignType = null,
    )
  }

  @Test
  fun saveLoadRoundTrip() = runBlocking {
    val dir = tmpDir()
    val store = FileJourneyStore(dir)
    store.clearCache()

    val journey = Journey(campaign = campaign(), distinctId = "user_1").apply {
      status = JourneyStatus.ACTIVE
    }

    store.saveJourney(journey)
    store.updateCache(journey)

    val loaded = store.loadJourney(journey.id)
    assertNotNull(loaded)
    assertEquals(journey.id, loaded?.id)
    assertEquals("user_1", loaded?.distinctId)
    assertEquals("camp_1", loaded?.campaignId)

    val all = store.loadActiveJourneys()
    assertTrue(all.any { it.id == journey.id })
  }

  @Test
  fun completionRecordsKeepLast10() = runBlocking {
    val dir = tmpDir()
    val store = FileJourneyStore(dir)
    store.clearCache()

    val distinctId = "user_1"
    val campaignId = "camp_1"

    for (i in 0 until 11) {
      val record = JourneyCompletionRecord(
        campaignId = campaignId,
        distinctId = distinctId,
        journeyId = "journey_$i",
        completedAtEpochMillis = 1_000L + i,
        exitReason = JourneyExitReason.COMPLETED,
      )
      store.recordCompletion(record)
    }

    val last = store.lastCompletionEpochMillis(distinctId, campaignId)
    assertEquals(1_010L, last)

    // Ensure file contains only 10 records.
    val file = File(File(File(dir, "journeys"), "completed"), distinctId)
      .resolve("campaign_$campaignId.json")
    assertTrue(file.exists())
    val raw = file.readText()
    // Cheap check: oldest journey_0 should be dropped, journey_10 should be present.
    assertTrue(!raw.contains("journey_0"))
    assertTrue(raw.contains("journey_10"))
  }

  @Test
  fun activeJourneyCacheTracksStatus() = runBlocking {
    val dir = tmpDir()
    val store = FileJourneyStore(dir)
    store.clearCache()

    val camp = campaign()
    val distinctId = "user_1"

    val active = Journey(campaign = camp, distinctId = distinctId).apply {
      status = JourneyStatus.ACTIVE
    }
    store.saveJourney(active)
    store.updateCache(active)

    val ids1 = store.getActiveJourneyIds(distinctId, camp.id)
    assertEquals(setOf(active.id), ids1)

    // Mark terminal and ensure cache updates.
    active.complete(reason = JourneyExitReason.COMPLETED, nowEpochMillis = System.currentTimeMillis())
    store.updateCache(active)
    val ids2 = store.getActiveJourneyIds(distinctId, camp.id)
    assertEquals(emptySet<String>(), ids2)

    // Deleting should still behave.
    store.deleteJourney(active.id)
    assertNull(store.loadJourney(active.id))
  }
}

