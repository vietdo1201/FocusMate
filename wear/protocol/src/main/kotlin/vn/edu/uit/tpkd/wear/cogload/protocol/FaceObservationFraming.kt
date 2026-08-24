// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, không reflect, không xorout. */
object Crc16 {
    fun ccittFalse(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        require(from in 0..until && until <= data.size) { "invalid range" }
        var crc = 0xFFFF
        for (index in from until until) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor POLY) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private const val POLY = 0x1021
}

/**
 * Cắt payload canonical thành notification theo `docs/GATT_PROFILE.md` mục 6.
 *
 * Payload vừa một notification thì gửi nguyên, **không** header — byte đầu `{` (0x7B)
 * phân biệt với header fragment (byte đầu là `framing_version` = 1).
 */
class FaceObservationFramer {
    private var nextMessageId = 0

    fun frame(payload: ByteArray, attMtu: Int): List<ByteArray> {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(payload[0] == OPEN_BRACE) { "payload must start with '{'" }
        val capacity = GattProfile.notificationCapacity(attMtu)
        require(capacity >= GattProfile.FRAME_HEADER_BYTES + 1) { "MTU too small: $attMtu" }
        if (payload.size <= capacity) return listOf(payload.copyOf())

        val chunkCapacity = capacity - GattProfile.FRAME_HEADER_BYTES
        val count = (payload.size + chunkCapacity - 1) / chunkCapacity
        require(count <= MAX_CHUNKS) { "payload needs $count chunks, max $MAX_CHUNKS" }
        val messageId = nextMessageId
        nextMessageId = (nextMessageId + 1) and 0xFF
        val crc = Crc16.ccittFalse(payload)
        return (0 until count).map { index ->
            val start = index * chunkCapacity
            val size = minOf(chunkCapacity, payload.size - start)
            val chunk = ByteArray(GattProfile.FRAME_HEADER_BYTES + size)
            chunk[0] = GattProfile.FRAMING_VERSION.toByte()
            chunk[1] = messageId.toByte()
            chunk[2] = index.toByte()
            chunk[3] = count.toByte()
            chunk[4] = (payload.size and 0xFF).toByte()
            chunk[5] = ((payload.size ushr 8) and 0xFF).toByte()
            chunk[6] = (crc and 0xFF).toByte()
            chunk[7] = ((crc ushr 8) and 0xFF).toByte()
            payload.copyInto(chunk, GattProfile.FRAME_HEADER_BYTES, start, start + size)
            chunk
        }
    }

    private companion object {
        const val OPEN_BRACE: Byte = 0x7B
        const val MAX_CHUNKS = 255
    }
}
