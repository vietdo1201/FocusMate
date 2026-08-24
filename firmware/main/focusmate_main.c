// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#include <assert.h>
#include <inttypes.h>
#include <string.h>

#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/ble_att.h"
#include "host/util/util.h"
#include "nimble/ble.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "face_observation.h"
#include "camera_smoke.h"
#include "face_detector.h"
#include "dashboard.h"
#include "dashboard_runtime.h"
#include "frame_broker.h"
#include "shadow_posture.h"

/* Provided by NimBLE's persistent store implementation. */
void ble_store_config_init(void);

#define PROTOCOL_VERSION 1U
#define FRAMING_VERSION 1U
#define DEVICE_INFO_SIZE 34U
#define FRAME_ACCESS_INFO_SIZE 40U
#define FRAME_HEADER_SIZE 8U
#define YAWN_BLE_STATE_SIZE 32U
#define YAWN_BLE_CONTROL_MAX_SIZE 150U
#define YAWN_BLE_OPCODE_SESSION_RESUME 0x10U
#define YAWN_BLE_OPCODE_EVENT 0x11U
#define YAWN_BLE_OPCODE_STATE_REQUEST 0x12U
#define YAWN_BLE_OPCODE_SESSION_END 0x13U
#define DEFAULT_NOTIFICATION_CAPACITY 20U
#define MAX_NOTIFICATION_CAPACITY 514U
/* Stub transport does not claim detector/camera readiness (bits 0/1). */
#define BASE_CAPABILITIES ((1U << 2) | (1U << 3) | (1U << 4))

static const char *TAG = "focusmate";
static uint8_t own_addr_type;
static uint8_t boot_id[16];
static uint16_t observation_handle;
static uint16_t active_connection = BLE_HS_CONN_HANDLE_NONE;
static bool subscribed;
static bool streaming;
static uint8_t rate_dhz = 50U;
static uint32_t sequence;
static uint8_t message_id;
static uint32_t notification_attempts;
static uint32_t notification_failures;
static uint8_t consecutive_notification_failures;
static uint64_t notification_failure_started_ms;
static uint32_t observations_emitted;
static uint32_t capabilities = BASE_CAPABILITIES;
static portMUX_TYPE ble_state_mux = portMUX_INITIALIZER_UNLOCKED;

typedef struct {
    uint16_t connection;
    bool subscribed;
    bool streaming;
    uint8_t rate_dhz;
} transport_state_t;

static transport_state_t transport_state(void)
{
    transport_state_t state;
    taskENTER_CRITICAL(&ble_state_mux);
    state.connection = active_connection;
    state.subscribed = subscribed;
    state.streaming = streaming;
    state.rate_dhz = rate_dhz;
    taskEXIT_CRITICAL(&ble_state_mux);
    return state;
}

void focusmate_ble_snapshot(focusmate_ble_snapshot_t *out)
{
    if (out == NULL) return;
    const transport_state_t state = transport_state();
    const bool connected = state.connection != BLE_HS_CONN_HANDLE_NONE;
    out->connected = connected;
    out->subscribed = state.subscribed;
    out->streaming = state.streaming;
    out->mtu = connected ? ble_att_mtu(state.connection) : 0U;
    out->rate_dhz = state.rate_dhz;
    out->observations = observations_emitted;
    out->notification_attempts = notification_attempts;
    out->notification_failures = notification_failures;
}

static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(
    0x6e, 0x44, 0x37, 0xf6, 0x04, 0x4a, 0x0b, 0x83,
    0x92, 0x47, 0x4e, 0x8e, 0xce, 0x90, 0x91, 0x3a);
static const ble_uuid128_t device_info_uuid = BLE_UUID128_INIT(
    0x38, 0xc1, 0xd5, 0x15, 0x0b, 0x9c, 0xdc, 0x9d,
    0x6d, 0x40, 0x70, 0x77, 0x43, 0x16, 0x44, 0x8c);
static const ble_uuid128_t observation_uuid = BLE_UUID128_INIT(
    0x63, 0x12, 0xb8, 0xd5, 0xef, 0xc5, 0x0d, 0x8b,
    0x67, 0x4a, 0x62, 0x0a, 0x21, 0x8a, 0xc1, 0xf8);
static const ble_uuid128_t control_uuid = BLE_UUID128_INIT(
    0x49, 0x40, 0x2f, 0xb3, 0xb5, 0xa0, 0xce, 0xac,
    0x39, 0x4d, 0x93, 0xce, 0x4c, 0x0d, 0xbf, 0x50);
/* f26cf312-b841-46f5-a172-6b53713a37f3 */
static const ble_uuid128_t frame_access_info_uuid = BLE_UUID128_INIT(
    0xf3, 0x37, 0x3a, 0x71, 0x53, 0x6b, 0x72, 0xa1,
    0xf5, 0x46, 0x41, 0xb8, 0x12, 0xf3, 0x6c, 0xf2);

static void put_le(uint8_t *target, uint64_t value, size_t count)
{
    for (size_t index = 0; index < count; ++index) {
        target[index] = (uint8_t)(value >> (index * 8U));
    }
}

static uint64_t get_le(const uint8_t *source, size_t count)
{
    uint64_t value = 0U;
    for (size_t index = 0; index < count; ++index) value |= (uint64_t)source[index] << (index * 8U);
    return value;
}

static void notify_payload(const uint8_t *payload, size_t length);

static void notify_yawn_ble_state(const focusmate_yawn_ble_state_t *state,
                                  bool acknowledge, uint32_t acknowledged_event_id)
{
    if (state == NULL || !state->active || state->revision == 0U) return;
    uint8_t payload[YAWN_BLE_STATE_SIZE] = {0x59U, 0x32U, 0U};
    payload[2] = (uint8_t)(1U | (acknowledge ? 2U : 0U));
    memcpy(payload + 3U, state->session, FOCUSMATE_YAWN_BLE_SESSION_BYTES);
    put_le(payload + 19U, state->revision, 4U);
    put_le(payload + 23U, state->total, 4U);
    payload[27] = state->window;
    put_le(payload + 28U, acknowledged_event_id, 4U);
    notify_payload(payload, sizeof payload);
}

static int device_info_access(uint16_t connection, uint16_t attribute,
                              struct ble_gatt_access_ctxt *context, void *arg)
{
    (void)connection;
    (void)attribute;
    (void)arg;
    uint8_t info[DEVICE_INFO_SIZE] = {0};
    put_le(info, PROTOCOL_VERSION, 2);
    info[2] = FRAMING_VERSION;
    memcpy(info + 3, boot_id, sizeof boot_id);
    put_le(info + 19, (uint64_t)(esp_timer_get_time() / 1000), 8);
    info[27] = 4;
    info[28] = 16;
    info[29] = transport_state().rate_dhz;
    put_le(info + 30, capabilities, 4);
    return os_mbuf_append(context->om, info, sizeof info) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int control_access(uint16_t connection, uint16_t attribute,
                          struct ble_gatt_access_ctxt *context, void *arg)
{
    (void)connection;
    (void)attribute;
    (void)arg;
    uint8_t command[YAWN_BLE_CONTROL_MAX_SIZE] = {0};
    const uint16_t length = OS_MBUF_PKTLEN(context->om);
    if (length < 1U || length > sizeof command || ble_hs_mbuf_to_flat(context->om, command, length, NULL) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    switch (command[0]) {
        case 0x01:
            if (length != 2U || command[1] < 10U || command[1] > 100U) return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
            taskENTER_CRITICAL(&ble_state_mux);
            rate_dhz = command[1];
            streaming = true;
            taskEXIT_CRITICAL(&ble_state_mux);
            break;
        case 0x02:
            if (length != 1U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            taskENTER_CRITICAL(&ble_state_mux);
            streaming = false;
            taskEXIT_CRITICAL(&ble_state_mux);
            break;
        case 0x03:
            if (length != 2U || command[1] < 10U || command[1] > 100U) return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
            taskENTER_CRITICAL(&ble_state_mux);
            rate_dhz = command[1];
            taskEXIT_CRITICAL(&ble_state_mux);
            break;
        case 0x04:
            if (length != 1U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            message_id = 0;
            break;
        case YAWN_BLE_OPCODE_SESSION_RESUME: {
            if (length < 22U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            const uint8_t count = command[21];
            if (count > FOCUSMATE_YAWN_BLE_MAX_RECENT_EVENTS ||
                length != (uint16_t)(22U + (uint16_t)count * 2U)) {
                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }
            uint16_t ages[FOCUSMATE_YAWN_BLE_MAX_RECENT_EVENTS] = {0};
            for (uint8_t index = 0; index < count; ++index) {
                ages[index] = (uint16_t)get_le(command + 22U + (size_t)index * 2U, 2U);
            }
            focusmate_yawn_ble_state_t yawn_state = {0};
            if (!focusmate_dashboard_yawn_ble_resume(command + 1U,
                    (uint32_t)get_le(command + 17U, 4U), ages, count, &yawn_state)) {
                return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
            }
            notify_yawn_ble_state(&yawn_state, false, 0U);
            break;
        }
        case YAWN_BLE_OPCODE_EVENT: {
            if (length != 37U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            const uint32_t event_id = (uint32_t)get_le(command + 21U, 4U);
            focusmate_yawn_ble_state_t yawn_state = {0};
            bool accepted = focusmate_dashboard_yawn_ble_event(
                command + 1U, (uint32_t)get_le(command + 17U, 4U), event_id,
                (uint32_t)get_le(command + 25U, 4U), get_le(command + 29U, 8U), &yawn_state);
            if (!accepted) accepted = focusmate_dashboard_yawn_ble_state(&yawn_state);
            if (accepted) notify_yawn_ble_state(&yawn_state, accepted &&
                memcmp(yawn_state.session, command + 1U, FOCUSMATE_YAWN_BLE_SESSION_BYTES) == 0,
                event_id);
            break;
        }
        case YAWN_BLE_OPCODE_STATE_REQUEST: {
            if (length != 17U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            focusmate_yawn_ble_state_t yawn_state = {0};
            if (focusmate_dashboard_yawn_ble_state(&yawn_state)) {
                notify_yawn_ble_state(&yawn_state, false, 0U);
            }
            break;
        }
        case YAWN_BLE_OPCODE_SESSION_END:
            if (length != 17U) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            (void)focusmate_dashboard_yawn_ble_end(command + 1U);
            break;
        default:
            return BLE_ATT_ERR_REQ_NOT_SUPPORTED;
    }
    const transport_state_t state = transport_state();
    ESP_LOGI(TAG, "control opcode=%u rate_dhz=%u streaming=%d", command[0], state.rate_dhz, state.streaming);
    return 0;
}

static int frame_access_info_access(uint16_t connection, uint16_t attribute,
                                    struct ble_gatt_access_ctxt *context, void *arg)
{
    (void)connection;
    (void)attribute;
    (void)arg;
    uint8_t value[FRAME_ACCESS_INFO_SIZE] = {0};
    uint8_t ipv4[4] = {0};
    uint8_t token[16] = {0};
    uint8_t flags = 0U;
    uint16_t port = 0U;
    value[0] = 1U;
    if (focusmate_dashboard_frame_access_snapshot(ipv4, &port, token, &flags)) {
        value[1] = flags;
        put_le(value + 2, port, 2U);
        memcpy(value + 4, ipv4, sizeof ipv4);
        memcpy(value + 8, boot_id, sizeof boot_id);
        memcpy(value + 24, token, sizeof token);
    }
    return os_mbuf_append(context->om, value, sizeof value) == 0
        ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int observation_access(uint16_t connection, uint16_t attribute,
                              struct ble_gatt_access_ctxt *context, void *arg)
{
    (void)connection;
    (void)attribute;
    (void)context;
    (void)arg;
    /* NimBLE requires an access callback even for a notify-only value. */
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &device_info_uuid.u,
                .access_cb = device_info_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC,
            },
            {
                .uuid = &observation_uuid.u,
                .access_cb = observation_access,
                .val_handle = &observation_handle,
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
            },
            {
                .uuid = &control_uuid.u,
                .access_cb = control_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC,
            },
            {
                .uuid = &frame_access_info_uuid.u,
                .access_cb = frame_access_info_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC,
            },
            {0},
        },
    },
    {0},
};

static uint16_t crc16_ccitt_false(const uint8_t *data, size_t length)
{
    uint16_t crc = 0xffffU;
    for (size_t index = 0; index < length; ++index) {
        crc ^= (uint16_t)data[index] << 8U;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000U) ? (uint16_t)((crc << 1U) ^ 0x1021U) : (uint16_t)(crc << 1U);
        }
    }
    return crc;
}

static void protocol_self_test(void)
{
    char payload[512];
    const char *typical_flags[] = {"well_lit", "stable"};
    size_t length = focusmate_encode_no_face(payload, sizeof payload, 0, 0, NULL, 0);
    assert(length == 122U);
    assert(strcmp(payload, "{\"schema_version\":\"focusmate_face_observation_v1\",\"sequence\":0,\"esp_uptime_ms\":0,\"face_detected\":false,\"quality_flags\":[]}") == 0);
    assert(crc16_ccitt_false((const uint8_t *)payload, length) == 0xA536U);

    length = focusmate_encode_face(payload, sizeof payload, 42, 12345,
                                   500000, 400000, 200000, 300000, 910000,
                                   typical_flags, 2);
    assert(length == 246U);
    assert(strcmp(payload, "{\"schema_version\":\"focusmate_face_observation_v1\",\"sequence\":42,\"esp_uptime_ms\":12345,\"face_detected\":true,\"cx\":0.500000,\"cy\":0.400000,\"width\":0.200000,\"height\":0.300000,\"area\":0.060000,\"confidence\":0.910000,\"quality_flags\":[\"stable\",\"well_lit\"]}") == 0);
    assert(crc16_ccitt_false((const uint8_t *)payload, length) == 0xD073U);
    ESP_LOGI(TAG, "C canonical golden self-test passed");
}

static void notify_bytes(uint16_t connection, const uint8_t *data, size_t length)
{
    ++notification_attempts;
    struct os_mbuf *buffer = ble_hs_mbuf_from_flat(data, (uint16_t)length);
    if (buffer == NULL) {
        ++notification_failures;
        ++consecutive_notification_failures;
        const uint64_t current = (uint64_t)(esp_timer_get_time() / 1000);
        if (notification_failure_started_ms == 0U) notification_failure_started_ms = current;
        if (current - notification_failure_started_ms >= 8000U && connection != BLE_HS_CONN_HANDLE_NONE) {
            ESP_LOGW(TAG, "notify allocation stalled for 8s; recycling BLE link");
            (void)ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        }
        return;
    }
    const int rc = ble_gatts_notify_custom(connection, observation_handle, buffer);
    if (rc != 0) {
        ++notification_failures;
        ESP_LOGW(TAG, "notify failed rc=%d", rc);
        ++consecutive_notification_failures;
        const uint64_t current = (uint64_t)(esp_timer_get_time() / 1000);
        if (notification_failure_started_ms == 0U) notification_failure_started_ms = current;
        if (current - notification_failure_started_ms >= 8000U && connection != BLE_HS_CONN_HANDLE_NONE) {
            ESP_LOGW(TAG, "notify path stalled for 8s; recycling BLE link");
            (void)ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        }
    } else {
        consecutive_notification_failures = 0U;
        notification_failure_started_ms = 0U;
    }
}

static void notify_payload(const uint8_t *payload, size_t length)
{
    const transport_state_t state = transport_state();
    if (state.connection == BLE_HS_CONN_HANDLE_NONE) return;
    struct ble_gap_conn_desc description;
    if (ble_gap_conn_find(state.connection, &description) != 0 || !description.sec_state.encrypted) {
        return;
    }
    const uint16_t negotiated_mtu = ble_att_mtu(state.connection);
    size_t notification_capacity = negotiated_mtu > 3U ? negotiated_mtu - 3U : DEFAULT_NOTIFICATION_CAPACITY;
    if (notification_capacity > MAX_NOTIFICATION_CAPACITY) notification_capacity = MAX_NOTIFICATION_CAPACITY;
    if (length <= notification_capacity) {
        notify_bytes(state.connection, payload, length);
    } else {
        if (notification_capacity <= FRAME_HEADER_SIZE) return;
        const size_t chunk_capacity = notification_capacity - FRAME_HEADER_SIZE;
        const uint8_t count = (uint8_t)((length + chunk_capacity - 1U) / chunk_capacity);
        const uint16_t crc = crc16_ccitt_false(payload, length);
        for (uint8_t index = 0; index < count; ++index) {
            const size_t offset = index * chunk_capacity;
            const size_t part = length - offset < chunk_capacity ? length - offset : chunk_capacity;
            uint8_t frame[MAX_NOTIFICATION_CAPACITY] = {
                FRAMING_VERSION, message_id, index, count,
                (uint8_t)length, (uint8_t)(length >> 8U),
                (uint8_t)crc, (uint8_t)(crc >> 8U),
            };
            memcpy(frame + FRAME_HEADER_SIZE, payload + offset, part);
            notify_bytes(state.connection, frame, FRAME_HEADER_SIZE + part);
        }
        ++message_id;
    }
    ++observations_emitted;
    if (observations_emitted % 50U == 0U) {
        ESP_LOGI(TAG, "transport observations=%" PRIu32 " notify_attempts=%" PRIu32
                 " notify_failures=%" PRIu32 " mtu=%u",
                 observations_emitted, notification_attempts, notification_failures, negotiated_mtu);
    }
}

static int gap_event(struct ble_gap_event *event, void *arg);

static const char *disconnect_reason_name(int reason)
{
    switch (reason) {
        case BLE_HS_HCI_ERR(BLE_ERR_AUTH_FAIL): return "authentication-failed";
        case BLE_HS_HCI_ERR(BLE_ERR_PINKEY_MISSING): return "key-missing";
        case BLE_HS_HCI_ERR(BLE_ERR_CONN_SPVN_TMO): return "connection-timeout";
        case BLE_HS_HCI_ERR(BLE_ERR_REM_USER_CONN_TERM): return "remote-user-terminated";
        case BLE_HS_HCI_ERR(BLE_ERR_CONN_TERM_LOCAL): return "local-host-terminated";
        default: return "other";
    }
}

static void advertise(void)
{
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = (ble_uuid128_t *)&service_uuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    assert(rc == 0);

    struct ble_hs_adv_fields response = {0};
    const char *name = ble_svc_gap_device_name();
    response.name = (uint8_t *)name;
    response.name_len = strlen(name);
    response.name_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&response);
    assert(rc == 0);

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    /* 0.625 ms units: advertise quickly after boot/disconnect so a Watch scan
       does not wait on the host defaults. */
    params.itvl_min = 48U;
    params.itvl_max = 80U;
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    assert(rc == 0);
    ESP_LOGI(TAG, "advertising FocusMate service");
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                taskENTER_CRITICAL(&ble_state_mux);
                active_connection = event->connect.conn_handle;
                taskEXIT_CRITICAL(&ble_state_mux);
                consecutive_notification_failures = 0U;
                notification_failure_started_ms = 0U;
                ESP_LOGI(TAG, "connected handle=%u", active_connection);
            } else {
                advertise();
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            ESP_LOGI(TAG, "disconnected reason=%d (%s)", event->disconnect.reason,
                     disconnect_reason_name(event->disconnect.reason));
            taskENTER_CRITICAL(&ble_state_mux);
            active_connection = BLE_HS_CONN_HANDLE_NONE;
            subscribed = false;
            streaming = false;
            taskEXIT_CRITICAL(&ble_state_mux);
            consecutive_notification_failures = 0U;
            notification_failure_started_ms = 0U;
            advertise();
            break;
        case BLE_GAP_EVENT_ENC_CHANGE:
            ESP_LOGI(TAG, "encryption change handle=%u status=%d",
                     event->enc_change.conn_handle, event->enc_change.status);
            break;
        case BLE_GAP_EVENT_REPEAT_PAIRING: {
            struct ble_gap_conn_desc description;
            const int rc = ble_gap_conn_find(event->repeat_pairing.conn_handle, &description);
            if (rc == 0) {
                ESP_LOGW(TAG, "repeat pairing: deleting stale peer key and retrying");
                ble_store_util_delete_peer(&description.peer_id_addr);
                return BLE_GAP_REPEAT_PAIRING_RETRY;
            }
            ESP_LOGE(TAG, "repeat pairing: connection lookup failed rc=%d", rc);
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == observation_handle) {
                taskENTER_CRITICAL(&ble_state_mux);
                subscribed = event->subscribe.cur_notify != 0;
                const bool current_subscribed = subscribed;
                taskEXIT_CRITICAL(&ble_state_mux);
                ESP_LOGI(TAG, "observation subscribed=%d mtu=%u", current_subscribed,
                         ble_att_mtu(event->subscribe.conn_handle));
            }
            break;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise();
            break;
        default:
            break;
    }
    return 0;
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    assert(rc == 0);
    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    assert(rc == 0);
    /* Queue Service Changed so bonded Android clients drop an older schema. */
    ble_svc_gatt_changed(0x0001U, 0xffffU);
    advertise();
}

static void host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static void observation_task(void *arg)
{
    (void)arg;
    char payload[320];
    const char *warmup_flags[] = {"sensor_warmup"};
    while (true) {
        const transport_state_t state = transport_state();
        if (state.streaming && state.subscribed && state.connection != BLE_HS_CONN_HANDLE_NONE) {
            focusmate_face_result_t result = {0};
            size_t length = 0U;
            if (focusmate_face_detector_latest(&result)) {
                if (result.face_detected) {
                    length = focusmate_encode_face(payload, sizeof payload, sequence++, result.observed_uptime_ms,
                                                   result.cx_q6, result.cy_q6, result.width_q6,
                                                   result.height_q6, result.confidence_q6, NULL, 0);
                } else {
                    length = focusmate_encode_no_face(payload, sizeof payload, sequence++,
                                                      result.observed_uptime_ms, NULL, 0);
                }
            } else {
                /* Keep transport liveness separate from detector freshness.
                   The Watch accepts the heartbeat but cannot treat it as a
                   usable face sample. */
                length = focusmate_encode_no_face(payload, sizeof payload, sequence++,
                    (uint64_t)(esp_timer_get_time() / 1000), warmup_flags, 1U);
            }
            if (length > 0U) notify_payload((const uint8_t *)payload, length);
        }
        const TickType_t delay = pdMS_TO_TICKS(10000U / state.rate_dhz);
        vTaskDelay(delay > 0 ? delay : 1);
    }
}

void app_main(void)
{
    esp_err_t error = nvs_flash_init();
    if (error == ESP_ERR_NVS_NO_FREE_PAGES || error == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        error = nvs_flash_init();
    }
    ESP_ERROR_CHECK(error);
    esp_fill_random(boot_id, sizeof boot_id);
    protocol_self_test();
    if (focusmate_camera_smoke_init()) {
        capabilities |= (1U << 1);
        if (!focusmate_shadow_posture_init()) ESP_LOGE(TAG, "shadow posture init failed");
        if (!focusmate_frame_broker_init()) ESP_LOGE(TAG, "frame broker init failed");
        if (focusmate_face_detector_start()) capabilities |= (1U << 0);
    }
    if (focusmate_dashboard_start()) capabilities |= (1U << 5);
    else ESP_LOGE(TAG, "dashboard start failed; BLE remains available");

    esp_log_level_set("NimBLE", ESP_LOG_WARN);
    ESP_ERROR_CHECK(nimble_port_init());
    ble_svc_gap_init();
    ble_svc_gatt_init();
    assert(ble_svc_gap_device_name_set("FocusMate-ESP") == 0);
    assert(ble_gatts_count_cfg(services) == 0);
    assert(ble_gatts_add_svcs(services) == 0);
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_store_config_init();
    nimble_port_freertos_init(host_task);
    xTaskCreate(observation_task, "face-observation", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "FocusMate GATT ready capabilities=0x%08" PRIx32
             "; local frames require authenticated browser/watch clients", capabilities);
}
