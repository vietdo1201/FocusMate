# ADR 0006: Local frame sidecar và pose posture trên Watch

- Status: Accepted
- Date: 2026-08-23
- Supersedes: mệnh đề “posture tương lai chỉ dùng bbox” của ADR 0002 và giới hạn bbox-only/shadow-only của ADR 0005 cho đường posture local; cụ thể hoá frame transport opt-in của ADR 0003. Không thay đổi Rule Engine của ADR 0002 hay payload BLE của ADR 0004.

## Context

Bbox mặt chỉ cho biết tâm và kích thước tương đối. Nó có thể xấp xỉ mặt dịch ngang, xuống hoặc gần camera, nhưng không quan sát vai/hông và không phân biệt ổn định `HEAD_DOWN`, nghiêng thân hay `SLUMPED`. Việc hạ confidence gate không tạo thêm thông tin, nên yêu cầu đủ nhãn không thể giải quyết đáng tin bằng bbox-only.

Một model pose pretrained cho nose/shoulder/hip cung cấp tín hiệu cần thiết mà không bắt người dùng thu/gắn nhãn dataset. ESP32-S3 vẫn phải ưu tiên camera/detector/BLE; inference pose chạy trong browser hoặc trên Watch, không trên task detector ESP.

## Decision

1. Giữ `FaceObservationV1` bbox-only ở 5 Hz. Thêm sidecar version hoá `LOCAL_FRAME_V1`: Device Info capability bit 5, characteristic encrypted-read `FrameAccessInfoV1` 40 byte và HTTP Watch endpoint. UUID/layout/auth/status/FaceMeta được khóa ở [LOCAL_FRAME_V1.md](../LOCAL_FRAME_V1.md).
2. JPEG chỉ đi trong LAN từ ESP tới client đã có token boot-scoped đọc qua bonded encrypted GATT. Frame/landmark chỉ ở RAM, không storage/cloud/dataset/identity. Dashboard cookie và Watch token là hai miền credential riêng; HTTP plaintext local không được mô tả như TLS.
3. Broker dành lease browser và Watch độc lập, latest-frame-wins. Mất/đóng web hoặc Wi-Fi không ảnh hưởng BLE detector/notification. `401` bắt Watch bỏ credential cũ và đọc lại BLE; không persist token.
4. Pose Landmarker Lite pretrained chạy local trên Watch và browser. Không cần dataset người dùng. Model/runtime đều pin version + SHA-256, không runtime CDN; generated assets và dependency verification là phần build bắt buộc.
5. `POSE_LOCAL_V1` khóa nose/eyes/shoulders bắt buộc (hips tùy label), quality ≥0,70, baseline median/MAD 20 frame unique spanning ít nhất 5 giây, subject-left axis, multi-signal head-down, close hysteresis, collapse 5 giây, debounce/freshness và vocabulary trong normative spec. Watch có thể session-only; browser chỉ được persist numerical median/MAD cùng model/camera/source/boot/session fingerprint và phải invalidate khi scope đổi. Frame/landmark không bao giờ được persist.
6. Pose local có thể là nguồn posture hiển thị chính khi fresh và calibrated, với bbox BLE làm fallback. Tuy nhiên nó vẫn `EXPERIMENTAL/UNVERIFIED` tới khi qua evidence gates; không được dùng để tuyên bố chất lượng trước report thiết bị.
7. Posture tiếp tục là metadata/feedback. Nó **CẤM** đi vào `ReminderContext`, thay đổi `shouldPrompt`, cooldown hoặc quyết định break của `watch_rules_v2`.

## Consequences

- Đủ observability để phân biệt các tư thế dựa trên head/shoulder/hip thay vì ép bbox mặt đo điều nó không thấy.
- Watch cần Wi-Fi local song song BLE, thêm chi phí decode/inference/pin/nhiệt. Thermal/lifecycle gate phải tự tắt frame path và fallback an toàn.
- Cùng model không tự đảm bảo cùng nhãn: Kotlin/JS phải dùng fixture feature và label parity, cùng coordinate semantics, sequence/freshness và baseline reset.
- ADR 0005 vẫn chuẩn cho dashboard/Wi-Fi provisioning và privacy browser, nhưng câu “không cần MediaPipe” và giới hạn classifier bbox-shadow không còn áp dụng cho pose path mới.
- ADR này không nâng `STATUS.md`. Code build/test chỉ là `VERIFIED_LOCAL`; `VERIFIED_DEVICE` cần report Watch + ESP thật theo spec.

## Evidence bắt buộc

- Byte-exact `FrameAccessInfoV1`, capability/reserved bits, boot/token/port/IP order và auth negative tests.
- HTTP/FaceMeta/JPEG/sequence/lease/backpressure/freshness tests; không log/persist frame/token.
- MediaPipe artifacts hash-verified, license inventory, không CDN; Kotlin ↔ JS fixture parity.
- Report thiết bị cho đủ vocabulary, reconnect/reboot/Wi-Fi loss/low light, BLE đồng thời và soak 2 giờ; posture không đổi Rule Engine.
