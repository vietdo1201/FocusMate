// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YawnBleV2Test {
    private val session = "00112233445566778899aabbccddeeff"

    @Test
    fun commandsHaveStableLittleEndianWireFormat() {
        val resume = YawnBleV2.resumeCommand(session, 2, listOf(1_001L, 5_000L))
        assertEquals(YawnBleV2.OPCODE_SESSION_RESUME, resume[0])
        assertEquals(2, resume[17].toInt() and 0xff)
        assertEquals(2, resume[21].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(2, 0, 5, 0), resume.copyOfRange(22, 26))

        val event = YawnBleV2.eventCommand(session, 7, 8, 9, 10)
        assertEquals(37, event.size)
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), event.copyOfRange(17, 21))
    }

    @Test
    fun canonicalStateIsStrict() {
        val value = ByteArray(YawnBleV2.STATE_BYTES)
        value[0] = 0x59
        value[1] = 0x32
        value[2] = 3
        session.chunked(2).forEachIndexed { index, byte -> value[3 + index] = byte.toInt(16).toByte() }
        value[19] = 4
        value[23] = 3
        value[27] = 3
        value[28] = 9
        val state = requireNotNull(YawnBleV2.parseState(value))
        assertEquals(session, state.session)
        assertEquals(4L, state.revision)
        assertEquals(3, state.totalCount)
        assertEquals(9L, state.acknowledgedEventId)
        assertNull(YawnBleV2.parseState(value.copyOf(31)))
    }
}
