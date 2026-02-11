package io.nuxie.sdk.flows

import io.nuxie.sdk.ir.IREnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Server-delivered flow definition.
 *
 * Mirrors iOS `RemoteFlow` (Swift).
 */
@Serializable
data class RemoteFlow(
  val id: String,
  val bundle: FlowBundleRef,
  val fontManifest: FontManifest? = null,
  val screens: List<RemoteFlowScreen>,
  val interactions: Map<String, List<Interaction>>,
  val viewModels: List<ViewModel>,
  val viewModelInstances: List<ViewModelInstance>? = null,
  val converters: Map<String, Map<String, JsonElement>>? = null,
)

@Serializable
data class FlowBundleRef(
  val url: String,
  val manifest: BuildManifest,
)

@Serializable
data class FontManifest(
  val version: Int,
  val fonts: List<FontManifestEntry>,
)

@Serializable
data class FontManifestEntry(
  val id: String,
  val family: String,
  val style: String,
  val weight: String,
  val format: String,
  val contentHash: String,
  val assetUrl: String,
)

@Serializable
data class RemoteFlowScreen(
  val id: String,
  @SerialName("defaultViewModelId")
  val defaultViewModelId: String? = null,
  @SerialName("defaultInstanceId")
  val defaultInstanceId: String? = null,
)

// MARK: - View Model Path References

@Serializable(with = VmPathRefSerializer::class)
data class VmPathRef(
  val pathIds: List<Int>,
  val isRelative: Boolean? = null,
  val nameBased: Boolean? = null,
) {
  val normalizedPath: String
    get() {
      val prefix = when {
        isRelative == true -> "ids:rel"
        nameBased == true -> "ids:name"
        else -> "ids"
      }
      return "$prefix:${pathIds.joinToString(".")}"
    }
}

object VmPathRefSerializer : kotlinx.serialization.KSerializer<VmPathRef> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nuxie.sdk.flows.VmPathRef")

  override fun deserialize(decoder: Decoder): VmPathRef {
    val input = decoder as? JsonDecoder ?: throw SerializationException("VmPathRef can be decoded only from JSON")
    val el = input.decodeJsonElement()
    val obj = el.jsonObject

    val idsEl = obj["pathIds"] ?: throw SerializationException("VmPathRef requires pathIds")
    val pathIds = idsEl.jsonArray.map { it.jsonPrimitive.int }

    return VmPathRef(
      pathIds = pathIds,
      isRelative = obj["isRelative"]?.jsonPrimitive?.booleanOrNull,
      nameBased = obj["nameBased"]?.jsonPrimitive?.booleanOrNull,
    )
  }

  override fun serialize(encoder: Encoder, value: VmPathRef) {
    val output = encoder as? JsonEncoder ?: throw SerializationException("VmPathRef can be encoded only to JSON")
    val obj = buildJsonObject {
      put("kind", "ids")
      putJsonArray("pathIds") { value.pathIds.forEach { add(JsonPrimitive(it)) } }
      if (value.isRelative == true) put("isRelative", true)
      if (value.nameBased == true) put("nameBased", true)
    }
    output.encodeJsonElement(obj)
  }
}

// MARK: - Interaction Models

@Serializable
data class Interaction(
  val id: String,
  val trigger: InteractionTrigger,
  val actions: List<InteractionAction>,
  val enabled: Boolean? = null,
)

@Serializable(with = InteractionTriggerSerializer::class)
sealed interface InteractionTrigger {
  @Serializable
  data class LongPress(val minMs: Int? = null) : InteractionTrigger

  @Serializable
  data object Hover : InteractionTrigger

  @Serializable
  data object Press : InteractionTrigger

  @Serializable
  data class Drag(
    val direction: DragDirection? = null,
    val threshold: Double? = null,
  ) : InteractionTrigger

  @Serializable
  data class Event(
    val eventName: String,
    val filter: IREnvelope? = null,
  ) : InteractionTrigger

  @Serializable
  data class Manual(val label: String? = null) : InteractionTrigger

  @Serializable
  data class DidSet(
    val path: VmPathRef,
    val debounceMs: Int? = null,
  ) : InteractionTrigger

  @Serializable
  data class Unknown(
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
  ) : InteractionTrigger

  @Serializable
  enum class DragDirection {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,

    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,
  }
}

object InteractionTriggerSerializer : kotlinx.serialization.KSerializer<InteractionTrigger> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nuxie.sdk.flows.InteractionTrigger")

  override fun deserialize(decoder: Decoder): InteractionTrigger {
    val input = decoder as? JsonDecoder ?: throw SerializationException("InteractionTrigger can be decoded only from JSON")
    val el = input.decodeJsonElement()
    val obj = el.jsonObject
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    return when (type) {
      "long_press" -> input.json.decodeFromJsonElement(InteractionTrigger.LongPress.serializer(), el)
      "hover" -> InteractionTrigger.Hover
      "press" -> InteractionTrigger.Press
      "drag" -> input.json.decodeFromJsonElement(InteractionTrigger.Drag.serializer(), el)
      "event" -> input.json.decodeFromJsonElement(InteractionTrigger.Event.serializer(), el)
      "manual" -> input.json.decodeFromJsonElement(InteractionTrigger.Manual.serializer(), el)
      "did_set" -> input.json.decodeFromJsonElement(InteractionTrigger.DidSet.serializer(), el)
      else -> InteractionTrigger.Unknown(type = type, payload = obj)
    }
  }

  override fun serialize(encoder: Encoder, value: InteractionTrigger) {
    val output = encoder as? JsonEncoder ?: throw SerializationException("InteractionTrigger can be encoded only to JSON")
    val obj = when (value) {
      is InteractionTrigger.LongPress -> buildJsonObject {
        put("type", "long_press")
        if (value.minMs != null) put("minMs", value.minMs)
      }
      is InteractionTrigger.Hover -> buildJsonObject { put("type", "hover") }
      is InteractionTrigger.Press -> buildJsonObject { put("type", "press") }
      is InteractionTrigger.Drag -> buildJsonObject {
        put("type", "drag")
        if (value.direction != null) {
          put(
            "direction",
            output.json.encodeToJsonElement(InteractionTrigger.DragDirection.serializer(), value.direction)
          )
        }
        if (value.threshold != null) put("threshold", value.threshold)
      }
      is InteractionTrigger.Event -> buildJsonObject {
        put("type", "event")
        put("eventName", value.eventName)
        if (value.filter != null) put("filter", output.json.encodeToJsonElement(IREnvelope.serializer(), value.filter))
      }
      is InteractionTrigger.Manual -> buildJsonObject {
        put("type", "manual")
        if (value.label != null) put("label", value.label)
      }
      is InteractionTrigger.DidSet -> buildJsonObject {
        put("type", "did_set")
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        if (value.debounceMs != null) put("debounceMs", value.debounceMs)
      }
      is InteractionTrigger.Unknown -> value.payload
    }
    output.encodeJsonElement(obj)
  }
}

@Serializable(with = InteractionActionSerializer::class)
sealed interface InteractionAction {
  @Serializable
  data class Navigate(val screenId: String, val transition: JsonElement? = null) : InteractionAction

  @Serializable
  data class Back(val steps: Int? = null, val transition: JsonElement? = null) : InteractionAction

  @Serializable
  data class Delay(val durationMs: Int) : InteractionAction

  @Serializable
  data class TimeWindow(
    val startTime: String,
    val endTime: String,
    val timezone: String,
    val daysOfWeek: List<Int>? = null,
  ) : InteractionAction

  @Serializable
  data class WaitUntil(
    val condition: IREnvelope? = null,
    val maxTimeMs: Int? = null,
  ) : InteractionAction

  @Serializable
  data class Condition(
    val branches: List<ConditionBranch>,
    val defaultActions: List<InteractionAction>? = null,
  ) : InteractionAction

  @Serializable
  data class ConditionBranch(
    val id: String,
    val label: String? = null,
    val condition: IREnvelope? = null,
    val actions: List<InteractionAction>,
  )

  @Serializable
  data class Experiment(
    val experimentId: String,
    val variants: List<ExperimentVariant>,
  ) : InteractionAction

  @Serializable
  data class ExperimentVariant(
    val id: String,
    val name: String? = null,
    val percentage: Double,
    val actions: List<InteractionAction>,
  )

  @Serializable
  data class SendEvent(
    val eventName: String,
    val properties: Map<String, JsonElement>? = null,
  ) : InteractionAction

  @Serializable
  data class UpdateCustomer(
    val attributes: Map<String, JsonElement>,
  ) : InteractionAction

  @Serializable
  data class Purchase(
    val placementIndex: JsonElement,
    val productId: JsonElement,
  ) : InteractionAction

  @Serializable
  data object Restore : InteractionAction

  @Serializable
  data class OpenLink(
    val url: JsonElement,
    val target: String? = null,
  ) : InteractionAction

  @Serializable
  data class Dismiss(
    val reason: String? = null,
  ) : InteractionAction

  @Serializable
  data class CallDelegate(
    val message: String,
    val payload: JsonElement? = null,
  ) : InteractionAction

  @Serializable
  data class Remote(
    val action: String,
    val payload: JsonElement,
    val async: Boolean? = null,
  ) : InteractionAction

  @Serializable
  data class SetViewModel(
    val path: VmPathRef,
    val value: JsonElement,
  ) : InteractionAction

  @Serializable
  data class FireTrigger(
    val path: VmPathRef,
  ) : InteractionAction

  @Serializable
  data class ListInsert(
    val path: VmPathRef,
    val index: Int? = null,
    val value: JsonElement,
  ) : InteractionAction

  @Serializable
  data class ListRemove(
    val path: VmPathRef,
    val index: Int,
  ) : InteractionAction

  @Serializable
  data class ListSwap(
    val path: VmPathRef,
    val indexA: Int,
    val indexB: Int,
  ) : InteractionAction

  @Serializable
  data class ListMove(
    val path: VmPathRef,
    val from: Int,
    val to: Int,
  ) : InteractionAction

  @Serializable
  data class ListSet(
    val path: VmPathRef,
    val index: Int,
    val value: JsonElement,
  ) : InteractionAction

  @Serializable
  data class ListClear(
    val path: VmPathRef,
  ) : InteractionAction

  @Serializable
  data class Exit(
    val reason: String? = null,
  ) : InteractionAction

  @Serializable
  data class Unknown(
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
  ) : InteractionAction
}

object InteractionActionSerializer : kotlinx.serialization.KSerializer<InteractionAction> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nuxie.sdk.flows.InteractionAction")

  override fun deserialize(decoder: Decoder): InteractionAction {
    val input = decoder as? JsonDecoder ?: throw SerializationException("InteractionAction can be decoded only from JSON")
    val el = input.decodeJsonElement()
    val obj = el.jsonObject
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    return when (type) {
      "navigate" -> input.json.decodeFromJsonElement(InteractionAction.Navigate.serializer(), el)
      "back" -> input.json.decodeFromJsonElement(InteractionAction.Back.serializer(), el)
      "delay" -> input.json.decodeFromJsonElement(InteractionAction.Delay.serializer(), el)
      "time_window" -> input.json.decodeFromJsonElement(InteractionAction.TimeWindow.serializer(), el)
      "wait_until" -> input.json.decodeFromJsonElement(InteractionAction.WaitUntil.serializer(), el)
      "condition" -> input.json.decodeFromJsonElement(InteractionAction.Condition.serializer(), el)
      "experiment" -> input.json.decodeFromJsonElement(InteractionAction.Experiment.serializer(), el)
      "send_event" -> input.json.decodeFromJsonElement(InteractionAction.SendEvent.serializer(), el)
      "update_customer" -> input.json.decodeFromJsonElement(InteractionAction.UpdateCustomer.serializer(), el)
      "purchase" -> input.json.decodeFromJsonElement(InteractionAction.Purchase.serializer(), el)
      "restore" -> InteractionAction.Restore
      "open_link" -> input.json.decodeFromJsonElement(InteractionAction.OpenLink.serializer(), el)
      "dismiss" -> input.json.decodeFromJsonElement(InteractionAction.Dismiss.serializer(), el)
      "call_delegate" -> input.json.decodeFromJsonElement(InteractionAction.CallDelegate.serializer(), el)
      "remote" -> input.json.decodeFromJsonElement(InteractionAction.Remote.serializer(), el)
      "set_view_model" -> input.json.decodeFromJsonElement(InteractionAction.SetViewModel.serializer(), el)
      "fire_trigger" -> input.json.decodeFromJsonElement(InteractionAction.FireTrigger.serializer(), el)
      "list_insert" -> input.json.decodeFromJsonElement(InteractionAction.ListInsert.serializer(), el)
      "list_remove" -> input.json.decodeFromJsonElement(InteractionAction.ListRemove.serializer(), el)
      "list_swap" -> input.json.decodeFromJsonElement(InteractionAction.ListSwap.serializer(), el)
      "list_move" -> input.json.decodeFromJsonElement(InteractionAction.ListMove.serializer(), el)
      "list_set" -> input.json.decodeFromJsonElement(InteractionAction.ListSet.serializer(), el)
      "list_clear" -> input.json.decodeFromJsonElement(InteractionAction.ListClear.serializer(), el)
      "exit" -> input.json.decodeFromJsonElement(InteractionAction.Exit.serializer(), el)
      else -> InteractionAction.Unknown(type = type, payload = obj)
    }
  }

  private fun encodeWithType(type: String, build: JsonObjectBuilder.() -> Unit): JsonObject {
    return buildJsonObject {
      put("type", type)
      build()
    }
  }

  override fun serialize(encoder: Encoder, value: InteractionAction) {
    val output = encoder as? JsonEncoder ?: throw SerializationException("InteractionAction can be encoded only to JSON")
    val obj = when (value) {
      is InteractionAction.Navigate -> encodeWithType("navigate") {
        put("screenId", value.screenId)
        if (value.transition != null) put("transition", value.transition)
      }
      is InteractionAction.Back -> encodeWithType("back") {
        if (value.steps != null) put("steps", value.steps)
        if (value.transition != null) put("transition", value.transition)
      }
      is InteractionAction.Delay -> encodeWithType("delay") { put("durationMs", value.durationMs) }
      is InteractionAction.TimeWindow -> encodeWithType("time_window") {
        put("startTime", value.startTime)
        put("endTime", value.endTime)
        put("timezone", value.timezone)
        if (value.daysOfWeek != null) {
          putJsonArray("daysOfWeek") { value.daysOfWeek.forEach { add(JsonPrimitive(it)) } }
        }
      }
      is InteractionAction.WaitUntil -> encodeWithType("wait_until") {
        if (value.condition != null) put("condition", output.json.encodeToJsonElement(IREnvelope.serializer(), value.condition))
        if (value.maxTimeMs != null) put("maxTimeMs", value.maxTimeMs)
      }
      is InteractionAction.Condition -> encodeWithType("condition") {
        put("branches", output.json.encodeToJsonElement(ListSerializer(InteractionAction.ConditionBranch.serializer()), value.branches))
        if (value.defaultActions != null) {
          put(
            "defaultActions",
            output.json.encodeToJsonElement(ListSerializer(InteractionActionSerializer), value.defaultActions)
          )
        }
      }
      is InteractionAction.Experiment -> encodeWithType("experiment") {
        put("experimentId", value.experimentId)
        put(
          "variants",
          output.json.encodeToJsonElement(ListSerializer(InteractionAction.ExperimentVariant.serializer()), value.variants)
        )
      }
      is InteractionAction.SendEvent -> encodeWithType("send_event") {
        put("eventName", value.eventName)
        if (value.properties != null) {
          put("properties", output.json.encodeToJsonElement(MapSerializer(String.serializer(), JsonElement.serializer()), value.properties))
        }
      }
      is InteractionAction.UpdateCustomer -> encodeWithType("update_customer") {
        put("attributes", output.json.encodeToJsonElement(MapSerializer(String.serializer(), JsonElement.serializer()), value.attributes))
      }
      is InteractionAction.Purchase -> encodeWithType("purchase") {
        put("placementIndex", value.placementIndex)
        put("productId", value.productId)
      }
      is InteractionAction.Restore -> encodeWithType("restore") {}
      is InteractionAction.OpenLink -> encodeWithType("open_link") {
        put("url", value.url)
        if (value.target != null) put("target", value.target)
      }
      is InteractionAction.Dismiss -> encodeWithType("dismiss") { if (value.reason != null) put("reason", value.reason) }
      is InteractionAction.CallDelegate -> encodeWithType("call_delegate") {
        put("message", value.message)
        if (value.payload != null) put("payload", value.payload)
      }
      is InteractionAction.Remote -> encodeWithType("remote") {
        put("action", value.action)
        put("payload", value.payload)
        if (value.async != null) put("async", value.async)
      }
      is InteractionAction.SetViewModel -> encodeWithType("set_view_model") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        put("value", value.value)
      }
      is InteractionAction.FireTrigger -> encodeWithType("fire_trigger") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
      }
      is InteractionAction.ListInsert -> encodeWithType("list_insert") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        if (value.index != null) put("index", value.index)
        put("value", value.value)
      }
      is InteractionAction.ListRemove -> encodeWithType("list_remove") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        put("index", value.index)
      }
      is InteractionAction.ListSwap -> encodeWithType("list_swap") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        put("indexA", value.indexA)
        put("indexB", value.indexB)
      }
      is InteractionAction.ListMove -> encodeWithType("list_move") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        put("from", value.from)
        put("to", value.to)
      }
      is InteractionAction.ListSet -> encodeWithType("list_set") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
        put("index", value.index)
        put("value", value.value)
      }
      is InteractionAction.ListClear -> encodeWithType("list_clear") {
        put("path", output.json.encodeToJsonElement(VmPathRefSerializer, value.path))
      }
      is InteractionAction.Exit -> encodeWithType("exit") { if (value.reason != null) put("reason", value.reason) }
      is InteractionAction.Unknown -> value.payload
    }
    output.encodeJsonElement(obj)
  }
}

// MARK: - View Model Models

@Serializable
data class ViewModel(
  val id: String,
  val name: String,
  val viewModelPathId: Int? = null,
  val properties: Map<String, ViewModelProperty>,
)

@Serializable
enum class ViewModelPropertyType {
  @SerialName("string")
  STRING,

  @SerialName("number")
  NUMBER,

  @SerialName("boolean")
  BOOLEAN,

  @SerialName("color")
  COLOR,

  @SerialName("enum")
  ENUM,

  @SerialName("list")
  LIST,

  @SerialName("list_index")
  LIST_INDEX,

  @SerialName("object")
  OBJECT,

  @SerialName("image")
  IMAGE,

  @SerialName("trigger")
  TRIGGER,

  @SerialName("viewModel")
  VIEW_MODEL,
}

@Serializable
data class ViewModelProperty(
  val type: ViewModelPropertyType,
  val propertyId: Int? = null,
  val defaultValue: JsonElement? = null,
  val required: Boolean? = null,
  val enumValues: List<String>? = null,
  val itemType: ViewModelProperty? = null,
  val schema: Map<String, ViewModelProperty>? = null,
  val viewModelId: String? = null,
  val validation: ViewModelValidation? = null,
)

@Serializable
data class ViewModelValidation(
  val min: Double? = null,
  val max: Double? = null,
  val minLength: Int? = null,
  val maxLength: Int? = null,
  val regex: String? = null,
)

@Serializable
data class ViewModelInstance(
  val viewModelId: String,
  val instanceId: String,
  val name: String? = null,
  val values: Map<String, JsonElement>,
)
