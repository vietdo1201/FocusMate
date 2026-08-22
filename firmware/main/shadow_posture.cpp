#include "shadow_posture.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>

#include "esp_log.h"
#include "esp_timer.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

namespace {

constexpr char kTag[] = "focusmate-posture";
constexpr char kNvsNamespace[] = "focusmate_web";
constexpr uint32_t kScale = 1000000U;
// Revision 2 changes horizontal geometry from camera/image direction to the
// seated person's left/right and requires a freshly collected baseline.
constexpr uint8_t kBaselineRevision = 2U;
constexpr uint32_t kProfileFingerprint = 0x4A032182U; // JPEG QVGA, 180-degree correction, subject-relative X v2.
// Calibration remains deliberately strict. Live tracking can use the lower
// confidence bbox after a high-confidence baseline exists: the real OV2640 +
// ESPDet path produced correct off-axis boxes around 0.59, which were
// previously discarded as UNKNOWN before geometry was evaluated.
constexpr uint32_t kCalibrationMinimumConfidence = 700000U;
constexpr uint32_t kLiveMinimumConfidence = 500000U;
constexpr int32_t kLeanDelta = 150000;
constexpr int32_t kHeadDownDelta = 120000;
constexpr int32_t kSlumpedDelta = 180000;
constexpr uint32_t kTooCloseRatio = 1600000U;
constexpr uint64_t kSlumpedMinimumMs = 5000U;
constexpr uint64_t kNoSlumpStart = UINT64_MAX;
constexpr uint64_t kSamplePeriodMs = 200U;
// The accurate ESPDet Pico model runs at about 2.9 inferences/s on the real
// board and can intermittently miss an otherwise visible face. Calibrate only
// from post-click samples, allow short detector gaps, and reject a face clipped
// by the detector crop because that biases the baseline center.
constexpr uint64_t kCalibrationSettleMs = 1000U;
constexpr uint64_t kCalibrationCollectionMs = 15000U;
constexpr uint64_t kCalibrationWindowMs = kCalibrationSettleMs + kCalibrationCollectionMs;
constexpr uint64_t kCalibrationMaximumGapMs = 1500U;
constexpr size_t kCalibrationSamples = 20U;
constexpr uint32_t kCalibrationCropLeft = 145000U;
constexpr uint32_t kCalibrationCropRight = 855000U;
constexpr uint32_t kCalibrationCropTop = 20000U;
constexpr uint32_t kCalibrationCropBottom = 980000U;
constexpr uint32_t kMaximumCenterSpread = 40000U;
constexpr uint32_t kMaximumAreaSpreadRatio = 200000U;
constexpr uint64_t kStaleMs = 3000U;
constexpr uint8_t kStableSamples = 3U;

struct Runtime {
    SemaphoreHandle_t mutex = nullptr;
    bool calibrated = false;
    bool calibration_active = false;
    uint64_t calibration_collect_after_ms = 0;
    uint64_t calibration_deadline_ms = 0;
    uint64_t last_calibration_sample_ms = 0;
    uint64_t last_sample_ms = 0;
    const char *calibration_reason = "not_calibrated";
    std::array<uint32_t, kCalibrationSamples> xs{};
    std::array<uint32_t, kCalibrationSamples> ys{};
    std::array<uint32_t, kCalibrationSamples> areas{};
    size_t sample_count = 0;
    uint32_t baseline_cx = 0;
    uint32_t baseline_cy = 0;
    uint32_t baseline_area = 0;
    focusmate_posture_state_t raw_state = FOCUSMATE_POSTURE_UNKNOWN;
    focusmate_posture_state_t stable_state = FOCUSMATE_POSTURE_UNKNOWN;
    focusmate_posture_state_t candidate_state = FOCUSMATE_POSTURE_UNKNOWN;
    uint8_t candidate_count = 0;
    uint32_t raw_confidence = 0;
    uint32_t stable_confidence = 0;
    int32_t dx = 0;
    int32_t dy = 0;
    uint32_t area_ratio = 0;
    uint64_t stable_since_ms = 0;
    uint64_t slumped_since_ms = kNoSlumpStart;
    uint64_t last_observed_ms = 0;
};

Runtime runtime;

void reset_live_temporal_state(uint64_t at_ms)
{
    runtime.slumped_since_ms = kNoSlumpStart;
    runtime.raw_state = FOCUSMATE_POSTURE_UNKNOWN;
    runtime.stable_state = FOCUSMATE_POSTURE_UNKNOWN;
    runtime.candidate_state = FOCUSMATE_POSTURE_UNKNOWN;
    runtime.candidate_count = 0;
    runtime.raw_confidence = 0;
    runtime.stable_confidence = 0;
    runtime.stable_since_ms = at_ms;
}

uint64_t now_ms()
{
    return static_cast<uint64_t>(esp_timer_get_time() / 1000);
}

uint32_t area_q6(const focusmate_face_result_t &result)
{
    return static_cast<uint32_t>((static_cast<uint64_t>(result.width_q6) * result.height_q6 + kScale / 2U) / kScale);
}

bool calibration_bbox_fully_visible(const focusmate_face_result_t &result)
{
    const int64_t left2 = 2LL * result.cx_q6 - result.width_q6;
    const int64_t right2 = 2LL * result.cx_q6 + result.width_q6;
    const int64_t top2 = 2LL * result.cy_q6 - result.height_q6;
    const int64_t bottom2 = 2LL * result.cy_q6 + result.height_q6;
    return left2 >= 2LL * kCalibrationCropLeft &&
        right2 <= 2LL * kCalibrationCropRight &&
        top2 >= 2LL * kCalibrationCropTop &&
        bottom2 <= 2LL * kCalibrationCropBottom;
}

template <size_t N>
uint32_t median(std::array<uint32_t, N> values)
{
    std::sort(values.begin(), values.end());
    if constexpr ((N % 2U) == 0U) {
        return static_cast<uint32_t>((static_cast<uint64_t>(values[N / 2U - 1U]) + values[N / 2U]) / 2U);
    }
    return values[N / 2U];
}

uint32_t ratio_q6(uint32_t numerator, uint32_t denominator)
{
    if (denominator == 0U) return 0U;
    const uint64_t value = (static_cast<uint64_t>(numerator) * kScale + denominator / 2U) / denominator;
    return static_cast<uint32_t>(std::min<uint64_t>(value, UINT32_MAX));
}

uint32_t geometry_confidence(focusmate_posture_state_t state, int32_t dx, int32_t dy,
                             uint32_t area_ratio)
{
    uint64_t value = kScale;
    switch (state) {
        case FOCUSMATE_POSTURE_TOO_CLOSE:
            value = area_ratio <= kScale ? 0U
                : static_cast<uint64_t>(area_ratio - kScale) * kScale / (kTooCloseRatio - kScale);
            break;
        case FOCUSMATE_POSTURE_HEAD_DOWN:
        case FOCUSMATE_POSTURE_SLUMPED:
            value = dy <= 0 ? 0U : static_cast<uint64_t>(dy) * kScale / kHeadDownDelta;
            break;
        case FOCUSMATE_POSTURE_LEAN_LEFT:
        case FOCUSMATE_POSTURE_LEAN_RIGHT:
            value = static_cast<uint64_t>(dx < 0 ? -static_cast<int64_t>(dx) : dx) * kScale / kLeanDelta;
            break;
        default:
            break;
    }
    return static_cast<uint32_t>(std::min<uint64_t>(value, kScale));
}

focusmate_posture_state_t classify_geometry(int32_t dx, int32_t dy, uint32_t area_ratio,
                                             uint64_t at_ms, uint64_t &slumped_since_ms)
{
    const uint32_t lateral = static_cast<uint32_t>(dx < 0 ? -static_cast<int64_t>(dx) : dx);
    const bool lean_candidate = lateral >= static_cast<uint32_t>(kLeanDelta);
    const bool head_candidate = dy >= kHeadDownDelta;
    // A natural side lean often lowers the face slightly. Use the dominant
    // normalized axis instead of allowing any vertical threshold crossing to
    // hide a much stronger horizontal lean.
    const bool lean_dominant = lean_candidate && (!head_candidate ||
        static_cast<uint64_t>(lateral) * kHeadDownDelta >=
            static_cast<uint64_t>(dy) * kLeanDelta);
    focusmate_posture_state_t state;
    if (area_ratio >= kTooCloseRatio) {
        slumped_since_ms = kNoSlumpStart;
        state = FOCUSMATE_POSTURE_TOO_CLOSE;
    } else if (lean_dominant) {
        slumped_since_ms = kNoSlumpStart;
        state = dx < 0 ? FOCUSMATE_POSTURE_LEAN_LEFT : FOCUSMATE_POSTURE_LEAN_RIGHT;
    } else if (dy >= kSlumpedDelta) {
        if (slumped_since_ms == kNoSlumpStart) slumped_since_ms = at_ms;
        state = at_ms - slumped_since_ms >= kSlumpedMinimumMs
            ? FOCUSMATE_POSTURE_SLUMPED : FOCUSMATE_POSTURE_HEAD_DOWN;
    } else if (dy >= kHeadDownDelta) {
        slumped_since_ms = kNoSlumpStart;
        state = FOCUSMATE_POSTURE_HEAD_DOWN;
    } else if (dx <= -kLeanDelta) {
        slumped_since_ms = kNoSlumpStart;
        state = FOCUSMATE_POSTURE_LEAN_LEFT;
    } else if (dx >= kLeanDelta) {
        slumped_since_ms = kNoSlumpStart;
        state = FOCUSMATE_POSTURE_LEAN_RIGHT;
    } else {
        slumped_since_ms = kNoSlumpStart;
        state = FOCUSMATE_POSTURE_NORMAL;
    }
    return state;
}

bool advance_stable_state(focusmate_posture_state_t raw,
                          focusmate_posture_state_t &stable,
                          focusmate_posture_state_t &candidate,
                          uint8_t &candidate_count)
{
    if (raw != candidate) {
        candidate = raw;
        candidate_count = 1U;
    } else if (candidate_count < kStableSamples) {
        ++candidate_count;
    }
    if (candidate_count < kStableSamples || stable == raw) return false;
    stable = raw;
    return true;
}

void geometry_self_test()
{
    uint64_t since = kNoSlumpStart;
    assert(classify_geometry(0, 0, kScale, 1000, since) == FOCUSMATE_POSTURE_NORMAL);
    assert(classify_geometry(0, 130000, kScale, 2000, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(-160000, 0, kScale, 3000, since) == FOCUSMATE_POSTURE_LEAN_LEFT);
    assert(classify_geometry(160000, 0, kScale, 4000, since) == FOCUSMATE_POSTURE_LEAN_RIGHT);
    assert(classify_geometry(-250000, 130000, kScale, 4100, since) == FOCUSMATE_POSTURE_LEAN_LEFT);
    assert(classify_geometry(250000, 130000, kScale, 4200, since) == FOCUSMATE_POSTURE_LEAN_RIGHT);
    assert(classify_geometry(160000, 170000, kScale, 4300, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(-150080, 120064, kScale, 4400, since) == FOCUSMATE_POSTURE_LEAN_LEFT);
    assert(ratio_q6(55835U, 13U) == UINT32_MAX);
    assert(classify_geometry(0, 0, ratio_q6(55835U, 13U), 4500, since) == FOCUSMATE_POSTURE_TOO_CLOSE);
    assert(classify_geometry(0, 0, 1600000, 5000, since) == FOCUSMATE_POSTURE_TOO_CLOSE);
    assert(classify_geometry(0, 130000, kScale, 6000, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(since == kNoSlumpStart);
    assert(classify_geometry(0, 190000, kScale, 7000, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(0, 190000, kScale, 11999, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(0, 190000, kScale, 12000, since) == FOCUSMATE_POSTURE_SLUMPED);
    assert(classify_geometry(0, 190000, kTooCloseRatio, 12001, since) == FOCUSMATE_POSTURE_TOO_CLOSE);
    assert(since == kNoSlumpStart);
    assert(classify_geometry(0, 190000, kScale, 12002, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    since = kNoSlumpStart;
    assert(classify_geometry(0, 190000, kScale, 0, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(0, 190000, kScale, 4999, since) == FOCUSMATE_POSTURE_HEAD_DOWN);
    assert(classify_geometry(0, 190000, kScale, 5000, since) == FOCUSMATE_POSTURE_SLUMPED);
    assert(classify_geometry(-250000, 190000, kScale, 6000, since) == FOCUSMATE_POSTURE_LEAN_LEFT);
    assert(since == kNoSlumpStart);
    assert(classify_geometry(0, 190000, kScale, 6001, since) == FOCUSMATE_POSTURE_HEAD_DOWN);

    focusmate_posture_state_t stable = FOCUSMATE_POSTURE_UNKNOWN;
    focusmate_posture_state_t candidate = FOCUSMATE_POSTURE_UNKNOWN;
    uint8_t count = 0;
    assert(!advance_stable_state(FOCUSMATE_POSTURE_NORMAL, stable, candidate, count));
    assert(!advance_stable_state(FOCUSMATE_POSTURE_NORMAL, stable, candidate, count));
    assert(advance_stable_state(FOCUSMATE_POSTURE_NORMAL, stable, candidate, count));
    assert(stable == FOCUSMATE_POSTURE_NORMAL);
    assert(!advance_stable_state(FOCUSMATE_POSTURE_UNKNOWN, stable, candidate, count));
    assert(stable == FOCUSMATE_POSTURE_NORMAL);
    assert(!advance_stable_state(FOCUSMATE_POSTURE_UNKNOWN, stable, candidate, count));
    assert(advance_stable_state(FOCUSMATE_POSTURE_UNKNOWN, stable, candidate, count));
    assert(stable == FOCUSMATE_POSTURE_UNKNOWN);

    focusmate_face_result_t visible{};
    visible.face_detected = true;
    visible.cx_q6 = 500000U;
    visible.cy_q6 = 400000U;
    visible.width_q6 = 200000U;
    visible.height_q6 = 300000U;
    assert(calibration_bbox_fully_visible(visible));
    visible.cy_q6 = 150000U;
    assert(!calibration_bbox_fully_visible(visible));
    visible.cy_q6 = 400000U;
    visible.cx_q6 = 200000U;
    visible.width_q6 = 150000U;
    assert(!calibration_bbox_fully_visible(visible));
}

bool persist_baseline()
{
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    esp_err_t error = nvs_set_u32(handle, "base_profile", kProfileFingerprint);
    if (error == ESP_OK) error = nvs_set_u32(handle, "base_cx", runtime.baseline_cx);
    if (error == ESP_OK) error = nvs_set_u32(handle, "base_cy", runtime.baseline_cy);
    if (error == ESP_OK) error = nvs_set_u32(handle, "base_area", runtime.baseline_area);
    if (error == ESP_OK) error = nvs_commit(handle);
    nvs_close(handle);
    if (error != ESP_OK) ESP_LOGE(kTag, "cannot persist baseline error=0x%x", error);
    return error == ESP_OK;
}

bool erase_baseline()
{
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    esp_err_t error = ESP_OK;
    for (const char *key : {"base_profile", "base_cx", "base_cy", "base_area"}) {
        const esp_err_t result = nvs_erase_key(handle, key);
        if (result != ESP_OK && result != ESP_ERR_NVS_NOT_FOUND && error == ESP_OK) error = result;
    }
    if (error == ESP_OK) error = nvs_commit(handle);
    nvs_close(handle);
    if (error != ESP_OK) ESP_LOGE(kTag, "cannot erase baseline error=0x%x", error);
    return error == ESP_OK;
}

bool load_baseline()
{
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READONLY, &handle) != ESP_OK) return false;
    uint32_t profile = 0, cx = 0, cy = 0, area = 0;
    const bool valid = nvs_get_u32(handle, "base_profile", &profile) == ESP_OK &&
        nvs_get_u32(handle, "base_cx", &cx) == ESP_OK &&
        nvs_get_u32(handle, "base_cy", &cy) == ESP_OK &&
        nvs_get_u32(handle, "base_area", &area) == ESP_OK &&
        profile == kProfileFingerprint && cx <= kScale && cy <= kScale && area > 0U && area <= kScale;
    nvs_close(handle);
    if (!valid) {
        if (profile != 0U) {
            runtime.calibration_reason = profile == kProfileFingerprint
                ? "invalid_persisted_baseline" : "profile_changed";
            ESP_LOGW(kTag, "discarding incompatible baseline stored_profile=0x%08lx expected=0x%08lx",
                     static_cast<unsigned long>(profile),
                     static_cast<unsigned long>(kProfileFingerprint));
            (void)erase_baseline();
        }
        return false;
    }
    runtime.baseline_cx = cx;
    runtime.baseline_cy = cy;
    runtime.baseline_area = area;
    runtime.calibrated = true;
    runtime.calibration_reason = "persisted";
    return true;
}

void set_raw_state(focusmate_posture_state_t state, uint32_t confidence, uint64_t at_ms)
{
    const focusmate_posture_state_t previous_raw = runtime.raw_state;
    const focusmate_posture_state_t previous_stable = runtime.stable_state;
    runtime.raw_state = state;
    runtime.raw_confidence = confidence;
    if (advance_stable_state(state, runtime.stable_state,
                             runtime.candidate_state, runtime.candidate_count)) {
        runtime.stable_confidence = confidence;
        runtime.stable_since_ms = at_ms;
    } else if (runtime.stable_state == state) {
        runtime.stable_confidence = confidence;
    }
    if (previous_raw != runtime.raw_state) {
        ESP_LOGI(kTag, "raw=%s confidence_q6=%lu dx_q6=%ld dy_q6=%ld area_ratio_q6=%lu",
                 focusmate_posture_state_name(runtime.raw_state),
                 static_cast<unsigned long>(runtime.raw_confidence),
                 static_cast<long>(runtime.dx), static_cast<long>(runtime.dy),
                 static_cast<unsigned long>(runtime.area_ratio));
    }
    if (previous_stable != runtime.stable_state) {
        ESP_LOGI(kTag, "stable=%s after=%u samples confidence_q6=%lu",
                 focusmate_posture_state_name(runtime.stable_state), kStableSamples,
                 static_cast<unsigned long>(runtime.stable_confidence));
    }
}

bool calibration_samples_stable()
{
    const uint32_t area = median(runtime.areas);
    const auto [min_x, max_x] = std::minmax_element(runtime.xs.begin(), runtime.xs.end());
    const auto [min_y, max_y] = std::minmax_element(runtime.ys.begin(), runtime.ys.end());
    const auto [min_area, max_area] = std::minmax_element(runtime.areas.begin(), runtime.areas.end());
    return *max_x - *min_x <= kMaximumCenterSpread &&
        *max_y - *min_y <= kMaximumCenterSpread && area > 0U &&
        ratio_q6(*max_area - *min_area, area) <= kMaximumAreaSpreadRatio;
}

void finish_calibration()
{
    const uint32_t cx = median(runtime.xs);
    const uint32_t cy = median(runtime.ys);
    const uint32_t area = median(runtime.areas);
    runtime.calibration_active = false;
    runtime.last_calibration_sample_ms = 0;
    if (!calibration_samples_stable()) {
        runtime.sample_count = 0;
        runtime.calibration_reason = "unstable";
        return;
    }
    runtime.baseline_cx = cx;
    runtime.baseline_cy = cy;
    runtime.baseline_area = area;
    reset_live_temporal_state(now_ms());
    if (!persist_baseline()) {
        runtime.calibrated = false;
        runtime.sample_count = 0;
        runtime.baseline_cx = runtime.baseline_cy = runtime.baseline_area = 0;
        runtime.calibration_reason = "storage_error";
        return;
    }
    runtime.calibrated = true;
    runtime.calibration_reason = "complete";
    ESP_LOGI(kTag, "calibration complete cx=%lu cy=%lu area=%lu",
             static_cast<unsigned long>(cx), static_cast<unsigned long>(cy), static_cast<unsigned long>(area));
}

} // namespace

extern "C" bool focusmate_shadow_posture_init(void)
{
    geometry_self_test();
    runtime.mutex = xSemaphoreCreateMutex();
    if (runtime.mutex == nullptr) return false;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    const bool loaded = load_baseline();
    xSemaphoreGive(runtime.mutex);
    ESP_LOGI(kTag, "shadow classifier ready baseline=%s cx_q6=%lu cy_q6=%lu area_q6=%lu live_confidence_q6=%lu calibration_confidence_q6=%lu",
             loaded ? "persisted" : "required",
             static_cast<unsigned long>(runtime.baseline_cx),
             static_cast<unsigned long>(runtime.baseline_cy),
             static_cast<unsigned long>(runtime.baseline_area),
             static_cast<unsigned long>(kLiveMinimumConfidence),
             static_cast<unsigned long>(kCalibrationMinimumConfidence));
    return true;
}

extern "C" void focusmate_shadow_posture_observe(const focusmate_face_result_t *result)
{
    if (result == nullptr || runtime.mutex == nullptr) return;
    const uint64_t at_ms = result->observed_uptime_ms;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    if (runtime.last_observed_ms != 0U &&
        (at_ms <= runtime.last_observed_ms || at_ms - runtime.last_observed_ms > kStaleMs)) {
        reset_live_temporal_state(at_ms);
    }
    runtime.last_observed_ms = at_ms;
    if (runtime.last_sample_ms != 0U && at_ms - runtime.last_sample_ms < kSamplePeriodMs) {
        xSemaphoreGive(runtime.mutex);
        return;
    }
    runtime.last_sample_ms = at_ms;

    const uint32_t observed_area = result->face_detected ? area_q6(*result) : 0U;
    if (runtime.calibration_active) {
        if (at_ms > runtime.calibration_deadline_ms) {
            runtime.calibration_active = false;
            runtime.last_calibration_sample_ms = 0;
            runtime.calibration_reason = runtime.sample_count == 0U ? "no_face" : "insufficient_valid_samples";
        } else if (at_ms < runtime.calibration_collect_after_ms) {
            runtime.calibration_reason = "settling";
        } else {
            const bool visible = result->face_detected && observed_area > 0U &&
                calibration_bbox_fully_visible(*result);
            const bool valid_sample = visible && result->confidence_q6 >= kCalibrationMinimumConfidence;
            if (valid_sample) {
                if (runtime.last_calibration_sample_ms != 0U &&
                    (at_ms <= runtime.last_calibration_sample_ms ||
                     at_ms - runtime.last_calibration_sample_ms > kCalibrationMaximumGapMs)) {
                    runtime.sample_count = 0;
                }
                runtime.last_calibration_sample_ms = at_ms;
                if (runtime.sample_count < kCalibrationSamples) {
                    const size_t index = runtime.sample_count++;
                    runtime.xs[index] = result->cx_q6;
                    runtime.ys[index] = result->cy_q6;
                    runtime.areas[index] = observed_area;
                    runtime.calibration_reason = "collecting";
                    if (runtime.sample_count == kCalibrationSamples) finish_calibration();
                }
            } else {
                runtime.calibration_reason = !result->face_detected ? "no_face" :
                    result->confidence_q6 < kCalibrationMinimumConfidence ? "low_confidence" :
                    !visible ? "edge_clipped" : "invalid_bbox";
                if (runtime.last_calibration_sample_ms != 0U &&
                    at_ms - runtime.last_calibration_sample_ms > kCalibrationMaximumGapMs) {
                    runtime.sample_count = 0;
                    runtime.last_calibration_sample_ms = 0;
                }
            }
        }
    }

    if (!result->face_detected) {
        runtime.slumped_since_ms = kNoSlumpStart;
        runtime.dx = runtime.dy = 0;
        runtime.area_ratio = 0;
        set_raw_state(FOCUSMATE_POSTURE_FACE_MISSING, kScale, at_ms);
        xSemaphoreGive(runtime.mutex);
        return;
    }
    if (runtime.calibrated && observed_area > 0U) {
        // A camera sees a facing person's left on the image's right. Negating
        // image displacement makes dx semantic from the seated person's view:
        // negative = their left, positive = their right.
        runtime.dx = static_cast<int32_t>(runtime.baseline_cx) - static_cast<int32_t>(result->cx_q6);
        runtime.dy = static_cast<int32_t>(result->cy_q6) - static_cast<int32_t>(runtime.baseline_cy);
        runtime.area_ratio = ratio_q6(observed_area, runtime.baseline_area);
    } else {
        runtime.dx = runtime.dy = 0;
        runtime.area_ratio = 0;
    }
    if (result->confidence_q6 < kLiveMinimumConfidence || !runtime.calibrated || observed_area == 0U) {
        runtime.slumped_since_ms = kNoSlumpStart;
        set_raw_state(FOCUSMATE_POSTURE_UNKNOWN, result->confidence_q6, at_ms);
        xSemaphoreGive(runtime.mutex);
        return;
    }

    const focusmate_posture_state_t state = classify_geometry(
        runtime.dx, runtime.dy, runtime.area_ratio, at_ms, runtime.slumped_since_ms);
    const uint32_t confidence = std::min(result->confidence_q6,
        geometry_confidence(state, runtime.dx, runtime.dy, runtime.area_ratio));
    set_raw_state(state, confidence, at_ms);
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_shadow_posture_start_calibration(void)
{
    if (runtime.mutex == nullptr) return;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    const uint64_t current = now_ms();
    runtime.calibrated = false;
    runtime.calibration_active = true;
    runtime.calibration_collect_after_ms = current + kCalibrationSettleMs;
    runtime.calibration_deadline_ms = current + kCalibrationWindowMs;
    runtime.last_calibration_sample_ms = 0;
    runtime.sample_count = 0;
    runtime.calibration_reason = "waiting_for_face";
    reset_live_temporal_state(current);
    runtime.baseline_cx = runtime.baseline_cy = runtime.baseline_area = 0;
    runtime.dx = runtime.dy = 0;
    runtime.area_ratio = 0;
    if (!erase_baseline()) {
        runtime.calibration_active = false;
        runtime.calibration_reason = "storage_error";
    }
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_shadow_posture_reset(void)
{
    if (runtime.mutex == nullptr) return;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    runtime.calibrated = false;
    runtime.calibration_active = false;
    runtime.last_calibration_sample_ms = 0;
    runtime.sample_count = 0;
    runtime.calibration_reason = "not_calibrated";
    reset_live_temporal_state(now_ms());
    runtime.baseline_cx = runtime.baseline_cy = runtime.baseline_area = 0;
    runtime.dx = runtime.dy = 0;
    runtime.area_ratio = 0;
    if (!erase_baseline()) runtime.calibration_reason = "storage_error";
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_shadow_posture_snapshot(focusmate_posture_snapshot_t *out)
{
    if (out == nullptr || runtime.mutex == nullptr) return;
    xSemaphoreTake(runtime.mutex, portMAX_DELAY);
    const uint64_t current = now_ms();
    const bool stale = runtime.last_observed_ms == 0U || current < runtime.last_observed_ms ||
        current - runtime.last_observed_ms > kStaleMs;
    if (stale && (runtime.slumped_since_ms != kNoSlumpStart ||
                  runtime.raw_state != FOCUSMATE_POSTURE_UNKNOWN ||
                  runtime.stable_state != FOCUSMATE_POSTURE_UNKNOWN ||
                  runtime.candidate_count != 0U)) {
        reset_live_temporal_state(current);
    }
    *out = {
        .calibrated = runtime.calibrated,
        .calibration_active = runtime.calibration_active,
        .calibration_progress = static_cast<uint8_t>(runtime.sample_count),
        .calibration_reason = runtime.calibration_reason,
        .raw_state = stale ? FOCUSMATE_POSTURE_UNKNOWN : runtime.raw_state,
        .state = stale ? FOCUSMATE_POSTURE_UNKNOWN : runtime.stable_state,
        .raw_confidence_q6 = stale ? 0U : runtime.raw_confidence,
        .confidence_q6 = stale ? 0U : runtime.stable_confidence,
        .stable_ms = stale || current < runtime.stable_since_ms ? 0U : current - runtime.stable_since_ms,
        .dx_q6 = runtime.dx,
        .dy_q6 = runtime.dy,
        .area_ratio_q6 = runtime.area_ratio,
        .baseline_cx_q6 = runtime.baseline_cx,
        .baseline_cy_q6 = runtime.baseline_cy,
        .baseline_area_q6 = runtime.baseline_area,
    };
    xSemaphoreGive(runtime.mutex);
}

extern "C" void focusmate_shadow_posture_thresholds(focusmate_posture_thresholds_t *out)
{
    if (out == nullptr) return;
    *out = {
        .calibration_min_confidence_q6 = kCalibrationMinimumConfidence,
        .live_min_confidence_q6 = kLiveMinimumConfidence,
        .lean_delta_q6 = kLeanDelta,
        .head_down_delta_q6 = kHeadDownDelta,
        .slumped_delta_q6 = kSlumpedDelta,
        .too_close_ratio_q6 = kTooCloseRatio,
        .slumped_minimum_ms = kSlumpedMinimumMs,
        .stable_samples = kStableSamples,
        .baseline_revision = kBaselineRevision,
    };
}

extern "C" const char *focusmate_posture_state_name(focusmate_posture_state_t state)
{
    switch (state) {
        case FOCUSMATE_POSTURE_NORMAL: return "NORMAL";
        case FOCUSMATE_POSTURE_HEAD_DOWN: return "HEAD_DOWN";
        case FOCUSMATE_POSTURE_LEAN_LEFT: return "LEAN_LEFT";
        case FOCUSMATE_POSTURE_LEAN_RIGHT: return "LEAN_RIGHT";
        case FOCUSMATE_POSTURE_TOO_CLOSE: return "TOO_CLOSE";
        case FOCUSMATE_POSTURE_SLUMPED: return "SLUMPED";
        case FOCUSMATE_POSTURE_FACE_MISSING: return "FACE_MISSING";
        default: return "UNKNOWN";
    }
}
