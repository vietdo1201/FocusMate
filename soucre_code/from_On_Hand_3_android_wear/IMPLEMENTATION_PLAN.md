# Kế hoạch triển khai kỹ thuật

## Đã triển khai trong Watch v2

- `protocol`: `FaceObservationV1`, strict codec, 512-byte cap và sequence gate.
- `app`: fatigue `1..10`, focus `1..5`, migration mood cũ, Rule Engine `watch_rules_v2`.
- Rules inclusive, reason aggregation, cooldown 20 phút, duplicate/retry behavior.
- Motion deterministic; thiếu `BODY_SENSORS` chỉ làm HR unavailable.
- `PostureClassifier`, geometry calibration experimental, shadow-model interface.
- Insight 180 giây hoặc 4 episode/15 phút; không prompt lúc học; break/end recommendations.
- App standalone, một variant, source version `15 / 1.14-posture-geometry`.

## Protocol/firmware còn lại

1. GATT profile, golden vectors, simulator, Watch BLE client và ESP-IDF camera/detector đã có.
2. Giữ payload bbox/quality; cấm frame, crop, landmark và identifier trên BLE.
3. Hoàn tất test thật từng state, low-light và soak trước khi chốt threshold/`VERIFIED_DEVICE` posture.
4. Cài APK version 15 lên Watch khi ADB trở lại; không suy diễn source build là device evidence.

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
