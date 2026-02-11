package io.nuxie.sdk.ir

/**
 * Errors raised by the IR interpreter.
 *
 * Mirrors iOS `IRError`.
 */
sealed class IRError(message: String) : Exception(message) {
  data object EncodingNotImplemented : IRError("IR encoding is not implemented")
  data class InvalidNodeType(val type: String) : IRError("Invalid IR node type: $type")
  data class InvalidOperator(val op: String) : IRError("Invalid operator: $op")
  data class TypeMismatch(val expected: String, val got: String) : IRError("Type mismatch: expected $expected, got $got")
  data class EvaluationError(val detail: String) : IRError("Evaluation error: $detail")
}

