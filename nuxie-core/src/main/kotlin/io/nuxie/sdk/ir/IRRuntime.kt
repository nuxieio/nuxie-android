package io.nuxie.sdk.ir

import io.nuxie.sdk.events.NuxieEvent

/**
 * Central place to build IR EvalContext + IRInterpreter consistently.
 *
 * Mirrors iOS `IRRuntime`.
 */
class IRRuntime(private val nowEpochMillis: () -> Long = { System.currentTimeMillis() }) {

  data class Config(
    val nowEpochMillis: Long? = null,
    val event: NuxieEvent? = null,
    val user: IRUserProps? = null,
    val events: IREventQueries? = null,
    val segments: IRSegmentQueries? = null,
    val features: IRFeatureQueries? = null,
    val journeyId: String? = null,
  )

  suspend fun makeContext(cfg: Config = Config()): EvalContext {
    return EvalContext(
      nowEpochMillis = cfg.nowEpochMillis ?: nowEpochMillis(),
      user = cfg.user,
      events = cfg.events,
      segments = cfg.segments,
      features = cfg.features,
      event = cfg.event,
      journeyId = cfg.journeyId,
    )
  }

  suspend fun makeInterpreter(cfg: Config = Config()): IRInterpreter {
    val ctx = makeContext(cfg)
    return IRInterpreter(ctx)
  }

  suspend fun eval(envelope: IREnvelope?, cfg: Config = Config()): Boolean {
    if (envelope == null) return true
    return runCatching {
      val interpreter = makeInterpreter(cfg)
      interpreter.evalBool(envelope.expr)
    }.getOrDefault(false)
  }
}

