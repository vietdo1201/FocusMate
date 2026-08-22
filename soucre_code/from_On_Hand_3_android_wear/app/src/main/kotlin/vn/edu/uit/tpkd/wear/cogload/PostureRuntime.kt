package vn.edu.uit.tpkd.wear.cogload

import vn.edu.uit.tpkd.wear.cogload.protocol.EspDeviceInfo
import vn.edu.uit.tpkd.wear.cogload.protocol.EspTimeAnchor
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationCodec
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationReassembler
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceSequenceGate

enum class PostureRuntimePhase {
    DISCONNECTED,
    CONNECTING,
    BONDING,
    CALIBRATING,
    LIVE,
    STALE,
    UNAVAILABLE,
}

enum class PostureSource {
    NONE,
    BLE_GEOMETRY,
    MEDIAPIPE_POSE_LITE,
}

enum class LocalPosePhase {
    STOPPED,
    WAITING_FRAME_ACCESS,
    WAITING_WIFI,
    LOADING_MODEL,
    CALIBRATING,
    LIVE,
    PAUSED_THERMAL,
    MODEL_MISSING,
    ERROR,
}

enum class PostureThermalState {
    UNKNOWN,
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

data class PostureRuntimeSnapshot(
    val phase: PostureRuntimePhase,
    val detail: String = "",
    val mtu: Int? = null,
    val notificationRateHz: Double? = null,
    val classification: PostureClassification? = null,
    val source: PostureSource = PostureSource.NONE,
    val localPosePhase: LocalPosePhase = LocalPosePhase.STOPPED,
    val thermalState: PostureThermalState = PostureThermalState.UNKNOWN,
    val localPoseDetail: String = "",
)

/** Process-local UI projection. Persistent posture data remains in StudySessionRepository. */
object PostureRuntimeStore {
    @Volatile
    var snapshot = PostureRuntimeSnapshot(PostureRuntimePhase.DISCONNECTED)
        private set

    @Synchronized
    fun update(value: PostureRuntimeSnapshot) {
        val previous = snapshot
        snapshot = value.copy(
            classification = if (previous.source == PostureSource.MEDIAPIPE_POSE_LITE) {
                previous.classification
            } else {
                value.classification
            },
            source = previous.source,
            localPosePhase = previous.localPosePhase,
            thermalState = previous.thermalState,
            localPoseDetail = previous.localPoseDetail,
        )
    }

    @Synchronized
    fun updateLocalPose(
        phase: LocalPosePhase,
        thermalState: PostureThermalState,
        detail: String,
    ) {
        snapshot = snapshot.copy(
            localPosePhase = phase,
            thermalState = thermalState,
            localPoseDetail = detail,
        )
    }

    @Synchronized
    fun updateSelectedSource(source: PostureSource, classification: PostureClassification? = null) {
        snapshot = snapshot.copy(source = source, classification = classification)
    }

    @Synchronized
    fun reset() {
        snapshot = PostureRuntimeSnapshot(PostureRuntimePhase.DISCONNECTED)
    }
}

data class PostureIngestionUpdate(
    val classification: PostureClassification,
    val summaries: List<PostureStateSummary>,
    val insights: List<PostureInsight>,
)

/**
 * Pure ingestion pipeline shared by simulator and Android BLE. All freshness and ordering
 * decisions use the Watch monotonic clock; wall clock is only the persisted display time.
 */
class FaceObservationIngestor(
    private val classifier: GeometryPostureClassifier = GeometryPostureClassifier(),
    private val tracker: PostureInsightTracker = PostureInsightTracker(),
    private val wallClockMs: () -> Long,
    private val monotonicMs: () -> Long,
    private val onUpdate: (PostureIngestionUpdate) -> Unit = {},
    private val onRuntime: (PostureRuntimeSnapshot) -> Unit = {},
) {
    private val sequenceGate = FaceSequenceGate()
    private val reassembler = FaceObservationReassembler()
    private val calibration = ArrayDeque<FaceObservationV1>()
    private var anchor: EspTimeAnchor? = null
    private var mtu: Int? = null
    private var notificationCount = 0L
    private var rateWindowStartedMs = -1L
    private var lastCalibrationSampleMonoMs: Long? = null
    private var lastUniqueEspUptimeMs: Long? = null
    private var lastAcceptedObservedAtMonoMs: Long? = null
    private var unavailableDetail = DEFAULT_UNAVAILABLE_DETAIL

    fun connecting() {
        anchor = null
        unavailableDetail = DEFAULT_UNAVAILABLE_DETAIL
        mtu = null
        resetRateWindow()
        publish(PostureRuntimePhase.CONNECTING, "Đang tìm ESP32-S3")
    }

    fun bonding() = publish(PostureRuntimePhase.BONDING, "Đang ghép đôi; xác nhận trên đồng hồ nếu được hỏi")

    fun onMtuChanged(value: Int) {
        mtu = value
        publish(PostureRuntimePhase.CONNECTING, "MTU $value; đang đọc Device Info")
    }

    fun onDeviceInfo(raw: ByteArray): Result<EspDeviceInfo> = runCatching {
        val info = EspDeviceInfo.parse(raw)
        require(info.transportCompatible) { "Device Info capability contract mismatch" }
        notificationCount = 0L
        rateWindowStartedMs = monotonicMs()
        if (!info.usable) {
            anchor = null
            unavailableDetail = capabilityDetail(info)
            publish(PostureRuntimePhase.UNAVAILABLE, unavailableDetail)
            return@runCatching info
        }
        val rebooted = sequenceGate.onDeviceInfo(info.bootIdHex)
        if (rebooted) {
            classifier.reset()
            tracker.reset()
            calibration.clear()
            lastCalibrationSampleMonoMs = null
            lastUniqueEspUptimeMs = null
            lastAcceptedObservedAtMonoMs = null
        }
        anchor = EspTimeAnchor.from(info, wallClockMs(), monotonicMs())
        publish(PostureRuntimePhase.CALIBRATING, "Cần 20 mẫu ổn định")
        info
    }.onFailure {
        anchor = null
        publish(PostureRuntimePhase.UNAVAILABLE, "Device Info không hợp lệ")
    }

    fun onNotification(notification: ByteArray) {
        val nowMono = monotonicMs()
        val outcome = reassembler.offer(notification, nowMono)
        if (outcome !is FaceObservationReassembler.Outcome.Complete) return
        val observation = FaceObservationCodec.tryDecode(outcome.payload).getOrNull() ?: return
        val timeAnchor = anchor ?: run {
            recordRate(nowMono)
            publish(PostureRuntimePhase.UNAVAILABLE, unavailableDetail)
            return
        }
        if (!sequenceGate.accept(observation)) return
        recordRate(nowMono)
        if (timeAnchor.isStale(observation.espUptimeMs, nowMono)) {
            classifier.resetTemporalState()
            tracker.pause()
            lastAcceptedObservedAtMonoMs = null
            if (!classifier.isCalibrated()) {
                calibration.clear()
                lastCalibrationSampleMonoMs = null
            }
            publish(PostureRuntimePhase.STALE, "Không có observation mới")
            return
        }
        // The ESP transport can notify faster than the detector produces a new
        // result. Keep those notifications in link-rate telemetry, but never
        // count the same detector timestamp twice for calibration, posture, or
        // temporal tracking. A lower uptime is already rejected by
        // FaceSequenceGate; reconnect on the same boot retains this gate,
        // while a boot_id change or full pipeline reset clears it.
        val previousUniqueUptime = lastUniqueEspUptimeMs
        if (previousUniqueUptime != null && observation.espUptimeMs <= previousUniqueUptime) return
        lastUniqueEspUptimeMs = observation.espUptimeMs
        if (!classifier.isCalibrated()) {
            if (classifier.isCalibrationCandidate(observation)) {
                val previous = lastCalibrationSampleMonoMs
                if (previous != null &&
                    (nowMono <= previous || nowMono - previous > CALIBRATION_MAXIMUM_GAP_MS)
                ) {
                    calibration.clear()
                }
                calibration.addLast(observation)
                lastCalibrationSampleMonoMs = nowMono
            } else {
                val previous = lastCalibrationSampleMonoMs
                if (previous != null && nowMono - previous > CALIBRATION_MAXIMUM_GAP_MS) {
                    calibration.clear()
                    lastCalibrationSampleMonoMs = null
                }
            }
            while (calibration.size > CALIBRATION_SAMPLES) calibration.removeFirst()
            if (!classifier.calibrate(calibration.toList())) {
                val detail = if (calibration.size == CALIBRATION_SAMPLES) {
                    "$CALIBRATION_SAMPLES/$CALIBRATION_SAMPLES mẫu; giữ tư thế ổn định"
                } else {
                    "${calibration.size}/$CALIBRATION_SAMPLES mẫu"
                }
                publish(PostureRuntimePhase.CALIBRATING, detail)
                return
            }
            calibration.clear()
            lastCalibrationSampleMonoMs = null
        }
        val observedAtMono = timeAnchor.observedAtMonotonicMs(observation.espUptimeMs)
        if (observedAtMono < 0L) return
        val previousObservedAt = lastAcceptedObservedAtMonoMs
        if (previousObservedAt != null &&
            (observedAtMono <= previousObservedAt ||
                observedAtMono - previousObservedAt > MAXIMUM_CONTINUOUS_GAP_MS)
        ) {
            classifier.resetTemporalState()
            tracker.pause()
        }
        lastAcceptedObservedAtMonoMs = observedAtMono
        val classification = classifier.classify(observation, observedAtMono)
        val insights = tracker.observe(classification.state, observedAtMono)
        val update = PostureIngestionUpdate(classification, tracker.summaries(observedAtMono), insights)
        onUpdate(update)
        publish(PostureRuntimePhase.LIVE, classification.state.name, classification)
    }

    fun disconnected(detail: String = "Mất kết nối") {
        anchor = null
        classifier.reset()
        tracker.pause()
        calibration.clear()
        lastCalibrationSampleMonoMs = null
        lastAcceptedObservedAtMonoMs = null
        unavailableDetail = DEFAULT_UNAVAILABLE_DETAIL
        mtu = null
        resetRateWindow()
        publish(PostureRuntimePhase.DISCONNECTED, detail)
    }

    fun reset() {
        sequenceGate.reset()
        classifier.reset()
        tracker.reset()
        calibration.clear()
        lastCalibrationSampleMonoMs = null
        lastUniqueEspUptimeMs = null
        lastAcceptedObservedAtMonoMs = null
        anchor = null
        unavailableDetail = DEFAULT_UNAVAILABLE_DETAIL
        mtu = null
        resetRateWindow()
        publish(PostureRuntimePhase.DISCONNECTED, "")
    }

    private fun recordRate(nowMono: Long) {
        notificationCount++
        if (rateWindowStartedMs < 0L) rateWindowStartedMs = nowMono
    }

    private fun resetRateWindow() {
        notificationCount = 0L
        rateWindowStartedMs = -1L
    }

    private fun capabilityDetail(info: EspDeviceInfo): String = when {
        info.cameraReady && !info.detectorReady -> "Transport OK; camera OK; detector chưa sẵn sàng"
        !info.cameraReady && info.detectorReady -> "Transport OK; detector OK; camera chưa sẵn sàng"
        else -> DEFAULT_UNAVAILABLE_DETAIL
    }

    private fun publish(
        phase: PostureRuntimePhase,
        detail: String,
        classification: PostureClassification? = null,
    ) {
        val elapsed = (monotonicMs() - rateWindowStartedMs).coerceAtLeast(0L)
        val rate = if (rateWindowStartedMs >= 0L && elapsed > 0L) notificationCount * 1_000.0 / elapsed else null
        onRuntime(PostureRuntimeSnapshot(phase, detail, mtu, rate, classification))
    }

    private companion object {
        const val CALIBRATION_SAMPLES = 20
        const val CALIBRATION_MAXIMUM_GAP_MS = 1_500L
        const val MAXIMUM_CONTINUOUS_GAP_MS = 3_000L
        const val DEFAULT_UNAVAILABLE_DETAIL = "Transport OK; camera/detector chưa sẵn sàng"
    }
}
