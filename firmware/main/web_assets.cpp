#include "web_assets.h"

#include <array>
#include <cstdio>
#include <cstring>

#include "esp_log.h"
#include "esp_spiffs.h"

namespace {

constexpr char kTag[] = "focusmate-assets";
constexpr char kMount[] = "/mpassets";

struct Asset {
    const char *uri;
    const char *file;
    const char *content_type;
    bool gzip;
};

constexpr Asset kAssets[] = {
    {"/assets/vision_bundle.mjs", "/mpassets/vision_bundle.mjs", "text/javascript; charset=utf-8", false},
    {"/assets/pose_worker.mjs", "/mpassets/pose_worker.mjs", "text/javascript; charset=utf-8", false},
    {"/assets/pose_worker_bootstrap.js", "/mpassets/pose_worker_bootstrap.js", "text/javascript; charset=utf-8", false},
    {"/assets/pose_classifier.mjs", "/mpassets/pose_classifier.mjs", "text/javascript; charset=utf-8", false},
    {"/assets/wasm/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", false},
    {"/assets/wasm/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.gz", "application/wasm", true},
    {"/assets/wasm-module-v1/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", false},
    {"/assets/wasm-module-v1/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.gz", "application/wasm", true},
    {"/assets/wasm-module-v2/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", false},
    {"/assets/wasm-module-v2/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.gz", "application/wasm", true},
    {"/assets/wasm-classic-v1/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", false},
    {"/assets/wasm-classic-v1/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.gz", "application/wasm", true},
    {"/assets/pose_landmarker_lite.task", "/mpassets/pose_landmarker_lite.task.gz", "application/octet-stream", true},
    {"/assets/pose_landmarker_lite-v2.task", "/mpassets/pose_landmarker_lite.task.gz", "application/octet-stream", true},
    {"/assets/asset-manifest.json", "/mpassets/asset-manifest.json", "application/json; charset=utf-8", false},
};

const Asset *find_asset(const char *uri)
{
    for (const Asset &asset : kAssets) {
        if (std::strcmp(uri, asset.uri) == 0) return &asset;
    }
    return nullptr;
}

} // namespace

extern "C" bool focusmate_web_assets_mount(void)
{
    const esp_vfs_spiffs_conf_t config = {
        .base_path = kMount,
        .partition_label = "mp_assets",
        .max_files = 8,
        .format_if_mount_failed = false,
    };
    const esp_err_t result = esp_vfs_spiffs_register(&config);
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(kTag, "cannot mount read-only MediaPipe assets: %s", esp_err_to_name(result));
        return false;
    }
    size_t total = 0;
    size_t used = 0;
    if (esp_spiffs_info("mp_assets", &total, &used) == ESP_OK)
        ESP_LOGI(kTag, "MediaPipe assets mounted: %u/%u bytes", static_cast<unsigned>(used), static_cast<unsigned>(total));
    return true;
}

extern "C" esp_err_t focusmate_web_assets_serve(httpd_req_t *request, const char *uri)
{
    const Asset *asset = find_asset(uri);
    if (asset == nullptr) return httpd_resp_send_404(request);
    FILE *file = std::fopen(asset->file, "rb");
    if (file == nullptr) {
        ESP_LOGE(kTag, "missing generated asset %s", asset->file);
        return httpd_resp_send_500(request);
    }
    httpd_resp_set_type(request, asset->content_type);
    httpd_resp_set_hdr(request, "Cache-Control", asset->gzip
        ? "public, max-age=31536000, immutable"
        : "no-cache");
    httpd_resp_set_hdr(request, "X-Content-Type-Options", "nosniff");
    httpd_resp_set_hdr(request, "Cross-Origin-Resource-Policy", "same-origin");
    if (asset->gzip) httpd_resp_set_hdr(request, "Content-Encoding", "gzip");

    std::array<char, 4096> buffer{};
    esp_err_t result = ESP_OK;
    while (!std::feof(file)) {
        const size_t count = std::fread(buffer.data(), 1, buffer.size(), file);
        if (count > 0 && httpd_resp_send_chunk(request, buffer.data(), count) != ESP_OK) {
            result = ESP_FAIL;
            break;
        }
        if (std::ferror(file)) {
            result = ESP_FAIL;
            break;
        }
    }
    std::fclose(file);
    httpd_resp_send_chunk(request, nullptr, 0);
    return result;
}
