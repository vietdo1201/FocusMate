/*
 * SPDX-License-Identifier: MIT
 */
#include "focusmate_dns.h"

#include <ctype.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "esp_log.h"
#include "esp_netif.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"

#define DNS_PORT 53
#define DNS_PACKET_MAX 512
#define DNS_HEADER_SIZE 12
#define DNS_TYPE_A 1
#define DNS_CLASS_IN 1
#define DNS_ANSWER_SIZE 16

static const char *TAG = "focusmate-dns";

typedef struct {
    char hostname[64];
    char netif_key[32];
} dns_state_t;

static bool read_u16(const uint8_t *packet, size_t length, size_t offset, uint16_t *value)
{
    if (offset + 2U > length) return false;
    *value = (uint16_t)(((uint16_t)packet[offset] << 8U) | packet[offset + 1U]);
    return true;
}

static void write_u16(uint8_t *packet, size_t offset, uint16_t value)
{
    packet[offset] = (uint8_t)(value >> 8U);
    packet[offset + 1U] = (uint8_t)value;
}

static void write_u32(uint8_t *packet, size_t offset, uint32_t value)
{
    packet[offset] = (uint8_t)(value >> 24U);
    packet[offset + 1U] = (uint8_t)(value >> 16U);
    packet[offset + 2U] = (uint8_t)(value >> 8U);
    packet[offset + 3U] = (uint8_t)value;
}

static bool parse_question_name(const uint8_t *packet, size_t length, size_t *offset,
                                char *name, size_t name_capacity)
{
    size_t input = *offset;
    size_t output = 0U;
    bool first = true;
    while (input < length) {
        const uint8_t label_length = packet[input++];
        if (label_length == 0U) {
            if (output >= name_capacity) return false;
            name[output] = '\0';
            *offset = input;
            return output != 0U;
        }
        if ((label_length & 0xc0U) != 0U || label_length > 63U ||
            input + label_length > length) return false;
        if (!first) {
            if (output + 1U >= name_capacity) return false;
            name[output++] = '.';
        }
        if (output + label_length >= name_capacity) return false;
        for (uint8_t index = 0U; index < label_length; ++index)
            name[output++] = (char)tolower((unsigned char)packet[input++]);
        first = false;
    }
    return false;
}

static size_t make_reply(const uint8_t *request, size_t request_length,
                         uint8_t *reply, size_t reply_capacity, const dns_state_t *state)
{
    if (request_length < DNS_HEADER_SIZE || request_length > reply_capacity) return 0U;
    uint16_t flags = 0U;
    uint16_t question_count = 0U;
    if (!read_u16(request, request_length, 2U, &flags) ||
        !read_u16(request, request_length, 4U, &question_count) ||
        (flags & 0xf800U) != 0U || question_count == 0U) return 0U;

    size_t question_end = DNS_HEADER_SIZE;
    char name[64];
    if (!parse_question_name(request, request_length, &question_end, name, sizeof(name)) ||
        question_end + 4U > request_length) return 0U;
    uint16_t type = 0U;
    uint16_t dns_class = 0U;
    if (!read_u16(request, request_length, question_end, &type) ||
        !read_u16(request, request_length, question_end + 2U, &dns_class)) return 0U;
    question_end += 4U;

    const bool answer = type == DNS_TYPE_A && dns_class == DNS_CLASS_IN &&
                        strcmp(name, state->hostname) == 0;
    const size_t reply_length = question_end + (answer ? DNS_ANSWER_SIZE : 0U);
    if (reply_length > reply_capacity) return 0U;
    memcpy(reply, request, question_end);
    write_u16(reply, 2U, (uint16_t)(0x8400U | (flags & 0x0100U)));
    write_u16(reply, 4U, 1U);
    write_u16(reply, 6U, answer ? 1U : 0U);
    write_u16(reply, 8U, 0U);
    write_u16(reply, 10U, 0U);
    if (!answer) return reply_length;

    esp_netif_t *netif = esp_netif_get_handle_from_ifkey(state->netif_key);
    esp_netif_ip_info_t ip_info = {0};
    if (netif == NULL || esp_netif_get_ip_info(netif, &ip_info) != ESP_OK ||
        ip_info.ip.addr == 0U) return 0U;
    size_t offset = question_end;
    write_u16(reply, offset, 0xc00cU); offset += 2U;
    write_u16(reply, offset, DNS_TYPE_A); offset += 2U;
    write_u16(reply, offset, DNS_CLASS_IN); offset += 2U;
    write_u32(reply, offset, 60U); offset += 4U;
    write_u16(reply, offset, 4U); offset += 2U;
    memcpy(reply + offset, &ip_info.ip.addr, 4U);
    return reply_length;
}

static void dns_task(void *argument)
{
    dns_state_t *state = (dns_state_t *)argument;
    const int socket_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_fd < 0) {
        ESP_LOGE(TAG, "socket failed: errno %d", errno);
        free(state);
        vTaskDelete(NULL);
        return;
    }
    struct sockaddr_in bind_address = {0};
    bind_address.sin_family = AF_INET;
    bind_address.sin_addr.s_addr = htonl(INADDR_ANY);
    bind_address.sin_port = htons(DNS_PORT);
    if (bind(socket_fd, (struct sockaddr *)&bind_address, sizeof(bind_address)) != 0) {
        ESP_LOGE(TAG, "bind port 53 failed: errno %d", errno);
        close(socket_fd);
        free(state);
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "%s resolves through AP DNS", state->hostname);
    uint8_t request[DNS_PACKET_MAX];
    uint8_t reply[DNS_PACKET_MAX];
    for (;;) {
        struct sockaddr_storage client = {0};
        socklen_t client_length = sizeof(client);
        const int received = recvfrom(socket_fd, request, sizeof(request), 0,
                                      (struct sockaddr *)&client, &client_length);
        if (received <= 0) continue;
        const size_t reply_length = make_reply(request, (size_t)received, reply, sizeof(reply), state);
        if (reply_length != 0U)
            sendto(socket_fd, reply, reply_length, 0, (struct sockaddr *)&client, client_length);
    }
}

bool focusmate_dns_start(const char *hostname, const char *netif_key)
{
    if (hostname == NULL || netif_key == NULL ||
        strlen(hostname) >= sizeof(((dns_state_t *)0)->hostname) ||
        strlen(netif_key) >= sizeof(((dns_state_t *)0)->netif_key)) return false;
    dns_state_t *state = (dns_state_t *)calloc(1U, sizeof(*state));
    if (state == NULL) return false;
    strcpy(state->hostname, hostname);
    strcpy(state->netif_key, netif_key);
    for (char *cursor = state->hostname; *cursor != '\0'; ++cursor)
        *cursor = (char)tolower((unsigned char)*cursor);
    TaskHandle_t task = NULL;
    if (xTaskCreate(dns_task, "focusmate-dns", 4096U, state, 4U, &task) != pdPASS) {
        free(state);
        return false;
    }
    return true;
}
