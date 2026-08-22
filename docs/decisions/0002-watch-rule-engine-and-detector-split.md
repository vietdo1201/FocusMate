# ADR 0002: Watch Rule Engine và detector split

- Status: Accepted
- Date: 2026-08-22
- Supersedes: các định hướng sinh ngôn ngữ/AI quyết định reminder được ghi trong tài liệu lịch sử; bổ sung và cập nhật kiến trúc sau ADR 0001.

## Context

Kiến trúc cũ có nhiều đường quyết định, đồng bộ companion và inference motion/posture không đồng nhất. Điều này làm khó kiểm chứng, tăng dependency và không phù hợp giới hạn ESP32-S3.

## Decision

- ESP32-S3 chỉ chạy face detector nhẹ.
- ESP gửi bbox chuẩn hóa, confidence và quality; tuyệt đối không gửi ảnh, crop, landmark hoặc identifier.
- Watch thực hiện calibration, phân loại posture, temporal insight và reminder.
- Rule deterministic `watch_rules_v2` là nguồn quyết định break duy nhất.
- Posture insight không notification lúc học; chỉ góp lời khuyên lúc nghỉ/báo cáo.
- Model posture tương lai chỉ dùng bbox feature và bắt đầu ở shadow mode.
- Yawn/PFLD deferred khỏi v2/alpha.

## Consequences

App trở thành Wear standalone một variant; module dùng chung đổi thành `protocol`. Các dependency/service/controller cho Gemma, Phone Data Layer, PersonalBreakAi, LiteRT/MLP và pilot capture bị loại khỏi code hiện hành. BLE/firmware không được xem là đã triển khai chỉ vì protocol/classifier test pass.

## Evidence

- `FaceObservationV1` codec/sequence tests.
- `WatchRuleEngineTest` và `PostureClassifierTest`.
- `verify.ps1` cho unit, lint, debug và release build.
