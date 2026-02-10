package io.nuxie.sdk.util

fun interface Clock {
  fun nowEpochMillis(): Long

  companion object {
    fun system(): Clock = Clock { System.currentTimeMillis() }
  }
}

