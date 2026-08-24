// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Chặn observation replay/out-of-order. Xem ADR 0004 quyết định 5 và
 * `docs/GATT_PROFILE.md` mục 8.
 *
 * Hai luật khác bản đầu:
 * - `sequence` so sánh **modular** trên uint32, nên wrap `0xFFFFFFFF -> 0` không làm
 *   gate reject vĩnh viễn.
 * - Reboot chỉ xác định bằng `boot_id` đọc từ Device Info. Uptime giảm là bất thường
 *   và bị bỏ, **không** reset gate — nếu không thì một packet khai uptime thấp là mở
 *   được cửa replay.
 *
 * Callback BLE đến trên binder thread nên mọi truy cập được đồng bộ.
 */
class FaceSequenceGate {

    /** Trạng thái persist được để anti-replay không mất khi app restart giữa phiên. */
    data class State(val bootIdHex: String, val lastSequence: Long, val lastUptimeMs: Long)

    private var bootIdHex: String? = null
    private var lastSequence: Long? = null
    private var lastUptimeMs: Long? = null

    /**
     * Gọi sau mỗi lần đọc Device Info. Trả `true` khi ESP đã reboot (hoặc đây là lần
     * đầu thấy thiết bị), tức caller phải re-anchor thời gian và huỷ baseline calibration.
     */
    @Synchronized
    fun onDeviceInfo(bootIdHex: String): Boolean {
        require(bootIdHex.matches(BOOT_ID_PATTERN)) { "boot_id must be 32 lowercase hex chars" }
        if (this.bootIdHex == bootIdHex) return false
        this.bootIdHex = bootIdHex
        lastSequence = null
        lastUptimeMs = null
        return true
    }

    @Synchronized
    fun accept(observation: FaceObservationV1): Boolean {
        val previousUptime = lastUptimeMs
        if (previousUptime != null && observation.espUptimeMs < previousUptime) return false
        val previousSequence = lastSequence
        if (previousSequence != null) {
            val delta = (observation.sequence - previousSequence) and UINT32_MASK
            if (delta == 0L || delta > FORWARD_WINDOW) return false
        }
        lastSequence = observation.sequence
        lastUptimeMs = observation.espUptimeMs
        return true
    }

    @Synchronized
    fun snapshot(): State? {
        val boot = bootIdHex ?: return null
        val sequence = lastSequence ?: return null
        val uptime = lastUptimeMs ?: return null
        return State(boot, sequence, uptime)
    }

    @Synchronized
    fun restore(state: State) {
        require(state.bootIdHex.matches(BOOT_ID_PATTERN)) { "boot_id must be 32 lowercase hex chars" }
        require(state.lastSequence in 0L..FaceObservationV1.MAX_SEQUENCE) { "invalid lastSequence" }
        require(state.lastUptimeMs in 0L..FaceObservationV1.MAX_UPTIME_MS) { "invalid lastUptimeMs" }
        bootIdHex = state.bootIdHex
        lastSequence = state.lastSequence
        lastUptimeMs = state.lastUptimeMs
    }

    @Synchronized
    fun reset() {
        bootIdHex = null
        lastSequence = null
        lastUptimeMs = null
    }

    private companion object {
        const val UINT32_MASK = 0xFFFF_FFFFL
        const val FORWARD_WINDOW = 0x8000_0000L
        val BOOT_ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}
