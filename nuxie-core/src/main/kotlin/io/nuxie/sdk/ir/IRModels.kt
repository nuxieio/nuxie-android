package io.nuxie.sdk.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level IR container with version and metadata.
 *
 * Mirrors iOS `IREnvelope`.
 */
@Serializable
data class IREnvelope(
  @SerialName("ir_version")
  val irVersion: Int,
  @SerialName("engine_min")
  val engineMin: String? = null,
  @SerialName("compiled_at")
  val compiledAt: Double? = null,
  val expr: IRExpr,
)

/**
 * IR expression node types (v1).
 *
 * This is decoded from a `type` discriminator field emitted by the backend compiler.
 * Mirrors iOS `IRExpr`.
 */
@Serializable
sealed interface IRExpr {

  // Scalars / containers
  @Serializable
  @SerialName("Bool")
  data class Bool(val value: Boolean) : IRExpr

  @Serializable
  @SerialName("Number")
  data class Number(val value: Double) : IRExpr

  @Serializable
  @SerialName("String")
  data class String(val value: kotlin.String) : IRExpr

  /** epoch seconds */
  @Serializable
  @SerialName("Timestamp")
  data class Timestamp(val value: Double) : IRExpr

  /** seconds */
  @Serializable
  @SerialName("Duration")
  data class Duration(val value: Double) : IRExpr

  @Serializable
  @SerialName("List")
  data class List(val value: kotlin.collections.List<IRExpr>) : IRExpr

  // Boolean composition
  @Serializable
  @SerialName("And")
  data class And(val args: kotlin.collections.List<IRExpr>) : IRExpr

  @Serializable
  @SerialName("Or")
  data class Or(val args: kotlin.collections.List<IRExpr>) : IRExpr

  @Serializable
  @SerialName("Not")
  data class Not(val arg: IRExpr) : IRExpr

  // Generic compare (used when both sides are values)
  @Serializable
  @SerialName("Compare")
  data class Compare(
    val op: kotlin.String,
    val left: IRExpr,
    val right: IRExpr,
  ) : IRExpr

  // User (identity)
  @Serializable
  @SerialName("User")
  data class User(
    val op: kotlin.String,
    val key: kotlin.String,
    val value: IRExpr? = null,
  ) : IRExpr

  // Event (current trigger event)
  @Serializable
  @SerialName("Event")
  data class Event(
    val op: kotlin.String,
    val key: kotlin.String,
    val value: IRExpr? = null,
  ) : IRExpr

  // Segment membership
  @Serializable
  @SerialName("Segment")
  data class Segment(
    val op: kotlin.String,
    val id: kotlin.String,
    val within: IRExpr? = null,
  ) : IRExpr

  // Feature access (entitlements)
  @Serializable
  @SerialName("Feature")
  data class Feature(
    val op: kotlin.String,
    val id: kotlin.String,
    val value: IRExpr? = null,
  ) : IRExpr

  // Predicates over event properties
  @Serializable
  @SerialName("Pred")
  data class Pred(
    val op: kotlin.String,
    val key: kotlin.String,
    val value: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("PredAnd")
  data class PredAnd(val args: kotlin.collections.List<IRExpr>) : IRExpr

  @Serializable
  @SerialName("PredOr")
  data class PredOr(val args: kotlin.collections.List<IRExpr>) : IRExpr

  // Event queries
  @Serializable
  @SerialName("Events.Exists")
  data class EventsExists(
    val name: kotlin.String,
    val since: IRExpr? = null,
    val until: IRExpr? = null,
    val within: IRExpr? = null,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.Count")
  data class EventsCount(
    val name: kotlin.String,
    val since: IRExpr? = null,
    val until: IRExpr? = null,
    val within: IRExpr? = null,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.FirstTime")
  data class EventsFirstTime(
    val name: kotlin.String,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.LastTime")
  data class EventsLastTime(
    val name: kotlin.String,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.LastAge")
  data class EventsLastAge(
    val name: kotlin.String,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.Aggregate")
  data class EventsAggregate(
    val agg: kotlin.String,
    val name: kotlin.String,
    val prop: kotlin.String,
    val since: IRExpr? = null,
    val until: IRExpr? = null,
    val within: IRExpr? = null,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.InOrder")
  data class EventsInOrder(
    val steps: kotlin.collections.List<Step>,
    val overallWithin: IRExpr? = null,
    val perStepWithin: IRExpr? = null,
    val since: IRExpr? = null,
    val until: IRExpr? = null,
  ) : IRExpr {
    @Serializable
    data class Step(
      val name: kotlin.String,
      @SerialName("where")
      val whereExpr: IRExpr? = null,
    )
  }

  @Serializable
  @SerialName("Events.ActivePeriods")
  data class EventsActivePeriods(
    val name: kotlin.String,
    val period: kotlin.String,
    val totalPeriods: Int,
    val minPeriods: Int,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.Stopped")
  data class EventsStopped(
    val name: kotlin.String,
    val inactiveFor: IRExpr,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  @Serializable
  @SerialName("Events.Restarted")
  data class EventsRestarted(
    val name: kotlin.String,
    val inactiveFor: IRExpr,
    val within: IRExpr,
    @SerialName("where")
    val whereExpr: IRExpr? = null,
  ) : IRExpr

  // Time helpers
  @Serializable
  @SerialName("Time.Now")
  data object TimeNow : IRExpr

  @Serializable
  @SerialName("Time.Ago")
  data class TimeAgo(val duration: IRExpr) : IRExpr

  @Serializable
  @SerialName("Time.Window")
  data class TimeWindow(
    val value: Int,
    val interval: kotlin.String,
  ) : IRExpr

  // Journey context
  @Serializable
  @SerialName("Journey.Id")
  data object JourneyId : IRExpr
}

