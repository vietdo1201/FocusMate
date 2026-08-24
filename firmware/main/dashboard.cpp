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
#include "focusmate_dns.h"
#include "frame_broker.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"
#include "mdns.h"
#include "nvs.h"
#include "shadow_posture.h"
#include "web_assets.h"
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
constexpr char kApSsid[] = "FocusMate-Setup";
constexpr char kMdnsHost[] = "focusmate";
constexpr char kCanonicalHostname[] = "focusmate.local";
constexpr char kCanonicalUrl[] = "http://focusmate.local/";
// A failed STA connection performs a channel scan. Retrying every few seconds
// makes the co-hosted setup AP difficult to discover, especially when the
// saved network is no longer nearby. Keep a generous setup window and stop
// background STA retries while somebody is connected to the setup AP.
constexpr uint32_t kStationReconnectIntervalMs = 30000U;

struct WifiStatus {
    bool station_online = false;
    char ip[16] = "192.168.4.1";
    char ssid[33] = "FocusMate-Setup";
    int rssi = 0;
};

struct YawnSyncSnapshot {
    uint32_t sequence = 0;
    uint32_t client = 0;
    uint32_t total = 0;
    uint32_t window = 0;
    uint64_t observed_uptime_ms = 0;
};

class Dashboard {
public:
    bool start();
    bool frame_access_snapshot(uint8_t ipv4[4], uint16_t *port, uint8_t token[16], uint8_t *flags);
    static esp_err_t dispatch(httpd_req_t *request);

private:
    esp_err_t handle(httpd_req_t *request);
    bool initialize_identity();
    bool initialize_network();
    bool initialize_server();
    esp_err_t root(httpd_req_t *request);
    esp_err_t camera(httpd_req_t *request);
    esp_err_t watch_camera(httpd_req_t *request);
    esp_err_t yawn_event(httpd_req_t *request);
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
    bool watch_authenticated(httpd_req_t *request) const;
    YawnSyncSnapshot yawn_sync_snapshot();

    SemaphoreHandle_t mutex_ = nullptr;
    httpd_handle_t server_ = nullptr;
    WifiStatus wifi_{};
    std::array<uint8_t, 16> watch_token_{};
    uint32_t previous_inference_count_ = 0;
    uint64_t previous_inference_at_ms_ = 0;
    uint32_t inference_fps_q6_ = 0;
    bool pending_active_ = false;
    bool pending_connection_started_ = false;
    bool pending_switch_requested_ = false;
    bool station_associated_ = false;
    uint8_t ap_client_count_ = 0U;
    uint64_t pending_deadline_ms_ = 0;
    uint64_t pending_switch_at_ms_ = 0;
    uint64_t last_reconnect_ms_ = 0;
    YawnSyncSnapshot yawn_sync_{};
    uint32_t yawn_event_id_ = 0;
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
    if (socket < 0 || getpeername(socket, reinterpret_cast<sockaddr *>(&address), &length) != 0)
        return 0U;
    if (address.ss_family == AF_INET)
        return reinterpret_cast<sockaddr_in *>(&address)->sin_addr.s_addr;
    if (address.ss_family == AF_INET6) {
        // ESP-IDF may expose an IPv4 peer accepted by its dual-stack listen
        // socket as ::ffff:a.b.c.d. The Watch always calls the IPv4 address
        // delivered over encrypted BLE, so accept only this mapped form and
        // keep rejecting arbitrary IPv6 peers as a frame-lease identity.
        const auto *mapped = reinterpret_cast<const uint8_t *>(
            &reinterpret_cast<const sockaddr_in6 *>(&address)->sin6_addr);
        const bool prefix = std::all_of(mapped, mapped + 10, [](uint8_t value) { return value == 0U; }) &&
            mapped[10] == 0xffU && mapped[11] == 0xffU;
        if (prefix) {
            uint32_t result = 0U;
            std::memcpy(&result, mapped + 12, sizeof result);
            return result;
        }
    }
    return 0U;
}

bool valid_password(const std::string &value)
{
    return value.size() >= 8U && value.size() <= 63U;
}

uint16_t q6_to_q16(uint32_t value)
{
    return static_cast<uint16_t>(std::min<uint64_t>(65535U,
        (static_cast<uint64_t>(value) * 65535U + 500000U) / 1000000U));
}

std::string face_meta_header(const focusmate_face_meta_v1_t &meta)
{
    std::array<uint16_t, 16> values{};
    values[0] = (meta.flags & FOCUSMATE_FACE_META_V1_FLAG_FACE_DETECTED) != 0U ? 1U : 0U;
    values[1] = q6_to_q16(meta.confidence_q6);
    values[2] = q6_to_q16(meta.cx_q6);
    values[3] = q6_to_q16(meta.cy_q6);
    values[4] = q6_to_q16(meta.width_q6);
    values[5] = q6_to_q16(meta.height_q6);
    for (size_t index = 0; index < FOCUSMATE_FACE_KEYPOINT_COUNT; ++index) {
        values[6U + index * 2U] = q6_to_q16(meta.keypoints[index].x_q6);
        values[7U + index * 2U] = q6_to_q16(meta.keypoints[index].y_q6);
    }
    std::array<uint8_t, 32> bytes{};
    for (size_t index = 0; index < values.size(); ++index) {
        bytes[index * 2U] = static_cast<uint8_t>(values[index] & 0xffU);
        bytes[index * 2U + 1U] = static_cast<uint8_t>(values[index] >> 8U);
    }
    constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string encoded;
    encoded.reserve(43U);
    uint32_t accumulator = 0U;
    unsigned bits = 0U;
    for (uint8_t byte : bytes) {
        accumulator = (accumulator << 8U) | byte;
        bits += 8U;
        while (bits >= 6U) {
            bits -= 6U;
            encoded.push_back(alphabet[(accumulator >> bits) & 0x3fU]);
        }
    }
    if (bits > 0U) encoded.push_back(alphabet[(accumulator << (6U - bits)) & 0x3fU]);
    return encoded;
}

esp_err_t send_jpeg(httpd_req_t *request, const focusmate_jpeg_view_t &view,
                    const YawnSyncSnapshot *yawn_sync = nullptr)
{
    char sequence[16], uptime[24], confidence[16], bbox[96];
    std::snprintf(sequence, sizeof sequence, "%" PRIu32, view.sequence);
    std::snprintf(uptime, sizeof uptime, "%" PRIu64, view.face.observed_uptime_ms);
    std::snprintf(confidence, sizeof confidence, "%" PRIu32 ".%06" PRIu32,
                  view.face.confidence_q6 / 1000000U, view.face.confidence_q6 % 1000000U);
    httpd_resp_set_type(request, "image/jpeg");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    httpd_resp_set_hdr(request, "X-Content-Type-Options", "nosniff");
    httpd_resp_set_hdr(request, "X-FocusMate-Frame-Sequence", sequence);
    httpd_resp_set_hdr(request, "X-FocusMate-Observed-Uptime-Ms", uptime);
    httpd_resp_set_hdr(request, "X-FocusMate-Face-Detected", view.face.face_detected ? "true" : "false");
    httpd_resp_set_hdr(request, "X-FocusMate-Confidence", confidence);
    const std::string encoded_meta = face_meta_header(view.meta);
    httpd_resp_set_hdr(request, "X-FocusMate-Face-Meta-V1", encoded_meta.c_str());
    char yawn_sequence[16], yawn_client[16], yawn_total[16], yawn_window[16], yawn_uptime[24];
    if (yawn_sync != nullptr && yawn_sync->sequence != 0U) {
        std::snprintf(yawn_sequence, sizeof yawn_sequence, "%" PRIu32, yawn_sync->sequence);
        std::snprintf(yawn_client, sizeof yawn_client, "%" PRIu32, yawn_sync->client);
        std::snprintf(yawn_total, sizeof yawn_total, "%" PRIu32, yawn_sync->total);
        std::snprintf(yawn_window, sizeof yawn_window, "%" PRIu32, yawn_sync->window);
        std::snprintf(yawn_uptime, sizeof yawn_uptime, "%" PRIu64, yawn_sync->observed_uptime_ms);
        httpd_resp_set_hdr(request, "X-FocusMate-Yawn-Sequence", yawn_sequence);
        httpd_resp_set_hdr(request, "X-FocusMate-Yawn-Client", yawn_client);
        httpd_resp_set_hdr(request, "X-FocusMate-Yawn-Total", yawn_total);
        httpd_resp_set_hdr(request, "X-FocusMate-Yawn-Window", yawn_window);
        httpd_resp_set_hdr(request, "X-FocusMate-Yawn-Observed-Uptime-Ms", yawn_uptime);
    }
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
    return httpd_resp_send(request, reinterpret_cast<const char *>(view.data), view.size);
}

} // namespace

bool Dashboard::start()
{
    mutex_ = xSemaphoreCreateMutex();
    if (mutex_ == nullptr || !initialize_identity()) return false;
    focusmate_web_assets_mount();
    if (!initialize_network()) return false;
    if (!initialize_server()) return false;
    ESP_LOGI(kTag, "dashboard ready at http://%s.local and http://192.168.4.1", kMdnsHost);
    return true;
}

bool Dashboard::initialize_identity()
{
    esp_fill_random(watch_token_.data(), watch_token_.size());
    return true;
}

bool request_host_is_ipv4(httpd_req_t *request)
{
    const size_t length = httpd_req_get_hdr_value_len(request, "Host");
    if (length == 0U || length >= 64U) return false;
    std::array<char, 64> host{};
    if (httpd_req_get_hdr_value_str(request, "Host", host.data(), host.size()) != ESP_OK)
        return false;
    if (char *port = std::strchr(host.data(), ':'); port != nullptr) *port = '\0';
    in_addr address{};
    return inet_pton(AF_INET, host.data(), &address) == 1;
}

bool Dashboard::initialize_network()
{
    esp_err_t result = esp_netif_init();
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) return false;
    result = esp_event_loop_create_default();
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) return false;
    esp_netif_t *station_netif = esp_netif_create_default_wifi_sta();
    esp_netif_t *ap_netif = esp_netif_create_default_wifi_ap();
    if (station_netif == nullptr || ap_netif == nullptr) return false;
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
    if (esp_wifi_set_config(WIFI_IF_AP, &ap) != ESP_OK) return false;

    // Register mDNS before AP_START so the responder sees the AP lifecycle
    // event. Explicitly enabling the AP afterwards also covers warm starts.
    bool mdns_ready = false;
    if (mdns_init() == ESP_OK) {
        mdns_ready = true;
        mdns_hostname_set(kMdnsHost);
        mdns_instance_name_set("FocusMate Shadow Dashboard");
        mdns_service_add(nullptr, "_http", "_tcp", 80, nullptr, 0);
    }
    if (esp_wifi_start() != ESP_OK) return false;
    if (mdns_ready && mdns_netif_action(ap_netif, MDNS_EVENT_ENABLE_IP4) != ESP_OK)
        ESP_LOGW(kTag, "could not explicitly enable mDNS on setup AP");

    // ESP-IDF's AP DHCP server advertises its own address as DNS. This small
    // unicast responder is needed because some Windows clients do not use
    // multicast .local resolution on Wi-Fi networks without Internet.
    if (!focusmate_dns_start(kCanonicalHostname, "WIFI_AP_DEF"))
        ESP_LOGE(kTag, "could not start setup AP DNS responder");

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

    if (xTaskCreate(network_task_thunk, "focusmate-network", 4096, this, 3, nullptr) != pdPASS)
        return false;
    return true;
}

bool Dashboard::initialize_server()
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    config.max_uri_handlers = 16;
    config.max_resp_headers = 16;
    config.max_open_sockets = 6;
    config.lru_purge_enable = true;
    config.stack_size = 10240;
    config.uri_match_fn = httpd_uri_match_wildcard;
    if (httpd_start(&server_, &config) != ESP_OK) return false;
    const httpd_uri_t routes[] = {
        {"/", HTTP_GET, dispatch, this},
        {"/assets/*", HTTP_GET, dispatch, this},
        {"/camera.jpg", HTTP_GET, dispatch, this},
        {"/api/watch/frame", HTTP_GET, dispatch, this},
        {"/api/yawn/event", HTTP_POST, dispatch, this},
        {"/api/viewer/release", HTTP_POST, dispatch, this},
        {"/api/status", HTTP_GET, dispatch, this},
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
    if (uri.rfind("/assets/", 0U) == 0U) return focusmate_web_assets_serve(request, uri.c_str());
    if (uri == "/api/watch/frame") return watch_camera(request);
    if (uri == "/api/yawn/event") return yawn_event(request);
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

bool Dashboard::watch_authenticated(httpd_req_t *request) const
{
    constexpr char prefix[] = "FocusMate ";
    constexpr char alphabet[] = "0123456789abcdef";
    const size_t length = httpd_req_get_hdr_value_len(request, "Authorization");
    if (length != sizeof(prefix) - 1U + watch_token_.size() * 2U) return false;
    std::array<char, 64> supplied{};
    if (httpd_req_get_hdr_value_str(request, "Authorization", supplied.data(), supplied.size()) != ESP_OK)
        return false;
    unsigned difference = 0U;
    for (size_t index = 0; index < sizeof(prefix) - 1U; ++index)
        difference |= static_cast<unsigned>(static_cast<uint8_t>(supplied[index]) ^ static_cast<uint8_t>(prefix[index]));
    for (size_t index = 0; index < watch_token_.size(); ++index) {
        difference |= static_cast<unsigned>(static_cast<uint8_t>(supplied[sizeof(prefix) - 1U + index * 2U]) ^
            static_cast<uint8_t>(alphabet[watch_token_[index] >> 4U]));
        difference |= static_cast<unsigned>(static_cast<uint8_t>(supplied[sizeof(prefix) + index * 2U]) ^
            static_cast<uint8_t>(alphabet[watch_token_[index] & 0x0fU]));
    }
    return difference == 0U;
}

esp_err_t Dashboard::root(httpd_req_t *request)
{
    // One hostname means one browser origin/localStorage/cache regardless of
    // whether the ESP is reached through the setup AP or the home/office LAN.
    if (request_host_is_ipv4(request)) {
        httpd_resp_set_status(request, "302 Found");
        httpd_resp_set_hdr(request, "Location", kCanonicalUrl);
        httpd_resp_set_hdr(request, "Cache-Control", "no-store");
        return httpd_resp_send(request, nullptr, 0);
    }
    httpd_resp_set_type(request, "text/html; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    httpd_resp_set_hdr(request, "Content-Security-Policy",
        "default-src 'self'; connect-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; "
        "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; worker-src 'self'");
    return httpd_resp_send(request, reinterpret_cast<const char *>(dashboard_html_start),
                           dashboard_html_end - dashboard_html_start);
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
    // ESP-IDF's HTTP server dispatches handlers serially. Long-polling here
    // lets one browser request starve the Watch endpoint. Latest-frame-wins
    // clients already retry, so respond immediately with 204 until a newer
    // frame is available.
    if (!focusmate_frame_broker_acquire(after, 0U, &view)) {
        httpd_resp_set_status(request, "204 No Content");
        return httpd_resp_send(request, nullptr, 0);
    }
    const esp_err_t result = send_jpeg(request, view);
    focusmate_frame_broker_release(&view);
    return result;
}

esp_err_t Dashboard::watch_camera(httpd_req_t *request)
{
    if (!watch_authenticated(request))
        return json_error(request, "401 Unauthorized", "invalid local frame credential");
    uint32_t after = 0U;
    char query[48]{};
    char value[16]{};
    if (httpd_req_get_url_query_str(request, query, sizeof query) == ESP_OK &&
        httpd_query_key_value(query, "after", value, sizeof value) == ESP_OK) {
        char *end = nullptr;
        const unsigned long parsed = std::strtoul(value, &end, 10);
        if (end != value && *end == '\0') after = static_cast<uint32_t>(parsed);
    }
    const uint32_t client = remote_ipv4(request);
    if (client == 0U) return json_error(request, "400 Bad Request", "IPv4 LAN client required");
    if (!focusmate_frame_broker_claim_consumer(FOCUSMATE_FRAME_CONSUMER_WATCH, client))
        return json_error(request, "409 Conflict", "another watch is viewing the camera");
    focusmate_frame_broker_touch_consumer(FOCUSMATE_FRAME_CONSUMER_WATCH, client);
    focusmate_jpeg_view_t view{};
    view.slot = -1;
    if (!focusmate_frame_broker_try_acquire_consumer(
            FOCUSMATE_FRAME_CONSUMER_WATCH, client, after, &view)) {
        httpd_resp_set_status(request, "204 No Content");
        return httpd_resp_send(request, nullptr, 0);
    }
    const YawnSyncSnapshot yawn_sync = yawn_sync_snapshot();
    const esp_err_t result = send_jpeg(request, view, &yawn_sync);
    focusmate_frame_broker_release(&view);
    return result;
}

esp_err_t Dashboard::yawn_event(httpd_req_t *request)
{
    const std::string body = read_body(request);
    cJSON *root = cJSON_ParseWithLength(body.data(), body.size());
    cJSON *client_item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "client");
    cJSON *event_item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "event");
    cJSON *total_item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "total_count");
    cJSON *window_item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "window_count");
    cJSON *uptime_item = root == nullptr ? nullptr : cJSON_GetObjectItemCaseSensitive(root, "observed_uptime_ms");
    const bool valid = cJSON_IsNumber(client_item) && client_item->valuedouble >= 1.0 &&
        client_item->valuedouble <= UINT32_MAX && cJSON_IsNumber(event_item) &&
        event_item->valuedouble >= 0.0 && event_item->valuedouble <= UINT32_MAX &&
        cJSON_IsNumber(total_item) && total_item->valuedouble >= 0.0 && total_item->valuedouble <= 1000000.0 &&
        cJSON_IsNumber(window_item) && window_item->valuedouble >= 0.0 && window_item->valuedouble <= 1000.0 &&
        cJSON_IsNumber(uptime_item) && uptime_item->valuedouble >= 0.0;
    if (!valid) {
        cJSON_Delete(root);
        return json_error(request, "400 Bad Request", "invalid yawn sync");
    }
    const uint32_t client = static_cast<uint32_t>(client_item->valuedouble);
    const uint32_t event = static_cast<uint32_t>(event_item->valuedouble);
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool changed = yawn_sync_.client != client || yawn_event_id_ != event ||
        yawn_sync_.total != static_cast<uint32_t>(total_item->valuedouble) ||
        yawn_sync_.window != static_cast<uint32_t>(window_item->valuedouble);
    if (changed) {
        ++yawn_sync_.sequence;
        if (yawn_sync_.sequence == 0U) ++yawn_sync_.sequence;
        yawn_sync_.client = client;
        yawn_event_id_ = event;
        yawn_sync_.total = static_cast<uint32_t>(total_item->valuedouble);
        yawn_sync_.window = static_cast<uint32_t>(window_item->valuedouble);
        yawn_sync_.observed_uptime_ms = static_cast<uint64_t>(uptime_item->valuedouble);
    }
    const YawnSyncSnapshot snapshot = yawn_sync_;
    xSemaphoreGive(mutex_);
    cJSON_Delete(root);
    cJSON *response = cJSON_CreateObject();
    cJSON_AddNumberToObject(response, "sequence", snapshot.sequence);
    cJSON_AddNumberToObject(response, "total_count", snapshot.total);
    cJSON_AddNumberToObject(response, "window_count", snapshot.window);
    return send_json(request, response);
}

YawnSyncSnapshot Dashboard::yawn_sync_snapshot()
{
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const YawnSyncSnapshot snapshot = yawn_sync_;
    xSemaphoreGive(mutex_);
    return snapshot;
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
    cJSON_AddBoolToObject(camera_json, "browser_connected", frames.browser_connected);
    cJSON_AddBoolToObject(camera_json, "watch_connected", frames.watch_connected);
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
        cJSON *keypoints = cJSON_AddArrayToObject(face_json, "keypoints");
        for (uint8_t index = 0U; index < face.keypoint_count; ++index) {
            cJSON *point = cJSON_CreateObject();
            add_q6(point, "x", face.keypoints[index].x_q6);
            add_q6(point, "y", face.keypoints[index].y_q6);
            cJSON_AddItemToArray(keypoints, point);
        }
    }
    cJSON_AddNumberToObject(face_json, "inference_ms", face.inference_ms);
    cJSON_AddNumberToObject(face_json, "inference_count", face.inference_count);
    cJSON_AddNumberToObject(face_json, "observed_uptime_ms", static_cast<double>(face.observed_uptime_ms));
    cJSON_AddNumberToObject(face_json, "age_ms", face_available && current >= face.observed_uptime_ms
        ? static_cast<double>(current - face.observed_uptime_ms) : -1.0);

    cJSON *posture_json = cJSON_AddObjectToObject(root, "posture");
    cJSON_AddStringToObject(posture_json, "source", "esp_bbox_fallback_v2");
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
    cJSON_AddBoolToObject(privacy, "landmark_local", true);
    cJSON_AddStringToObject(privacy, "pose_model_sha256",
        "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a");
    cJSON_AddBoolToObject(root, "dashboard_auth", false);
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
        char ip[sizeof(wifi_.ip)]{};
        std::snprintf(ip, sizeof ip, IPSTR, IP2STR(&event->ip_info.ip));
        wifi_ap_record_t record{};
        xSemaphoreTake(mutex_, portMAX_DELAY);
        station_associated_ = true;
        wifi_.station_online = true;
        std::memcpy(wifi_.ip, ip, sizeof(wifi_.ip));
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
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_AP_STACONNECTED) {
        xSemaphoreTake(mutex_, portMAX_DELAY);
        if (ap_client_count_ < UINT8_MAX) ++ap_client_count_;
        xSemaphoreGive(mutex_);
        ESP_LOGI(kTag, "setup AP client connected; pausing saved-network retries");
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_AP_STADISCONNECTED) {
        xSemaphoreTake(mutex_, portMAX_DELAY);
        if (ap_client_count_ > 0U) --ap_client_count_;
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
        const bool ap_client_connected = ap_client_count_ > 0U;
        const uint64_t deadline = pending_deadline_ms_;
        const bool reconnect_due = current - last_reconnect_ms_ >= kStationReconnectIntervalMs;
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
        } else if (!online && !associated && !ap_client_connected && reconnect_due) {
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

bool Dashboard::frame_access_snapshot(uint8_t ipv4[4], uint16_t *port,
                                      uint8_t token[16], uint8_t *flags)
{
    if (ipv4 == nullptr || port == nullptr || token == nullptr || flags == nullptr || mutex_ == nullptr)
        return false;
    const WifiStatus wifi = wifi_status();
    in_addr address{};
    if (inet_pton(AF_INET, wifi.ip, &address) != 1) return false;
    std::memcpy(ipv4, &address.s_addr, 4U);
    *port = 80U;
    std::memcpy(token, watch_token_.data(), watch_token_.size());
    constexpr uint8_t kTokenRequired = 1U << 1U;
    constexpr uint8_t kFaceMetaV1 = 1U << 2U;
    *flags = static_cast<uint8_t>((wifi.station_online ? 1U : 0U) | kTokenRequired | kFaceMetaV1);
    return true;
}

extern "C" bool focusmate_dashboard_start(void)
{
    return dashboard.start();
}

extern "C" bool focusmate_dashboard_frame_access_snapshot(uint8_t ipv4[4], uint16_t *port,
                                                              uint8_t token[16], uint8_t *flags)
{
    return dashboard.frame_access_snapshot(ipv4, port, token, flags);
}
