package io.nuxie.sdk.segments

interface SegmentMembershipStore {
  suspend fun load(distinctId: String): Map<String, SegmentMembership>?
  suspend fun save(distinctId: String, memberships: Map<String, SegmentMembership>)
  suspend fun clear(distinctId: String)
}

