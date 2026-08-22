# Posture geometry confidence/debounce device retest

- Ngày chạy: 2026-08-23 (Asia/Saigon)
- Phần cứng: ESP32-S3 N16R8 rev 0.2 + OV2640 trên COM4; Galaxy Watch 5 Pro đã bond/subscribed BLE.
- Phạm vi: sửa hiện tượng dashboard chủ yếu chỉ hiện `NORMAL`/`UNKNOWN`, đồng bộ raw classifier firmware/Watch và kiểm tra regression.
- Gate: vẫn `IN_PROGRESS / UNVERIFIED` cho posture tổng thể; chưa có đủ scenario thật và soak 2 giờ.

> **Đính chính bắt buộc (2026-08-23):** người dùng xác nhận observation `dy=0,167` bên dưới được ghi khi đang ngồi thẳng, không phải lúc cúi đầu. Baseline cũ bị lệch/tái dùng nên đó là false positive. Mọi tuyên bố “đã nhận HEAD_DOWN thật” trong bản report này bị rút lại; chỉ bằng chứng build, BLE và detector vẫn có giá trị. Bản sửa kế tiếp là firmware `0.4.2-posture-orientation`, baseline revision 2.

## Build cuối

- ESP-IDF 5.5.5, firmware `0.4.1-shadow-posture`.
- `focusmate_esp.bin`: 3.385.344 byte, SHA-256 `78A193C02659E63C29C69A2A5A7A6D426013A5391103E4CB8BAE3C8BA54FF5A5`.
- `focusmate_esp.elf`: 46.018.000 byte, SHA-256 `E5D581CCD0139CB5A056F3BB6A4F5FE54C00D14E9274DABAE075EAE0971CA2EC`.
- App partition còn 19%; final image validation hash hợp lệ.
- Watch source `versionCode 15`, `1.14-posture-geometry`.
- Debug APK: 12.361.799 byte, SHA-256 `73E65C4179F51E6CE2DD048CF12ED21AF4DFB4C42DAD1A16E4C6EDF1CD838DA2`.
- Release unsigned APK: 1.256.706 byte, SHA-256 `94192A11F83288DDEC6BEBD7DEE350985AC0D30F9EDF1F0AF633B681D31114BB`.
- `adb devices -l` trả danh sách rỗng, nên APK version 15 **chưa được cài** lên Watch và không được tính là device evidence.

## Sửa classifier

- Calibration vẫn yêu cầu confidence `>=0,70`; live geometry sau baseline dùng `>=0,50`. Mốc live dựa trên bbox thật đúng đã quan sát khoảng `0,593`, không hạ xuống floor detector `0,35`.
- `UNKNOWN` và `FACE_MISSING` đi qua cùng stabilizer ba mẫu như các state khác; stale quá 3 giây vẫn về `UNKNOWN` ngay.
- API/UI tách `raw_confidence` khỏi confidence của state ổn định, nên nhãn và confidence chính không còn trỏ tới hai observation khác nhau.
- `SLUMPED` chỉ đếm 5 giây liên tục khi `dy >= 0,18`. `HEAD_DOWN` nhẹ, `TOO_CLOSE`, no-face và mẫu lỗi reset timer.
- Precedence và ngưỡng geometry giữ đúng contract hiện tại: too-close `1,60x`; head-down `dy >= 0,12`; slumped `dy >= 0,18`; lean `|dx| >= 0,15`.

## Bằng chứng COM4 + dashboard

- Final firmware flash thành công, NVS Wi-Fi/bond được giữ; LAN trở lại `P154`, `192.168.1.17`, `focusmate.local`.
- Watch tự reconnect sau reboot, link mã hóa/subscription hoạt động, MTU lên 256; log đạt ít nhất 150 observation sau final boot với 0 notification failure.
- Self-test của final binary chạy trực tiếp trên ESP và assert đủ `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, timer interruption và debounce `UNKNOWN`.
- Final dashboard thật đạt khoảng 2,9 detector FPS, 296 ms/inference, BLE `LIVE / MTU 256 / 0 failure`; camera observation thật lên state ổn định `NORMAL` với confidence khoảng `0,766`.
- Trước lần bump metadata/version cuối, classifier từng phát `HEAD_DOWN` với `dy=0,167`, confidence `0,86`, stable khoảng 14,8 giây. Kết quả posture này **không hợp lệ** vì người dùng thực tế đang ngồi thẳng; artifact trung gian SHA-256 `AE9DA2A6E4B9F1891825FE23F831B577C4DDC60E7D09597177D8AA94E06B14A3` chỉ được giữ để truy vết lỗi, không làm device evidence. BLE MTU 256/0 failure trong cùng run vẫn hợp lệ.
- Final boot cũng chứng minh `FACE_MISSING` chỉ lên stable sau ba mẫu (`stable=FACE_MISSING after=3 samples`).

## Regression

```text
python -m unittest discover -s tests -p 'test_*.py'
Ran 7 tests ... OK

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1
BUILD SUCCESSFUL in 2m 39s
103 actionable tasks: 101 executed, 2 up-to-date
```

Kotlin tests mới phủ live/calibration confidence boundary, toàn bộ geometry fixture, precedence `TOO_CLOSE`, và `SLUMPED` 4999/5000 ms với interruption. Firmware self-test phủ cùng logic trên CPU thật.

## Phần còn thiếu trước `VERIFIED_DEVICE`

- Chạy lại trên build cuối với người thật: `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, low-light và face return/reconnect.
- Recalibrate sau khi camera/ghế đổi vị trí; baseline revision 2 không tái dùng pre-buffer và tự loại profile cũ.
- Cài APK 1.15 lên Watch khi ADB trở lại và xác nhận Watch raw classifier khớp dashboard.
- Soak Wi-Fi + BLE + detector 2 giờ và xác nhận posture không đổi Rule Engine.
