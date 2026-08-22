# Trạng thái dự án

Ngày kiểm chứng tài liệu: 2026-08-23. Ý nghĩa trạng thái xem [GOVERNANCE.md](GOVERNANCE.md).

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
| OV2640 camera smoke | `IMPLEMENTED` | `TARGET` | `VERIFIED_DEVICE` | PID 0x26; JPEG QVGA quality 8, sensor xoay đúng 180°, 25/25 frame; [report lịch sử](../reports/2026-08-22-gate-c-camera-smoke.md), realtime retest đang hoàn thiện |
| Face detector ESP32-S3 | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | ESPDet Pico 224 nhận bbox mặt thật tới ~90%; ~294–304 ms với JPEG decode/crop; no-person không sinh bbox trong >50 inference; BLE 5 Hz/0 notify failure; [retest](../reports/2026-08-22-shadow-dashboard-device-retest.md) |
| Posture với ESP32-S3 + Galaxy Watch 5 Pro thật | `IN_PROGRESS` | `TARGET` | `UNVERIFIED` | Pass correction đạt baseline v2 20/20 và ngồi thẳng `NORMAL`; artifact cuối đã flash/cài, GATT MTU 256/0 lỗi nhưng đang chờ calibration mới. Bằng chứng `HEAD_DOWN dy=0,167` cũ đã rút lại; chưa kiểm đủ state, low-light và soak; [correction](../reports/2026-08-23-posture-orientation-correction.md) |
| Yawn/PFLD | `NOT_STARTED` | `DEFERRED` | `UNVERIFIED` | `deferred/unavailable` cho v2 và alpha |
| Future posture model shadow | `NOT_STARTED` | `EXPERIMENTAL` | `UNVERIFIED` | Chỉ có interface, chưa runtime/model |
| Frame transport ESP → Watch | `NOT_STARTED` | `EXPERIMENTAL` | `UNVERIFIED` | Được phép theo ADR 0003; chưa có protocol/runtime/benchmark |

Verification chuẩn:

```powershell
./verify.ps1
```

Command gồm `:protocol:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`. Kết quả local không chứng minh thiết bị thật.

Gate B vertical slice (2026-08-22) có code, test local và bằng chứng transport trên đúng Watch/ESP. Gate C đạt `VERIFIED_DEVICE` cho camera smoke và detector gồm positive bbox; posture `LIVE`, low-light và bài chạy dài vẫn chưa đạt.
