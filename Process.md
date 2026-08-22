# Process Log

## Checkpoint hiện tại

- Ngày cập nhật: 2026-08-22.
- Watch đã chuyển sang app standalone, một variant, `versionCode 14`, `1.13-watch-rules-v2`.
- Module `protocol` chứa `FaceObservationV1`; app chứa Rule Engine v2, geometry classifier và posture insight tracker.
- Các đường quyết định không deterministic và đường đồng bộ companion cũ đã được loại khỏi app.
- BLE runtime và firmware vẫn `NOT_STARTED / UNVERIFIED`.

## Hành động tiếp theo

1. Đóng băng UUID/GATT profile và golden vectors từ `FaceObservationV1`.
2. Viết ESP-IDF camera + detector và đo latency/RAM trên ESP32-S3 thật.
3. Viết BLE client Watch sau simulator; không nâng status phần cứng chỉ vì codec/test Kotlin pass.
4. Chạy calibration/posture benchmark trên ESP32-S3 + Galaxy Watch FE.

## Resume

1. Đọc `docs/GOVERNANCE.md`, `docs/STATUS.md` và ADR 0002.
2. Chạy `git status --short` và `./verify.ps1`.
3. Không coi synthetic bbox là bằng chứng thiết bị.
