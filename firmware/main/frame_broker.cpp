#include "frame_broker.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>

#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

namespace {

constexpr char kTag[] = "focusmate-frame";
constexpr size_t kJpegSlotCount = 3U;
constexpr size_t kJpegSlotCapacity = 128U * 1024U;
constexpr uint16_t kFrameWidth = 320U;
constexpr uint16_t kFrameHeight = 240U;
constexpr uint64_t kOfferPeriodUs = 200000U;
constexpr uint64_t kViewerLeaseUs = 2000000U;
constexpr uint64_t kClientClaimUs = 3000000U;
constexpr uint64_t kRateFreshnessUs = 500000U;

struct JpegSlot {
    uint8_t *data = nullptr;
    size_t size = 0;
    uint32_t sequence = 0;
    focusmate_face_result_t face{};
    uint16_t readers = 0;
    bool writing = false;
    bool latest = false;
};

struct Runtime {
    SemaphoreHandle_t mutex = nullptr;
    std::array<JpegSlot, kJpegSlotCount> slots{};
    int latest = -1;
    uint32_t next_sequence = 1;
    uint64_t last_offer_us = 0;
    uint64_t viewer_deadline_us = 0;
    uint64_t client_deadline_us = 0;
    uint32_t client_id = 0;
    uint64_t total_bytes = 0;
    uint32_t frames_encoded = 0;
    uint32_t encode_drops = 0;
    uint32_t encode_errors = 0;
    uint64_t previous_success_us = 0;
    uint64_t latest_success_us = 0;
};

Runtime runtime;

bool viewer_active(uint64_t current_us)
{
    return current_us <= runtime.viewer_deadline_us;
}

int available_slot()
{
    for (size_t index = 0; index < runtime.slots.size(); ++index) {
        const JpegSlot &slot = runtime.slots[index];
        if (!slot.latest && !slot.writing && slot.readers == 0U) return static_cast<int>(index);
    }
    return -1;
}

bool valid_jpeg(const camera_fb_t *frame)
{
    return frame != nullptr && frame->format == PIXFORMAT_JPEG &&
        frame->width == kFrameWidth && frame->height == kFrameHeight &&
        frame->len >= 4U && frame->len <= kJpegSlotCapacity &&
        frame->buf[0] == 0xffU && frame->buf[1] == 0xd8U &&
        frame->buf[frame->len - 2U] == 0xffU && frame->buf[frame->len - 1U] == 0xd9U;
}

} // namespace

extern "C" bool focusmate_frame_broker_init(void)
{
    runtime.mutex = xSemaphoreCreateMutex();
    if (runtime.mutex == nullptr) return false;
    for (JpegSlot &slot : runtime.slots) {
        slot.data = static_cast<uint8_t *>(heap_caps_malloc(kJpegSlotCapacity,
            MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
        if (slot.data == nullptr) {
            ESP_LOGE(kTag, "cannot allocate JPEG slot");
            return false;
        }
    }
    ESP_LOGI(kTag, "direct camera JPEG broker ready slots=%u capacity=%u",
             static_cast<unsigned>(kJpegSlotCount), static_cast<unsigned>(kJpegSlotCapacity));
    return true;
}

extern "C" void focusmate_frame_broker_offer(camera_fb_t *frame,
                                               const focusmate_face_result_t *face)
{
    if (runtime.mutex == nullptr || face == nullptr) return;
    if (!valid_jpeg(frame)) {
        xSemaphoreTake(runtime.mutex, portMAX_DELAY);
        ++runtime.encode_errors;
        xSemaphoreGive(runtime.mutex);
        return;
    }
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    if (!viewer_active(current_us) || current_us - runtime.last_offer_us < kOfferPeriodUs) {
        xSemaphoreGive(runtime.mutex);
        return;
    }
    const int selected = available_slot();
    if (selected < 0) {
        ++runtime.encode_drops;
        xSemaphoreGive(runtime.mutex);
        return;
    }
    JpegSlot &slot = runtime.slots[static_cast<size_t>(selected)];
    slot.writing = true;
    runtime.last_offer_us = current_us;
    xSemaphoreGive(runtime.mutex);

    std::memcpy(slot.data, frame->buf, frame->len);

    const uint64_t completed_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    slot.writing = false;
    slot.size = frame->len;
    slot.face = *face;
    slot.sequence = runtime.next_sequence++;
    if (runtime.latest >= 0) runtime.slots[static_cast<size_t>(runtime.latest)].latest = false;
    slot.latest = true;
    runtime.latest = selected;
    ++runtime.frames_encoded;
    runtime.total_bytes += slot.size;
    runtime.previous_success_us = runtime.latest_success_us;
    runtime.latest_success_us = completed_us;
    xSemaphoreGive(runtime.mutex);
}

extern "C" bool focusmate_frame_broker_claim(uint32_t client_id)
{
    if (runtime.mutex == nullptr || client_id == 0U) return false;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    const bool lease_was_active = viewer_active(current_us);
    const bool available = runtime.client_id == 0U || current_us > runtime.client_deadline_us ||
        runtime.client_id == client_id;
    if (available) {
        runtime.client_id = client_id;
        runtime.client_deadline_us = current_us + kClientClaimUs;
        runtime.viewer_deadline_us = current_us + kViewerLeaseUs;
        if (!lease_was_active) {
            if (runtime.latest >= 0) runtime.slots[static_cast<size_t>(runtime.latest)].latest = false;
            runtime.latest = -1;
            runtime.previous_success_us = 0U;
            runtime.latest_success_us = 0U;
        }
    }
    xSemaphoreGive(runtime.mutex);
    return available;
}

extern "C" void focusmate_frame_broker_touch(uint32_t client_id)
{
    if (runtime.mutex == nullptr || client_id == 0U) return;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    if (runtime.client_id == client_id) {
        runtime.client_deadline_us = current_us + kClientClaimUs;
        runtime.viewer_deadline_us = current_us + kViewerLeaseUs;
    }
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_frame_broker_release_claim(uint32_t client_id)
{
    if (runtime.mutex == nullptr || client_id == 0U) return;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    if (runtime.client_id == client_id) {
        runtime.client_id = 0U;
        runtime.client_deadline_us = 0U;
        runtime.viewer_deadline_us = 0U;
        if (runtime.latest >= 0) runtime.slots[static_cast<size_t>(runtime.latest)].latest = false;
        runtime.latest = -1;
        runtime.previous_success_us = 0U;
        runtime.latest_success_us = 0U;
    }
    xSemaphoreGive(runtime.mutex);
}

extern "C" bool focusmate_frame_broker_acquire(uint32_t after_sequence, uint32_t timeout_ms,
                                                 focusmate_jpeg_view_t *out)
{
    if (runtime.mutex == nullptr || out == nullptr) return false;
    const TickType_t started = xTaskGetTickCount();
    const TickType_t timeout = pdMS_TO_TICKS(timeout_ms);
    while (xTaskGetTickCount() - started <= timeout) {
        xSemaphoreTake(runtime.mutex, portMAX_DELAY);
        if (runtime.latest >= 0) {
            JpegSlot &slot = runtime.slots[static_cast<size_t>(runtime.latest)];
            if (slot.size > 0U && slot.sequence != after_sequence && !slot.writing) {
                ++slot.readers;
                *out = {slot.data, slot.size, slot.sequence, slot.face, runtime.latest};
                xSemaphoreGive(runtime.mutex);
                return true;
            }
        }
        xSemaphoreGive(runtime.mutex);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    return false;
}

extern "C" void focusmate_frame_broker_release(focusmate_jpeg_view_t *view)
{
    if (runtime.mutex == nullptr || view == nullptr || view->slot < 0 ||
        static_cast<size_t>(view->slot) >= runtime.slots.size()) return;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    JpegSlot &slot = runtime.slots[static_cast<size_t>(view->slot)];
    if (slot.readers > 0U) --slot.readers;
    xSemaphoreGive(runtime.mutex);
    view->slot = -1;
    view->data = nullptr;
    view->size = 0U;
}

extern "C" void focusmate_frame_broker_stats(focusmate_frame_stats_t *out)
{
    if (runtime.mutex == nullptr || out == nullptr) return;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    uint32_t fps_q6 = 0U;
    if (runtime.previous_success_us > 0U && runtime.latest_success_us > runtime.previous_success_us &&
        current_us - runtime.latest_success_us <= kRateFreshnessUs) {
        fps_q6 = std::min<uint32_t>(5000000U, static_cast<uint32_t>(1000000000000ULL /
            (runtime.latest_success_us - runtime.previous_success_us)));
    }
    const uint32_t last_bytes = runtime.latest < 0 ? 0U
        : static_cast<uint32_t>(runtime.slots[static_cast<size_t>(runtime.latest)].size);
    *out = {
        .healthy = runtime.mutex != nullptr && runtime.encode_errors == 0U,
        .client_connected = viewer_active(current_us),
        .frames_encoded = runtime.frames_encoded,
        .encode_drops = runtime.encode_drops,
        .encode_errors = runtime.encode_errors,
        .average_jpeg_bytes = runtime.frames_encoded == 0U ? 0U
            : static_cast<uint32_t>(runtime.total_bytes / runtime.frames_encoded),
        .last_jpeg_bytes = last_bytes,
        .jpeg_fps_q6 = fps_q6,
    };
    xSemaphoreGive(runtime.mutex);
}
