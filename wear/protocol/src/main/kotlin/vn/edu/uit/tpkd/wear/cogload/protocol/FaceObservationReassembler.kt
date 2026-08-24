// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Ghép notification thành payload canonical. Xem `docs/GATT_PROFILE.md` mục 6.4.
 *
 * Mất chunk, `total_len` lệch, CRC sai hay quá timeout đều làm **bỏ cả observation**;
 * cấm ghép một phần. Observation bị bỏ không được làm [FaceSequenceGate] tiến.
 */
class FaceObservationReassembler(
    private val timeoutMs: Long = GattProfile.REASSEMBLY_TIMEOUT_MS,
) {
    sealed interface Outcome {
        /** Payload canonical đã đủ; caller đưa sang [FaceObservationCodec.tryDecode]. */
        data class Complete(val payload: ByteArray, val displaced: Reason? = null) : Outcome {
            override fun equals(other: Any?): Boolean =
                other is Complete && payload.contentEquals(other.payload)

            override fun hashCode(): Int = payload.contentHashCode()
        }

        /** Còn thiếu chunk; [displaced] ghi nhận buffer cũ vừa bị bỏ nhưng chunk mới đã được giữ. */
        data class Pending(val displaced: Reason? = null) : Outcome

        data class Dropped(val reason: Reason) : Outcome
    }

    enum class Reason {
        EMPTY_NOTIFICATION,
        UNSUPPORTED_FRAMING_VERSION,
        MALFORMED_HEADER,
        DUPLICATE_CHUNK,
        SUPERSEDED_MESSAGE,
        TIMEOUT,
        LENGTH_MISMATCH,
        CRC_MISMATCH,
    }

    private var messageId: Int? = null
    private var expectedCount = 0
    private var totalLength = 0
    private var expectedCrc = 0
    private var startedAtMs = 0L
    private var received = 0
    private var chunks: Array<ByteArray?> = emptyArray()

    fun offer(notification: ByteArray, atMs: Long): Outcome {
        require(atMs >= 0L) { "atMs must be monotonic and non-negative" }
        if (notification.isEmpty()) return drop(Reason.EMPTY_NOTIFICATION)
        if (notification[0] == OPEN_BRACE) {
            val displaced = if (messageId != null) Reason.SUPERSEDED_MESSAGE else null
            reset()
            return Outcome.Complete(notification.copyOf(), displaced)
        }
        if (notification[0].toInt() != GattProfile.FRAMING_VERSION) {
            return drop(Reason.UNSUPPORTED_FRAMING_VERSION)
        }
        if (notification.size <= GattProfile.FRAME_HEADER_BYTES) return drop(Reason.MALFORMED_HEADER)

        val incomingId = notification[1].toInt() and 0xFF
        val index = notification[2].toInt() and 0xFF
        val count = notification[3].toInt() and 0xFF
        val total = (notification[4].toInt() and 0xFF) or ((notification[5].toInt() and 0xFF) shl 8)
        val crc = (notification[6].toInt() and 0xFF) or ((notification[7].toInt() and 0xFF) shl 8)
        if (count == 0 || index >= count) return drop(Reason.MALFORMED_HEADER)
        if (total !in 1..FaceObservationV1.MAX_PAYLOAD_BYTES) return drop(Reason.MALFORMED_HEADER)

        var displaced: Reason? = null
        var current = messageId
        if (current != null && atMs - startedAtMs > timeoutMs) {
            reset()
            displaced = Reason.TIMEOUT
            current = null
        }
        if (current != null && current != incomingId) {
            reset()
            displaced = Reason.SUPERSEDED_MESSAGE
            current = null
        }
        if (current == null) {
            messageId = incomingId
            expectedCount = count
            totalLength = total
            expectedCrc = crc
            startedAtMs = atMs
            received = 0
            chunks = arrayOfNulls(count)
        } else if (expectedCount != count || totalLength != total || expectedCrc != crc) {
            reset()
            return drop(Reason.MALFORMED_HEADER)
        }
        if (chunks[index] != null) return drop(Reason.DUPLICATE_CHUNK)

        chunks[index] = notification.copyOfRange(GattProfile.FRAME_HEADER_BYTES, notification.size)
        received++
        if (received < expectedCount) return Outcome.Pending(displaced)

        val payload = ByteArray(totalLength)
        var offset = 0
        var truncated = false
        for (chunk in chunks) {
            val part = chunk ?: ByteArray(0)
            if (offset + part.size > totalLength) {
                truncated = true
                break
            }
            part.copyInto(payload, offset)
            offset += part.size
        }
        val expectedLength = totalLength
        val expected = expectedCrc
        reset()
        if (truncated || offset != expectedLength) return drop(Reason.LENGTH_MISMATCH)
        if (Crc16.ccittFalse(payload) != expected) return drop(Reason.CRC_MISMATCH)
        return Outcome.Complete(payload, displaced)
    }

    private fun drop(reason: Reason): Outcome.Dropped = Outcome.Dropped(reason)

    private fun reset() {
        messageId = null
        expectedCount = 0
        totalLength = 0
        expectedCrc = 0
        startedAtMs = 0L
        received = 0
        chunks = emptyArray()
    }

    private companion object {
        const val OPEN_BRACE: Byte = 0x7B
    }
}
