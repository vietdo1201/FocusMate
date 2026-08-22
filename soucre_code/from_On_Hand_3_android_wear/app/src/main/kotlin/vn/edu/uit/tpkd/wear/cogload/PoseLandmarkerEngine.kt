package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Thin lifecycle wrapper around the official MediaPipe Tasks Pose Landmarker Lite model. */
class PoseLandmarkerEngine(
    context: Context,
    private val onObservation: (PoseFrameObservation) -> Unit,
    private val onAvailability: (available: Boolean, detail: String) -> Unit,
    private val onDiagnostic: (detail: String) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focusmate-pose-landmarker").apply { isDaemon = true }
    },
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var running = false
    private var landmarker: PoseLandmarker? = null
    private var inFlight: InFlight? = null
    private var lastInferenceTimestampMs = -1L
    private val mailbox = LatestFrameMailbox(
        executor = executor,
        processor = ::process,
        discard = { /* JPEG byte arrays become collectible; they are never persisted. */ },
    )

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        executor.execute {
            val modelPresent = runCatching {
                appContext.assets.open(MODEL_ASSET_PATH).use { it.read() >= 0 }
            }.isSuccess
            if (!modelPresent) {
                onAvailability(false, "Thiếu model local; chạy script download_pose_landmarker_lite.ps1")
                return@execute
            }
            val created = runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setOutputSegmentationMasks(false)
                    .setResultListener(::handleResult)
                    .setErrorListener(::handleError)
                    .build()
                PoseLandmarker.createFromOptions(appContext, options)
            }
            if (created.isFailure) {
                onAvailability(false, "MediaPipe init lỗi ${created.exceptionOrNull()?.javaClass?.simpleName}")
                return@execute
            }
            val value = created.getOrThrow()
            val keep = synchronized(lock) {
                if (running) {
                    landmarker = value
                    mailbox.start()
                    true
                } else {
                    false
                }
            }
            if (!keep) value.close() else onAvailability(true, "Pose Landmarker Lite sẵn sàng")
        }
    }

    fun offer(frame: LocalFramePacket) = mailbox.offer(frame)

    fun stop() {
        val current = synchronized(lock) {
            if (!running) return
            running = false
            mailbox.stop()
            landmarker.also { landmarker = null }
        }
        executor.execute {
            cleanup(synchronized(lock) { inFlight.also { inFlight = null } })
            runCatching { current?.close() }
        }
        executor.shutdown()
    }

    private fun process(frame: LocalFramePacket, completion: () -> Unit) {
        val current = synchronized(lock) { landmarker }
        if (current == null) {
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
        val inferenceTimestamp = synchronized(lock) {
            maxOf(frame.receivedAtMonoMs, lastInferenceTimestampMs + 1L).also { lastInferenceTimestampMs = it }
        }
        synchronized(lock) {
            if (!running || landmarker !== current) {
                image.close()
                bitmap.recycle()
                completion()
                return
            }
            inFlight = InFlight(frame, bitmap, image, completion)
        }
        runCatching { current.detectAsync(image, inferenceTimestamp) }
            .onFailure { handleError(RuntimeException("Pose detectAsync failed", it)) }
    }

    private fun handleResult(result: PoseLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val pending = synchronized(lock) { inFlight.also { inFlight = null } } ?: return
        try {
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
                    frameSequence = pending.frame.sequence,
                    observedAtMonoMs = pending.frame.receivedAtMonoMs,
                    landmarks = points,
                    faceMeta = pending.frame.faceMetaV1,
                ),
            )
        } finally {
            cleanup(pending)
            pending.completion()
        }
    }

    private fun handleError(error: RuntimeException) {
        val pending = synchronized(lock) { inFlight.also { inFlight = null } }
        onDiagnostic("Pose inference lỗi ${error.javaClass.simpleName}")
        if (pending != null) {
            cleanup(pending)
            pending.completion()
        }
    }

    private fun cleanup(value: InFlight?) {
        if (value == null) return
        runCatching { value.image.close() }
        if (!value.bitmap.isRecycled) value.bitmap.recycle()
    }

    private data class InFlight(
        val frame: LocalFramePacket,
        val bitmap: Bitmap,
        val image: MPImage,
        val completion: () -> Unit,
    )

    companion object {
        const val MODEL_ASSET_PATH = "generated/pose_landmarker_lite.task"
    }
}
