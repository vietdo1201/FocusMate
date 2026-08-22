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

#ifdef __cplusplus
}
#endif
