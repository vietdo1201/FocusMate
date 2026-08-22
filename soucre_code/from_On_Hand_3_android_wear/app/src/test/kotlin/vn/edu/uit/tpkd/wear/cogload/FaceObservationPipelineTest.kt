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
    fun calibrationCountsValidFaceSamplesInsteadOfRecentNotifications() {
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
        var halfwayDetail = ""
        repeat(40) { index ->
            mono = 10_000L + index * 100L
            val observation = if (index % 2 == 0) {
                FaceObservationV1(
                    sequence = index.toLong(),
                    espUptimeMs = 1_000L + index * 100L,
                    faceDetected = false,
                )
            } else {
                face(index.toLong(), 1_000L + index * 100L)
            }
            simulator.notifications(observation, 256).forEach(ingestor::onNotification)
            if (index == 19) halfwayDetail = states.last().detail
        }

        assertEquals("10/20 mẫu", halfwayDetail)
        assertEquals(PostureRuntimePhase.LIVE, states.last().phase)
        assertEquals(PostureState.NORMAL, updates.single().classification.state)
    }

    @Test
    fun repeatedDetectorTimestampCountsTransportButOnlyOneCalibrationSample() {
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
            mono += 50L
            simulator.notifications(face(index.toLong(), 1_100L), 256)
                .forEach(ingestor::onNotification)
        }

        assertTrue(updates.isEmpty())
        assertEquals(PostureRuntimePhase.CALIBRATING, states.last().phase)
        assertEquals("1/20 mẫu", states.last().detail)

        repeat(19) { index ->
            mono += 100L
            simulator.notifications(face(20L + index, 1_200L + index * 100L), 256)
                .forEach(ingestor::onNotification)
        }

        assertEquals(PostureState.NORMAL, updates.single().classification.state)
        // All 39 complete notifications contribute to transport telemetry,
        // even though only 20 unique detector timestamps reach calibration.
        assertTrue(states.last().notificationRateHz!! > 10.0)
    }

    @Test
    fun duplicateTimestampCannotPublishOrDisturbSlumpedTimer() {
        var mono = 10_000L
        val updates = mutableListOf<PostureIngestionUpdate>()
        val ingestor = FaceObservationIngestor(
            wallClockMs = { 1_000_000L },
            monotonicMs = { mono },
            onUpdate = updates::add,
        )
        ingestor.onDeviceInfo(deviceInfo(1_000L).encode()).getOrThrow()
        val simulator = FaceObservationSimulator()
        repeat(20) { index ->
            mono += 100L
            simulator.notifications(face(index.toLong(), 1_100L + index * 100L), 256)
                .forEach(ingestor::onNotification)
        }
        assertEquals(PostureState.NORMAL, updates.single().classification.state)

        mono += 100L
        simulator.notifications(face(20, 4_000L, cy = 0.59), 256)
            .forEach(ingestor::onNotification)
        assertEquals(PostureState.HEAD_DOWN, updates.last().classification.state)
        val publishedBeforeDuplicates = updates.size

        repeat(10) { index ->
            mono += 100L
            simulator.notifications(face(21L + index, 4_000L, cy = 0.59), 256)
                .forEach(ingestor::onNotification)
        }
        assertEquals(publishedBeforeDuplicates, updates.size)

        mono += 100L
        simulator.notifications(face(31, 6_500L, cy = 0.59), 256)
            .forEach(ingestor::onNotification)
        assertEquals(PostureState.HEAD_DOWN, updates.last().classification.state)
        mono += 100L
        simulator.notifications(face(32, 8_999L, cy = 0.59), 256)
            .forEach(ingestor::onNotification)
        assertEquals(PostureState.HEAD_DOWN, updates.last().classification.state)
        mono += 100L
        simulator.notifications(face(33, 9_000L, cy = 0.59), 256)
            .forEach(ingestor::onNotification)
        assertEquals(PostureState.SLUMPED, updates.last().classification.state)
    }

    @Test
    fun repeatedTimestampCanStillMakeRuntimeStaleWithoutPublishingPosture() {
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
            simulator.notifications(face(index.toLong(), 1_100L + index * 100L), 256)
                .forEach(ingestor::onNotification)
        }
        val publishedBeforeDuplicate = updates.size

        mono += 3_001L
        simulator.notifications(face(20, 3_000L), 256).forEach(ingestor::onNotification)

        assertEquals(publishedBeforeDuplicate, updates.size)
        assertEquals(PostureRuntimePhase.STALE, states.last().phase)
        assertTrue(states.last().notificationRateHz!! > 0.0)
    }

    @Test
    fun strictUptimeGateSurvivesSequenceWrapRejectsRegressionAndResetsOnReboot() {
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
            val sequence = (FaceObservationV1.MAX_SEQUENCE - 9L + index) and FaceObservationV1.MAX_SEQUENCE
            simulator.notifications(face(sequence, 1_100L + index * 100L), 256)
                .forEach(ingestor::onNotification)
        }
        assertEquals(PostureState.NORMAL, updates.single().classification.state)

        val publishedBeforeRegression = updates.size
        mono += 100L
        simulator.notifications(face(10, 2_900L), 256).forEach(ingestor::onNotification)
        assertEquals(publishedBeforeRegression, updates.size)
        mono += 100L
        simulator.notifications(face(11, 3_100L), 256).forEach(ingestor::onNotification)
        assertEquals(publishedBeforeRegression + 1, updates.size)

        ingestor.onDeviceInfo(
            deviceInfo(50L, boot = "ffeeddccbbaa99887766554433221100").encode(),
        ).getOrThrow()
        assertEquals(PostureRuntimePhase.CALIBRATING, states.last().phase)
        repeat(20) { index ->
            mono += 100L
            simulator.notifications(face(index.toLong(), 100L + index * 100L), 256)
                .forEach(ingestor::onNotification)
        }
        assertEquals(3, updates.count { it.classification.state == PostureState.NORMAL })
        assertEquals(PostureRuntimePhase.LIVE, states.last().phase)
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
        assertEquals("0/20 mẫu", states.last().detail)
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

    private fun face(sequence: Long, uptime: Long, cy: Double = 0.4) = FaceObservationV1(
        sequence = sequence,
        espUptimeMs = uptime,
        faceDetected = true,
        centerX = 0.5,
        centerY = cy,
        width = 0.2,
        height = 0.3,
        area = 0.06,
        confidence = 0.95,
    )
}
