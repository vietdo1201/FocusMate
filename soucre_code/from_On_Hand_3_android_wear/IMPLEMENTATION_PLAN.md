# Kế hoạch triển khai kỹ thuật

## Đã triển khai trong Watch v2

- `protocol`: `FaceObservationV1`, strict codec, 512-byte cap và sequence gate.
- `app`: fatigue `1..10`, focus `1..5`, migration mood cũ, Rule Engine `watch_rules_v2`.
- Rules inclusive, reason aggregation, cooldown 20 phút, duplicate/retry behavior.
- Motion deterministic; thiếu `BODY_SENSORS` chỉ làm HR unavailable.
- `PostureClassifier`, geometry calibration experimental, shadow-model interface.
- Insight 180 giây hoặc 4 episode/15 phút; không prompt lúc học; break/end recommendations.
- App standalone, một variant, version `14 / 1.13-watch-rules-v2`.

## Protocol/firmware còn lại

1. Viết GATT profile doc và golden vectors dựa trên `FaceObservationV1`.
2. Viết simulator và Watch BLE client với reconnect/freshness/sequence tests.
3. Tạo ESP-IDF project, camera driver và detector nhẹ.
4. Chỉ phát bbox/quality; cấm frame, crop, landmark và identifier.
5. Benchmark trên ESP32-S3 thật rồi mới điều chỉnh ngưỡng geometry.

## Definition of Done cho đợt Watch v2

- Không còn dependency/import/manifest service của đường companion hoặc motion model runtime cũ.
- Không còn product flavor; app phụ thuộc `:protocol`.
- Unit/Robolectric, lint, debug và release assemble pass từ build sạch.
- Live docs thống nhất detector/Watch split; BLE và firmware vẫn `NOT_STARTED/UNVERIFIED`.
- Git tracked state không có model binary/build artifact.

## Ngoài phạm vi

- Firmware, GATT server và BLE client runtime.
- Model posture runtime; chỉ interface shadow.
- Yawn/PFLD và landmark.

Frame transport để model chạy trên Watch là workstream experimental theo ADR 0003, chưa thuộc APK/release hiện tại.
