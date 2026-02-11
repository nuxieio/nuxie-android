package io.nuxie.sdk.triggers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerBrokerTest {

  @Test
  fun registerEmitCompleteLifecycle() = runBlocking {
    val broker: TriggerBroker = DefaultTriggerBroker()
    val seen = mutableListOf<TriggerUpdate>()

    broker.register(eventId = "evt_1") { update ->
      seen += update
    }

    broker.emit("evt_1", TriggerUpdate.Decision(TriggerDecision.NoMatch))
    assertEquals(1, seen.size)

    broker.complete("evt_1")
    broker.emit("evt_1", TriggerUpdate.Decision(TriggerDecision.NoMatch))
    assertEquals(1, seen.size)
  }

  @Test
  fun resetClearsHandlers() = runBlocking {
    val broker: TriggerBroker = DefaultTriggerBroker()
    val seen = mutableListOf<TriggerUpdate>()

    broker.register(eventId = "evt_1") { update -> seen += update }
    broker.emit("evt_1", TriggerUpdate.Decision(TriggerDecision.NoMatch))
    assertEquals(1, seen.size)

    broker.reset()
    broker.emit("evt_1", TriggerUpdate.Decision(TriggerDecision.NoMatch))
    assertEquals(1, seen.size)
  }
}

