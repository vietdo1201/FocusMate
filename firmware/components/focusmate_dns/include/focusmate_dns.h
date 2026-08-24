/*
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Start the AP-only unicast DNS responder used as a fallback for clients that
 * do not send .local queries over mDNS on a no-Internet Wi-Fi network.
 */
bool focusmate_dns_start(const char *hostname, const char *netif_key);

#ifdef __cplusplus
}
#endif
