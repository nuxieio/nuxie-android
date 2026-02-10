package io.nuxie.sdk.session

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTimeProvider(var now: Long) : TimeProvider {
  override fun nowEpochSeconds(): Long = now
}

class DefaultSessionServiceTest {
  @Test
  fun get_session_id_readonly_does_not_create_session() {
    val time = FakeTimeProvider(now = 1000)
    val s = DefaultSessionService(timeProvider = time)
    assertNull(s.getSessionId(readOnly = true))
  }

  @Test
  fun session_rotates_after_inactivity_timeout() {
    val time = FakeTimeProvider(now = 1000)
    val s = DefaultSessionService(timeProvider = time)

    val first = s.getSessionId(readOnly = false)
    assertTrue(!first.isNullOrBlank())

    // Advance beyond 30 minutes.
    time.now += (60L * 30L) + 1L
    s.touchSession()
    val second = s.getSessionId(readOnly = false)
    assertNotEquals(first, second)
  }

  @Test
  fun session_is_cleared_in_background_when_rotation_is_needed() {
    val time = FakeTimeProvider(now = 1000)
    val s = DefaultSessionService(timeProvider = time)

    val first = s.getSessionId(readOnly = false)
    assertTrue(!first.isNullOrBlank())

    s.onAppDidEnterBackground()
    time.now += (60L * 30L) + 1L
    s.touchSession()

    // Should have been cleared due to background.
    assertNull(s.getSessionId(readOnly = true))

    // Foreground should create a new session.
    s.onAppBecameActive()
    val second = s.getSessionId(readOnly = false)
    assertTrue(!second.isNullOrBlank())
    assertNotEquals(first, second)
  }
}

