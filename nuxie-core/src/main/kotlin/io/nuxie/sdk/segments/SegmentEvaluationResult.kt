package io.nuxie.sdk.segments

data class SegmentEvaluationResult(
  val distinctId: String,
  val entered: List<Segment>,
  val exited: List<Segment>,
  val remained: List<Segment>,
) {
  val hasChanges: Boolean get() = entered.isNotEmpty() || exited.isNotEmpty()
}

