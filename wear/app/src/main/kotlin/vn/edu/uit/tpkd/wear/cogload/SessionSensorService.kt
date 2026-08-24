// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Keeps deterministic motion collection alive; heart rate is independently optional. */
class SessionSensorService : Service() {
    private lateinit var repository: StudySessionRepository
    private lateinit var motionCollector: AccCollector
    private lateinit var heartRateCollector: HeartRateCollector
    private val handler = Handler(Looper.getMainLooper())
    private var collectingSessionId: String? = null
    private val thresholdCalibrator = PersonalActivityThresholdCalibrator()
    private lateinit var postureSourceCoordinator: PostureSourceCoordinator
    private lateinit var postureIngestor: FaceObservationIngestor
    private lateinit var postureBleClient: FaceObservationBleClient
    private lateinit var localPosePipeline: LocalPosePosturePipeline
    private lateinit var yawnSyncClient: YawnSyncClient
    private var currentThermalState = PostureThermalState.UNKNOWN
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_SCREEN_OFF) {
                applyPowerPolicy()
            }
        }
    }

    private val heartRateTicker = object : Runnable {
        override fun run() {
            val active = repository.activeSession() ?: run { stopSelf(); return }
            val now = System.currentTimeMillis()
            val hasHeartRatePermission = hasHeartRatePermission()
            val phase = (now - active.startTimeMs).coerceAtLeast(0L) % HEART_RATE_INTERVAL_MS
            val shouldMeasure = hasHeartRatePermission && !StudySessionClock.isOnBreak(active, now) &&
                phase < HEART_RATE_DURATION_MS
            if (shouldMeasure) heartRateCollector.start() else heartRateCollector.stop()
            handler.postDelayed(this, HEART_RATE_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = StudySessionRepository(this)
        motionCollector = AccCollector(this) { metrics ->
            repository.activeSession()?.sessionId?.let { sessionId ->
                repository.updateActiveMotion(sessionId, metrics)
                repository.updateActiveRuleActivity(
                    sessionId,
                    thresholdCalibrator.classify(sessionId, metrics),
                    metrics.observedAtMs,
                )
            }
        }
        heartRateCollector = HeartRateCollector(
            context = this,
            onHeartRate = { bpm, observedAtMs ->
                repository.activeSession()?.sessionId?.let {
                    repository.updateActiveHeartRate(it, bpm, observedAtMs)
                }
            },
        )
        postureSourceCoordinator = PostureSourceCoordinator(
            onUpdate = { update ->
                repository.activeSession()?.sessionId?.let { sessionId ->
                    repository.updateActivePosture(sessionId, update.summaries, update.insights)
                }
            },
            onSource = PostureRuntimeStore::updateSelectedSource,
        )
        postureIngestor = FaceObservationIngestor(
            wallClockMs = System::currentTimeMillis,
            monotonicMs = SystemClock::elapsedRealtime,
            onUpdate = { update -> postureSourceCoordinator.acceptGeometry(update.classification) },
            onRuntime = PostureRuntimeStore::update,
        )
        localPosePipeline = LocalPosePosturePipeline(
            context = this,
            sourceCoordinator = postureSourceCoordinator,
            yawnClassifier = repository.activeSession()?.let { active ->
                YawnClassifier(
                    YawnSeed(
                        totalCount = active.yawnCount,
                        alertCount = active.yawnAlertCount,
                        totalDurationMs = active.yawnTotalDurationMs,
                        recentEventTimesMs = active.recentYawnEventTimesMs,
                        lastAlertAtMs = active.lastYawnAlertAtMs,
                    ),
                )
            } ?: YawnClassifier(),
            onRuntime = PostureRuntimeStore::updateLocalPose,
            onYawn = { detection ->
                val active = repository.activeSession()
                if (active != null && detection.persistenceChanged) {
                    repository.updateActiveYawn(active.sessionId, detection)?.let { updated ->
                        if (::yawnSyncClient.isInitialized) {
                            yawnSyncClient.updateSession(updated)
                            yawnSyncClient.wake()
                        }
                        if (::postureBleClient.isInitialized) postureBleClient.updateYawnSession(updated)
                    }
                }
                if (detection.alertJustTriggered) notifyYawnAlert(detection.eventsInWindow)
            },
            onCanonicalYawnSync = { state ->
                repository.activeSession()?.let { active ->
                    repository.applyCanonicalYawnSync(active.sessionId, state)?.let { updated ->
                        if (::yawnSyncClient.isInitialized) yawnSyncClient.updateSession(updated)
                        if (::postureBleClient.isInitialized) postureBleClient.updateYawnSession(updated)
                    }
                }
            },
            onThermalStateChanged = { state ->
                currentThermalState = state
                applyPowerPolicy()
            },
            requestFrameAccessRefresh = {
                if (::postureBleClient.isInitialized) postureBleClient.refreshFrameAccessInfo()
            },
        )
        yawnSyncClient = YawnSyncClient(
            context = this,
            onCanonicalState = { state, acknowledgedEventId ->
                repository.activeSession()?.let { active ->
                    repository.applyCanonicalYawnSync(active.sessionId, state, acknowledgedEventId)?.let { updated ->
                        yawnSyncClient.updateSession(updated)
                        if (::postureBleClient.isInitialized) postureBleClient.updateYawnSession(updated)
                    }
                }
                localPosePipeline.applyCanonicalYawnSync(state)
            },
            onUnauthorized = {
                if (::postureBleClient.isInitialized) postureBleClient.refreshFrameAccessInfo()
            },
        )
        postureBleClient = FaceObservationBleClient(
            context = this,
            ingestor = postureIngestor,
            onFrameAccess = { endpoint ->
                localPosePipeline.updateFrameAccess(endpoint)
                yawnSyncClient.updateEndpoint(endpoint)
            },
            onYawnBleState = { state, acknowledgedEventId ->
                repository.activeSession()?.let { active ->
                    repository.applyCanonicalYawnSync(active.sessionId, state, acknowledgedEventId)?.let { updated ->
                        yawnSyncClient.updateSession(updated)
                        postureBleClient.updateYawnSession(updated)
                    }
                }
                localPosePipeline.applyCanonicalYawnSync(state)
            },
            onYawnBleSupport = { supported ->
                yawnSyncClient.setFallbackEnabled(!supported)
                YawnSyncRuntimeStore.update(
                    if (supported) YawnSyncCompatibility.V2 else YawnSyncCompatibility.UNKNOWN,
                )
            },
            onEspBootChanged = {
                repository.activeSession()?.let { active ->
                    repository.resetActiveYawnSyncEpoch(active.sessionId)?.let { updated ->
                        yawnSyncClient.updateSession(updated)
                        postureBleClient.updateYawnSession(updated)
                    }
                }
            },
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            // SCREEN_ON/OFF are protected framework broadcasts. EXPORTED is
            // required so broadcasts sent by the system process reach us.
            ContextCompat.RECEIVER_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = repository.activeSession() ?: run {
            StudyDndController.disable(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (StudySessionClock.isOnBreak(active, System.currentTimeMillis())) {
            StudyDndController.disable(this)
            stopSelf()
            return START_NOT_STICKY
        }
        StudyDndController.enable(this)
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (error: Exception) {
            ReminderDiagnostics.recordEvent(this, "sensor_service_foreground_failed", error.javaClass.simpleName)
            StudyDndController.disable(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (collectingSessionId != active.sessionId) {
            motionCollector.stop()
            motionCollector.start(active.startTimeMs)
            collectingSessionId = active.sessionId
        }
        handler.removeCallbacks(heartRateTicker)
        handler.post(heartRateTicker)
        localPosePipeline.start()
        yawnSyncClient.updateSession(active)
        yawnSyncClient.start()
        postureBleClient.updateYawnSession(active)
        postureBleClient.start()
        applyPowerPolicy()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartRateTicker)
        motionCollector.stop()
        heartRateCollector.stop()
        yawnSyncClient.stop(closeSession = repository.activeSession() == null)
        postureBleClient.stop()
        localPosePipeline.stop()
        postureIngestor.reset()
        postureSourceCoordinator.reset()
        YawnRuntimeStore.reset()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        collectingSessionId = null
        if (::repository.isInitialized && shouldReleaseStudyDnd(repository.activeSession(), System.currentTimeMillis())) {
            StudyDndController.disable(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasHeartRatePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= ANDROID_16_API) {
            HEART_RATE_PERMISSION
        } else {
            Manifest.permission.BODY_SENSORS
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Đo phiên học", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Đang đo phiên học")
            .setContentText("Chuyển động đang hoạt động; nhịp tim tùy quyền cảm biến")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyYawnAlert(eventsInWindow: Int) {
        val vibrator = getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(YAWN_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isInteractive == true) {
            handler.post {
                Toast.makeText(
                    this,
                    "Bạn hơi buồn ngủ rồi hả? Đã ngáp $eventsInWindow lần trong 10 phút.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun applyPowerPolicy() {
        if (!::postureBleClient.isInitialized || !::localPosePipeline.isInitialized) return
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        localPosePipeline.setInteractive(interactive)
        val policy = watchConnectionPowerPolicy(interactive, currentThermalState)
        postureBleClient.setPowerMode(
            rateDhz = policy.rateDhz,
            interactive = policy.interactivePriority,
        )
    }

    companion object {
        private const val ANDROID_16_API = 36
        private const val HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
        private const val CHANNEL_ID = "focusmate_session_measurement"
        private const val NOTIFICATION_ID = 4510
        private const val HEART_RATE_INTERVAL_MS = 5 * 60_000L
        private const val HEART_RATE_DURATION_MS = 60_000L
        private const val HEART_RATE_TICK_MS = 5_000L
        private const val YAWN_VIBRATION_MS = 180L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SessionSensorService::class.java)) }
                .onFailure { ReminderDiagnostics.recordEvent(context, "sensor_service_start_failed", it.javaClass.simpleName) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionSensorService::class.java))
        }
    }
}

internal fun shouldReleaseStudyDnd(active: ActiveStudySession?, nowMs: Long): Boolean =
    active == null || StudySessionClock.isOnBreak(active, nowMs)
