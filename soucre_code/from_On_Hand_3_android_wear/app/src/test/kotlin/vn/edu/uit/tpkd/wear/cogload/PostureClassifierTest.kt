package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1

class PostureClassifierTest {
    @Test
    fun stableObservationsCalibrateAndClassifyGeometry() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong(), cx = 0.5, cy = 0.4, width = 0.2, height = 0.3) }))
        assertEquals(PostureState.NORMAL, classifier.classify(face(21), 1_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.53), 2_000).state)
        assertEquals(PostureState.LEAN_LEFT, classifier.classify(face(23, cx = 0.34), 3_000).state)
        assertEquals(PostureState.LEAN_RIGHT, classifier.classify(face(24, cx = 0.66), 4_000).state)
        assertEquals(PostureState.TOO_CLOSE, classifier.classify(face(25, width = 0.3, height = 0.33), 5_000).state)
    }

    @Test
    fun missingOrUncalibratedDataFailsClosed() {
        val classifier = GeometryPostureClassifier()
        assertEquals(PostureState.UNKNOWN, classifier.classify(face(1), 1_000).state)
        assertEquals(PostureState.FACE_MISSING, classifier.classify(FaceObservationV1(2, 2, false), 2_000).state)
        assertFalse(classifier.isCalibrated())
    }

    @Test
    fun postureInsightUsesConservativeThresholdsAndNeverReturnsPrompt() {
        val tracker = PostureInsightTracker()
        assertTrue(tracker.observe(PostureState.HEAD_DOWN, 0L).isEmpty())
        assertTrue(tracker.observe(PostureState.HEAD_DOWN, 179_000L).isEmpty())
        val continuous = tracker.observe(PostureState.HEAD_DOWN, 180_000L)
        assertEquals(PostureInsightTracker.INSIGHT_V2_POSTURE_CONTINUOUS, continuous.single().reasonCode)
        assertNull(PostureInsightTracker().breakSuggestion())
        assertTrue(tracker.breakSuggestion(180_000L)?.isNotBlank() == true)
    }

    @Test
    fun fourthEpisodeWithinFifteenMinutesCreatesRepeatedInsight() {
        val tracker = PostureInsightTracker()
        var repeated: List<PostureInsight> = emptyList()
        repeat(4) { index ->
            val start = index * 3 * 60_000L
            repeated = tracker.observe(PostureState.LEAN_LEFT, start)
            tracker.observe(PostureState.NORMAL, start + 30_000L)
        }
        assertEquals(PostureInsightTracker.INSIGHT_V2_POSTURE_REPEATED, repeated.single().reasonCode)
        assertEquals(4, tracker.summaries(10 * 60_000L).single().episodeCount)
    }

    private fun face(
        sequence: Long,
        cx: Double = 0.5,
        cy: Double = 0.4,
        width: Double = 0.2,
        height: Double = 0.3,
    ) = FaceObservationV1(
        sequence = sequence,
        espUptimeMs = sequence * 100,
        faceDetected = true,
        centerX = cx,
        centerY = cy,
        width = width,
        height = height,
        area = width * height,
        confidence = 0.95,
    )
}
