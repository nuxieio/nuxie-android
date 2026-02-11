package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.flows.Flow
import io.nuxie.sdk.flows.FlowViewModelRuntime
import io.nuxie.sdk.flows.Interaction
import io.nuxie.sdk.flows.InteractionAction
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.VmPathRef
import io.nuxie.sdk.flows.ViewModelInstance
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.IREventQueries
import io.nuxie.sdk.ir.IRFeatureQueries
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import io.nuxie.sdk.network.models.ExperimentAssignment
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.triggers.JourneyExitReason
import io.nuxie.sdk.util.fromJsonElement
import io.nuxie.sdk.util.toJsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.random.Random

enum class ResumeReason {
  START,
  TIMER,
  EVENT,
  SEGMENT_CHANGE,
}

data class RuntimeTriggerContext(
  val screenId: String?,
  val componentId: String?,
  val interactionId: String?,
  val instanceId: String?,
)

sealed class FlowRunOutcome {
  data class Paused(val pending: FlowPendingAction) : FlowRunOutcome()
  data class Exited(val reason: JourneyExitReason) : FlowRunOutcome()
}

interface FlowJourneyHost {
  suspend fun sendRuntimeMessage(type: String, payload: JsonObject = JsonObject(emptyMap()), replyTo: String? = null)
  suspend fun showScreen(screenId: String, transition: JsonElement? = null)
  suspend fun performPurchase(productId: String, placementIndex: Any?)
  suspend fun performRestore()
  suspend fun performOpenLink(url: String, target: String?)
  suspend fun performDismiss()
  suspend fun performBack(steps: Int?, transition: JsonElement?)
  suspend fun callDelegate(message: String, payload: Any?)
}

class FlowJourneyRunner(
  private val journey: Journey,
  private val campaign: Campaign,
  private val flow: Flow,
  private var host: FlowJourneyHost? = null,
  private val eventService: EventService,
  private val identityService: IdentityService,
  private val segmentService: SegmentService,
  private val featureService: FeatureService,
  private val profileService: ProfileService,
  private val irRuntime: IRRuntime,
  private val scope: CoroutineScope,
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
  private sealed class ActionResult {
    data object Continue : ActionResult()
    data object StopSequence : ActionResult()
    data class Pause(val pending: FlowPendingAction) : ActionResult()
    data class Exit(val reason: JourneyExitReason) : ActionResult()
  }

  private data class ActionRequest(
    val actions: List<InteractionAction>,
    val context: RuntimeTriggerContext,
  )

  private data class ResumeContext(
    val pending: FlowPendingAction,
    val reason: ResumeReason,
    val event: NuxieEvent?,
  )

  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
  private val viewModels = FlowViewModelRuntime(flow.remoteFlow)
  private val interactionsById: Map<String, List<Interaction>> = flow.remoteFlow.interactions

  private val actionQueue: MutableList<ActionRequest> = mutableListOf()
  private var activeRequest: ActionRequest? = null
  private var activeIndex: Int = 0
  private var isProcessing: Boolean = false
  private var isPaused: Boolean = false
  private val debounceJobs: MutableMap<String, Job> = mutableMapOf()
  private val triggerResetJobs: MutableMap<String, Job> = mutableMapOf()
  private var didWarnConverters: Boolean = false

  val isRuntimeReady: Boolean
    get() = runtimeReady
  private var runtimeReady: Boolean = false

  init {
    val snapshot = journey.flowState.viewModelSnapshot
    if (snapshot != null) {
      viewModels.hydrate(snapshot)
    } else {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
    }
  }

  fun attach(host: FlowJourneyHost) {
    this.host = host
  }

  suspend fun handleRuntimeReady(): FlowRunOutcome? {
    runtimeReady = true
    sendViewModelInit()

    val current = journey.flowState.currentScreenId
    if (!current.isNullOrBlank()) {
      sendShowScreen(current)
      return null
    }

    if (journey.flowState.pendingAction == null) {
      val outcome = runEntryActionsIfNeeded()
      if (outcome != null) return outcome

      if (journey.flowState.currentScreenId == null) {
        val fallback = flow.remoteFlow.screens.firstOrNull()?.id
        if (!fallback.isNullOrBlank()) {
          navigate(to = fallback, transition = null)
        }
      }
    }

    return null
  }

  suspend fun handleScreenChanged(screenId: String): FlowRunOutcome? {
    journey.flowState.currentScreenId = screenId
    val event = makeSystemEvent(
      name = SystemEventNames.screenShown,
      properties = mapOf("screen_id" to screenId),
    )
    return dispatchEventTrigger(event)
  }

  suspend fun handleDidSet(
    path: VmPathRef,
    value: JsonElement,
    source: String?,
    screenId: String?,
    instanceId: String?,
  ): FlowRunOutcome? {
    val resolvedScreen = screenId ?: journey.flowState.currentScreenId
    viewModels.setValue(path, value, resolvedScreen, instanceId)
    journey.flowState.viewModelSnapshot = viewModels.getSnapshot()

    val outcome = dispatchDidSetTrigger(
      path = path,
      value = value,
      screenId = resolvedScreen,
      instanceId = instanceId,
    )
    scheduleTriggerReset(path, resolvedScreen, instanceId)
    return outcome
  }

  fun resolveRuntimeValue(value: JsonElement, screenId: String?, instanceId: String?): Any? {
    return resolveValueRefs(
      value = value,
      context = RuntimeTriggerContext(
        screenId = screenId,
        componentId = null,
        interactionId = null,
        instanceId = instanceId,
      )
    )
  }

  suspend fun handleRuntimeBack(steps: Int?, transition: JsonElement?) {
    handleBack(InteractionAction.Back(steps = steps, transition = transition))
  }

  suspend fun handleRuntimeOpenLink(url: JsonElement, target: String?, screenId: String?, instanceId: String?) {
    val resolved = resolveValueRefs(
      value = url,
      context = RuntimeTriggerContext(
        screenId = screenId,
        componentId = null,
        interactionId = null,
        instanceId = instanceId,
      )
    )
    val urlString = resolved as? String ?: return
    if (urlString.isBlank()) return
    host?.performOpenLink(urlString, target)
  }

  suspend fun dispatchEventTrigger(event: NuxieEvent): FlowRunOutcome? {
    return dispatchTrigger(
      trigger = InteractionTrigger.Event(eventName = event.name, filter = null),
      screenId = journey.flowState.currentScreenId,
      componentId = null,
      instanceId = null,
      event = event,
    )
  }

  suspend fun dispatchTrigger(
    trigger: InteractionTrigger,
    screenId: String?,
    componentId: String?,
    instanceId: String?,
    event: NuxieEvent?,
  ): FlowRunOutcome? {
    if (isPaused) return null

    if (journey.flowState.currentScreenId == null && !screenId.isNullOrBlank()) {
      journey.flowState.currentScreenId = screenId
    }

    val candidates = mutableListOf<Interaction>()
    if (!componentId.isNullOrBlank()) {
      candidates += interactionsById[componentId].orEmpty()
    }
    if (!screenId.isNullOrBlank()) {
      candidates += interactionsById[screenId].orEmpty()
    }
    if (candidates.isEmpty()) return null

    for (interaction in candidates) {
      if (interaction.enabled == false) continue
      if (!matchesTrigger(interaction.trigger, trigger)) continue

      if (interaction.trigger is InteractionTrigger.Event && trigger is InteractionTrigger.Event) {
        if (interaction.trigger.eventName != trigger.eventName) continue
        val filter = interaction.trigger.filter
        if (filter != null) {
          val ok = evalConditionIR(filter, event = event)
          if (!ok) continue
        }
      }

      if (interaction.trigger is InteractionTrigger.DidSet) {
        continue
      }

      enqueueActions(
        interaction.actions,
        RuntimeTriggerContext(
          screenId = screenId,
          componentId = componentId,
          interactionId = interaction.id,
          instanceId = instanceId,
        )
      )
    }

    return processQueue(resumeContext = null)
  }

  suspend fun resumePendingAction(reason: ResumeReason, event: NuxieEvent?): FlowRunOutcome? {
    val pending = journey.flowState.pendingAction ?: return null

    isPaused = false
    journey.flowState.pendingAction = null

    val actions = resolveActions(
      interactionId = pending.interactionId,
      screenId = pending.screenId,
      componentId = pending.componentId,
    ) ?: return null

    val context = RuntimeTriggerContext(
      screenId = pending.screenId,
      componentId = pending.componentId,
      interactionId = pending.interactionId,
      instanceId = null,
    )

    activeRequest = ActionRequest(actions = actions, context = context)
    activeIndex = if (pending.kind == FlowPendingActionKind.DELAY) pending.actionIndex + 1 else pending.actionIndex

    return processQueue(ResumeContext(pending = pending, reason = reason, event = event))
  }

  fun clearDebounces() {
    for ((_, job) in debounceJobs) {
      job.cancel()
    }
    debounceJobs.clear()
  }

  fun hasPendingWork(): Boolean {
    if (journey.flowState.pendingAction != null) return true
    if (activeRequest != null) return true
    if (actionQueue.isNotEmpty()) return true
    return false
  }

  private fun makeSystemEvent(name: String, properties: Map<String, Any?>): NuxieEvent {
    return NuxieEvent(
      name = name,
      distinctId = journey.distinctId,
      properties = properties,
    )
  }

  private fun entryScreenId(interactions: List<Interaction>): String? {
    for (interaction in interactions) {
      for (action in interaction.actions) {
        if (action is InteractionAction.Navigate && action.screenId.isNotBlank()) {
          return action.screenId
        }
      }
    }
    return null
  }

  private suspend fun runEntryActionsIfNeeded(): FlowRunOutcome? {
    val entryInteractions = interactionsById["start"].orEmpty()
    if (entryInteractions.isEmpty()) return null

    val entryScreenId = entryScreenId(entryInteractions)
    val event = makeSystemEvent(
      name = SystemEventNames.flowEntered,
      properties = if (!entryScreenId.isNullOrBlank()) mapOf("entry_screen_id" to entryScreenId) else emptyMap(),
    )

    for (interaction in entryInteractions) {
      if (interaction.enabled == false) continue
      val trigger = interaction.trigger as? InteractionTrigger.Event ?: continue
      if (trigger.eventName != SystemEventNames.flowEntered) continue
      val filter = trigger.filter
      if (filter != null && !evalConditionIR(filter, event)) continue

      enqueueActions(
        interaction.actions,
        RuntimeTriggerContext(
          screenId = journey.flowState.currentScreenId,
          componentId = null,
          interactionId = interaction.id,
          instanceId = null,
        )
      )
    }

    return processQueue(resumeContext = null)
  }

  private fun enqueueActions(actions: List<InteractionAction>, context: RuntimeTriggerContext) {
    if (actions.isEmpty()) return
    actionQueue += ActionRequest(actions = actions, context = context)
  }

  private suspend fun processQueue(resumeContext: ResumeContext?): FlowRunOutcome? {
    if (isProcessing) return null
    isProcessing = true
    try {
      var localResume = resumeContext

      while (!isPaused) {
        if (activeRequest == null) {
          if (actionQueue.isEmpty()) return null
          activeRequest = actionQueue.removeAt(0)
          activeIndex = 0
        }

        val request = activeRequest ?: return null

        while (activeIndex < request.actions.size) {
          val action = request.actions[activeIndex]
          val result = executeAction(action, request.context, activeIndex, localResume)
          localResume = null

          when (result) {
            is ActionResult.Continue -> activeIndex += 1
            is ActionResult.StopSequence -> {
              activeRequest = null
              activeIndex = 0
              break
            }
            is ActionResult.Pause -> {
              isPaused = true
              journey.flowState.pendingAction = result.pending
              return FlowRunOutcome.Paused(result.pending)
            }
            is ActionResult.Exit -> {
              return FlowRunOutcome.Exited(result.reason)
            }
          }
        }

        if (activeIndex >= request.actions.size) {
          activeRequest = null
          activeIndex = 0
        }
      }

      return null
    } finally {
      isProcessing = false
    }
  }

  private suspend fun executeAction(
    action: InteractionAction,
    context: RuntimeTriggerContext,
    index: Int,
    resumeContext: ResumeContext?,
  ): ActionResult {
    return try {
      when (action) {
        is InteractionAction.Navigate -> {
          navigateToAction(action, context)
          trackAction(action, context, error = null)
          ActionResult.StopSequence
        }
        is InteractionAction.Back -> {
          handleBack(action)
          trackAction(action, context, error = null)
          ActionResult.StopSequence
        }
        is InteractionAction.Delay -> {
          val result = handleDelay(action, context, index, resumeContext)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.TimeWindow -> {
          val result = handleTimeWindow(action, context, index, resumeContext)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.WaitUntil -> {
          val result = handleWaitUntil(action, context, index, resumeContext)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.Condition -> {
          val result = handleCondition(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.Experiment -> {
          val result = handleExperiment(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.SendEvent -> {
          handleSendEvent(action, context)
          trackAction(action, context, error = null)
          ActionResult.Continue
        }
        is InteractionAction.UpdateCustomer -> {
          handleUpdateCustomer(action, context)
          trackAction(action, context, error = null)
          ActionResult.Continue
        }
        is InteractionAction.Purchase -> {
          val result = handlePurchase(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.Restore -> {
          val result = handleRestore(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.OpenLink -> {
          val result = handleOpenLink(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.Dismiss -> {
          val result = handleDismiss(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.CallDelegate -> {
          handleCallDelegate(action, context)
          trackAction(action, context, error = null)
          ActionResult.Continue
        }
        is InteractionAction.Remote -> {
          val result = handleRemote(action, context, index)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.SetViewModel -> {
          val result = handleSetViewModel(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.FireTrigger -> {
          val result = handleFireTrigger(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListInsert -> {
          val result = handleListInsert(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListRemove -> {
          val result = handleListRemove(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListSwap -> {
          val result = handleListSwap(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListMove -> {
          val result = handleListMove(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListSet -> {
          val result = handleListSet(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.ListClear -> {
          val result = handleListClear(action, context)
          trackAction(action, context, error = null)
          result
        }
        is InteractionAction.Exit -> {
          trackAction(action, context, error = null)
          ActionResult.Exit(mapExitReason(action.reason))
        }
        is InteractionAction.Unknown -> ActionResult.Continue
      }
    } catch (t: Throwable) {
      trackAction(action, context, error = t.message ?: "unknown_error")
      ActionResult.Exit(JourneyExitReason.ERROR)
    }
  }

  private suspend fun navigateToAction(action: InteractionAction.Navigate, context: RuntimeTriggerContext) {
    if (action.screenId.isBlank()) return
    navigate(to = action.screenId, transition = action.transition)
  }

  private suspend fun navigate(to: String, transition: JsonElement?) {
    val current = journey.flowState.currentScreenId
    if (!current.isNullOrBlank() && current != to) {
      val event = makeSystemEvent(
        name = SystemEventNames.screenDismissed,
        properties = mapOf(
          "screen_id" to current,
          "method" to "navigate",
        )
      )
      dispatchEventTrigger(event)
      journey.flowState.navigationStack.add(current)
    }
    sendShowScreen(to, transition)
  }

  private suspend fun handleBack(action: InteractionAction.Back) {
    val steps = max(1, action.steps ?: 1)
    if (journey.flowState.navigationStack.isEmpty()) return

    val stack = journey.flowState.navigationStack
    val targetIndex = max(0, stack.size - steps)
    val target = stack[targetIndex]
    val remaining = stack.take(targetIndex).toMutableList()
    journey.flowState.navigationStack = remaining
    sendShowScreen(target, action.transition)
    host?.performBack(steps, action.transition)
  }

  private fun handleDelay(
    action: InteractionAction.Delay,
    context: RuntimeTriggerContext,
    index: Int,
    resumeContext: ResumeContext?,
  ): ActionResult {
    if (resumeContext?.pending?.kind == FlowPendingActionKind.DELAY) {
      return ActionResult.Continue
    }
    val durationMs = max(0, action.durationMs)
    if (durationMs <= 0) return ActionResult.Continue
    val resumeAt = nowEpochMillis() + durationMs.toLong()
    return ActionResult.Pause(
      makePendingAction(
        kind = FlowPendingActionKind.DELAY,
        context = context,
        index = index,
        resumeAtEpochMillis = resumeAt,
        condition = null,
        maxTimeMs = null,
      )
    )
  }

  private fun handleTimeWindow(
    action: InteractionAction.TimeWindow,
    context: RuntimeTriggerContext,
    index: Int,
    resumeContext: ResumeContext?,
  ): ActionResult {
    val nowMs = nowEpochMillis()
    val zone = runCatching { ZoneId.of(action.timezone) }.getOrNull() ?: ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)

    val start = parseTime(action.startTime) ?: return ActionResult.Continue
    val end = parseTime(action.endTime) ?: return ActionResult.Continue

    val weekday = now.dayOfWeek.value.valueToCalendarWeekday()
    val days = action.daysOfWeek
    if (!days.isNullOrEmpty() && !days.contains(weekday)) {
      val nextValid = calculateNextValidDay(now, days, zone)
      return ActionResult.Pause(
        makePendingAction(
          kind = FlowPendingActionKind.TIME_WINDOW,
          context = context,
          index = index,
          resumeAtEpochMillis = nextValid.toInstant().toEpochMilli(),
          condition = null,
          maxTimeMs = null,
        )
      )
    }

    val curMin = now.hour * 60 + now.minute
    val startMin = start.first * 60 + start.second
    val endMin = end.first * 60 + end.second

    if (startMin == endMin) return ActionResult.Continue

    val inWindow = if (startMin <= endMin) {
      curMin in startMin until endMin
    } else {
      curMin >= startMin || curMin < endMin
    }
    if (inWindow) return ActionResult.Continue

    val nextOpen = calculateNextWindowOpen(now, action.startTime, zone, action.daysOfWeek)
    return ActionResult.Pause(
      makePendingAction(
        kind = FlowPendingActionKind.TIME_WINDOW,
        context = context,
        index = index,
        resumeAtEpochMillis = nextOpen.toInstant().toEpochMilli(),
        condition = null,
        maxTimeMs = null,
      )
    )
  }

  private suspend fun handleWaitUntil(
    action: InteractionAction.WaitUntil,
    context: RuntimeTriggerContext,
    index: Int,
    resumeContext: ResumeContext?,
  ): ActionResult {
    val nowMs = nowEpochMillis()
    val condition = action.condition ?: resumeContext?.pending?.condition
    val event = resumeContext?.event

    val ok = evalConditionIR(condition, event)
    if (ok) return ActionResult.Continue

    val maxTimeMs = action.maxTimeMs ?: resumeContext?.pending?.maxTimeMs
    val startedAt = resumeContext?.pending?.startedAtEpochMillis ?: nowMs

    if (maxTimeMs != null) {
      val deadline = startedAt + maxTimeMs.toLong()
      if (nowMs >= deadline) return ActionResult.Continue
      return ActionResult.Pause(
        makePendingAction(
          kind = FlowPendingActionKind.WAIT_UNTIL,
          context = context,
          index = index,
          resumeAtEpochMillis = deadline,
          condition = condition,
          maxTimeMs = maxTimeMs,
          startedAtEpochMillis = startedAt,
        )
      )
    }

    return ActionResult.Pause(
      makePendingAction(
        kind = FlowPendingActionKind.WAIT_UNTIL,
        context = context,
        index = index,
        resumeAtEpochMillis = null,
        condition = condition,
        maxTimeMs = null,
        startedAtEpochMillis = startedAt,
      )
    )
  }

  private suspend fun handleCondition(
    action: InteractionAction.Condition,
    context: RuntimeTriggerContext,
  ): ActionResult {
    for (branch in action.branches) {
      val ok = evalConditionIR(branch.condition, event = null)
      if (ok) {
        return runNestedActions(branch.actions, context)
      }
    }
    val defaults = action.defaultActions
    if (defaults != null) {
      return runNestedActions(defaults, context)
    }
    return ActionResult.Continue
  }

  private suspend fun handleExperiment(
    action: InteractionAction.Experiment,
    context: RuntimeTriggerContext,
  ): ActionResult {
    if (action.variants.isEmpty()) return ActionResult.Continue

    val experimentKey = action.experimentId
    val assignment = getServerAssignment(experimentKey)

    val frozenVariantKey = getFrozenExperimentVariantKey(experimentKey)
    val frozenVariant = if (frozenVariantKey != null) action.variants.firstOrNull { it.id == frozenVariantKey } else null

    val resolution = if (frozenVariant != null) {
      Pair(frozenVariant, assignment?.variantKey == frozenVariantKey)
    } else {
      resolveExperimentVariant(action, assignment)
    }

    val variant = resolution.first ?: return ActionResult.Continue
    val matchedAssignment = resolution.second

    val status = assignment?.status
    if (status == "running" && matchedAssignment && (frozenVariantKey == null || frozenVariant == null)) {
      freezeExperimentVariantKey(experimentKey, variant.id)
    }

    journey.setContext("_experiment_key", experimentKey)
    journey.setContext("_variant_key", variant.id)

    if (status == "running" && !hasEmittedExperimentExposure(experimentKey)) {
      val assignmentSource = if (frozenVariant != null) "journey_context" else "profile"
      if (matchedAssignment) {
        eventService.track(
          JourneyEvents.experimentExposure,
          properties = JourneyEvents.experimentExposureProperties(
            journey = journey,
            experimentKey = experimentKey,
            variantKey = variant.id,
            flowId = journey.flowId,
            isHoldout = assignment?.isHoldout == true,
            assignmentSource = assignmentSource,
          )
        )
        markExperimentExposureEmitted(experimentKey)
      } else {
        eventService.track(
          "\$experiment_exposure_error",
          properties = mapOf(
            "experiment_key" to experimentKey,
            "variant_key" to assignment?.variantKey,
            "reason" to "variant_not_found",
          )
        )
      }
    }

    return runNestedActions(variant.actions, context)
  }

  private suspend fun handleSendEvent(action: InteractionAction.SendEvent, context: RuntimeTriggerContext) {
    val props = mutableMapOf<String, Any?>()
    for ((k, v) in action.properties.orEmpty()) {
      props[k] = resolveValueRefs(v, context)
    }
    props["journeyId"] = journey.id
    props["campaignId"] = journey.campaignId
    val resolvedScreen = context.screenId ?: journey.flowState.currentScreenId
    if (!resolvedScreen.isNullOrBlank()) {
      props["screenId"] = resolvedScreen
    }

    eventService.track(action.eventName, properties = props)
    eventService.track(
      JourneyEvents.eventSent,
      properties = JourneyEvents.eventSentProperties(
        journey = journey,
        screenId = resolvedScreen,
        eventName = action.eventName,
        eventProperties = props,
      )
    )
  }

  private fun handleUpdateCustomer(action: InteractionAction.UpdateCustomer, context: RuntimeTriggerContext) {
    val attributes = mutableMapOf<String, Any?>()
    for ((k, v) in action.attributes) {
      attributes[k] = resolveValueRefs(v, context)
    }
    identityService.setUserProperties(attributes)

    eventService.track(
      JourneyEvents.customerUpdated,
      properties = JourneyEvents.customerUpdatedProperties(
        journey = journey,
        screenId = context.screenId ?: journey.flowState.currentScreenId,
        attributesUpdated = attributes.keys.toList(),
      )
    )
  }

  private fun handleCallDelegate(action: InteractionAction.CallDelegate, context: RuntimeTriggerContext) {
    val payload = action.payload?.let { resolveValueRefs(it, context) }
    scope.launch(Dispatchers.Default) {
      host?.callDelegate(action.message, payload)
    }

    eventService.track(
      JourneyEvents.delegateCalled,
      properties = JourneyEvents.delegateCalledProperties(
        journey = journey,
        screenId = context.screenId ?: journey.flowState.currentScreenId,
        message = action.message,
        payload = payload,
      )
    )
  }

  private suspend fun handlePurchase(action: InteractionAction.Purchase, context: RuntimeTriggerContext): ActionResult {
    val resolvedProductId = resolveValueRefs(action.productId, context) as? String ?: return ActionResult.Continue
    if (resolvedProductId.isBlank()) return ActionResult.Continue

    val placementIndex = resolveValueRefs(action.placementIndex, context)
    host?.performPurchase(resolvedProductId, placementIndex)
    return ActionResult.Continue
  }

  private suspend fun handleRestore(action: InteractionAction.Restore, context: RuntimeTriggerContext): ActionResult {
    host?.performRestore()
    return ActionResult.Continue
  }

  private suspend fun handleOpenLink(action: InteractionAction.OpenLink, context: RuntimeTriggerContext): ActionResult {
    val resolvedUrl = resolveValueRefs(action.url, context) as? String ?: return ActionResult.Continue
    if (resolvedUrl.isBlank()) return ActionResult.Continue
    host?.performOpenLink(resolvedUrl, action.target)
    return ActionResult.Continue
  }

  private suspend fun handleDismiss(action: InteractionAction.Dismiss, context: RuntimeTriggerContext): ActionResult {
    host?.performDismiss()
    return ActionResult.Continue
  }

  private suspend fun handleRemote(
    action: InteractionAction.Remote,
    context: RuntimeTriggerContext,
    index: Int,
  ): ActionResult {
    val nodeId = context.interactionId ?: context.screenId ?: journey.flowState.currentScreenId ?: "unknown"
    val screenId = context.screenId ?: journey.flowState.currentScreenId

    val payload = mapOf(
      "session_id" to journey.id,
      "node_id" to nodeId,
      "screen_id" to screenId,
      "node_data" to mapOf(
        "type" to "remote",
        "data" to mapOf(
          "action" to action.action,
          "payload" to fromJsonElement(action.payload),
          "async" to (action.async == true),
        )
      ),
      "context" to journey.context.mapValues { (_, v) -> fromJsonElement(v) }
    )

    if (action.async == true) {
      eventService.track("\$journey_node_executed", properties = payload)
      return ActionResult.Continue
    }

    return try {
      val response = eventService.trackWithResponse(
        event = "\$journey_node_executed",
        properties = payload,
      )

      val execution = response.execution
      if (execution != null) {
        if (execution.success) {
          val updates = execution.contextUpdates
          if (updates != null) {
            for ((k, v) in updates) {
              journey.setContextJson(k, v)
            }
          }
          ActionResult.Continue
        } else {
          val err = execution.error
          if (err != null && err.retryable) {
            val retryAfter = (err.retryAfter ?: 5).toLong()
            val resumeAt = nowEpochMillis() + retryAfter * 1000L
            ActionResult.Pause(
              makePendingAction(
                kind = FlowPendingActionKind.REMOTE_RETRY,
                context = context,
                index = index,
                resumeAtEpochMillis = resumeAt,
                condition = null,
                maxTimeMs = null,
              )
            )
          } else {
            ActionResult.Exit(JourneyExitReason.ERROR)
          }
        }
      } else {
        ActionResult.Continue
      }
    } catch (_: Throwable) {
      val resumeAt = nowEpochMillis() + 5_000L
      ActionResult.Pause(
        makePendingAction(
          kind = FlowPendingActionKind.REMOTE_RETRY,
          context = context,
          index = index,
          resumeAtEpochMillis = resumeAt,
          condition = null,
          maxTimeMs = null,
        )
      )
    }
  }

  private suspend fun handleSetViewModel(action: InteractionAction.SetViewModel, context: RuntimeTriggerContext): ActionResult {
    val resolvedValue = resolveToJsonElement(action.value, context)
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    viewModels.setValue(action.path, resolvedValue, screenId, context.instanceId)
    journey.flowState.viewModelSnapshot = viewModels.getSnapshot()

    sendViewModelPatch(action.path, resolvedValue, source = "host", instanceId = context.instanceId)

    dispatchDidSetTrigger(
      path = action.path,
      value = resolvedValue,
      screenId = screenId,
      instanceId = context.instanceId,
    )
    scheduleTriggerReset(action.path, screenId, context.instanceId)
    return ActionResult.Continue
  }

  private suspend fun handleFireTrigger(action: InteractionAction.FireTrigger, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val timestamp = JsonPrimitive(nowEpochMillis())
    viewModels.setValue(action.path, timestamp, screenId, context.instanceId)
    journey.flowState.viewModelSnapshot = viewModels.getSnapshot()

    sendViewModelTrigger(action.path, timestamp, context.instanceId)

    dispatchDidSetTrigger(
      path = action.path,
      value = timestamp,
      screenId = screenId,
      instanceId = context.instanceId,
    )
    scheduleTriggerReset(action.path, screenId, context.instanceId)

    return ActionResult.Continue
  }

  private suspend fun handleListInsert(action: InteractionAction.ListInsert, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val resolved = resolveValueRefs(action.value, context)
    val payload = mutableMapOf<String, Any?>("value" to resolved)
    if (action.index != null) payload["index"] = action.index

    val ok = viewModels.setListValue(
      path = action.path,
      operation = "insert",
      payload = payload,
      screenId = screenId,
      instanceId = context.instanceId,
    )
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("insert", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun handleListRemove(action: InteractionAction.ListRemove, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val payload = mapOf("index" to action.index)
    val ok = viewModels.setListValue(action.path, "remove", payload, screenId, context.instanceId)
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("remove", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun handleListSwap(action: InteractionAction.ListSwap, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val payload = mapOf("from" to action.indexA, "to" to action.indexB)
    val ok = viewModels.setListValue(action.path, "swap", payload, screenId, context.instanceId)
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("swap", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun handleListMove(action: InteractionAction.ListMove, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val payload = mapOf("from" to action.from, "to" to action.to)
    val ok = viewModels.setListValue(action.path, "move", payload, screenId, context.instanceId)
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("move", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun handleListSet(action: InteractionAction.ListSet, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val resolved = resolveValueRefs(action.value, context)
    val payload = mapOf("index" to action.index, "value" to resolved)
    val ok = viewModels.setListValue(action.path, "set", payload, screenId, context.instanceId)
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("set", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun handleListClear(action: InteractionAction.ListClear, context: RuntimeTriggerContext): ActionResult {
    val screenId = context.screenId ?: journey.flowState.currentScreenId
    val payload = emptyMap<String, Any?>()
    val ok = viewModels.setListValue(action.path, "clear", payload, screenId, context.instanceId)
    if (ok) {
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelListOperation("clear", action.path, payload, context.instanceId)
      val updated = viewModels.getValue(action.path, screenId, context.instanceId) ?: JsonNull
      dispatchDidSetTrigger(action.path, updated, screenId, context.instanceId)
    }
    return ActionResult.Continue
  }

  private suspend fun runNestedActions(actions: List<InteractionAction>, context: RuntimeTriggerContext): ActionResult {
    if (actions.isEmpty()) return ActionResult.Continue

    for ((idx, action) in actions.withIndex()) {
      when (val result = executeAction(action, context, idx, resumeContext = null)) {
        is ActionResult.Continue -> continue
        is ActionResult.StopSequence,
        is ActionResult.Pause,
        is ActionResult.Exit,
        -> return result
      }
    }
    return ActionResult.Continue
  }

  private suspend fun dispatchDidSetTrigger(
    path: VmPathRef,
    value: JsonElement,
    screenId: String?,
    instanceId: String?,
  ): FlowRunOutcome? {
    val interactions = if (screenId != null) interactionsById[screenId].orEmpty() else emptyList()
    if (interactions.isEmpty()) return null

    for (interaction in interactions) {
      if (interaction.enabled == false) continue
      val trigger = interaction.trigger as? InteractionTrigger.DidSet ?: continue
      if (!matchesViewModelPath(trigger.path, path)) continue

      val debounceMs = trigger.debounceMs
      if (debounceMs != null && debounceMs > 0) {
        val key = trigger.path.normalizedPath
        debounceJobs[key]?.cancel()
        debounceJobs[key] = scope.launch {
          delay(debounceMs.toLong())
          enqueueActions(
            interaction.actions,
            RuntimeTriggerContext(
              screenId = screenId,
              componentId = null,
              interactionId = interaction.id,
              instanceId = instanceId,
            )
          )
          processQueue(resumeContext = null)
        }
      } else {
        enqueueActions(
          interaction.actions,
          RuntimeTriggerContext(
            screenId = screenId,
            componentId = null,
            interactionId = interaction.id,
            instanceId = instanceId,
          )
        )
      }
    }

    return processQueue(resumeContext = null)
  }

  private fun scheduleTriggerReset(path: VmPathRef, screenId: String?, instanceId: String?) {
    if (!viewModels.isTriggerPath(path, screenId)) return
    val key = path.normalizedPath
    triggerResetJobs[key]?.cancel()
    triggerResetJobs[key] = scope.launch {
      kotlinx.coroutines.yield()
      viewModels.setValue(path, JsonPrimitive(0), screenId, instanceId)
      journey.flowState.viewModelSnapshot = viewModels.getSnapshot()
      sendViewModelTrigger(path, JsonPrimitive(0), instanceId)
    }
  }

  private fun resolveActions(interactionId: String, screenId: String?, componentId: String?): List<InteractionAction>? {
    if (!screenId.isNullOrBlank()) {
      val list = interactionsById[screenId].orEmpty()
      val match = list.firstOrNull { it.id == interactionId }
      if (match != null) return match.actions
    }

    if (!componentId.isNullOrBlank()) {
      val list = interactionsById[componentId].orEmpty()
      val match = list.firstOrNull { it.id == interactionId }
      if (match != null) return match.actions
    }

    for ((_, list) in interactionsById) {
      val match = list.firstOrNull { it.id == interactionId }
      if (match != null) return match.actions
    }
    return null
  }

  private fun makePendingAction(
    kind: FlowPendingActionKind,
    context: RuntimeTriggerContext,
    index: Int,
    resumeAtEpochMillis: Long?,
    condition: io.nuxie.sdk.ir.IREnvelope?,
    maxTimeMs: Int?,
    startedAtEpochMillis: Long = nowEpochMillis(),
  ): FlowPendingAction {
    return FlowPendingAction(
      interactionId = context.interactionId ?: "entry",
      screenId = context.screenId,
      componentId = context.componentId,
      actionIndex = index,
      kind = kind,
      resumeAtEpochMillis = resumeAtEpochMillis,
      condition = condition,
      maxTimeMs = maxTimeMs,
      startedAtEpochMillis = startedAtEpochMillis,
    )
  }

  private fun mapExitReason(reason: String?): JourneyExitReason {
    return when (reason?.lowercase()) {
      "goal_met" -> JourneyExitReason.GOAL_MET
      "trigger_unmatched" -> JourneyExitReason.TRIGGER_UNMATCHED
      "expired" -> JourneyExitReason.EXPIRED
      "error" -> JourneyExitReason.ERROR
      "cancelled" -> JourneyExitReason.CANCELLED
      else -> JourneyExitReason.COMPLETED
    }
  }

  private fun trackAction(action: InteractionAction, context: RuntimeTriggerContext, error: String?) {
    eventService.track(
      JourneyEvents.journeyAction,
      properties = JourneyEvents.journeyActionProperties(
        journey = journey,
        screenId = context.screenId ?: journey.flowState.currentScreenId,
        interactionId = context.interactionId,
        actionType = action.actionType,
        error = error,
      )
    )
  }

  private fun sendViewModelInit() {
    warnConvertersIfNeeded()
    val payload = buildJsonObject(
      "viewModels" to json.encodeToJsonElement(ListSerializer(io.nuxie.sdk.flows.ViewModel.serializer()), flow.remoteFlow.viewModels),
      "instances" to json.encodeToJsonElement(ListSerializer(ViewModelInstance.serializer()), viewModels.allInstances()),
      "converters" to convertersPayload(flow.remoteFlow.converters),
      "screenDefaults" to json.encodeToJsonElement(
        MapSerializer(
          String.serializer(),
          MapSerializer(String.serializer(), String.serializer())
        ),
        viewModels.screenDefaultsPayload(),
      )
    )
    scope.launch { host?.sendRuntimeMessage("runtime/view_model_init", payload) }
  }

  private fun warnConvertersIfNeeded() {
    if (didWarnConverters) return
    val converters = flow.remoteFlow.converters
    if (converters.isNullOrEmpty()) return
    didWarnConverters = true
  }

  private fun convertersPayload(input: Map<String, Map<String, JsonElement>>?): JsonElement {
    if (input == null) return JsonObject(emptyMap())
    return JsonObject(
      input.mapValues { (_, converterMap) ->
        JsonObject(
          converterMap.mapValues { (_, value) -> value }
        )
      }
    )
  }

  private fun sendViewModelPatch(path: VmPathRef, value: JsonElement, source: String?, instanceId: String?) {
    val payload = mutableMapOf<String, JsonElement>(
      "value" to value,
      "pathIds" to JsonArray(path.pathIds.map { JsonPrimitive(it) }),
    )
    if (path.isRelative == true) payload["isRelative"] = JsonPrimitive(true)
    if (path.nameBased == true) payload["nameBased"] = JsonPrimitive(true)
    if (!source.isNullOrBlank()) payload["source"] = JsonPrimitive(source)
    if (!instanceId.isNullOrBlank()) payload["instanceId"] = JsonPrimitive(instanceId)
    scope.launch { host?.sendRuntimeMessage("runtime/view_model_patch", JsonObject(payload)) }
  }

  private fun sendViewModelListOperation(op: String, path: VmPathRef, payload: Map<String, Any?>, instanceId: String?) {
    val out = payload.mapValues { (_, v) -> toJsonElement(v) }.toMutableMap()
    out["pathIds"] = JsonArray(path.pathIds.map { JsonPrimitive(it) })
    if (path.isRelative == true) out["isRelative"] = JsonPrimitive(true)
    if (path.nameBased == true) out["nameBased"] = JsonPrimitive(true)
    if (!instanceId.isNullOrBlank()) out["instanceId"] = JsonPrimitive(instanceId)
    scope.launch { host?.sendRuntimeMessage("runtime/view_model_list_$op", JsonObject(out)) }
  }

  private fun sendViewModelTrigger(path: VmPathRef, value: JsonElement?, instanceId: String?) {
    val payload = mutableMapOf<String, JsonElement>(
      "pathIds" to JsonArray(path.pathIds.map { JsonPrimitive(it) }),
    )
    if (path.isRelative == true) payload["isRelative"] = JsonPrimitive(true)
    if (path.nameBased == true) payload["nameBased"] = JsonPrimitive(true)
    if (value != null) payload["value"] = value
    if (!instanceId.isNullOrBlank()) payload["instanceId"] = JsonPrimitive(instanceId)
    scope.launch { host?.sendRuntimeMessage("runtime/view_model_trigger", JsonObject(payload)) }
  }

  private fun sendShowScreen(screenId: String, transition: JsonElement? = null) {
    val payload = mutableMapOf<String, JsonElement>("screenId" to JsonPrimitive(screenId))
    if (transition != null) {
      payload["transition"] = transition
    }
    journey.flowState.currentScreenId = screenId
    scope.launch {
      host?.showScreen(screenId, transition)
      host?.sendRuntimeMessage("runtime/navigate", JsonObject(payload))
    }
  }

  private fun matchesTrigger(expected: InteractionTrigger, actual: InteractionTrigger): Boolean {
    return when {
      expected is InteractionTrigger.Press && actual is InteractionTrigger.Press -> true
      expected is InteractionTrigger.Hover && actual is InteractionTrigger.Hover -> true
      expected is InteractionTrigger.LongPress && actual is InteractionTrigger.LongPress -> {
        val minMs = expected.minMs
        val input = actual.minMs
        if (minMs == null || input == null) true else minMs <= input
      }
      expected is InteractionTrigger.Drag && actual is InteractionTrigger.Drag -> {
        val dir = expected.direction
        val inputDir = actual.direction
        val threshold = expected.threshold
        val inputThreshold = actual.threshold
        if (dir != null && inputDir != null && dir != inputDir) return false
        if (threshold != null && inputThreshold != null && threshold > inputThreshold) return false
        true
      }
      expected is InteractionTrigger.Event && actual is InteractionTrigger.Event -> expected.eventName == actual.eventName
      expected is InteractionTrigger.DidSet && actual is InteractionTrigger.DidSet -> matchesViewModelPath(expected.path, actual.path)
      expected is InteractionTrigger.Manual && actual is InteractionTrigger.Manual -> true
      else -> false
    }
  }

  private fun matchesViewModelPath(triggerPath: VmPathRef, inputPath: VmPathRef): Boolean {
    val trigger = pathIdsKey(triggerPath)
    val input = pathIdsKey(inputPath)
    return trigger == input
  }

  private fun pathIdsKey(ref: VmPathRef): String {
    val prefix = when {
      ref.isRelative == true -> "ids:rel"
      ref.nameBased == true -> "ids:name"
      else -> "ids"
    }
    return "$prefix:${ref.pathIds.joinToString(".")}"
  }

  private fun resolveValueRefs(value: JsonElement, context: RuntimeTriggerContext): Any? {
    when (value) {
      JsonNull -> return null
      is JsonPrimitive -> {
        if (value.isString) return value.content
        return value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.contentOrNull
      }
      is JsonArray -> {
        return value.map { resolveValueRefs(it, context) }
      }
      is JsonObject -> {
        if (value.size == 1 && value["literal"] != null) {
          return resolveValueRefs(value["literal"] ?: JsonNull, context)
        }
        if (value.size == 1 && value["ref"] != null) {
          val ref = parseRefPath(value["ref"])
          if (ref != null) {
            val vmValue = viewModels.getValue(
              ref,
              context.screenId ?: journey.flowState.currentScreenId,
              context.instanceId,
            )
            return vmValue?.let { resolveValueRefs(it, context) }
          }
        }

        val out = mutableMapOf<String, Any?>()
        for ((k, v) in value) {
          out[k] = resolveValueRefs(v, context)
        }
        return out
      }
    }
  }

  private fun resolveToJsonElement(value: JsonElement, context: RuntimeTriggerContext): JsonElement {
    return toJsonElement(resolveValueRefs(value, context))
  }

  private fun parseRefPath(value: JsonElement?): VmPathRef? {
    val obj = value as? JsonObject ?: return null
    val ids = (obj["pathIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: return null
    val isRelative = (obj["isRelative"] as? JsonPrimitive)?.booleanOrNull
    val nameBased = (obj["nameBased"] as? JsonPrimitive)?.booleanOrNull
    return VmPathRef(pathIds = ids, isRelative = isRelative, nameBased = nameBased)
  }

  private suspend fun evalConditionIR(envelope: io.nuxie.sdk.ir.IREnvelope?, event: NuxieEvent?): Boolean {
    if (envelope == null) return true

    val userAdapter = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    }
    val eventsAdapter = eventService as IREventQueries
    val segmentsAdapter = segmentService as IRSegmentQueries
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
      envelope,
      IRRuntime.Config(
        event = event,
        user = userAdapter,
        events = eventsAdapter,
        segments = segmentsAdapter,
        features = featuresAdapter,
      )
    )
  }

  private suspend fun getServerAssignment(experimentId: String): ExperimentAssignment? {
    val profile = profileService.getCachedProfile(journey.distinctId) ?: return null
    return profile.experiments?.get(experimentId)
  }

  private fun getFrozenExperimentVariantKey(experimentKey: String): String? {
    val dict = journey.getContextObject("_experiment_variants") ?: return null
    return (dict[experimentKey] as? JsonPrimitive)?.contentOrNull
  }

  private fun freezeExperimentVariantKey(experimentKey: String, variantKey: String) {
    if (experimentKey.isBlank() || variantKey.isBlank()) return
    val existing = (journey.getContextObject("_experiment_variants")?.toMutableMap() ?: mutableMapOf())
    existing[experimentKey] = JsonPrimitive(variantKey)
    journey.setContextJson("_experiment_variants", JsonObject(existing))
  }

  private fun hasEmittedExperimentExposure(experimentKey: String): Boolean {
    val dict = journey.getContextObject("_experiment_exposure_emitted") ?: return false
    val value = dict[experimentKey]
    return when (value) {
      is JsonPrimitive -> value.booleanOrNull == true || value.contentOrNull == "1" || value.contentOrNull == "true"
      else -> false
    }
  }

  private fun markExperimentExposureEmitted(experimentKey: String) {
    if (experimentKey.isBlank()) return
    val existing = (journey.getContextObject("_experiment_exposure_emitted")?.toMutableMap() ?: mutableMapOf())
    existing[experimentKey] = JsonPrimitive(true)
    journey.setContextJson("_experiment_exposure_emitted", JsonObject(existing))
  }

  private fun resolveExperimentVariant(
    action: InteractionAction.Experiment,
    assignment: ExperimentAssignment?,
  ): Pair<InteractionAction.ExperimentVariant?, Boolean> {
    if (assignment == null) return Pair(action.variants.firstOrNull(), false)

    return when (assignment.status) {
      "running", "concluded" -> {
        val key = assignment.variantKey
        if (!key.isNullOrBlank()) {
          val variant = action.variants.firstOrNull { it.id == key }
          if (variant != null) return Pair(variant, true)
        }
        Pair(action.variants.firstOrNull(), false)
      }
      else -> Pair(action.variants.firstOrNull(), false)
    }
  }

  private fun parseTime(value: String): Pair<Int, Int>? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h to m
  }

  private fun calculateNextValidDay(
    from: ZonedDateTime,
    validDays: List<Int>,
    zone: ZoneId,
  ): ZonedDateTime {
    for (i in 1..7) {
      val next = from.plusDays(i.toLong())
      val wd = next.dayOfWeek.value.valueToCalendarWeekday()
      if (validDays.contains(wd)) {
        return ZonedDateTime.of(next.toLocalDate(), LocalDateTime.MIN.toLocalTime(), zone)
      }
    }
    return from
  }

  private fun calculateNextWindowOpen(
    from: ZonedDateTime,
    startTime: String,
    zone: ZoneId,
    validDays: List<Int>?,
  ): ZonedDateTime {
    val start = parseTime(startTime) ?: return from
    var next = from
      .withHour(start.first)
      .withMinute(start.second)
      .withSecond(0)
      .withNano(0)
    if (!next.isAfter(from)) {
      next = next.plusDays(1)
    }

    if (!validDays.isNullOrEmpty()) {
      while (true) {
        val wd = next.dayOfWeek.value.valueToCalendarWeekday()
        if (validDays.contains(wd)) break
        next = next.plusDays(1)
      }
    }

    return next.withZoneSameInstant(zone)
  }

  private fun Int.valueToCalendarWeekday(): Int {
    // java.time Monday=1..Sunday=7 -> Calendar Sunday=1..Saturday=7
    return when (this) {
      7 -> 1
      else -> this + 1
    }
  }

  private fun buildJsonObject(vararg entries: Pair<String, JsonElement>): JsonObject {
    return JsonObject(entries.associate { it.first to it.second })
  }
}

private val InteractionAction.actionType: String
  get() = when (this) {
    is InteractionAction.Navigate -> "navigate"
    is InteractionAction.Back -> "back"
    is InteractionAction.Delay -> "delay"
    is InteractionAction.TimeWindow -> "time_window"
    is InteractionAction.WaitUntil -> "wait_until"
    is InteractionAction.Condition -> "condition"
    is InteractionAction.Experiment -> "experiment"
    is InteractionAction.SendEvent -> "send_event"
    is InteractionAction.UpdateCustomer -> "update_customer"
    is InteractionAction.Purchase -> "purchase"
    is InteractionAction.Restore -> "restore"
    is InteractionAction.OpenLink -> "open_link"
    is InteractionAction.Dismiss -> "dismiss"
    is InteractionAction.CallDelegate -> "call_delegate"
    is InteractionAction.Remote -> "remote"
    is InteractionAction.SetViewModel -> "set_view_model"
    is InteractionAction.FireTrigger -> "fire_trigger"
    is InteractionAction.ListInsert -> "list_insert"
    is InteractionAction.ListRemove -> "list_remove"
    is InteractionAction.ListSwap -> "list_swap"
    is InteractionAction.ListMove -> "list_move"
    is InteractionAction.ListSet -> "list_set"
    is InteractionAction.ListClear -> "list_clear"
    is InteractionAction.Exit -> "exit"
    is InteractionAction.Unknown -> this.type
  }
