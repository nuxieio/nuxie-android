package io.nuxie.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class NuxieVersionTest {
  @Test
  fun version_is_not_empty() {
    assertEquals(false, NuxieVersion.current.isBlank())
  }
}
