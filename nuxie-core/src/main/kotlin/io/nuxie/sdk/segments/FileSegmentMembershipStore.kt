package io.nuxie.sdk.segments

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

class FileSegmentMembershipStore(
  private val directory: File,
  private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : SegmentMembershipStore {

  override suspend fun load(distinctId: String): Map<String, SegmentMembership>? {
    val file = fileFor(distinctId)
    if (!file.exists()) return null
    return runCatching {
      val raw = file.readText()
      json.decodeFromString(
        MapSerializer(String.serializer(), SegmentMembership.serializer()),
        raw
      )
    }.getOrNull()
  }

  override suspend fun save(distinctId: String, memberships: Map<String, SegmentMembership>) {
    directory.mkdirs()
    val file = fileFor(distinctId)
    val raw = json.encodeToString(
      MapSerializer(String.serializer(), SegmentMembership.serializer()),
      memberships
    )
    runCatching { file.writeText(raw) }
  }

  override suspend fun clear(distinctId: String) {
    runCatching { fileFor(distinctId).delete() }
  }

  private fun fileFor(distinctId: String): File {
    val safe = distinctId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(directory, "segments_$safe.json")
  }
}

