package io.nuxie.sdk.events

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.store.EventHistoryStore
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.Aggregate
import io.nuxie.sdk.ir.Coercion
import io.nuxie.sdk.ir.IREventQueries
import io.nuxie.sdk.ir.IRPredicate
import io.nuxie.sdk.ir.Period
import io.nuxie.sdk.ir.PredicateEval
import io.nuxie.sdk.ir.StepQuery
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class EventService(
  private val identityService: IdentityService,
  private val sessionService: SessionService,
  private val configuration: NuxieConfiguration,
  private val api: NuxieApiProtocol,
  private val store: EventQueueStore,
  private val historyStore: EventHistoryStore,
  private val networkQueue: NuxieNetworkQueue,
  private val scope: CoroutineScope,
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : IREventQueries {
  companion object {
    private const val DEFAULT_QUERY_LIMIT = 5_000
  }

  fun track(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
    value: Double? = null,
    entityId: String? = null,
  ) {
    if (event.isBlank()) {
      NuxieLogger.warning("Event name cannot be empty")
      return
    }

    // Snapshot id NOW to preserve pre/post identify semantics (parity with iOS).
    val distinctIdSnapshot = identityService.getDistinctId()

    val mergedProps = buildMap<String, Any?> {
      putAll(properties ?: emptyMap())
      if (userProperties != null) put("\$set", userProperties)
      if (userPropertiesSetOnce != null) put("\$set_once", userPropertiesSetOnce)
      if (value != null) put("value", value)
      if (entityId != null) put("entityId", entityId)

      if (!containsKey("\$session_id")) {
        val sessionId = sessionService.getSessionId(readOnly = false)
        if (sessionId != null) {
          put("\$session_id", sessionId)
          sessionService.touchSession()
        }
      }
    }.let { props ->
      configuration.propertiesSanitizer?.sanitize(props) ?: props
    }

    val nuxieEvent = NuxieEvent(
      name = event,
      distinctId = distinctIdSnapshot,
      properties = mergedProps,
    )

    val finalEvent = if (configuration.beforeSend != null) {
      configuration.beforeSend?.invoke(nuxieEvent) ?: return
    } else {
      nuxieEvent
    }

    // Mirror iOS route(): user property extraction is driven by local events.
    extractUserProperties(finalEvent)

    // Store event locally (best-effort) for IR evaluation (segments/journeys).
    scope.launch {
      runCatching { historyStore.insert(storedEvent(finalEvent)) }
    }

    val propsJson: JsonObject = toJsonObject(finalEvent.properties)
    val anonDistinctId = (finalEvent.properties["\$anon_distinct_id"] as? String)

    val queued = QueuedEvent(
      id = finalEvent.id,
      name = finalEvent.name,
      distinctId = finalEvent.distinctId,
      anonDistinctId = anonDistinctId,
      timestamp = finalEvent.timestamp,
      properties = propsJson,
      value = (finalEvent.properties["value"] as? Number)?.toDouble(),
      entityId = finalEvent.properties["entityId"] as? String,
    )

    scope.launch {
      val ok = networkQueue.enqueue(queued)
      if (!ok) {
        NuxieLogger.warning("Dropped event due to queue limits: ${queued.name}")
      }
    }
  }

  suspend fun flushEvents(): Boolean = networkQueue.flush(forceSend = true)

  suspend fun getQueuedEventCount(): Int = store.size()

  suspend fun pauseEventQueue() = networkQueue.pause()

  suspend fun resumeEventQueue() = networkQueue.resume()

  /**
   * Reassign queued events from one distinctId to another.
   *
   * This is used for anonymous -> identified linking when `eventLinkingPolicy` is `migrateOnIdentify`.
   */
  suspend fun reassignEvents(fromDistinctId: String, toDistinctId: String): Int {
    val queued = store.reassignDistinctId(fromDistinctId = fromDistinctId, toDistinctId = toDistinctId)
    val history = historyStore.reassignDistinctId(fromDistinctId = fromDistinctId, toDistinctId = toDistinctId)
    return queued + history
  }

  /**
   * Track an event synchronously and return the enriched event plus server response.
   *
   * Mirrors iOS `EventService.trackForTrigger(...)`:
   * - flushes pending batch queue first (best-effort)
   * - calls `POST /event` directly
   * - records a local history copy for segment/journey evaluation
   */
  suspend fun trackForTrigger(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  ): Pair<NuxieEvent, EventResponse> {
    if (event.isBlank()) {
      throw IllegalArgumentException("Event name cannot be empty")
    }

    // Ensure any queued events are delivered first so the trigger call observes a consistent order.
    runCatching { networkQueue.flush(forceSend = true) }

    val distinctId = identityService.getDistinctId()

    val mergedProps = buildMap<String, Any?> {
      putAll(properties ?: emptyMap())
      if (userProperties != null) put("\$set", userProperties)
      if (userPropertiesSetOnce != null) put("\$set_once", userPropertiesSetOnce)

      if (!containsKey("\$session_id")) {
        val sessionId = sessionService.getSessionId(readOnly = false)
        if (sessionId != null) {
          put("\$session_id", sessionId)
          sessionService.touchSession()
        }
      }
    }.let { props ->
      configuration.propertiesSanitizer?.sanitize(props) ?: props
    }

    val nuxieEvent = NuxieEvent(
      name = event,
      distinctId = distinctId,
      properties = mergedProps,
    )

    val finalEvent = if (configuration.beforeSend != null) {
      configuration.beforeSend?.invoke(nuxieEvent) ?: throw IllegalStateException("Event dropped by beforeSend")
    } else {
      nuxieEvent
    }

    // Store event locally (best-effort) before trigger evaluation continues.
    runCatching { historyStore.insert(storedEvent(finalEvent)) }

    val propsJson: JsonObject = toJsonObject(finalEvent.properties)
    val anonDistinctId = (finalEvent.properties["\$anon_distinct_id"] as? String)

    val response = api.trackEvent(
      event = finalEvent.name,
      distinctId = finalEvent.distinctId,
      anonDistinctId = anonDistinctId,
      properties = propsJson,
      uuid = finalEvent.id,
      value = finalEvent.properties["value"] as? Double,
      entityId = finalEvent.properties["entityId"] as? String,
      timestamp = finalEvent.timestamp,
    )

    val eventId = response.event?.id ?: finalEvent.id
    val enriched = NuxieEvent(
      id = eventId,
      name = finalEvent.name,
      distinctId = finalEvent.distinctId,
      properties = finalEvent.properties,
      timestamp = finalEvent.timestamp,
    )

    return enriched to response
  }

  suspend fun getRecentEvents(limit: Int = 100): List<StoredEvent> {
    return historyStore.getRecentEvents(limit)
  }

  suspend fun getEventsForUser(distinctId: String, limit: Int = 100): List<StoredEvent> {
    return historyStore.getEventsForUser(distinctId, limit)
  }

  suspend fun getEventCount(): Int = historyStore.count()

  // MARK: - IREventQueries

  override suspend fun exists(
    name: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: IRPredicate?,
  ): Boolean {
    return count(name, sinceEpochMillis, untilEpochMillis, where) > 0
  }

  override suspend fun count(
    name: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: IRPredicate?,
  ): Int {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = 1_000)
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

  override suspend fun firstTime(name: String, where: IRPredicate?): Long? {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { it.name == name }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .sortedBy { it.timestampEpochMillis }
      .toList()
    return events.firstOrNull()?.timestampEpochMillis
  }

  override suspend fun lastTime(name: String, where: IRPredicate?): Long? {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = DEFAULT_QUERY_LIMIT)
      .asSequence()
      .filter { it.name == name }
      .filter { ev -> where?.let { PredicateEval.eval(it, ev.properties) } ?: true }
      .sortedByDescending { it.timestampEpochMillis }
      .toList()
    return events.firstOrNull()?.timestampEpochMillis
  }

  override suspend fun aggregate(
    agg: Aggregate,
    name: String,
    prop: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: IRPredicate?,
  ): Double? {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = DEFAULT_QUERY_LIMIT)
    val values = events.asSequence()
      .filter { it.name == name }
      .filter { ev ->
        if (sinceEpochMillis != null && ev.timestampEpochMillis < sinceEpochMillis) return@filter false
        if (untilEpochMillis != null && ev.timestampEpochMillis > untilEpochMillis) return@filter false
        true
      }
      .mapNotNull { ev ->
        where?.let { if (!PredicateEval.eval(it, ev.properties)) return@mapNotNull null }
        Coercion.asNumber(ev.properties[prop])
      }
      .toList()

    if (values.isEmpty()) return null

    return when (agg) {
      Aggregate.SUM -> values.sum()
      Aggregate.AVG -> values.sum() / values.size.toDouble()
      Aggregate.MIN -> values.minOrNull()
      Aggregate.MAX -> values.maxOrNull()
      Aggregate.UNIQUE -> values.toSet().size.toDouble()
    }
  }

  override suspend fun inOrder(
    steps: List<StepQuery>,
    overallWithinSeconds: Double?,
    perStepWithinSeconds: Double?,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
  ): Boolean {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = DEFAULT_QUERY_LIMIT)
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
    where: IRPredicate?,
  ): Boolean {
    val distinctId = identityService.getDistinctId()
    val events = getEventsForUser(distinctId, limit = 10_000)
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

  override suspend fun stopped(name: String, inactiveForSeconds: Double, where: IRPredicate?): Boolean {
    val last = lastTime(name, where) ?: return false
    return (nowEpochMillis() - last) >= (inactiveForSeconds * 1000.0)
  }

  override suspend fun restarted(
    name: String,
    inactiveForSeconds: Double,
    withinSeconds: Double,
    where: IRPredicate?,
  ): Boolean {
    val distinctId = identityService.getDistinctId()
    val nowMs = nowEpochMillis()
    val events = getEventsForUser(distinctId, limit = DEFAULT_QUERY_LIMIT)
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

  private fun extractUserProperties(event: NuxieEvent) {
    val set = event.properties["\$set"] as? Map<*, *>
    if (set != null) {
      identityService.setUserProperties(set.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap())
    }
    val setOnce = event.properties["\$set_once"] as? Map<*, *>
    if (setOnce != null) {
      identityService.setOnceUserProperties(setOnce.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap())
    }
  }

  private fun storedEvent(event: NuxieEvent): StoredEvent {
    val tsSeconds = Coercion.asTimestamp(event.timestamp)
    val tsMs = if (tsSeconds != null) (tsSeconds * 1000.0).toLong() else nowEpochMillis()
    return StoredEvent(
      id = event.id,
      name = event.name,
      distinctId = event.distinctId,
      timestampEpochMillis = tsMs,
      properties = event.properties,
    )
  }
}
