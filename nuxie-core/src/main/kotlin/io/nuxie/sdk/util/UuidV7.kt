package io.nuxie.sdk.util

import java.security.SecureRandom
import java.util.UUID

/**
 * UUIDv7 generator (time-ordered), modeled after the iOS implementation.
 *
 * We keep a 10-byte entropy buffer that increments when multiple UUIDs are
 * generated in the same millisecond.
 */
object UuidV7 {
  private val random = SecureRandom()
  private val lock = Any()

  private var lastTimestampMs: Long = 0
  private val lastEntropy: ByteArray = ByteArray(10)

  fun generate(): UUID {
    synchronized(lock) {
      val nowMs = System.currentTimeMillis()
      val bytes = ByteArray(16)

      // 48-bit big-endian unix time ms.
      bytes[0] = ((nowMs ushr 40) and 0xFF).toByte()
      bytes[1] = ((nowMs ushr 32) and 0xFF).toByte()
      bytes[2] = ((nowMs ushr 24) and 0xFF).toByte()
      bytes[3] = ((nowMs ushr 16) and 0xFF).toByte()
      bytes[4] = ((nowMs ushr 8) and 0xFF).toByte()
      bytes[5] = (nowMs and 0xFF).toByte()

      if (nowMs == lastTimestampMs) {
        // Increment entropy as unsigned 80-bit integer.
        for (i in 9 downTo 0) {
          val prev = lastEntropy[i].toInt() and 0xFF
          val next = (prev + 1) and 0xFF
          lastEntropy[i] = next.toByte()
          if (prev != 0xFF) break
        }
      } else {
        lastTimestampMs = nowMs
        random.nextBytes(lastEntropy)
      }

      System.arraycopy(lastEntropy, 0, bytes, 6, 10)

      // Set version to 7 (0b0111).
      bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
      // Set variant to RFC 4122 (0b10xx).
      bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

      val msb = bytesToLong(bytes, 0)
      val lsb = bytesToLong(bytes, 8)
      return UUID(msb, lsb)
    }
  }

  fun generateString(): String = generate().toString()

  private fun bytesToLong(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
      result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
    }
    return result
  }
}

