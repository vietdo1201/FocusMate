// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
#include "yawn_sync_broker.h"

#include <algorithm>
#include <cassert>

namespace {

uint64_t absolute_distance(uint64_t left, uint64_t right)
{
    return left >= right ? left - right : right - left;
}

} // namespace

YawnSyncStateV2 YawnSyncBroker::start_or_resume(
    const std::string &session, uint32_t checkpoint_total,
    const std::vector<uint32_t> &recent_event_ages_ms, uint64_t now_uptime_ms)
{
    const bool replacing = !active_ || session_ != session;
    const bool total_changed = checkpoint_total > total_;
    if (replacing) {
        active_ = true;
        session_ = session;
        revision_ = 0;
        total_ = checkpoint_total;
        canonical_events_.clear();
        submitted_events_.clear();
    } else {
        total_ = std::max(total_, checkpoint_total);
        prune(now_uptime_ms);
    }

    bool seeded = false;
    for (uint32_t age : recent_event_ages_ms) {
        if (age > WINDOW_MS) continue;
        const uint64_t observed = now_uptime_ms >= age ? now_uptime_ms - age : 0U;
        const bool duplicate = std::any_of(canonical_events_.begin(), canonical_events_.end(),
            [observed](uint64_t existing) {
                return absolute_distance(existing, observed) <= CROSS_SOURCE_DEDUPE_MS;
            });
        if (!duplicate && canonical_events_.size() < MAX_RECENT_EVENTS) {
            canonical_events_.push_back(observed);
            seeded = true;
        }
    }
    std::sort(canonical_events_.begin(), canonical_events_.end());
    if (replacing || seeded || total_changed) advance_revision();
    if (revision_ == 0U) advance_revision();
    return state();
}

YawnSyncStateV2 YawnSyncBroker::end(const std::string &session, uint64_t now_uptime_ms)
{
    prune(now_uptime_ms);
    if (active_ && session_ == session) {
        active_ = false;
        session_.clear();
        revision_ = 0;
        total_ = 0;
        canonical_events_.clear();
        submitted_events_.clear();
    }
    return state();
}

bool YawnSyncBroker::submit(const std::string &session, const YawnSyncEventV2 &event,
                            uint64_t now_uptime_ms, YawnSyncStateV2 *result)
{
    prune(now_uptime_ms);
    const bool missing_capture_time = event.observed_uptime_ms == 0U;
    const bool from_future = event.observed_uptime_ms > now_uptime_ms + MAX_EVENT_CLOCK_SKEW_MS;
    const bool too_old = now_uptime_ms >= event.observed_uptime_ms &&
        now_uptime_ms - event.observed_uptime_ms > WINDOW_MS;
    if (!active_ || session_ != session || event.client == 0U ||
        missing_capture_time || from_future || too_old) {
        if (result != nullptr) *result = state();
        return false;
    }
    const bool already_submitted = std::any_of(submitted_events_.begin(), submitted_events_.end(),
        [&event](const SubmittedEvent &existing) {
            return existing.source == event.source && existing.client == event.client &&
                existing.event_id == event.event_id;
        });
    if (already_submitted) {
        if (result != nullptr) *result = state();
        return true;
    }

    if (submitted_events_.size() == MAX_RECENT_EVENTS) submitted_events_.erase(submitted_events_.begin());
    submitted_events_.push_back({event.source, event.client, event.event_id});
    const bool same_physical_event = std::any_of(canonical_events_.begin(), canonical_events_.end(),
        [&event](uint64_t existing) {
            return absolute_distance(existing, event.observed_uptime_ms) <= CROSS_SOURCE_DEDUPE_MS;
        });
    if (!same_physical_event) {
        if (canonical_events_.size() == MAX_RECENT_EVENTS) canonical_events_.erase(canonical_events_.begin());
        canonical_events_.push_back(event.observed_uptime_ms);
        std::sort(canonical_events_.begin(), canonical_events_.end());
        if (total_ < 1000000U) ++total_;
        advance_revision();
    }
    if (result != nullptr) *result = state();
    return true;
}

YawnSyncStateV2 YawnSyncBroker::snapshot(uint64_t now_uptime_ms)
{
    prune(now_uptime_ms);
    return state();
}

void YawnSyncBroker::prune(uint64_t now_uptime_ms)
{
    const size_t before = canonical_events_.size();
    canonical_events_.erase(
        std::remove_if(canonical_events_.begin(), canonical_events_.end(),
            [now_uptime_ms](uint64_t observed) {
                return now_uptime_ms >= observed && now_uptime_ms - observed > WINDOW_MS;
            }),
        canonical_events_.end());
    if (active_ && canonical_events_.size() != before) advance_revision();
}

void YawnSyncBroker::advance_revision()
{
    ++revision_;
    if (revision_ == 0U) ++revision_;
}

YawnSyncStateV2 YawnSyncBroker::state() const
{
    YawnSyncStateV2 result{};
    result.active = active_;
    result.session = session_;
    result.revision = revision_;
    result.total = total_;
    result.window = static_cast<uint32_t>(canonical_events_.size());
    result.last_event_uptime_ms = canonical_events_.empty() ? 0U : canonical_events_.back();
    return result;
}

void yawn_sync_broker_self_test()
{
    constexpr char session[] = "00112233445566778899aabbccddeeff";
    YawnSyncBroker broker;
    YawnSyncStateV2 state = broker.start_or_resume(session, 2U, {1000U, 5000U}, 100000U);
    assert(state.active && state.total == 2U && state.window == 2U);

    YawnSyncEventV2 web{YawnSyncSource::WEB, 7U, 1U, 10U, 110000U};
    assert(broker.submit(session, web, 110000U, &state));
    assert(state.total == 3U && state.window == 3U);
    const uint32_t revision = state.revision;
    assert(broker.submit(session, web, 110000U, &state));
    assert(state.total == 3U && state.revision == revision);

    YawnSyncEventV2 watch{YawnSyncSource::WATCH, 9U, 1U, 11U, 111000U};
    assert(broker.submit(session, watch, 111000U, &state));
    assert(state.total == 3U);
    watch.event_id = 2U;
    watch.observed_uptime_ms = 113000U;
    assert(broker.submit(session, watch, 113000U, &state));
    assert(state.total == 4U);

    const uint32_t protected_total = state.total;
    watch.event_id = 3U;
    watch.observed_uptime_ms = 0U;
    assert(!broker.submit(session, watch, 114000U, &state));
    assert(state.total == protected_total);
    const uint64_t validation_now = 800000U;
    watch.event_id = 4U;
    watch.observed_uptime_ms = validation_now - YawnSyncBroker::WINDOW_MS - 1U;
    assert(!broker.submit(session, watch, validation_now, &state));
    assert(state.total == protected_total);
    watch.event_id = 5U;
    watch.observed_uptime_ms = validation_now + YawnSyncBroker::MAX_EVENT_CLOCK_SKEW_MS + 1U;
    assert(!broker.submit(session, watch, validation_now, &state));
    assert(state.total == protected_total);
    assert(!broker.end(session, 113000U).active);
}
