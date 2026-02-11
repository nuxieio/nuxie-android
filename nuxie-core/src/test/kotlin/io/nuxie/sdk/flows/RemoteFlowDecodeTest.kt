package io.nuxie.sdk.flows

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFlowDecodeTest {
  @Test
  fun `decodes interactions actions viewModels and path refs`() = runTest {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    val raw = """
      {
        "id": "flow_1",
        "bundle": {
          "url": "https://example.com/flows/flow_1/",
          "manifest": {
            "totalFiles": 1,
            "totalSize": 10,
            "contentHash": "sha256:abc",
            "files": [
              { "path": "index.html", "size": 10, "contentType": "text/html" }
            ]
          }
        },
        "screens": [{ "id": "screen_1" }],
        "interactions": {
          "screen_1": [
            {
              "id": "i1",
              "trigger": { "type": "press" },
              "actions": [
                { "type": "navigate", "screenId": "screen_2" }
              ]
            },
            {
              "id": "i2",
              "trigger": {
                "type": "did_set",
                "path": { "kind": "ids", "pathIds": [1, 2, 3], "isRelative": true },
                "debounceMs": 100
              },
              "actions": [
                { "type": "set_view_model", "path": { "pathIds": [1, 2, 3] }, "value": { "literal": "ok" } }
              ]
            },
            {
              "id": "i3",
              "trigger": { "type": "unknown_trigger", "foo": "bar" },
              "actions": [
                { "type": "unknown_action", "foo": 1 }
              ]
            }
          ]
        },
        "viewModels": [
          {
            "id": "vm_1",
            "name": "Main",
            "properties": {
              "title": { "type": "string", "propertyId": 11, "defaultValue": "hi" }
            }
          }
        ],
        "viewModelInstances": [
          {
            "viewModelId": "vm_1",
            "instanceId": "vm_1_default",
            "name": "default",
            "values": { "title": "hello" }
          }
        ]
      }
    """.trimIndent()

    val flow = json.decodeFromString(RemoteFlow.serializer(), raw)
    assertEquals("flow_1", flow.id)
    assertEquals(1, flow.screens.size)
    assertEquals(1, flow.viewModels.size)
    assertNotNull(flow.viewModelInstances)
    assertEquals(1, flow.viewModelInstances!!.size)

    val interactions = flow.interactions["screen_1"]
    assertNotNull(interactions)
    assertEquals(3, interactions!!.size)

    val press = interactions[0]
    assertTrue(press.trigger is InteractionTrigger.Press)
    assertEquals(1, press.actions.size)
    val nav = press.actions[0] as InteractionAction.Navigate
    assertEquals("screen_2", nav.screenId)

    val didSet = interactions[1]
    val didSetTrigger = didSet.trigger as InteractionTrigger.DidSet
    assertEquals(listOf(1, 2, 3), didSetTrigger.path.pathIds)
    assertEquals(true, didSetTrigger.path.isRelative)
    assertEquals("ids:rel:1.2.3", didSetTrigger.path.normalizedPath)
    val setVm = didSet.actions[0] as InteractionAction.SetViewModel
    assertEquals(listOf(1, 2, 3), setVm.path.pathIds)
    assertEquals("ok", setVm.value.jsonObject["literal"].toString().trim('"'))

    val unknown = interactions[2]
    val unknownTrigger = unknown.trigger as InteractionTrigger.Unknown
    assertEquals("unknown_trigger", unknownTrigger.type)
    assertTrue(unknownTrigger.payload.containsKey("foo"))

    val unknownAction = unknown.actions[0] as InteractionAction.Unknown
    assertEquals("unknown_action", unknownAction.type)
    assertTrue(unknownAction.payload.containsKey("foo"))
  }
}

