# Gate C — OV2640 camera smoke trên ESP32-S3 thật

- Ngày chạy: 2026-08-22 (Asia/Saigon)
- Kết luận phạm vi này: **PASS / VERIFIED_DEVICE cho camera smoke-test**
- Chưa kết luận: face detector, bbox thật, model quality, thermal/power dài hạn và `VERIFIED_DEVICE` toàn hệ thống.

## Phần cứng và cấu hình được chốt

- ESP32-S3 N16R8, QFN56 revision v0.2, flash 16 MB, octal PSRAM 8 MB.
- Camera OV2640 PID `0x26`, SCCB address `0x30`, module 18 chân có oscillator riêng.
- `pin_xclk=-1`, tần số sensor khai báo 24 MHz.
- Pin: SDA 1, SCL 2, D0..D3 4/5/6/7, D4..D7 15/16/17/18, PCLK 39, RESET 40, HREF 41, VSYNC 42, PWDN 38.
- RGB565 240×240, một framebuffer 115.200 byte trong PSRAM.
- Source Arduino đã chạy trước đây dùng để đối chiếu: `C:\Users\vietdo1201\Documents\Arduino\sketch_aug12a\sketch_aug12a.ino`, SHA-256 `16C8DF6F47ECAACB807F503F053DB55B3002FA08A76F8DCF1B5F123667D49AAF`.

## Build và flash

```powershell
idf.py reconfigure build
idf.py -p COM4 flash monitor
```

- ESP-IDF: 5.5.5.
- App version: `0.2.0-camera-smoke`.
- `focusmate_esp.bin`: 628.528 byte; SHA-256 `54709BF160EEE87818D21E92D55A6BBF7801F4CA888C87BA557050EFA341C7F4`.
- `focusmate_esp.elf`: SHA-256 `E58879E32FE5B033A76062BC1C9A6F9FE8AF6EB6C5A6EEB670712933E973DD72`.
- Binary dùng 628.528/1.048.576 byte app partition; còn 40%.
- Esptool xác nhận hash sau khi ghi bootloader, partition table và app. NVS không bị erase nên bond BLE được giữ.

## Kết quả camera

```text
Camera PID=0x26 VER=0x42 MIDL=0x7f MIDH=0xa2
Detected OV2640 camera at address=0x30
Allocating 115200 Byte frame buffer in PSRAM
OV2640 smoke PID=0x0026 format=RGB565 size=240x240 valid=25 errors=0 fps=7.45
```

Acceptance `valid >= 24/25` và `errors <= 1` đạt: **25/25, 0 lỗi, 7,45 FPS**. Firmware chỉ kiểm metadata/độ dài rồi trả framebuffer ngay; không ghi hay truyền ảnh.

Log trích dẫn: [2026-08-22-camera-smoke-com4.log](../artifacts/2026-08-22-camera-smoke-com4.log).

## Tương tác với Watch/GATT sau camera init

- Galaxy Watch 5 Pro `SM-R925F`, Android 16/API 36.
- Bond cũ được dùng lại; reconnect thành công sau flash, link encryption status 0.
- Device Info trên Watch: protocol 1, framing 1, capability `0x1e`, `usable=false`.
- Bit camera-ready đã bật; bit detector-ready vẫn tắt nên pipeline không được phép vào LIVE.
- MTU 256, rate 5,0 Hz; firmware ghi nhận 100 observation / 100 notify attempt / 0 failure.
- APK evidence: versionCode 14, `1.13-watch-rules-v2`, SHA-256 `C7AE3E80472A6E3CA8161F13C82B87EAF3F3302E71CD4E480F830E3EF2283073`.
- UI nói rõ `camera OK; detector chưa sẵn sàng`: [PNG](../artifacts/focusmate-camera-capability-status.png), [UI XML](../artifacts/focusmate-camera-capability-status.xml).

## Quyết định gate

- Camera smoke-test: `IMPLEMENTED / TARGET / VERIFIED_DEVICE`.
- Firmware tổng thể: vẫn `IN_PROGRESS`; detector chưa được tích hợp.
- Detector/model: `NOT_STARTED / UNVERIFIED`; không tái sử dụng model demo không đạt quality gate.
- Chưa nâng posture thật hoặc toàn hệ thống lên `VERIFIED_DEVICE`.
