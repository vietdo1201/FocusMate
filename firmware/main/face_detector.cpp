#include "face_detector.h"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <list>

#include "esp_camera.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "human_face_detect.hpp"
#include "jpeg_decoder.h"
#include "frame_broker.h"
#include "shadow_posture.h"

namespace {

constexpr uint32_t kScale = 1000000U;
constexpr uint64_t kMaximumResultAgeMs = 1000U;
constexpr uint32_t kCameraWidth = 320U;
constexpr uint32_t kCameraHeight = 240U;
// Preserve QVGA detail, then center-crop to the square aspect ratio expected by
// ESPDet. Feeding 4:3 directly stretched faces horizontally during the model's
// resize; the square crop keeps facial geometry intact and still covers the
// normal seated region. Bboxes are mapped back to the 320x240 camera space.
constexpr uint32_t kDetectorWidth = 240U;
constexpr uint32_t kDetectorHeight = 240U;
constexpr uint32_t kDetectorLeft = (kCameraWidth - kDetectorWidth) / 2U;
constexpr float kDetectorScoreThreshold = 0.35F;
const char *kTag = "focusmate-detector";

portMUX_TYPE result_lock = portMUX_INITIALIZER_UNLOCKED;
focusmate_face_result_t latest_result = {};
bool has_result = false;
HumanFaceDetect *detector = nullptr;
uint8_t *decoded_rgb888 = nullptr;
uint8_t *detector_rgb888 = nullptr;
alignas(4) uint8_t jpeg_work_buffer[3100] = {};

bool decode_detector_frame(const camera_fb_t *frame)
{
    esp_jpeg_image_cfg_t config = {};
    config.indata = frame->buf;
    config.indata_size = static_cast<uint32_t>(frame->len);
    config.outbuf = decoded_rgb888;
    config.outbuf_size = kCameraWidth * kCameraHeight * 3U;
    config.out_format = JPEG_IMAGE_FORMAT_RGB888;
    config.out_scale = JPEG_IMAGE_SCALE_0;
    config.flags.swap_color_bytes = 0U;
    config.advanced.working_buffer = jpeg_work_buffer;
    config.advanced.working_buffer_size = sizeof(jpeg_work_buffer);
    esp_jpeg_image_output_t output = {};
    if (esp_jpeg_decode(&config, &output) != ESP_OK ||
        output.width != kCameraWidth || output.height != kCameraHeight ||
        output.output_len != kCameraWidth * kCameraHeight * 3U || detector_rgb888 == nullptr) {
        return false;
    }
    constexpr size_t row_bytes = kDetectorWidth * 3U;
    for (uint32_t row = 0; row < kDetectorHeight; ++row) {
        std::memcpy(detector_rgb888 + row * row_bytes,
                    decoded_rgb888 + (row * kCameraWidth + kDetectorLeft) * 3U,
                    row_bytes);
    }
    return true;
}

uint32_t scaled_ratio_half_up(uint64_t numerator, uint64_t denominator)
{
    return static_cast<uint32_t>((numerator * kScale + denominator / 2U) / denominator);
}

uint32_t confidence_to_q6(float score)
{
    if (score <= 0.0F) return 0U;
    if (score >= 1.0F) return kScale;
    return static_cast<uint32_t>(score * static_cast<float>(kScale) + 0.5F);
}

void detector_math_self_test()
{
    assert(scaled_ratio_half_up(1U, 320U) == 3125U);
    assert(scaled_ratio_half_up(160U, 320U) == 500000U);
    assert(scaled_ratio_half_up(216U, 480U) == 450000U);
    assert(confidence_to_q6(0.0F) == 0U);
    assert(confidence_to_q6(0.5F) == 500000U);
    assert(confidence_to_q6(1.0F) == kScale);
}

bool better_result(const dl::detect::result_t &candidate, const dl::detect::result_t &current)
{
    if (candidate.score != current.score) return candidate.score > current.score;
    const int candidate_area = candidate.box_area();
    const int current_area = current.box_area();
    if (candidate_area != current_area) return candidate_area > current_area;
    return candidate.box < current.box;
}

bool run_one_inference(focusmate_face_result_t *out)
{
    camera_fb_t *frame = esp_camera_fb_get();
    if (frame == nullptr || frame->format != PIXFORMAT_JPEG ||
        frame->width != kCameraWidth || frame->height != kCameraHeight ||
        frame->len < 4U || frame->buf[0] != 0xffU || frame->buf[1] != 0xd8U ||
        frame->buf[frame->len - 2U] != 0xffU || frame->buf[frame->len - 1U] != 0xd9U ||
        decoded_rgb888 == nullptr || detector_rgb888 == nullptr) {
        if (frame != nullptr) esp_camera_fb_return(frame);
        ESP_LOGE(kTag, "invalid camera framebuffer for detector");
        return false;
    }

    const int64_t started_us = esp_timer_get_time();
    if (!decode_detector_frame(frame)) {
        esp_camera_fb_return(frame);
        ESP_LOGE(kTag, "cannot decode OV2640 JPEG for detector");
        return false;
    }
    const dl::image::img_t image = {
        .data = detector_rgb888,
        .width = static_cast<uint16_t>(kDetectorWidth),
        .height = static_cast<uint16_t>(kDetectorHeight),
        .pix_type = dl::image::DL_IMAGE_PIX_TYPE_RGB888,
    };
    std::list<dl::detect::result_t> &detections = detector->run(image);
    const int64_t completed_us = esp_timer_get_time();

    focusmate_face_result_t result = {};
    result.observed_uptime_ms = static_cast<uint64_t>(completed_us / 1000);
    result.inference_ms = static_cast<uint32_t>((completed_us - started_us + 500) / 1000);

    const dl::detect::result_t *best = nullptr;
    for (dl::detect::result_t &candidate : detections) {
        if (candidate.box.size() != 4U) continue;
        candidate.limit_box(static_cast<int>(kDetectorWidth), static_cast<int>(kDetectorHeight));
        if (candidate.box[2] < candidate.box[0] || candidate.box[3] < candidate.box[1]) continue;
        if (best == nullptr || better_result(candidate, *best)) best = &candidate;
    }
    if (best != nullptr) {
        const uint32_t left = static_cast<uint32_t>(best->box[0]) + kDetectorLeft;
        const uint32_t top = static_cast<uint32_t>(best->box[1]);
        const uint32_t width = static_cast<uint32_t>(best->box[2] - best->box[0] + 1);
        const uint32_t height = static_cast<uint32_t>(best->box[3] - best->box[1] + 1);
        result.face_detected = true;
        result.width_q6 = scaled_ratio_half_up(width, kCameraWidth);
        result.height_q6 = scaled_ratio_half_up(height, kCameraHeight);
        result.cx_q6 = scaled_ratio_half_up(2U * left + width, 2U * kCameraWidth);
        result.cy_q6 = scaled_ratio_half_up(2U * top + height, 2U * kCameraHeight);
        result.confidence_q6 = confidence_to_q6(best->score);
    }
    focusmate_shadow_posture_observe(&result);
    focusmate_frame_broker_offer(frame, &result);
    esp_camera_fb_return(frame);
    *out = result;
    return true;
}

void publish(const focusmate_face_result_t &result)
{
    portENTER_CRITICAL(&result_lock);
    focusmate_face_result_t next = result;
    next.inference_count = latest_result.inference_count + 1U;
    latest_result = next;
    has_result = true;
    portEXIT_CRITICAL(&result_lock);
}

void detector_task(void *)
{
    uint64_t total_inference_ms = 0U;
    uint32_t successful = 0U;
    uint32_t failures = 0U;
    uint32_t positive_detections = 0U;
    while (true) {
        focusmate_face_result_t result = {};
        if (run_one_inference(&result)) {
            publish(result);
            total_inference_ms += result.inference_ms;
            ++successful;
            if (result.face_detected) {
                ++positive_detections;
                if (positive_detections == 1U || positive_detections % 5U == 0U) {
                    ESP_LOGI(kTag,
                             "positive=%lu bbox_q6=%lu,%lu,%lu,%lu confidence_q6=%lu latency_ms=%lu",
                             static_cast<unsigned long>(positive_detections),
                             static_cast<unsigned long>(result.cx_q6),
                             static_cast<unsigned long>(result.cy_q6),
                             static_cast<unsigned long>(result.width_q6),
                             static_cast<unsigned long>(result.height_q6),
                             static_cast<unsigned long>(result.confidence_q6),
                             static_cast<unsigned long>(result.inference_ms));
                }
            }
            if (successful % 25U == 0U) {
                ESP_LOGI(kTag,
                         "benchmark inferences=%lu failures=%lu avg_ms=%.1f last_ms=%lu face=%d confidence_q6=%lu",
                         static_cast<unsigned long>(successful), static_cast<unsigned long>(failures),
                         static_cast<double>(total_inference_ms) / successful,
                         static_cast<unsigned long>(result.inference_ms), result.face_detected,
                         static_cast<unsigned long>(result.confidence_q6));
            }
        } else {
            ++failures;
            vTaskDelay(pdMS_TO_TICKS(100));
        }
        taskYIELD();
    }
}

} // namespace

extern "C" bool focusmate_face_detector_start(void)
{
    if (detector != nullptr) return has_result;
    detector_math_self_test();
    ESP_LOGI(kTag, "integer geometry self-test passed");
    decoded_rgb888 = static_cast<uint8_t *>(heap_caps_malloc(
        kCameraWidth * kCameraHeight * 3U, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    detector_rgb888 = static_cast<uint8_t *>(heap_caps_malloc(
        kDetectorWidth * kDetectorHeight * 3U, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    detector = new HumanFaceDetect(HumanFaceDetect::ESPDET_PICO_224_224_FACE, false);
    if (detector != nullptr) {
        detector->set_score_thr(kDetectorScoreThreshold, 0);
    }
    focusmate_face_result_t first = {};
    if (decoded_rgb888 == nullptr || detector_rgb888 == nullptr || detector == nullptr ||
        !run_one_inference(&first)) {
        ESP_LOGE(kTag, "detector failed initial inference; capability remains disabled");
        return false;
    }
    publish(first);
    ESP_LOGI(kTag, "initial inference passed latency_ms=%lu face=%d confidence_q6=%lu",
             static_cast<unsigned long>(first.inference_ms), first.face_detected,
             static_cast<unsigned long>(first.confidence_q6));
    const BaseType_t created = xTaskCreate(detector_task, "face-detector", 8192U, nullptr, 4U, nullptr);
    if (created != pdPASS) {
        ESP_LOGE(kTag, "failed to create detector task");
        portENTER_CRITICAL(&result_lock);
        has_result = false;
        portEXIT_CRITICAL(&result_lock);
        return false;
    }
    return true;
}

extern "C" bool focusmate_face_detector_latest(focusmate_face_result_t *out)
{
    if (out == nullptr) return false;
    bool available;
    portENTER_CRITICAL(&result_lock);
    available = has_result;
    if (available) *out = latest_result;
    portEXIT_CRITICAL(&result_lock);
    if (!available) return false;
    const uint64_t now_ms = static_cast<uint64_t>(esp_timer_get_time() / 1000);
    return now_ms >= out->observed_uptime_ms && now_ms - out->observed_uptime_ms <= kMaximumResultAgeMs;
}
