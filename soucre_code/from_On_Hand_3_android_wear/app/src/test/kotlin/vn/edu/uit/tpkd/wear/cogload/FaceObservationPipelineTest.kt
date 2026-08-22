package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.uit.tpkd.wear.cogload.protocol.EspDeviceInfo
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1
import vn.edu.uit.tpkd.wear.cogload.protocol.GattProfile

class FaceObservationPipelineTest {
    @Test
    fun simulatorMtu23CalibratesThenPublishesLivePosture() {
        var wall = 1_000_000L
        var mono = 10_000L
        val updates = mutableListOf<PostureIngestionUpdate>()
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { wall },
            monotonicMs = { mono },
            onUpdate = updates::add,
            onRuntime = states::add,
        )
        assertTrue(ingestor.onDeviceInfo(deviceInfo(1_000L).encode()).getOrThrow().usable)
        val simulator = FaceObservationSimulator()
        repeat(20) { index ->
            mono = 10_000L + index * 100L
            simulator.notifications(face(index.toLong(), 1_000L + index * 100L), 23)
                .forEach(ingestor::onNotification)
        }
        assertEquals(PostureState.NORMAL, updates.single().classification.state)
        assertEquals(PostureRuntimePhase.LIVE, states.last().phase)
        assertTrue(states.last().notificationRateHz!! > 0.0)
    }

    @Test
    fun corruptCrcAndDegradedSamplesCannotReachLivePipeline() {
        var mono = 10_000L
        val updates = mutableListOf<PostureIngestionUpdate>()
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { 1_000_000L },
            monotonicMs = { mono },
            onUpdate = updates::add,
            onRuntime = states::add,
        )
        ingestor.onDeviceInfo(deviceInfo(1_000L).encode()).getOrThrow()
        val simulator = FaceObservationSimulator()
        repeat(20) { index ->
            mono += 100L
            simulator.notifications(
                face(index.toLong(), 1_000L + (index + 1L) * 100L),
                23,
                SimulatorFaults(corruptCrc = index == 0, degradedQuality = index > 0),
            ).forEach(ingestor::onNotification)
        }
        assertTrue(updates.isEmpty())
        assertEquals(PostureRuntimePhase.CALIBRATING, states.last().phase)
    }

    @Test
    fun staleUsesMonotonicClockAndRebootInvalidatesCalibration() {
        var wall = 1_000_000L
        var mono = 10_000L
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { wall },
            monotonicMs = { mono },
            onRuntime = states::add,
        )
        val simulator = FaceObservationSimulator()
        ingestor.onDeviceInfo(deviceInfo(1_000L).encode()).getOrThrow()
        wall = 9_000_000L // Wall-clock jump must not affect freshness.
        mono = 20_000L
        simulator.notifications(face(1, 1_100L), 517).forEach(ingestor::onNotification)
        assertEquals(PostureRuntimePhase.STALE, states.last().phase)

        ingestor.onDeviceInfo(deviceInfo(50L, boot = "ffeeddccbbaa99887766554433221100").encode()).getOrThrow()
        assertEquals(PostureRuntimePhase.CALIBRATING, states.last().phase)
    }

    @Test
    fun trackerPauseAndResetBoundSessionState() {
        val tracker = PostureInsightTracker()
        repeat(100) { index ->
            val at = index * 1_000L
            tracker.observe(if (index % 2 == 0) PostureState.HEAD_DOWN else PostureState.NORMAL, at)
        }
        assertFalse(tracker.summaries(100_000L).isEmpty())
        tracker.pause()
        tracker.observe(PostureState.NORMAL, 0L) // New active block has a new monotonic origin.
        tracker.reset()
        assertTrue(tracker.summaries().isEmpty())
    }

    @Test
    fun transportStubReportsRateWithoutClaimingCameraOrDetector() {
        var mono = 10_000L
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { 1_000_000L },
            monotonicMs = { mono },
            onRuntime = states::add,
        )
        val stubInfo = deviceInfo(1_000L).copy(
            capabilityBits = GattProfile.CAP_SET_RATE or
                GattProfile.CAP_REPORTS_LOW_LIGHT or GattProfile.CAP_REPORTS_UNSTABLE,
        )
        assertFalse(ingestor.onDeviceInfo(stubInfo.encode()).getOrThrow().usable)
        val simulator = FaceObservationSimulator()
        repeat(5) { index ->
            mono += 200L
            simulator.notifications(
                FaceObservationV1(
                    sequence = index.toLong(),
                    espUptimeMs = 1_000L + index * 200L,
                    faceDetected = false,
                ),
                23,
            ).forEach(ingestor::onNotification)
        }
        assertEquals(PostureRuntimePhase.UNAVAILABLE, states.last().phase)
        assertTrue(states.last().detail.contains("Transport OK"))
        assertTrue(states.last().notificationRateHz!! > 0.0)
    }

    @Test
    fun cameraSmokeCapabilityDoesNotClaimDetectorReadiness() {
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { 1_000_000L },
            monotonicMs = { 10_000L },
            onRuntime = states::add,
        )
        val cameraOnly = deviceInfo(1_000L).copy(
            capabilityBits = GattProfile.CAP_CAMERA_READY or GattProfile.CAP_SET_RATE or
                GattProfile.CAP_REPORTS_LOW_LIGHT or GattProfile.CAP_REPORTS_UNSTABLE,
        )

        assertFalse(ingestor.onDeviceInfo(cameraOnly.encode()).getOrThrow().usable)
        assertEquals(PostureRuntimePhase.UNAVAILABLE, states.last().phase)
        assertEquals("Transport OK; camera OK; detector chưa sẵn sàng", states.last().detail)
    }

    @Test
    fun reconnectAndDisconnectNeverExposeStaleMtuOrRate() {
        var mono = 10_000L
        val states = mutableListOf<PostureRuntimeSnapshot>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { 1_000_000L },
            monotonicMs = { mono },
            onRuntime = states::add,
        )
        ingestor.onMtuChanged(256)
        ingestor.onDeviceInfo(deviceInfo(1_000L).encode()).getOrThrow()
        mono += 200L
        FaceObservationSimulator().notifications(face(0, 1_200L), 256)
            .forEach(ingestor::onNotification)
        assertTrue(states.last().notificationRateHz != null)

        ingestor.disconnected("GATT ngắt kết nối (8)")
        assertEquals(PostureRuntimePhase.DISCONNECTED, states.last().phase)
        assertEquals(null, states.last().mtu)
        assertEquals(null, states.last().notificationRateHz)

        ingestor.connecting()
        assertEquals(PostureRuntimePhase.CONNECTING, states.last().phase)
        assertEquals(null, states.last().mtu)
        assertEquals(null, states.last().notificationRateHz)
        ingestor.bonding()
        assertEquals(PostureRuntimePhase.BONDING, states.last().phase)
    }

    private fun deviceInfo(uptime: Long, boot: String = "00112233445566778899aabbccddeeff") = EspDeviceInfo(
        protocolVersion = GattProfile.PROTOCOL_VERSION,
        framingVersion = GattProfile.FRAMING_VERSION,
        bootIdHex = boot,
        espUptimeMs = uptime,
        maxQualityFlags = 4,
        maxFlagLength = 16,
        nominalRateDhz = 50,
        capabilityBits = GattProfile.CAP_DETECTOR_READY or GattProfile.CAP_CAMERA_READY or
            GattProfile.CAP_REPORTS_LOW_LIGHT or GattProfile.CAP_REPORTS_UNSTABLE,
    )

    private fun face(sequence: Long, uptime: Long) = FaceObservationV1(
        sequence = sequence,
        espUptimeMs = uptime,
        faceDetected = true,
        centerX = 0.5,
        centerY = 0.4,
        width = 0.2,
        height = 0.3,
        area = 0.06,
        confidence = 0.95,
    )
}
