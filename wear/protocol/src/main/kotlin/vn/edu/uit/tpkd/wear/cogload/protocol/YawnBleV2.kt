// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/** Backward-compatible yawn-sync extension carried on the existing Control/Observation UUIDs. */
object YawnBleV2 {
    const val OPCODE_SESSION_RESUME: Byte = 0x10
    const val OPCODE_EVENT: Byte = 0x11
    const val OPCODE_STATE_REQUEST: Byte = 0x12
    const val OPCODE_SESSION_END: Byte = 0x13

    const val SESSION_BYTES = 16
    const val STATE_BYTES = 32
    const val MAX_RECENT_EVENTS = 64
    const val WINDOW_MS = 10L * 60L * 1_000L
    private const val MAGIC_0: Byte = 0x59 // Y
    private const val MAGIC_1: Byte = 0x32 // 2
    private const val FLAG_ACTIVE = 1
    private const val FLAG_ACK = 2
    private const val UINT32_MAX = 0xffff_ffffL
    private val SESSION_PATTERN = Regex("[0-9a-f]{32}")

    data class State(
        val active: Boolean,
        val session: String,
        val revision: Long,
        val totalCount: Int,
        val windowCount: Int,
        val acknowledgedEventId: Long?,
    )

    fun resumeCommand(
        session: String,
        checkpointTotal: Int,
        recentEventAgesMs: List<Long>,
    ): ByteArray {
        require(checkpointTotal in 0..1_000_000)
        val ages = recentEventAgesMs
            .filter { it in 0L..WINDOW_MS }
            .take(MAX_RECENT_EVENTS)
        require(ages.size <= checkpointTotal)
        return ByteArray(22 + ages.size * 2).also { value ->
            value[0] = OPCODE_SESSION_RESUME
            sessionBytes(session).copyInto(value, 1)
            putU32(value, 17, checkpointTotal.toLong())
            value[21] = ages.size.toByte()
            ages.forEachIndexed { index, ageMs ->
                putU16(value, 22 + index * 2, ((ageMs + 999L) / 1_000L).coerceAtMost(600L).toInt())
            }
        }
    }

    fun eventCommand(
        session: String,
        clientId: Long,
        eventId: Long,
        frameSequence: Long,
        observedEspUptimeMs: Long,
    ): ByteArray {
        require(clientId in 1L..UINT32_MAX)
        require(eventId in 0L..UINT32_MAX)
        require(frameSequence in 0L..UINT32_MAX)
        require(observedEspUptimeMs >= 0L)
        return ByteArray(37).also { value ->
            value[0] = OPCODE_EVENT
            sessionBytes(session).copyInto(value, 1)
            putU32(value, 17, clientId)
            putU32(value, 21, eventId)
            putU32(value, 25, frameSequence)
            putU64(value, 29, observedEspUptimeMs)
        }
    }

    fun stateRequestCommand(session: String): ByteArray =
        byteArrayOf(OPCODE_STATE_REQUEST) + sessionBytes(session)

    fun endCommand(session: String): ByteArray =
        byteArrayOf(OPCODE_SESSION_END) + sessionBytes(session)

    fun parseState(value: ByteArray): State? {
        if (value.size != STATE_BYTES || value[0] != MAGIC_0 || value[1] != MAGIC_1) return null
        val flags = value[2].toInt() and 0xff
        if (flags and 0xfc != 0) return null
        val active = flags and FLAG_ACTIVE != 0
        val session = value.copyOfRange(3, 19).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val revision = readU32(value, 19)
        val total = readU32(value, 23)
        val window = value[27].toInt() and 0xff
        val ack = readU32(value, 28).takeIf { flags and FLAG_ACK != 0 }
        if (!active || !SESSION_PATTERN.matches(session) || revision !in 1L..UINT32_MAX ||
            total !in 0L..1_000_000L || window > MAX_RECENT_EVENTS
        ) return null
        return State(active, session, revision, total.toInt(), window, ack)
    }

    private fun sessionBytes(session: String): ByteArray {
        require(SESSION_PATTERN.matches(session))
        return ByteArray(SESSION_BYTES) { index ->
            session.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { target[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { target[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun readU32(source: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { result, index ->
            result or ((source[offset + index].toLong() and 0xffL) shl (index * 8))
        }
}
