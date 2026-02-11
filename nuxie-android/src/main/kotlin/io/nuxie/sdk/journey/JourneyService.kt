package io.nuxie.sdk.journey

import android.app.Activity
import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.ExitPolicy
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.flows.Flow
import io.nuxie.sdk.flows.FlowRuntimeDelegate
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.FlowView
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.VmPathRef
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.IRFeatureQueries
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.JourneyExitReason
import io.nuxie.sdk.triggers.JourneyRef
import io.nuxie.sdk.triggers.JourneyUpdate
import io.nuxie.sdk.triggers.SuppressReason
import io.nuxie.sdk.triggers.TriggerBroker
import io.nuxie.sdk.triggers.TriggerUpdate
import io.nuxie.sdk.util.fromJsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class JourneyTriggerResult {
  data class Started(val journey: Journey) : JourneyTriggerResult()
  data class Suppressed(val reason: SuppressReason) : JourneyTriggerResult()
}

class JourneyService(
  private val scope: CoroutineScope,
  private val configuration: NuxieConfiguration,
  private val identityService: IdentityService,
  private val eventService: EventService,
  private val profileService: ProfileService,
  private val segmentService: SegmentService,
  private val featureService: FeatureService,
  private val flowService: FlowService,
  private val journeyStore: JourneyStore,
  private val triggerBroker: TriggerBroker = DefaultTriggerBroker(),
  private val irRuntime: IRRuntime = IRRuntime(),
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
  private val presentFlow: suspend (flowId: String, journeyId: String) -> Boolean,
) {
  private val inMemoryJourneysById: MutableMap<String, Journey> = mutableMapOf()
  private val flowRunners: MutableMap<String, FlowJourneyRunner> = mutableMapOf()
  private val runtimeDelegates: MutableMap<String, FlowRuntimeDelegateAdapter> = mutableMapOf()
  private val activeTasks: MutableMap<String, Job> = mutableMapOf()
  private var segmentMonitoringJob: Job? = null

  private val goalEvaluator: GoalEvaluator = DefaultGoalEvaluator(
    loadEventsForUser = { distinctId, limit -> eventService.getEventsForUser(distinctId, limit) },
    segmentQueries = segmentService as IRSegmentQueries,
    featureQueries = object : IRFeatureQueries {
      override suspend fun has(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.allowed == true
      }

      override suspend fun isUnlimited(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.unlimited == true
      }

      override suspend fun getBalance(featureId: String): Int? {
        return featureService.getCached(featureId, null)?.balance
      }
    },
    userProps = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    },
    eventQueries = eventService,
    irRuntime = irRuntime,
    nowEpochMillis = nowEpochMillis,
  )

  suspend fun initialize() {
    val restored = journeyStore.loadActiveJourneys()
    for (journey in restored) {
      if (!journey.status.isLive) continue
      inMemoryJourneysById[journey.id] = journey
      val resumeAt = journey.flowState.pendingAction?.resumeAtEpochMillis
      if (resumeAt != null) {
        scheduleResume(journey.id, resumeAt)
      }
    }
    checkExpiredTimers()
    registerForSegmentChanges()
  }

  suspend fun shutdown() {
    segmentMonitoringJob?.cancel()
    segmentMonitoringJob = null
    cancelAllTasks()
  }

  suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {
    val oldJourneys = getActiveJourneys(fromOldDistinctId)
    for (journey in oldJourneys) {
      cancelJourney(journey)
    }

    inMemoryJourneysById.entries.removeAll { it.value.distinctId == fromOldDistinctId }

    val restored = journeyStore.loadActiveJourneys().filter { it.distinctId == toNewDistinctId && it.status.isLive }
    for (journey in restored) {
      inMemoryJourneysById[journey.id] = journey
      val resumeAt = journey.flowState.pendingAction?.resumeAtEpochMillis
      if (resumeAt != null) {
        scheduleResume(journey.id, resumeAt)
      }
    }
    checkExpiredTimers()
  }

  suspend fun handleEventForTrigger(event: NuxieEvent): List<JourneyTriggerResult> {
    val campaigns = getAllCampaigns(event.distinctId) ?: return emptyList()
    val results = mutableListOf<JourneyTriggerResult>()

    for (campaign in campaigns) {
      if (!shouldTriggerFromEvent(campaign, event)) continue
      val reason = suppressionReason(campaign, event.distinctId)
      if (reason != null) {
        results += JourneyTriggerResult.Suppressed(reason)
        continue
      }
      val journey = startJourneyInternal(campaign, event.distinctId, originEventId = event.id)
      if (journey != null) {
        results += JourneyTriggerResult.Started(journey)
      } else {
        results += JourneyTriggerResult.Suppressed(SuppressReason.Unknown("start_failed"))
      }
    }

    val activeJourneys = getActiveJourneys(event.distinctId)
    for (journey in activeJourneys) {
      val campaign = campaigns.firstOrNull { it.id == journey.campaignId } ?: continue
      evaluateGoalIfNeeded(journey, campaign)
      val exit = exitDecision(journey, campaign)
      if (exit != null) {
        completeJourney(journey, exit)
        continue
      }

      val pending = journey.flowState.pendingAction
      if (pending != null && pending.kind == FlowPendingActionKind.WAIT_UNTIL) {
        val runner = flowRunners[journey.id]
        if (runner != null) {
          val outcome = runner.resumePendingAction(ResumeReason.EVENT, event)
          handleOutcome(outcome, journey)
        }
        continue
      }

      val runner = flowRunners[journey.id]
      if (runner != null) {
        val outcome = runner.dispatchEventTrigger(event)
        handleOutcome(outcome, journey)
      }
    }

    return results
  }

  suspend fun handleSegmentChange(distinctId: String, segments: Set<String>) {
    val campaigns = getAllCampaigns(distinctId) ?: return

    for (campaign in campaigns) {
      val trigger = campaign.trigger as? CampaignTrigger.Segment ?: continue
      val matches = evalConditionIR(trigger.config.condition, event = null)
      if (matches) {
        startJourney(campaign, distinctId, originEventId = null)
      }
    }

    val journeys = getActiveJourneys(distinctId)
    for (journey in journeys) {
      val campaign = campaigns.firstOrNull { it.id == journey.campaignId } ?: continue
      evaluateGoalIfNeeded(journey, campaign)
      val exit = exitDecision(journey, campaign)
      if (exit != null) {
        completeJourney(journey, exit)
      }
    }
  }

  suspend fun checkExpiredTimers() {
    val now = nowEpochMillis()
    val liveJourneys = inMemoryJourneysById.values.filter { it.status.isLive }
    for (journey in liveJourneys) {
      val pending = journey.flowState.pendingAction
      val resumeAt = pending?.resumeAtEpochMillis
      if (resumeAt != null && resumeAt <= now) {
        resumeJourney(journey)
      }
    }
  }

  suspend fun getActiveJourneys(distinctId: String): List<Journey> {
    return inMemoryJourneysById.values.filter { it.distinctId == distinctId && it.status.isLive }
  }

  suspend fun startJourney(campaign: Campaign, distinctId: String, originEventId: String?): Journey? {
    val reason = suppressionReason(campaign, distinctId)
    if (reason != null) return null
    return startJourneyInternal(campaign, distinctId, originEventId)
  }

  suspend fun createFlowViewForJourney(activity: Activity, flowId: String, journeyId: String): FlowView {
    val delegate = runtimeDelegates[journeyId] ?: FlowRuntimeDelegateAdapter(journeyId, this, scope).also {
      runtimeDelegates[journeyId] = it
    }

    val view = flowService.getFlowView(activity, flowId, runtimeDelegate = delegate)
    delegate.attachFlowView(view)
    flowRunners[journeyId]?.attach(delegate)
    return view
  }

  suspend fun handleRuntimeMessage(
    journeyId: String,
    type: String,
    payload: JsonObject,
    id: String?,
  ) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val runner = flowRunners[journeyId] ?: return

    when (type) {
      "runtime/ready" -> {
        val outcome = runner.handleRuntimeReady()
        handleOutcome(outcome, journey)
      }

      "runtime/screen_changed" -> {
        val screenId = payload.string("screenId") ?: return
        val outcome = runner.handleScreenChanged(screenId)
        handleOutcome(outcome, journey)
        persistJourney(journey)

        eventService.track(
          "\$journey_node_executed",
          properties = mapOf(
            "session_id" to journey.id,
            "node_id" to screenId,
            "context" to journey.context.mapValues { (_, v) -> fromJsonElement(v) },
          )
        )
      }

      "action/did_set" -> {
        val path = parsePathRef(payload) ?: return
        val value = payload["value"] ?: JsonNull
        val source = payload.string("source")
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val outcome = runner.handleDidSet(path, value, source, screenId, instanceId)
        handleOutcome(outcome, journey)
        persistJourney(journey)
      }

      "action/event" -> {
        val name = payload.string("name") ?: ""
        if (name.isNotBlank()) {
          val props = (payload["properties"] as? JsonObject)?.toMapAny()
          eventService.track(name, properties = props)
        }
      }

      "action/purchase" -> {
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val resolvedProductId = runner.resolveRuntimeValue(payload["productId"] ?: JsonPrimitive(""), screenId, instanceId) as? String
        if (!resolvedProductId.isNullOrBlank()) {
          val placementIndex = runner.resolveRuntimeValue(payload["placementIndex"] ?: JsonNull, screenId, instanceId)
          runtimeDelegates[journeyId]?.performPurchase(resolvedProductId, placementIndex)
        }
      }

      "action/restore" -> {
        runtimeDelegates[journeyId]?.performRestore()
      }

      "action/open_link" -> {
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val target = payload.string("target")
        runner.handleRuntimeOpenLink(payload["url"] ?: JsonPrimitive(""), target, screenId, instanceId)
      }

      "action/back" -> {
        val steps = payload.int("steps") ?: payload.int("step")
        val transition = payload["transition"]
        runner.handleRuntimeBack(steps, transition)
      }

      else -> {
        if (type.startsWith("action/")) {
          val trigger = parseRuntimeTrigger(type, payload)
          if (trigger != null) {
            val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
            val componentId = payload.string("componentId")
            val instanceId = payload.string("instanceId")
            val outcome = runner.dispatchTrigger(
              trigger = trigger,
              screenId = screenId,
              componentId = componentId,
              instanceId = instanceId,
              event = null,
            )
            handleOutcome(outcome, journey)
            persistJourney(journey)
          }
        }
      }
    }
  }

  suspend fun handleRuntimeDismiss(journeyId: String, reason: io.nuxie.sdk.flows.CloseReason) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val runner = flowRunners[journeyId] ?: return

    val method = when (reason) {
      io.nuxie.sdk.flows.CloseReason.UserDismissed -> "user"
      io.nuxie.sdk.flows.CloseReason.PurchaseCompleted -> "purchase_completed"
      io.nuxie.sdk.flows.CloseReason.Timeout -> "timeout"
      is io.nuxie.sdk.flows.CloseReason.Error -> "error"
    }

    val props = mutableMapOf<String, Any?>(
      "method" to method,
    )
    val screenId = journey.flowState.currentScreenId
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }

    val event = NuxieEvent(
      name = SystemEventNames.screenDismissed,
      distinctId = journey.distinctId,
      properties = props,
    )

    val outcome = runner.dispatchEventTrigger(event)
    handleOutcome(outcome, journey)

    if (!runner.hasPendingWork()) {
      completeJourney(journey, JourneyExitReason.COMPLETED)
    }
  }

  private suspend fun startJourneyInternal(campaign: Campaign, distinctId: String, originEventId: String?): Journey? {
    if (campaign.flowId.isBlank()) return null

    val journey = Journey(campaign = campaign, distinctId = distinctId).apply {
      status = JourneyStatus.ACTIVE
    }
    if (!originEventId.isNullOrBlank()) {
      journey.setContext("_origin_event_id", originEventId)
    }

    inMemoryJourneysById[journey.id] = journey
    persistJourney(journey)

    val flow = runCatching { flowService.fetchFlow(campaign.flowId) }.getOrNull()
    val entryScreenId = flow?.remoteFlow?.screens?.firstOrNull()?.id

    eventService.track(
      "\$journey_start",
      properties = mapOf(
        "session_id" to journey.id,
        "campaign_id" to campaign.id,
        "flow_id" to campaign.flowId,
        "entry_node_id" to entryScreenId,
      )
    )

    eventService.track(
      JourneyEvents.journeyStarted,
      properties = JourneyEvents.journeyStartedProperties(
        journey = journey,
        campaign = campaign,
        triggerEvent = null,
        entryScreenId = entryScreenId,
      )
    )

    val runner = ensureRunner(journey, campaign, flow)
    if (runner == null) {
      completeJourney(journey, JourneyExitReason.ERROR)
      return journey
    }

    return journey
  }

  private suspend fun resumeJourney(journey: Journey) {
    if (journey.status != JourneyStatus.PAUSED && journey.status != JourneyStatus.ACTIVE) return

    val campaign = getCampaign(journey.campaignId, journey.distinctId)
    if (campaign == null) {
      cancelJourney(journey)
      return
    }

    val runner = ensureRunner(journey, campaign, null)
    if (runner == null) {
      completeJourney(journey, JourneyExitReason.ERROR)
      return
    }

    journey.resume(nowEpochMillis())
    inMemoryJourneysById[journey.id] = journey

    val outcome = runner.resumePendingAction(ResumeReason.TIMER, event = null)
    handleOutcome(outcome, journey)

    eventService.track(
      JourneyEvents.journeyResumed,
      properties = JourneyEvents.journeyResumedProperties(
        journey = journey,
        screenId = journey.flowState.currentScreenId,
        resumeReason = "timer",
      )
    )
  }

  private suspend fun ensureRunner(
    journey: Journey,
    campaign: Campaign,
    preloadedFlow: Flow?,
  ): FlowJourneyRunner? {
    val existing = flowRunners[journey.id]
    if (existing != null) return existing

    val flow = preloadedFlow ?: runCatching { flowService.fetchFlow(campaign.flowId) }.getOrNull() ?: return null

    val delegate = runtimeDelegates[journey.id] ?: FlowRuntimeDelegateAdapter(journey.id, this, scope).also {
      runtimeDelegates[journey.id] = it
    }

    val runner = FlowJourneyRunner(
      journey = journey,
      campaign = campaign,
      flow = flow,
      host = delegate,
      eventService = eventService,
      identityService = identityService,
      segmentService = segmentService,
      featureService = featureService,
      profileService = profileService,
      irRuntime = irRuntime,
      scope = scope,
      nowEpochMillis = nowEpochMillis,
    )
    flowRunners[journey.id] = runner

    val shown = presentFlow(campaign.flowId, journey.id)
    if (shown) {
      eventService.track(
        JourneyEvents.flowShown,
        properties = JourneyEvents.flowShownProperties(flowId = campaign.flowId, journey = journey)
      )
    }

    return runner
  }

  private fun handleOutcome(outcome: FlowRunOutcome?, journey: Journey) {
    when (outcome) {
      is FlowRunOutcome.Paused -> {
        journey.pause(outcome.pending.resumeAtEpochMillis, nowEpochMillis())
        persistJourney(journey)
        val resumeAt = outcome.pending.resumeAtEpochMillis
        if (resumeAt != null) {
          scheduleResume(journey.id, resumeAt)
        }
        eventService.track(
          JourneyEvents.journeyPaused,
          properties = JourneyEvents.journeyPausedProperties(
            journey = journey,
            screenId = journey.flowState.currentScreenId,
            resumeAtEpochMillis = resumeAt,
          )
        )
      }
      is FlowRunOutcome.Exited -> {
        completeJourney(journey, outcome.reason)
      }
      null -> Unit
    }
  }

  private fun scheduleResume(journeyId: String, resumeAtEpochMillis: Long) {
    val key = "$journeyId:resume"
    activeTasks[key]?.cancel()
    activeTasks[key] = scope.launch(Dispatchers.Default) {
      val delayMs = maxOf(0L, resumeAtEpochMillis - nowEpochMillis())
      delay(delayMs)
      val journey = inMemoryJourneysById[journeyId] ?: return@launch
      resumeJourney(journey)
      activeTasks.remove(key)
    }
  }

  private fun cancelAllTasks() {
    for ((_, task) in activeTasks) {
      task.cancel()
    }
    activeTasks.clear()
  }

  private fun cancelTasksForJourney(journeyId: String) {
    val keys = activeTasks.keys.filter { it.startsWith("$journeyId:") }
    for (key in keys) {
      activeTasks[key]?.cancel()
      activeTasks.remove(key)
    }
  }

  private fun persistJourney(journey: Journey) {
    scope.launch {
      runCatching {
        journeyStore.saveJourney(journey)
        journeyStore.updateCache(journey)
      }.onFailure {
        NuxieLogger.warning("Failed to persist journey ${journey.id}: ${it.message}", it)
      }
    }
  }

  private fun completeJourney(journey: Journey, reason: JourneyExitReason) {
    journey.complete(reason, nowEpochMillis())

    val durationSeconds = ((journey.completedAtEpochMillis ?: nowEpochMillis()) - journey.startedAtEpochMillis).toDouble() / 1000.0

    eventService.track(
      "\$journey_completed",
      properties = mapOf(
        "session_id" to journey.id,
        "exit_reason" to exitReasonString(reason),
        "goal_met" to (journey.convertedAtEpochMillis != null),
        "goal_met_at" to journey.convertedAtEpochMillis?.let { it / 1000.0 },
        "duration_seconds" to durationSeconds,
      )
    )

    eventService.track(
      JourneyEvents.journeyExited,
      properties = JourneyEvents.journeyExitedProperties(
        journey = journey,
        reason = reason,
        screenId = journey.flowState.currentScreenId,
      )
    )

    val originEventId = (journey.getContext("_origin_event_id") as? JsonPrimitive)?.contentOrNull
      ?: fromJsonElement(journey.getContext("_origin_event_id") ?: JsonNull) as? String
    if (!originEventId.isNullOrBlank()) {
      val update = TriggerUpdate.Journey(
        JourneyUpdate(
          journeyId = journey.id,
          campaignId = journey.campaignId,
          flowId = journey.flowId,
          exitReason = reason,
          goalMet = journey.convertedAtEpochMillis != null,
          goalMetAtEpochMillis = journey.convertedAtEpochMillis,
          durationSeconds = durationSeconds,
          flowExitReason = null,
        )
      )
      scope.launch {
        triggerBroker.emit(originEventId, update)
      }
    }

    cancelTasksForJourney(journey.id)
    flowRunners.remove(journey.id)
    runtimeDelegates.remove(journey.id)
    inMemoryJourneysById.remove(journey.id)

    scope.launch {
      runCatching {
        journeyStore.deleteJourney(journey.id)
        journeyStore.recordCompletion(JourneyCompletionRecord(journey))
      }
    }
  }

  private fun cancelJourney(journey: Journey) {
    journey.cancel(nowEpochMillis())
    completeJourney(journey, JourneyExitReason.CANCELLED)
  }

  private suspend fun evaluateGoalIfNeeded(journey: Journey, campaign: Campaign) {
    if (journey.convertedAtEpochMillis != null) return
    if (journey.goalSnapshot == null) return

    val result = goalEvaluator.isGoalMet(journey, campaign)
    if (result.met && result.atEpochMillis != null) {
      val metAt = result.atEpochMillis
      if (metAt == null) return
      journey.convertedAtEpochMillis = metAt
      journey.updatedAtEpochMillis = nowEpochMillis()
      persistJourney(journey)

      eventService.track(
        JourneyEvents.journeyGoalMet,
        properties = mapOf(
          "journey_id" to journey.id,
          "campaign_id" to journey.campaignId,
          "goal_kind" to journey.goalSnapshot?.kind?.name?.lowercase(),
          "met_at" to (metAt / 1000.0),
          "window_seconds" to journey.conversionWindowSeconds,
        )
      )
    }
  }

  private suspend fun exitDecision(journey: Journey, campaign: Campaign): JourneyExitReason? {
    if (journey.hasExpired(nowEpochMillis())) return JourneyExitReason.EXPIRED

    val mode = journey.exitPolicySnapshot?.mode ?: ExitPolicy.Mode.NEVER
    if ((mode == ExitPolicy.Mode.ON_GOAL || mode == ExitPolicy.Mode.ON_GOAL_OR_STOP) && journey.convertedAtEpochMillis != null) {
      return JourneyExitReason.GOAL_MET
    }

    if (mode == ExitPolicy.Mode.ON_STOP_MATCHING || mode == ExitPolicy.Mode.ON_GOAL_OR_STOP) {
      val trigger = campaign.trigger
      if (trigger is CampaignTrigger.Segment) {
        val stillMatches = evalConditionIR(trigger.config.condition, event = null)
        if (!stillMatches) {
          return JourneyExitReason.TRIGGER_UNMATCHED
        }
      }
    }
    return null
  }

  private suspend fun shouldTriggerFromEvent(campaign: Campaign, event: NuxieEvent): Boolean {
    return when (val trigger = campaign.trigger) {
      is CampaignTrigger.Event -> {
        if (trigger.config.eventName != event.name) return false
        val condition = trigger.config.condition
        if (condition != null) evalConditionIR(condition, event) else true
      }
      is CampaignTrigger.Segment -> false
    }
  }

  private suspend fun evalConditionIR(envelope: io.nuxie.sdk.ir.IREnvelope?, event: NuxieEvent?): Boolean {
    if (envelope == null) return true

    val userAdapter = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    }
    val featuresAdapter = object : IRFeatureQueries {
      override suspend fun has(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.allowed == true
      }

      override suspend fun isUnlimited(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.unlimited == true
      }

      override suspend fun getBalance(featureId: String): Int? {
        return featureService.getCached(featureId, null)?.balance
      }
    }

    return irRuntime.eval(
      envelope = envelope,
      cfg = IRRuntime.Config(
        event = event,
        user = userAdapter,
        events = eventService,
        segments = segmentService as IRSegmentQueries,
        features = featuresAdapter,
      )
    )
  }

  private suspend fun suppressionReason(campaign: Campaign, distinctId: String): SuppressReason? {
    val live = inMemoryJourneysById.values.any {
      it.distinctId == distinctId && it.campaignId == campaign.id && it.status.isLive
    }
    if (live) return SuppressReason.AlreadyActive

    return when (val reentry = campaign.reentry) {
      is CampaignReentry.EveryTime -> null
      is CampaignReentry.OneTime -> {
        val completed = journeyStore.hasCompletedCampaign(distinctId, campaign.id)
        if (completed) SuppressReason.ReentryLimited else null
      }
      is CampaignReentry.OncePerWindow -> {
        val last = journeyStore.lastCompletionEpochMillis(distinctId, campaign.id) ?: return null
        val intervalSeconds = when (reentry.window.unit) {
          io.nuxie.sdk.campaigns.WindowUnit.MINUTE -> reentry.window.amount.toLong() * 60L
          io.nuxie.sdk.campaigns.WindowUnit.HOUR -> reentry.window.amount.toLong() * 3600L
          io.nuxie.sdk.campaigns.WindowUnit.DAY -> reentry.window.amount.toLong() * 86400L
          io.nuxie.sdk.campaigns.WindowUnit.WEEK -> reentry.window.amount.toLong() * 604800L
        }
        val allowed = (nowEpochMillis() - last) >= (intervalSeconds * 1000L)
        if (allowed) null else SuppressReason.ReentryLimited
      }
    }
  }

  private suspend fun getCampaign(id: String, distinctId: String): Campaign? {
    val profile = profileService.getCachedProfile(distinctId) ?: return null
    return profile.campaigns.firstOrNull { it.id == id }
  }

  private suspend fun getAllCampaigns(distinctId: String): List<Campaign>? {
    val profile = profileService.getCachedProfile(distinctId) ?: return null
    return profile.campaigns
  }

  private fun registerForSegmentChanges() {
    segmentMonitoringJob?.cancel()
    segmentMonitoringJob = scope.launch {
      segmentService.segmentChanges.collectLatest { result ->
        val currentDistinctId = identityService.getDistinctId()
        if (result.distinctId != currentDistinctId) return@collectLatest
        val currentSegments = (result.entered + result.remained).map { it.id }.toSet()
        handleSegmentChange(result.distinctId, currentSegments)
      }
    }
  }

  private fun parseRuntimeTrigger(type: String, payload: JsonObject): InteractionTrigger? {
    val triggerPayload = payload["trigger"] as? JsonObject
    if (triggerPayload != null) {
      return parseTriggerType(triggerPayload.string("type") ?: triggerPayload.string("trigger") ?: return null, triggerPayload)
    }

    val triggerType = payload.string("trigger")
    if (!triggerType.isNullOrBlank()) {
      return parseTriggerType(triggerType, payload)
    }

    val fallback = if (type.startsWith("action/")) type.removePrefix("action/") else type
    return parseTriggerType(fallback, payload)
  }

  private fun parseTriggerType(raw: String, payload: JsonObject): InteractionTrigger? {
    val normalized = raw
      .replace("action/", "")
      .replace("-", "_")
      .lowercase()

    return when (normalized) {
      "long_press", "longpress" -> InteractionTrigger.LongPress(minMs = payload.int("minMs") ?: payload.int("min_ms"))
      "hover" -> InteractionTrigger.Hover
      "press" -> InteractionTrigger.Press
      "drag" -> {
        val direction = when (payload.string("direction")?.lowercase()) {
          "left" -> InteractionTrigger.DragDirection.LEFT
          "right" -> InteractionTrigger.DragDirection.RIGHT
          "up" -> InteractionTrigger.DragDirection.UP
          "down" -> InteractionTrigger.DragDirection.DOWN
          else -> null
        }
        InteractionTrigger.Drag(
          direction = direction,
          threshold = payload.double("threshold"),
        )
      }
      "manual" -> InteractionTrigger.Manual(label = payload.string("label"))
      else -> null
    }
  }

  private fun parsePathRef(payload: JsonObject): VmPathRef? {
    val direct = parsePathRefObject(payload)
    if (direct != null) return direct
    val nested = payload["path"] as? JsonObject
    if (nested != null) return parsePathRefObject(nested)
    return null
  }

  private fun parsePathRefObject(payload: JsonObject): VmPathRef? {
    val pathIds = payload["pathIds"] as? JsonArray ?: return null
    val ids = pathIds.mapNotNull { it.jsonPrimitive.intOrNull }
    if (ids.isEmpty()) return null
    return VmPathRef(
      pathIds = ids,
      isRelative = payload.bool("isRelative"),
      nameBased = payload.bool("nameBased"),
    )
  }

  private fun exitReasonString(reason: JourneyExitReason): String {
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

private class FlowRuntimeDelegateAdapter(
  private val journeyId: String,
  private val journeyService: JourneyService,
  private val scope: CoroutineScope,
) : FlowRuntimeDelegate, FlowJourneyHost {
  @Volatile private var flowView: FlowView? = null

  fun attachFlowView(view: FlowView) {
    this.flowView = view
  }

  override fun onRuntimeMessage(type: String, payload: JsonObject, id: String?) {
    scope.launch {
      journeyService.handleRuntimeMessage(journeyId, type, payload, id)
    }
  }

  override fun onDismissRequested(reason: io.nuxie.sdk.flows.CloseReason) {
    scope.launch {
      journeyService.handleRuntimeDismiss(journeyId, reason)
    }
  }

  override suspend fun sendRuntimeMessage(type: String, payload: JsonObject, replyTo: String?) {
    flowView?.sendRuntimeMessage(type = type, payload = payload, replyTo = replyTo)
  }

  override suspend fun showScreen(screenId: String, transition: JsonElement?) {
    // Runtime navigation is sent through sendRuntimeMessage("runtime/navigate", ...).
  }

  override suspend fun performPurchase(productId: String, placementIndex: Any?) {
    flowView?.performPurchase(productId)
  }

  override suspend fun performRestore() {
    flowView?.performRestore()
  }

  override suspend fun performOpenLink(url: String, target: String?) {
    flowView?.performOpenLink(url, target)
  }

  override suspend fun performDismiss() {
    flowView?.performDismiss()
  }

  override suspend fun performBack(steps: Int?, transition: JsonElement?) {
    // Back is currently represented through runtime navigation stack updates.
  }

  override suspend fun callDelegate(message: String, payload: Any?) {
    // Delegate callbacks will be wired in SDK surface in a follow-up patch.
  }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
}
private fun JsonObject.double(key: String): Double? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}
private fun JsonObject.bool(key: String): Boolean? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.booleanOrNull
}

private fun JsonElement.toAny(): Any? {
  return when (this) {
    JsonNull -> null
    is JsonPrimitive -> {
      if (isString) contentOrNull
      else booleanOrNull ?: intOrNull ?: doubleOrNull ?: contentOrNull
    }
    is JsonArray -> map { it.toAny() }
    is JsonObject -> entries.associate { it.key to it.value.toAny() }
  }
}

private fun JsonObject.toMapAny(): Map<String, Any?> = this.entries.associate { it.key to it.value.toAny() }
