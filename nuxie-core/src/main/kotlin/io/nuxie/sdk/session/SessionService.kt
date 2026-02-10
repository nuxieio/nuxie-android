package io.nuxie.sdk.session

import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.util.UuidV7
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface SessionService {
  fun getSessionId(readOnly: Boolean = false): String?
  fun getNextSessionId(): String?
  fun setSessionId(sessionId: String)
  fun startSession()
  fun endSession()
  fun resetSession()
  fun touchSession()
  fun onAppDidEnterBackground()
  fun onAppBecameActive()

  var onSessionIdChanged: ((SessionIdChangeReason) -> Unit)?
}

enum class SessionIdChangeReason {
  SESSION_ID_EMPTY,
  SESSION_START,
  SESSION_END,
  SESSION_RESET,
  SESSION_TIMEOUT,
  SESSION_PAST_MAXIMUM_LENGTH,
  CUSTOM_SESSION_ID,
}

interface TimeProvider {
  fun nowEpochSeconds(): Long

  companion object {
    fun system(): TimeProvider = object : TimeProvider {
      override fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L
    }
  }
}

class DefaultSessionService(
  private val timeProvider: TimeProvider = TimeProvider.system(),
) : SessionService {
  private val lock = ReentrantLock()

  private val sessionActivityThresholdSeconds: Long = 60L * 30L
  private val sessionMaxLengthThresholdSeconds: Long = 24L * 60L * 60L

  private var sessionId: String? = null
  private var sessionStartEpochSeconds: Long? = null
  private var sessionActivityEpochSeconds: Long? = null
  private var isAppInBackground: Boolean = false

  override var onSessionIdChanged: ((SessionIdChangeReason) -> Unit)? = null

  override fun getSessionId(readOnly: Boolean): String? = lock.withLock {
    val now = timeProvider.nowEpochSeconds()
    val reason = shouldStartNewSessionLocked(now)
    if (reason != null && !readOnly) {
      createNewSessionLocked(now, reason)
    }

    if (sessionId != null && !readOnly) {
      sessionActivityEpochSeconds = now
    }

    sessionId
  }

  override fun getNextSessionId(): String? = generateSessionId()

  override fun setSessionId(sessionId: String) {
    lock.withLock {
      val now = timeProvider.nowEpochSeconds()
      this.sessionId = sessionId
      sessionStartEpochSeconds = now
      sessionActivityEpochSeconds = now
      NuxieLogger.info("Custom session ID set: $sessionId")
      onSessionIdChanged?.invoke(SessionIdChangeReason.CUSTOM_SESSION_ID)
    }
  }

  override fun startSession() = lock.withLock {
    val now = timeProvider.nowEpochSeconds()
    createNewSessionLocked(now, SessionIdChangeReason.SESSION_START)
  }

  override fun endSession() = lock.withLock {
    clearSessionLocked(SessionIdChangeReason.SESSION_END)
  }

  override fun resetSession() = lock.withLock {
    val now = timeProvider.nowEpochSeconds()
    clearSessionLocked(SessionIdChangeReason.SESSION_RESET)
    createNewSessionLocked(now, SessionIdChangeReason.SESSION_RESET)
  }

  override fun touchSession() = lock.withLock {
    val now = timeProvider.nowEpochSeconds()
    val reason = shouldStartNewSessionLocked(now)
    if (reason != null) {
      if (isAppInBackground) {
        clearSessionLocked(reason)
      } else {
        createNewSessionLocked(now, reason)
      }
      return
    }

    if (sessionId != null) {
      sessionActivityEpochSeconds = now
    }
  }

  override fun onAppDidEnterBackground() = lock.withLock {
    isAppInBackground = true
    NuxieLogger.debug("App entered background, session: ${sessionId ?: "none"}")
  }

  override fun onAppBecameActive() = lock.withLock {
    isAppInBackground = false
    val now = timeProvider.nowEpochSeconds()
    val reason = shouldStartNewSessionLocked(now)
    if (reason != null) {
      createNewSessionLocked(now, reason)
    }
    NuxieLogger.debug("App became active, session: ${sessionId ?: "none"}")
  }

  private fun shouldStartNewSessionLocked(atEpochSeconds: Long): SessionIdChangeReason? {
    if (sessionId == null) return SessionIdChangeReason.SESSION_ID_EMPTY

    val start = sessionStartEpochSeconds
    if (start != null && atEpochSeconds - start > sessionMaxLengthThresholdSeconds) {
      return SessionIdChangeReason.SESSION_PAST_MAXIMUM_LENGTH
    }

    val last = sessionActivityEpochSeconds
    if (last != null && atEpochSeconds - last > sessionActivityThresholdSeconds) {
      return SessionIdChangeReason.SESSION_TIMEOUT
    }

    return null
  }

  private fun createNewSessionLocked(atEpochSeconds: Long, reason: SessionIdChangeReason) {
    val newId = generateSessionId()
    sessionId = newId
    sessionStartEpochSeconds = atEpochSeconds
    sessionActivityEpochSeconds = atEpochSeconds
    NuxieLogger.info("New session created: $newId (reason: $reason)")
    onSessionIdChanged?.invoke(reason)
  }

  private fun clearSessionLocked(reason: SessionIdChangeReason) {
    sessionId = null
    sessionStartEpochSeconds = null
    sessionActivityEpochSeconds = null
    NuxieLogger.info("Session cleared (reason: $reason)")
    onSessionIdChanged?.invoke(reason)
  }

  // iOS uses a raw UUIDv7 string (no prefix). Keep parity.
  private fun generateSessionId(): String = UuidV7.generateString()
}
