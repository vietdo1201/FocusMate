// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1
import java.io.File
import kotlin.math.sqrt

class PostureClassifierTest {
    @Test
    fun stableObservationsCalibrateAndClassifyGeometry() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong(), cx = 0.5, cy = 0.4, width = 0.2, height = 0.3) }))
        assertEquals(PostureState.NORMAL, classifier.classify(face(21), 1_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.53), 2_000).state)
        assertEquals(PostureState.LEAN_LEFT, classifier.classify(face(23, cx = 0.66), 3_000).state)
        assertEquals(PostureState.LEAN_RIGHT, classifier.classify(face(24, cx = 0.34), 4_000).state)
        assertEquals(PostureState.TOO_CLOSE, classifier.classify(face(25, width = 0.3, height = 0.33), 5_000).state)
    }

    @Test
    fun calibrationStaysStrictButLiveTrackingAcceptsMeasuredOffAxisConfidence() {
        val classifier = GeometryPostureClassifier()
        assertFalse(classifier.calibrate((0 until 20).map { face(it.toLong(), confidence = 0.69) }))
        assertTrue(classifier.calibrate((20 until 40).map { face(it.toLong(), confidence = 0.70) }))

        assertEquals(
            PostureState.HEAD_DOWN,
            classifier.classify(face(41, cy = 0.53, confidence = 0.50), 4_100).state,
        )
        assertEquals(
            PostureState.UNKNOWN,
            classifier.classify(face(42, cy = 0.53, confidence = 0.49), 4_200).state,
        )
    }

    @Test
    fun degradedQualityRemainsUnknownAndCannotCalibrate() {
        val classifier = GeometryPostureClassifier()
        assertFalse(
            classifier.calibrate(
                (0 until 20).map { face(it.toLong(), qualityFlags = setOf("low_light")) },
            ),
        )
        assertTrue(classifier.calibrate((20 until 40).map { face(it.toLong()) }))
        assertEquals(
            PostureState.UNKNOWN,
            classifier.classify(face(41, cy = 0.59, qualityFlags = setOf("unstable")), 4_100).state,
        )
    }

    @Test
    fun slumpedRequiresFiveContinuousSecondsAboveItsOwnThreshold() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))

        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(21, cy = 0.53), 1_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.59), 7_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.59), 9_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.59), 11_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(23, cy = 0.59), 11_999).state)
        assertEquals(PostureState.SLUMPED, classifier.classify(face(24, cy = 0.59), 12_000).state)

        assertEquals(
            PostureState.TOO_CLOSE,
            classifier.classify(face(25, cy = 0.59, width = 0.30, height = 0.33), 12_100).state,
        )
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(26, cy = 0.59), 12_200).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(26, cy = 0.59), 14_200).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(26, cy = 0.59), 16_200).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(27, cy = 0.59), 17_199).state)
        assertEquals(PostureState.SLUMPED, classifier.classify(face(28, cy = 0.59), 17_200).state)
    }

    @Test
    fun tooCloseKeepsPrecedenceOverCombinedHeadAndLeanGeometry() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertEquals(
            PostureState.TOO_CLOSE,
            classifier.classify(face(21, cx = 0.30, cy = 0.59, width = 0.30, height = 0.33), 1_000).state,
        )
    }

    @Test
    fun dominantSubjectRelativeAxisDisambiguatesNaturalSideLean() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertEquals(
            PostureState.LEAN_LEFT,
            classifier.classify(face(21, cx = 0.75, cy = 0.53), 1_000).state,
        )
        assertEquals(
            PostureState.LEAN_RIGHT,
            classifier.classify(face(22, cx = 0.25, cy = 0.53), 1_100).state,
        )
        assertEquals(
            PostureState.HEAD_DOWN,
            classifier.classify(face(23, cx = 0.34, cy = 0.57), 1_200).state,
        )
    }

    @Test
    fun q6DominanceTieMatchesFirmwareIntegerCrossProduct() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertEquals(
            PostureState.LEAN_LEFT,
            classifier.classify(face(21, cx = 0.650080, cy = 0.520064), 1_000).state,
        )
    }

    @Test
    fun evenMedianUsesFirmwareQ6FloorAtLeanBoundary() {
        val classifier = GeometryPostureClassifier()
        val calibration = (0 until 20).map { index ->
            face(index.toLong(), cx = if (index < 10) 0.500000 else 0.500001)
        }
        assertTrue(classifier.calibrate(calibration))
        assertEquals(0.500000, classifier.baseline()!!.centerX, 0.0)
        assertEquals(PostureState.LEAN_LEFT, classifier.classify(face(21, cx = 0.650000), 1_000).state)
    }

    @Test
    fun tinyBaselineAreaRatioSaturatesInsteadOfWrapping() {
        val classifier = GeometryPostureClassifier()
        assertTrue(
            classifier.calibrate(
                (0 until 20).map {
                    face(it.toLong(), width = 0.00325, height = 0.004, confidence = 0.95)
                },
            ),
        )
        assertEquals(
            PostureState.TOO_CLOSE,
            classifier.classify(face(21, width = 0.22334, height = 0.25), 1_000).state,
        )
    }

    @Test
    fun staleAndInvalidSamplesBreakSlumpedContinuity() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(21, cy = 0.59), 0).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(22, cy = 0.59), 2_900).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(23, cy = 0.59), 6_001).state)
        assertEquals(PostureState.UNKNOWN, classifier.classify(face(24, cy = 0.59, confidence = 0.49), 8_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(25, cy = 0.59), 10_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(25, cy = 0.59), 12_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(25, cy = 0.59), 14_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(26, cy = 0.59), 14_999).state)
        assertEquals(PostureState.SLUMPED, classifier.classify(face(27, cy = 0.59), 15_000).state)
    }

    @Test
    fun lateralDominantMotionInterruptsSlumpedTimer() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(21, cy = 0.59), 1_000).state)
        assertEquals(PostureState.LEAN_LEFT, classifier.classify(face(22, cx = 0.75, cy = 0.59), 3_000).state)
        assertEquals(PostureState.LEAN_LEFT, classifier.classify(face(23, cx = 0.75, cy = 0.59), 8_000).state)
        assertEquals(PostureState.HEAD_DOWN, classifier.classify(face(24, cy = 0.59), 8_001).state)
    }

    @Test
    fun missingOrUncalibratedDataFailsClosed() {
        val classifier = GeometryPostureClassifier()
        assertEquals(PostureState.UNKNOWN, classifier.classify(face(1), 1_000).state)
        assertEquals(PostureState.FACE_MISSING, classifier.classify(FaceObservationV1(2, 2, false), 2_000).state)
        assertFalse(classifier.isCalibrated())
    }

    @Test
    fun resetDropsSessionScopedBaseline() {
        val classifier = GeometryPostureClassifier()
        assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
        assertTrue(classifier.isCalibrated())
        classifier.reset()
        assertFalse(classifier.isCalibrated())
        assertNull(classifier.baseline())
    }

    @Test
    fun sharedGoldenVectorsMatchFirmwareShadowClassifier() {
        val startDirectory = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val fixture = generateSequence(startDirectory) { it.parentFile }
            .map { it.resolve("tests/golden/posture_geometry_v2.tsv") }
            .firstOrNull(File::isFile)
        assertTrue("Missing shared fixture above: $startDirectory", fixture != null)
        val fixtureFile = requireNotNull(fixture)
        fixtureFile.readLines()
            .drop(1)
            .filter(String::isNotBlank)
            .forEachIndexed { index, line ->
                val columns = line.split('\t')
                val dx = columns[1].toDouble()
                val dy = columns[2].toDouble()
                val areaRatio = columns[3].toDouble()
                val holdMs = columns[4].toLong()
                val expected = PostureState.valueOf(columns[5])
                val classifier = GeometryPostureClassifier()
                assertTrue(classifier.calibrate((0 until 20).map { face(it.toLong()) }))
                val scale = sqrt(areaRatio)
                val observation = face(
                    sequence = 100L + index,
                    cx = 0.5 - dx,
                    cy = 0.4 + dy,
                    width = 0.2 * scale,
                    height = 0.3 * scale,
                )
                if (holdMs > 0) {
                    classifier.classify(observation, 1_000L)
                    var elapsed = 2_000L
                    while (elapsed < holdMs) {
                        classifier.classify(observation, 1_000L + elapsed)
                        elapsed += 2_000L
                    }
                }
                assertEquals(columns[0], expected, classifier.classify(observation, 1_000L + holdMs).state)
            }
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
        confidence: Double = 0.95,
        qualityFlags: Set<String> = emptySet(),
    ) = FaceObservationV1(
        sequence = sequence,
        espUptimeMs = sequence * 100,
        faceDetected = true,
        centerX = cx,
        centerY = cy,
        width = width,
        height = height,
        area = width * height,
        confidence = confidence,
        qualityFlags = qualityFlags,
    )
}
