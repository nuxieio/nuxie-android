package io.nuxie.sdk.segments

import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.IREventQueries
import io.nuxie.sdk.ir.IRFeatureQueries
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.util.fromJsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Segment evaluation + membership tracking.
 *
 * Mirrors iOS `SegmentService` behavior at a high level:
 * - evaluates segment IR against local adapters (user/events/segments/features)
 * - tracks membership + enteredAt timestamps
 * - emits enter/exit deltas
 * - optionally persists memberships
 */
class SegmentService(
  private val identityService: IdentityService,
  private val events: IREventQueries?,
  private val irRuntime: IRRuntime,
  private val featureQueriesProvider: () -> IRFeatureQueries? = { null },
  private val membershipStore: SegmentMembershipStore? = null,
  private val scope: CoroutineScope,
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
  private val evaluationIntervalMillis: Long = 60_000L,
  private val enableMonitoring: Boolean = true,
) : IRSegmentQueries {

  private val mutex = Mutex()

  private var segments: List<Segment> = emptyList()
  private var memberships: MutableMap<String, SegmentMembership> = mutableMapOf()
  private var monitoringJob: Job? = null

  private val changes = MutableSharedFlow<SegmentEvaluationResult>(extraBufferCapacity = 16)
  val segmentChanges: SharedFlow<SegmentEvaluationResult> = changes

  suspend fun getCurrentMemberships(): List<SegmentMembership> = mutex.withLock {
    memberships.values.toList()
  }

  suspend fun updateSegments(segments: List<Segment>, distinctId: String = identityService.getDistinctId()): SegmentEvaluationResult {
    mutex.withLock {
      this.segments = segments
    }

    // Best-effort load persisted memberships for this user (only if empty).
    val store = membershipStore
    if (store != null) {
      val loaded = runCatching { store.load(distinctId) }.getOrNull()
      if (loaded != null) {
        mutex.withLock {
          if (memberships.isEmpty()) {
            memberships = loaded.toMutableMap()
          }
        }
      }
    }

    val result = performEvaluation(distinctId)
    if (enableMonitoring) startMonitoringIfNeeded()
    return result
  }

  suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {
    mutex.withLock {
      memberships.clear()
    }

    val store = membershipStore
    if (store != null) {
      val loaded = runCatching { store.load(toNewDistinctId) }.getOrNull()
      if (loaded != null) {
        mutex.withLock { memberships = loaded.toMutableMap() }
      }
    }

    performEvaluation(toNewDistinctId)

    NuxieLogger.info(
      "SegmentService user change: ${NuxieLogger.logDistinctId(fromOldDistinctId)} -> ${NuxieLogger.logDistinctId(toNewDistinctId)}"
    )
  }

  suspend fun clearSegments(distinctId: String) {
    mutex.withLock { memberships.clear() }
    membershipStore?.clear(distinctId)
  }

  suspend fun evaluateNow(distinctId: String = identityService.getDistinctId()): SegmentEvaluationResult {
    return performEvaluation(distinctId)
  }

  override suspend fun isMember(segmentId: String): Boolean = mutex.withLock {
    memberships.containsKey(segmentId)
  }

  override suspend fun enteredAtEpochMillis(segmentId: String): Long? = mutex.withLock {
    memberships[segmentId]?.enteredAtEpochMillis
  }

  private fun startMonitoringIfNeeded() {
    if (monitoringJob != null) return
    if (segments.isEmpty()) return

    monitoringJob = scope.launchSafely {
      while (true) {
        delay(evaluationIntervalMillis)
        val distinctId = identityService.getDistinctId()
        runCatching {
          val res = performEvaluation(distinctId)
          if (res.hasChanges) {
            changes.tryEmit(res)
          }
        }
      }
    }
  }

  private suspend fun performEvaluation(distinctId: String): SegmentEvaluationResult {
    val now = nowEpochMillis()
    val segmentsSnapshot: List<Segment>
    val membershipsSnapshot: Map<String, SegmentMembership>
    mutex.withLock {
      segmentsSnapshot = segments.toList()
      membershipsSnapshot = memberships.toMap()
    }

    val entered = mutableListOf<Segment>()
    val exited = mutableListOf<Segment>()
    val remained = mutableListOf<Segment>()
    val newMemberships = mutableMapOf<String, SegmentMembership>()

    val userAdapter = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    }

    val segmentsAdapter = object : IRSegmentQueries {
      override suspend fun isMember(segmentId: String): Boolean = membershipsSnapshot.containsKey(segmentId)
      override suspend fun enteredAtEpochMillis(segmentId: String): Long? = membershipsSnapshot[segmentId]?.enteredAtEpochMillis
    }

    val featuresAdapter = featureQueriesProvider()

    for (segment in segmentsSnapshot) {
      val qualifies = irRuntime.eval(
        segment.condition,
        IRRuntime.Config(
          nowEpochMillis = now,
          user = userAdapter,
          events = events,
          segments = segmentsAdapter,
          features = featuresAdapter,
        )
      )

      if (qualifies) {
        val existing = membershipsSnapshot[segment.id]
        if (existing != null) {
          remained.add(segment)
          newMemberships[segment.id] = existing.copy(lastEvaluatedEpochMillis = now)
        } else {
          entered.add(segment)
          newMemberships[segment.id] = SegmentMembership(
            segmentId = segment.id,
            segmentName = segment.name,
            enteredAtEpochMillis = now,
            lastEvaluatedEpochMillis = now,
          )
        }
      } else if (membershipsSnapshot.containsKey(segment.id)) {
        exited.add(segment)
      }
    }

    mutex.withLock {
      memberships = newMemberships
    }

    // Persist best-effort.
    val store = membershipStore
    if (store != null) {
      runCatching { store.save(distinctId, newMemberships) }
    }

    val result = SegmentEvaluationResult(
      distinctId = distinctId,
      entered = entered,
      exited = exited,
      remained = remained,
    )

    if (result.hasChanges) {
      changes.tryEmit(result)
    }

    return result
  }

  private fun CoroutineScope.launchSafely(block: suspend CoroutineScope.() -> Unit): Job {
    return this.launch {
      runCatching { block() }
    }
  }
}
