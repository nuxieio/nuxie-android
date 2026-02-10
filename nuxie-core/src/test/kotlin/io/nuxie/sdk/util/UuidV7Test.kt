package io.nuxie.sdk.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {
  @Test
  fun uuidv7_has_correct_version_and_variant() {
    val uuid = UuidV7.generate()
    assertEquals(7, uuid.version())
    assertEquals(2, uuid.variant())
  }

  @Test
  fun uuidv7_generates_unique_values() {
    val set = mutableSetOf<String>()
    repeat(10_000) {
      set.add(UuidV7.generateString())
    }
    assertEquals(10_000, set.size)
  }
}

