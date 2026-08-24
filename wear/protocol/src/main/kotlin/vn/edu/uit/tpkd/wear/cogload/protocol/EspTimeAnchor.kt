// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Ánh xạ uptime của ESP sang cả wall clock và monotonic clock của Watch.
 *
 * Anchor lấy lúc đọc Device Info và chỉ được đặt lại khi reconnect hoặc khi `boot_id`
 * đổi; **cấm** re-anchor giữa dòng notification vì sẽ làm timeline nhảy.
 */
data class EspTimeAnchor(
    val wallClockMs: Long,
    val monotonicMs: Long,
    val uptimeMs: Long,
) {
    init {
        require(wallClockMs >= 0L) { "wallClockMs must be non-negative" }
        require(monotonicMs >= 0L) { "monotonicMs must be non-negative" }
        require(uptimeMs in 0L..FaceObservationV1.MAX_UPTIME_MS) { "invalid anchor uptime" }
    }

    /** Chỉ dùng cho hiển thị/persistence; không dùng để quyết định freshness. */
    fun observedAtWallClockMs(espUptimeMs: Long): Long = wallClockMs + (espUptimeMs - uptimeMs)

    fun observedAtMonotonicMs(espUptimeMs: Long): Long = monotonicMs + (espUptimeMs - uptimeMs)

    fun ageMs(espUptimeMs: Long, nowMonotonicMs: Long): Long =
        nowMonotonicMs - observedAtMonotonicMs(espUptimeMs)

    fun isStale(espUptimeMs: Long, nowMonotonicMs: Long): Boolean =
        ageMs(espUptimeMs, nowMonotonicMs) > GattProfile.STALE_THRESHOLD_MS

    companion object {
        fun from(deviceInfo: EspDeviceInfo, wallClockMs: Long, monotonicMs: Long): EspTimeAnchor =
            EspTimeAnchor(wallClockMs, monotonicMs, deviceInfo.espUptimeMs)
    }
}
