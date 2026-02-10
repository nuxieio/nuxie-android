package io.nuxie.sdk.identity

import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.storage.KeyValueStore
import io.nuxie.sdk.util.UuidV7
import io.nuxie.sdk.util.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface IdentityService {
  fun getDistinctId(): String
  fun getRawDistinctId(): String?
  fun getAnonymousId(): String
  val isIdentified: Boolean

  fun setDistinctId(distinctId: String)
  fun reset(keepAnonymousId: Boolean)

  fun getUserProperties(): Map<String, JsonElement>
  fun setUserProperties(properties: Map<String, Any?>)
  fun setOnceUserProperties(properties: Map<String, Any?>)

  suspend fun userProperty(key: String): JsonElement?
}

class DefaultIdentityService(
  private val store: KeyValueStore,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : IdentityService {
  private val lock = ReentrantLock()

  private var distinctId: String? = store.getString(KEY_DISTINCT_ID)
  private var anonymousId: String? = store.getString(KEY_ANON_ID)
  private var userPropertiesById: MutableMap<String, JsonObject> =
    decodeUserProps(store.getString(KEY_USER_PROPS_JSON))

  init {
    if (anonymousId == null) {
      anonymousId = generateAnonymousId()
      persistLocked()
    }
  }

  override fun getDistinctId(): String = lock.withLock {
    distinctId ?: (anonymousId ?: generateAnonymousId().also { anonymousId = it; persistLocked() })
  }

  override fun getRawDistinctId(): String? = lock.withLock { distinctId }

  override fun getAnonymousId(): String = lock.withLock {
    anonymousId ?: generateAnonymousId().also { anonymousId = it; persistLocked() }
  }

  override val isIdentified: Boolean
    get() = lock.withLock { distinctId != null }

  override fun setDistinctId(distinctId: String) = lock.withLock {
    val oldEffectiveKey = effectiveKeyLocked()
    val wasIdentified = this.distinctId != null
    val prev = this.distinctId

    this.distinctId = distinctId

    // Migrate user property bag from anonymous -> identified only when transitioning.
    if (!wasIdentified && distinctId != oldEffectiveKey) {
      val oldProps = userPropertiesById[oldEffectiveKey]
      if (oldProps != null) {
        val existingNew = userPropertiesById[distinctId]
        val merged = JsonObject(oldProps + (existingNew ?: emptyMap()))
        userPropertiesById[distinctId] = merged
        userPropertiesById.remove(oldEffectiveKey)
        NuxieLogger.debug(
          "Migrated ${merged.size} user properties from ${NuxieLogger.logDistinctId(oldEffectiveKey)} to ${NuxieLogger.logDistinctId(distinctId)}"
        )
      }
    }

    persistLocked()
    NuxieLogger.info(
      "Set distinct ID: ${NuxieLogger.logDistinctId(distinctId)} (previous: ${NuxieLogger.logDistinctId(prev)})"
    )
  }

  override fun reset(keepAnonymousId: Boolean) = lock.withLock {
    val prevEffectiveKey = effectiveKeyLocked()
    val prev = distinctId

    // Clear property bag for previous identity.
    userPropertiesById.remove(prevEffectiveKey)

    distinctId = null

    if (!keepAnonymousId) {
      anonymousId = null
    }
    if (anonymousId == null) {
      anonymousId = generateAnonymousId()
    }

    persistLocked()
    NuxieLogger.info(
      "Reset identity - distinct ID: ${NuxieLogger.logDistinctId(prev)} -> nil, anonymous kept: $keepAnonymousId"
    )
  }

  override fun getUserProperties(): Map<String, JsonElement> = lock.withLock {
    userPropertiesById[effectiveKeyLocked()]?.toMap() ?: emptyMap()
  }

  override fun setUserProperties(properties: Map<String, Any?>) = lock.withLock {
    val key = effectiveKeyLocked()
    val current = userPropertiesById[key]?.toMutableMap() ?: mutableMapOf()
    properties.forEach { (k, v) -> current[k] = toJsonElement(v) }
    userPropertiesById[key] = JsonObject(current)
    persistLocked()
    NuxieLogger.debug("Set ${properties.size} user properties for ${NuxieLogger.logDistinctId(key)}")
  }

  override fun setOnceUserProperties(properties: Map<String, Any?>) = lock.withLock {
    val key = effectiveKeyLocked()
    val current = userPropertiesById[key]?.toMutableMap() ?: mutableMapOf()
    var setCount = 0
    properties.forEach { (k, v) ->
      if (current[k] == null) {
        current[k] = toJsonElement(v)
        setCount += 1
      }
    }
    if (setCount > 0) {
      userPropertiesById[key] = JsonObject(current)
      persistLocked()
    }
    NuxieLogger.debug(
      "Set $setCount new user properties for ${NuxieLogger.logDistinctId(key)} (${properties.size - setCount} existed)"
    )
  }

  override suspend fun userProperty(key: String): JsonElement? = lock.withLock {
    userPropertiesById[effectiveKeyLocked()]?.get(key)
  }

  private fun effectiveKeyLocked(): String {
    return distinctId ?: (anonymousId ?: generateAnonymousId().also { anonymousId = it })
  }

  private fun persistLocked() {
    store.putString(KEY_DISTINCT_ID, distinctId)
    store.putString(KEY_ANON_ID, anonymousId)
    store.putString(KEY_USER_PROPS_JSON, encodeUserProps(userPropertiesById))
  }

  // iOS uses a raw UUIDv7 string (no prefix). Keep parity.
  private fun generateAnonymousId(): String = UuidV7.generateString()

  private fun decodeUserProps(raw: String?): MutableMap<String, JsonObject> {
    if (raw.isNullOrBlank()) return mutableMapOf()
    return try {
      val root = json.parseToJsonElement(raw).jsonObject
      root.mapValues { (_, v) -> v.jsonObject }.toMutableMap()
    } catch (_: Exception) {
      mutableMapOf()
    }
  }

  private fun encodeUserProps(map: Map<String, JsonObject>): String {
    // JsonObject is a JsonElement; serialize via JsonElement serializer for stability.
    return json.encodeToString(JsonElement.serializer(), JsonObject(map))
  }

  private companion object {
    private const val KEY_DISTINCT_ID = "nuxie.distinct_id"
    private const val KEY_ANON_ID = "nuxie.anonymous_id"
    private const val KEY_USER_PROPS_JSON = "nuxie.user_properties_by_id"
  }
}
