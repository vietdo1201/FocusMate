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
// Detector cycles can straddle the nominal 200 ms boundary by a few
// milliseconds. A 200 ms gate therefore discarded every other otherwise
// valid frame on-device (about 2.5 FPS). This guard is intentionally below
// the 5 Hz target so each completed detector frame can become the latest
// frame without allowing an unbounded producer rate.
constexpr uint64_t kOfferPeriodUs = 150000U;
constexpr uint64_t kViewerLeaseUs = 2000000U;
constexpr uint64_t kClientClaimUs = 3000000U;
constexpr uint64_t kRateFreshnessUs = 500000U;
static_assert(sizeof(focusmate_face_meta_v1_t) == FOCUSMATE_FACE_META_V1_SIZE,
              "FaceMetaV1 wire size changed");

struct JpegSlot {
    uint8_t *data = nullptr;
    size_t size = 0;
    uint32_t sequence = 0;
    focusmate_face_result_t face{};
    focusmate_face_meta_v1_t meta{};
    uint16_t readers = 0;
    bool writing = false;
    bool latest = false;
};

struct ConsumerLease {
    uint32_t client_id = 0;
    uint64_t claim_deadline_us = 0;
    uint64_t viewer_deadline_us = 0;
};

struct Runtime {
    SemaphoreHandle_t mutex = nullptr;
    std::array<JpegSlot, kJpegSlotCount> slots{};
    int latest = -1;
    uint32_t next_sequence = 1;
    uint64_t last_offer_us = 0;
    std::array<ConsumerLease, FOCUSMATE_FRAME_CONSUMER_COUNT> consumers{};
    uint64_t total_bytes = 0;
    uint32_t frames_encoded = 0;
    uint32_t encode_drops = 0;
    uint32_t encode_errors = 0;
    uint64_t previous_success_us = 0;
    uint64_t latest_success_us = 0;
};

Runtime runtime;

bool valid_consumer(focusmate_frame_consumer_t consumer)
{
    return consumer >= FOCUSMATE_FRAME_CONSUMER_BROWSER &&
        consumer < FOCUSMATE_FRAME_CONSUMER_COUNT;
}

ConsumerLease &consumer_lease(focusmate_frame_consumer_t consumer)
{
    return runtime.consumers[static_cast<size_t>(consumer)];
}

bool consumer_active(focusmate_frame_consumer_t consumer, uint64_t current_us)
{
    if (!valid_consumer(consumer)) return false;
    const ConsumerLease &lease = consumer_lease(consumer);
    return lease.client_id != 0U && current_us <= lease.viewer_deadline_us;
}

bool viewer_active(uint64_t current_us)
{
    return consumer_active(FOCUSMATE_FRAME_CONSUMER_BROWSER, current_us) ||
        consumer_active(FOCUSMATE_FRAME_CONSUMER_WATCH, current_us);
}

void reset_stream_state()
{
    if (runtime.latest >= 0) runtime.slots[static_cast<size_t>(runtime.latest)].latest = false;
    runtime.latest = -1;
    runtime.previous_success_us = 0U;
    runtime.latest_success_us = 0U;
}

focusmate_face_meta_v1_t make_face_meta(uint32_t frame_sequence,
                                        const focusmate_face_result_t &face)
{
    focusmate_face_meta_v1_t meta = {};
    meta.version = FOCUSMATE_FACE_META_V1_VERSION;
    meta.flags = face.face_detected ? FOCUSMATE_FACE_META_V1_FLAG_FACE_DETECTED : 0U;
    meta.keypoint_count = face.face_detected
        ? std::min<uint8_t>(face.keypoint_count, FOCUSMATE_FACE_KEYPOINT_COUNT) : 0U;
    meta.frame_sequence = frame_sequence;
    meta.detector_sequence = face.inference_count;
    meta.inference_ms = face.inference_ms;
    meta.observed_uptime_ms = face.observed_uptime_ms;
    meta.cx_q6 = face.cx_q6;
    meta.cy_q6 = face.cy_q6;
    meta.width_q6 = face.width_q6;
    meta.height_q6 = face.height_q6;
    meta.confidence_q6 = face.confidence_q6;
    for (uint8_t index = 0U; index < meta.keypoint_count; ++index) {
        meta.keypoints[index] = face.keypoints[index];
    }
    return meta;
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

bool acquire_once(focusmate_frame_consumer_t consumer, uint32_t client_id,
                  uint32_t after_sequence, TickType_t mutex_wait,
                  focusmate_jpeg_view_t *out)
{
    if (!valid_consumer(consumer) || runtime.mutex == nullptr || out == nullptr ||
        xSemaphoreTake(runtime.mutex, mutex_wait) != pdTRUE) {
        return false;
    }
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    const ConsumerLease &lease = consumer_lease(consumer);
    const bool lease_matches = consumer_active(consumer, current_us) &&
        (client_id == 0U || lease.client_id == client_id);
    if (lease_matches && runtime.latest >= 0) {
        JpegSlot &slot = runtime.slots[static_cast<size_t>(runtime.latest)];
        if (slot.size > 0U && slot.sequence != after_sequence && !slot.writing) {
            ++slot.readers;
            out->data = slot.data;
            out->size = slot.size;
            out->sequence = slot.sequence;
            out->face = slot.face;
            out->meta = slot.meta;
            out->slot = runtime.latest;
            xSemaphoreGive(runtime.mutex);
            return true;
        }
    }
    xSemaphoreGive(runtime.mutex);
    return false;
}

bool acquire_with_timeout(focusmate_frame_consumer_t consumer, uint32_t client_id,
                          uint32_t after_sequence, uint32_t timeout_ms,
                          focusmate_jpeg_view_t *out)
{
    const TickType_t started = xTaskGetTickCount();
    const TickType_t timeout = pdMS_TO_TICKS(timeout_ms);
    do {
        if (acquire_once(consumer, client_id, after_sequence, portMAX_DELAY, out)) return true;
        if (timeout_ms == 0U || xTaskGetTickCount() - started >= timeout) break;
        vTaskDelay(pdMS_TO_TICKS(10));
    } while (xTaskGetTickCount() - started <= timeout);
    return false;
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
    slot.meta = make_face_meta(slot.sequence, slot.face);
    if (runtime.latest >= 0) runtime.slots[static_cast<size_t>(runtime.latest)].latest = false;
    slot.latest = true;
    runtime.latest = selected;
    ++runtime.frames_encoded;
    runtime.total_bytes += slot.size;
    runtime.previous_success_us = runtime.latest_success_us;
    runtime.latest_success_us = completed_us;
    xSemaphoreGive(runtime.mutex);
}

extern "C" bool focusmate_frame_broker_claim_consumer(focusmate_frame_consumer_t consumer,
                                                         uint32_t client_id)
{
    if (!valid_consumer(consumer) || runtime.mutex == nullptr || client_id == 0U) return false;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    const bool lease_was_active = viewer_active(current_us);
    ConsumerLease &lease = consumer_lease(consumer);
    const bool available = lease.client_id == 0U || current_us > lease.claim_deadline_us ||
        lease.client_id == client_id;
    if (available) {
        lease.client_id = client_id;
        lease.claim_deadline_us = current_us + kClientClaimUs;
        lease.viewer_deadline_us = current_us + kViewerLeaseUs;
        if (!lease_was_active) reset_stream_state();
    }
    xSemaphoreGive(runtime.mutex);
    return available;
}

extern "C" void focusmate_frame_broker_touch_consumer(focusmate_frame_consumer_t consumer,
                                                         uint32_t client_id)
{
    if (!valid_consumer(consumer) || runtime.mutex == nullptr || client_id == 0U) return;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    ConsumerLease &lease = consumer_lease(consumer);
    if (lease.client_id == client_id) {
        lease.claim_deadline_us = current_us + kClientClaimUs;
        lease.viewer_deadline_us = current_us + kViewerLeaseUs;
    }
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_frame_broker_release_consumer(focusmate_frame_consumer_t consumer,
                                                           uint32_t client_id)
{
    if (!valid_consumer(consumer) || runtime.mutex == nullptr || client_id == 0U) return;
    const uint64_t current_us = static_cast<uint64_t>(esp_timer_get_time());
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    ConsumerLease &lease = consumer_lease(consumer);
    if (lease.client_id == client_id) {
        lease = {};
        if (!viewer_active(current_us)) reset_stream_state();
    }
    xSemaphoreGive(runtime.mutex);
}

extern "C" bool focusmate_frame_broker_acquire_consumer(focusmate_frame_consumer_t consumer,
                                                           uint32_t client_id,
                                                           uint32_t after_sequence,
                                                           uint32_t timeout_ms,
                                                           focusmate_jpeg_view_t *out)
{
    if (client_id == 0U) return false;
    return acquire_with_timeout(consumer, client_id, after_sequence, timeout_ms, out);
}

extern "C" bool focusmate_frame_broker_try_acquire_consumer(focusmate_frame_consumer_t consumer,
                                                               uint32_t client_id,
                                                               uint32_t after_sequence,
                                                               focusmate_jpeg_view_t *out)
{
    if (client_id == 0U) return false;
    return acquire_once(consumer, client_id, after_sequence, 0U, out);
}

extern "C" bool focusmate_frame_broker_claim(uint32_t client_id)
{
    return focusmate_frame_broker_claim_consumer(FOCUSMATE_FRAME_CONSUMER_BROWSER, client_id);
}

extern "C" void focusmate_frame_broker_touch(uint32_t client_id)
{
    focusmate_frame_broker_touch_consumer(FOCUSMATE_FRAME_CONSUMER_BROWSER, client_id);
}

extern "C" void focusmate_frame_broker_release_claim(uint32_t client_id)
{
    focusmate_frame_broker_release_consumer(FOCUSMATE_FRAME_CONSUMER_BROWSER, client_id);
}

extern "C" bool focusmate_frame_broker_acquire(uint32_t after_sequence, uint32_t timeout_ms,
                                                 focusmate_jpeg_view_t *out)
{
    return acquire_with_timeout(FOCUSMATE_FRAME_CONSUMER_BROWSER, 0U,
                                after_sequence, timeout_ms, out);
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
    view->sequence = 0U;
    view->face = {};
    view->meta = {};
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
    const bool browser_connected = consumer_active(FOCUSMATE_FRAME_CONSUMER_BROWSER, current_us);
    const bool watch_connected = consumer_active(FOCUSMATE_FRAME_CONSUMER_WATCH, current_us);
    *out = {
        .healthy = runtime.mutex != nullptr && runtime.encode_errors == 0U,
        .client_connected = browser_connected || watch_connected,
        .frames_encoded = runtime.frames_encoded,
        .encode_drops = runtime.encode_drops,
        .encode_errors = runtime.encode_errors,
        .average_jpeg_bytes = runtime.frames_encoded == 0U ? 0U
            : static_cast<uint32_t>(runtime.total_bytes / runtime.frames_encoded),
        .last_jpeg_bytes = last_bytes,
        .jpeg_fps_q6 = fps_q6,
        .browser_connected = browser_connected,
        .watch_connected = watch_connected,
    };
    xSemaphoreGive(runtime.mutex);
}
