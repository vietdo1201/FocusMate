# Process Log

## Checkpoint hiện tại

- Ngày cập nhật: 2026-08-22.
- Watch đã chuyển sang app standalone, một variant, `versionCode 14`, `1.13-watch-rules-v2`.
- Module `protocol` chứa `FaceObservationV1`; app chứa Rule Engine v2, geometry classifier và posture insight tracker.
- Các đường quyết định không deterministic và đường đồng bộ companion cũ đã được loại khỏi app.
- BLE runtime và firmware vẫn `NOT_STARTED / UNVERIFIED`.

## Gate A — nền tảng dev (đạt 2026-08-22)

- Git repo được khởi tạo lại tại `D:\FocusMate-main`; baseline commit `0e2402c`, 102 file tracked, không có build artifact/model binary. Không remote, không push.
- Toolchain: Eclipse Temurin `17.0.20+8` (JDK archive đã verify SHA-256 `418497be…8122`) tại `C:\Users\vietdo1201\Java\jdk-17.0.20+8`, `JAVA_HOME` đặt ở user scope. Android SDK: `platforms/android-35` + `build-tools/35.0.0`.
- `./verify.ps1` **pass tại máy dev này**: `BUILD SUCCESSFUL in 5m 11s`, 103 actionable task, 46 test / 0 failure, `app-debug.apk` + `app-release-unsigned.apk` sinh ra. Đây là bằng chứng local, không nâng trạng thái BLE/firmware/hardware.
- Thiết bị của hồ sơ đã đổi sang Galaxy Watch 5 Pro trong `ROADMAP.md`, `Readme.md`, `docs/STATUS.md`.

## Hành động tiếp theo

1. Đóng băng UUID/GATT profile và golden vectors từ `FaceObservationV1`.
2. Viết ESP-IDF camera + detector và đo latency/RAM trên ESP32-S3 thật.
3. Viết BLE client Watch sau simulator; không nâng status phần cứng chỉ vì codec/test Kotlin pass.
4. Chạy calibration/posture benchmark trên ESP32-S3 + Galaxy Watch 5 Pro.

## Resume

1. Đọc `docs/GOVERNANCE.md`, `docs/STATUS.md` và ADR 0002.
2. Chạy `git status --short` và `./verify.ps1`.
3. Không coi synthetic bbox là bằng chứng thiết bị.
