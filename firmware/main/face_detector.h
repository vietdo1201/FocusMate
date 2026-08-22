#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FOCUSMATE_FACE_KEYPOINT_COUNT 5U

typedef enum {
    FOCUSMATE_FACE_KEYPOINT_LEFT_EYE = 0,
    FOCUSMATE_FACE_KEYPOINT_LEFT_MOUTH = 1,
    FOCUSMATE_FACE_KEYPOINT_NOSE = 2,
    FOCUSMATE_FACE_KEYPOINT_RIGHT_EYE = 3,
    FOCUSMATE_FACE_KEYPOINT_RIGHT_MOUTH = 4,
} focusmate_face_keypoint_index_t;

typedef struct {
    uint32_t x_q6;
    uint32_t y_q6;
} focusmate_face_keypoint_t;

typedef struct {
    bool face_detected;
    uint32_t cx_q6;
    uint32_t cy_q6;
    uint32_t width_q6;
    uint32_t height_q6;
    uint32_t confidence_q6;
    uint8_t keypoint_count;
    focusmate_face_keypoint_t keypoints[FOCUSMATE_FACE_KEYPOINT_COUNT];
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
