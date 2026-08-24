package vn.edu.uit.tpkd.wear.cogload

import kotlin.math.abs

enum class YawnState {
    CALIBRATING,
    IDLE,
    MOUTH_OPEN,
    YAWNING,
    UNAVAILABLE,
}

data class YawnFrameObservation(
    val observedAtMonoMs: Long,
    val observedAtWallMs: Long,
    val jawOpen: Double?,
    val mouthAspectRatio: Double?,
)

data class YawnSeed(
    val totalCount: Int = 0,
    val alertCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val recentEventTimesMs: List<Long> = emptyList(),
    val lastAlertAtMs: Long? = null,
)

data class YawnDetection(
    val state: YawnState,
    val observedAtMonoMs: Long,
    val jawOpen: Double? = null,
    val mouthAspectRatio: Double? = null,
    val calibrated: Boolean = false,
    val calibrationProgress: Int = 0,
    val calibrationRequired: Int = REQUIRED_SAMPLES,
    val calibrationSpanMs: Long = 0L,
    val totalCount: Int = 0,
    val alertCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val eventsInWindow: Int = 0,
    val recentEventTimesMs: List<Long> = emptyList(),
    val lastAlertAtMs: Long? = null,
    val advisory: Boolean = false,
    val eventJustCounted: Boolean = false,
    val alertJustTriggered: Boolean = false,
    val persistenceChanged: Boolean = false,
    val reason: String = "",
) {
    companion object {
        const val REQUIRED_SAMPLES = 20
    }
}

class YawnClassifier(seed: YawnSeed = YawnSeed()) {
    private data class CalibrationSample(val monoMs: Long, val mar: Double, val jaw: Double)
    private data class Baseline(
        val center: Double,
        val noise: Double,
        val jawCenter: Double,
        val jawNoise: Double,
    )

    private val samples = ArrayDeque<CalibrationSample>()
    private var baseline: Baseline? = null
    private var candidateStartedAtMono: Long? = null
    private var lastOpenAtMono: Long? = null
    private var closedSinceMono: Long? = null
    private var peakJaw = 0.0
    private var peakMar = 0.0
    private var countedCurrentOpen = false
    private var currentYawnStartedAtMono: Long? = null
    private var lastEventAtMono = Long.MIN_VALUE
    private val recentEventTimesMs = ArrayDeque<Long>()
    private var totalCount = seed.totalCount.coerceAtLeast(0)
    private var alertCount = seed.alertCount.coerceAtLeast(0)
    private var totalDurationMs = seed.totalDurationMs.coerceAtLeast(0L)
    private var lastAlertAtMs = seed.lastAlertAtMs
    private var remoteEventsInWindow = 0
    private var lastRemoteSyncAtMs: Long? = null

    init {
        seed.recentEventTimesMs.sorted().forEach(recentEventTimesMs::addLast)
    }

    fun observe(observation: YawnFrameObservation): YawnDetection {
        pruneEvents(observation.observedAtWallMs)
        val jaw = observation.jawOpen
        val mar = observation.mouthAspectRatio
        if (jaw == null || mar == null || !jaw.isFinite() || !mar.isFinite()) {
            candidateStartedAtMono = null
            lastOpenAtMono = null
            peakJaw = 0.0
            peakMar = 0.0
            return snapshot(
                state = if (baseline == null) YawnState.CALIBRATING else YawnState.UNAVAILABLE,
                observation = observation,
                reason = "face_missing",
            )
        }

        if (baseline == null) return calibrate(observation, jaw, mar)
        val currentBaseline = requireNotNull(baseline)
        val marOpenThreshold = currentBaseline.center + maxOf(3.0 * currentBaseline.noise, 0.05)
        val marPeakThreshold = currentBaseline.center + maxOf(5.0 * currentBaseline.noise, 0.10)
        val marCloseThreshold = currentBaseline.center + maxOf(2.0 * currentBaseline.noise, 0.035)
        val jawOpenThreshold = maxOf(
            OPEN_JAW_FLOOR,
            currentBaseline.jawCenter + maxOf(4.0 * currentBaseline.jawNoise, 0.18),
        )
        val jawPeakThreshold = maxOf(
            PEAK_JAW_FLOOR,
            currentBaseline.jawCenter + maxOf(6.0 * currentBaseline.jawNoise, 0.28),
        )
        val jawCloseThreshold = currentBaseline.jawCenter + maxOf(2.0 * currentBaseline.jawNoise, 0.10)
        val open = jaw >= jawOpenThreshold && mar >= marOpenThreshold
        var eventJustCounted = false
        var alertJustTriggered = false
        var persistenceChanged = false
        var state = YawnState.IDLE

        if (open) {
            closedSinceMono = null
            val previousOpen = lastOpenAtMono
            if (previousOpen == null || observation.observedAtMonoMs - previousOpen > MAX_OPEN_SAMPLE_GAP_MS) {
                if (!countedCurrentOpen) candidateStartedAtMono = observation.observedAtMonoMs
                peakJaw = jaw
                peakMar = mar
            }
            lastOpenAtMono = observation.observedAtMonoMs
            peakJaw = maxOf(peakJaw, jaw)
            peakMar = maxOf(peakMar, mar)
            state = if (countedCurrentOpen) YawnState.YAWNING else YawnState.MOUTH_OPEN
            val candidateStart = candidateStartedAtMono
            if (!countedCurrentOpen && candidateStart != null &&
                observation.observedAtMonoMs - candidateStart >= OPEN_DURATION_MS &&
                (peakJaw >= jawPeakThreshold || peakMar >= marPeakThreshold) &&
                elapsedSince(observation.observedAtMonoMs, lastEventAtMono) >= EVENT_COOLDOWN_MS
            ) {
                countedCurrentOpen = true
                currentYawnStartedAtMono = candidateStart
                lastEventAtMono = observation.observedAtMonoMs
                totalCount += 1
                recentEventTimesMs.addLast(observation.observedAtWallMs)
                pruneEvents(observation.observedAtWallMs)
                eventJustCounted = true
                persistenceChanged = true
                state = YawnState.YAWNING
                val previousAlertAt = lastAlertAtMs
                if (eventsInWindowCount() >= ALERT_EVENT_COUNT &&
                    (previousAlertAt == null || observation.observedAtWallMs - previousAlertAt >= ALERT_COOLDOWN_MS)
                ) {
                    lastAlertAtMs = observation.observedAtWallMs
                    alertCount += 1
                    alertJustTriggered = true
                }
            }
        } else {
            if (jaw <= jawCloseThreshold || mar <= marCloseThreshold) {
                if (closedSinceMono == null) closedSinceMono = observation.observedAtMonoMs
            }
            val previousOpen = lastOpenAtMono
            if (!countedCurrentOpen && previousOpen != null &&
                observation.observedAtMonoMs - previousOpen > OPEN_GAP_MS
            ) {
                candidateStartedAtMono = null
                lastOpenAtMono = null
                peakJaw = 0.0
                peakMar = 0.0
            }
            val closedSince = closedSinceMono
            if (countedCurrentOpen && closedSince != null &&
                observation.observedAtMonoMs - closedSince >= CLOSE_HOLD_MS &&
                elapsedSince(observation.observedAtMonoMs, lastEventAtMono) >= EVENT_COOLDOWN_MS
            ) {
                currentYawnStartedAtMono?.let {
                    totalDurationMs += maxOf(OPEN_DURATION_MS, observation.observedAtMonoMs - it)
                    persistenceChanged = true
                }
                candidateStartedAtMono = null
                lastOpenAtMono = null
                peakJaw = 0.0
                peakMar = 0.0
                countedCurrentOpen = false
                currentYawnStartedAtMono = null
            }
            if (countedCurrentOpen) state = YawnState.YAWNING
        }

        return snapshot(
            state = state,
            observation = observation,
            jaw = jaw,
            mar = mar,
            reason = if (eventsInWindowCount() >= ALERT_EVENT_COUNT) "repeated_yawn" else "live",
            eventJustCounted = eventJustCounted,
            alertJustTriggered = alertJustTriggered,
            persistenceChanged = persistenceChanged,
        )
    }

    fun reset() {
        baseline = null
        samples.clear()
        candidateStartedAtMono = null
        lastOpenAtMono = null
        closedSinceMono = null
        peakJaw = 0.0
        peakMar = 0.0
        countedCurrentOpen = false
        currentYawnStartedAtMono = null
        lastEventAtMono = Long.MIN_VALUE
        remoteEventsInWindow = 0
        lastRemoteSyncAtMs = null
    }

    fun synchronizeRemote(
        remoteTotalCount: Int,
        remoteWindowCount: Int,
        observedAtMonoMs: Long,
        observedAtWallMs: Long,
    ): YawnDetection? {
        pruneEvents(observedAtWallMs)
        val boundedTotal = remoteTotalCount.coerceIn(0, 1_000_000)
        val boundedWindow = remoteWindowCount.coerceIn(0, 1_000)
        val totalChanged = boundedTotal > totalCount
        val windowChanged = boundedWindow != remoteEventsInWindow
        if (!totalChanged && !windowChanged) {
            lastRemoteSyncAtMs = observedAtWallMs
            return null
        }
        if (totalChanged) {
            totalCount = boundedTotal
            candidateStartedAtMono = null
            lastOpenAtMono = observedAtMonoMs
            closedSinceMono = null
            peakJaw = 0.0
            peakMar = 0.0
            countedCurrentOpen = true
            currentYawnStartedAtMono = null
            lastEventAtMono = observedAtMonoMs
        }
        remoteEventsInWindow = boundedWindow
        lastRemoteSyncAtMs = observedAtWallMs
        var alertJustTriggered = false
        val previousAlertAt = lastAlertAtMs
        if (eventsInWindowCount() >= ALERT_EVENT_COUNT &&
            (previousAlertAt == null || observedAtWallMs - previousAlertAt >= ALERT_COOLDOWN_MS)
        ) {
            lastAlertAtMs = observedAtWallMs
            alertCount += 1
            alertJustTriggered = true
        }
        return snapshot(
            state = if (totalChanged) YawnState.YAWNING else YawnState.IDLE,
            observation = YawnFrameObservation(observedAtMonoMs, observedAtWallMs, null, null),
            reason = if (eventsInWindowCount() >= ALERT_EVENT_COUNT) "repeated_yawn" else "remote_sync",
            eventJustCounted = totalChanged,
            alertJustTriggered = alertJustTriggered,
            persistenceChanged = totalChanged || windowChanged || alertJustTriggered,
        )
    }

    private fun calibrate(observation: YawnFrameObservation, jaw: Double, mar: Double): YawnDetection {
        var reason = if (jaw < CALIBRATION_JAW_MAX) "collecting_closed_mouth" else "mouth_moving"
        if (jaw < CALIBRATION_JAW_MAX) {
            samples.addLast(CalibrationSample(observation.observedAtMonoMs, mar, jaw))
        }
        while (samples.size > REQUIRED_SAMPLES) samples.removeFirst()
        var baselineReady = false
        if (samples.size == REQUIRED_SAMPLES && samples.last().monoMs - samples.first().monoMs >= CALIBRATION_SPAN_MS) {
            val values = samples.map(CalibrationSample::mar)
            val center = median(values)
            val noise = maxOf(0.002, median(values.map { abs(it - center) }))
            val jawValues = samples.map(CalibrationSample::jaw)
            val jawCenter = median(jawValues)
            val jawNoise = maxOf(0.002, median(jawValues.map { abs(it - jawCenter) }))
            if (noise <= MAX_CALIBRATION_MAD && jawNoise <= MAX_JAW_CALIBRATION_MAD) {
                baseline = Baseline(center, noise, jawCenter, jawNoise)
                samples.clear()
                baselineReady = true
                reason = "baseline_ready"
            } else {
                samples.removeFirst()
                reason = "mouth_moving"
            }
        }
        return snapshot(
            state = if (baselineReady) YawnState.IDLE else YawnState.CALIBRATING,
            observation = observation,
            jaw = jaw,
            mar = mar,
            reason = reason,
        )
    }

    private fun snapshot(
        state: YawnState,
        observation: YawnFrameObservation,
        jaw: Double? = null,
        mar: Double? = null,
        reason: String,
        eventJustCounted: Boolean = false,
        alertJustTriggered: Boolean = false,
        persistenceChanged: Boolean = false,
    ): YawnDetection {
        val span = if (samples.size > 1) samples.last().monoMs - samples.first().monoMs else 0L
        val windowCount = eventsInWindowCount()
        return YawnDetection(
            state = state,
            observedAtMonoMs = observation.observedAtMonoMs,
            jawOpen = jaw,
            mouthAspectRatio = mar,
            calibrated = baseline != null,
            calibrationProgress = if (baseline == null) samples.size else REQUIRED_SAMPLES,
            calibrationSpanMs = if (baseline == null) span else CALIBRATION_SPAN_MS,
            totalCount = totalCount,
            alertCount = alertCount,
            totalDurationMs = totalDurationMs,
            eventsInWindow = windowCount,
            recentEventTimesMs = recentEventTimesMs.toList(),
            lastAlertAtMs = lastAlertAtMs,
            advisory = windowCount >= ALERT_EVENT_COUNT,
            eventJustCounted = eventJustCounted,
            alertJustTriggered = alertJustTriggered,
            persistenceChanged = persistenceChanged,
            reason = reason,
        )
    }

    private fun pruneEvents(nowWallMs: Long) {
        while (recentEventTimesMs.isNotEmpty() && nowWallMs - recentEventTimesMs.first() > WINDOW_MS) {
            recentEventTimesMs.removeFirst()
        }
        val remoteAt = lastRemoteSyncAtMs
        if (remoteAt != null && nowWallMs - remoteAt > WINDOW_MS) remoteEventsInWindow = 0
    }

    private fun eventsInWindowCount(): Int = maxOf(recentEventTimesMs.size, remoteEventsInWindow)

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun elapsedSince(now: Long, previous: Long): Long =
        if (previous == Long.MIN_VALUE) Long.MAX_VALUE else (now - previous).coerceAtLeast(0L)

    companion object {
        const val REQUIRED_SAMPLES = YawnDetection.REQUIRED_SAMPLES
        private const val CALIBRATION_SPAN_MS = 5_000L
        private const val MAX_CALIBRATION_MAD = 0.025
        private const val MAX_JAW_CALIBRATION_MAD = 0.04
        private const val CALIBRATION_JAW_MAX = 0.30
        private const val OPEN_JAW_FLOOR = 0.32
        private const val PEAK_JAW_FLOOR = 0.42
        private const val OPEN_DURATION_MS = 1_000L
        private const val OPEN_GAP_MS = 250L
        // Face runs at 2-2.5 FPS. This is the maximum gap between consecutive
        // open observations, not the permitted duration of a measured closure.
        private const val MAX_OPEN_SAMPLE_GAP_MS = 900L
        private const val CLOSE_HOLD_MS = 500L
        private const val EVENT_COOLDOWN_MS = 2_000L
        private const val WINDOW_MS = 10 * 60_000L
        private const val ALERT_COOLDOWN_MS = 10 * 60_000L
        private const val ALERT_EVENT_COUNT = 3
    }
}

object YawnRuntimeStore {
    @Volatile
    var snapshot: YawnDetection? = null
        private set

    @Synchronized
    fun update(value: YawnDetection) {
        snapshot = value
    }

    @Synchronized
    fun reset() {
        snapshot = null
    }
}
