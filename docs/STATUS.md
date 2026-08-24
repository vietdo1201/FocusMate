# Trạng thái dự án

Ngày kiểm chứng tài liệu: 2026-08-24. Ý nghĩa trạng thái xem [GOVERNANCE.md](GOVERNANCE.md).

Thiết bị của hồ sơ: Galaxy Watch 5 Pro (Wear OS, API ≥ 33) + ESP32-S3 N16R8 + OV2640. Định nghĩa nằm trong [ROADMAP.md](../soucre_code/from_On_Hand_3_android_wear/ROADMAP.md). `VERIFIED_DEVICE` chỉ được cấp cho hạng mục đã chạy trên đúng cặp thiết bị này và có report trong `reports/`.

| Thành phần | Implementation | Readiness | Evidence | Ghi chú |
|---|---|---|---|---|
| Wear session/UI/fatigue input | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Một app variant, standalone |
| Rule Engine `watch_rules_v2` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Boundary, overlap, cooldown, missing-data tests |
| Motion collection/rule immobility | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Thiếu HR permission không dừng motion; chưa device test |
| `FaceObservationV1` protocol codec | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Canonical encode/strict decode, golden vectors, uint32 gate, monotonic freshness |
| Canonical wire format + GATT profile | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Kotlin↔C byte equality, CRC/framing MTU 23–517, Device Info 34 byte |
| Geometry classifier/calibration | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 10 fixture geometry v2 chung; trục X theo người ngồi, Q6 mixed-axis dominant, baseline revision 2; `FACE_MISSING`/`UNKNOWN` fail-closed; live 0,50/calibration 0,70 |
| Posture insight/report policy | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 180 s và 4 episode/15 phút tests |
| BLE GATT Watch ↔ ESP | `IN_PROGRESS` | `TARGET` | `VERIFIED_LOCAL` | Watch 5 Pro + ESP thật: encrypted bond, MTU 23→256, 5,0 Hz, reboot reconnect; [Gate B report](../reports/2026-08-22-gate-b-ble-vertical-slice.md) |
| Firmware ESP-IDF | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | GATT + camera + detector/no-face/positive-bbox chạy trên ESP32-S3 thật; long-run còn thiếu; [Gate C detector report](../reports/2026-08-22-gate-c-face-detector.md) |
| OV2640 camera smoke | `IMPLEMENTED` | `TARGET` | `VERIFIED_DEVICE` | PID 0x26; JPEG QVGA quality 6, sensor xoay đúng 180°, 25/25 frame; [report lịch sử](../reports/2026-08-22-gate-c-camera-smoke.md), realtime retest đang hoàn thiện |
| Face detector ESP32-S3 | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | MSR/MNP 5-point thay ESPDet bbox-only, JPEG QVGA; detector/broker đạt 5,0 FPS trong pass Web + Watch, BLE 5 Hz/0 notify failure; positive landmarks và long-run vẫn cần retest; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Posture với ESP32-S3 + Galaxy Watch 5 Pro thật | `IN_PROGRESS` | `TARGET` | `UNVERIFIED` | MediaPipe Pose Lite chạy local trên Web/Watch, Web từng tự baseline 20/20; Watch frame transport đã hết HTTP 400 và fail-closed khi mất mặt. Chưa kiểm đủ tám state, 90 giây guided test, low-light, thermal và soak; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Yawn advisory (Face Landmarker) | `IMPLEMENTED` | `EXPERIMENTAL` | `UNVERIFIED` | Local Web/Watch; summary số Web→ESP→Watch hội tụ bằng max + sequence; MAR + `jawOpen`, 3 lần/10 phút; không tác động Rule Engine, chưa device accuracy/thermal test |
| MediaPipe Pose Lite local (Web + Watch) | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Model/runtime đóng gói offline, shared 8-state fixtures pass; smoke thiết bị đạt Web 38–49 ms nhưng chưa đủ accuracy/thermal/soak để nâng device evidence |
| Frame transport ESP → Watch | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | `LOCAL_FRAME_V1`, encrypted `FrameAccessInfoV1`, boot token và latest-frame-wins đã chạy thật ở 5 FPS; thiếu network capture và soak 2 giờ |

Verification chuẩn:

```powershell
./verify.ps1
```

Command gồm `:protocol:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`. Kết quả local không chứng minh thiết bị thật.

Gate B vertical slice (2026-08-22) có code, test local và bằng chứng transport trên đúng Watch/ESP. Gate C đạt `VERIFIED_DEVICE` cho camera smoke và detector gồm positive bbox; posture `LIVE`, low-light và bài chạy dài vẫn chưa đạt.
