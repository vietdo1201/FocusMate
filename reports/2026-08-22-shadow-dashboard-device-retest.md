# Realtime shadow dashboard — device retest

> **Lịch sử/superseded:** pre-buffer 10 giây và deadline 8 giây ghi trong report này đã bị loại ở firmware `0.4.2-posture-orientation`. Calibration hiện xóa baseline trước, chỉ lấy 20 mẫu sau khi bấm và loại bbox chạm biên. Không dùng cơ chế cũ làm contract hiện hành.

- Ngày chạy: 2026-08-22 (Asia/Saigon)
- Phần cứng: ESP32-S3 N16R8 rev 0.2 + OV2640 trên COM4; Galaxy Watch 5 Pro đã bond và subscribe BLE.
- Phạm vi pass: camera đúng chiều, JPEG dashboard local, bbox mặt thật, Wi-Fi P154, mDNS, BLE chạy đồng thời và regression local.
- Chưa pass: baseline posture, đủ scenario tư thế/low-light và soak 2 giờ. Không nâng posture/toàn hệ thống lên `VERIFIED_DEVICE`.

## Build đã flash

- ESP-IDF `5.5.5`, app `0.4.0-shadow-web`.
- `focusmate_esp.bin`: 3.381.552 byte, SHA-256 `2B6E2A4D072847F21E0D606A22E5EA8BC019180D986A0CD4002A814DCD0E946F`.
- `focusmate_esp.elf`: 46.009.092 byte, SHA-256 `08C4BC72F0355CC012EB6F5C2D06F1D2810D08C6837DF94012F552D9D686ED1C`.
- Model: `ESPDET_PICO_224_224_FACE`; model hash và license nằm trong [`../firmware/MODEL_CARD.md`](../firmware/MODEL_CARD.md).
- Debug APK từ regression build: 12.361.247 byte, SHA-256 `1D875AE663C9B09869A16D2357E606F2DD4D028B8C95629AF44FA8CCFAA70159`, versionCode 14 / `1.13-watch-rules-v2`.
- ADB không còn thấy thiết bị ở lần truy vấn cuối, nên report này không ghi một lần cài APK mới. Bằng chứng Watch của run là kết nối BLE mã hóa/subscription đang hoạt động; thông tin model/OS đã ghi ở report Gate C trước đó.

## Camera và detector

- OV2640 PID `0x26`; direct JPEG QVGA `320×240`, quality 8, sensor `hmirror=1` + `vflip=1` để sửa đúng mount 180°.
- Smoke cuối: `25/25` JPEG hợp lệ, `0` lỗi theo gate, `10,58 FPS`. Có diagnostic `FB-OVF/NO-EOI` lúc sensor vừa ổn định nhưng các frame acceptance đều hợp lệ.
- JPEG được giải mã RGB888 đầy đủ rồi center-crop `240×240` để không kéo giãn mặt khi ESPDet resize về 224×224; bbox được map lại tọa độ QVGA.
- Run ngắn của cùng đường detector đạt khoảng `294–304 ms`/inference, không có inference failure. Bbox thật bám đúng mặt, confidence quan sát tới `0,909907`.
- Khi ghế không có người, detector giữ no-face qua hơn 50 inference liên tiếp. Khi người trở lại, positive bbox xuất hiện lại mà không reboot.
- Ảnh cuối cho thấy bbox đúng nhưng khuôn mặt bị cắt sát mép trên và nguồn sáng bên phải làm cháy sáng; confidence tức thời `0,593`. Do đó classifier trả `UNKNOWN` thay vì kết luận posture từ mẫu yếu.

## Dashboard, Wi-Fi và BLE đồng thời

- URL LAN: `http://focusmate.local`, DHCP `192.168.1.17`, SSID `P154`, RSSI khoảng `-28..-32 dBm`.
- Dashboard đo khoảng `2,7–3,1` detector FPS và `2,7–3,0` web FPS; JPEG/bbox hiển thị đúng chiều.
- Fix trạng thái link Wi-Fi ngăn task reconnect gọi `esp_wifi_connect()` trong khoảng đã associate nhưng chưa nhận DHCP. Boot cuối không còn warning reconnect đó.
- Galaxy Watch giữ `LIVE`, MTU 256. Run đồng thời ghi ít nhất `350` observation / `350` notify attempt / `0` notify failure ở rate yêu cầu 5 Hz.
- Một client frame, auth session, frame-only-in-RAM và shadow-only giữ nguyên; web không ghi posture về Watch hay Rule Engine.

## Calibration và quyết định gate

- Deadline được sửa từ 5 thành 8 giây vì ESPDet khoảng 3 FPS không thể tạo 20 mẫu trong 5 giây.
- Firmware thêm pre-buffer 20 bbox confidence ≥0,70 trong tối đa 10 giây; nút calibrate dùng ngay pre-buffer nếu đủ và geometry ổn định, nếu chưa thì thu tiếp.
- Khung thực tế cuối chỉ đạt `2/20`, reason `low_confidence`, do mặt ở sát mép trên/ánh sáng gắt và người không giữ thẳng liên tục. Baseline không được lưu: **đúng fail-safe**.
- Cần chỉnh camera để toàn bộ đầu/mặt nằm trong khung, tránh đèn chiếu thẳng rồi ngồi thẳng đủ vài giây để nghiệm thu baseline/posture.

## Regression

```text
python -m unittest discover -s tests -p 'test_*.py'
Ran 6 tests ... OK

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1
BUILD SUCCESSFUL in 2m 42s
103 actionable tasks: 101 executed, 2 up-to-date
```

Gate hiện tại: dashboard/camera/detector/BLE concurrent **đã chạy trên thiết bị thật nhưng vẫn `IN_PROGRESS`**; posture tổng thể **`UNVERIFIED`** cho tới khi baseline, scenario và soak pass.
