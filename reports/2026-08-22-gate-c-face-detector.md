# Gate C — face detector runtime trên ESP32-S3 thật

## Kết luận

- **PASS / VERIFIED_DEVICE** cho phạm vi: load model, inference no-face lặp lại,
  positive bbox path, capability gate và transport sang Galaxy Watch 5 Pro.
- **Chưa pass**: baseline ổn định/LIVE, chất lượng low-light, threshold hình học
  theo từng tư thế, nhiệt/nguồn và phiên 2–3 giờ. Vì vậy chưa nâng toàn hệ thống lên
  `VERIFIED_DEVICE`.

## Build và phần cứng

- ESP-IDF `5.5.5`; app `0.3.0-face-detector`.
- ESP32-S3 rev `0.2`, flash 16 MB, octal PSRAM 8 MB, CPU 240 MHz.
- OV2640 PID `0x26`, RGB565 240×240, module dùng oscillator riêng.
- Factory partition 4 MiB; NVS giữ tại `0x9000`.
- Firmware BIN: 2,483,840 byte; SHA-256
  `B221F4469B3FBE192F9BDF1CE952252B577036761FA22195A5BE5B856FA5DC24`.
- ELF SHA-256:
  `B4CEC48231A847E89E2273BBB4FA25BB0629C821C1F85B81B7914674D284F43D`.
- App partition còn trống `0x1a1980` byte, khoảng 41%.
- APK dùng cho device retest `0/20→5/20`: SHA-256
  `EFE0AFE9945764D278B78856CB4BA8DB7CE981978044F63B56E5CD4FFB01E060`.
- Debug APK cuối đã cài sau thay đổi wording-only: 12.361.247 byte, SHA-256
  `1D875AE663C9B09869A16D2357E606F2DD4D028B8C95629AF44FA8CCFAA70159`.

Lệnh:

```text
idf.py reconfigure
idf.py build
idf.py -p COM4 flash
idf.py -p COM4 monitor
```

Lần `reconfigure` cuối giải lại manifest và xác nhận đúng `esp-dl 3.3.9`,
`esp32-camera 2.1.7`, `human_face_detect 0.5.0`, sau đó `idf.py build` pass.

## Model và benchmark

- `human_face_detect 0.5.0`, `esp-dl 3.3.9`, model MSR+MNP S8.
- Hash từng model, license và giới hạn sử dụng nằm trong
  [`../firmware/MODEL_CARD.md`](../firmware/MODEL_CARD.md).
- Boot gate: `integer geometry self-test passed`.
- Inference thật đầu tiên: 47 ms, no-face.
- Cửa sổ quan sát khoảng 8 phút 40 giây: 3.900 inference, 0 inference failure,
  latency trung bình 47,0–47,1 ms; camera khoảng 7,45 FPS.
- Có diagnostic DMA overflow thoáng qua của camera nhưng không làm tăng bộ đếm
  inference failure. Cần phiên dài để quyết định tính ổn định cuối cùng.
- Log COM4 đã lọc, không chứa frame/identifier:
  [`../artifacts/2026-08-22-face-detector-com4.log`](../artifacts/2026-08-22-face-detector-com4.log).

## Positive bbox và calibration

- Detector đã phát bbox dương thật; confidence quan sát được tới khoảng `0.99`,
  latency positive path khoảng 55–57 ms.
- Một engineering run đạt bộ đếm 400 positive detection. Đây là dữ liệu có
  chuyển động, chưa phải baseline ổn định hay bộ threshold theo tư thế.
- Device test phát hiện ring calibration cũ tính cả `no-face`, làm UI đầy giả
  `20/20`. Ring đã sửa để chỉ giữ face-positive đủ confidence/quality.
- APK sửa trên Watch thể hiện đúng: no-face giữ `0/20`; sau đúng 5 positive
  observation tăng thành `5/20`, MTU 256 và 5,0 Hz.
- Log số đã lọc:
  [`../artifacts/2026-08-22-positive-bbox-calibration.log`](../artifacts/2026-08-22-positive-bbox-calibration.log).

## Watch và transport

- Galaxy Watch 5 Pro `SM-R925F`, Wear OS 6 / Android 16, API 36.
- Bond mã hóa cũ được giữ sau đổi partition/flash.
- Watch đọc Device Info: protocol 1, framing 1, capability `0x1f`,
  `usable=true`.
- MTU 256, subscribe thành công, START 50 dHz.
- Firmware ghi 100 observation / 100 notify attempt / 0 notify failure ở 5 Hz.
- Watch nhận no-face và face-positive hợp lệ; calibration chỉ đếm face-valid
  sau fix. Run này chưa giữ một tư thế đủ ổn định để chuyển sang `LIVE`.
- Log pipeline Watch đã lọc:
  [`../artifacts/2026-08-22-watch-detector-pipeline.log`](../artifacts/2026-08-22-watch-detector-pipeline.log).

## Privacy và phần còn thiếu

- Không frame/crop/landmark/embedding/identifier rời ESP; chỉ observation JSON.
- Positive bbox đã được xác nhận nhưng chưa phải dữ liệu baseline ổn định.
- Gate kế tiếp phải thu baseline theo từng tư thế, low-light, nhiệt/nguồn
  và phiên 2–3 giờ trước report `VERIFIED_DEVICE` toàn hệ thống.

## Regression gate

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1
BUILD SUCCESSFUL in 2m 48s
103 actionable tasks: 101 executed, 2 up-to-date
```

Gate này gồm `:protocol:test`, app unit tests, lint, debug APK và release APK.
