package io.nuxie.sdk.ir

import io.nuxie.sdk.events.NuxieEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IRInterpreterTest {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "type"
  }

  @Test
  fun `decodes and evaluates basic boolean composition`() = runTest {
    val raw = """
      {
        "ir_version": 1,
        "expr": {
          "type": "And",
          "args": [
            { "type": "Bool", "value": true },
            { "type": "Not", "arg": { "type": "Bool", "value": false } }
          ]
        }
      }
    """.trimIndent()

    val env = json.decodeFromString(IREnvelope.serializer(), raw)
    val runtime = IRRuntime(nowEpochMillis = { 1_700_000_000_000L })
    val ok = runtime.eval(env)
    assertTrue(ok)
  }

  @Test
  fun `compares numbers`() = runTest {
    val env = IREnvelope(
      irVersion = 1,
      expr = IRExpr.Compare(
        op = ">",
        left = IRExpr.Number(2.0),
        right = IRExpr.Number(1.0),
      )
    )

    val ctx = EvalContext(nowEpochMillis = 0L)
    val interpreter = IRInterpreter(ctx)
    assertTrue(interpreter.evalBool(env.expr))
  }

  @Test
  fun `user property eq works`() = runTest {
    val expr = IRExpr.User(op = "eq", key = "plan", value = IRExpr.String("pro"))
    val ctx = EvalContext(
      nowEpochMillis = 0L,
      user = IRUserProps { key -> if (key == "plan") "pro" else null },
    )
    val interpreter = IRInterpreter(ctx)
    assertTrue(interpreter.evalBool(expr))
  }

  @Test
  fun `event predicate resolves dotted properties`() = runTest {
    val event = NuxieEvent(
      name = "purchase",
      distinctId = "u1",
      properties = mapOf(
        "amount" to 10,
        "nested" to mapOf("value" to "yes"),
      ),
      timestamp = "2025-01-01T00:00:00.000Z",
    )

    val expr = IRExpr.Pred(op = "eq", key = "properties.nested.value", value = IRExpr.String("yes"))
    val ctx = EvalContext(nowEpochMillis = 0L, event = event)
    val interpreter = IRInterpreter(ctx)
    assertTrue(interpreter.evalBool(expr))
  }

  @Test
  fun `segment entered_within works`() = runTest {
    val now = 1_700_000_000_000L
    val enteredAt = now - (30 * 1000L)

    val expr = IRExpr.Segment(op = "entered_within", id = "seg1", within = IRExpr.Duration(60.0))
    val ctx = EvalContext(
      nowEpochMillis = now,
      segments = object : IRSegmentQueries {
        override suspend fun isMember(segmentId: String): Boolean = segmentId == "seg1"
        override suspend fun enteredAtEpochMillis(segmentId: String): Long? = if (segmentId == "seg1") enteredAt else null
      },
    )

    val interpreter = IRInterpreter(ctx)
    assertTrue(interpreter.evalBool(expr))
  }

  @Test
  fun `segment entered_within false when no enteredAt`() = runTest {
    val now = 1_700_000_000_000L
    val expr = IRExpr.Segment(op = "entered_within", id = "seg1", within = IRExpr.Duration(60.0))
    val ctx = EvalContext(
      nowEpochMillis = now,
      segments = object : IRSegmentQueries {
        override suspend fun isMember(segmentId: String): Boolean = true
        override suspend fun enteredAtEpochMillis(segmentId: String): Long? = null
      },
    )
    val interpreter = IRInterpreter(ctx)
    assertFalse(interpreter.evalBool(expr))
  }
}

