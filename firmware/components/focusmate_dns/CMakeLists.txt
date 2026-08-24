# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
idf_component_register(
    SRCS "focusmate_dns.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_netif lwip
)
