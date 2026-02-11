package io.nuxie.sdk.ir

/**
 * Predicate structure for event property filtering.
 *
 * Mirrors iOS `IRPredicate`.
 */
sealed interface IRPredicate {
  data class Atom(val op: String, val key: String, val value: IRValue?) : IRPredicate
  data class And(val predicates: List<IRPredicate>) : IRPredicate
  data class Or(val predicates: List<IRPredicate>) : IRPredicate
}

/**
 * Predicate evaluator for event properties.
 *
 * Mirrors iOS `PredicateEval`.
 */
object PredicateEval {
  fun eval(predicate: IRPredicate, props: Map<String, Any?>): Boolean {
    return when (predicate) {
      is IRPredicate.And -> predicate.predicates.all { eval(it, props) }
      is IRPredicate.Or -> predicate.predicates.any { eval(it, props) }
      is IRPredicate.Atom -> {
        val raw = props[predicate.key]
        evalAtom(predicate.op, raw, predicate.value)
      }
    }
  }

  private fun unwrap(value: IRValue?): Any? = value?.toAny()

  private fun evalAtom(op: String, raw: Any?, value: IRValue?): Boolean {
    return when (op) {
      "is_set", "has" -> raw != null
      "is_not_set" -> raw == null

      "eq", "equals" -> Comparer.compare(CompareOp.EQ, raw, unwrap(value))
      "neq", "not_equals" -> Comparer.compare(CompareOp.NEQ, raw, unwrap(value))
      "gt" -> Comparer.compare(CompareOp.GT, raw, unwrap(value))
      "gte" -> Comparer.compare(CompareOp.GTE, raw, unwrap(value))
      "lt" -> Comparer.compare(CompareOp.LT, raw, unwrap(value))
      "lte" -> Comparer.compare(CompareOp.LTE, raw, unwrap(value))

      "icontains", "contains" -> {
        val needle = Coercion.asString(unwrap(value)) ?: return false
        Comparer.icontains(raw, needle)
      }

      "regex" -> {
        val pattern = Coercion.asString(unwrap(value)) ?: return false
        Comparer.regex(raw, pattern)
      }

      "in" -> {
        when (value) {
          is IRValue.List -> Comparer.member(raw, value.value.map { it.toAny() })
          else -> false
        }
      }

      "not_in" -> {
        when (value) {
          is IRValue.List -> !Comparer.member(raw, value.value.map { it.toAny() })
          else -> true
        }
      }

      "is_date_exact" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val target = (value as? IRValue.Timestamp)?.value ?: return false
        Comparer.isSameDay(ts, target)
      }

      "is_date_after" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val target = (value as? IRValue.Timestamp)?.value ?: return false
        ts > target
      }

      "is_date_before" -> {
        val ts = Coercion.asTimestamp(raw) ?: return false
        val target = (value as? IRValue.Timestamp)?.value ?: return false
        ts < target
      }

      else -> false
    }
  }
}

