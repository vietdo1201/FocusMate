# MediaPipe landmark posture — device progress

- Ngày chạy: 2026-08-23 (Asia/Saigon).
- Thiết bị: ESP32-S3 N16R8 rev 0.2 + OV2640 trên COM4; Galaxy Watch 5 Pro SM-R925F, API 36, ABI `armeabi-v7a`.
- Mạng: Wi-Fi P154; ESP `192.168.1.17`, Watch `192.168.1.14`; dashboard `http://focusmate.local`.
- Phạm vi bằng chứng: runtime landmark local, frame transport đồng thời Web/Watch và BLE. Posture tổng thể vẫn `IN_PROGRESS / UNVERIFIED` vì chưa chạy đủ tám nhãn, false-positive 2 phút, thermal 30 phút và soak 2 giờ.

## Thay đổi đã chạy trên thiết bị

- Web và Watch đóng gói MediaPipe Pose Landmarker Lite; inference không cần Internet và không thu dataset.
- ESP dùng MSR/MNP 5 điểm cho `FaceMetaV1`; BLE `FrameAccessInfoV1` chỉ đọc sau encryption, token 128-bit xoay theo boot và chỉ nằm trong header HTTP.
- Watch bỏ observation có `esp_uptime_ms` trùng; reboot/boot-id làm reset baseline.
- Baseline landmark tự động cần 20 frame duy nhất trong ít nhất 5 giây. Trước baseline, classifier trả `UNKNOWN`; khi cả Pose và ESP mất mặt, trả `FACE_MISSING` thay vì đoán tư thế.
- Trái/phải dùng landmark giải phẫu sau khi chuẩn hóa mirror/rotation. Shared fixture Kotlin/JavaScript phủ đủ tám state, mirror, xoay 180 độ, stale, duplicate và precedence.

## Lỗi thiết bị thật đã sửa

1. MediaPipe Emscripten loader cần classic worker bootstrap; module worker cũ lỗi `ModuleFactory not set`/`custom_dbg is not defined`.
2. Chrome local HTTP không giải nén Brotli cho WASM; asset runtime/model chuyển sang gzip, khóa URL/version và phục vụ đúng content encoding.
3. HTTP server ESP xử lý handler tuần tự; long-poll browser và Watch làm mỗi client chỉ còn khoảng 2,5 FPS và có `IOException`. Endpoint đổi sang latest-frame-wins không chờ, trả `204` ngay khi chưa có frame mới; frame offer guard giảm 200 ms xuống 150 ms.
4. Socket Watch được ESP báo là IPv4-mapped IPv6 (`::ffff:192.168.1.14`). `remote_ipv4()` cũ chỉ chấp nhận `AF_INET`, làm `/api/watch/frame` trả HTTP 400. Parser hiện chấp nhận đúng mapped form, vẫn từ chối IPv6 tùy ý.

## Bằng chứng chạy đồng thời

- Firmware cuối build bằng ESP-IDF 5.5.5, app partition còn 25%; flash app qua COM4 thành công và esptool xác minh hash dữ liệu.
- Watch reconnect bond cũ, MTU 256, Device Info protocol/framing `1/1`, capability `0x3f`, `FrameAccess read status=0 usable=true`, subscribe 50 dHz; MediaPipe JNI nạp từ APK thành công.
- Sau sửa dual-stack, 20 giây log Watch không có `FocusMateFrame`, `FATAL EXCEPTION` hoặc `AndroidRuntime` error. UI Watch nhận frame và báo nguyên nhân fail-closed `face_missing_both_sources` khi không có người trước camera.
- Khi Web và Watch cùng chạy: detector 5,0 FPS, broker JPEG 5,0 FPS, Web Pose khoảng 38–49 ms/inference, BLE 5,0 Hz, MTU 256, notification failure 0, PSRAM còn khoảng 6,8 MB.
- Web đã tự tạo baseline 20/20 trong một pass có người trước camera và nguồn hiển thị `POSE_LOCAL`. Pass cuối sau reboot không có người, nên cả Web và Watch đúng quy tắc trả `FACE_MISSING`/đang calibration; không dùng pass này làm bằng chứng cho nhãn tư thế.
- Không có ảnh được ghi vào report/storage; screenshot thiết bị chỉ chứa UI trạng thái.

## Artifact cuối đã flash

- Firmware `0.4.2-posture-orientation`, `focusmate_esp.bin`: 3.143.616 byte, SHA-256 `CA9626A005E70C79987937CFF03B561E9F134BE12339C6F0B30DB9BCA0AEEFB6`.
- `focusmate_esp.elf`: 46.290.724 byte, SHA-256 `7D4346CB310AC7BA5A1308DDA616499E659E87FA9C44085FECE50EA7AE601E28`.
- Web asset partition `mp_assets.bin`: 11.010.048 byte, SHA-256 `425DB7B0640FE44F9282345EBAFB66507534B4859F7F25D5C9DB47628CF910B4`.
- Watch app: versionCode 16, versionName `1.15-posture-orientation`.
- Debug APK đã cài: 28.297.293 byte, SHA-256 `4E011704C21B901C69EA57D6C92850C446BD535642C0E87F8A071AFCB4140841`.
- Release unsigned APK: 15.017.896 byte, SHA-256 `A165A5CCA1B7886CC0683746A31B9F03D8A774E0CB80F4B7BE4F3CB4D70B9155`.

## Verification và phần còn thiếu

- Repository contract: 14 tests pass.
- JavaScript landmark classifier: 9/9 pass.
- `verify.ps1`: `BUILD SUCCESSFUL in 3m`, 108 actionable tasks (106 executed, 2 up-to-date); gồm protocol/app unit tests, lint, debug APK và release APK.
- APK debug đúng hash phía trên được cài lại sau clean verify; `dumpsys package` xác nhận versionCode 16/versionName và pass sau cài vẫn có FrameAccess usable, MTU 256, 5 Hz, không có frame error.
- Chưa chạy bài hướng dẫn 90 giây cho đủ tám nhãn; chưa chứng minh độ đúng ≥80%, false-positive ≤5% trong 2 phút, trái/phải trên người thật, low-light, network capture, thermal 30 phút hoặc soak 2 giờ.
- Vì các gate trên còn thiếu, không nâng posture hoặc dashboard tổng thể lên `VERIFIED_DEVICE`.
