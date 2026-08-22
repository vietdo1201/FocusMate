package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps deterministic motion collection alive; heart rate is independently optional. */
class SessionSensorService : Service() {
    private lateinit var repository: StudySessionRepository
    private lateinit var motionCollector: AccCollector
    private lateinit var heartRateCollector: HeartRateCollector
    private val handler = Handler(Looper.getMainLooper())
    private var collectingSessionId: String? = null
    private val thresholdCalibrator = PersonalActivityThresholdCalibrator()

    private val heartRateTicker = object : Runnable {
        override fun run() {
            val active = repository.activeSession() ?: run { stopSelf(); return }
            val now = System.currentTimeMillis()
            val hasHeartRatePermission = checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
                PackageManager.PERMISSION_GRANTED
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = repository.activeSession() ?: run { stopSelf(); return START_NOT_STICKY }
        if (StudySessionClock.isOnBreak(active, System.currentTimeMillis())) {
            stopSelf()
            return START_NOT_STICKY
        }
        StudyDndController.enable(this)
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (error: Exception) {
            ReminderDiagnostics.recordEvent(this, "sensor_service_foreground_failed", error.javaClass.simpleName)
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
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartRateTicker)
        motionCollector.stop()
        heartRateCollector.stop()
        collectingSessionId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    companion object {
        private const val CHANNEL_ID = "focusmate_session_measurement"
        private const val NOTIFICATION_ID = 4510
        private const val HEART_RATE_INTERVAL_MS = 5 * 60_000L
        private const val HEART_RATE_DURATION_MS = 60_000L
        private const val HEART_RATE_TICK_MS = 5_000L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SessionSensorService::class.java)) }
                .onFailure { ReminderDiagnostics.recordEvent(context, "sensor_service_start_failed", it.javaClass.simpleName) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionSensorService::class.java))
        }
    }
}
