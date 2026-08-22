package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.os.SystemClock

/** Connects BLE-delivered frame access, local HTTP JPEGs, MediaPipe, and source fallback. */
class LocalPosePosturePipeline(
    context: Context,
    private val sourceCoordinator: PostureSourceCoordinator,
    private val classifier: PosePostureClassifier = PosePostureClassifier(),
    private val onRuntime: (LocalPosePhase, PostureThermalState, String) -> Unit,
    private val requestFrameAccessRefresh: () -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var modelReady = false
    private var thermalState = PostureThermalState.UNKNOWN
    private var endpointBootId: String? = null
    private val engine = PoseLandmarkerEngine(
        context = context,
        onObservation = ::onPoseObservation,
        onAvailability = ::onEngineAvailability,
        onDiagnostic = ::onEngineDiagnostic,
    )
    private val frameClient = LocalFrameClient(
        context = context,
        onFrame = engine::offer,
        onState = ::onFrameState,
        onUnauthorized = requestFrameAccessRefresh,
    )
    private val thermalMonitor = WatchThermalMonitor(context, ::onThermalState)

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        onRuntime(LocalPosePhase.LOADING_MODEL, thermalState, "Đang nạp Pose Landmarker Lite")
        thermalMonitor.start()
        engine.start()
    }

    fun updateFrameAccess(endpoint: LocalFrameAccessEndpoint?) {
        val bootChanged = synchronized(lock) {
            val changed = endpoint != null && endpointBootId != null && endpoint.bootIdHex != endpointBootId
            endpointBootId = endpoint?.bootIdHex
            changed
        }
        if (bootChanged) {
            classifier.reset()
            sourceCoordinator.poseUnavailable()
        }
        frameClient.updateEndpoint(endpoint)
        if (endpoint == null || !endpoint.usable) {
            sourceCoordinator.poseUnavailable()
            onRuntime(
                LocalPosePhase.WAITING_FRAME_ACCESS,
                thermalState,
                if (endpoint == null) "Firmware chưa cấp Frame Access" else "Frame Access thiếu LAN/token/FaceMeta",
            )
        }
    }

    fun stop() {
        val shouldStop = synchronized(lock) {
            if (!started) false else true.also { started = false }
        }
        if (!shouldStop) return
        thermalMonitor.stop()
        frameClient.stop()
        engine.stop()
        classifier.reset()
        sourceCoordinator.poseUnavailable()
        onRuntime(LocalPosePhase.STOPPED, thermalState, "Đã dừng Pose local")
    }

    private fun onEngineAvailability(available: Boolean, detail: String) {
        synchronized(lock) { modelReady = available }
        if (!available) {
            frameClient.setEnabled(false)
            sourceCoordinator.poseUnavailable()
            val phase = if (detail.startsWith("Thiếu model")) LocalPosePhase.MODEL_MISSING else LocalPosePhase.ERROR
            onRuntime(phase, thermalState, detail)
            return
        }
        val allowed = synchronized(lock) { started && thermalState.allowsLocalPose() }
        if (allowed) {
            frameClient.start()
            frameClient.setEnabled(true, thermalState.localPosePollDelayMs())
            onRuntime(LocalPosePhase.WAITING_FRAME_ACCESS, thermalState, detail)
        }
    }

    private fun onPoseObservation(observation: PoseFrameObservation) {
        val update = classifier.observe(observation)
        val classification = update.classification
        if (classification != null) sourceCoordinator.acceptPose(classification)
        val phase = if (update.calibration.calibrated) LocalPosePhase.LIVE else LocalPosePhase.CALIBRATING
        val detail = if (update.calibration.calibrated) {
            "stable=${classification?.state?.name ?: "WAIT"} raw=${update.rawState.name} ${update.calibration.reason}"
        } else {
            "${update.calibration.acceptedSamples}/${update.calibration.requiredSamples}: ${update.calibration.reason}"
        }
        onRuntime(phase, thermalState, detail)
    }

    private fun onEngineDiagnostic(detail: String) {
        sourceCoordinator.poseUnavailable()
        onRuntime(LocalPosePhase.ERROR, thermalState, detail)
    }

    private fun onFrameState(state: LocalFrameFetchState, detail: String) {
        when (state) {
            LocalFrameFetchState.STOPPED -> Unit
            LocalFrameFetchState.WAITING_ACCESS, LocalFrameFetchState.UNAUTHORIZED -> {
                sourceCoordinator.poseUnavailable()
                onRuntime(LocalPosePhase.WAITING_FRAME_ACCESS, thermalState, detail)
            }
            LocalFrameFetchState.WAITING_WIFI -> {
                sourceCoordinator.poseUnavailable()
                onRuntime(LocalPosePhase.WAITING_WIFI, thermalState, detail)
            }
            LocalFrameFetchState.FETCHING -> {
                classifier.stale(SystemClock.elapsedRealtime())?.classification?.let(sourceCoordinator::acceptPose)
                // A successful inference reports richer calibration progress. Do not
                // overwrite it five times per second with the transport-level state.
            }
            LocalFrameFetchState.ERROR -> {
                sourceCoordinator.poseUnavailable()
                onRuntime(LocalPosePhase.ERROR, thermalState, detail)
            }
        }
    }

    private fun onThermalState(value: PostureThermalState) {
        synchronized(lock) { thermalState = value }
        val ready = synchronized(lock) { started && modelReady }
        if (!value.allowsLocalPose()) {
            frameClient.setEnabled(false)
            sourceCoordinator.poseUnavailable()
            onRuntime(LocalPosePhase.PAUSED_THERMAL, value, "Tạm dừng Pose để bảo vệ nhiệt Watch")
        } else if (ready) {
            frameClient.start()
            frameClient.setEnabled(true, value.localPosePollDelayMs())
            onRuntime(
                if (classifier.isCalibrated()) LocalPosePhase.LIVE else LocalPosePhase.WAITING_FRAME_ACCESS,
                value,
                if (value == PostureThermalState.MODERATE) "Giảm còn 2 FPS do nhiệt" else "Pose local sẵn sàng",
            )
        } else {
            onRuntime(LocalPosePhase.LOADING_MODEL, value, "Đang nạp Pose Landmarker Lite")
        }
    }
}
