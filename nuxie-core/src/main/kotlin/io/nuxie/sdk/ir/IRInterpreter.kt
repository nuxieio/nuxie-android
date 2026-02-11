package io.nuxie.sdk.ir

import io.nuxie.sdk.events.NuxieEvent
import java.util.Locale

/**
 * Interpreter for evaluating IR expressions.
 *
 * Mirrors iOS `IRInterpreter`.
 */
class IRInterpreter(private val ctx: EvalContext) {

  suspend fun evalBool(expr: IRExpr): Boolean {
    return when (expr) {
      is IRExpr.Bool -> expr.value

      is IRExpr.And -> {
        for (e in expr.args) {
          if (!evalBool(e)) return false
        }
        true
      }

      is IRExpr.Or -> {
        for (e in expr.args) {
          if (evalBool(e)) return true
        }
        false
      }

      is IRExpr.Not -> !evalBool(expr.arg)

      is IRExpr.Compare -> {
        val leftValue = evalValue(expr.left)
        val rightValue = evalValue(expr.right)
        compareValues(expr.op, leftValue, rightValue)
      }

      is IRExpr.User -> evalUser(expr.op, expr.key, expr.value)

      is IRExpr.Event -> {
        val event = ctx.event ?: return false
        evalEvent(expr.op, expr.key, expr.value, event)
      }

      is IRExpr.Segment -> {
        val segments = ctx.segments ?: return false
        when (expr.op) {
          "is_member", "in" -> segments.isMember(expr.id)
          "not_member", "not_in" -> !segments.isMember(expr.id)
          "entered_within" -> {
            val withinExpr = expr.within ?: return false
            val durationSeconds = evalDuration(withinExpr)
            val enteredAt = segments.enteredAtEpochMillis(expr.id) ?: return false
            (ctx.nowEpochMillis - enteredAt) <= (durationSeconds * 1000.0)
          }
          else -> false
        }
      }

      is IRExpr.Feature -> evalFeature(expr.op, expr.id, expr.value)

      is IRExpr.EventsExists -> {
        val events = ctx.events ?: return false
        val (s, u) = window(expr.since, expr.until, expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        events.exists(expr.name, s, u, predicate)
      }

      is IRExpr.EventsCount -> {
        val events = ctx.events ?: return false
        val (s, u) = window(expr.since, expr.until, expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        events.count(expr.name, s, u, predicate) > 0
      }

      is IRExpr.EventsFirstTime -> {
        val events = ctx.events ?: return false
        val predicate = exprToPredicate(expr.whereExpr)
        events.firstTime(expr.name, predicate) != null
      }

      is IRExpr.EventsLastTime -> {
        val events = ctx.events ?: return false
        val predicate = exprToPredicate(expr.whereExpr)
        events.lastTime(expr.name, predicate) != null
      }

      is IRExpr.EventsLastAge -> {
        val events = ctx.events ?: return false
        val predicate = exprToPredicate(expr.whereExpr)
        val last = events.lastTime(expr.name, predicate) ?: return false
        (ctx.nowEpochMillis - last) >= 0
      }

      is IRExpr.EventsAggregate -> {
        val events = ctx.events ?: return false
        val (s, u) = window(expr.since, expr.until, expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        val aggType = when (expr.agg.lowercase(Locale.US)) {
          "sum" -> Aggregate.SUM
          "avg" -> Aggregate.AVG
          "min" -> Aggregate.MIN
          "max" -> Aggregate.MAX
          "unique" -> Aggregate.UNIQUE
          else -> return false
        }
        val value = events.aggregate(aggType, expr.name, expr.prop, s, u, predicate)
        (value ?: 0.0) != 0.0
      }

      is IRExpr.EventsInOrder -> {
        val events = ctx.events ?: return false
        val (s, u) = window(expr.since, expr.until, within = null)
        val overall = expr.overallWithin?.let { evalDuration(it) }
        val perStep = expr.perStepWithin?.let { evalDuration(it) }
        val stepQueries = expr.steps.map { step ->
          StepQuery(name = step.name, predicate = exprToPredicate(step.whereExpr))
        }
        events.inOrder(stepQueries, overall, perStep, s, u)
      }

      is IRExpr.EventsActivePeriods -> {
        val events = ctx.events ?: return false
        val predicate = exprToPredicate(expr.whereExpr)
        val period = when (expr.period.lowercase(Locale.US)) {
          "day" -> Period.DAY
          "week" -> Period.WEEK
          "month" -> Period.MONTH
          "year" -> Period.YEAR
          else -> return false
        }
        events.activePeriods(expr.name, period, expr.totalPeriods, expr.minPeriods, predicate)
      }

      is IRExpr.EventsStopped -> {
        val events = ctx.events ?: return false
        val durationSeconds = evalDuration(expr.inactiveFor)
        val predicate = exprToPredicate(expr.whereExpr)
        events.stopped(expr.name, durationSeconds, predicate)
      }

      is IRExpr.EventsRestarted -> {
        val events = ctx.events ?: return false
        val inactiveSeconds = evalDuration(expr.inactiveFor)
        val withinSeconds = evalDuration(expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        events.restarted(expr.name, inactiveSeconds, withinSeconds, predicate)
      }

      // Values in boolean position -> truthy
      is IRExpr.TimeNow,
      is IRExpr.TimeAgo,
      is IRExpr.TimeWindow,
      is IRExpr.JourneyId,
      is IRExpr.Number,
      is IRExpr.String,
      is IRExpr.Timestamp,
      is IRExpr.Duration,
      is IRExpr.List,
      -> evalValue(expr).isTruthy

      is IRExpr.Pred -> {
        val event = ctx.event ?: return false
        evalPredicate(expr.op, expr.key, expr.value, event)
      }

      is IRExpr.PredAnd -> {
        if (ctx.event == null) return false
        for (p in expr.args) {
          if (!evalBool(p)) return false
        }
        true
      }

      is IRExpr.PredOr -> {
        if (ctx.event == null) return false
        for (p in expr.args) {
          if (evalBool(p)) return true
        }
        false
      }
    }
  }

  suspend fun evalValue(expr: IRExpr): IRValue {
    return when (expr) {
      is IRExpr.Bool -> IRValue.Bool(expr.value)
      is IRExpr.Number -> IRValue.Number(expr.value)
      is IRExpr.String -> IRValue.String(expr.value)
      is IRExpr.Timestamp -> IRValue.Timestamp(expr.value)
      is IRExpr.Duration -> IRValue.Duration(expr.value)
      is IRExpr.List -> IRValue.List(expr.value.map { evalValue(it) })

      is IRExpr.TimeNow -> IRValue.Timestamp(ctx.nowEpochMillis / 1000.0)

      is IRExpr.TimeAgo -> {
        val durationSeconds = evalDuration(expr.duration)
        IRValue.Timestamp((ctx.nowEpochMillis / 1000.0) - durationSeconds)
      }

      is IRExpr.TimeWindow -> IRValue.Duration(toSeconds(expr.value, expr.interval))

      is IRExpr.JourneyId -> {
        val journeyId = ctx.journeyId ?: return IRValue.Null
        IRValue.String(journeyId)
      }

      is IRExpr.EventsCount -> {
        val events = ctx.events ?: return IRValue.Number(0.0)
        val (s, u) = window(expr.since, expr.until, expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        IRValue.Number(events.count(expr.name, s, u, predicate).toDouble())
      }

      is IRExpr.EventsAggregate -> {
        val events = ctx.events ?: return IRValue.Number(0.0)
        val (s, u) = window(expr.since, expr.until, expr.within)
        val predicate = exprToPredicate(expr.whereExpr)
        val aggType = when (expr.agg.lowercase(Locale.US)) {
          "sum" -> Aggregate.SUM
          "avg" -> Aggregate.AVG
          "min" -> Aggregate.MIN
          "max" -> Aggregate.MAX
          "unique" -> Aggregate.UNIQUE
          else -> return IRValue.Number(0.0)
        }
        val value = events.aggregate(aggType, expr.name, expr.prop, s, u, predicate)
        IRValue.Number(value ?: 0.0)
      }

      is IRExpr.EventsFirstTime -> {
        val events = ctx.events ?: return IRValue.Null
        val predicate = exprToPredicate(expr.whereExpr)
        val first = events.firstTime(expr.name, predicate) ?: return IRValue.Null
        IRValue.Timestamp(first / 1000.0)
      }

      is IRExpr.EventsLastTime -> {
        val events = ctx.events ?: return IRValue.Null
        val predicate = exprToPredicate(expr.whereExpr)
        val last = events.lastTime(expr.name, predicate) ?: return IRValue.Null
        IRValue.Timestamp(last / 1000.0)
      }

      is IRExpr.EventsLastAge -> {
        val events = ctx.events ?: return IRValue.Null
        val predicate = exprToPredicate(expr.whereExpr)
        val last = events.lastTime(expr.name, predicate) ?: return IRValue.Null
        val ageSeconds = (ctx.nowEpochMillis - last) / 1000.0
        IRValue.Duration(ageSeconds)
      }

      else -> throw IRError.TypeMismatch(expected = "value node", got = expr.javaClass.simpleName)
    }
  }

  private suspend fun evalDuration(expr: IRExpr): Double {
    return when (val value = evalValue(expr)) {
      is IRValue.Duration -> value.value
      is IRValue.Number -> value.value
      else -> throw IRError.TypeMismatch(expected = "duration or number", got = value.javaClass.simpleName)
    }
  }

  private suspend fun window(since: IRExpr?, until: IRExpr?, within: IRExpr?): Pair<Long?, Long?> {
    var sinceMillis: Long? = null
    var untilMillis: Long? = null

    if (since != null) {
      val value = evalValue(since)
      if (value is IRValue.Timestamp) sinceMillis = (value.value * 1000.0).toLong()
    }

    if (until != null) {
      val value = evalValue(until)
      if (value is IRValue.Timestamp) untilMillis = (value.value * 1000.0).toLong()
    }

    if (within != null) {
      val value = evalValue(within)
      if (value is IRValue.Duration) {
        val start = ctx.nowEpochMillis - (value.value * 1000.0).toLong()
        sinceMillis = if (sinceMillis != null) kotlin.math.max(sinceMillis, start) else start
      }
    }

    return sinceMillis to untilMillis
  }

  private fun compareValues(op: String, left: IRValue, right: IRValue): Boolean {
    val compareOp = when (op) {
      "==" -> CompareOp.EQ
      "!=" -> CompareOp.NEQ
      ">" -> CompareOp.GT
      ">=" -> CompareOp.GTE
      "<" -> CompareOp.LT
      "<=" -> CompareOp.LTE
      else -> null
    }
    if (compareOp != null) {
      return Comparer.compare(compareOp, left.toAny(), right.toAny())
    }

    return when (op) {
      "in" -> Comparer.compare(CompareOp.IN, left.toAny(), right.toAny())
      "not_in" -> Comparer.compare(CompareOp.NOT_IN, left.toAny(), right.toAny())
      else -> false
    }
  }

  private fun toSeconds(value: Int, interval: String): Double {
    return when (interval.lowercase(Locale.US)) {
      "hour" -> value * 3600.0
      "day" -> value * 86400.0
      "week" -> value * 7.0 * 86400.0
      "month" -> value * 30.0 * 86400.0
      "year" -> value * 365.0 * 86400.0
      else -> 0.0
    }
  }

  private suspend fun evalUser(op: String, key: String, value: IRExpr?): Boolean {
    val user = ctx.user ?: return false
    val raw = user.userProperty(key)
    return evalPropOps(op, raw, value)
  }

  private suspend fun evalFeature(op: String, id: String, value: IRExpr?): Boolean {
    val features = ctx.features ?: return false
    return when (op) {
      "has" -> features.has(id)
      "not_has" -> !features.has(id)
      "is_unlimited" -> features.isUnlimited(id)
      "credits_eq", "credits_neq", "credits_gt", "credits_gte", "credits_lt", "credits_lte" -> {
        val v = value ?: return false
        val target = evalValue(v)
        val n = (target as? IRValue.Number)?.value ?: return false
        val balance = features.getBalance(id) ?: return false
        val targetInt = n.toInt()
        when (op) {
          "credits_eq" -> balance == targetInt
          "credits_neq" -> balance != targetInt
          "credits_gt" -> balance > targetInt
          "credits_gte" -> balance >= targetInt
          "credits_lt" -> balance < targetInt
          "credits_lte" -> balance <= targetInt
          else -> false
        }
      }
      else -> false
    }
  }

  private suspend fun evalEvent(op: String, key: String, value: IRExpr?, event: NuxieEvent): Boolean {
    val raw = resolveEventValue(key, event)
    return evalPropOps(op, raw, value)
  }

  private suspend fun evalPropOps(op: String, raw: Any?, value: IRExpr?): Boolean {
    return when (op) {
      "has", "is_set" -> raw != null
      "is_not_set" -> raw == null

      "eq", "equals" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.EQ, raw, evalValue(v).toAny())
      }

      "neq", "not_equals" -> {
        val v = value ?: return true
        Comparer.compare(CompareOp.NEQ, raw, evalValue(v).toAny())
      }

      "gt" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.GT, raw, evalValue(v).toAny())
      }

      "gte" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.GTE, raw, evalValue(v).toAny())
      }

      "lt" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.LT, raw, evalValue(v).toAny())
      }

      "lte" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.LTE, raw, evalValue(v).toAny())
      }

      "icontains" -> {
        val v = value ?: return false
        val needle = Coercion.asString(evalValue(v).toAny()) ?: ""
        Comparer.icontains(raw, needle)
      }

      "regex" -> {
        val v = value ?: return false
        val pattern = Coercion.asString(evalValue(v).toAny()) ?: ""
        Comparer.regex(raw, pattern)
      }

      "in" -> {
        val v = value ?: return false
        val listValue = evalValue(v)
        (listValue as? IRValue.List)?.let { Comparer.member(raw, it.value.map { it.toAny() }) } ?: false
      }

      "not_in" -> {
        val v = value ?: return true
        val listValue = evalValue(v)
        (listValue as? IRValue.List)?.let { !Comparer.member(raw, it.value.map { it.toAny() }) } ?: true
      }

      "is_date_exact" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        Comparer.isSameDay(ts, target.value)
      }

      "is_date_after" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        ts > target.value
      }

      "is_date_before" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        ts < target.value
      }

      else -> false
    }
  }

  private suspend fun evalPredicate(op: String, key: String, value: IRExpr?, event: NuxieEvent): Boolean {
    val eventValue = resolveEventValue(key, event)
    return when (op) {
      "is_set", "has" -> eventValue != null
      "is_not_set" -> eventValue == null

      "eq", "equals" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.EQ, eventValue, evalValue(v).toAny())
      }
      "neq", "not_equals" -> {
        val v = value ?: return true
        Comparer.compare(CompareOp.NEQ, eventValue, evalValue(v).toAny())
      }
      "gt" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.GT, eventValue, evalValue(v).toAny())
      }
      "gte" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.GTE, eventValue, evalValue(v).toAny())
      }
      "lt" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.LT, eventValue, evalValue(v).toAny())
      }
      "lte" -> {
        val v = value ?: return false
        Comparer.compare(CompareOp.LTE, eventValue, evalValue(v).toAny())
      }
      "icontains" -> {
        val v = value ?: return false
        val needle = Coercion.asString(evalValue(v).toAny()) ?: ""
        Comparer.icontains(eventValue, needle)
      }
      "regex" -> {
        val v = value ?: return false
        val pattern = Coercion.asString(evalValue(v).toAny()) ?: ""
        Comparer.regex(eventValue, pattern)
      }
      "in" -> {
        val v = value ?: return false
        val listValue = evalValue(v)
        (listValue as? IRValue.List)?.let { Comparer.member(eventValue, it.value.map { it.toAny() }) } ?: false
      }
      "not_in" -> {
        val v = value ?: return true
        val listValue = evalValue(v)
        (listValue as? IRValue.List)?.let { !Comparer.member(eventValue, it.value.map { it.toAny() }) } ?: true
      }
      "is_date_exact" -> {
        val ts = Coercion.asTimestamp(eventValue) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        Comparer.isSameDay(ts, target.value)
      }
      "is_date_after" -> {
        val ts = Coercion.asTimestamp(eventValue) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        ts > target.value
      }
      "is_date_before" -> {
        val ts = Coercion.asTimestamp(eventValue) ?: return false
        val v = value ?: return false
        val target = evalValue(v) as? IRValue.Timestamp ?: return false
        ts < target.value
      }
      else -> false
    }
  }

  private suspend fun exprToPredicate(expr: IRExpr?): IRPredicate? {
    if (expr == null) return null
    return when (expr) {
      is IRExpr.Pred -> {
        val irValue = expr.value?.let { evalValue(it) }
        IRPredicate.Atom(op = expr.op, key = expr.key, value = irValue)
      }

      is IRExpr.PredAnd -> {
        val preds = buildList {
          for (e in expr.args) {
            val p = exprToPredicate(e) ?: continue
            add(p)
          }
        }
        IRPredicate.And(preds)
      }

      is IRExpr.PredOr -> {
        val preds = buildList {
          for (e in expr.args) {
            val p = exprToPredicate(e) ?: continue
            add(p)
          }
        }
        IRPredicate.Or(preds)
      }

      is IRExpr.Not -> throw IRError.EvaluationError("NOT over predicates not supported in v1")

      else -> throw IRError.TypeMismatch(expected = "Pred* node", got = expr.javaClass.simpleName)
    }
  }

  /**
   * Resolve event values with support for:
   * - $name, $timestamp, $distinct_id
   * - dotted paths (e.g., "properties.amount", "properties.nested.key")
   * - fallback to properties[key] if top-level miss
   *
   * Mirrors iOS `resolveEventValue`.
   */
  private fun resolveEventValue(key: String, event: NuxieEvent): Any? {
    val rawKey = if (key.startsWith("$")) key.drop(1) else key

    if (rawKey == "name" || rawKey == "event") return event.name
    if (rawKey == "timestamp") return Coercion.asTimestamp(event.timestamp)
    if (rawKey == "distinct_id" || rawKey == "distinctId") return event.distinctId

    if (rawKey.startsWith("properties.")) {
      val path = rawKey.removePrefix("properties.")
      return valueForKeyPath(path, event.properties)
    }

    return event.properties[rawKey]
  }

  private fun valueForKeyPath(keyPath: String, obj: Any?): Any? {
    var dict = obj as? Map<*, *> ?: return null
    val parts = keyPath.split(".")
    for ((i, part) in parts.withIndex()) {
      if (i == parts.size - 1) return dict[part]
      val next = dict[part] as? Map<*, *> ?: return null
      dict = next
    }
    return null
  }
}

