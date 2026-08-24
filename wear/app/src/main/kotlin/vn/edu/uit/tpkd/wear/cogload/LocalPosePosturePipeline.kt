// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.os.SystemClock

/** Connects BLE-delivered frame access, local HTTP JPEGs, MediaPipe, and source fallback. */
class LocalPosePosturePipeline(
    context: Context,
    private val sourceCoordinator: PostureSourceCoordinator,
    private val classifier: PosePostureClassifier = PosePostureClassifier(),
    private val yawnClassifier: YawnClassifier = YawnClassifier(),
    private val onRuntime: (LocalPosePhase, PostureThermalState, String) -> Unit,
    private val onYawn: (YawnDetection) -> Unit = {},
    private val onCanonicalYawnSync: (CanonicalYawnSyncState) -> Unit = {},
    private val onThermalStateChanged: (PostureThermalState) -> Unit = {},
    private val requestFrameAccessRefresh: () -> Unit,
) {
    private val lock = Any()
    private val yawnLock = Any()
    private var started = false
    private var modelReady = false
    private var thermalState = PostureThermalState.UNKNOWN
    private var interactive = true
    private var endpointBootId: String? = null
    private var lastRemoteYawnSequence: Long? = null
    private val engine = PoseLandmarkerEngine(
        context = context,
        onObservation = ::onPoseObservation,
        onYawnObservation = ::onYawnObservation,
        onAvailability = ::onEngineAvailability,
        onDiagnostic = ::onEngineDiagnostic,
    )
    private val frameClient = LocalFrameClient(
        context = context,
        onFrame = ::onFrame,
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
            lastRemoteYawnSequence = null
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
        yawnClassifier.reset()
        YawnRuntimeStore.reset()
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
            frameClient.setEnabled(true, effectivePollDelayMs())
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

    private fun onYawnObservation(observation: YawnFrameObservation) {
        val detection = synchronized(yawnLock) { yawnClassifier.observe(observation) }
        YawnRuntimeStore.update(detection)
        onYawn(detection)
    }

    private fun onFrame(frame: LocalFramePacket) {
        val sync = frame.yawnSync
        if (sync != null && isNewerSequence(sync.sequence, lastRemoteYawnSequence)) {
            lastRemoteYawnSequence = sync.sequence
            if (sync.schema == 2 && sync.session != null) {
                onCanonicalYawnSync(
                    CanonicalYawnSyncState(sync.session, sync.revision, sync.totalCount, sync.windowCount),
                )
            }
            synchronized(yawnLock) { yawnClassifier.synchronizeRemote(
                remoteTotalCount = sync.totalCount,
                remoteWindowCount = sync.windowCount,
                observedAtMonoMs = frame.receivedAtMonoMs,
                observedAtWallMs = System.currentTimeMillis(),
            ) }?.let { detection ->
                YawnRuntimeStore.update(detection)
                onYawn(detection)
            }
        }
        engine.offer(frame)
    }

    fun applyCanonicalYawnSync(state: CanonicalYawnSyncState) {
        synchronized(yawnLock) {
            yawnClassifier.synchronizeRemote(
                remoteTotalCount = state.totalCount,
                remoteWindowCount = state.windowCount,
                observedAtMonoMs = SystemClock.elapsedRealtime(),
                observedAtWallMs = System.currentTimeMillis(),
            )
        }?.let { detection ->
            YawnRuntimeStore.update(detection)
            onYawn(detection)
        }
    }

    fun setInteractive(value: Boolean) {
        synchronized(lock) { interactive = value }
        applyInferenceAndPollPolicy()
    }

    private fun isNewerSequence(candidate: Long, previous: Long?): Boolean {
        if (previous == null) return true
        val distance = (candidate - previous) and UINT32_MAX
        return distance in 1L..0x7FFF_FFFFL
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
        onThermalStateChanged(value)
        applyInferenceAndPollPolicy()
        val ready = synchronized(lock) { started && modelReady }
        if (!value.allowsLocalPose()) {
            frameClient.setEnabled(false)
            sourceCoordinator.poseUnavailable()
            onRuntime(LocalPosePhase.PAUSED_THERMAL, value, "Tạm dừng Pose để bảo vệ nhiệt Watch")
        } else if (ready) {
            frameClient.start()
            frameClient.setEnabled(true, effectivePollDelayMs())
            onRuntime(
                if (classifier.isCalibrated()) LocalPosePhase.LIVE else LocalPosePhase.WAITING_FRAME_ACCESS,
                value,
                if (value == PostureThermalState.MODERATE) "Giảm còn 2 FPS do nhiệt" else "Pose local sẵn sàng",
            )
        } else {
            onRuntime(LocalPosePhase.LOADING_MODEL, value, "Đang nạp Pose Landmarker Lite")
        }
    }

    private fun applyInferenceAndPollPolicy() {
        val (thermal, isInteractive, ready) = synchronized(lock) {
            Triple(thermalState, interactive, started && modelReady)
        }
        val policy = watchConnectionPowerPolicy(isInteractive, thermal)
        engine.setFaceInferenceIntervalMs(policy.faceInferenceIntervalMs)
        if (ready && thermal.allowsLocalPose()) frameClient.setEnabled(true, effectivePollDelayMs())
    }

    private fun effectivePollDelayMs(): Long = synchronized(lock) {
        watchConnectionPowerPolicy(interactive, thermalState).framePollDelayMs ?: 500L
    }

    private companion object {
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
