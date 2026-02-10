package io.nuxie.sdk.storage

import android.content.Context
import androidx.room.Room
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.storage.db.NuxieDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(org.robolectric.RobolectricTestRunner::class)
class RoomEventQueueStoreTest {
  @Test
  fun enqueue_peek_delete_roundtrip() = runBlocking {
    val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    val db = Room.inMemoryDatabaseBuilder(context, NuxieDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val store = RoomEventQueueStore(db.eventQueueDao())
      val e1 = QueuedEvent(
        name = "e1",
        distinctId = "d1",
        timestamp = "t",
        properties = JsonObject(emptyMap()),
      )
      val e2 = QueuedEvent(
        name = "e2",
        distinctId = "d1",
        timestamp = "t",
        properties = JsonObject(emptyMap()),
      )

      store.enqueue(e1)
      store.enqueue(e2)
      assertEquals(2, store.size())

      val peeked = store.peek(10)
      assertEquals(listOf("e1", "e2"), peeked.map { it.name })

      store.delete(listOf(e1.id))
      assertEquals(1, store.size())
    } finally {
      db.close()
    }
  }

  @Test
  fun reassignDistinctId_updates_rows() = runBlocking {
    val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    val db = Room.inMemoryDatabaseBuilder(context, NuxieDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val store = RoomEventQueueStore(db.eventQueueDao())
      val e1 = QueuedEvent(
        name = "e1",
        distinctId = "anon",
        timestamp = "t",
        properties = JsonObject(emptyMap()),
      )
      val e2 = QueuedEvent(
        name = "e2",
        distinctId = "anon",
        timestamp = "t",
        properties = JsonObject(emptyMap()),
      )

      store.enqueue(e1)
      store.enqueue(e2)

      val updated = store.reassignDistinctId(fromDistinctId = "anon", toDistinctId = "user_1")
      assertEquals(2, updated)

      val peeked = store.peek(10)
      assertEquals(listOf("user_1", "user_1"), peeked.map { it.distinctId })
    } finally {
      db.close()
    }
  }
}
