# Flash FocusMate v2.3.0

Tải từ [release v2.3.0](https://github.com/vietdo1201/FocusMate/releases/tag/v2.3.0).
Chỉ dùng các lệnh này sau khi kiểm tra đúng board ESP32-S3 N16R8, đúng cổng serial,
partition table và SHA-256 trong `SHA256SUMS.txt`. Hồ sơ phát hành và phạm vi
kiểm chứng được tổng hợp tại [STATUS.md](STATUS.md).

## Cập nhật giữ NVS

```bash
esptool.py --chip esp32s3 --port <PORT> write_flash \
  0x10000 FocusMate-ESP32S3-v2.3.0-update-app.bin \
  0x410000 FocusMate-ESP32S3-v2.3.0-update-assets.bin
```

Không dùng factory image cho cập nhật thông thường. `FocusMate-ESP32S3-v2.3.0-factory-full.bin` có thể ghi đè cấu hình Wi-Fi, baseline và NVS; chỉ dùng cho cài mới/phục hồi sau khi đã sao lưu dữ liệu cần giữ.

Sau flash, đối chiếu firmware version `2.3.0`, asset manifest hash, BLE/HTTP local và chạy smoke test trước phiên dài.
