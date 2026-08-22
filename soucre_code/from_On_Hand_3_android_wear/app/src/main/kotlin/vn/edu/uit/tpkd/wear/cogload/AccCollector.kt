package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

data class MotionWindowMetrics(
    val observedAtMs: Long,
    val movementRms: Double,
    val rotationRms: Double,
    val suddenMovementCount: Int,
    val wristRotationCount: Int,
    val immobileSeconds: Double,
    val movementChangeFromBaseline: Double?,
    val watchRaiseCount: Int,
    val accelerometerSamples: Int,
    val gyroscopeSamples: Int,
    val stepCount: Int = 0,
    val orientationChangeDegrees: Double = 0.0,
    val stepDetectorAvailable: Boolean = false,
    val orientationSensorAvailable: Boolean = false,
)

/** Emits one explainable, non-overlapping motion window every 30 seconds. */
class AccCollector(
    context: Context,
    private val onMotionWindowReady: (MotionWindowMetrics) -> Unit,
) : SensorEventListener {
    private data class Sample(val timestampNanos: Long, val x: Float, val y: Float, val z: Float)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accBuffer = ArrayDeque<Sample>()
    private val gyroBuffer = ArrayDeque<Sample>()
    private var windowStartNanos = 0L
    private var sessionStartTimeMs = 0L
    private var baselineMovementSum = 0.0
    private var baselineWindowCount = 0
    private var latestGravity: FloatArray? = null
    private var windowStartGravity: FloatArray? = null
    private var latestOrientation: FloatArray? = null
    private var windowStartOrientation: FloatArray? = null
    private var windowStepCount = 0
    private var stepDetectorRegistered = false
    private var orientationSensorRegistered = false
    private var lastWatchViewable: Boolean? = null

    fun start(sessionStartTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (this.sessionStartTimeMs != sessionStartTimeMs) resetSession(sessionStartTimeMs)
        val acc = accelerometer?.let { sensorManager.registerListener(this, it, SAMPLE_PERIOD_MICROS) } ?: false
        val gyro = gyroscope?.let { sensorManager.registerListener(this, it, SAMPLE_PERIOD_MICROS) } ?: false
        gravitySensor?.let {
            orientationSensorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) ||
                orientationSensorRegistered
        }
        rotationVectorSensor?.let {
            orientationSensorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) ||
                orientationSensorRegistered
        }
        stepDetectorRegistered = stepDetector?.let {
            runCatching { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        } ?: false
        return acc || gyro
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        accBuffer.clear()
        gyroBuffer.clear()
        windowStartNanos = 0L
        windowStepCount = 0
        stepDetectorRegistered = false
        orientationSensorRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val now = event.timestamp
                accBuffer.addLast(Sample(now, event.values[0], event.values[1], event.values[2]))
                if (windowStartNanos == 0L) windowStartNanos = now
                if (now - windowStartNanos >= WINDOW_NANOS) emitWindow(now)
            }
            Sensor.TYPE_GYROSCOPE -> gyroBuffer.addLast(
                Sample(event.timestamp, event.values[0], event.values[1], event.values[2])
            )
            Sensor.TYPE_GRAVITY -> {
                latestGravity = event.values.copyOf(3)
                if (windowStartGravity == null) windowStartGravity = latestGravity?.copyOf()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                latestOrientation = FloatArray(4).also { SensorManager.getQuaternionFromVector(it, event.values) }
                if (windowStartOrientation == null) windowStartOrientation = latestOrientation?.copyOf()
            }
            Sensor.TYPE_STEP_DETECTOR -> windowStepCount++
        }
    }

    private fun emitWindow(nowNanos: Long) {
        val start = windowStartNanos
        val acc = accBuffer.filter { it.timestampNanos >= start }
        val gyro = gyroBuffer.filter { it.timestampNanos >= start }
        windowStartNanos = nowNanos
        trim(accBuffer, nowNanos - WINDOW_NANOS)
        trim(gyroBuffer, nowNanos - WINDOW_NANOS)
        if (acc.size < 2) return

        val linear = acc.map { sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) - GRAVITY }
        val movementRms = sqrt(linear.sumOf { it * it } / linear.size)
        val rotationRms = if (gyro.isEmpty()) 0.0 else sqrt(
            gyro.sumOf { (it.x * it.x + it.y * it.y + it.z * it.z).toDouble() } / gyro.size
        )
        var suddenCount = 0
        var above = false
        linear.forEach {
            val current = abs(it) >= SUDDEN_THRESHOLD
            if (current && !above) suddenCount++
            above = current
        }
        var immobileNanos = 0L
        for (index in 1 until acc.size) {
            if (abs(linear[index - 1]) <= IMMOBILE_THRESHOLD) {
                immobileNanos += (acc[index].timestampNanos - acc[index - 1].timestampNanos).coerceAtLeast(0L)
            }
        }
        var wristRotations = 0
        var radians = 0.0
        for (index in 1 until gyro.size) {
            val previous = gyro[index - 1]
            val seconds = (gyro[index].timestampNanos - previous.timestampNanos).coerceAtLeast(0L) / 1e9
            radians += sqrt((previous.x * previous.x + previous.y * previous.y + previous.z * previous.z).toDouble()) * seconds
            while (radians >= WRIST_ROTATION_RADIANS) {
                wristRotations++
                radians -= WRIST_ROTATION_RADIANS
            }
        }
        var watchRaises = 0
        acc.forEach {
            val magnitude = sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble())
            val viewable = magnitude > 0 && it.z / magnitude >= WATCH_VIEW_Z_RATIO
            if (viewable && lastWatchViewable == false) watchRaises++
            lastWatchViewable = viewable
        }
        val observedAtMs = System.currentTimeMillis()
        val orientationChange = max(
            vectorAngleDegrees(windowStartGravity, latestGravity),
            quaternionAngleDegrees(windowStartOrientation, latestOrientation),
        )
        if (observedAtMs - sessionStartTimeMs <= BASELINE_DURATION_MS) {
            baselineMovementSum += movementRms
            baselineWindowCount++
        }
        val baseline = if (baselineWindowCount == 0) null else baselineMovementSum / baselineWindowCount
        onMotionWindowReady(
            MotionWindowMetrics(
                observedAtMs, movementRms, rotationRms, suddenCount, wristRotations,
                immobileNanos / 1e9, baseline?.takeIf { it > 1e-6 }?.let { (movementRms - it) / it },
                watchRaises, acc.size, gyro.size, windowStepCount, orientationChange,
                stepDetectorRegistered, orientationSensorRegistered,
            )
        )
        windowStepCount = 0
        windowStartGravity = latestGravity?.copyOf()
        windowStartOrientation = latestOrientation?.copyOf()
    }

    private fun vectorAngleDegrees(first: FloatArray?, second: FloatArray?): Double {
        if (first == null || second == null) return 0.0
        val a = sqrt(first.sumOf { it.toDouble() * it })
        val b = sqrt(second.sumOf { it.toDouble() * it })
        if (a <= 1e-6 || b <= 1e-6) return 0.0
        return acos((first.indices.sumOf { first[it].toDouble() * second[it] } / (a * b)).coerceIn(-1.0, 1.0)) * 180 / PI
    }

    private fun quaternionAngleDegrees(first: FloatArray?, second: FloatArray?): Double {
        if (first == null || second == null || first.size != 4 || second.size != 4) return 0.0
        return 2 * acos(abs(first.indices.sumOf { first[it].toDouble() * second[it] }).coerceIn(0.0, 1.0)) * 180 / PI
    }

    private fun trim(buffer: ArrayDeque<Sample>, cutoff: Long) {
        while (buffer.peekFirst()?.timestampNanos?.let { it < cutoff } == true) buffer.removeFirst()
    }

    private fun resetSession(startMs: Long) {
        sessionStartTimeMs = startMs
        baselineMovementSum = 0.0
        baselineWindowCount = 0
        lastWatchViewable = null
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        const val SAMPLE_RATE_HZ = 25
        const val MOTION_WINDOW_SECONDS = 30.0
        private const val SAMPLE_PERIOD_MICROS = 1_000_000 / SAMPLE_RATE_HZ
        private const val WINDOW_NANOS = 30_000_000_000L
        private const val BASELINE_DURATION_MS = 5 * 60_000L
        private const val GRAVITY = 9.80665
        private const val SUDDEN_THRESHOLD = 3.0
        private const val IMMOBILE_THRESHOLD = 0.12
        private const val WRIST_ROTATION_RADIANS = 25.0 * PI / 180.0
        private const val WATCH_VIEW_Z_RATIO = 0.72
    }
}
