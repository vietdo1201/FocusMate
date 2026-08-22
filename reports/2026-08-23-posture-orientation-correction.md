# Posture orientation và baseline correction — device retest

- Ngày chạy: 2026-08-23 (Asia/Saigon).
- Phần cứng: ESP32-S3 N16R8 rev 0.2 + OV2640 trên COM4; Galaxy Watch 5 Pro SM-R925F.
- Phạm vi: sửa false-positive `HEAD_DOWN` khi ngồi thẳng, trái/phải bị đảo và vertical precedence che lateral lean.
- Gate: posture tổng thể vẫn `IN_PROGRESS / UNVERIFIED`; report này không thay cho đủ scenario, low-light hoặc soak 2 giờ.

## Đính chính bằng chứng cũ

Observation `dy=0,167` từng được ghi là `HEAD_DOWN` trong report 1.14 thực tế xảy ra khi người dùng đang ngồi thẳng. Baseline NVS cũ/tái dùng pre-buffer làm `cy` sai và precedence dọc che lateral motion. Kết quả đó bị rút lại hoàn toàn khỏi device evidence posture.

## Build cuối

- ESP-IDF 5.5.5, firmware `0.4.2-posture-orientation`.
- `focusmate_esp.bin`: 3.389.632 byte, SHA-256 `D814283A8A1EB4C0C6CE9B4FCDF561D32DAA7306772715DE54DF9B792E049B1A`.
- `focusmate_esp.elf`: 46.024.252 byte, SHA-256 `53A03547FA058589D08EFE09542C01E67795A6FBA3953BDB7925BA1CD8907767`.
- Watch app `versionCode 16`, `1.15-posture-orientation`.
- Debug APK: 12.367.263 byte, SHA-256 `C7BB1FCD9FD5D43F4882AB7168482B6C3AD0EBE458E9D3DB5CB14956FE75AFB5`.
- Release unsigned APK: 1.260.134 byte, SHA-256 `947E1EE2823731CE6FF3052BC8FDB53423A554CF133EC6B9501DE42218CB192A`.

## Sửa logic

- Baseline revision 2/fingerprint mới đổi geometry ngang sang phía người ngồi: âm = trái, dương = phải. Firmware tự loại record profile cũ.
- Recalibration xóa baseline RAM/NVS trước, fail-closed về `UNKNOWN`, chờ 1 giây rồi chỉ lấy 20 mẫu sau khi bấm trong tối đa 15 giây. Bbox chạm biên crop bị loại; gap trên 1,5 giây reset progress; lỗi NVS không được báo thành công giả.
- `TOO_CLOSE` vẫn ưu tiên cao nhất. Khi cả ngang và dọc vượt ngưỡng, C++ và Kotlin dùng Q6 integer cross-product để chọn trục có độ vượt chuẩn hóa mạnh hơn. Tie chọn lean như nhau trên cả hai runtime.
- Kotlin dùng median Q6 floor giống C++ cho 20 mẫu; vector nửa micro-unit không còn làm hai runtime lệch nhãn.
- Area ratio Q6 bão hòa ở `uint32 max` trên cả C++/Kotlin thay vì wrap ở baseline cực nhỏ; vector `55835/13` vẫn chắc chắn là `TOO_CLOSE`.
- Mất dữ liệu quá 3 giây, timestamp regression, no-face, confidence thấp, lean hoặc `TOO_CLOSE` đều cắt timer `SLUMPED`; thời gian stale không được tính là 5 giây liên tục.
- Shared fixture đổi sang `posture_geometry_v2.tsv`, gồm 10 vector kể cả mixed-axis và equality boundary.

## Bằng chứng thiết bị

- Final binary build và flash COM4 thành công; image hash khi ghi flash hợp lệ, app partition còn 19%.
- Boot final chạy qua canonical/self-test và geometry assertions, camera nhận OV2640 PID `0x26`, smoke 25 frame hợp lệ/0 lỗi, Wi-Fi tự nối P154 tại `192.168.1.17` và mDNS `focusmate.local`.
- Trong pass correction ngay trước hardening storage/arithmetic cuối, baseline revision 2 được thu mới 20/20 từ các frame sau khi bấm. Ở tư thế người dùng xác nhận ngồi thẳng, dashboard trả `NORMAL` với baseline `cx=0,447`, `cy=0,404`, `dx=0,002`, `dy=-0,004`, confidence khoảng `0,899`. Đây là bằng chứng sửa đúng root cause, nhưng không được dùng thay cho scenario retest trên artifact hash cuối.
- Sau clean build, APK v16 được cài bằng ADB và `dumpsys package` xác nhận đúng version. Watch reconnect bonded GATT, MTU 256, Device Info protocol/framing `1/1`, capability `0x1f`, subscribe observation và gửi START 50 dHz.
- Artifact hash cuối đã flash lại và boot tới dashboard thành công; status sau boot báo detector khoảng 2,8 FPS, 300 ms/inference, Wi-Fi P154, BLE `LIVE`, MTU 256 và 0 notification failure. Baseline cuối hiện cố ý là chưa hiệu chỉnh (`UNKNOWN`, revision v2), chờ người dùng ngồi đúng vị trí để thu mới.
- Không có crash Android trong log kiểm tra sau cài APK hash cuối.
- Chưa ghi pass trái/phải/cúi/quá gần/gù trên build cuối: người dùng chưa xác nhận và giữ lần lượt từng động tác sau calibration. Số đo lúc người dùng/camera đang di chuyển không được dùng làm evidence.

## Regression local

```text
python -m unittest discover -s tests -p 'test_*.py'
Ran 8 tests ... OK

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1
BUILD SUCCESSFUL in 2m 19s
103 actionable tasks: 101 executed, 2 up-to-date
```

Focused Kotlin tests còn phủ Q6 dominance equality, median odd-sum boundary, saturated area ratio, stale/confidence interruption, lateral interruption và fixture v2. Firmware final được build lại sau các test logic và flash đúng artifact có hash ở trên.

## Còn thiếu trước `VERIFIED_DEVICE`

- Trên một baseline mới khi người dùng ngồi đúng vị trí, giữ lần lượt `LEAN_LEFT`, `LEAN_RIGHT`, `HEAD_DOWN`, `TOO_CLOSE`, `SLUMPED` và face return để ghi số đo thật.
- Xác nhận nhãn Watch/UI sau khi đóng hoặc xử lý hộp thoại nghỉ hiện tại; không thay đổi quyết định Rule Engine chỉ để test posture.
- Chạy low-light và soak Wi-Fi + BLE + detector 2 giờ; đo notification failures, heap/PSRAM, nhiệt và nguồn.
