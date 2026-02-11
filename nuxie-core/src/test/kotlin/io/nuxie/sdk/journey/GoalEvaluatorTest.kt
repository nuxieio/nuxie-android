package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.ir.IRExpr
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalEvaluatorTest {

  private fun campaign(goal: GoalConfig?): Campaign {
    return Campaign(
      id = "camp_1",
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "app_opened")),
      goal = goal,
      exitPolicy = null,
      conversionAnchor = null,
      campaignType = null,
    )
  }

  private class FakeSegments(private val memberIds: Set<String>) : IRSegmentQueries {
    override suspend fun isMember(segmentId: String): Boolean = memberIds.contains(segmentId)
    override suspend fun enteredAtEpochMillis(segmentId: String): Long? = null
  }

  @Test
  fun eventGoalNoFilterRespectsWindowByEventTime() = runBlocking {
    val history = InMemoryEventHistoryStore()

    val anchor = 10_000L
    val end = 12_000L
    history.insert(StoredEvent("e1", "goal_event", "user_1", timestampEpochMillis = 11_000L, properties = mapOf()))
    history.insert(StoredEvent("e2", "goal_event", "user_1", timestampEpochMillis = 13_000L, properties = mapOf()))

    val evaluator = DefaultGoalEvaluator(
      loadEventsForUser = { distinctId, limit -> history.getEventsForUser(distinctId, limit) },
      segmentQueries = FakeSegments(emptySet()),
      featureQueries = null,
      userProps = null,
      eventQueries = null,
      irRuntime = IRRuntime { end },
      nowEpochMillis = { end },
    )

    val goal = GoalConfig(kind = GoalConfig.Kind.EVENT, eventName = "goal_event", window = 2.0)
    val camp = campaign(goal)
    val journey = Journey(campaign = camp, distinctId = "user_1", nowEpochMillis = anchor).apply {
      conversionAnchorAtEpochMillis = anchor
      conversionWindowSeconds = 2.0
    }

    val result = evaluator.isGoalMet(journey, camp)
    assertTrue(result.met)
    assertEquals(11_000L, result.atEpochMillis)
  }

  @Test
  fun eventGoalWithFilterFindsMostRecentMatchingEvent() = runBlocking {
    val history = InMemoryEventHistoryStore()

    val anchor = 10_000L
    history.insert(
      StoredEvent(
        id = "e1",
        name = "goal_event",
        distinctId = "user_1",
        timestampEpochMillis = 11_000L,
        properties = mapOf("foo" to "bar"),
      )
    )
    history.insert(
      StoredEvent(
        id = "e2",
        name = "goal_event",
        distinctId = "user_1",
        timestampEpochMillis = 11_500L,
        properties = mapOf("foo" to "nope"),
      )
    )

    val filter = IREnvelope(
      irVersion = 1,
      expr = IRExpr.Pred(op = "eq", key = "foo", value = IRExpr.String("bar"))
    )

    val evaluator = DefaultGoalEvaluator(
      loadEventsForUser = { distinctId, limit -> history.getEventsForUser(distinctId, limit) },
      segmentQueries = FakeSegments(emptySet()),
      featureQueries = null,
      userProps = null,
      eventQueries = null,
      irRuntime = IRRuntime { 12_000L },
      nowEpochMillis = { 12_000L },
    )

    val goal = GoalConfig(kind = GoalConfig.Kind.EVENT, eventName = "goal_event", eventFilter = filter, window = 5.0)
    val camp = campaign(goal)
    val journey = Journey(campaign = camp, distinctId = "user_1", nowEpochMillis = anchor).apply {
      conversionAnchorAtEpochMillis = anchor
      conversionWindowSeconds = 5.0
    }

    val result = evaluator.isGoalMet(journey, camp)
    assertTrue(result.met)
    assertEquals(11_000L, result.atEpochMillis)
  }

  @Test
  fun segmentEnterGoalRequiresWithinWindow() = runBlocking {
    val history = InMemoryEventHistoryStore()
    val now = 20_000L
    val evaluator = DefaultGoalEvaluator(
      loadEventsForUser = { distinctId, limit -> history.getEventsForUser(distinctId, limit) },
      segmentQueries = FakeSegments(setOf("seg_1")),
      featureQueries = null,
      userProps = null,
      eventQueries = null,
      irRuntime = IRRuntime { now },
      nowEpochMillis = { now },
    )

    val goal = GoalConfig(kind = GoalConfig.Kind.SEGMENT_ENTER, segmentId = "seg_1", window = 1.0)
    val camp = campaign(goal)
    val journey = Journey(campaign = camp, distinctId = "user_1", nowEpochMillis = now - 2_000L).apply {
      conversionAnchorAtEpochMillis = now - 2_000L
      conversionWindowSeconds = 1.0
    }

    val result = evaluator.isGoalMet(journey, camp)
    assertFalse(result.met)
  }

  @Test
  fun attributeGoalEvaluatesIRAgainstUserProps() = runBlocking {
    val history = InMemoryEventHistoryStore()
    val now = 50_000L
    val user = IRUserProps { key -> if (key == "plan") "pro" else null }

    val evaluator = DefaultGoalEvaluator(
      loadEventsForUser = { distinctId, limit -> history.getEventsForUser(distinctId, limit) },
      segmentQueries = FakeSegments(emptySet()),
      featureQueries = null,
      userProps = user,
      eventQueries = null,
      irRuntime = IRRuntime { now },
      nowEpochMillis = { now },
    )

    val expr = IREnvelope(
      irVersion = 1,
      expr = IRExpr.User(op = "eq", key = "plan", value = IRExpr.String("pro"))
    )

    val goal = GoalConfig(kind = GoalConfig.Kind.ATTRIBUTE, attributeExpr = expr, window = 10.0)
    val camp = campaign(goal)
    val journey = Journey(campaign = camp, distinctId = "user_1", nowEpochMillis = now).apply {
      conversionAnchorAtEpochMillis = now
      conversionWindowSeconds = 10.0
    }

    val result = evaluator.isGoalMet(journey, camp)
    assertTrue(result.met)
    assertEquals(now, result.atEpochMillis)
  }
}
