# Process Log

## Checkpoint hiện tại

- Ngày cập nhật: 2026-08-22.
- Watch đã chuyển sang app standalone, một variant, `versionCode 14`, `1.13-watch-rules-v2`.
- Module `protocol` chứa `FaceObservationV1`; app chứa Rule Engine v2, geometry classifier và posture insight tracker.
- Các đường quyết định không deterministic và đường đồng bộ companion cũ đã được loại khỏi app.
- Protocol canonical đã hoàn tất. BLE runtime và ESP-IDF GATT stub đang `IN_PROGRESS / VERIFIED_LOCAL`; camera/detector vẫn chưa bắt đầu trong firmware hiện tại.

## Gate A — nền tảng dev (đạt 2026-08-22)

- Git repo được khởi tạo lại tại `D:\FocusMate-main`; baseline commit `0e2402c`, 102 file tracked, không có build artifact/model binary. Không remote, không push.
- Toolchain: Eclipse Temurin `17.0.20+8` (JDK archive đã verify SHA-256 `418497be…8122`) tại `C:\Users\vietdo1201\Java\jdk-17.0.20+8`, `JAVA_HOME` đặt ở user scope. Android SDK: `platforms/android-35` + `build-tools/35.0.0`.
- `./verify.ps1` **pass tại máy dev này**: `BUILD SUCCESSFUL in 5m 11s`, 103 actionable task, 46 test / 0 failure, `app-debug.apk` + `app-release-unsigned.apk` sinh ra. Đây là bằng chứng local, không nâng trạng thái BLE/firmware/hardware.
- Thiết bị của hồ sơ đã đổi sang Galaxy Watch 5 Pro trong `ROADMAP.md`, `Readme.md`, `docs/STATUS.md`.

## Hành động tiếp theo

1. Trước khi cấp nguồn camera: xác minh oscillator/XCLK, pinout, 3,3 V/GND và revision PCB OV2640.
2. Ghép camera smoke test vào firmware hiện tại; sau đó mới thêm detector/model card/license/hash.
3. Benchmark latency/RAM/FPS/nhiệt/nguồn trên ESP32-S3 thật, không lưu ảnh hay identifier.
4. Chạy calibration/posture benchmark và phiên 2–3 giờ trên Galaxy Watch 5 Pro; chỉ khi đủ bằng chứng mới lập report `VERIFIED_DEVICE`.

## Gate B — protocol + BLE vertical slice (đạt mức local 2026-08-22)

- Protocol canonical, golden vectors, simulator fault injection, reassembler MTU 23–517 và ingestion pipeline đã qua unit tests.
- Watch client có scan/bond/reconnect, một GATT in-flight, timeout, Device Info/capability gate, MTU, subscribe/control và trạng thái UI riêng cho connecting/bonding/unavailable.
- ESP-IDF 5.5.5 GATT stub dùng link mã hóa, bond lưu NVS, xử lý repeat-pairing, encoder C golden self-test và thống kê notify.
- Trên Watch 5 Pro + ESP32-S3 thật: encrypted bond, MTU 256, 5,0 Hz, 150/150 notify không lỗi và reconnect sau reset. Xem [report](reports/2026-08-22-gate-b-ble-vertical-slice.md).
- Lifecycle gate đã có test DND cleanup, scheduler/receiver idempotency, corrupt row retention và tracker bounds.
- Chưa nâng `VERIFIED_DEVICE` vì chưa có camera/detector và bài test tích hợp dài hạn.

## Resume

1. Đọc `docs/GOVERNANCE.md`, `docs/STATUS.md` và ADR 0002.
2. Chạy `git status --short` và `./verify.ps1`.
3. Không coi synthetic bbox là bằng chứng thiết bị.
