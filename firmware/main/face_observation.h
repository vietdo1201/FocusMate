#pragma once

#include <stddef.h>
#include <stdint.h>

/** Canonical v1 encoder. All geometry inputs are integer micro-units. */
size_t focusmate_encode_no_face(char *out, size_t capacity, uint32_t sequence,
                                uint64_t uptime_ms, const char *const *quality_flags,
                                size_t quality_flag_count);

size_t focusmate_encode_face(char *out, size_t capacity, uint32_t sequence,
                             uint64_t uptime_ms, uint32_t cx_q6, uint32_t cy_q6,
                             uint32_t width_q6, uint32_t height_q6,
                             uint32_t confidence_q6, const char *const *quality_flags,
                             size_t quality_flag_count);
