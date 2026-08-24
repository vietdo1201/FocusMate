// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.os.PowerManager

class WatchThermalMonitor(
    context: Context,
    private val onState: (PostureThermalState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val listener = PowerManager.OnThermalStatusChangedListener { status -> onState(mapThermalStatus(status)) }
    private var running = false

    fun start() {
        if (running) return
        running = true
        val manager = powerManager
        if (manager == null) {
            onState(PostureThermalState.UNKNOWN)
            return
        }
        runCatching {
            onState(mapThermalStatus(manager.currentThermalStatus))
            manager.addThermalStatusListener(appContext.mainExecutor, listener)
        }.onFailure { onState(PostureThermalState.UNKNOWN) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { powerManager?.removeThermalStatusListener(listener) }
    }
}

internal fun mapThermalStatus(status: Int): PostureThermalState = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> PostureThermalState.NOMINAL
    PowerManager.THERMAL_STATUS_LIGHT -> PostureThermalState.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> PostureThermalState.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> PostureThermalState.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> PostureThermalState.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> PostureThermalState.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> PostureThermalState.SHUTDOWN
    else -> PostureThermalState.UNKNOWN
}

internal fun PostureThermalState.allowsLocalPose(): Boolean =
    this == PostureThermalState.NOMINAL || this == PostureThermalState.LIGHT ||
        this == PostureThermalState.MODERATE || this == PostureThermalState.UNKNOWN

internal fun PostureThermalState.localPosePollDelayMs(): Long =
    if (this == PostureThermalState.MODERATE) 500L else 200L
