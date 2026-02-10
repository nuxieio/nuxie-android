package io.nuxie.sdk.identity

import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultIdentityServiceTest {
  @Test
  fun anonymous_id_is_stable_and_used_as_distinct_id_when_not_identified() {
    val kv = InMemoryKeyValueStore()
    val s1 = DefaultIdentityService(kv)

    val anon1 = s1.getAnonymousId()
    assertFalse(anon1.isBlank())
    assertEquals(anon1, s1.getDistinctId())
    assertNull(s1.getRawDistinctId())
    assertFalse(s1.isIdentified)

    val s2 = DefaultIdentityService(kv)
    val anon2 = s2.getAnonymousId()
    assertEquals(anon1, anon2)
  }

  @Test
  fun identify_sets_distinct_id_and_migrates_user_properties_from_anonymous() = runBlocking {
    val kv = InMemoryKeyValueStore()
    val s = DefaultIdentityService(kv)

    val anon = s.getAnonymousId()
    s.setUserProperties(mapOf("plan" to "free", "count" to 1))

    s.setDistinctId("user_123")

    assertTrue(s.isIdentified)
    assertEquals("user_123", s.getDistinctId())
    assertEquals("user_123", s.getRawDistinctId())

    val props = s.getUserProperties()
    assertTrue("plan" in props)
    assertTrue("count" in props)

    // Old anonymous key should not remain as a separate bag after migration.
    s.reset(keepAnonymousId = true)
    assertEquals(anon, s.getDistinctId())
  }

  @Test
  fun set_once_user_properties_does_not_override_existing() {
    val kv = InMemoryKeyValueStore()
    val s = DefaultIdentityService(kv)

    s.setUserProperties(mapOf("a" to "one"))
    s.setOnceUserProperties(mapOf("a" to "two", "b" to "three"))

    val props = s.getUserProperties()
    assertEquals("one", props["a"]?.toString()?.trim('"'))
    assertEquals("three", props["b"]?.toString()?.trim('"'))
  }

  @Test
  fun reset_rotates_anonymous_id_when_keep_anonymous_id_is_false() {
    val kv = InMemoryKeyValueStore()
    val s = DefaultIdentityService(kv)

    val anon1 = s.getAnonymousId()
    s.setDistinctId("user_abc")
    s.reset(keepAnonymousId = false)

    val anon2 = s.getAnonymousId()
    assertNotEquals(anon1, anon2)
    assertFalse(s.isIdentified)
  }
}

