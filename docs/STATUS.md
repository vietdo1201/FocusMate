# Trạng thái dự án

Ngày đối chiếu trạng thái phát hành: 2026-09-16. Ý nghĩa trạng thái xem [GOVERNANCE.md](GOVERNANCE.md).

[Release v2.3.0](https://github.com/vietdo1201/FocusMate/releases/tag/v2.3.0)
đã công khai lúc 2026-09-16 02:07:07 +07:00 (2026-09-15 19:07:07 UTC), commit
`d7c072e`. `version.properties` đồng bộ Watch `versionCode 26`/`versionName 2.3.0`
và firmware `2.3.0`. [CI phát hành](https://github.com/vietdo1201/FocusMate/actions/runs/35010338290)
hoàn tất thành công, có APK ký số, firmware, source archive, SBOM và checksum.

Phân biệt **published**, **code-ready** và **device-verified**: hai trạng thái đầu
có bằng chứng cho release/current main; trạng thái cuối được cấp theo từng
component/report có hash và điều kiện chạy. Phiên học/reminder hiện theo
[roadmap](SESSION_HEALTH_ROADMAP.md) và [ADR 0008](decisions/0008-session-health-reminder-lifecycle.md).

Bộ kiểm thử hệ thống được ghi nhận ngày 28–29/08/2026 với kết quả `24/24
PASS`. Đây là `RECORDED_FUNCTIONAL_TEST` cho các kịch bản đã ghi trong [ma
trận test](../tests/FocusMate_Test/TEST_MATRIX.md). Mỗi hạng mục posture, yawn,
frame transport, accuracy, low-light, thermal và soak giữ nhãn evidence theo
đúng report nguồn thay vì được suy rộng từ tổng 24/24.

Thiết bị của hồ sơ: Galaxy Watch 5 Pro (Wear OS, API ≥ 33) + ESP32-S3 N16R8 + OV2640. Định nghĩa nằm trong [ROADMAP.md](../wear/ROADMAP.md). `VERIFIED_DEVICE` chỉ được cấp cho hạng mục đã chạy trên đúng cặp thiết bị này và có report trong `reports/`.

| Thành phần | Implementation | Readiness | Evidence | Ghi chú |
|---|---|---|---|---|
| Wear session/UI/fatigue input | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 2.3.0: pause/resume, nghỉ chủ động, check-in tự nguyện và motion fallback; evidence target tiếp theo là UX màn hình tròn theo artifact |
| Session SQLite v2/monotonic recovery | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Migration/recovery giữ thời gian đã biết, reset block mới từ 0; rollback, deadline và retention có host tests cùng audit độc lập 2/2 |
| Reminder delivery/check-in shadow | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Một initial + tối đa một retry; shadow/HR/posture/yawn không phát reminder; Android 14+ chọn service type theo quyền và có motion fallback |
| Rule Engine `watch_rules_v2` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Boundary, overlap, cooldown, missing-data tests |
| End-session `session_advice_v1` | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Automated matrix pass; Watch/ESP smoke xác nhận schema, fallback report cuộn và BLE lifecycle; evidence targets theo nhánh 30/45/60 phút và HR/posture/ngáp; [report](../reports/2026-08-27-session-advice-v1-device-smoke.md) |
| Motion collection/rule immobility | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | HR là tùy chọn; identity session/block/generation, duplicate/gap và service-permission matrix có regression tests |
| `FaceObservationV1` protocol codec | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Canonical encode/strict decode, golden vectors, uint32 gate, monotonic freshness |
| Canonical wire format + GATT profile | `IMPLEMENTED` | `TARGET` | `VERIFIED_LOCAL` | Kotlin↔C byte equality, CRC/framing MTU 23–517, Device Info 34 byte |
| Geometry classifier/calibration | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Scale consensus Web/Watch 2/3 và ESP 2/2; baseline revision 3 migration giữ posture/NVS; `FACE_MISSING`/`UNKNOWN` fail-closed; live 0,50/calibration 0,70 |
| Posture insight/report policy | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | 180 s và 4 episode/15 phút tests |
| BLE GATT Watch ↔ ESP | `IN_PROGRESS` | `TARGET` | `VERIFIED_LOCAL` | Queue control, jitter backoff, Bluetooth state wake và rate 5/2/1 Hz đã build/test local; adaptive retest là evidence target tiếp theo. Bằng chứng lịch sử: encrypted bond, MTU 23→256, 5,0 Hz, reboot reconnect; [Gate B report](../reports/2026-08-22-gate-b-ble-vertical-slice.md) |
| Firmware ESP-IDF | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` (lịch sử) | GATT + camera + detector/no-face/positive-bbox đã chạy trên ESP32-S3; [report theo source revision](../reports/2026-08-26-yawn-shape-v5-firmware-flash.md). Long-run được theo dõi thành evidence target riêng |
| OV2640 camera smoke | `IMPLEMENTED` | `TARGET` | `VERIFIED_DEVICE` | PID 0x26; JPEG QVGA quality 6, sensor xoay đúng 180°, 25/25 frame; [report lịch sử](../reports/2026-08-22-gate-c-camera-smoke.md), realtime retest đang hoàn thiện |
| Face detector ESP32-S3 | `IN_PROGRESS` | `TARGET` | `VERIFIED_DEVICE` | MSR/MNP 5-point thay ESPDet bbox-only, JPEG QVGA; detector/broker đạt 5,0 FPS trong pass Web + Watch, BLE 5 Hz/0 notify failure; positive landmarks và long-run là evidence targets; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Posture với ESP32-S3 + Galaxy Watch 5 Pro thật | `IN_PROGRESS` | `TARGET` | `UNVERIFIED` | MediaPipe Pose Lite chạy local trên Web/Watch, Web từng tự baseline 20/20; Watch frame transport fail-closed khi mất mặt. Evidence targets: tám state, guided test 90 giây, low-light, thermal và soak; [landmark progress](../reports/2026-08-23-local-pose-landmark-device-progress.md) |
| Yawn advisory (Face Landmarker) | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | V5 dùng MAR + mouth-width/eye-width + 1,6 giây trên Web/Watch để loại cười; Watch thêm `jawOpen`; broker V2 idempotent/dedupe 1,5 giây, 3 lần/10 phút; accuracy ngáp/cười/speech, thermal và soak là evidence targets |
| MediaPipe Pose Lite local (Web + Watch) | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | Model/runtime đóng gói offline, shared 8-state fixtures pass; APK tối ưu giữ byte field/callback JNI cần cho MediaPipe graph startup; smoke Web 38–49 ms |
| Frame transport ESP → Watch | `IMPLEMENTED` | `EXPERIMENTAL` | `VERIFIED_LOCAL` | `LOCAL_FRAME_V1`, encrypted `FrameAccessInfoV1`, boot token và latest-frame-wins đã chạy ở 5 FPS; network capture, adaptive retest và soak 2 giờ là evidence targets |

Verification chuẩn:

```powershell
./verify.ps1
```

Command chuẩn bị model hash-pinned, chạy Python/Node contracts, Gradle test/lint/APK và ESP-IDF 5.5.5 clean build. Evidence local/CI và device report được gắn nhãn riêng.

Gate B vertical slice (2026-08-22) có code, test local và bằng chứng transport trên đúng Watch/ESP. Gate C đạt `VERIFIED_DEVICE` cho camera smoke và detector gồm positive bbox. Flash Yawn Shape V5 ngày 2026-08-26 xác nhận boot, camera smoke, asset mount, dashboard và BLE của source revision được ghi; accuracy ngáp/overlay, posture `LIVE`, low-light và bài chạy dài được quản lý thành các evidence target riêng.
