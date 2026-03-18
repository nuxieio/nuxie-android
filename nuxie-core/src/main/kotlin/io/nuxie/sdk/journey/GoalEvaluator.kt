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
import io.nuxie.sdk.ir.Period
import io.nuxie.sdk.ir.PredicateEval
import io.nuxie.sdk.ir.StepQuery
import io.nuxie.sdk.util.Iso8601
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

data class GoalMetResult(
  val met: Boolean,
  val atEpochMillis: Long? = null,
)

interface GoalEvaluator {
  suspend fun isGoalMet(
    journey: Journey,
    campaign: Campaign,
    transientEvents: List<StoredEvent>,
  ): GoalMetResult
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

  override suspend fun isGoalMet(
    journey: Journey,
    campaign: Campaign,
    transientEvents: List<StoredEvent>,
  ): GoalMetResult {
    val goal = journey.goalSnapshot ?: return GoalMetResult(met = false)

    val anchorMs = journey.conversionAnchorAtEpochMillis

    return when (goal.kind) {
      GoalConfig.Kind.EVENT -> evaluateEventGoal(
        goal,
        journey = journey,
        anchorEpochMillis = anchorMs,
        transientEvents = transientEvents,
      )
      GoalConfig.Kind.SEGMENT_ENTER -> evaluateSegmentEnterGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
      GoalConfig.Kind.SEGMENT_LEAVE -> evaluateSegmentLeaveGoal(goal, journey = journey, anchorEpochMillis = anchorMs)
      GoalConfig.Kind.ATTRIBUTE -> evaluateAttributeGoal(
        goal,
        journey = journey,
        anchorEpochMillis = anchorMs,
        transientEvents = transientEvents,
      )
    }
  }

  private suspend fun evaluateEventGoal(
    goal: GoalConfig,
    journey: Journey,
    anchorEpochMillis: Long,
    transientEvents: List<StoredEvent> = emptyList(),
  ): GoalMetResult {
    val eventName = goal.eventName ?: return GoalMetResult(met = false)

    // If already latched by JourneyService, trust that.
    journey.convertedAtEpochMillis?.let { return GoalMetResult(met = true, atEpochMillis = it) }

    val last = findLastMatchingEventTime(
      name = eventName,
      filter = goal.eventFilter,
      journey = journey,
      anchorEpochMillis = anchorEpochMillis,
      additionalEvents = transientEvents,
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
    transientEvents: List<StoredEvent> = emptyList(),
  ): GoalMetResult {
    val expr = goal.attributeExpr ?: return GoalMetResult(met = false)

    evaluateEventOnlyAttributeExpr(
      expr = expr.expr,
      journey = journey,
      anchorEpochMillis = anchorEpochMillis,
      transientEvents = transientEvents,
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
        events = eventQueriesForAttributeEvaluation(journey, transientEvents),
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
    additionalEvents: List<StoredEvent> = emptyList(),
  ): Long? {
    val windowEndMs = windowEndEpochMillis(journey, anchorEpochMillis)
    val events = mergeEvents(
      primary = allEvents ?: loadEventsForUser(journey.distinctId, 1_000),
      secondary = additionalEvents,
    )

    if (filter == null) {
      return getLastEventTime(
        name = name,
        events = events,
        sinceEpochMillis = anchorEpochMillis,
        untilEpochMillis = windowEndMs,
      )
    }

    val matchingEvents = events.asSequence()
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
    transientEvents: List<StoredEvent> = emptyList(),
  ): EventOnlyAttributeEvaluation? {
    suspend fun getCachedEvents(): List<StoredEvent> {
      val existing = eventCache.events
      if (existing != null) return existing
      return mergeEvents(
        primary = loadEventsForUser(journey.distinctId, 1_000),
        secondary = transientEvents,
      ).also {
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
            transientEvents = transientEvents,
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
            transientEvents = transientEvents,
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
    events: List<StoredEvent>,
    sinceEpochMillis: Long,
    untilEpochMillis: Long?,
  ): Long? {
    return events.asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .maxOfOrNull { it.timestampEpochMillis }
  }

  private fun mergeEvents(
    primary: List<StoredEvent>,
    secondary: List<StoredEvent>,
  ): List<StoredEvent> {
    if (secondary.isEmpty()) return primary
    return (primary + secondary)
      .distinctBy { it.id }
  }

  private fun eventQueriesForAttributeEvaluation(
    journey: Journey,
    transientEvents: List<StoredEvent>,
  ): IREventQueries? {
    if (transientEvents.isEmpty()) {
      return eventQueries
    }

    return TransientEventQueries(
      distinctId = journey.distinctId,
      loadEventsForUser = loadEventsForUser,
      transientEvents = transientEvents,
      nowEpochMillis = nowEpochMillis,
    )
  }
}

private class TransientEventQueries(
  private val distinctId: String,
  private val loadEventsForUser: suspend (distinctId: String, limit: Int) -> List<StoredEvent>,
  private val transientEvents: List<StoredEvent>,
  private val nowEpochMillis: () -> Long,
) : IREventQueries {
  companion object {
    private const val DEFAULT_QUERY_LIMIT = 5_000
  }

  override suspend fun exists(
    name: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Boolean {
    return count(name, sinceEpochMillis, untilEpochMillis, where) > 0
  }

  override suspend fun count(
    name: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Int {
    val events = mergedEvents(limit = 1_000)
    return events.asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (sinceEpochMillis != null && ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .count()
  }

  override suspend fun firstTime(name: String, where: io.nuxie.sdk.ir.IRPredicate?): Long? {
    val events = mergedEvents(limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { it.name == name }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .sortedBy { it.timestampEpochMillis }
      .toList()
    return events.firstOrNull()?.timestampEpochMillis
  }

  override suspend fun lastTime(name: String, where: io.nuxie.sdk.ir.IRPredicate?): Long? {
    val events = mergedEvents(limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { it.name == name }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .sortedByDescending { it.timestampEpochMillis }
      .toList()
    return events.firstOrNull()?.timestampEpochMillis
  }

  override suspend fun aggregate(
    agg: io.nuxie.sdk.ir.Aggregate,
    name: String,
    prop: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Double? {
    val events = mergedEvents(limit = DEFAULT_QUERY_LIMIT)
    val values = events.asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (sinceEpochMillis != null && ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .mapNotNull { ev ->
        where?.let { if (!PredicateEval.eval(it, ev.properties)) return@mapNotNull null }
        io.nuxie.sdk.ir.Coercion.asNumber(ev.properties[prop])
      }
      .toList()

    if (values.isEmpty()) return null

    return when (agg) {
      io.nuxie.sdk.ir.Aggregate.SUM -> values.sum()
      io.nuxie.sdk.ir.Aggregate.AVG -> values.sum() / values.size.toDouble()
      io.nuxie.sdk.ir.Aggregate.MIN -> values.minOrNull()
      io.nuxie.sdk.ir.Aggregate.MAX -> values.maxOrNull()
      io.nuxie.sdk.ir.Aggregate.UNIQUE -> values.toSet().size.toDouble()
    }
  }

  override suspend fun inOrder(
    steps: List<StepQuery>,
    overallWithinSeconds: Double?,
    perStepWithinSeconds: Double?,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
  ): Boolean {
    val events = mergedEvents(limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { ev ->
        if (sinceEpochMillis != null && ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .sortedBy { it.timestampEpochMillis }
      .toList()

    var lastTime: Long? = null
    val startRef = events.firstOrNull()?.timestampEpochMillis

    for (step in steps) {
      val match = events.firstOrNull { ev ->
        val after = lastTime ?: sinceEpochMillis ?: Long.MIN_VALUE
        if (ev.timestampEpochMillis < after) return@firstOrNull false
        if (ev.name != step.name) return@firstOrNull false
        if (step.predicate != null && !PredicateEval.eval(step.predicate, ev.properties)) return@firstOrNull false
        if (perStepWithinSeconds != null && lastTime != null) {
          if ((ev.timestampEpochMillis - lastTime) > (perStepWithinSeconds * 1000.0)) return@firstOrNull false
        }
        if (overallWithinSeconds != null && startRef != null) {
          if ((ev.timestampEpochMillis - startRef) > (overallWithinSeconds * 1000.0)) return@firstOrNull false
        }
        true
      } ?: return false
      lastTime = match.timestampEpochMillis
    }

    return true
  }

  override suspend fun activePeriods(
    name: String,
    period: Period,
    total: Int,
    min: Int,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Boolean {
    val events = mergedEvents(limit = 10_000)
      .asSequence()
      .filter { it.name == name }
      .toList()

    if (total <= 0 || min <= 0) return false

    val tz = TimeZone.getTimeZone("UTC")
    val cal = Calendar.getInstance(tz).apply { timeZone = tz }
    val nowMs = nowEpochMillis()

    val windowStartMs = run {
      cal.time = Date(nowMs)
      when (period) {
        Period.DAY -> cal.add(Calendar.DAY_OF_YEAR, -total)
        Period.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, -total)
        Period.MONTH -> cal.add(Calendar.MONTH, -total)
        Period.YEAR -> cal.add(Calendar.YEAR, -total)
      }
      cal.timeInMillis
    }

    val buckets = HashSet<String>()
    for (ev in events) {
      if (ev.timestampEpochMillis < windowStartMs) continue
      if (where != null && !PredicateEval.eval(where, ev.properties)) continue

      cal.time = Date(ev.timestampEpochMillis)
      val key = when (period) {
        Period.DAY -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        Period.WEEK -> "${cal.weekYear}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
        Period.MONTH -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        Period.YEAR -> "${cal.get(Calendar.YEAR)}"
      }
      buckets.add(key)
    }

    return buckets.size >= min
  }

  override suspend fun stopped(
    name: String,
    inactiveForSeconds: Double,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Boolean {
    val last = lastTime(name, where) ?: return false
    return (nowEpochMillis() - last) >= (inactiveForSeconds * 1000.0)
  }

  override suspend fun restarted(
    name: String,
    inactiveForSeconds: Double,
    withinSeconds: Double,
    where: io.nuxie.sdk.ir.IRPredicate?,
  ): Boolean {
    val nowMs = nowEpochMillis()
    val events = mergedEvents(limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { it.name == name }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .sortedBy { it.timestampEpochMillis }
      .toList()

    var prev: Long? = null
    var hadGap = false
    val inactiveForMs = (inactiveForSeconds * 1000.0).toLong()

    for (ev in events) {
      if (prev != null && (ev.timestampEpochMillis - prev) >= inactiveForMs) {
        hadGap = true
      }
      prev = ev.timestampEpochMillis
    }

    if (!hadGap) return false

    val withinMs = (withinSeconds * 1000.0).toLong()
    return events.any { ev -> (nowMs - ev.timestampEpochMillis) <= withinMs }
  }

  private suspend fun mergedEvents(limit: Int): List<StoredEvent> {
    val persisted = loadEventsForUser(distinctId, limit)
    if (transientEvents.isEmpty()) return persisted
    return (persisted + transientEvents)
      .distinctBy { it.id }
  }
}
