// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType

/** Short, capability-checked heart-rate measurements using Wear Health Services. */
class HeartRateCollector(
    context: Context,
    private val onHeartRate: (bpm: Double, observedAtMs: Long) -> Unit,
    private val onStatusChanged: (Status) -> Unit = {},
) {
    enum class Status { IDLE, CHECKING, MEASURING, UNAVAILABLE, ERROR }

    private val appContext = context.applicationContext
    private val measureClient = HealthServices.getClient(appContext).measureClient
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private var status = Status.IDLE
    private var registered = false

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            if (dataType == DataType.HEART_RATE_BPM && availability.toString().contains("UNAVAILABLE")) {
                setStatus(Status.UNAVAILABLE)
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).forEach { point ->
                val bpm = point.value
                if (bpm.isFinite() && bpm in 25.0..240.0) {
                    setStatus(Status.MEASURING)
                    onHeartRate(bpm, System.currentTimeMillis())
                }
            }
        }

        override fun onRegistered() {
            registered = true
            setStatus(Status.MEASURING)
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            registered = false
            setStatus(Status.ERROR)
        }
    }

    fun start() {
        if (status == Status.CHECKING || registered) return
        setStatus(Status.CHECKING)
        val future = measureClient.getCapabilitiesAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { capabilities ->
                        if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesMeasure) {
                            setStatus(Status.UNAVAILABLE)
                        } else {
                            runCatching {
                                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
                            }.onFailure { setStatus(Status.ERROR) }
                        }
                    }
                    .onFailure { setStatus(Status.ERROR) }
            },
            mainExecutor,
        )
    }

    fun stop() {
        if (registered) {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
            registered = false
        }
        setStatus(Status.IDLE)
    }

    fun currentStatus(): Status = status

    private fun setStatus(newStatus: Status) {
        if (status == newStatus) return
        status = newStatus
        onStatusChanged(newStatus)
    }
}
