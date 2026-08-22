# Trạng thái dự án

Ngày kiểm chứng tài liệu: 2026-08-22. Ý nghĩa trạng thái xem [GOVERNANCE.md](GOVERNANCE.md).

Thiết bị của hồ sơ: Galaxy Watch 5 Pro (Wear OS, API ≥ 33) + ESP32-S3 N16R8 + OV2640. Định nghĩa nằm trong [ROADMAP.md](../soucre_code/from_On_Hand_3_android_wear/ROADMAP.md). `VERIFIED_DEVICE` chỉ được cấp cho hạng mục đã chạy trên đúng cặp thiết bị này và có report trong `reports/`.

| Thành phần | Implementation | Readiness | Evidence | Ghi chú |
|---|---|---|---|---|
| Wear session/UI/fatigue input | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Một app variant, standalone |
| Rule Engine `watch_rules_v2` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Boundary, overlap, cooldown, missing-data tests |
| Motion collection/rule immobility | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Thiếu HR permission không dừng motion; chưa device test |
| `FaceObservationV1` protocol codec | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Round-trip, malformed, size, sequence gate |
| Geometry classifier/calibration | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Synthetic bbox only |
| Posture insight/report policy | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 180 s và 4 episode/15 phút tests |
| BLE GATT Watch ↔ ESP | `NOT_STARTED` | `TARGET` | `UNVERIFIED` | Chưa có client/server/runtime |
| Firmware ESP-IDF | `NOT_STARTED` | `TARGET` | `UNVERIFIED` | Chưa có project/build |
| Camera + face detector ESP32-S3 | `NOT_STARTED` | `TARGET` | `UNVERIFIED` | Chưa có artifact/hardware benchmark |
| Posture với ESP32-S3 + Galaxy Watch 5 Pro thật | `NOT_STARTED` | `TARGET` | `UNVERIFIED` | Synthetic test không nâng trạng thái |
| Yawn/PFLD | `NOT_STARTED` | `DEFERRED` | `UNVERIFIED` | `deferred/unavailable` cho v2 và alpha |
| Future posture model shadow | `NOT_STARTED` | `EXPERIMENTAL` | `UNVERIFIED` | Chỉ có interface, chưa runtime/model |
| Frame transport ESP → Watch | `NOT_STARTED` | `EXPERIMENTAL` | `UNVERIFIED` | Được phép theo ADR 0003; chưa có protocol/runtime/benchmark |

Verification chuẩn:

```powershell
./verify.ps1
```

Command gồm `:protocol:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`. Kết quả local không chứng minh thiết bị thật.
