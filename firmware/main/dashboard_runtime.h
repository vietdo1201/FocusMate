// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool connected;
    bool subscribed;
    bool streaming;
    uint16_t mtu;
    uint8_t rate_dhz;
    uint32_t observations;
    uint32_t notification_attempts;
    uint32_t notification_failures;
} focusmate_ble_snapshot_t;

void focusmate_ble_snapshot(focusmate_ble_snapshot_t *out);

#ifdef __cplusplus
}
#endif

