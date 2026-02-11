package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.ir.IREventQueries
import io.nuxie.sdk.ir.IRFeatureQueries
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import io.nuxie.sdk.util.Iso8601

data class GoalMetResult(
  val met: Boolean,
  val atEpochMillis: Long? = null,
)

interface GoalEvaluator {
  suspend fun isGoalMet(journey: Journey, campaign: Campaign): GoalMetResult
}

/**
 * Goal evaluation for journeys.
 *
 * Mirrors iOS `GoalEvaluator` semantics.
 */
class DefaultGoalEvaluator(
  private val loadEventsForUser: suspend (distinctId: String, limit: Int) -> List<StoredEvent>,
  private val segmentQueries: IRSegmentQueries,
  private val featureQueries: IRFeatureQueries?,
  private val userProps: IRUserProps?,
  private val eventQueries: IREventQueries?,
  private val irRuntime: IRRuntime,
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : GoalEvaluator {

  override suspend fun isGoalMet(journey: Journey, campaign: Campaign): GoalMetResult {
    val goal = journey.goalSnapshot ?: return GoalMetResult(met = false)

    val anchorMs = journey.conversionAnchorAtEpochMillis

    return when (goal.kind) {
      GoalConfig.Kind.EVENT -> evaluateEventGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
      GoalConfig.Kind.SEGMENT_ENTER -> evaluateSegmentEnterGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
      GoalConfig.Kind.SEGMENT_LEAVE -> evaluateSegmentLeaveGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
      GoalConfig.Kind.ATTRIBUTE -> evaluateAttributeGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
    }
  }

  private suspend fun evaluateEventGoal(
    goal: GoalConfig,
    journey: Journey,
    anchorEpochMillis: Long,
  ): GoalMetResult {
    val eventName = goal.eventName ?: return GoalMetResult(met = false)

    // If already latched by JourneyService, trust that.
    journey.convertedAtEpochMillis?.let { return GoalMetResult(met = true, atEpochMillis = it) }

    val windowEndMs = if (journey.conversionWindowSeconds > 0) {
      anchorEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
    } else {
      null
    }

    // Fast path: no filter (just check for existence within the window).
    val filter = goal.eventFilter
    if (filter == null) {
      val last = getLastEventTime(
        name = eventName,
        distinctId = journey.distinctId,
        sinceEpochMillis = anchorEpochMillis,
        untilEpochMillis = windowEndMs,
      )
      return if (last != null) GoalMetResult(met = true, atEpochMillis = last) else GoalMetResult(met = false)
    }

    val allEvents = loadEventsForUser(journey.distinctId, 1_000)
    val matching = allEvents.asSequence()
      .filter { it.name == eventName }
      .filter { ev ->
        if (ev.timestampEpochMillis < anchorEpochMillis) return@filter false
        if (windowEndMs != null && ev.timestampEpochMillis > windowEndMs) return@filter false
        true
      }
      .sortedByDescending { it.timestampEpochMillis }
      .toList()

    for (ev in matching) {
      val nuxieEvent = NuxieEvent(
        id = ev.id,
        name = ev.name,
        distinctId = ev.distinctId,
        properties = ev.properties,
        timestamp = Iso8601.formatEpochMillis(ev.timestampEpochMillis),
      )

      val ok = irRuntime.eval(
        filter,
        IRRuntime.Config(
          event = nuxieEvent,
          journeyId = journey.id,
        )
      )

      if (ok) {
        return GoalMetResult(met = true, atEpochMillis = ev.timestampEpochMillis)
      }
    }

    return GoalMetResult(met = false)
  }

  private suspend fun evaluateSegmentEnterGoal(
    goal: GoalConfig,
    journey: Journey,
    anchorEpochMillis: Long,
  ): GoalMetResult {
    val segmentId = goal.segmentId ?: return GoalMetResult(met = false)

    // Segment-time semantics: reject evaluation after the conversion window.
    val now = nowEpochMillis()
    if (journey.conversionWindowSeconds > 0) {
      val end = anchorEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
      if (now > end) return GoalMetResult(met = false)
    }

    val isMember = segmentQueries.isMember(segmentId)
    return if (isMember) GoalMetResult(met = true, atEpochMillis = now) else GoalMetResult(met = false)
  }

  private suspend fun evaluateSegmentLeaveGoal(
    goal: GoalConfig,
    journey: Journey,
    anchorEpochMillis: Long,
  ): GoalMetResult {
    val segmentId = goal.segmentId ?: return GoalMetResult(met = false)

    val now = nowEpochMillis()
    if (journey.conversionWindowSeconds > 0) {
      val end = anchorEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
      if (now > end) return GoalMetResult(met = false)
    }

    val isMember = segmentQueries.isMember(segmentId)
    return if (!isMember) GoalMetResult(met = true, atEpochMillis = now) else GoalMetResult(met = false)
  }

  private suspend fun evaluateAttributeGoal(
    goal: GoalConfig,
    journey: Journey,
    anchorEpochMillis: Long,
  ): GoalMetResult {
    val expr = goal.attributeExpr ?: return GoalMetResult(met = false)

    val now = nowEpochMillis()
    if (journey.conversionWindowSeconds > 0) {
      val end = anchorEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
      if (now > end) return GoalMetResult(met = false)
    }

    val ok = irRuntime.eval(
      expr,
      IRRuntime.Config(
        nowEpochMillis = now,
        user = userProps,
        events = eventQueries,
        segments = segmentQueries,
        features = featureQueries,
        journeyId = journey.id,
      )
    )

    return if (ok) GoalMetResult(met = true, atEpochMillis = now) else GoalMetResult(met = false)
  }

  private suspend fun getLastEventTime(
    name: String,
    distinctId: String,
    sinceEpochMillis: Long,
    untilEpochMillis: Long?,
  ): Long? {
    val events = loadEventsForUser(distinctId, 1_000)
    return events.asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .maxOfOrNull { it.timestampEpochMillis }
  }
}
