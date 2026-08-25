// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdbool.h>

#include "esp_err.h"
#include "esp_http_server.h"

#ifdef __cplusplus
extern "C" {
#endif

bool focusmate_web_assets_mount(void);
esp_err_t focusmate_web_assets_serve(httpd_req_t *request, const char *uri);
const char *focusmate_web_assets_manifest_sha256(void);

#ifdef __cplusplus
}
#endif
