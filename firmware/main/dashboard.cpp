#include "dashboard.h"

#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <string>
#include <vector>

#include "cJSON.h"
#include "dashboard_runtime.h"
#include "esp_event.h"
#include "esp_heap_caps.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "face_detector.h"
#include "frame_broker.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"
#include "mdns.h"
#include "nvs.h"
#include "shadow_posture.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

extern const uint8_t dashboard_html_start[] asm("_binary_index_html_start");
extern const uint8_t dashboard_html_end[] asm("_binary_index_html_end");

namespace {

constexpr char kTag[] = "focusmate-web";
constexpr char kNvsNamespace[] = "focusmate_web";
constexpr char kLegacyNvsNamespace[] = "focusmate";
constexpr char kDefaultPassword[] = "FocusMate123";
constexpr char kCookieName[] = "focusmate_session";
constexpr char kApSsid[] = "FocusMate-Setup";
constexpr char kMdnsHost[] = "focusmate";
constexpr uint32_t kLoginBackoffMs = 1000U;

struct WifiStatus {
    bool station_online = false;
    char ip[16] = "192.168.4.1";
    char ssid[33] = "FocusMate-Setup";
    int rssi = 0;
};

class Dashboard {
public:
    bool start();
    static esp_err_t dispatch(httpd_req_t *request);

private:
    esp_err_t handle(httpd_req_t *request);
    bool initialize_identity();
    bool initialize_network();
    bool initialize_server();
    bool authenticated(httpd_req_t *request) const;
    esp_err_t root(httpd_req_t *request);
    esp_err_t login(httpd_req_t *request);
    esp_err_t logout(httpd_req_t *request);
    esp_err_t change_password(httpd_req_t *request);
    esp_err_t camera(httpd_req_t *request);
    esp_err_t viewer_release(httpd_req_t *request);
    esp_err_t status(httpd_req_t *request);
    esp_err_t posture(httpd_req_t *request, bool calibrate);
    esp_err_t wifi_scan(httpd_req_t *request);
    esp_err_t wifi_connect(httpd_req_t *request);
    esp_err_t wifi_reset(httpd_req_t *request);
    esp_err_t ap_password(httpd_req_t *request);
    static void event_thunk(void *argument, esp_event_base_t base, int32_t id, void *data);
    static void network_task_thunk(void *argument);
    void on_event(esp_event_base_t base, int32_t id, void *data);
    void network_task();
    std::string nvs_string(const char *key) const;
    bool set_nvs_string(const char *key, const std::string &value);
    bool erase_nvs_keys(std::initializer_list<const char *> keys);
    bool configure_station(const std::string &ssid, const std::string &password);
    bool promote_pending();
    void rollback_pending();
    WifiStatus wifi_status();

    SemaphoreHandle_t mutex_ = nullptr;
    httpd_handle_t server_ = nullptr;
    WifiStatus wifi_{};
    std::string dashboard_password_;
    std::string session_token_;
    uint64_t last_login_failure_ms_ = 0;
    uint32_t previous_inference_count_ = 0;
    uint64_t previous_inference_at_ms_ = 0;
    uint32_t inference_fps_q6_ = 0;
    bool pending_active_ = false;
    bool pending_connection_started_ = false;
    bool pending_switch_requested_ = false;
    bool station_associated_ = false;
    uint64_t pending_deadline_ms_ = 0;
    uint64_t pending_switch_at_ms_ = 0;
    uint64_t last_reconnect_ms_ = 0;
};

Dashboard dashboard;

uint64_t now_ms()
{
    return static_cast<uint64_t>(esp_timer_get_time() / 1000);
}

std::string read_body(httpd_req_t *request)
{
    if (request->content_len <= 0 || request->content_len > 1024) return {};
    std::string body(static_cast<size_t>(request->content_len), '\0');
    size_t received = 0;
    while (received < body.size()) {
        const int count = httpd_req_recv(request, body.data() + received, body.size() - received);
        if (count <= 0) return {};
        received += static_cast<size_t>(count);
    }
    return body;
}

esp_err_t send_json(httpd_req_t *request, cJSON *root)
{
    httpd_resp_set_type(request, "application/json; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    char *body = cJSON_PrintUnformatted(root);
    const esp_err_t result = body == nullptr ? ESP_ERR_NO_MEM : httpd_resp_sendstr(request, body);
    std::free(body);
    cJSON_Delete(root);
    return result;
}

esp_err_t json_error(httpd_req_t *request, const char *status, const char *message)
{
    httpd_resp_set_status(request, status);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "error", message);
    return send_json(request, root);
}

std::string json_string(cJSON *root, const char *key)
{
    cJSON *item = cJSON_GetObjectItemCaseSensitive(root, key);
    return cJSON_IsString(item) && item->valuestring != nullptr ? item->valuestring : "";
}

void add_q6(cJSON *object, const char *key, int64_t value)
{
    cJSON_AddNumberToObject(object, key, static_cast<double>(value) / 1000000.0);
}

uint32_t remote_ipv4(httpd_req_t *request)
{
    const int socket = httpd_req_to_sockfd(request);
    sockaddr_storage address{};
    socklen_t length = sizeof address;
    if (socket < 0 || getpeername(socket, reinterpret_cast<sockaddr *>(&address), &length) != 0 ||
        address.ss_family != AF_INET) return 0U;
    return reinterpret_cast<sockaddr_in *>(&address)->sin_addr.s_addr;
}

std::string hex_token()
{
    std::array<uint8_t, 16> bytes{};
    esp_fill_random(bytes.data(), bytes.size());
    char value[33];
    for (size_t index = 0; index < bytes.size(); ++index)
        std::snprintf(value + index * 2U, 3U, "%02x", bytes[index]);
    value[32] = '\0';
    return value;
}

bool valid_password(const std::string &value)
{
    return value.size() >= 8U && value.size() <= 63U;
}

} // namespace

bool Dashboard::start()
{
    mutex_ = xSemaphoreCreateMutex();
    if (mutex_ == nullptr || !initialize_identity()) return false;
    if (!initialize_network()) return false;
    if (!initialize_server()) return false;
    ESP_LOGI(kTag, "dashboard ready at http://%s.local and http://192.168.4.1", kMdnsHost);
    return true;
}

bool Dashboard::initialize_identity()
{
    dashboard_password_ = nvs_string("dash_pass");
    if (!valid_password(dashboard_password_)) {
        const std::string legacy_ap_password = nvs_string("ap_pass");
        dashboard_password_ = valid_password(legacy_ap_password) ? legacy_ap_password : kDefaultPassword;
        if (!set_nvs_string("dash_pass", dashboard_password_)) return false;
    }
    session_token_ = nvs_string("dash_token");
    if (session_token_.size() != 32U) {
        session_token_ = hex_token();
        if (!set_nvs_string("dash_token", session_token_)) return false;
    }
    return true;
}

bool Dashboard::initialize_network()
{
    esp_err_t result = esp_netif_init();
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) return false;
    result = esp_event_loop_create_default();
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) return false;
    esp_netif_create_default_wifi_sta();
    esp_netif_create_default_wifi_ap();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    if (esp_wifi_init(&init) != ESP_OK) return false;
    esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, event_thunk, this);
    esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, event_thunk, this);
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    esp_wifi_set_ps(WIFI_PS_NONE);
    if (esp_wifi_set_mode(WIFI_MODE_APSTA) != ESP_OK) return false;

    wifi_config_t ap{};
    std::strncpy(reinterpret_cast<char *>(ap.ap.ssid), kApSsid, sizeof(ap.ap.ssid) - 1U);
    const std::string ap_pass = [&]() {
        const std::string stored = nvs_string("ap_pass");
        return valid_password(stored) ? stored : std::string(kDefaultPassword);
    }();
    std::strncpy(reinterpret_cast<char *>(ap.ap.password), ap_pass.c_str(), sizeof(ap.ap.password) - 1U);
    ap.ap.ssid_len = std::strlen(kApSsid);
    ap.ap.channel = 1;
    ap.ap.max_connection = 2;
    ap.ap.authmode = WIFI_AUTH_WPA2_PSK;
    if (esp_wifi_set_config(WIFI_IF_AP, &ap) != ESP_OK || esp_wifi_start() != ESP_OK) return false;

    std::string ssid = nvs_string("pending_ssid");
    std::string password = nvs_string("pending_pass");
    if (!ssid.empty()) {
        pending_active_ = true;
        pending_connection_started_ = true;
        pending_deadline_ms_ = now_ms() + 20000U;
    } else {
        ssid = nvs_string("wifi_ssid");
        password = nvs_string("wifi_pass");
    }
    if (!ssid.empty()) configure_station(ssid, password);

    if (mdns_init() == ESP_OK) {
        mdns_hostname_set(kMdnsHost);
        mdns_instance_name_set("FocusMate Shadow Dashboard");
        mdns_service_add(nullptr, "_http", "_tcp", 80, nullptr, 0);
    }
    if (xTaskCreate(network_task_thunk, "focusmate-network", 4096, this, 3, nullptr) != pdPASS)
        return false;
    return true;
}

bool Dashboard::initialize_server()
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    config.max_uri_handlers = 16;
    config.max_open_sockets = 6;
    config.lru_purge_enable = true;
    config.stack_size = 10240;
    if (httpd_start(&server_, &config) != ESP_OK) return false;
    const httpd_uri_t routes[] = {
        {"/", HTTP_GET, dispatch, this},
        {"/camera.jpg", HTTP_GET, dispatch, this},
        {"/api/viewer/release", HTTP_POST, dispatch, this},
        {"/api/status", HTTP_GET, dispatch, this},
        {"/api/auth/login", HTTP_POST, dispatch, this},
        {"/api/auth/logout", HTTP_POST, dispatch, this},
        {"/api/auth/password", HTTP_POST, dispatch, this},
        {"/api/posture/calibrate", HTTP_POST, dispatch, this},
        {"/api/posture/reset", HTTP_POST, dispatch, this},
        {"/api/wifi/scan", HTTP_GET, dispatch, this},
        {"/api/wifi/connect", HTTP_POST, dispatch, this},
        {"/api/wifi/reset", HTTP_POST, dispatch, this},
        {"/api/wifi/ap-password", HTTP_POST, dispatch, this},
    };
    for (const httpd_uri_t &route : routes) {
        if (httpd_register_uri_handler(server_, &route) != ESP_OK) return false;
    }
    return true;
}

esp_err_t Dashboard::dispatch(httpd_req_t *request)
{
    return static_cast<Dashboard *>(request->user_ctx)->handle(request);
}

esp_err_t Dashboard::handle(httpd_req_t *request)
{
    std::string uri = request->uri;
    const size_t query = uri.find('?');
    if (query != std::string::npos) uri.resize(query);
    if (uri == "/") return root(request);
    if (uri == "/api/auth/login") return login(request);
    if (!authenticated(request)) return json_error(request, "401 Unauthorized", "authentication required");
    if (uri == "/api/auth/logout") return logout(request);
    if (uri == "/api/auth/password") return change_password(request);
    if (uri == "/camera.jpg") return camera(request);
    if (uri == "/api/viewer/release") return viewer_release(request);
    if (uri == "/api/status") return status(request);
    if (uri == "/api/posture/calibrate") return posture(request, true);
    if (uri == "/api/posture/reset") return posture(request, false);
    if (uri == "/api/wifi/scan") return wifi_scan(request);
    if (uri == "/api/wifi/connect") return wifi_connect(request);
    if (uri == "/api/wifi/reset") return wifi_reset(request);
    if (uri == "/api/wifi/ap-password") return ap_password(request);
    return httpd_resp_send_404(request);
}

bool Dashboard::authenticated(httpd_req_t *request) const
{
    const size_t length = httpd_req_get_hdr_value_len(request, "Cookie");
    if (length == 0U || length > 512U) return false;
    std::vector<char> cookie(length + 1U);
    if (httpd_req_get_hdr_value_str(request, "Cookie", cookie.data(), cookie.size()) != ESP_OK) return false;
    const std::string expected = std::string(kCookieName) + "=" + session_token_;
    const std::string value(cookie.data());
    const size_t position = value.find(expected);
    if (position == std::string::npos) return false;
    const size_t end = position + expected.size();
    return (position == 0U || value[position - 1U] == ' ' || value[position - 1U] == ';') &&
        (end == value.size() || value[end] == ';');
}

esp_err_t Dashboard::root(httpd_req_t *request)
{
    httpd_resp_set_type(request, "text/html; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    httpd_resp_set_hdr(request, "Content-Security-Policy",
        "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
    return httpd_resp_send(request, reinterpret_cast<const char *>(dashboard_html_start),
                           dashboard_html_end - dashboard_html_start);
}

esp_err_t Dashboard::login(httpd_req_t *request)
{
    const uint64_t current = now_ms();
    if (last_login_failure_ms_ != 0U && current - last_login_failure_ms_ < kLoginBackoffMs)
        return json_error(request, "429 Too Many Requests", "try again shortly");
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    const std::string password = root == nullptr ? "" : json_string(root, "password");
    cJSON_Delete(root);
    if (password != dashboard_password_) {
        last_login_failure_ms_ = current;
        return json_error(request, "401 Unauthorized", "invalid password");
    }
    const std::string cookie = std::string(kCookieName) + "=" + session_token_ +
        "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Strict";
    httpd_resp_set_hdr(request, "Set-Cookie", cookie.c_str());
    cJSON *response = cJSON_CreateObject();
    cJSON_AddBoolToObject(response, "authenticated", true);
    cJSON_AddBoolToObject(response, "default_password", dashboard_password_ == kDefaultPassword);
    return send_json(request, response);
}

esp_err_t Dashboard::logout(httpd_req_t *request)
{
    httpd_resp_set_hdr(request, "Set-Cookie",
        "focusmate_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    cJSON *response = cJSON_CreateObject();
    cJSON_AddBoolToObject(response, "authenticated", false);
    return send_json(request, response);
}

esp_err_t Dashboard::change_password(httpd_req_t *request)
{
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    const std::string current = root == nullptr ? "" : json_string(root, "current_password");
    const std::string next = root == nullptr ? "" : json_string(root, "new_password");
    cJSON_Delete(root);
    if (current != dashboard_password_) return json_error(request, "403 Forbidden", "current password is invalid");
    if (!valid_password(next)) return json_error(request, "400 Bad Request", "password must contain 8 to 63 characters");
    const std::string token = hex_token();
    if (!set_nvs_string("dash_pass", next) || !set_nvs_string("dash_token", token))
        return json_error(request, "500 Internal Server Error", "cannot persist password");
    dashboard_password_ = next;
    session_token_ = token;
    return logout(request);
}

esp_err_t Dashboard::camera(httpd_req_t *request)
{
    uint32_t after = 0U;
    uint32_t client = 0U;
    char query[96]{};
    char value[24]{};
    if (httpd_req_get_url_query_str(request, query, sizeof query) == ESP_OK) {
        if (httpd_query_key_value(query, "after", value, sizeof value) == ESP_OK) {
            char *end = nullptr;
            const unsigned long parsed = std::strtoul(value, &end, 10);
            if (end != value && *end == '\0') after = static_cast<uint32_t>(parsed);
        }
        if (httpd_query_key_value(query, "client", value, sizeof value) == ESP_OK) {
            char *end = nullptr;
            const unsigned long parsed = std::strtoul(value, &end, 10);
            if (end != value && *end == '\0') client = static_cast<uint32_t>(parsed);
        }
    }
    if (client == 0U) client = remote_ipv4(request);
    if (!focusmate_frame_broker_claim(client))
        return json_error(request, "409 Conflict", "another dashboard is viewing the camera");
    focusmate_frame_broker_touch(client);
    focusmate_jpeg_view_t view{};
    view.slot = -1;
    if (!focusmate_frame_broker_acquire(after, 1000U, &view)) {
        httpd_resp_set_status(request, "204 No Content");
        return httpd_resp_send(request, nullptr, 0);
    }
    char sequence[16], uptime[24], confidence[16], bbox[96];
    std::snprintf(sequence, sizeof sequence, "%" PRIu32, view.sequence);
    std::snprintf(uptime, sizeof uptime, "%" PRIu64, view.face.observed_uptime_ms);
    std::snprintf(confidence, sizeof confidence, "%" PRIu32 ".%06" PRIu32,
                  view.face.confidence_q6 / 1000000U, view.face.confidence_q6 % 1000000U);
    httpd_resp_set_type(request, "image/jpeg");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    httpd_resp_set_hdr(request, "X-FocusMate-Frame-Sequence", sequence);
    httpd_resp_set_hdr(request, "X-FocusMate-Observed-Uptime-Ms", uptime);
    httpd_resp_set_hdr(request, "X-FocusMate-Face-Detected", view.face.face_detected ? "true" : "false");
    httpd_resp_set_hdr(request, "X-FocusMate-Confidence", confidence);
    if (view.face.face_detected) {
        std::snprintf(bbox, sizeof bbox,
            "%" PRIu32 ".%06" PRIu32 ",%" PRIu32 ".%06" PRIu32
            ",%" PRIu32 ".%06" PRIu32 ",%" PRIu32 ".%06" PRIu32,
            view.face.cx_q6 / 1000000U, view.face.cx_q6 % 1000000U,
            view.face.cy_q6 / 1000000U, view.face.cy_q6 % 1000000U,
            view.face.width_q6 / 1000000U, view.face.width_q6 % 1000000U,
            view.face.height_q6 / 1000000U, view.face.height_q6 % 1000000U);
        httpd_resp_set_hdr(request, "X-FocusMate-Bbox", bbox);
    }
    const esp_err_t result = httpd_resp_send(request, reinterpret_cast<const char *>(view.data), view.size);
    focusmate_frame_broker_release(&view);
    return result;
}

esp_err_t Dashboard::viewer_release(httpd_req_t *request)
{
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    cJSON *item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "client");
    const uint32_t client = cJSON_IsNumber(item) && item->valuedouble > 0.0
        ? static_cast<uint32_t>(item->valuedouble) : 0U;
    cJSON_Delete(root);
    if (client == 0U) return json_error(request, "400 Bad Request", "invalid viewer client id");
    focusmate_frame_broker_release_claim(client);
    cJSON *response = cJSON_CreateObject();
    cJSON_AddBoolToObject(response, "released", true);
    return send_json(request, response);
}

esp_err_t Dashboard::status(httpd_req_t *request)
{
    focusmate_face_result_t face{};
    const bool face_available = focusmate_face_detector_latest(&face);
    focusmate_posture_snapshot_t posture{};
    focusmate_shadow_posture_snapshot(&posture);
    focusmate_posture_thresholds_t posture_thresholds{};
    focusmate_shadow_posture_thresholds(&posture_thresholds);
    focusmate_frame_stats_t frames{};
    focusmate_frame_broker_stats(&frames);
    focusmate_ble_snapshot_t ble{};
    focusmate_ble_snapshot(&ble);
    const WifiStatus wifi = wifi_status();
    const uint64_t current = now_ms();
    if (face_available && previous_inference_at_ms_ != 0U && current > previous_inference_at_ms_ &&
        face.inference_count >= previous_inference_count_) {
        inference_fps_q6_ = static_cast<uint32_t>(
            static_cast<uint64_t>(face.inference_count - previous_inference_count_) * 1000000000ULL /
            (current - previous_inference_at_ms_));
    }
    if (face_available) {
        previous_inference_at_ms_ = current;
        previous_inference_count_ = face.inference_count;
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "schema", 1);
    cJSON_AddNumberToObject(root, "uptime_ms", static_cast<double>(current));
    cJSON *wifi_json = cJSON_AddObjectToObject(root, "wifi");
    cJSON_AddStringToObject(wifi_json, "mode", wifi.station_online ? "APSTA" : "AP");
    cJSON_AddStringToObject(wifi_json, "ip", wifi.ip);
    cJSON_AddStringToObject(wifi_json, "ssid", wifi.ssid);
    cJSON_AddNumberToObject(wifi_json, "rssi", wifi.rssi);
    cJSON_AddStringToObject(wifi_json, "mdns", "focusmate.local");

    cJSON *camera_json = cJSON_AddObjectToObject(root, "camera");
    cJSON_AddBoolToObject(camera_json, "healthy", face_available || face.inference_count > 0U);
    cJSON_AddNumberToObject(camera_json, "width", 320);
    cJSON_AddNumberToObject(camera_json, "height", 240);
    cJSON_AddStringToObject(camera_json, "format", "JPEG");
    cJSON_AddStringToObject(camera_json, "profile", "qvga_direct_jpeg_indoor_auto_v1");
    add_q6(camera_json, "capture_fps", inference_fps_q6_);
    add_q6(camera_json, "jpeg_fps", frames.jpeg_fps_q6);
    cJSON_AddNumberToObject(camera_json, "average_jpeg_bytes", frames.average_jpeg_bytes);
    cJSON_AddBoolToObject(camera_json, "client_connected", frames.client_connected);
    cJSON_AddNumberToObject(camera_json, "encode_drops", frames.encode_drops);
    cJSON_AddNumberToObject(camera_json, "errors", frames.encode_errors);

    cJSON *ble_json = cJSON_AddObjectToObject(root, "ble");
    cJSON_AddBoolToObject(ble_json, "connected", ble.connected);
    cJSON_AddBoolToObject(ble_json, "subscribed", ble.subscribed);
    cJSON_AddNumberToObject(ble_json, "mtu", ble.mtu);
    cJSON_AddNumberToObject(ble_json, "rate_hz", static_cast<double>(ble.rate_dhz) / 10.0);
    cJSON_AddNumberToObject(ble_json, "observations", ble.observations);
    cJSON_AddNumberToObject(ble_json, "notification_attempts", ble.notification_attempts);
    cJSON_AddNumberToObject(ble_json, "notification_failures", ble.notification_failures);

    cJSON *face_json = cJSON_AddObjectToObject(root, "face");
    cJSON_AddBoolToObject(face_json, "available", face_available);
    cJSON_AddBoolToObject(face_json, "detected", face_available && face.face_detected);
    if (face_available && face.face_detected) {
        add_q6(face_json, "cx", face.cx_q6); add_q6(face_json, "cy", face.cy_q6);
        add_q6(face_json, "width", face.width_q6); add_q6(face_json, "height", face.height_q6);
        add_q6(face_json, "area", (static_cast<uint64_t>(face.width_q6) * face.height_q6 + 500000U) / 1000000U);
        add_q6(face_json, "confidence", face.confidence_q6);
    }
    cJSON_AddNumberToObject(face_json, "inference_ms", face.inference_ms);
    cJSON_AddNumberToObject(face_json, "inference_count", face.inference_count);
    cJSON_AddNumberToObject(face_json, "observed_uptime_ms", static_cast<double>(face.observed_uptime_ms));
    cJSON_AddNumberToObject(face_json, "age_ms", face_available && current >= face.observed_uptime_ms
        ? static_cast<double>(current - face.observed_uptime_ms) : -1.0);

    cJSON *posture_json = cJSON_AddObjectToObject(root, "posture");
    cJSON_AddStringToObject(posture_json, "source", "esp_web_geometry_v2_shadow");
    cJSON_AddBoolToObject(posture_json, "calibrated", posture.calibrated);
    cJSON_AddBoolToObject(posture_json, "calibration_active", posture.calibration_active);
    cJSON_AddNumberToObject(posture_json, "calibration_progress", posture.calibration_progress);
    cJSON_AddStringToObject(posture_json, "calibration_reason", posture.calibration_reason);
    cJSON_AddStringToObject(posture_json, "raw_state", focusmate_posture_state_name(posture.raw_state));
    cJSON_AddStringToObject(posture_json, "state", focusmate_posture_state_name(posture.state));
    add_q6(posture_json, "raw_confidence", posture.raw_confidence_q6);
    add_q6(posture_json, "confidence", posture.confidence_q6);
    cJSON_AddNumberToObject(posture_json, "stable_ms", static_cast<double>(posture.stable_ms));
    add_q6(posture_json, "dx", posture.dx_q6); add_q6(posture_json, "dy", posture.dy_q6);
    add_q6(posture_json, "area_ratio", posture.area_ratio_q6);
    cJSON *baseline = cJSON_AddObjectToObject(posture_json, "baseline");
    add_q6(baseline, "cx", posture.baseline_cx_q6);
    add_q6(baseline, "cy", posture.baseline_cy_q6);
    add_q6(baseline, "area", posture.baseline_area_q6);
    cJSON_AddNumberToObject(baseline, "revision", posture_thresholds.baseline_revision);
    cJSON *thresholds = cJSON_AddObjectToObject(posture_json, "thresholds");
    add_q6(thresholds, "calibration_confidence", posture_thresholds.calibration_min_confidence_q6);
    add_q6(thresholds, "live_confidence", posture_thresholds.live_min_confidence_q6);
    add_q6(thresholds, "lean_delta", posture_thresholds.lean_delta_q6);
    add_q6(thresholds, "head_down_delta", posture_thresholds.head_down_delta_q6);
    add_q6(thresholds, "slumped_delta", posture_thresholds.slumped_delta_q6);
    add_q6(thresholds, "too_close_ratio", posture_thresholds.too_close_ratio_q6);
    cJSON_AddNumberToObject(thresholds, "slumped_ms", static_cast<double>(posture_thresholds.slumped_minimum_ms));
    cJSON_AddNumberToObject(thresholds, "stable_samples", posture_thresholds.stable_samples);
    cJSON_AddNumberToObject(thresholds, "baseline_revision", posture_thresholds.baseline_revision);

    cJSON *memory = cJSON_AddObjectToObject(root, "memory");
    cJSON_AddNumberToObject(memory, "free_internal_heap", heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
    cJSON_AddNumberToObject(memory, "minimum_internal_heap", heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
    cJSON_AddNumberToObject(memory, "free_psram", heap_caps_get_free_size(MALLOC_CAP_SPIRAM));
    cJSON *privacy = cJSON_AddObjectToObject(root, "privacy");
    cJSON_AddBoolToObject(privacy, "frame_in_ram_only", true);
    cJSON_AddBoolToObject(privacy, "storage", false);
    cJSON_AddBoolToObject(privacy, "cloud", false);
    cJSON_AddBoolToObject(privacy, "shadow_only", true);
    cJSON_AddBoolToObject(root, "default_password", dashboard_password_ == kDefaultPassword);
    return send_json(request, root);
}

esp_err_t Dashboard::posture(httpd_req_t *request, bool calibrate)
{
    if (calibrate) focusmate_shadow_posture_start_calibration();
    else focusmate_shadow_posture_reset();
    focusmate_posture_snapshot_t posture{};
    focusmate_shadow_posture_snapshot(&posture);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "calibration_active", posture.calibration_active);
    cJSON_AddBoolToObject(root, "calibrated", posture.calibrated);
    cJSON_AddStringToObject(root, "calibration_reason", posture.calibration_reason);
    return send_json(request, root);
}

esp_err_t Dashboard::wifi_scan(httpd_req_t *request)
{
    wifi_scan_config_t config{};
    if (esp_wifi_scan_start(&config, true) != ESP_OK)
        return json_error(request, "503 Service Unavailable", "Wi-Fi scan failed");
    uint16_t count = 0;
    esp_wifi_scan_get_ap_num(&count);
    count = std::min<uint16_t>(count, 24U);
    std::vector<wifi_ap_record_t> records(count);
    if (count > 0U && esp_wifi_scan_get_ap_records(&count, records.data()) != ESP_OK)
        return json_error(request, "503 Service Unavailable", "Wi-Fi results unavailable");
    cJSON *root = cJSON_CreateObject();
    cJSON *networks = cJSON_AddArrayToObject(root, "networks");
    std::vector<std::string> seen;
    for (uint16_t index = 0; index < count; ++index) {
        const std::string ssid(reinterpret_cast<const char *>(records[index].ssid));
        if (ssid.empty() || std::find(seen.begin(), seen.end(), ssid) != seen.end()) continue;
        seen.push_back(ssid);
        cJSON *network = cJSON_CreateObject();
        cJSON_AddStringToObject(network, "ssid", ssid.c_str());
        cJSON_AddNumberToObject(network, "rssi", records[index].rssi);
        cJSON_AddBoolToObject(network, "secured", records[index].authmode != WIFI_AUTH_OPEN);
        cJSON_AddItemToArray(networks, network);
    }
    return send_json(request, root);
}

esp_err_t Dashboard::wifi_connect(httpd_req_t *request)
{
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    const std::string ssid = root == nullptr ? "" : json_string(root, "ssid");
    const std::string password = root == nullptr ? "" : json_string(root, "password");
    cJSON_Delete(root);
    if (ssid.empty() || ssid.size() > 32U || password.size() > 63U)
        return json_error(request, "400 Bad Request", "invalid Wi-Fi credentials");
    if (!set_nvs_string("pending_ssid", ssid) || !set_nvs_string("pending_pass", password))
        return json_error(request, "500 Internal Server Error", "cannot stage Wi-Fi credentials");
    xSemaphoreTake(mutex_, portMAX_DELAY);
    pending_active_ = true;
    pending_connection_started_ = false;
    pending_deadline_ms_ = now_ms() + 20000U;
    pending_switch_requested_ = true;
    pending_switch_at_ms_ = now_ms() + 500U;
    xSemaphoreGive(mutex_);
    cJSON *response = cJSON_CreateObject();
    cJSON_AddBoolToObject(response, "testing", true);
    cJSON_AddNumberToObject(response, "rollback_after_ms", 20000);
    return send_json(request, response);
}

esp_err_t Dashboard::wifi_reset(httpd_req_t *request)
{
    if (!erase_nvs_keys({"wifi_ssid", "wifi_pass", "pending_ssid", "pending_pass"}))
        return json_error(request, "500 Internal Server Error", "cannot reset Wi-Fi");
    nvs_handle_t legacy;
    if (nvs_open(kLegacyNvsNamespace, NVS_READWRITE, &legacy) == ESP_OK) {
        nvs_erase_key(legacy, "wifi_ssid");
        nvs_erase_key(legacy, "wifi_pass");
        nvs_commit(legacy);
        nvs_close(legacy);
    }
    esp_wifi_disconnect();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    wifi_ = WifiStatus{};
    pending_active_ = false;
    pending_connection_started_ = false;
    pending_switch_requested_ = false;
    pending_deadline_ms_ = 0;
    pending_switch_at_ms_ = 0;
    xSemaphoreGive(mutex_);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "reset", true);
    return send_json(request, root);
}

esp_err_t Dashboard::ap_password(httpd_req_t *request)
{
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    const std::string password = root == nullptr ? "" : json_string(root, "password");
    cJSON_Delete(root);
    if (!valid_password(password)) return json_error(request, "400 Bad Request", "password must contain 8 to 63 characters");
    if (!set_nvs_string("ap_pass", password))
        return json_error(request, "500 Internal Server Error", "cannot persist AP password");
    wifi_config_t ap{};
    if (esp_wifi_get_config(WIFI_IF_AP, &ap) == ESP_OK) {
        std::memset(ap.ap.password, 0, sizeof ap.ap.password);
        std::strncpy(reinterpret_cast<char *>(ap.ap.password), password.c_str(), sizeof(ap.ap.password) - 1U);
        ap.ap.authmode = WIFI_AUTH_WPA2_PSK;
        esp_wifi_set_config(WIFI_IF_AP, &ap);
    }
    cJSON *response = cJSON_CreateObject();
    cJSON_AddBoolToObject(response, "saved", true);
    return send_json(request, response);
}

void Dashboard::event_thunk(void *argument, esp_event_base_t base, int32_t id, void *data)
{
    static_cast<Dashboard *>(argument)->on_event(base, id, data);
}

void Dashboard::on_event(esp_event_base_t base, int32_t id, void *data)
{
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        const auto *event = static_cast<ip_event_got_ip_t *>(data);
        char ip[16];
        std::snprintf(ip, sizeof ip, IPSTR, IP2STR(&event->ip_info.ip));
        wifi_ap_record_t record{};
        xSemaphoreTake(mutex_, portMAX_DELAY);
        station_associated_ = true;
        wifi_.station_online = true;
        std::strncpy(wifi_.ip, ip, sizeof(wifi_.ip) - 1U);
        wifi_config_t station{};
        if (esp_wifi_get_config(WIFI_IF_STA, &station) == ESP_OK)
            std::strncpy(wifi_.ssid, reinterpret_cast<const char *>(station.sta.ssid), sizeof(wifi_.ssid) - 1U);
        wifi_.rssi = esp_wifi_sta_get_ap_info(&record) == ESP_OK ? record.rssi : 0;
        xSemaphoreGive(mutex_);
        xSemaphoreTake(mutex_, portMAX_DELAY);
        const bool tested_pending = pending_active_ && pending_connection_started_;
        xSemaphoreGive(mutex_);
        if (tested_pending) promote_pending();
        ESP_LOGI(kTag, "LAN ready http://%s (%s.local)", ip, kMdnsHost);
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_CONNECTED) {
        xSemaphoreTake(mutex_, portMAX_DELAY);
        station_associated_ = true;
        xSemaphoreGive(mutex_);
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xSemaphoreTake(mutex_, portMAX_DELAY);
        station_associated_ = false;
        wifi_.station_online = false;
        std::strncpy(wifi_.ip, "192.168.4.1", sizeof(wifi_.ip));
        std::strncpy(wifi_.ssid, kApSsid, sizeof(wifi_.ssid));
        wifi_.rssi = 0;
        xSemaphoreGive(mutex_);
    }
}

void Dashboard::network_task_thunk(void *argument)
{
    static_cast<Dashboard *>(argument)->network_task();
}

void Dashboard::network_task()
{
    while (true) {
        const uint64_t current = now_ms();
        xSemaphoreTake(mutex_, portMAX_DELAY);
        const bool pending = pending_active_;
        const bool switch_requested = pending_switch_requested_;
        const uint64_t switch_at = pending_switch_at_ms_;
        const bool online = wifi_.station_online;
        const bool associated = station_associated_;
        const uint64_t deadline = pending_deadline_ms_;
        const bool reconnect_due = current - last_reconnect_ms_ >= 5000U;
        if (reconnect_due) last_reconnect_ms_ = current;
        if (switch_requested && current >= switch_at) pending_switch_requested_ = false;
        xSemaphoreGive(mutex_);
        if (switch_requested && current >= switch_at) {
            const std::string ssid = nvs_string("pending_ssid");
            const std::string password = nvs_string("pending_pass");
            xSemaphoreTake(mutex_, portMAX_DELAY);
            pending_connection_started_ = !ssid.empty();
            xSemaphoreGive(mutex_);
            if (ssid.empty() || !configure_station(ssid, password)) rollback_pending();
        } else if (pending && !online && current >= deadline) {
            rollback_pending();
        } else if (!online && !associated && reconnect_due) {
            esp_wifi_connect();
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

std::string Dashboard::nvs_string(const char *key) const
{
    const auto read = [key](const char *name_space) {
        nvs_handle_t handle;
        if (nvs_open(name_space, NVS_READONLY, &handle) != ESP_OK) return std::string();
        size_t size = 0;
        if (nvs_get_str(handle, key, nullptr, &size) != ESP_OK || size == 0U) {
            nvs_close(handle);
            return std::string();
        }
        std::vector<char> value(size);
        const esp_err_t result = nvs_get_str(handle, key, value.data(), &size);
        nvs_close(handle);
        return result == ESP_OK ? std::string(value.data()) : std::string();
    };
    std::string value = read(kNvsNamespace);
    if (!value.empty()) return value;
    if (std::strcmp(key, "wifi_ssid") == 0 || std::strcmp(key, "wifi_pass") == 0 ||
        std::strcmp(key, "ap_pass") == 0) {
        value = read(kLegacyNvsNamespace);
    }
    return value;
}

bool Dashboard::set_nvs_string(const char *key, const std::string &value)
{
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    const bool ok = nvs_set_str(handle, key, value.c_str()) == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return ok;
}

bool Dashboard::erase_nvs_keys(std::initializer_list<const char *> keys)
{
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    for (const char *key : keys) nvs_erase_key(handle, key);
    const bool ok = nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return ok;
}

bool Dashboard::configure_station(const std::string &ssid, const std::string &password)
{
    wifi_config_t station{};
    std::strncpy(reinterpret_cast<char *>(station.sta.ssid), ssid.c_str(), sizeof(station.sta.ssid) - 1U);
    std::strncpy(reinterpret_cast<char *>(station.sta.password), password.c_str(), sizeof(station.sta.password) - 1U);
    station.sta.threshold.authmode = WIFI_AUTH_OPEN;
    station.sta.pmf_cfg.capable = true;
    station.sta.pmf_cfg.required = false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    station_associated_ = false;
    xSemaphoreGive(mutex_);
    esp_wifi_disconnect();
    if (esp_wifi_set_config(WIFI_IF_STA, &station) != ESP_OK) return false;
    const esp_err_t result = esp_wifi_connect();
    return result == ESP_OK || result == ESP_ERR_WIFI_CONN;
}

bool Dashboard::promote_pending()
{
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool pending = pending_active_;
    xSemaphoreGive(mutex_);
    if (!pending) return true;
    const std::string ssid = nvs_string("pending_ssid");
    const std::string password = nvs_string("pending_pass");
    if (ssid.empty()) return false;
    nvs_handle_t handle;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    const bool ok = nvs_set_str(handle, "wifi_ssid", ssid.c_str()) == ESP_OK &&
        nvs_set_str(handle, "wifi_pass", password.c_str()) == ESP_OK &&
        nvs_erase_key(handle, "pending_ssid") == ESP_OK &&
        nvs_erase_key(handle, "pending_pass") == ESP_OK &&
        nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    if (ok) {
        xSemaphoreTake(mutex_, portMAX_DELAY);
        pending_active_ = false;
        pending_connection_started_ = false;
        pending_switch_requested_ = false;
        pending_deadline_ms_ = 0;
        pending_switch_at_ms_ = 0;
        xSemaphoreGive(mutex_);
        ESP_LOGI(kTag, "promoted tested Wi-Fi credentials for %s", ssid.c_str());
    }
    return ok;
}

void Dashboard::rollback_pending()
{
    const std::string active_ssid = nvs_string("wifi_ssid");
    const std::string active_password = nvs_string("wifi_pass");
    erase_nvs_keys({"pending_ssid", "pending_pass"});
    xSemaphoreTake(mutex_, portMAX_DELAY);
    pending_active_ = false;
    pending_connection_started_ = false;
    pending_switch_requested_ = false;
    pending_deadline_ms_ = 0;
    pending_switch_at_ms_ = 0;
    xSemaphoreGive(mutex_);
    if (!active_ssid.empty()) configure_station(active_ssid, active_password);
    ESP_LOGW(kTag, "pending Wi-Fi timed out; restored previous network");
}

WifiStatus Dashboard::wifi_status()
{
    xSemaphoreTake(mutex_, portMAX_DELAY);
    WifiStatus value = wifi_;
    xSemaphoreGive(mutex_);
    if (value.station_online) {
        wifi_ap_record_t record{};
        if (esp_wifi_sta_get_ap_info(&record) == ESP_OK) value.rssi = record.rssi;
    }
    return value;
}

extern "C" bool focusmate_dashboard_start(void)
{
    return dashboard.start();
}
