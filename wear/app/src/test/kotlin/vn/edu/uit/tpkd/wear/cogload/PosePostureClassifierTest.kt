// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

class PosePostureClassifierTest {
    @Test
    fun calibrationRequiresTwentyUniqueSamplesSpanningAtLeastFiveSeconds() {
        val classifier = PosePostureClassifier()
        repeat(20) { index -> classifier.observeFeatures(index.toLong(), index * 250L, neutral(), true) }
        assertFalse(classifier.isCalibrated())
        val ready = classifier.observeFeatures(20, 5_000L, neutral(), true)
        assertTrue(classifier.isCalibrated())
        assertTrue(ready.calibration.calibrated)
        assertEquals(20, ready.calibration.acceptedSamples)
        assertEquals(PostureState.NORMAL, ready.rawState)
    }

    @Test
    fun lowFpsCalibrationMayTakeLongerThanFiveSeconds() {
        val classifier = PosePostureClassifier()
        repeat(20) { index -> classifier.observeFeatures(index.toLong(), index * 300L, neutral(), true) }
        assertTrue(classifier.isCalibrated())
    }

    @Test
    fun duplicatesOldFramesGapsAndLowQualityCannotFakeCalibration() {
        val classifier = PosePostureClassifier()
        assertEquals(1, classifier.observeFeatures(7, 0, neutral(), true).calibration.acceptedSamples)
        assertEquals(1, classifier.observeFeatures(7, 100, neutral(), true).calibration.acceptedSamples)
        assertEquals("duplicate_or_reordered_frame", classifier.observeFeatures(6, 200, neutral(), true).calibration.reason)
        assertEquals(1, classifier.observeFeatures(8, 2_000, neutral(), true).calibration.acceptedSamples)
        assertEquals(1, classifier.observeFeatures(9, 2_200, neutral(quality = 0.69), true).calibration.acceptedSamples)
        assertFalse(classifier.isCalibrated())
    }

    @Test
    fun isolatedJitterIsIgnoredWithoutErasingStableCalibrationHistory() {
        val classifier = PosePostureClassifier()
        assertEquals(1, classifier.observeFeatures(1, 0, neutral(), true).calibration.acceptedSamples)
        assertEquals(1, classifier.observeFeatures(2, 250, neutral(headRollDeg = 6.0), true).calibration.acceptedSamples)
        assertEquals(2, classifier.observeFeatures(3, 500, neutral(), true).calibration.acceptedSamples)
    }

    @Test
    fun anatomicalSubjectLeftProjectionDoesNotDependOnImageXDirection() {
        val original = extractPoseFeatures(landmarks(noseX = 0.56), faceMeta())!!
        val mirrored = extractPoseFeatures(
            landmarks(noseX = 0.56).map { it.copy(x = 1.0 - it.x) },
            faceMeta().mirrorX(),
        )!!
        assertEquals(original.lateralHead, mirrored.lateralHead, 0.000001)
        assertTrue(original.lateralHead > 0.0)
    }

    @Test
    fun missingPoseAndEspFaceMustAgreeBeforeFaceMissing() {
        val classifier = calibrated()
        repeat(4) { index -> classifier.observeFeatures(40L + index, 7_000L + index * 250L, null, false) }
        assertEquals(
            PostureState.FACE_MISSING,
            classifier.observeFeatures(44, 8_000, null, false).classification?.state,
        )
        repeat(4) { index -> classifier.observeFeatures(45L + index, 8_250L + index * 250L, null, true) }
        val disagreement = classifier.observeFeatures(49, 9_250, null, true)
        assertEquals(PostureState.UNKNOWN, disagreement.classification?.state)
        assertEquals("pose_missing_esp_face_present", disagreement.calibration.reason)
    }

    @Test
    fun validPoseRemainsPrimaryWhenEspDetectorTemporarilyMissesFaceAndStaleIsImmediate() {
        val classifier = calibratedAndSettled()
        repeat(4) { index ->
            classifier.observeFeatures(40L + index, 7_000L + index * 250L, neutral(headRollDeg = 13.0), false)
        }
        assertEquals(
            PostureState.LEAN_LEFT,
            classifier.observeFeatures(44, 8_000, neutral(headRollDeg = 13.0), false).classification?.state,
        )
        assertEquals(PostureState.UNKNOWN, classifier.stale(11_001)?.classification?.state)
        assertNull(classifier.stale(11_100))
    }

    @Test
    fun stateChangesNeedOneSecondAndHysteresisPreventsBoundaryFlicker() {
        val classifier = calibratedAndSettled()
        val leaned = neutral(headRollDeg = 13.0)
        assertEquals(PostureState.NORMAL, classifier.observeFeatures(40, 7_000, leaned, true).classification?.state)
        assertEquals(PostureState.NORMAL, classifier.observeFeatures(41, 7_999, leaned, true).classification?.state)
        assertEquals(PostureState.LEAN_LEFT, classifier.observeFeatures(42, 8_000, leaned, true).classification?.state)
        repeat(3) { index ->
            assertEquals(
                PostureState.LEAN_LEFT,
                classifier.observeFeatures(43L + index, 8_250L + index * 250L, neutral(headRollDeg = 8.0), true).classification?.state,
            )
        }
    }

    @Test
    fun sharedWebAndWatchGoldenFeaturesProduceSameVocabularyAndPrecedence() {
        val fixture = findFixture("tests/golden/posture_landmarks_v1.tsv")
        val rows = fixture.readLines().filter(String::isNotBlank)
        val names = rows.first().split('\t')
        rows.drop(1).forEach { line ->
            val values = line.split('\t')
            val row = names.zip(values).toMap()
            val classifier = calibrated()
            val features = neutral(
                headRollDeg = row.getValue("head_roll_deg").toDouble(),
                torsoLeanDeg = row.getValue("torso_lean_deg").toDouble(),
                lateralHead = row.getValue("lateral_head").toDouble(),
                headHeight = row.getValue("head_height").toDouble(),
                eyeHeight = row.getValue("eye_height").toDouble(),
                facePitch = row.getValue("face_pitch").toDouble(),
                faceScale = row.getValue("face_scale").toDouble(),
                torsoLength = row.getValue("torso_length").toDouble(),
            )
            val holdMs = row.getValue("hold_ms").toLong()
            val sampleIntervalMs = 300L
            val requiredDurationMs = maxOf(PoseGeometryConfig().labelDebounceMs, holdMs) + sampleIntervalMs
            val frames = ceil(requiredDurationMs / sampleIntervalMs.toDouble()).toInt() + 1
            var result: PoseClassifierUpdate? = null
            repeat(frames) { index ->
                result = classifier.observeFeatures(40L + index, 7_000L + index * sampleIntervalMs, features, true)
            }
            assertEquals(row.getValue("name"), PostureState.valueOf(row.getValue("expected")), result?.classification?.state)
        }
    }

    @Test
    fun resetDropsSessionScopedModelAndProfileBaseline() {
        val classifier = calibrated()
        classifier.reset()
        assertFalse(classifier.isCalibrated())
        assertNull(classifier.observeFeatures(0, 0, null, false).classification)
        assertTrue(PosePostureClassifier.PROFILE_FINGERPRINT.contains("59929e1d"))
    }

    private fun calibrated(): PosePostureClassifier = PosePostureClassifier().also { classifier ->
        repeat(26) { index -> classifier.observeFeatures(index.toLong(), index * 200L, neutral(), true) }
        assertTrue(classifier.isCalibrated())
    }

    private fun calibratedAndSettled(): PosePostureClassifier = calibrated().also { classifier ->
        repeat(2) { index -> classifier.observeFeatures(30L + index, 5_200L + index * 250L, neutral(), true) }
        assertEquals(PostureState.NORMAL, classifier.observeFeatures(32, 6_000, neutral(), true).classification?.state)
    }

    private fun neutral(
        quality: Double = 0.95,
        headRollDeg: Double = 0.0,
        torsoLeanDeg: Double = 0.0,
        lateralHead: Double = 0.0,
        headHeight: Double = 1.0,
        eyeHeight: Double = 0.9,
        facePitch: Double? = 0.5,
        faceScale: Double = 0.20,
        torsoLength: Double? = 0.40,
    ) = PoseFeatures(
        quality = quality,
        headRollDeg = headRollDeg,
        torsoLeanDeg = torsoLeanDeg,
        shoulderAngleDeg = 0.0,
        lateralHead = lateralHead,
        headHeight = headHeight,
        eyeHeight = eyeHeight,
        facePitch = facePitch,
        faceScale = faceScale,
        shoulderWidth = 0.20,
        torsoLength = torsoLength,
    )

    private fun landmarks(noseX: Double): List<PoseLandmarkPoint> {
        val points = MutableList(33) { PoseLandmarkPoint(0.5, 0.5, visibility = 0.95, presence = 0.95) }
        points[0] = PoseLandmarkPoint(noseX, 0.24, visibility = 0.95, presence = 0.95)
        points[2] = PoseLandmarkPoint(0.55, 0.28, visibility = 0.95, presence = 0.95)
        points[5] = PoseLandmarkPoint(0.45, 0.28, visibility = 0.95, presence = 0.95)
        points[11] = PoseLandmarkPoint(0.60, 0.45, visibility = 0.95, presence = 0.95)
        points[12] = PoseLandmarkPoint(0.40, 0.45, visibility = 0.95, presence = 0.95)
        points[23] = PoseLandmarkPoint(0.58, 0.80, visibility = 0.95, presence = 0.95)
        points[24] = PoseLandmarkPoint(0.42, 0.80, visibility = 0.95, presence = 0.95)
        return points
    }

    private fun faceMeta() = PoseFaceMetaV1(
        detected = true,
        confidence = 0.95,
        centerX = 0.5,
        centerY = 0.3,
        width = 0.2,
        height = 0.2,
        leftEye = PoseFacePoint(0.55, 0.27),
        leftMouth = PoseFacePoint(0.54, 0.35),
        nose = PoseFacePoint(0.50, 0.31),
        rightEye = PoseFacePoint(0.45, 0.27),
        rightMouth = PoseFacePoint(0.46, 0.35),
    )

    private fun PoseFaceMetaV1.mirrorX() = copy(
        centerX = 1.0 - centerX,
        leftEye = leftEye.copy(x = 1.0 - leftEye.x),
        leftMouth = leftMouth.copy(x = 1.0 - leftMouth.x),
        nose = nose.copy(x = 1.0 - nose.x),
        rightEye = rightEye.copy(x = 1.0 - rightEye.x),
        rightMouth = rightMouth.copy(x = 1.0 - rightMouth.x),
    )

    private fun findFixture(relative: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(start) { it.parentFile }
            .map { it.resolve(relative) }
            .firstOrNull(File::isFile)
            ?: error("Missing fixture $relative above $start")
    }
}
