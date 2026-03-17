package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.ir.IREventQueries
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.ir.IRExpr
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

private data class EventOnlyAttributeEvaluation(
  val met: Boolean,
  val atEpochMillis: Long? = null,
)

private class EventHistoryCache(
  initialEvents: List<StoredEvent>? = null,
) {
  var events: List<StoredEvent>? = initialEvents
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

    val last = findLastMatchingEventTime(
      name = eventName,
      filter = goal.eventFilter,
      journey = journey,
      anchorEpochMillis = anchorEpochMillis,
    )
    return if (last != null) GoalMetResult(met = true, atEpochMillis = last) else GoalMetResult(met = false)
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

    evaluateEventOnlyAttributeExpr(
      expr = expr.expr,
      journey = journey,
      anchorEpochMillis = anchorEpochMillis,
    )?.let { result ->
      return if (result.met) {
        GoalMetResult(met = true, atEpochMillis = result.atEpochMillis)
      } else {
        GoalMetResult(met = false)
      }
    }

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

  private fun windowEndEpochMillis(
    journey: Journey,
    anchorEpochMillis: Long,
  ): Long? {
    return if (journey.conversionWindowSeconds > 0) {
      anchorEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
    } else {
      null
    }
  }

  private suspend fun findLastMatchingEventTime(
    name: String,
    filter: IREnvelope?,
    journey: Journey,
    anchorEpochMillis: Long,
    allEvents: List<StoredEvent>? = null,
  ): Long? {
    val windowEndMs = windowEndEpochMillis(journey, anchorEpochMillis)

    if (filter == null) {
      return getLastEventTime(
        name = name,
        distinctId = journey.distinctId,
        sinceEpochMillis = anchorEpochMillis,
        untilEpochMillis = windowEndMs,
      )
    }

    val matchingEvents = (allEvents ?: loadEventsForUser(journey.distinctId, 1_000)).asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (ev.timestampEpochMillis < anchorEpochMillis) return@filter false
        if (windowEndMs != null && ev.timestampEpochMillis > windowEndMs) return@filter false
        true
      }
      .sortedByDescending { it.timestampEpochMillis }
      .toList()

    for (ev in matchingEvents) {
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
        return ev.timestampEpochMillis
      }
    }

    return null
  }

  private suspend fun evaluateEventOnlyAttributeExpr(
    expr: IRExpr,
    journey: Journey,
    anchorEpochMillis: Long,
    eventCache: EventHistoryCache = EventHistoryCache(),
  ): EventOnlyAttributeEvaluation? {
    suspend fun getCachedEvents(): List<StoredEvent> {
      val existing = eventCache.events
      if (existing != null) return existing
      return loadEventsForUser(journey.distinctId, 1_000).also {
        eventCache.events = it
      }
    }

    return when (expr) {
      is IRExpr.And -> {
        val results = expr.args.map { child ->
          evaluateEventOnlyAttributeExpr(
            expr = child,
            journey = journey,
            anchorEpochMillis = anchorEpochMillis,
            eventCache = eventCache,
          ) ?: return null
        }
        if (results.all { it.met }) {
          EventOnlyAttributeEvaluation(
            met = true,
            atEpochMillis = results.mapNotNull { it.atEpochMillis }.maxOrNull(),
          )
        } else {
          EventOnlyAttributeEvaluation(met = false)
        }
      }

      is IRExpr.Or -> {
        val results = expr.args.map { child ->
          evaluateEventOnlyAttributeExpr(
            expr = child,
            journey = journey,
            anchorEpochMillis = anchorEpochMillis,
            eventCache = eventCache,
          ) ?: return null
        }
        val metTimes = results.filter { it.met }.mapNotNull { it.atEpochMillis }
        if (metTimes.isNotEmpty()) {
          EventOnlyAttributeEvaluation(
            met = true,
            atEpochMillis = metTimes.minOrNull(),
          )
        } else {
          EventOnlyAttributeEvaluation(met = false)
        }
      }

      is IRExpr.EventsExists -> {
        if (expr.since != null || expr.until != null || expr.within != null) {
          return null
        }
        val last = findLastMatchingEventTime(
          name = expr.name,
          filter = IREnvelope(irVersion = 1, expr = expr.whereExpr ?: IRExpr.Bool(true)),
          journey = journey,
          anchorEpochMillis = anchorEpochMillis,
          allEvents = getCachedEvents(),
        )
        if (last != null) {
          EventOnlyAttributeEvaluation(met = true, atEpochMillis = last)
        } else {
          EventOnlyAttributeEvaluation(met = false)
        }
      }

      else -> null
    }
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
