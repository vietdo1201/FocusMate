#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool face_detected;
    uint32_t cx_q6;
    uint32_t cy_q6;
    uint32_t width_q6;
    uint32_t height_q6;
    uint32_t confidence_q6;
    uint64_t observed_uptime_ms;
    uint32_t inference_ms;
    uint32_t inference_count;
} focusmate_face_result_t;

/** Loads the pinned detector and returns true only after one real inference. */
bool focusmate_face_detector_start(void);

/** Copies a recent inference result; false means unavailable or stale. */
bool focusmate_face_detector_latest(focusmate_face_result_t *out);

#ifdef __cplusplus
}
#endif
