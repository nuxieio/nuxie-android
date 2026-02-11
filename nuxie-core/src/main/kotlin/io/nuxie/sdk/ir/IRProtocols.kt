package io.nuxie.sdk.ir

import io.nuxie.sdk.events.NuxieEvent

/**
 * Adapter protocol for user property access.
 *
 * Mirrors iOS `IRUserProps`.
 */
fun interface IRUserProps {
  suspend fun userProperty(key: String): Any?
}

/**
 * Adapter protocol for event queries.
 *
 * Mirrors iOS `IREventQueries`, but uses epoch millis instead of `Date`.
 */
interface IREventQueries {
  suspend fun exists(name: String, sinceEpochMillis: Long?, untilEpochMillis: Long?, where: IRPredicate?): Boolean
  suspend fun count(name: String, sinceEpochMillis: Long?, untilEpochMillis: Long?, where: IRPredicate?): Int
  suspend fun firstTime(name: String, where: IRPredicate?): Long?
  suspend fun lastTime(name: String, where: IRPredicate?): Long?
  suspend fun aggregate(
    agg: Aggregate,
    name: String,
    prop: String,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
    where: IRPredicate?,
  ): Double?
  suspend fun inOrder(
    steps: List<StepQuery>,
    overallWithinSeconds: Double?,
    perStepWithinSeconds: Double?,
    sinceEpochMillis: Long?,
    untilEpochMillis: Long?,
  ): Boolean
  suspend fun activePeriods(
    name: String,
    period: Period,
    total: Int,
    min: Int,
    where: IRPredicate?,
  ): Boolean
  suspend fun stopped(name: String, inactiveForSeconds: Double, where: IRPredicate?): Boolean
  suspend fun restarted(
    name: String,
    inactiveForSeconds: Double,
    withinSeconds: Double,
    where: IRPredicate?,
  ): Boolean
}

/**
 * Adapter protocol for segment queries.
 *
 * Mirrors iOS `IRSegmentQueries`, but uses epoch millis instead of `Date`.
 */
interface IRSegmentQueries {
  suspend fun isMember(segmentId: String): Boolean
  suspend fun enteredAtEpochMillis(segmentId: String): Long?
}

/**
 * Adapter protocol for feature access queries (entitlements).
 *
 * Mirrors iOS `IRFeatureQueries`.
 */
interface IRFeatureQueries {
  suspend fun has(featureId: String): Boolean
  suspend fun isUnlimited(featureId: String): Boolean
  suspend fun getBalance(featureId: String): Int?
}

enum class Aggregate {
  SUM,
  AVG,
  MIN,
  MAX,
  UNIQUE,
}

data class StepQuery(
  val name: String,
  val predicate: IRPredicate?,
)

enum class Period {
  DAY,
  WEEK,
  MONTH,
  YEAR,
}

/**
 * Context for IR evaluation.
 *
 * Mirrors iOS `EvalContext`.
 */
data class EvalContext(
  val nowEpochMillis: Long,
  val user: IRUserProps? = null,
  val events: IREventQueries? = null,
  val segments: IRSegmentQueries? = null,
  val features: IRFeatureQueries? = null,
  val event: NuxieEvent? = null,
  val journeyId: String? = null,
)

