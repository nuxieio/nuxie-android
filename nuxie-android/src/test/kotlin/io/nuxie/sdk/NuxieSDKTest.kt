package io.nuxie.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class NuxieSDKTest {
  @Test
  fun version_matches_core() {
    assertEquals(NuxieVersion.current, NuxieSDK.shared().version())
  }
}
