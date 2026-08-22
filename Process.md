# Process Log

## Checkpoint hiện tại

- Ngày cập nhật: 2026-08-23.
- Watch source đã chuyển sang app standalone, một variant, `versionCode 15`, `1.14-posture-geometry`; APK mới chưa cài vì ADB hiện không thấy Watch.
- Module `protocol` chứa `FaceObservationV1`; app chứa Rule Engine v2, geometry classifier và posture insight tracker.
- Các đường quyết định không deterministic và đường đồng bộ companion cũ đã được loại khỏi app.
- Protocol canonical đã hoàn tất. BLE runtime đang `IN_PROGRESS / VERIFIED_LOCAL`; camera, detector và một scenario `HEAD_DOWN` shadow có bằng chứng thiết bị thật, nhưng bộ posture/soak vẫn chưa đạt.

## Realtime posture retest (2026-08-23)

- Firmware `0.4.1-shadow-posture` tách gate confidence: calibration `0,70`, live `0,50`; mọi state live dùng ba mẫu ổn định và stale vẫn fail-closed ngay. Xem [device retest](reports/2026-08-23-posture-geometry-retest.md).
- Timer `SLUMPED` chỉ đếm khi `dy ≥ 0,18` liên tục 5 giây; không tái dùng thời gian `HEAD_DOWN` nhẹ hoặc `TOO_CLOSE`.
- Flash COM4 thành công; dashboard thật tại `focusmate.local` nhận `HEAD_DOWN` ổn định với `dy=0,167`, confidence `0,86`, BLE MTU 256 và 0 notify failure.
- Đây chỉ là bằng chứng scenario `HEAD_DOWN`; `LEAN_LEFT/RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, low-light và soak vẫn phải chạy thật trước khi nâng posture.

## Gate A — nền tảng dev (đạt 2026-08-22)

- Git repo được khởi tạo lại tại `D:\FocusMate-main`; baseline commit `0e2402c`, 102 file tracked, không có build artifact/model binary. Không remote, không push.
- Toolchain: Eclipse Temurin `17.0.20+8` (JDK archive đã verify SHA-256 `418497be…8122`) tại `C:\Users\vietdo1201\Java\jdk-17.0.20+8`, `JAVA_HOME` đặt ở user scope. Android SDK: `platforms/android-35` + `build-tools/35.0.0`.
- `./verify.ps1` **pass tại máy dev này**: `BUILD SUCCESSFUL in 5m 11s`, 103 actionable task, 46 test / 0 failure, `app-debug.apk` + `app-release-unsigned.apk` sinh ra. Đây là bằng chứng local, không nâng trạng thái BLE/firmware/hardware.
- Thiết bị của hồ sơ đã đổi sang Galaxy Watch 5 Pro trong `ROADMAP.md`, `Readme.md`, `docs/STATUS.md`.

## Hành động tiếp theo

1. Giữ tư thế ổn định để đạt baseline `LIVE`, rồi thu bbox theo từng tư thế và chạy low-light acceptance.
2. Chốt geometry threshold từ số đo thật; đo RAM/nhiệt/nguồn và xác nhận posture không đổi quyết định Rule Engine.
3. Chạy phiên 2–3 giờ trên Galaxy Watch 5 Pro; chỉ khi đủ bằng chứng mới lập report `VERIFIED_DEVICE` toàn hệ thống.

## Gate B — protocol + BLE vertical slice (đạt mức local 2026-08-22)

- Protocol canonical, golden vectors, simulator fault injection, reassembler MTU 23–517 và ingestion pipeline đã qua unit tests.
- Watch client có scan/bond/reconnect, một GATT in-flight, timeout, Device Info/capability gate, MTU, subscribe/control và trạng thái UI riêng cho connecting/bonding/unavailable.
- ESP-IDF 5.5.5 GATT stub dùng link mã hóa, bond lưu NVS, xử lý repeat-pairing, encoder C golden self-test và thống kê notify.
- Trên Watch 5 Pro + ESP32-S3 thật: encrypted bond, MTU 256, 5,0 Hz, 150/150 notify không lỗi và reconnect sau reset. Xem [report](reports/2026-08-22-gate-b-ble-vertical-slice.md).
- Lifecycle gate đã có test DND cleanup, scheduler/receiver idempotency, corrupt row retention và tracker bounds.
- Chưa nâng toàn hệ thống lên `VERIFIED_DEVICE` vì detector và bài test tích hợp dài hạn còn thiếu.

## Gate C — camera smoke (đạt cho riêng camera 2026-08-22)

- Pinout/XCLK được đối chiếu với source Arduino đã chạy trước đây và được chủ thiết bị xác nhận.
- Firmware `0.2.0-camera-smoke` build/flash trên COM4; OV2640 PID 0x26 đạt 25/25 frame RGB565 240×240, 0 lỗi, 7,45 FPS.
- Watch reconnect bằng bond cũ, đọc capability `0x1e`, MTU 256 và tiếp tục nhận 5,0 Hz; UI giữ `UNAVAILABLE` vì detector bit chưa bật.
- Xem [Gate C camera report](reports/2026-08-22-gate-c-camera-smoke.md). Chỉ hàng camera smoke được nâng `VERIFIED_DEVICE`; detector và posture thật vẫn chưa xác minh.

## Gate C — detector runtime (đạt phạm vi runtime 2026-08-22)

- Firmware `0.3.0-face-detector` dùng `human_face_detect 0.5.0` + `esp-dl 3.3.9`, model MSR+MNP S8; model card có license, source revision và hash byte.
- Binary 2.483.664 byte chạy từ factory partition 4 MiB; NVS/bond giữ nguyên địa chỉ. Build còn 41% partition.
- Trên ESP thật: integer geometry self-test pass, inference đầu 47 ms; cửa sổ khoảng 8 phút 40 giây đạt 3.900 inference, 0 lỗi, trung bình 47,0–47,1 ms.
- Watch đọc capability `0x1f`, `usable=true`, MTU 256; 100/100 notify không lỗi ở 5 Hz và pipeline nhận no-face hợp lệ.
- Resolver xác nhận các version component đã ghim; firmware build pass với 41% app partition trống. Full `verify.ps1` cuối pass 103 task trong 2 phút 48 giây.
- Positive bbox thật sau đó đã được xác nhận. Bài device test phát hiện calibration từng tính cả no-face; ring đã sửa chỉ đếm face-valid và Watch xác nhận đúng `0/20→5/20` ở 5 Hz.
- Xem [Gate C detector report](reports/2026-08-22-gate-c-face-detector.md). Chưa đạt baseline `LIVE`/threshold nên posture và `VERIFIED_DEVICE` toàn hệ thống vẫn bị chặn.

## Resume

1. Đọc `docs/GOVERNANCE.md`, `docs/STATUS.md` và ADR 0002.
2. Chạy `git status --short` và `./verify.ps1`.
3. Không coi synthetic bbox là bằng chứng thiết bị.
