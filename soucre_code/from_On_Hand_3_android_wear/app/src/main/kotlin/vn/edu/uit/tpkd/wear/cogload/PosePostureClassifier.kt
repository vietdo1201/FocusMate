package vn.edu.uit.tpkd.wear.cogload

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

data class PoseLandmarkPoint(
    val x: Double,
    val y: Double,
    val z: Double = 0.0,
    val visibility: Double = 1.0,
    val presence: Double = 1.0,
) {
    val confidence: Double get() = minOf(visibility, presence).coerceIn(0.0, 1.0)
}

data class PoseFacePoint(val x: Double, val y: Double)

data class PoseFaceMetaV1(
    val detected: Boolean,
    val confidence: Double,
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val leftEye: PoseFacePoint,
    val leftMouth: PoseFacePoint,
    val nose: PoseFacePoint,
    val rightEye: PoseFacePoint,
    val rightMouth: PoseFacePoint,
)

data class PoseFrameObservation(
    val frameSequence: Long,
    val observedAtMonoMs: Long,
    val landmarks: List<PoseLandmarkPoint>,
    val faceMeta: PoseFaceMetaV1? = null,
) {
    init {
        require(frameSequence in 0L..UINT32_MAX)
        require(observedAtMonoMs >= 0L)
    }

    private companion object {
        const val UINT32_MAX = 4_294_967_295L
    }
}

data class PoseCalibrationProgress(
    val acceptedSamples: Int,
    val requiredSamples: Int,
    val reason: String,
    val calibrated: Boolean,
    val stableMs: Long = 0L,
)

data class PoseClassifierUpdate(
    val classification: PostureClassification?,
    val rawState: PostureState,
    val calibration: PoseCalibrationProgress,
)

data class PoseGeometryConfig(
    val calibrationSamples: Int = 20,
    val calibrationMinimumSpanMs: Long = 5_000L,
    val calibrationMaximumGapMs: Long = 1_500L,
    val maximumContinuousGapMs: Long = 3_000L,
    val minimumLandmarkConfidence: Double = 0.70,
    val headRollEnterDeg: Double = 10.0,
    val torsoLeanEnterDeg: Double = 8.0,
    val lateralEnter: Double = 0.12,
    val headDropEnter: Double = 0.12,
    val eyeDropEnter: Double = 0.10,
    val facePitchEnter: Double = 0.08,
    val tooCloseScaleRatio: Double = 1.35,
    val torsoCompressionEnter: Double = 0.10,
    val slumpedMinimumMs: Long = 5_000L,
    val hysteresisFactor: Double = 0.65,
    val labelDebounceMs: Long = 1_000L,
    val invalidHoldMs: Long = 800L,
)

internal data class PoseFeatures(
    val quality: Double,
    val headRollDeg: Double,
    val torsoLeanDeg: Double,
    val shoulderAngleDeg: Double,
    val lateralHead: Double,
    val headHeight: Double,
    val eyeHeight: Double,
    val facePitch: Double?,
    val faceScale: Double,
    val shoulderWidth: Double,
    val torsoLength: Double?,
)

private data class TimedPoseFeatures(val observedAtMs: Long, val features: PoseFeatures)
private data class FeatureSummary(val value: Double, val noise: Double)
private data class PoseBaseline(
    val headRollDeg: FeatureSummary,
    val torsoLeanDeg: FeatureSummary,
    val lateralHead: FeatureSummary,
    val headHeight: FeatureSummary,
    val eyeHeight: FeatureSummary,
    val facePitch: FeatureSummary?,
    val faceScale: FeatureSummary,
    val torsoLength: FeatureSummary?,
)

/**
 * Session-scoped port of the local web pose classifier. No image, landmarks, or
 * baseline is persisted; a new session or camera/ESP boot must calibrate again.
 */
class PosePostureClassifier(
    private val config: PoseGeometryConfig = PoseGeometryConfig(),
) {
    private val calibration = ArrayDeque<TimedPoseFeatures>()
    private var baseline: PoseBaseline? = null
    private var lastFrameSequence: Long? = null
    private var lastObservedAtMs: Long? = null
    private var lastCalibrationObservedAtMs: Long? = null
    private var calibrationOutlier: TimedPoseFeatures? = null
    private var slumpedSinceMs: Long? = null
    private var candidateState = PostureState.UNKNOWN
    private var candidateSinceMs = 0L
    private var stableState = PostureState.UNKNOWN
    private var lastUsableAtMs: Long? = null

    fun observe(frame: PoseFrameObservation): PoseClassifierUpdate = observeFeatures(
        frameSequence = frame.frameSequence,
        observedAtMonoMs = frame.observedAtMonoMs,
        features = extractPoseFeatures(frame.landmarks, frame.faceMeta),
        faceDetected = frame.faceMeta?.detected,
    )

    internal fun observeFeatures(
        frameSequence: Long,
        observedAtMonoMs: Long,
        features: PoseFeatures?,
        faceDetected: Boolean?,
    ): PoseClassifierUpdate {
        if (!acceptSequence(frameSequence)) return progress(null, candidateState, "duplicate_or_reordered_frame")
        if (baseline == null) {
            if (features == null || features.quality < config.minimumLandmarkConfidence) {
                return progress(null, missingRawState(features, faceDetected), calibrationFailureReason(features, faceDetected))
            }
            val calibrationReason = collectCalibration(observedAtMonoMs, features)
            val calibratedNow = tryCalibrate()
            if (!calibratedNow) return progress(null, PostureState.UNKNOWN, calibrationReason)
        }

        if (features == null || features.quality < config.minimumLandmarkConfidence) {
            breakTemporalContinuity(observedAtMonoMs)
            val raw = missingRawState(features, faceDetected)
            val usableAt = lastUsableAtMs
            if (raw == PostureState.UNKNOWN && usableAt != null && observedAtMonoMs - usableAt <= config.invalidHoldMs) {
                return progress(stableState, raw, "${calibrationFailureReason(features, faceDetected)}_hold", observedAtMonoMs, 0.0)
            }
            return progress(commit(raw, observedAtMonoMs), raw, calibrationFailureReason(features, faceDetected), observedAtMonoMs, 0.0)
        }
        lastUsableAtMs = observedAtMonoMs
        val raw = classifyRaw(features, observedAtMonoMs)
        return progress(
            classification = commit(raw.state, observedAtMonoMs),
            rawState = raw.state,
            reason = raw.reason,
            observedAtMs = observedAtMonoMs,
            confidence = minOf(features.quality, raw.confidence),
        )
    }

    fun isCalibrated(): Boolean = baseline != null

    /** Freshness is monotonic and bypasses the normal time-based label debounce. */
    fun stale(nowMonoMs: Long): PoseClassifierUpdate? {
        val observedAt = lastObservedAtMs ?: return null
        if (nowMonoMs - observedAt <= config.maximumContinuousGapMs || stableState == PostureState.UNKNOWN) return null
        slumpedSinceMs = null
        candidateState = PostureState.UNKNOWN
        candidateSinceMs = nowMonoMs - config.labelDebounceMs
        stableState = PostureState.UNKNOWN
        return progress(
            classification = PostureState.UNKNOWN,
            rawState = PostureState.UNKNOWN,
            reason = "stale",
            observedAtMs = nowMonoMs,
            confidence = 0.0,
        )
    }

    fun reset() {
        calibration.clear()
        baseline = null
        lastFrameSequence = null
        lastObservedAtMs = null
        lastCalibrationObservedAtMs = null
        calibrationOutlier = null
        slumpedSinceMs = null
        candidateState = PostureState.UNKNOWN
        candidateSinceMs = 0L
        stableState = PostureState.UNKNOWN
        lastUsableAtMs = null
    }

    private fun collectCalibration(observedAtMs: Long, features: PoseFeatures): String {
        if (!features.headHeight.isFinite() || features.headHeight !in 0.25..3.0 ||
            !features.shoulderWidth.isFinite() || features.shoulderWidth < MINIMUM_BODY_SCALE
        ) return "invalid_geometry"
        lastCalibrationObservedAtMs?.let { previousAt ->
            if (observedAtMs <= previousAt || observedAtMs - previousAt > config.calibrationMaximumGapMs) {
                clearCalibration()
            }
        }
        val previous = calibration.lastOrNull()?.features
        if (previous != null && !stablePair(features, previous)) {
            val priorOutlier = calibrationOutlier
            calibrationOutlier = TimedPoseFeatures(observedAtMs, features)
            if (priorOutlier == null || observedAtMs - priorOutlier.observedAtMs > config.calibrationMaximumGapMs ||
                !stablePair(features, priorOutlier.features)
            ) return "moving_too_much"
            calibration.clear()
            calibration.addLast(priorOutlier)
            lastCalibrationObservedAtMs = priorOutlier.observedAtMs
        }
        calibrationOutlier = null
        lastCalibrationObservedAtMs = observedAtMs
        calibration.addLast(TimedPoseFeatures(observedAtMs, features))
        while (calibration.size > MAXIMUM_CALIBRATION_SAMPLES) calibration.removeFirst()
        return "collecting_stable_personal_pose"
    }

    private fun stablePair(current: PoseFeatures, previous: PoseFeatures): Boolean =
        abs(current.headRollDeg - previous.headRollDeg) <= 4.0 &&
            abs(current.torsoLeanDeg - previous.torsoLeanDeg) <= 4.0 &&
            abs(current.lateralHead - previous.lateralHead) <= 0.04 &&
            abs(current.headHeight - previous.headHeight) <= 0.08

    private fun tryCalibrate(): Boolean {
        if (calibration.size < config.calibrationSamples) return false
        if (calibration.last().observedAtMs - calibration.first().observedAtMs < config.calibrationMinimumSpanMs) return false
        val samples = calibration.map(TimedPoseFeatures::features)
        baseline = PoseBaseline(
            headRollDeg = samples.summary(PoseFeatures::headRollDeg),
            torsoLeanDeg = samples.summary(PoseFeatures::torsoLeanDeg),
            lateralHead = samples.summary(PoseFeatures::lateralHead),
            headHeight = samples.summary(PoseFeatures::headHeight),
            eyeHeight = samples.summary(PoseFeatures::eyeHeight),
            facePitch = samples.mapNotNull(PoseFeatures::facePitch).takeIf { it.size >= config.calibrationSamples / 2 }?.summary(),
            faceScale = samples.summary(PoseFeatures::faceScale),
            torsoLength = samples.mapNotNull(PoseFeatures::torsoLength).takeIf { it.size >= config.calibrationSamples / 2 }?.summary(),
        )
        calibration.clear()
        lastCalibrationObservedAtMs = null
        calibrationOutlier = null
        lastObservedAtMs = null
        slumpedSinceMs = null
        return true
    }

    private fun classifyRaw(features: PoseFeatures, observedAtMs: Long): RawClassification {
        val reference = requireNotNull(baseline)
        val previousAt = lastObservedAtMs
        if (previousAt != null &&
            (observedAtMs <= previousAt || observedAtMs - previousAt > config.maximumContinuousGapMs)
        ) slumpedSinceMs = null
        lastObservedAtMs = observedAtMs

        val leanHysteresis = if (stableState == PostureState.LEAN_LEFT || stableState == PostureState.LEAN_RIGHT) {
            config.hysteresisFactor
        } else 1.0
        val downHysteresis = if (stableState == PostureState.HEAD_DOWN || stableState == PostureState.SLUMPED) {
            config.hysteresisFactor
        } else 1.0
        val headRollEnter = maxOf(config.headRollEnterDeg, 6.0 * reference.headRollDeg.noise) * leanHysteresis
        val torsoLeanEnter = maxOf(config.torsoLeanEnterDeg, 6.0 * reference.torsoLeanDeg.noise) * leanHysteresis
        val lateralEnter = maxOf(config.lateralEnter, 6.0 * reference.lateralHead.noise) * leanHysteresis
        val headDropEnter = maxOf(config.headDropEnter, 6.0 * reference.headHeight.noise) * downHysteresis
        val eyeDropEnter = maxOf(config.eyeDropEnter, 6.0 * reference.eyeHeight.noise) * downHysteresis
        val pitchEnter = maxOf(config.facePitchEnter, 6.0 * (reference.facePitch?.noise ?: 0.0)) * downHysteresis

        val headRoll = normalizedDegrees(features.headRollDeg - reference.headRollDeg.value)
        val torsoLean = features.torsoLeanDeg - reference.torsoLeanDeg.value
        val lateral = features.lateralHead - reference.lateralHead.value
        val headDrop = reference.headHeight.value - features.headHeight
        val eyeDrop = reference.eyeHeight.value - features.eyeHeight
        val pitch = if (features.facePitch != null && reference.facePitch != null) {
            features.facePitch - reference.facePitch.value
        } else null
        val scaleRatio = features.faceScale / reference.faceScale.value
        val torsoCompression = if (features.torsoLength != null && reference.torsoLength != null && reference.torsoLength.value > 0.0) {
            (reference.torsoLength.value - features.torsoLength) / reference.torsoLength.value
        } else 0.0

        val tooCloseThreshold = if (stableState == PostureState.TOO_CLOSE) 1.20 else config.tooCloseScaleRatio
        if (scaleRatio >= tooCloseThreshold) {
            slumpedSinceMs = null
            return RawClassification(PostureState.TOO_CLOSE, ((scaleRatio - 1.0) / 0.55).coerceIn(0.0, 1.0), "scale")
        }

        val leanSignals = listOf(
            DirectionalScore(headRoll.sign, abs(headRoll) / headRollEnter),
            DirectionalScore(torsoLean.sign, abs(torsoLean) / torsoLeanEnter),
            DirectionalScore(lateral.sign, abs(lateral) / lateralEnter),
        ).filter { it.direction != 0.0 && it.score >= 1.0 }.sortedByDescending(DirectionalScore::score)
        var leanScore = leanSignals.firstOrNull()?.score ?: 0.0
        var leanDirection = leanSignals.firstOrNull()?.direction ?: 0.0

        val downSignals = buildList {
            add(headDrop / headDropEnter)
            add(eyeDrop / eyeDropEnter)
            if (pitch != null) add(pitch / pitchEnter)
        }
        val downScore = downSignals.sortedDescending().getOrElse(1) { 0.0 }
        val headDown = downSignals.count { it >= 1.0 } >= 2 || headDrop / headDropEnter >= 1.5
        val slumpEvidence = headDown && (
            torsoCompression >= config.torsoCompressionEnter ||
                (headDrop >= maxOf(0.18, headDropEnter) && eyeDrop >= maxOf(0.16, eyeDropEnter))
            )
        if (slumpEvidence) {
            val since = slumpedSinceMs ?: observedAtMs.also { slumpedSinceMs = it }
            if (observedAtMs - since >= config.slumpedMinimumMs) {
                return RawClassification(
                    PostureState.SLUMPED,
                    maxOf(downScore, torsoCompression / 0.20).coerceIn(0.0, 1.0),
                    "sustained_collapse",
                )
            }
        } else {
            slumpedSinceMs = null
        }

        if (leanScore >= 1.0 && (!headDown || leanScore >= downScore)) {
            val state = if (leanDirection > 0.0) PostureState.LEAN_LEFT else PostureState.LEAN_RIGHT
            return RawClassification(state, (leanScore / 1.8).coerceIn(0.0, 1.0), "anatomical_lean")
        }
        if (headDown) {
            return RawClassification(
                PostureState.HEAD_DOWN,
                (maxOf(downScore, headDrop / headDropEnter) / 1.8).coerceIn(0.0, 1.0),
                "head_geometry",
            )
        }
        slumpedSinceMs = null
        return RawClassification(PostureState.NORMAL, features.quality, "within_baseline")
    }

    private fun commit(raw: PostureState, observedAtMs: Long): PostureState {
        if (raw != candidateState) {
            candidateState = raw
            candidateSinceMs = observedAtMs
        }
        if (stableState != raw && observedAtMs - candidateSinceMs >= config.labelDebounceMs) stableState = raw
        return stableState
    }

    private fun progress(
        classification: PostureState?,
        rawState: PostureState,
        reason: String,
        observedAtMs: Long = lastObservedAtMs ?: 0L,
        confidence: Double = 0.0,
    ): PoseClassifierUpdate = PoseClassifierUpdate(
        classification = classification?.let {
            PostureClassification(it, observedAtMs, confidence.coerceIn(0.0, 1.0), SOURCE)
        },
        rawState = rawState,
        calibration = PoseCalibrationProgress(
            acceptedSamples = if (baseline == null) minOf(calibration.size, config.calibrationSamples) else config.calibrationSamples,
            requiredSamples = config.calibrationSamples,
            reason = reason,
            calibrated = baseline != null,
            stableMs = if (baseline != null || calibration.size < 2) 0L
            else (calibration.last().observedAtMs - calibration.first().observedAtMs).coerceAtLeast(0L),
        ),
    )

    private fun missingRawState(features: PoseFeatures?, faceDetected: Boolean?): PostureState =
        if (features == null && faceDetected == false) PostureState.FACE_MISSING else PostureState.UNKNOWN

    private fun calibrationFailureReason(features: PoseFeatures?, faceDetected: Boolean?): String = when {
        faceDetected == null -> "face_meta_missing"
        !faceDetected && features == null -> "face_missing_both_sources"
        !faceDetected -> "esp_pose_disagreement"
        features == null -> "pose_missing_esp_face_present"
        features.quality < config.minimumLandmarkConfidence -> "pose_landmarks_low_confidence"
        else -> "invalid_calibration_sample"
    }

    private fun breakTemporalContinuity(observedAtMs: Long) {
        lastObservedAtMs = observedAtMs
        slumpedSinceMs = null
    }

    private fun clearCalibration() {
        calibration.clear()
        lastCalibrationObservedAtMs = null
        calibrationOutlier = null
    }

    private fun acceptSequence(sequence: Long): Boolean {
        val previous = lastFrameSequence
        if (previous == null) {
            lastFrameSequence = sequence
            return true
        }
        val delta = (sequence - previous) and UINT32_MAX
        if (delta == 0L || delta > UINT32_HALF_RANGE) return false
        lastFrameSequence = sequence
        return true
    }

    private data class RawClassification(val state: PostureState, val confidence: Double, val reason: String)
    private data class DirectionalScore(val direction: Double, val score: Double)

    companion object {
        const val SOURCE = "watch_mediapipe_pose_lite_v1"
        const val PROFILE_FINGERPRINT =
            "ov2640-qvga-canonical-v1:59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a:classifier-v2"
        private const val MAXIMUM_CALIBRATION_SAMPLES = 60
        private const val UINT32_MAX = 4_294_967_295L
        private const val UINT32_HALF_RANGE = 2_147_483_647L
    }
}

internal fun extractPoseFeatures(
    landmarks: List<PoseLandmarkPoint>,
    faceMeta: PoseFaceMetaV1?,
): PoseFeatures? {
    if (landmarks.size <= RIGHT_HIP) return null
    val nose = landmarks[NOSE]
    val leftEye = landmarks[LEFT_EYE]
    val rightEye = landmarks[RIGHT_EYE]
    val leftShoulder = landmarks[LEFT_SHOULDER]
    val rightShoulder = landmarks[RIGHT_SHOULDER]
    val required = listOf(nose, leftEye, rightEye, leftShoulder, rightShoulder)
    if (required.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val quality = required.minOf(PoseLandmarkPoint::confidence)
    val shoulderWidth = distance(leftShoulder, rightShoulder)
    if (!shoulderWidth.isFinite() || shoulderWidth < MINIMUM_BODY_SCALE) return null
    val shoulderMid = midpoint(leftShoulder, rightShoulder)
    // Anatomical vector, not screen-x: positive projections always mean subject-left.
    val subjectLeftX = (leftShoulder.x - rightShoulder.x) / shoulderWidth
    val subjectLeftY = (leftShoulder.y - rightShoulder.y) / shoulderWidth
    val shoulderAngle = atan2(subjectLeftY, subjectLeftX) * 180.0 / PI
    val eyeWidth = distance(leftEye, rightEye)
    val eyeAngle = if (eyeWidth > MINIMUM_EYE_SCALE) {
        atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x) * 180.0 / PI
    } else shoulderAngle
    val headRollSignals = mutableListOf(normalizedDegrees(eyeAngle - shoulderAngle))
    val leftEar = landmarks[LEFT_EAR]
    val rightEar = landmarks[RIGHT_EAR]
    if (leftEar.confidence >= MINIMUM_HIP_CONFIDENCE && rightEar.confidence >= MINIMUM_HIP_CONFIDENCE &&
        distance(leftEar, rightEar) > MINIMUM_EYE_SCALE
    ) {
        val earAngle = atan2(leftEar.y - rightEar.y, leftEar.x - rightEar.x) * 180.0 / PI
        headRollSignals += normalizedDegrees(earAngle - shoulderAngle)
    }
    val eyeMid = midpoint(leftEye, rightEye)
    val lateralHead = ((nose.x - shoulderMid.x) * subjectLeftX +
        (nose.y - shoulderMid.y) * subjectLeftY) / shoulderWidth

    val leftHip = landmarks[LEFT_HIP]
    val rightHip = landmarks[RIGHT_HIP]
    val hipsUsable = leftHip.confidence >= MINIMUM_HIP_CONFIDENCE &&
        rightHip.confidence >= MINIMUM_HIP_CONFIDENCE &&
        leftHip.x.isFinite() && leftHip.y.isFinite() && rightHip.x.isFinite() && rightHip.y.isFinite()
    var torsoLean = shoulderAngle
    var torsoLength: Double? = null
    if (hipsUsable) {
        val hipMid = midpoint(leftHip, rightHip)
        torsoLength = distance(hipMid, shoulderMid)
        if (torsoLength > MINIMUM_TORSO_SCALE) {
            val lateral = ((shoulderMid.x - hipMid.x) * subjectLeftX +
                (shoulderMid.y - hipMid.y) * subjectLeftY) / torsoLength
            torsoLean = asin(lateral.coerceIn(-1.0, 1.0)) * 180.0 / PI
        }
    }

    var facePitch: Double? = null
    var faceScale = shoulderWidth
    if (faceMeta?.detected == true) {
        faceScale = sqrt((faceMeta.width * faceMeta.height).coerceAtLeast(0.000001))
        val metaEyeMid = midpoint(faceMeta.leftEye, faceMeta.rightEye)
        val mouthMid = midpoint(faceMeta.leftMouth, faceMeta.rightMouth)
        val eyeMouth = distance(metaEyeMid, mouthMid)
        if (eyeMouth > MINIMUM_EYE_SCALE) facePitch = (faceMeta.nose.y - metaEyeMid.y) / eyeMouth
    }
    return PoseFeatures(
        quality = quality,
        headRollDeg = headRollSignals.median(),
        torsoLeanDeg = torsoLean,
        shoulderAngleDeg = shoulderAngle,
        lateralHead = lateralHead,
        headHeight = distance(nose, shoulderMid) / shoulderWidth,
        eyeHeight = distance(eyeMid, shoulderMid) / shoulderWidth,
        facePitch = facePitch,
        faceScale = faceScale,
        shoulderWidth = shoulderWidth,
        torsoLength = torsoLength,
    )
}

private data class Point(val x: Double, val y: Double)
private fun midpoint(a: PoseLandmarkPoint, b: PoseLandmarkPoint) = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
private fun midpoint(a: PoseFacePoint, b: PoseFacePoint) = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
private fun distance(a: PoseLandmarkPoint, b: PoseLandmarkPoint) = hypot(a.x - b.x, a.y - b.y)
private fun distance(a: PoseLandmarkPoint, b: Point) = hypot(a.x - b.x, a.y - b.y)
private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
private fun normalizedDegrees(value: Double): Double {
    var result = value
    while (result > 180.0) result -= 360.0
    while (result < -180.0) result += 360.0
    return result
}

private fun List<PoseFeatures>.summary(selector: (PoseFeatures) -> Double): FeatureSummary = map(selector).summary()
private fun List<Double>.summary(): FeatureSummary {
    val center = median()
    return FeatureSummary(center, map { abs(it - center) }.median())
}
private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

private const val NOSE = 0
private const val LEFT_EYE = 2
private const val RIGHT_EYE = 5
private const val LEFT_EAR = 7
private const val RIGHT_EAR = 8
private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val MINIMUM_BODY_SCALE = 0.04
private const val MINIMUM_EYE_SCALE = 0.005
private const val MINIMUM_TORSO_SCALE = 0.02
private const val MINIMUM_HIP_CONFIDENCE = 0.50
