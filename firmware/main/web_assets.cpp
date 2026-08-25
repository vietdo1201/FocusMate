// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#include "web_assets.h"

#include <array>
#include <cstdio>
#include <cstring>

#include "esp_log.h"
#include "esp_spiffs.h"
#include "mbedtls/sha256.h"

namespace {

constexpr char kTag[] = "focusmate-assets";
constexpr char kMount[] = "/mpassets";
std::array<char, 65> manifest_sha256{};

struct Asset {
    const char *uri;
    const char *file;
    const char *content_type;
    const char *content_encoding;
};

constexpr Asset kAssets[] = {
    {"/assets/vision_bundle.mjs", "/mpassets/vision_bundle.mjs", "text/javascript; charset=utf-8", nullptr},
    {"/assets/pose_worker.mjs", "/mpassets/pose_worker.mjs", "text/javascript; charset=utf-8", nullptr},
    {"/assets/pose_worker_bootstrap.js", "/mpassets/pose_worker_bootstrap.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/pose_classifier.mjs", "/mpassets/pose_classifier.mjs", "text/javascript; charset=utf-8", nullptr},
    {"/assets/yawn_classifier.mjs", "/mpassets/yawn_classifier.mjs", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm/vision_wasm_nosimd_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm/vision_wasm_nosimd_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm-module-v1/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm-module-v1/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm-module-v2/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm-module-v2/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm-classic-v1/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm-classic-v1/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm-compatible-v1/vision_wasm_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm-compatible-v1/vision_wasm_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/wasm-compatible-v1/vision_wasm_nosimd_internal.js", "/mpassets/wasm/vwi.js", "text/javascript; charset=utf-8", nullptr},
    {"/assets/wasm-compatible-v1/vision_wasm_nosimd_internal.wasm", "/mpassets/wasm/vwi.wasm.br", "application/wasm", "br"},
    {"/assets/pose_landmarker_lite.task", "/mpassets/pose_landmarker_lite.task.gz", "application/octet-stream", "gzip"},
    {"/assets/pose_landmarker_lite-v2.task", "/mpassets/pose_landmarker_lite.task.gz", "application/octet-stream", "gzip"},
    {"/assets/face_landmarker-v1.task", "/mpassets/face_landmarker.task.gz", "application/octet-stream", "gzip"},
    {"/assets/asset-manifest.json", "/mpassets/asset-manifest.json", "application/json; charset=utf-8", nullptr},
};

const Asset *find_asset(const char *uri)
{
    for (const Asset &asset : kAssets) {
        if (std::strcmp(uri, asset.uri) == 0) return &asset;
    }
    return nullptr;
}

void hash_manifest()
{
    FILE *file = std::fopen("/mpassets/asset-manifest.json", "rb");
    if (file == nullptr) return;
    mbedtls_sha256_context context;
    mbedtls_sha256_init(&context);
    std::array<unsigned char, 32> digest{};
    std::array<unsigned char, 1024> buffer{};
    bool ok = mbedtls_sha256_starts(&context, 0) == 0;
    while (ok && !std::feof(file)) {
        const size_t count = std::fread(buffer.data(), 1, buffer.size(), file);
        if (count > 0) ok = mbedtls_sha256_update(&context, buffer.data(), count) == 0;
        if (std::ferror(file)) ok = false;
    }
    if (ok) ok = mbedtls_sha256_finish(&context, digest.data()) == 0;
    mbedtls_sha256_free(&context);
    std::fclose(file);
    if (!ok) return;
    for (size_t index = 0; index < digest.size(); ++index) {
        std::snprintf(manifest_sha256.data() + index * 2U, 3U, "%02x", digest[index]);
    }
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
    hash_manifest();
    return true;
}

extern "C" const char *focusmate_web_assets_manifest_sha256(void)
{
    return manifest_sha256[0] == '\0' ? "unavailable" : manifest_sha256.data();
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
    httpd_resp_set_hdr(request, "Cache-Control", asset->content_encoding != nullptr
        ? "public, max-age=31536000, immutable"
        : "no-cache");
    httpd_resp_set_hdr(request, "X-Content-Type-Options", "nosniff");
    httpd_resp_set_hdr(request, "Cross-Origin-Resource-Policy", "same-origin");
    if (asset->content_encoding != nullptr) httpd_resp_set_hdr(request, "Content-Encoding", asset->content_encoding);

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
