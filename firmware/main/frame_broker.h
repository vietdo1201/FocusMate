#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_camera.h"
#include "face_detector.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t *data;
    size_t size;
    uint32_t sequence;
    focusmate_face_result_t face;
    int slot;
} focusmate_jpeg_view_t;

typedef struct {
    bool healthy;
    bool client_connected;
    uint32_t frames_encoded;
    uint32_t encode_drops;
    uint32_t encode_errors;
    uint32_t average_jpeg_bytes;
    uint32_t last_jpeg_bytes;
    uint32_t jpeg_fps_q6;
} focusmate_frame_stats_t;

bool focusmate_frame_broker_init(void);
void focusmate_frame_broker_offer(camera_fb_t *frame, const focusmate_face_result_t *face);
bool focusmate_frame_broker_claim(uint32_t client_id);
void focusmate_frame_broker_touch(uint32_t client_id);
void focusmate_frame_broker_release_claim(uint32_t client_id);
bool focusmate_frame_broker_acquire(uint32_t after_sequence, uint32_t timeout_ms,
                                    focusmate_jpeg_view_t *out);
void focusmate_frame_broker_release(focusmate_jpeg_view_t *view);
void focusmate_frame_broker_stats(focusmate_frame_stats_t *out);

#ifdef __cplusplus
}
#endif
