package vn.edu.uit.tpkd.wear.cogload.protocol

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameAccessInfoV1Test {
    private val fixture: JSONObject by lazy {
        val root = requireNotNull(System.getProperty("focusmate.vectors"))
        JSONObject(File(root, "golden/frame_access_info_v1.json").readText())
    }

    @Test
    fun goldenVectorsAreByteExactAndRoundTrip() {
        val vectors = fixture.getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val bytes = vector.getString("wire_hex").hexToBytes()
            assertEquals(vector.getString("id"), GattProfile.FRAME_ACCESS_INFO_BYTES, bytes.size)
            val parsed = FrameAccessInfoV1.parse(bytes)
            assertEquals(vector.getInt("version"), parsed.version)
            assertEquals(vector.getInt("flags"), parsed.flags)
            assertEquals(vector.getInt("http_port"), parsed.httpPort)
            assertEquals(vector.getString("ipv4"), parsed.ipv4)
            assertEquals(vector.getString("boot_id"), parsed.bootIdHex)
            assertEquals(vector.getString("token"), parsed.tokenHex)
            assertEquals(vector.getBoolean("usable"), parsed.usable)
            assertTrue(vector.getString("id"), parsed.encode().contentEquals(bytes))
        }
    }

    @Test
    fun readyVectorExposesLockedFlagsAndNetworkByteOrder() {
        val ready = fixture.getJSONArray("vectors").getJSONObject(0)
        val parsed = FrameAccessInfoV1.parse(ready.getString("wire_hex").hexToBytes())
        assertTrue(parsed.transportCompatible)
        assertTrue(parsed.lanReady)
        assertTrue(parsed.tokenAuthRequired)
        assertTrue(parsed.faceMetaV1)
        assertEquals(8080, parsed.httpPort)
        assertEquals("192.168.1.17", parsed.ipv4)
        assertEquals("f26cf312-b841-46f5-a172-6b53713a37f3", GattProfile.FRAME_ACCESS_INFO_UUID)
    }

    @Test
    fun incompatibleOrUnsafeValuesAreParsedButNeverUsable() {
        val base = FrameAccessInfoV1.parse(
            fixture.getJSONArray("vectors").getJSONObject(0).getString("wire_hex").hexToBytes(),
        )
        assertFalse(base.copy(version = 2).transportCompatible)
        assertFalse(base.copy(flags = base.flags or (1 shl 3)).transportCompatible)
        assertFalse(base.copy(flags = base.flags and GattProfile.FRAME_ACCESS_FLAG_TOKEN_AUTH_REQUIRED.inv()).usable)
        assertFalse(base.copy(flags = base.flags and GattProfile.FRAME_ACCESS_FLAG_FACE_META_V1.inv()).usable)
        assertFalse(base.copy(tokenHex = "00000000000000000000000000000000").usable)
        assertFalse(base.copy(ipv4 = "255.255.255.255").usable)
    }

    @Test
    fun parserRejectsWrongLengthAndConstructorRejectsNonCanonicalIpv4() {
        assertFails { FrameAccessInfoV1.parse(ByteArray(GattProfile.FRAME_ACCESS_INFO_BYTES - 1)) }
        assertFails {
            FrameAccessInfoV1(
                version = 1,
                flags = 7,
                httpPort = 8080,
                ipv4 = "192.168.001.17",
                bootIdHex = "000102030405060708090a0b0c0d0e0f",
                tokenHex = "f0e0d0c0b0a090807060504030201000",
            )
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
