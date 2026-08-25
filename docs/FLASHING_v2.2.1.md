# Flash FocusMate v2.2.1

## Cập nhật an toàn, giữ NVS

Xác nhận board ESP32-S3 N16R8 + OV2640 và đúng cổng serial trước khi chạy:

```bash
esptool.py --chip esp32s3 --port <PORT> write_flash \
  0x10000 FocusMate-ESP32S3-v2.2.1-update-app.bin \
  0x410000 FocusMate-ESP32S3-v2.2.1-update-assets.bin
```

Hai file update chỉ ghi phân vùng app và `mp_assets`, do đó giữ nguyên Wi-Fi,
baseline và dữ liệu NVS. Không thêm `erase_flash` khi chỉ cập nhật phiên bản.

Sau khi khởi động, mở `http://focusmate.local/api/status` và xác nhận:

- `build.firmware_version` là `2.2.1`;
- `build.asset_manifest_sha256` có 64 ký tự hex và trùng hash manifest
  được dashboard hiển thị;
- baseline posture cũ còn nguyên; eye-scale revision 3 sẽ tự thu nền khi mặt rõ,
  không che tay và tư thế ổn định.

## Factory image

`FocusMate-ESP32S3-v2.2.1-factory-full.bin` là ảnh đầy đủ để cài mới/phục hồi:

```bash
esptool.py --chip esp32s3 --port <PORT> write_flash \
  0x0 FocusMate-ESP32S3-v2.2.1-factory-full.bin
```

Factory image bao phủ toàn flash và có thể thay thế NVS, Wi-Fi và baseline. Không
dùng file này cho cập nhật thông thường. Kiểm tra `SHA256SUMS.txt` trước khi flash.
