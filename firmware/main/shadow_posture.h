#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "face_detector.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    FOCUSMATE_POSTURE_NORMAL = 0,
    FOCUSMATE_POSTURE_HEAD_DOWN,
    FOCUSMATE_POSTURE_LEAN_LEFT,
    FOCUSMATE_POSTURE_LEAN_RIGHT,
    FOCUSMATE_POSTURE_TOO_CLOSE,
    FOCUSMATE_POSTURE_SLUMPED,
    FOCUSMATE_POSTURE_FACE_MISSING,
    FOCUSMATE_POSTURE_UNKNOWN,
} focusmate_posture_state_t;

typedef struct {
    bool calibrated;
    bool calibration_active;
    uint8_t calibration_progress;
    const char *calibration_reason;
    focusmate_posture_state_t raw_state;
    focusmate_posture_state_t state;
    uint32_t raw_confidence_q6;
    uint32_t confidence_q6;
    uint64_t stable_ms;
    int32_t dx_q6;
    int32_t dy_q6;
    uint32_t area_ratio_q6;
    uint32_t baseline_cx_q6;
    uint32_t baseline_cy_q6;
    uint32_t baseline_area_q6;
} focusmate_posture_snapshot_t;

typedef struct {
    uint32_t calibration_min_confidence_q6;
    uint32_t live_min_confidence_q6;
    int32_t lean_delta_q6;
    int32_t head_down_delta_q6;
    int32_t slumped_delta_q6;
    uint32_t too_close_ratio_q6;
    uint64_t slumped_minimum_ms;
    uint8_t stable_samples;
} focusmate_posture_thresholds_t;

bool focusmate_shadow_posture_init(void);
void focusmate_shadow_posture_observe(const focusmate_face_result_t *result);
void focusmate_shadow_posture_start_calibration(void);
void focusmate_shadow_posture_reset(void);
void focusmate_shadow_posture_snapshot(focusmate_posture_snapshot_t *out);
void focusmate_shadow_posture_thresholds(focusmate_posture_thresholds_t *out);
const char *focusmate_posture_state_name(focusmate_posture_state_t state);

#ifdef __cplusplus
}
#endif
