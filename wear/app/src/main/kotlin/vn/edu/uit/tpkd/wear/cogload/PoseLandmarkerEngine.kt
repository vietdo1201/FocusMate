// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Decodes each JPEG once, then runs Pose and cadence-limited Face Landmarker locally. */
class PoseLandmarkerEngine(
    context: Context,
    private val onObservation: (PoseFrameObservation) -> Unit,
    private val onYawnObservation: (YawnFrameObservation) -> Unit,
    private val onAvailability: (available: Boolean, detail: String) -> Unit,
    private val onDiagnostic: (detail: String) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focusmate-vision-landmarker").apply { isDaemon = true }
    },
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var running = false
    private var poseLandmarker: PoseLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var lastPoseTimestampMs = -1L
    private var lastFaceTimestampMs = -1L
    private var lastFaceScaleGeometry: PoseFaceScaleGeometry? = null
    private var faceInferenceIntervalMs: Long? = DEFAULT_FACE_INTERVAL_MS
    private val mailbox = LatestFrameMailbox(
        executor = executor,
        processor = ::process,
        discard = { /* JPEG bytes are never persisted. */ },
    )

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        executor.execute {
            val missing = listOf(POSE_MODEL_ASSET_PATH, FACE_MODEL_ASSET_PATH).filterNot { path ->
                runCatching { appContext.assets.open(path).use { it.read() >= 0 } }.isSuccess
            }
            if (missing.isNotEmpty()) {
                onAvailability(false, "Thiếu model local: ${missing.joinToString()}")
                return@execute
            }
            val poseResult = runCatching { createPoseLandmarker() }
            if (poseResult.isFailure) {
                onAvailability(false, "Pose MediaPipe init lỗi ${poseResult.exceptionOrNull()?.javaClass?.simpleName}")
                return@execute
            }
            val faceResult = runCatching { createFaceLandmarker() }
            if (faceResult.isFailure) {
                runCatching { poseResult.getOrThrow().close() }
                onAvailability(false, "Face MediaPipe init lỗi ${faceResult.exceptionOrNull()?.javaClass?.simpleName}")
                return@execute
            }
            val pose = poseResult.getOrThrow()
            val face = faceResult.getOrThrow()
            val keep = synchronized(lock) {
                if (running) {
                    poseLandmarker = pose
                    faceLandmarker = face
                    mailbox.start()
                    true
                } else false
            }
            if (!keep) {
                pose.close()
                face.close()
            } else {
                onAvailability(true, "Pose Lite + Face Landmarker sẵn sàng")
            }
        }
    }

    fun offer(frame: LocalFramePacket) = mailbox.offer(frame)

    fun setFaceInferenceIntervalMs(value: Long?) {
        synchronized(lock) { faceInferenceIntervalMs = value?.coerceAtLeast(MINIMUM_FACE_INTERVAL_MS) }
    }

    fun stop() {
        val current = synchronized(lock) {
            if (!running) return
            running = false
            mailbox.stop()
            val value = poseLandmarker to faceLandmarker
            poseLandmarker = null
            faceLandmarker = null
            lastFaceScaleGeometry = null
            value
        }
        executor.execute {
            runCatching { current.first?.close() }
            runCatching { current.second?.close() }
        }
        executor.shutdown()
    }

    private fun createPoseLandmarker(): PoseLandmarker {
        val baseOptions = BaseOptions.builder().setModelAssetPath(POSE_MODEL_ASSET_PATH).build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .build()
        return PoseLandmarker.createFromOptions(appContext, options)
    }

    private fun createFaceLandmarker(): FaceLandmarker {
        val baseOptions = BaseOptions.builder().setModelAssetPath(FACE_MODEL_ASSET_PATH).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.6f)
            .setMinFacePresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(false)
            .build()
        return FaceLandmarker.createFromOptions(appContext, options)
    }

    private fun process(frame: LocalFramePacket, completion: () -> Unit) {
        val tasks = synchronized(lock) { Triple(poseLandmarker, faceLandmarker, faceInferenceIntervalMs) }
        val pose = tasks.first
        if (pose == null) {
            completion()
            return
        }
        val bitmap = BitmapFactory.decodeByteArray(
            frame.jpeg,
            0,
            frame.jpeg.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
        if (bitmap == null) {
            onDiagnostic("JPEG local không giải mã được")
            completion()
            return
        }
        val image = BitmapImageBuilder(bitmap).build()
        try {
            val poseTimestamp = synchronized(lock) {
                maxOf(frame.receivedAtMonoMs, lastPoseTimestampMs + 1L).also { lastPoseTimestampMs = it }
            }
            val poseResult = pose.detectForVideo(image, poseTimestamp)
            val face = tasks.second
            val interval = tasks.third
            if (face != null && interval != null) {
                val shouldRunFace = synchronized(lock) { poseTimestamp - lastFaceTimestampMs >= interval }
                if (shouldRunFace) {
                    val faceTimestamp = synchronized(lock) {
                        maxOf(poseTimestamp, lastFaceTimestampMs + 1L).also { lastFaceTimestampMs = it }
                    }
                    lastFaceScaleGeometry = handleFaceResult(face.detectForVideo(image, faceTimestamp), frame)
                }
            }
            val faceGeometry = lastFaceScaleGeometry?.takeIf {
                poseTimestamp >= it.observedAtMonoMs && poseTimestamp - it.observedAtMonoMs <= FACE_SCALE_MAX_AGE_MS
            }
            handlePoseResult(poseResult, frame, faceGeometry)
        } catch (error: RuntimeException) {
            onDiagnostic("MediaPipe inference lỗi ${error.javaClass.simpleName}")
        } finally {
            runCatching { image.close() }
            if (!bitmap.isRecycled) bitmap.recycle()
            completion()
        }
    }

    private fun handlePoseResult(
        result: PoseLandmarkerResult,
        frame: LocalFramePacket,
        faceScaleGeometry: PoseFaceScaleGeometry?,
    ) {
        val points = result.landmarks().firstOrNull().orEmpty().map { landmark ->
            PoseLandmarkPoint(
                x = landmark.x().toDouble(),
                y = landmark.y().toDouble(),
                z = landmark.z().toDouble(),
                visibility = landmark.visibility().orElse(1.0f).toDouble(),
                presence = landmark.presence().orElse(1.0f).toDouble(),
            )
        }
        onObservation(
            PoseFrameObservation(
                frameSequence = frame.sequence,
                observedAtMonoMs = frame.receivedAtMonoMs,
                landmarks = points,
                faceMeta = frame.faceMetaV1,
                faceScaleGeometry = faceScaleGeometry,
            ),
        )
    }

    private fun handleFaceResult(result: FaceLandmarkerResult, frame: LocalFramePacket): PoseFaceScaleGeometry? {
        val face = result.faceLandmarks().firstOrNull()
        val mar = if (face != null && face.size > RIGHT_MOUTH_INDEX) {
            val upper = face[UPPER_LIP_INDEX]
            val lower = face[LOWER_LIP_INDEX]
            val left = face[LEFT_MOUTH_INDEX]
            val right = face[RIGHT_MOUTH_INDEX]
            val width = hypot((left.x() - right.x()).toDouble(), (left.y() - right.y()).toDouble())
            if (width >= MINIMUM_MOUTH_WIDTH) {
                hypot((upper.x() - lower.x()).toDouble(), (upper.y() - lower.y()).toDouble()) / width
            } else null
        } else null
        val jawOpen = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
            ?.firstOrNull { it.categoryName().equals("jawOpen", ignoreCase = true) }
            ?.score()
            ?.toDouble()
        onYawnObservation(
            YawnFrameObservation(
                observedAtMonoMs = frame.receivedAtMonoMs,
                observedAtWallMs = System.currentTimeMillis(),
                jawOpen = jawOpen,
                mouthAspectRatio = mar,
                frameSequence = frame.sequence,
                observedEspUptimeMs = frame.observedEspUptimeMs,
            ),
        )
        if (face == null || face.size <= RIGHT_FACE_EYE_INDEX) return null
        val leftEye = face[LEFT_FACE_EYE_INDEX]
        val rightEye = face[RIGHT_FACE_EYE_INDEX]
        val eyeScale = hypot((leftEye.x() - rightEye.x()).toDouble(), (leftEye.y() - rightEye.y()).toDouble())
        return eyeScale.takeIf { it.isFinite() && it > MINIMUM_FACE_EYE_SCALE }
            ?.let { PoseFaceScaleGeometry(it, frame.receivedAtMonoMs) }
    }

    companion object {
        const val POSE_MODEL_ASSET_PATH = "generated/pose_landmarker_lite.task"
        const val FACE_MODEL_ASSET_PATH = "generated/face_landmarker.task"
        private const val DEFAULT_FACE_INTERVAL_MS = 400L
        private const val MINIMUM_FACE_INTERVAL_MS = 350L
        private const val UPPER_LIP_INDEX = 13
        private const val LOWER_LIP_INDEX = 14
        private const val LEFT_MOUTH_INDEX = 78
        private const val RIGHT_MOUTH_INDEX = 308
        private const val MINIMUM_MOUTH_WIDTH = 0.01
        private const val LEFT_FACE_EYE_INDEX = 33
        private const val RIGHT_FACE_EYE_INDEX = 263
        private const val MINIMUM_FACE_EYE_SCALE = 0.005
        private const val FACE_SCALE_MAX_AGE_MS = 700L
    }
}
