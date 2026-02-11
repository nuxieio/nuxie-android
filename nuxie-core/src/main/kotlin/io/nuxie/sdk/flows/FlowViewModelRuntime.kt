package io.nuxie.sdk.flows

import kotlinx.serialization.Serializable
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
import java.util.Locale

/**
 * Local view-model runtime for Flow bundles.
 *
 * Mirrors iOS `FlowViewModelRuntime`:
 * - resolves VmPathRef to a specific view model instance
 * - supports set/get for nested objects
 * - supports list operations (insert/remove/swap/move/set/clear)
 * - maintains list_index properties for view-model list items
 */
class FlowViewModelRuntime(private val remoteFlow: RemoteFlow) {

  @Serializable
  data class FlowViewModelSnapshot(
    val viewModelInstances: List<ViewModelInstance>,
  )

  private data class InstanceState(
    var viewModelId: String,
    var instanceId: String,
    var name: String?,
    var values: MutableMap<String, JsonElement>,
  )

  private sealed interface PathSegment {
    data class Prop(val name: String) : PathSegment
    data class Index(val expr: String) : PathSegment
  }

  private data class ResolvedPathInfo(
    val instance: InstanceState?,
    val segments: List<PathSegment>,
    val rawPath: String,
    val viewModel: ViewModel?,
  )

  private val viewModelsById: Map<String, ViewModel> = remoteFlow.viewModels.associateBy { it.id }
  private val viewModelList: List<ViewModel> = remoteFlow.viewModels
  private val instancesById: MutableMap<String, InstanceState> = mutableMapOf()
  private val instancesByViewModel: MutableMap<String, MutableList<String>> = mutableMapOf()
  private val screenDefaults: Map<String, Pair<String?, String?>> =
    remoteFlow.screens.associate { it.id to (it.defaultViewModelId to it.defaultInstanceId) }

  init {
    val initialInstances = remoteFlow.viewModelInstances.orEmpty()
    for (instance in initialInstances) {
      val state = InstanceState(
        viewModelId = instance.viewModelId,
        instanceId = instance.instanceId,
        name = instance.name,
        values = instance.values.toMutableMap(),
      )
      instancesById[state.instanceId] = state
      val list = instancesByViewModel.getOrPut(state.viewModelId) { mutableListOf() }
      list.add(state.instanceId)
      applyViewModelDefaults(instanceId = state.instanceId)
    }

    // Ensure each view model has at least one instance.
    for (vm in viewModelList) {
      val list = instancesByViewModel[vm.id].orEmpty()
      if (list.isEmpty()) {
        val blank = createBlankInstance(forViewModelId = vm.id)
        instancesById[blank.instanceId] = blank
        instancesByViewModel[vm.id] = mutableListOf(blank.instanceId)
      }
    }
  }

  fun getSnapshot(): FlowViewModelSnapshot {
    val instances = instancesById.values.map { state ->
      ViewModelInstance(
        viewModelId = state.viewModelId,
        instanceId = state.instanceId,
        name = state.name,
        values = state.values.toMap(),
      )
    }
    return FlowViewModelSnapshot(viewModelInstances = instances)
  }

  fun hydrate(snapshot: FlowViewModelSnapshot) {
    instancesById.clear()
    instancesByViewModel.clear()

    for (instance in snapshot.viewModelInstances) {
      val state = InstanceState(
        viewModelId = instance.viewModelId,
        instanceId = instance.instanceId,
        name = instance.name,
        values = instance.values.toMutableMap(),
      )
      instancesById[state.instanceId] = state
      val list = instancesByViewModel.getOrPut(state.viewModelId) { mutableListOf() }
      list.add(state.instanceId)
      applyViewModelDefaults(instanceId = state.instanceId)
    }

    // Ensure each view model has at least one instance.
    for (vm in viewModelList) {
      val list = instancesByViewModel[vm.id].orEmpty()
      if (list.isEmpty()) {
        val blank = createBlankInstance(forViewModelId = vm.id)
        instancesById[blank.instanceId] = blank
        instancesByViewModel[vm.id] = mutableListOf(blank.instanceId)
      }
    }
  }

  fun isTriggerPath(path: VmPathRef, screenId: String?): Boolean {
    val resolved = resolvePathInfo(path, screenId = screenId, instanceId = null)
    val instance = resolved.instance ?: return false
    if (resolved.segments.isEmpty()) return false

    val viewModel = resolved.viewModel ?: viewModelsById[instance.viewModelId] ?: return false
    val property = resolveProperty(inSchema = viewModel.properties, segments = resolved.segments) ?: return false
    return property.type == ViewModelPropertyType.TRIGGER
  }

  fun setValue(path: VmPathRef, value: JsonElement, screenId: String?, instanceId: String? = null): Boolean {
    val resolved = resolvePathInfo(path, screenId = screenId, instanceId = instanceId)
    val instance = resolved.instance ?: return false

    val segments = resolved.segments
    val resolvedValue = resolveLiteralValue(value)

    if (segments.isEmpty()) {
      instance.values[resolved.rawPath] = resolvedValue
      instancesById[instance.instanceId] = instance
      return true
    }

    val updated = setNestedValue(
      current = JsonObject(instance.values.toMap()),
      segments = segments,
      value = resolvedValue,
      screenId = screenId,
    )
    val dict = updated as? JsonObject ?: return false
    instance.values = dict.toMutableMap()
    instancesById[instance.instanceId] = instance

    // Keep list_index properties in sync when setting list values.
    syncListIndexValues(viewModel = resolved.viewModel, segments = segments, listValue = resolvedValue)
    return true
  }

  fun getValue(path: VmPathRef, screenId: String?, instanceId: String? = null): JsonElement? {
    val resolved = resolvePathInfo(path, screenId = screenId, instanceId = instanceId)
    val instance = resolved.instance ?: return null
    val segments = resolved.segments

    if (segments.isEmpty()) {
      return instance.values[resolved.rawPath]
    }

    var current: JsonElement = JsonObject(instance.values)
    for (segment in segments) {
      current = when (segment) {
        is PathSegment.Prop -> {
          val obj = current as? JsonObject ?: return null
          obj[segment.name] ?: return null
        }
        is PathSegment.Index -> {
          val idx = resolveIndex(segment.expr) ?: return null
          val arr = current as? JsonArray ?: return null
          if (idx < 0 || idx >= arr.size) return null
          arr[idx]
        }
      }
    }

    return current
  }

  fun setListValue(
    path: VmPathRef,
    operation: String,
    payload: Map<String, Any?>,
    screenId: String?,
    instanceId: String? = null,
  ): Boolean {
    val resolved = resolvePathInfo(path, screenId = screenId, instanceId = instanceId)
    val instance = resolved.instance ?: return false
    val segments = resolved.segments
    if (segments.isEmpty()) return false

    // Traverse to parent element.
    var parent: JsonElement = JsonObject(instance.values)
    for (segment in segments.dropLast(1)) {
      parent = when (segment) {
        is PathSegment.Prop -> {
          val obj = parent as? JsonObject ?: return false
          obj[segment.name] ?: JsonObject(emptyMap())
        }
        is PathSegment.Index -> {
          val idx = resolveIndex(segment.expr) ?: return false
          val arr = parent as? JsonArray ?: return false
          if (idx < 0 || idx >= arr.size) return false
          arr[idx]
        }
      }
    }

    val last = segments.last()
    if (last !is PathSegment.Prop) return false
    val name = last.name

    val currentListEl = (parent as? JsonObject)?.get(name)
    val array = (currentListEl as? JsonArray)?.toMutableList() ?: mutableListOf()

    fun valueAsJson(): JsonElement {
      val v = payload["value"]
      return when (v) {
        null -> JsonNull
        is JsonElement -> resolveLiteralValue(v)
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toDouble())
        else -> JsonPrimitive(v.toString())
      }
    }

    when (operation.lowercase(Locale.US)) {
      "insert" -> {
        val v = valueAsJson()
        val idx = (payload["index"] as? Int)
        if (idx != null) {
          val target = idx.coerceIn(0, array.size)
          array.add(target, v)
        } else {
          array.add(v)
        }
      }
      "remove" -> {
        val idx = (payload["index"] as? Int) ?: return false
        if (idx < 0 || idx >= array.size) return false
        array.removeAt(idx)
      }
      "swap" -> {
        val from = (payload["from"] as? Int) ?: (payload["indexA"] as? Int) ?: return false
        val to = (payload["to"] as? Int) ?: (payload["indexB"] as? Int) ?: return false
        if (from < 0 || to < 0 || from >= array.size || to >= array.size) return false
        val tmp = array[from]
        array[from] = array[to]
        array[to] = tmp
      }
      "move" -> {
        val from = (payload["from"] as? Int) ?: return false
        val to = (payload["to"] as? Int) ?: return false
        if (from < 0 || from >= array.size) return false
        val item = array.removeAt(from)
        val target = to.coerceIn(0, array.size)
        array.add(target, item)
      }
      "set" -> {
        val idx = (payload["index"] as? Int) ?: return false
        if (idx < 0 || idx >= array.size) return false
        array[idx] = valueAsJson()
      }
      "clear" -> array.clear()
      else -> return false
    }

    // Rebuild parent object by setting the list property.
    val updatedInstance = setNestedValue(
      current = JsonObject(instance.values.toMap()),
      segments = segments,
      value = JsonArray(array),
      screenId = screenId,
    )
    val dict = updatedInstance as? JsonObject ?: return false
    instance.values = dict.toMutableMap()
    instancesById[instance.instanceId] = instance

    syncListIndexValues(viewModel = resolved.viewModel, segments = segments, listValue = JsonArray(array))
    return true
  }

  fun allInstances(): List<ViewModelInstance> {
    return instancesById.values.map { state ->
      ViewModelInstance(
        viewModelId = state.viewModelId,
        instanceId = state.instanceId,
        name = state.name,
        values = state.values.toMap(),
      )
    }
  }

  fun screenDefaultsPayload(): Map<String, Map<String, String>> {
    val payload = mutableMapOf<String, Map<String, String>>()
    for ((screenId, defaults) in screenDefaults) {
      val entry = mutableMapOf<String, String>()
      val (vmId, instanceId) = defaults
      if (vmId != null) entry["defaultViewModelId"] = vmId
      if (instanceId != null) entry["defaultInstanceId"] = instanceId
      if (entry.isNotEmpty()) payload[screenId] = entry
    }
    return payload
  }

  // MARK: - Path Resolution

  private fun resolveInstance(screenId: String?, viewModelId: String?, instanceId: String?): InstanceState? {
    if (instanceId != null) {
      val found = instancesById[instanceId]
      if (found != null) return found
    }

    if (viewModelId != null) {
      val firstId = instancesByViewModel[viewModelId]?.firstOrNull()
      if (firstId != null) {
        val found = instancesById[firstId]
        if (found != null) return found
      }
    }

    if (screenId != null) {
      val defaults = screenDefaults[screenId]
      if (defaults != null) {
        val (defaultVmId, defaultInstanceId) = defaults
        if (defaultInstanceId != null) {
          val found = instancesById[defaultInstanceId]
          if (found != null) return found
        }
        if (defaultVmId != null) {
          val firstId = instancesByViewModel[defaultVmId]?.firstOrNull()
          if (firstId != null) {
            val found = instancesById[firstId]
            if (found != null) return found
          }
        }
      }
    }

    return instancesById.values.firstOrNull()
  }

  private fun resolvePathInfo(path: VmPathRef, screenId: String?, instanceId: String?): ResolvedPathInfo {
    val ref = path
    val resolved = if (ref.isRelative == true || ref.nameBased == true) {
      resolveNamePathIds(ref, screenId = screenId)
    } else {
      resolvePathIds(ref.pathIds)
    }

    if (resolved != null) {
      val (viewModelId, segments) = resolved
      val resolvedInstanceId = if (ref.isRelative == true) instanceId else null
      val instance = resolveInstance(screenId = screenId, viewModelId = viewModelId, instanceId = resolvedInstanceId)
      return ResolvedPathInfo(
        instance = instance,
        segments = segments,
        rawPath = ref.normalizedPath,
        viewModel = viewModelsById[viewModelId],
      )
    }

    return ResolvedPathInfo(
      instance = null,
      segments = emptyList(),
      rawPath = ref.normalizedPath,
      viewModel = null,
    )
  }

  private fun resolvePathIds(pathIds: List<Int>): Pair<String, List<PathSegment>>? {
    val root = pathIds.firstOrNull() ?: return null
    val viewModel = viewModelList.firstOrNull { viewModelPathId(it) == root } ?: return null
    val propertyIds = pathIds.drop(1)
    if (propertyIds.isEmpty()) return null

    var schema = viewModel.properties
    val segments = mutableListOf<PathSegment>()

    for ((idx, propertyId) in propertyIds.withIndex()) {
      val found = findPropertyById(schema, propertyId) ?: return null
      segments.add(PathSegment.Prop(found.first))

      if (idx == propertyIds.size - 1) continue
      val prop = found.second
      when (prop.type) {
        ViewModelPropertyType.OBJECT -> {
          schema = prop.schema ?: return null
        }
        ViewModelPropertyType.VIEW_MODEL -> {
          val nestedId = prop.viewModelId ?: return null
          val nested = viewModelsById[nestedId] ?: return null
          schema = nested.properties
        }
        ViewModelPropertyType.LIST -> return null
        else -> return null
      }
    }

    return viewModel.id to segments
  }

  private fun resolveNamePathIds(ref: VmPathRef, screenId: String?): Pair<String, List<PathSegment>>? {
    val pathIds = ref.pathIds
    if (pathIds.isEmpty()) return null

    val viewModel: ViewModel?
    val propertyIds: List<Int>

    if (ref.isRelative == true) {
      val instance = resolveInstance(screenId = screenId, viewModelId = null, instanceId = null) ?: return null
      viewModel = viewModelsById[instance.viewModelId]
      propertyIds = pathIds
    } else {
      val viewModelNameId = pathIds.first()
      viewModel = viewModelList.firstOrNull { hashNameId(it.name) == viewModelNameId }
      propertyIds = pathIds.drop(1)
    }

    if (viewModel == null || propertyIds.isEmpty()) return null

    var schema = viewModel.properties
    val segments = mutableListOf<PathSegment>()

    for ((idx, nameId) in propertyIds.withIndex()) {
      val found = findPropertyByNameId(schema, nameId) ?: return null
      segments.add(PathSegment.Prop(found.first))

      if (idx == propertyIds.size - 1) continue
      val prop = found.second
      when (prop.type) {
        ViewModelPropertyType.OBJECT -> schema = prop.schema ?: return null
        ViewModelPropertyType.VIEW_MODEL -> {
          val nestedId = prop.viewModelId ?: return null
          val nested = viewModelsById[nestedId] ?: return null
          schema = nested.properties
        }
        ViewModelPropertyType.LIST -> return null
        else -> return null
      }
    }

    return viewModel.id to segments
  }

  private fun findPropertyById(schema: Map<String, ViewModelProperty>, propertyId: Int): Pair<String, ViewModelProperty>? {
    for ((name, prop) in schema) {
      if (prop.propertyId == propertyId) return name to prop
    }
    return null
  }

  private fun findPropertyByNameId(schema: Map<String, ViewModelProperty>, nameId: Int): Pair<String, ViewModelProperty>? {
    for ((name, prop) in schema) {
      if (hashNameId(name) == nameId) return name to prop
    }
    return null
  }

  private fun resolveProperty(inSchema: Map<String, ViewModelProperty>, segments: List<PathSegment>): ViewModelProperty? {
    val first = segments.firstOrNull() ?: return null
    if (first !is PathSegment.Prop) return null
    val property = inSchema[first.name] ?: return null
    val remaining = segments.drop(1)
    return resolveProperty(property, remaining)
  }

  private fun resolveProperty(property: ViewModelProperty, remaining: List<PathSegment>): ViewModelProperty? {
    if (remaining.isEmpty()) return property

    return when (property.type) {
      ViewModelPropertyType.OBJECT -> {
        val schema = property.schema ?: return null
        resolveProperty(schema, remaining)
      }
      ViewModelPropertyType.VIEW_MODEL -> {
        val viewModelId = property.viewModelId ?: return null
        val vm = viewModelsById[viewModelId] ?: return null
        resolveProperty(vm.properties, remaining)
      }
      ViewModelPropertyType.LIST -> {
        val next = remaining.firstOrNull() ?: return null
        if (next !is PathSegment.Index) return null
        val itemType = property.itemType ?: return null
        resolveProperty(itemType, remaining.drop(1))
      }
      else -> null
    }
  }

  // MARK: - Nested Value Helpers

  private fun resolveLiteralValue(value: JsonElement): JsonElement {
    val obj = value as? JsonObject ?: return value
    if (obj.size == 1 && obj["literal"] != null) {
      return obj["literal"] ?: JsonNull
    }
    return value
  }

  private fun resolveIndex(expr: String): Int? {
    return expr.trim().toIntOrNull()
  }

  private fun setNestedValue(
    current: JsonElement,
    segments: List<PathSegment>,
    value: JsonElement,
    screenId: String?,
  ): JsonElement {
    val segment = segments.firstOrNull() ?: return value

    return when (segment) {
      is PathSegment.Prop -> {
        val base = (current as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val existing = base[segment.name] ?: JsonNull
        base[segment.name] = setNestedValue(existing, segments.drop(1), value, screenId)
        JsonObject(base)
      }
      is PathSegment.Index -> {
        val idx = resolveIndex(segment.expr) ?: return current
        val list = (current as? JsonArray)?.toMutableList() ?: mutableListOf()
        while (list.size <= idx) list.add(JsonNull)
        list[idx] = setNestedValue(list[idx], segments.drop(1), value, screenId)
        JsonArray(list)
      }
    }
  }

  // MARK: - Defaults

  private fun applyViewModelDefaults(instanceId: String) {
    val instance = instancesById[instanceId] ?: return
    val vm = viewModelsById[instance.viewModelId] ?: return

    val values = instance.values.toMutableMap()
    applyDefaults(schema = vm.properties, target = values)
    instance.values = values
    instancesById[instanceId] = instance
  }

  private fun applyDefaults(schema: Map<String, ViewModelProperty>, target: MutableMap<String, JsonElement>) {
    for ((key, property) in schema) {
      val explicitDefault = property.defaultValue
      if (explicitDefault != null) {
        if (!target.containsKey(key)) {
          target[key] = explicitDefault
        }
        continue
      }

      if (property.type == ViewModelPropertyType.OBJECT && property.schema != null) {
        val existing = target[key] as? JsonObject
        val nested = existing?.toMutableMap() ?: mutableMapOf()
        applyDefaults(schema = property.schema, target = nested)
        target[key] = JsonObject(nested)
        continue
      }

      if (!target.containsKey(key)) {
        val fallback = defaultValueFor(property)
        if (fallback != null) target[key] = fallback
      }
    }
  }

  private fun defaultValueFor(property: ViewModelProperty): JsonElement? {
    property.defaultValue?.let { return it }
    return when (property.type) {
      ViewModelPropertyType.LIST -> JsonArray(emptyList())
      ViewModelPropertyType.OBJECT -> JsonObject(emptyMap())
      ViewModelPropertyType.VIEW_MODEL -> JsonObject(emptyMap())
      ViewModelPropertyType.ENUM -> JsonPrimitive(property.enumValues?.firstOrNull() ?: "")
      ViewModelPropertyType.STRING,
      ViewModelPropertyType.COLOR,
      ViewModelPropertyType.IMAGE,
      -> JsonPrimitive("")
      ViewModelPropertyType.NUMBER,
      ViewModelPropertyType.LIST_INDEX,
      ViewModelPropertyType.TRIGGER,
      -> JsonPrimitive(0)
      ViewModelPropertyType.BOOLEAN -> JsonPrimitive(false)
    }
  }

  private fun createBlankInstance(forViewModelId: String): InstanceState {
    val instanceId = "${forViewModelId}_default"
    val values = mutableMapOf<String, JsonElement>()
    val vm = viewModelsById[forViewModelId]
    if (vm != null) {
      applyDefaults(vm.properties, values)
    }
    return InstanceState(
      viewModelId = forViewModelId,
      instanceId = instanceId,
      name = "default",
      values = values,
    )
  }

  // MARK: - list_index Support

  private fun resolveListItemInstanceId(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return null
    return (obj["vmInstanceId"] as? JsonPrimitive)?.contentOrNull
      ?: (obj["instanceId"] as? JsonPrimitive)?.contentOrNull
  }

  private fun syncListIndexValues(viewModel: ViewModel?, segments: List<PathSegment>, listValue: JsonElement) {
    val vm = viewModel ?: return
    val list = (listValue as? JsonArray)?.toList() ?: return

    val property = resolveProperty(inSchema = vm.properties, segments = segments) ?: return
    if (property.type != ViewModelPropertyType.LIST) return

    val itemType = property.itemType ?: return
    if (itemType.type != ViewModelPropertyType.VIEW_MODEL) return
    val itemViewModelId = itemType.viewModelId ?: return
    val itemVm = viewModelsById[itemViewModelId] ?: return

    val indexKeys = itemVm.properties.filter { (_, prop) -> prop.type == ViewModelPropertyType.LIST_INDEX }.keys
    if (indexKeys.isEmpty()) return

    for ((index, entry) in list.withIndex()) {
      val instanceId = resolveListItemInstanceId(entry) ?: continue
      val instance = instancesById[instanceId] ?: continue
      var didChange = false
      for (key in indexKeys) {
        val current = (instance.values[key] as? JsonPrimitive)?.intOrNull
        if (current != index) {
          instance.values[key] = JsonPrimitive(index)
          didChange = true
        }
      }
      if (didChange) {
        instancesById[instanceId] = instance
      }
    }
  }

  // MARK: - Name ID Hashing

  private fun viewModelPathId(viewModel: ViewModel): Int {
    return viewModel.viewModelPathId ?: hashNameId(viewModel.id)
  }

  private fun hashNameId(value: String): Int {
    if (value.isEmpty()) return FNV_OFFSET_BASIS.toInt()
    var hash = FNV_OFFSET_BASIS
    val bytes = value.toByteArray(Charsets.UTF_8)
    for (b in bytes) {
      hash = hash xor (b.toInt() and 0xff).toUInt()
      hash *= FNV_PRIME
    }
    return hash.toInt()
  }

  private companion object {
    // FNV-1a 32-bit (matches iOS).
    val FNV_OFFSET_BASIS: UInt = 0x811c9dc5u
    val FNV_PRIME: UInt = 0x01000193u
  }
}
