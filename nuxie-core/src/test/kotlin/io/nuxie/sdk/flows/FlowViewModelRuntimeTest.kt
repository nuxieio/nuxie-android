package io.nuxie.sdk.flows

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowViewModelRuntimeTest {

  private fun sampleManifest(): BuildManifest {
    return BuildManifest(
      totalFiles = 1,
      totalSize = 1,
      contentHash = "sha256:abc",
      files = listOf(
        BuildManifestFile(path = "index.html", size = 1, contentType = "text/html"),
      ),
    )
  }

  private fun sampleFlow(
    viewModels: List<ViewModel>,
    instances: List<ViewModelInstance>? = null,
    screens: List<RemoteFlowScreen> = listOf(RemoteFlowScreen(id = "screen_1")),
  ): RemoteFlow {
    return RemoteFlow(
      id = "flow_1",
      bundle = FlowBundleRef(url = "https://example.com/flows/flow_1/", manifest = sampleManifest()),
      screens = screens,
      interactions = emptyMap(),
      viewModels = viewModels,
      viewModelInstances = instances,
      converters = null,
    )
  }

  private fun hashNameId(value: String): Int {
    // FNV-1a 32-bit, matches iOS and FlowViewModelRuntime implementation.
    val offsetBasis = 0x811c9dc5u
    val prime = 0x01000193u
    if (value.isEmpty()) return offsetBasis.toInt()
    var hash = offsetBasis
    for (b in value.toByteArray(Charsets.UTF_8)) {
      hash = hash xor (b.toInt() and 0xff).toUInt()
      hash *= prime
    }
    return hash.toInt()
  }

  @Test
  fun `creates blank instance and applies defaults`() = runTest {
    val vm = ViewModel(
      id = "vm_1",
      name = "Main",
      viewModelPathId = 100,
      properties = mapOf(
        "title" to ViewModelProperty(type = ViewModelPropertyType.STRING, propertyId = 1),
        "count" to ViewModelProperty(type = ViewModelPropertyType.NUMBER, propertyId = 2),
        "flag" to ViewModelProperty(type = ViewModelPropertyType.BOOLEAN, propertyId = 3),
        "items" to ViewModelProperty(type = ViewModelPropertyType.LIST, propertyId = 4),
        "kind" to ViewModelProperty(type = ViewModelPropertyType.ENUM, propertyId = 5, enumValues = listOf("a", "b")),
        "trigger" to ViewModelProperty(type = ViewModelPropertyType.TRIGGER, propertyId = 6),
        "obj" to ViewModelProperty(
          type = ViewModelPropertyType.OBJECT,
          propertyId = 7,
          schema = mapOf(
            "nested" to ViewModelProperty(type = ViewModelPropertyType.STRING, propertyId = 8),
          ),
        ),
      ),
    )

    val runtime = FlowViewModelRuntime(sampleFlow(viewModels = listOf(vm)))
    val instances = runtime.allInstances()
    assertEquals(1, instances.size)

    val inst = instances.first()
    assertEquals("vm_1_default", inst.instanceId)
    assertEquals("vm_1", inst.viewModelId)

    assertEquals(JsonPrimitive(""), inst.values["title"])
    assertEquals(JsonPrimitive(0), inst.values["count"])
    assertEquals(JsonPrimitive(false), inst.values["flag"])
    assertEquals(JsonArray(emptyList()), inst.values["items"])
    assertEquals(JsonPrimitive("a"), inst.values["kind"])
    assertEquals(JsonPrimitive(0), inst.values["trigger"])

    val obj = inst.values["obj"] as? JsonObject
    assertNotNull(obj)
    assertEquals(JsonPrimitive(""), obj!!["nested"])
  }

  @Test
  fun `setValue and getValue resolve ids and nameBased refs`() = runTest {
    val vm = ViewModel(
      id = "vm_1",
      name = "Main",
      viewModelPathId = 100,
      properties = mapOf(
        "title" to ViewModelProperty(type = ViewModelPropertyType.STRING, propertyId = 1),
        "trigger" to ViewModelProperty(type = ViewModelPropertyType.TRIGGER, propertyId = 2),
      ),
    )

    val runtime = FlowViewModelRuntime(sampleFlow(viewModels = listOf(vm)))

    val titlePath = VmPathRef(pathIds = listOf(100, 1))
    assertTrue(runtime.setValue(titlePath, JsonPrimitive("hello"), screenId = null))
    assertEquals(JsonPrimitive("hello"), runtime.getValue(titlePath, screenId = null))

    val triggerPath = VmPathRef(pathIds = listOf(100, 2))
    assertTrue(runtime.isTriggerPath(triggerPath, screenId = null))
    assertTrue(!runtime.isTriggerPath(titlePath, screenId = null))

    // Name-based path ids: [hash(viewModel.name), hash(propertyName)]
    val nameBasedTitle = VmPathRef(
      pathIds = listOf(hashNameId("Main"), hashNameId("title")),
      nameBased = true,
    )
    assertTrue(runtime.setValue(nameBasedTitle, JsonPrimitive("world"), screenId = null))
    assertEquals(JsonPrimitive("world"), runtime.getValue(titlePath, screenId = null))
  }

  @Test
  fun `list operations update list_index on referenced view-model items`() = runTest {
    val itemVm = ViewModel(
      id = "item_vm",
      name = "Item",
      viewModelPathId = 200,
      properties = mapOf(
        "idx" to ViewModelProperty(type = ViewModelPropertyType.LIST_INDEX, propertyId = 21),
        "name" to ViewModelProperty(type = ViewModelPropertyType.STRING, propertyId = 22),
      ),
    )

    val parentVm = ViewModel(
      id = "parent_vm",
      name = "Parent",
      viewModelPathId = 100,
      properties = mapOf(
        "items" to ViewModelProperty(
          type = ViewModelPropertyType.LIST,
          propertyId = 1,
          itemType = ViewModelProperty(type = ViewModelPropertyType.VIEW_MODEL, viewModelId = "item_vm"),
        ),
      ),
    )

    val item1 = ViewModelInstance(
      viewModelId = "item_vm",
      instanceId = "item_1",
      name = "one",
      values = mapOf(
        "idx" to JsonPrimitive(0),
        "name" to JsonPrimitive("a"),
      ),
    )
    val item2 = ViewModelInstance(
      viewModelId = "item_vm",
      instanceId = "item_2",
      name = "two",
      values = mapOf(
        "idx" to JsonPrimitive(0),
        "name" to JsonPrimitive("b"),
      ),
    )

    val parent = ViewModelInstance(
      viewModelId = "parent_vm",
      instanceId = "parent_vm_default",
      name = "default",
      values = mapOf(
        "items" to JsonArray(
          listOf(
            JsonObject(mapOf("vmInstanceId" to JsonPrimitive("item_2"))),
            JsonObject(mapOf("vmInstanceId" to JsonPrimitive("item_1"))),
          )
        ),
      ),
    )

    val runtime = FlowViewModelRuntime(
      sampleFlow(
        viewModels = listOf(parentVm, itemVm),
        instances = listOf(parent, item1, item2),
      )
    )

    val itemsPath = VmPathRef(pathIds = listOf(100, 1))
    val ok = runtime.setListValue(
      path = itemsPath,
      operation = "swap",
      payload = mapOf("from" to 0, "to" to 1),
      screenId = null,
    )
    assertTrue(ok)

    val itemInstances = runtime.allInstances().filter { it.viewModelId == "item_vm" }.associateBy { it.instanceId }
    assertEquals(JsonPrimitive(0), itemInstances["item_1"]!!.values["idx"])
    assertEquals(JsonPrimitive(1), itemInstances["item_2"]!!.values["idx"])

    val updatedList = runtime.getValue(itemsPath, screenId = null) as? JsonArray
    assertNotNull(updatedList)
    assertEquals("item_1", (updatedList!![0] as JsonObject)["vmInstanceId"]!!.toString().trim('"'))
    assertEquals("item_2", (updatedList[1] as JsonObject)["vmInstanceId"]!!.toString().trim('"'))

    // Clear list
    assertTrue(
      runtime.setListValue(
        path = itemsPath,
        operation = "clear",
        payload = emptyMap(),
        screenId = null,
      )
    )
    assertEquals(JsonArray(emptyList()), runtime.getValue(itemsPath, screenId = null))

    // Insert item and ensure it doesn't crash on index sync even when item lacks vmInstanceId.
    assertTrue(
      runtime.setListValue(
        path = itemsPath,
        operation = "insert",
        payload = mapOf("value" to JsonNull),
        screenId = null,
      )
    )
    val afterInsert = runtime.getValue(itemsPath, screenId = null) as? JsonArray
    assertNotNull(afterInsert)
    assertEquals(1, afterInsert!!.size)
  }
}

