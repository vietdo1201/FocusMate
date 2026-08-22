#include "camera_smoke.h"

#include "esp_camera.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "sdkconfig.h"

static const char *TAG = "focusmate-camera";

#if CONFIG_FOCUSMATE_CAMERA_ENABLE && !CONFIG_FOCUSMATE_CAMERA_PINOUT_CONFIRMED
#error "Camera is enabled without physical pinout/XCLK confirmation"
#endif

bool focusmate_camera_smoke_init(void)
{
#if !CONFIG_FOCUSMATE_CAMERA_ENABLE
    ESP_LOGW(TAG, "camera gate disabled; capability remains not-ready");
    return false;
#else
    /* Confirmed against the previously working Arduino camera sketch recorded
     * in data/So_do_chan.md. This 18-pin module supplies its own oscillator. */
    const camera_config_t config = {
        .pin_pwdn = 38,
        .pin_reset = 40,
        .pin_xclk = CONFIG_FOCUSMATE_CAMERA_EXTERNAL_OSCILLATOR ? -1 : 21,
        .pin_sccb_sda = 1,
        .pin_sccb_scl = 2,
        .pin_d7 = 18,
        .pin_d6 = 17,
        .pin_d5 = 16,
        .pin_d4 = 15,
        .pin_d3 = 7,
        .pin_d2 = 6,
        .pin_d1 = 5,
        .pin_d0 = 4,
        .pin_vsync = 42,
        .pin_href = 41,
        .pin_pclk = 39,
        .xclk_freq_hz = 24000000,
        .ledc_timer = LEDC_TIMER_0,
        .ledc_channel = LEDC_CHANNEL_0,
        .pixel_format = PIXFORMAT_JPEG,
        .frame_size = FRAMESIZE_QVGA,
        .jpeg_quality = 8,
        .fb_count = 1,
        .fb_location = CAMERA_FB_IN_PSRAM,
        .grab_mode = CAMERA_GRAB_WHEN_EMPTY,
    };
    esp_err_t error = esp_camera_init(&config);
    if (error != ESP_OK) {
        ESP_LOGE(TAG, "OV2640 init failed error=0x%x", error);
        return false;
    }

    sensor_t *sensor = esp_camera_sensor_get();
    if (sensor == NULL || sensor->id.PID != OV2640_PID) {
        ESP_LOGE(TAG, "unexpected camera PID=0x%04x", sensor == NULL ? 0U : sensor->id.PID);
        esp_camera_deinit();
        return false;
    }

    /* Correct the module's confirmed 180-degree physical mounting at the
     * sensor so both the detector and dashboard receive an upright frame. The
     * transform preserves normalized bbox geometry. The old
     * dashboard's LowLight preset used brightness +1; contrast +1 belongs to
     * its separate Sharp preset and crushes shadow detail when both are
     * combined.  Explicitly keep the OV2640 automatic controls enabled and
     * give AGC enough headroom for an indoor desk without forcing fixed gain
     * or exposure. */
    int tuning_error = 0;
    tuning_error |= sensor->set_whitebal(sensor, 1);
    tuning_error |= sensor->set_awb_gain(sensor, 1);
    tuning_error |= sensor->set_exposure_ctrl(sensor, 1);
    tuning_error |= sensor->set_gain_ctrl(sensor, 1);
    tuning_error |= sensor->set_aec2(sensor, 1);
    tuning_error |= sensor->set_ae_level(sensor, 1);
    tuning_error |= sensor->set_gainceiling(sensor, GAINCEILING_8X);
    tuning_error |= sensor->set_brightness(sensor, 1);
    tuning_error |= sensor->set_contrast(sensor, 0);
    tuning_error |= sensor->set_saturation(sensor, 0);
    tuning_error |= sensor->set_hmirror(sensor, 1);
    tuning_error |= sensor->set_vflip(sensor, 1);
    if (tuning_error != 0) {
        ESP_LOGE(TAG, "OV2640 rejected the QVGA indoor-auto profile");
        esp_camera_deinit();
        return false;
    }

    const int64_t started_us = esp_timer_get_time();
    unsigned valid = 0;
    unsigned errors = 0;
    for (unsigned attempt = 0; attempt < 25U; ++attempt) {
        camera_fb_t *frame = esp_camera_fb_get();
        if (frame == NULL || frame->format != PIXFORMAT_JPEG ||
            frame->width != 320U || frame->height != 240U ||
            frame->len < 4U || frame->buf[0] != 0xffU || frame->buf[1] != 0xd8U ||
            frame->buf[frame->len - 2U] != 0xffU || frame->buf[frame->len - 1U] != 0xd9U) {
            ++errors;
        } else {
            ++valid;
        }
        if (frame != NULL) esp_camera_fb_return(frame);
    }
    const int64_t elapsed_us = esp_timer_get_time() - started_us;
    const double fps = elapsed_us > 0 ? (double)valid * 1000000.0 / (double)elapsed_us : 0.0;
    ESP_LOGI(TAG, "OV2640 smoke PID=0x%04x format=JPEG size=320x240 quality=8 profile=indoor-auto valid=%u errors=%u fps=%.2f",
             sensor->id.PID, valid, errors, fps);
    if (valid < 24U || errors > 1U) {
        ESP_LOGE(TAG, "camera smoke acceptance failed");
        esp_camera_deinit();
        return false;
    }
    return true;
#endif
}
