package io.nuxie.sdk.journey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Journey persistence.
 *
 * Mirrors iOS `JourneyStore`:
 * - active journeys stored as one JSON file per journey
 * - completion records stored per distinctId+campaignId (last 10)
 * - small in-memory cache for "is there an active journey for campaign" checks
 */
interface JourneyStore {
  suspend fun saveJourney(journey: Journey)
  suspend fun loadActiveJourneys(): List<Journey>
  suspend fun loadJourney(id: String): Journey?
  suspend fun deleteJourney(id: String)

  suspend fun recordCompletion(record: JourneyCompletionRecord)
  suspend fun hasCompletedCampaign(distinctId: String, campaignId: String): Boolean
  suspend fun lastCompletionEpochMillis(distinctId: String, campaignId: String): Long?

  suspend fun cleanup(olderThanEpochMillis: Long)

  suspend fun getActiveJourneyIds(distinctId: String, campaignId: String): Set<String>
  suspend fun updateCache(journey: Journey)
  suspend fun clearCache()
}

class FileJourneyStore(
  private val baseDirectory: File,
  private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : JourneyStore {

  private val journeysDir: File = File(baseDirectory, "journeys")
  private val activeDir: File = File(journeysDir, "active")
  private val completedDir: File = File(journeysDir, "completed")

  init {
    activeDir.mkdirs()
    completedDir.mkdirs()
  }

  override suspend fun saveJourney(journey: Journey) {
    val file = activeFile(journey.id)
    val encoded = runCatching { json.encodeToString(Journey.serializer(), journey) }
      .getOrElse { throw it }

    withContext(Dispatchers.IO) {
      file.parentFile?.mkdirs()
      file.writeText(encoded)
    }
  }

  override suspend fun loadActiveJourneys(): List<Journey> = withContext(Dispatchers.IO) {
    if (!activeDir.exists()) return@withContext emptyList()
    val files = activeDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("journey_") && it.name.endsWith(".json") }
    val out = mutableListOf<Journey>()
    for (file in files) {
      val raw = runCatching { file.readText() }.getOrNull() ?: continue
      val decoded = runCatching { json.decodeFromString(Journey.serializer(), raw) }.getOrNull()
      if (decoded != null) {
        out += decoded
      } else {
        // Best-effort: delete corrupt file.
        runCatching { file.delete() }
      }
    }
    out
  }

  override suspend fun loadJourney(id: String): Journey? {
    val file = activeFile(id)
    if (!file.exists()) return null
    return withContext(Dispatchers.IO) {
      val raw = runCatching { file.readText() }.getOrNull() ?: return@withContext null
      runCatching { json.decodeFromString(Journey.serializer(), raw) }.getOrNull()
    }
  }

  override suspend fun deleteJourney(id: String) {
    withContext(Dispatchers.IO) {
      runCatching { activeFile(id).delete() }
    }
  }

  override suspend fun recordCompletion(record: JourneyCompletionRecord) {
    val userDir = File(completedDir, sanitize(record.distinctId))
    userDir.mkdirs()
    val file = File(userDir, "campaign_${sanitize(record.campaignId)}.json")

    withContext(Dispatchers.IO) {
      val existing: List<JourneyCompletionRecord> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val raw = file.readText()
        json.decodeFromString(ListSerializer(JourneyCompletionRecord.serializer()), raw)
      }.getOrDefault(emptyList())

      val next = (existing + record).let { list ->
        if (list.size <= 10) list else list.takeLast(10)
      }

      val encoded = json.encodeToString(ListSerializer(JourneyCompletionRecord.serializer()), next)
      runCatching { file.writeText(encoded) }
    }
  }

  override suspend fun hasCompletedCampaign(distinctId: String, campaignId: String): Boolean {
    val file = completionFile(distinctId, campaignId)
    return withContext(Dispatchers.IO) { file.exists() }
  }

  override suspend fun lastCompletionEpochMillis(distinctId: String, campaignId: String): Long? {
    val file = completionFile(distinctId, campaignId)
    if (!file.exists()) return null
    return withContext(Dispatchers.IO) {
      val raw = runCatching { file.readText() }.getOrNull() ?: return@withContext null
      val list = runCatching {
        json.decodeFromString(ListSerializer(JourneyCompletionRecord.serializer()), raw)
      }.getOrNull() ?: return@withContext null
      list.lastOrNull()?.completedAtEpochMillis
    }
  }

  override suspend fun cleanup(olderThanEpochMillis: Long) {
    withContext(Dispatchers.IO) {
      // Active journeys: delete files older than threshold (best-effort based on lastModified).
      activeDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("journey_") && it.name.endsWith(".json") }
        .forEach { file ->
          val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
          if (lastModified > 0L && lastModified < olderThanEpochMillis) {
            runCatching { file.delete() }
          }
        }

      // Completed: delete per-campaign files older than 90 days relative to the threshold.
      val ninetyDaysMillis = 90L * 24L * 60L * 60L * 1000L
      val completionThreshold = olderThanEpochMillis - ninetyDaysMillis
      completedDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".json") }
        .forEach { file ->
          val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
          if (lastModified > 0L && lastModified < completionThreshold) {
            runCatching { file.delete() }
          }
        }
    }
  }

  override suspend fun getActiveJourneyIds(distinctId: String, campaignId: String): Set<String> {
    val key = cacheKey(distinctId, campaignId)
    synchronized(cacheLock) {
      val cached = activeJourneyCache[key]
      if (cached != null) return cached.toSet()
    }

    val journeys = loadActiveJourneys()
    val ids = journeys.asSequence()
      .filter { it.distinctId == distinctId && it.campaignId == campaignId && it.status.isActive }
      .map { it.id }
      .toSet()

    synchronized(cacheLock) {
      activeJourneyCache[key] = ids.toMutableSet()
    }

    return ids
  }

  override suspend fun updateCache(journey: Journey) {
    val key = cacheKey(journey.distinctId, journey.campaignId)
    synchronized(cacheLock) {
      val set = activeJourneyCache.getOrPut(key) { mutableSetOf() }
      if (journey.status.isActive) {
        set.add(journey.id)
      } else {
        set.remove(journey.id)
      }
    }
  }

  override suspend fun clearCache() {
    synchronized(cacheLock) { activeJourneyCache.clear() }
  }

  private fun activeFile(id: String): File = File(activeDir, "journey_${sanitize(id)}.json")

  private fun completionFile(distinctId: String, campaignId: String): File {
    val userDir = File(completedDir, sanitize(distinctId))
    return File(userDir, "campaign_${sanitize(campaignId)}.json")
  }

  private fun sanitize(value: String): String {
    return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
  }

  private fun cacheKey(distinctId: String, campaignId: String): String = "${distinctId}:${campaignId}"

  private companion object {
    private val cacheLock = Any()
    private val activeJourneyCache: MutableMap<String, MutableSet<String>> = mutableMapOf()
  }
}
