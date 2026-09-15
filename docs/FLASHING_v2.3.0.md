# Flash FocusMate v2.3.0 (candidate)

Chỉ dùng các lệnh này sau khi kiểm tra đúng board ESP32-S3 N16R8, đúng cổng serial và SHA-256 trong bộ artifact. Bản candidate chưa được flash/xác nhận thiết bị tại thời điểm viết.

## Cập nhật giữ NVS

```bash
esptool.py --chip esp32s3 --port <PORT> write_flash \
  0x10000 FocusMate-ESP32S3-v2.3.0-update-app.bin \
  0x410000 FocusMate-ESP32S3-v2.3.0-update-assets.bin
```

Không dùng factory image cho cập nhật thông thường. `FocusMate-ESP32S3-v2.3.0-factory-full.bin` có thể ghi đè cấu hình Wi-Fi, baseline và NVS; chỉ dùng cho cài mới/phục hồi sau khi đã sao lưu dữ liệu cần giữ.

Sau flash, đối chiếu firmware version `2.3.0`, asset manifest hash, BLE/HTTP local và chạy smoke test trước phiên dài.
