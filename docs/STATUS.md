# Trạng thái dự án

Ngày kiểm chứng tài liệu: 2026-09-01. Ý nghĩa trạng thái xem [GOVERNANCE.md](GOVERNANCE.md).

Release `v2.2.2` đồng bộ Watch `versionCode 25`/`versionName 2.2.2`, firmware
descriptor `2.2.2`, SBOM và tên artifact. Build/test tự động áp dụng cho đúng tag;
không có Watch/ESP tại thời điểm phát hành nên artifact chính xác chưa được cài/
flash lại. Bằng chứng thiết bị gần nhất và giới hạn xem trong các report lịch sử.

Bộ kiểm thử hệ thống được ghi nhận ngày 28–29/08/2026 với kết quả `24/24
PASS`. Đây là `RECORDED_FUNCTIONAL_TEST` cho các kịch bản đã ghi trong [ma
trận test](../tests/FocusMate_Test/TEST_MATRIX.md), không tự động nâng posture,
yawn, frame transport hoặc soak lên `VERIFIED_DEVICE`. Các giới hạn về accuracy
người thật, false-positive, low-light, thermal 30 phút và soak 2 giờ vẫn giữ
nguyên cho tới khi có report thiết bị đáp ứng đủ evidence gate.

Thiết bị của hồ sơ: Galaxy Watch 5 Pro (Wear OS, API ≥ 33) + ESP32-S3 N16R8 + OV2640. Định nghĩa nằm trong [ROADMAP.md](../wear/ROADMAP.md). `VERIFIED_DEVICE` chỉ được cấp cho hạng mục đã chạy trên đúng cặp thiết bị này và có report trong `reports/`.

| Thành phần | Implementation | Readiness | Evidence | Ghi chú |
|---|---|---|---|---|
| Wear session/UI/fatigue input | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Một app variant, standalone |
| Rule Engine `watch_rules_v2` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Boundary, overlap, cooldown, missing-data tests |
| End-session `session_advice_v1` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Automated matrix pass; Watch/ESP smoke thật đã xác nhận schema, fallback report cuộn và BLE lifecycle, chưa kiểm các nhánh 30/45/60 phút hoặc HR/posture/ngáp thật; [report](../reports/2026-08-27-session-advice-v1-device-smoke.md) |
| Motion collection/rule immobility | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Thiếu HR permission không dừng motion; chưa device test |
| `FaceObservationV1` protocol codec | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Canonical encode/strict decode, golden vectors, uint32 gate, monotonic freshness |
| Canonical wire format + GATT profile | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Kotlin↔C byte equality, CRC/framing MTU 23–517, Device Info 34 byte |
| Geometry classifier/calibration | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Scale consensus Web/Watch 2/3 và ESP 2/2; baseline revision 3 migration giữ posture/NVS; `FACE_MISSING`/`UNKNOWN` fail-closed; live 0,50/calibration 0,70 |
| Posture insight/report policy | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 180 s và 4 episode/15 phút tests |
| BLE GATT Watch ↔ ESP | `IN_PROGRESS` | `TARGET` | `VERIFIED_LOCAL` | Queue control, jitter backoff, Bluetooth state wake và rate 5/2/1 Hz đã build/test local; phần adaptive mới cần device retest. Bằng chứng cũ: encrypted bond, MTU 23→256, 5,0 Hz, reboot reconnect; [Gate B report](../reports/2026-08-22-gate-b-ble-vertical-slice.md) |
| Firmware ESP-IDF | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | GATT + camera + detector/no-face/positive-bbox từng chạy trên ESP32-S3 thật; source tổ tiên của Yawn Shape V5 đã flash/boot smoke, nhưng artifact v2.2.2 chính xác chưa flash lại và long-run còn thiếu; [report](../reports/2026-08-26-yawn-shape-v5-firmware-flash.md) |
| OV2640 camera smoke | `IMPLEMENTED` | `TARGET` | `VERIFIED_DEVICE` | PID 0x26; JPEG QVGA quality 6, sensor xoay đúng 180°, 25/25 frame; [report lịch sử](../reports/2026-08-22-gate-c-camera-smoke.md), realtime retest đang hoàn thiện |
| Face detector ESP32-S3 | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | MSR/MNP 5-point thay ESPDet bbox-only, JPEG QVGA; detector/broker đạt 5,0 FPS trong pass Web + Watch, BLE 5 Hz/0 notify failure; positive landmarks và long-run vẫn cần retest; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Posture với ESP32-S3 + Galaxy Watch 5 Pro thật | `IN_PROGRESS` | `TARGET` | `UNVERIFIED` | MediaPipe Pose Lite chạy local trên Web/Watch, Web từng tự baseline 20/20; Watch frame transport đã hết HTTP 400 và fail-closed khi mất mặt. Chưa kiểm đủ tám state, 90 giây guided test, low-light, thermal và soak; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Yawn advisory (Face Landmarker) | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | V5 dùng MAR + mouth-width/eye-width + 1,6 giây trên Web/Watch để loại cười; Watch thêm `jawOpen`; broker V2 idempotent/dedupe 1,5 giây, 3 lần/10 phút; vẫn cần ngáp/cười/speech accuracy, thermal và soak trên thiết bị |
| MediaPipe Pose Lite local (Web + Watch) | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Model/runtime đóng gói offline, shared 8-state fixtures pass; smoke thiết bị đạt Web 38–49 ms nhưng chưa đủ accuracy/thermal/soak để nâng device evidence |
| Frame transport ESP → Watch | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | `LOCAL_FRAME_V1`, encrypted `FrameAccessInfoV1`, boot token và latest-frame-wins đã chạy thật ở 5 FPS; thiếu network capture, retest adaptive sau bản mới và soak 2 giờ |

Verification chuẩn:

```powershell
./verify.ps1
```

Command chuẩn bị model hash-pinned, chạy Python/Node contracts, Gradle test/lint/APK và ESP-IDF 5.5.5 clean build. Kết quả local/CI không tự chứng minh thiết bị thật.

Gate B vertical slice (2026-08-22) có code, test local và bằng chứng transport trên đúng Watch/ESP. Gate C đạt `VERIFIED_DEVICE` cho camera smoke và detector gồm positive bbox. Flash Yawn Shape V5 ngày 2026-08-26 xác nhận boot, camera smoke, asset mount, dashboard và BLE của source tổ tiên; không kiểm chứng accuracy ngáp/overlay và không tự chuyển thành bằng chứng cho byte-exact artifact v2.2.2. Posture `LIVE`, low-light và bài chạy dài vẫn chưa đạt.
