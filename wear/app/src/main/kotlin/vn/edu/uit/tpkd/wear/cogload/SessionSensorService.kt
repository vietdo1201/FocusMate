// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Keeps deterministic motion collection alive; heart rate is independently optional. */
class SessionSensorService : Service() {
    private lateinit var repository: StudySessionRepository
    private lateinit var motionCollector: AccCollector
    private lateinit var heartRateCollector: HeartRateCollector
    private val handler = Handler(Looper.getMainLooper())
    private var collectingSessionId: String? = null
    private var collectingBlockId: String? = null
    private var collectionGeneration = 0L
    private var collectionIdentity: CollectionIdentity? = null
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

    /** Persists the session clock independently from MainActivity and sensor permission state. */
    private val checkpointTicker = object : Runnable {
        override fun run() {
            val active = repository.activeSession() ?: run { stopSelf(); return }
            active.timeline?.takeIf { timeline ->
                timeline.state != SessionState.RECOVERY_REQUIRED &&
                    SystemClock.elapsedRealtime() - timeline.checkpointAt.elapsedMs >= CHECKPOINT_INTERVAL_MS
            }?.let { repository.checkpointActiveSession() }
            handler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = StudySessionRepository(this)
        motionCollector = AccCollector(this) { metrics ->
            currentCollectionActive()?.takeIf {
                metrics.sessionId == it.sessionId && metrics.blockId == it.focusBlockId
            }?.let { active ->
                repository.updateActiveMotion(active.sessionId, metrics)
                repository.updateActiveRuleActivity(
                    active.sessionId,
                    thresholdCalibrator.classify(active.sessionId, metrics),
                    metrics.observedAtMs,
                )
                // Motion is part of watch_rules_v2. Re-evaluate when a complete,
                // valid window arrives even if MainActivity is not visible.
                BreakReminderScheduler.requestImmediateCheck(this)
            }
        }
        heartRateCollector = HeartRateCollector(
            context = this,
            onHeartRate = { bpm, observedAtMs ->
                currentCollectionActive()?.let {
                    repository.updateActiveHeartRate(it.sessionId, bpm, observedAtMs)
                }
            },
        )
        postureSourceCoordinator = PostureSourceCoordinator(
            onUpdate = { update ->
                currentCollectionActive()?.let { active ->
                    repository.updateActivePosture(active.sessionId, update.summaries, update.insights)
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
                val active = currentCollectionActive()
                if (active != null && detection.persistenceChanged) {
                    repository.updateActiveYawn(active.sessionId, detection)?.let { updated ->
                        if (::yawnSyncClient.isInitialized) {
                            yawnSyncClient.updateSession(updated)
                            yawnSyncClient.wake()
                        }
                        if (::postureBleClient.isInitialized) postureBleClient.updateYawnSession(updated)
                    }
                }
                // Yawn remains a silent advisory. It is persisted for the session
                // screen/report but never starts its own vibration or notification.
            },
            onCanonicalYawnSync = { state ->
                currentCollectionActive()?.let { active ->
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
                currentCollectionActive()?.let { active ->
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
                currentCollectionActive()?.let { active ->
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
                currentCollectionActive()?.let { active ->
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
        if (StudySessionClock.isOnBreak(active, System.currentTimeMillis()) || StudySessionClock.isPaused(active)) {
            StudyDndController.disable(this)
            stopSelf()
            return START_NOT_STICKY
        }
        StudyDndController.enable(this)
        val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            foregroundServiceTypes(this)
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && serviceTypes == 0) {
            ReminderDiagnostics.recordEvent(this, "sensor_service_permission_missing", "no_eligible_fgs_type")
            notifyStartFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), serviceTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (error: Exception) {
            ReminderDiagnostics.recordEvent(this, "sensor_service_foreground_failed", error.javaClass.simpleName)
            notifyStartFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        if (collectingSessionId != active.sessionId || collectingBlockId != active.focusBlockId) {
            motionCollector.stop()
            collectionGeneration++
            collectionIdentity = CollectionIdentity(active.sessionId, active.focusBlockId, collectionGeneration)
            motionCollector.start(
                active.startTimeMs,
                active.focusBlockId,
                AndroidSessionClock(this).now().bootId,
                active.sessionId,
            )
            collectingSessionId = active.sessionId
            collectingBlockId = active.focusBlockId
        }
        handler.removeCallbacks(heartRateTicker)
        handler.post(heartRateTicker)
        handler.removeCallbacks(checkpointTicker)
        handler.post(checkpointTicker)
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
        handler.removeCallbacks(checkpointTicker)
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
        collectingBlockId = null
        collectionIdentity = null
        if (::repository.isInitialized && shouldReleaseStudyDnd(repository.activeSession(), System.currentTimeMillis())) {
            StudyDndController.disable(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentCollectionActive(): ActiveStudySession? {
        val identity = collectionIdentity ?: return null
        val active = repository.activeSession() ?: return null
        return active.takeIf {
            it.sessionId == identity.sessionId && it.focusBlockId == identity.blockId &&
                identity.generation == collectionGeneration
        }
    }

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

    private fun notifyStartFailed() {
        sendBroadcast(Intent(ACTION_START_FAILED).setPackage(packageName))
    }

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 30_000L
        private const val ANDROID_16_API = 36
        private const val HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
        private const val CHANNEL_ID = "focusmate_session_measurement"
        private const val NOTIFICATION_ID = 4510
        private const val HEART_RATE_INTERVAL_MS = 5 * 60_000L
        private const val HEART_RATE_DURATION_MS = 60_000L
        private const val HEART_RATE_TICK_MS = 5_000L
        internal const val ACTION_START_FAILED =
            "vn.edu.uit.tpkd.wear.cogload.action.SENSOR_SERVICE_START_FAILED"

        fun start(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                foregroundServiceTypes(context) == 0
            ) {
                ReminderDiagnostics.recordEvent(context, "sensor_service_permission_missing", "no_eligible_fgs_type")
                return false
            }
            return runCatching {
                context.startForegroundService(Intent(context, SessionSensorService::class.java))
            }.onFailure {
                ReminderDiagnostics.recordEvent(context, "sensor_service_start_failed", it.javaClass.simpleName)
            }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionSensorService::class.java))
        }

        internal fun foregroundServiceTypes(context: Context): Int {
            val heartRatePermission = if (Build.VERSION.SDK_INT >= ANDROID_16_API) {
                HEART_RATE_PERMISSION
            } else {
                Manifest.permission.BODY_SENSORS
            }
            val hasHeartRate = context.checkSelfPermission(heartRatePermission) == PackageManager.PERMISSION_GRANTED
            val hasActivityRecognition =
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            val hasBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            return sessionSensorForegroundServiceTypes(hasHeartRate, hasActivityRecognition, hasBluetooth)
        }
    }

    private data class CollectionIdentity(val sessionId: String, val blockId: String, val generation: Long)
}

@SuppressLint("InlinedApi")
internal fun sessionSensorForegroundServiceTypes(
    hasHeartRate: Boolean,
    hasActivityRecognition: Boolean,
    hasBluetooth: Boolean,
): Int {
    var types = 0
    if (hasHeartRate || hasActivityRecognition) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    }
    if (hasBluetooth) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }
    return types
}

internal fun shouldReleaseStudyDnd(active: ActiveStudySession?, nowMs: Long): Boolean =
    active == null || StudySessionClock.isOnBreak(active, nowMs)
