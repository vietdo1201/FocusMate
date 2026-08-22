#include "face_observation.h"

#include <inttypes.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#define SCALE 1000000U

static int append_q6(char *out, size_t capacity, size_t offset, uint32_t value)
{
    return snprintf(out + offset, capacity - offset, "%" PRIu32 ".%06" PRIu32,
                    value / SCALE, value % SCALE);
}

static bool valid_flag(const char *flag)
{
    if (flag == NULL) return false;
    const size_t length = strlen(flag);
    if (length == 0U || length > 16U) return false;
    for (size_t index = 0; index < length; ++index) {
        const char ch = flag[index];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')) return false;
    }
    return true;
}

static size_t append_flags(char *out, size_t capacity, size_t offset,
                           const char *const *quality_flags, size_t count)
{
    if (count > 4U || (count > 0U && quality_flags == NULL)) return 0U;
    const char *sorted[4] = {0};
    bool stable = false;
    bool unstable = false;
    for (size_t index = 0; index < count; ++index) {
        if (!valid_flag(quality_flags[index])) return 0U;
        sorted[index] = quality_flags[index];
        stable |= strcmp(sorted[index], "stable") == 0;
        unstable |= strcmp(sorted[index], "unstable") == 0;
    }
    if (stable && unstable) return 0U;
    for (size_t index = 1; index < count; ++index) {
        const char *candidate = sorted[index];
        size_t position = index;
        while (position > 0U && strcmp(sorted[position - 1U], candidate) > 0) {
            sorted[position] = sorted[position - 1U];
            --position;
        }
        sorted[position] = candidate;
    }
    for (size_t index = 1; index < count; ++index) {
        if (strcmp(sorted[index - 1U], sorted[index]) == 0) return 0U;
    }
    for (size_t index = 0; index < count; ++index) {
        const int written = snprintf(out + offset, capacity - offset, "%s\"%s\"",
                                     index == 0U ? "" : ",", sorted[index]);
        if (written <= 0 || (size_t)written >= capacity - offset) return 0U;
        offset += (size_t)written;
    }
    return offset;
}

size_t focusmate_encode_no_face(char *out, size_t capacity, uint32_t sequence,
                                uint64_t uptime_ms, const char *const *quality_flags,
                                size_t quality_flag_count)
{
    int count = snprintf(
        out, capacity,
        "{\"schema_version\":\"focusmate_face_observation_v1\",\"sequence\":%" PRIu32
        ",\"esp_uptime_ms\":%" PRIu64 ",\"face_detected\":false,\"quality_flags\":[",
        sequence, uptime_ms);
    if (count <= 0 || (size_t)count >= capacity) return 0U;
    size_t offset = append_flags(out, capacity, (size_t)count, quality_flags, quality_flag_count);
    if (offset == 0U) return 0U;
    count = snprintf(out + offset, capacity - offset, "]}");
    return count > 0 && (size_t)count < capacity - offset ? offset + (size_t)count : 0U;
}

size_t focusmate_encode_face(char *out, size_t capacity, uint32_t sequence,
                             uint64_t uptime_ms, uint32_t cx_q6, uint32_t cy_q6,
                             uint32_t width_q6, uint32_t height_q6,
                             uint32_t confidence_q6, const char *const *quality_flags,
                             size_t quality_flag_count)
{
    if (cx_q6 > SCALE || cy_q6 > SCALE || width_q6 < 1000U || width_q6 > SCALE ||
        height_q6 < 1000U || height_q6 > SCALE || confidence_q6 > SCALE) {
        return 0U;
    }
    const uint32_t area_q6 = (uint32_t)(((uint64_t)width_q6 * height_q6 + SCALE / 2U) / SCALE);
    int count = snprintf(
        out, capacity,
        "{\"schema_version\":\"focusmate_face_observation_v1\",\"sequence\":%" PRIu32
        ",\"esp_uptime_ms\":%" PRIu64 ",\"face_detected\":true,\"cx\":",
        sequence, uptime_ms);
    if (count <= 0 || (size_t)count >= capacity) return 0U;
    size_t offset = (size_t)count;
#define APPEND_Q6(KEY, VALUE) do { \
    if (offset >= capacity) return 0U; \
    count = snprintf(out + offset, capacity - offset, KEY); \
    if (count <= 0 || (size_t)count >= capacity - offset) return 0U; \
    offset += (size_t)count; \
    count = append_q6(out, capacity, offset, VALUE); \
    if (count <= 0 || (size_t)count >= capacity - offset) return 0U; \
    offset += (size_t)count; \
} while (0)
    count = append_q6(out, capacity, offset, cx_q6);
    if (count <= 0 || (size_t)count >= capacity - offset) return 0U;
    offset += (size_t)count;
    APPEND_Q6(",\"cy\":", cy_q6);
    APPEND_Q6(",\"width\":", width_q6);
    APPEND_Q6(",\"height\":", height_q6);
    APPEND_Q6(",\"area\":", area_q6);
    APPEND_Q6(",\"confidence\":", confidence_q6);
#undef APPEND_Q6
    count = snprintf(out + offset, capacity - offset, ",\"quality_flags\":[");
    if (count <= 0 || (size_t)count >= capacity - offset) return 0U;
    offset += (size_t)count;
    offset = append_flags(out, capacity, offset, quality_flags, quality_flag_count);
    if (offset == 0U) return 0U;
    count = snprintf(out + offset, capacity - offset, "]}");
    if (count <= 0 || (size_t)count >= capacity - offset) return 0U;
    return offset + (size_t)count;
}
