// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

enum class YawnSyncSource : uint8_t {
    WEB = 1,
    WATCH = 2,
};

struct YawnSyncEventV2 {
    YawnSyncSource source = YawnSyncSource::WEB;
    uint32_t client = 0;
    uint32_t event_id = 0;
    uint32_t frame_sequence = 0;
    uint64_t observed_uptime_ms = 0;
};

struct YawnSyncStateV2 {
    bool active = false;
    std::string session;
    uint32_t revision = 0;
    uint32_t total = 0;
    uint32_t window = 0;
    uint64_t last_event_uptime_ms = 0;
};

/** Session-scoped, RAM-only event broker shared by Web and Watch. */
class YawnSyncBroker {
public:
    YawnSyncStateV2 start_or_resume(const std::string &session, uint32_t checkpoint_total,
                                    const std::vector<uint32_t> &recent_event_ages_ms,
                                    uint64_t now_uptime_ms);
    YawnSyncStateV2 end(const std::string &session, uint64_t now_uptime_ms);
    bool submit(const std::string &session, const YawnSyncEventV2 &event,
                uint64_t now_uptime_ms, YawnSyncStateV2 *state);
    YawnSyncStateV2 snapshot(uint64_t now_uptime_ms);

    static constexpr uint32_t WINDOW_MS = 10U * 60U * 1000U;
    static constexpr uint32_t CROSS_SOURCE_DEDUPE_MS = 1500U;
    static constexpr uint32_t MAX_EVENT_CLOCK_SKEW_MS = 5000U;
    static constexpr size_t MAX_RECENT_EVENTS = 64U;

private:
    struct SubmittedEvent {
        YawnSyncSource source;
        uint32_t client;
        uint32_t event_id;
    };

    void prune(uint64_t now_uptime_ms);
    void advance_revision();
    YawnSyncStateV2 state() const;

    bool active_ = false;
    std::string session_;
    uint32_t revision_ = 0;
    uint32_t total_ = 0;
    std::vector<uint64_t> canonical_events_;
    std::vector<SubmittedEvent> submitted_events_;
};

void yawn_sync_broker_self_test();
