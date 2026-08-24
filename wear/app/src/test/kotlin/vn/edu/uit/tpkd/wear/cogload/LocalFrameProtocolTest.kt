// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import vn.edu.uit.tpkd.wear.cogload.protocol.FrameAccessInfoV1
import vn.edu.uit.tpkd.wear.cogload.protocol.GattProfile

class LocalFrameProtocolTest {
    @Test
    fun endpointOnlyAcceptsNumericIpv4AndNeverPlacesTokenInUrl() {
        val endpoint = LocalFrameAccessEndpoint(
            ipv4 = "192.168.1.17",
            port = 80,
            bootIdHex = "00112233445566778899aabbccddeeff",
            tokenHex = "ffeeddccbbaa99887766554433221100",
            lanReady = true,
            tokenAuthRequired = true,
            faceMetaV1 = true,
        )
        assertEquals("http://192.168.1.17:80/api/watch/frame?after=42", endpoint.frameUrl(42).toString())
        assertFalse(endpoint.frameUrl(42).toString().contains(endpoint.tokenHex))
        assertThrows(IllegalArgumentException::class.java) {
            endpoint.copy(ipv4 = "8.8.8.8")
        }
    }

    @Test
    fun encryptedGattInfoMapsOnlyWhenAllLocalFrameFlagsAndPrivateIpAreUsable() {
        val flags = GattProfile.FRAME_ACCESS_FLAG_LAN_READY or
            GattProfile.FRAME_ACCESS_FLAG_TOKEN_AUTH_REQUIRED or
            GattProfile.FRAME_ACCESS_FLAG_FACE_META_V1
        val info = FrameAccessInfoV1(
            version = 1,
            flags = flags,
            httpPort = 80,
            ipv4 = "192.168.1.17",
            bootIdHex = "00112233445566778899aabbccddeeff",
            tokenHex = "ffeeddccbbaa99887766554433221100",
        )
        assertEquals("192.168.1.17", info.toLocalFrameAccessEndpointOrNull()?.ipv4)
        assertEquals(null, info.copy(flags = GattProfile.FRAME_ACCESS_FLAG_LAN_READY).toLocalFrameAccessEndpointOrNull())
        assertEquals(null, info.copy(ipv4 = "8.8.8.8").toLocalFrameAccessEndpointOrNull())
    }

    @Test
    fun validatesFrameHeadersAndJpegEnvelope() {
        val headers = mapOf(
            "X-FocusMate-Frame-Sequence" to "4294967295",
            "X-FocusMate-Observed-Uptime-Ms" to "123456",
            "X-FocusMate-Face-Meta-V1" to faceMetaHeader(detected = true),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val parsed = LocalFrameResponseParser.parse(headers::get, "image/jpeg", jpeg)
        assertEquals(4_294_967_295L, parsed.sequence)
        assertEquals(123_456L, parsed.observedEspUptimeMs)
        assertTrue(parsed.faceMetaV1.detected)
        assertEquals(0.9, parsed.faceMetaV1.confidence, 0.001)
    }

    @Test
    fun severeThermalStatePausesPoseButModerateOnlyThrottles() {
        assertEquals(PostureThermalState.MODERATE, mapThermalStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(PostureThermalState.MODERATE.allowsLocalPose())
        assertEquals(500L, PostureThermalState.MODERATE.localPosePollDelayMs())
        assertFalse(PostureThermalState.SEVERE.allowsLocalPose())
    }

    @Test
    fun parsesOptionalYawnSyncV2WithoutBreakingTheJpegEnvelope() {
        val headers = mapOf(
            "X-FocusMate-Frame-Sequence" to "7",
            "X-FocusMate-Observed-Uptime-Ms" to "123456",
            "X-FocusMate-Face-Meta-V1" to faceMetaHeader(detected = true),
            "X-FocusMate-Yawn-Sequence" to "9",
            "X-FocusMate-Yawn-Client" to "1",
            "X-FocusMate-Yawn-Total" to "4",
            "X-FocusMate-Yawn-Window" to "2",
            "X-FocusMate-Yawn-Observed-Uptime-Ms" to "123000",
            "X-FocusMate-Yawn-Schema" to "2",
            "X-FocusMate-Yawn-Session" to "00112233445566778899aabbccddeeff",
            "X-FocusMate-Yawn-Revision" to "9",
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val sync = LocalFrameResponseParser.parse(headers::get, "image/jpeg", jpeg).yawnSync!!
        assertEquals(2, sync.schema)
        assertEquals("00112233445566778899aabbccddeeff", sync.session)
        assertEquals(9L, sync.revision)
        assertEquals(4, sync.totalCount)
    }

    @Test
    fun screenAndThermalPoliciesSelectFiveTwoAndOneHertz() {
        assertEquals(50, watchConnectionPowerPolicy(true, PostureThermalState.NOMINAL).rateDhz)
        assertEquals(20, watchConnectionPowerPolicy(false, PostureThermalState.NOMINAL).rateDhz)
        assertEquals(20, watchConnectionPowerPolicy(true, PostureThermalState.MODERATE).rateDhz)
        assertEquals(10, watchConnectionPowerPolicy(true, PostureThermalState.SEVERE).rateDhz)
        assertEquals(3_000L, bleWatchdogThresholds(50).restartMs)
        assertEquals(16_000L, bleWatchdogThresholds(10).reconnectMs)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
            (0..5).map(::reconnectBaseDelayMs))
    }

    @Test
    fun localFrameFailuresBackOffInsteadOfPollingAtInferenceRate() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(::localFrameFailureBackoffMs),
        )
    }

    private fun faceMetaHeader(detected: Boolean): String {
        val values = intArrayOf(
            if (detected) 1 else 0,
            (0.9 * 65_535).toInt(),
            32_768,
            24_000,
            13_000,
            18_000,
            28_000,
            20_000,
            29_000,
            27_000,
            32_000,
            23_000,
            36_000,
            20_000,
            35_000,
            27_000,
        )
        val bytes = ByteArray(32)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
