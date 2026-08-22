package vn.edu.uit.tpkd.wear.cogload.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceTimeDeviceInfoTest {
    @Test
    fun sequenceWrapRebootAndPersistenceAreDeterministic() {
        val gate = FaceSequenceGate()
        assertTrue(gate.onDeviceInfo(BOOT_A))
        assertTrue(gate.accept(FaceObservationV1(FaceObservationV1.MAX_SEQUENCE, 100, false)))
        assertTrue(gate.accept(FaceObservationV1(0, 101, false)))
        assertFalse(gate.accept(FaceObservationV1(0, 102, false)))
        assertFalse(gate.accept(FaceObservationV1(1, 99, false)))

        val restored = FaceSequenceGate()
        restored.restore(requireNotNull(gate.snapshot()))
        assertTrue(restored.accept(FaceObservationV1(1, 103, false)))
        assertFalse(restored.onDeviceInfo(BOOT_A))
        assertTrue(restored.onDeviceInfo(BOOT_B))
        assertTrue(restored.accept(FaceObservationV1(0, 1, false)))
    }

    @Test
    fun monotonicFreshnessIsIndependentOfWallClock() {
        val anchorA = EspTimeAnchor(wallClockMs = 1_000_000, monotonicMs = 10_000, uptimeMs = 5_000)
        val anchorB = EspTimeAnchor(wallClockMs = 9_000_000, monotonicMs = 10_000, uptimeMs = 5_000)
        assertEquals(1_001_000, anchorA.observedAtWallClockMs(6_000))
        assertEquals(9_001_000, anchorB.observedAtWallClockMs(6_000))
        assertFalse(anchorA.isStale(6_000, 14_000))
        assertFalse(anchorB.isStale(6_000, 14_000))
        assertTrue(anchorA.isStale(6_000, 14_001))
        assertTrue(anchorB.isStale(6_000, 14_001))
    }

    @Test
    fun deviceInfoRoundTripsAndReportsUsability() {
        val info = EspDeviceInfo(
            protocolVersion = 1,
            framingVersion = 1,
            bootIdHex = BOOT_A,
            espUptimeMs = 123_456,
            maxQualityFlags = 4,
            maxFlagLength = 16,
            nominalRateDhz = 50,
            capabilityBits = GattProfile.CAP_DETECTOR_READY or GattProfile.CAP_CAMERA_READY or GattProfile.CAP_SET_RATE,
        )
        val parsed = EspDeviceInfo.parse(info.encode())
        assertEquals(info, parsed)
        assertTrue(parsed.usable)
        assertTrue(parsed.supportsSetRate)
    }

    private companion object {
        const val BOOT_A = "00000000000000000000000000000001"
        const val BOOT_B = "00000000000000000000000000000002"
    }
}
