package io.nuxie.sdk.ir

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Unified value representation for IR evaluation.
 *
 * Mirrors iOS `IRValue`.
 */
sealed class IRValue {
  data class Bool(val value: Boolean) : IRValue()
  data class Number(val value: Double) : IRValue()
  data class String(val value: kotlin.String) : IRValue()

  /** epoch seconds */
  data class Timestamp(val value: Double) : IRValue()

  /** seconds */
  data class Duration(val value: Double) : IRValue()

  data class List(val value: kotlin.collections.List<IRValue>) : IRValue()

  data object Null : IRValue()

  val isTruthy: Boolean
    get() = when (this) {
      is Bool -> value
      is Number -> value != 0.0
      is String -> value.isNotEmpty()
      is List -> value.isNotEmpty()
      is Timestamp -> value != 0.0
      is Duration -> value != 0.0
      Null -> false
    }

  fun toAny(): Any? = when (this) {
    is Bool -> value
    is Number -> value
    is String -> value
    is Timestamp -> value
    is Duration -> value
    is List -> value.map { it.toAny() }
    Null -> null
  }
}

/**
 * Type coercion utilities for flexible comparisons.
 *
 * Mirrors iOS `Coercion`.
 */
object Coercion {
  private val fmtWithMillis = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
  }
  private val fmtNoMillis = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
  }

  fun asNumber(value: Any?): Double? = when (value) {
    null -> null
    is Double -> value
    is Float -> value.toDouble()
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Short -> value.toDouble()
    is Byte -> value.toDouble()
    is Number -> value.toDouble()
    is kotlin.String -> value.toDoubleOrNull()
    is Date -> value.time / 1000.0
    else -> null
  }

  fun asString(value: Any?): kotlin.String? = when (value) {
    null -> null
    is kotlin.String -> value
    is Boolean -> if (value) "true" else "false"
    is Number -> value.toString()
    is Date -> fmtWithMillis.get().format(value)
    else -> null
  }

  /**
   * Coerce value to timestamp (epoch seconds) if possible.
   *
   * iOS accepts:
   * - numeric seconds
   * - ISO8601 date strings (with or without fractional seconds)
   */
  fun asTimestamp(value: Any?): Double? {
    // Numeric path first
    val n = asNumber(value)
    if (n != null) return n

    val s = value as? kotlin.String ?: return null
    // Try parsing as ISO date string.
    val parsed = parseIso8601Seconds(s)
    if (parsed != null) return parsed
    return null
  }

  fun asBool(value: Any?): Boolean? = when (value) {
    null -> null
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is kotlin.String -> when (value.lowercase(Locale.US)) {
      "true", "yes", "1" -> true
      "false", "no", "0" -> false
      else -> null
    }
    else -> null
  }

  private fun parseIso8601Seconds(value: kotlin.String): Double? {
    fun tryParse(fmt: SimpleDateFormat): Double? {
      return try {
        val d = fmt.parse(value) ?: return null
        d.time / 1000.0
      } catch (_: ParseException) {
        null
      }
    }
    return tryParse(fmtWithMillis.get()) ?: tryParse(fmtNoMillis.get())
  }
}

/**
 * Comparison operators.
 *
 * Mirrors iOS `CompareOp`.
 */
enum class CompareOp(val raw: kotlin.String) {
  EQ("=="),
  NEQ("!="),
  GT(">"),
  GTE(">="),
  LT("<"),
  LTE("<="),
  IN("in"),
  NOT_IN("not_in"),
}

/**
 * Comparison utilities for IR evaluation.
 *
 * Mirrors iOS `Comparer`.
 */
object Comparer {
  fun compare(op: CompareOp, lhs: Any?, rhs: Any?): Boolean {
    if (op == CompareOp.IN || op == CompareOp.NOT_IN) {
      val result = member(lhs, rhs)
      return if (op == CompareOp.IN) result else !result
    }

    // Numeric comparison
    val lNum = Coercion.asNumber(lhs)
    val rNum = Coercion.asNumber(rhs)
    if (lNum != null && rNum != null) {
      return when (op) {
        CompareOp.EQ -> lNum == rNum
        CompareOp.NEQ -> lNum != rNum
        CompareOp.GT -> lNum > rNum
        CompareOp.GTE -> lNum >= rNum
        CompareOp.LT -> lNum < rNum
        CompareOp.LTE -> lNum <= rNum
        else -> false
      }
    }

    // String comparison
    val lStr = Coercion.asString(lhs)
    val rStr = Coercion.asString(rhs)
    if (lStr != null && rStr != null) {
      return when (op) {
        CompareOp.EQ -> lStr == rStr
        CompareOp.NEQ -> lStr != rStr
        CompareOp.GT -> lStr > rStr
        CompareOp.GTE -> lStr >= rStr
        CompareOp.LT -> lStr < rStr
        CompareOp.LTE -> lStr <= rStr
        else -> false
      }
    }

    // Boolean comparison for equality
    if (op == CompareOp.EQ || op == CompareOp.NEQ) {
      val lBool = Coercion.asBool(lhs)
      val rBool = Coercion.asBool(rhs)
      if (lBool != null && rBool != null) {
        return if (op == CompareOp.EQ) lBool == rBool else lBool != rBool
      }
    }

    // Null comparisons
    val lhsNull = lhs == null
    val rhsNull = rhs == null
    if (lhsNull || rhsNull) {
      return when (op) {
        CompareOp.EQ -> lhsNull && rhsNull
        CompareOp.NEQ -> !(lhsNull && rhsNull)
        else -> false
      }
    }

    return false
  }

  fun icontains(haystack: Any?, needle: kotlin.String): Boolean {
    val s = Coercion.asString(haystack)
    if (s != null) {
      return s.lowercase(Locale.US).contains(needle.lowercase(Locale.US))
    }

    val arr = haystack as? kotlin.collections.List<*>
    if (arr != null) {
      val n = needle.lowercase(Locale.US)
      return arr.any { item -> Coercion.asString(item)?.lowercase(Locale.US)?.contains(n) == true }
    }

    return false
  }

  fun regex(haystack: Any?, pattern: kotlin.String): Boolean {
    val s = Coercion.asString(haystack) ?: return false
    return try {
      Pattern.compile(pattern).matcher(s).find()
    } catch (_: Exception) {
      false
    }
  }

  fun member(value: Any?, list: Any?): Boolean {
    val array: kotlin.collections.List<*>
    array = when (list) {
      is kotlin.collections.List<*> -> list
      is Array<*> -> list.toList()
      is IRValue.List -> list.value.map { it.toAny() }
      else -> return false
    }

    val s = Coercion.asString(value)
    if (s != null) {
      return array.any { item -> Coercion.asString(item) == s }
    }

    val n = Coercion.asNumber(value)
    if (n != null) {
      return array.any { item ->
        val itemNum = Coercion.asNumber(item) ?: return@any false
        kotlin.math.abs(itemNum - n) < Math.ulp(1.0)
      }
    }

    val b = Coercion.asBool(value)
    if (b != null) {
      return array.any { item -> Coercion.asBool(item) == b }
    }

    return false
  }

  fun isSameDay(epochSecondsA: Double, epochSecondsB: Double): Boolean {
    val cal = Calendar.getInstance()
    val da = Date((epochSecondsA * 1000.0).toLong())
    val db = Date((epochSecondsB * 1000.0).toLong())
    cal.time = da
    val ay = cal.get(Calendar.YEAR)
    val am = cal.get(Calendar.MONTH)
    val ad = cal.get(Calendar.DAY_OF_MONTH)
    cal.time = db
    return ay == cal.get(Calendar.YEAR) &&
      am == cal.get(Calendar.MONTH) &&
      ad == cal.get(Calendar.DAY_OF_MONTH)
  }
}
