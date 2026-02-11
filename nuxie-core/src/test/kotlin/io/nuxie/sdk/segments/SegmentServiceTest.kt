package io.nuxie.sdk.segments

import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.ir.IRExpr
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentServiceTest {

  @Test
  fun `evaluates segments and tracks enter and exit`() = runTest {
    var now = 1_000L

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u1") }
    identity.setUserProperties(mapOf("plan" to "pro"))

    val runtime = IRRuntime(nowEpochMillis = { now })
    val service = SegmentService(
      identityService = identity,
      events = null,
      irRuntime = runtime,
      scope = this,
      nowEpochMillis = { now },
      evaluationIntervalMillis = Long.MAX_VALUE,
      enableMonitoring = false,
    )

    val segment = Segment(
      id = "s1",
      name = "Pro Users",
      condition = IREnvelope(
        irVersion = 1,
        expr = IRExpr.User(op = "eq", key = "plan", value = IRExpr.String("pro")),
      ),
    )

    val first = service.updateSegments(listOf(segment))
    assertEquals(listOf("s1"), first.entered.map { it.id })
    assertEquals(emptyList<String>(), first.exited.map { it.id })

    now = 2_000L
    identity.setUserProperties(mapOf("plan" to "free"))

    val second = service.evaluateNow()
    assertEquals(emptyList<String>(), second.entered.map { it.id })
    assertEquals(listOf("s1"), second.exited.map { it.id })
  }
}
