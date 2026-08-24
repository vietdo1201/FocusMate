// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_camera.h"
#include "face_detector.h"

#ifdef __cplusplus
extern "C" {
#endif

#define FOCUSMATE_FACE_META_V1_VERSION 1U
#define FOCUSMATE_FACE_META_V1_FLAG_FACE_DETECTED 0x01U
#define FOCUSMATE_FACE_META_V1_SIZE 84U

/** Fixed-size little-endian metadata paired atomically with one JPEG frame. */
typedef struct __attribute__((packed)) {
    uint8_t version;
    uint8_t flags;
    uint8_t keypoint_count;
    uint8_t reserved;
    uint32_t frame_sequence;
    uint32_t detector_sequence;
    uint32_t inference_ms;
    uint64_t observed_uptime_ms;
    uint32_t cx_q6;
    uint32_t cy_q6;
    uint32_t width_q6;
    uint32_t height_q6;
    uint32_t confidence_q6;
    focusmate_face_keypoint_t keypoints[FOCUSMATE_FACE_KEYPOINT_COUNT];
} focusmate_face_meta_v1_t;

typedef enum {
    FOCUSMATE_FRAME_CONSUMER_BROWSER = 0,
    FOCUSMATE_FRAME_CONSUMER_WATCH = 1,
    FOCUSMATE_FRAME_CONSUMER_COUNT = 2,
} focusmate_frame_consumer_t;

typedef struct {
    const uint8_t *data;
    size_t size;
    uint32_t sequence;
    focusmate_face_result_t face;
    focusmate_face_meta_v1_t meta;
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
    bool browser_connected;
    bool watch_connected;
} focusmate_frame_stats_t;

bool focusmate_frame_broker_init(void);
void focusmate_frame_broker_offer(camera_fb_t *frame, const focusmate_face_result_t *face);

/** One independent lease is allowed for each role. */
bool focusmate_frame_broker_claim_consumer(focusmate_frame_consumer_t consumer,
                                           uint32_t client_id);
void focusmate_frame_broker_touch_consumer(focusmate_frame_consumer_t consumer,
                                           uint32_t client_id);
void focusmate_frame_broker_release_consumer(focusmate_frame_consumer_t consumer,
                                             uint32_t client_id);
bool focusmate_frame_broker_acquire_consumer(focusmate_frame_consumer_t consumer,
                                             uint32_t client_id,
                                             uint32_t after_sequence,
                                             uint32_t timeout_ms,
                                             focusmate_jpeg_view_t *out);
/** Single mutex probe: safe for a BLE task that must never wait for a frame. */
bool focusmate_frame_broker_try_acquire_consumer(focusmate_frame_consumer_t consumer,
                                                 uint32_t client_id,
                                                 uint32_t after_sequence,
                                                 focusmate_jpeg_view_t *out);

/** Legacy browser wrappers. */
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
