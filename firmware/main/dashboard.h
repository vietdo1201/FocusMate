// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool focusmate_dashboard_start(void);

/** Snapshot used by the encrypted FrameAccessInfoV1 GATT characteristic. */
bool focusmate_dashboard_frame_access_snapshot(uint8_t ipv4[4], uint16_t *port,
                                               uint8_t token[16], uint8_t *flags);

#define FOCUSMATE_YAWN_BLE_SESSION_BYTES 16U
#define FOCUSMATE_YAWN_BLE_MAX_RECENT_EVENTS 64U

typedef struct {
    bool active;
    uint8_t session[FOCUSMATE_YAWN_BLE_SESSION_BYTES];
    uint32_t revision;
    uint32_t total;
    uint8_t window;
} focusmate_yawn_ble_state_t;

bool focusmate_dashboard_yawn_ble_resume(
    const uint8_t session[FOCUSMATE_YAWN_BLE_SESSION_BYTES], uint32_t checkpoint_total,
    const uint16_t *recent_event_ages_seconds, uint8_t recent_event_count,
    focusmate_yawn_ble_state_t *state);
bool focusmate_dashboard_yawn_ble_event(
    const uint8_t session[FOCUSMATE_YAWN_BLE_SESSION_BYTES], uint32_t client,
    uint32_t event_id, uint32_t frame_sequence, uint64_t observed_uptime_ms,
    focusmate_yawn_ble_state_t *state);
bool focusmate_dashboard_yawn_ble_state(focusmate_yawn_ble_state_t *state);
bool focusmate_dashboard_yawn_ble_end(
    const uint8_t session[FOCUSMATE_YAWN_BLE_SESSION_BYTES]);

#ifdef __cplusplus
}
#endif
