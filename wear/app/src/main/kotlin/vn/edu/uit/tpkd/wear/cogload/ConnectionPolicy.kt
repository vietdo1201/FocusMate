// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

data class WatchConnectionPowerPolicy(
    val rateDhz: Int,
    val framePollDelayMs: Long?,
    val faceInferenceIntervalMs: Long?,
    val interactivePriority: Boolean,
)

internal fun watchConnectionPowerPolicy(
    interactive: Boolean,
    thermal: PostureThermalState,
): WatchConnectionPowerPolicy = when {
    !thermal.allowsLocalPose() -> WatchConnectionPowerPolicy(10, null, null, false)
    thermal == PostureThermalState.MODERATE || !interactive ->
        WatchConnectionPowerPolicy(20, 500L, 500L, false)
    else -> WatchConnectionPowerPolicy(50, 200L, 400L, true)
}

data class BleWatchdogThresholds(val restartMs: Long, val reconnectMs: Long)

internal fun bleWatchdogThresholds(rateDhz: Int): BleWatchdogThresholds {
    val periodMs = 10_000L / rateDhz.coerceIn(10, 100)
    return BleWatchdogThresholds(
        restartMs = maxOf(3_000L, periodMs * 6L),
        reconnectMs = maxOf(8_000L, periodMs * 16L),
    )
}

internal fun reconnectBaseDelayMs(attempt: Int): Long =
    if (attempt >= 5) 30_000L else 1_000L shl attempt.coerceAtLeast(0)
