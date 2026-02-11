package io.nuxie.sdk.network.models

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileResponseDecodeTest {
  @Test
  fun `decodes campaigns segments and IR with default Json config`() = runTest {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    val raw = """
      {
        "campaigns": [
          {
            "id": "c1",
            "name": "Test Campaign",
            "flowId": "flow_1",
            "flowNumber": 1,
            "flowName": "Flow Name",
            "reentry": { "type": "one_time" },
            "publishedAt": "2025-01-01T00:00:00.000Z",
            "trigger": {
              "type": "event",
              "config": {
                "eventName": "purchase",
                "condition": {
                  "ir_version": 1,
                  "expr": { "type": "Bool", "value": true }
                }
              }
            }
          }
        ],
        "segments": [
          {
            "id": "s1",
            "name": "Test Segment",
            "condition": {
              "ir_version": 1,
              "expr": { "type": "Bool", "value": true }
            }
          }
        ],
        "flows": []
      }
    """.trimIndent()

    val decoded = json.decodeFromString(ProfileResponse.serializer(), raw)
    assertEquals(1, decoded.campaigns.size)
    assertEquals("c1", decoded.campaigns.first().id)
    assertNotNull(decoded.campaigns.first().trigger)
    assertEquals(1, decoded.segments.size)
    assertEquals("s1", decoded.segments.first().id)
    assertNotNull(decoded.segments.first().condition.expr)
  }
}

