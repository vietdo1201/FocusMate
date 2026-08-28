# Build từ mã nguồn

## Yêu cầu

- Python 3.11 trở lên, Node.js 20 trở lên và Git.
- JDK 17 + Android SDK cho Wear OS.
- ESP-IDF 5.5.5 cho ESP32-S3.

## Chuẩn bị artifact AI

```bash
python tools/bootstrap_assets.py
```

Script tải MediaPipe Tasks Vision, Pose Landmarker Lite và Face Landmarker từ
URL cố định, xác minh SHA-256 rồi tạo asset Android/firmware. Hash sai làm build
dừng; app/firmware không tải model lúc runtime.

## Android/Wear

```bash
cd wear
./gradlew --no-daemon clean :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Trên Windows dùng `gradlew.bat`. Release signing chỉ đọc biến môi trường, không
đọc keystore hoặc mật khẩu từ repository.

## ESP32-S3

```bash
. "$IDF_PATH/export.sh"
idf.py -C firmware fullclean
idf.py -C firmware build
```

Build dùng cấu hình mặc định cho ESP32-S3 N16R8 + OV2640; không cần sửa header.
Chỉ flash sau khi người vận hành xác nhận đúng board.

## Cập nhật thiết bị mà không xóa NVS

Release `v2.2.2` phát hành riêng app firmware và phân vùng Web assets. Sau khi
xác nhận đúng cổng serial và đúng board, có thể cập nhật hai phân vùng này:

```bash
esptool.py --chip esp32s3 --port <PORT> write_flash \
  0x10000 FocusMate-ESP32S3-v2.2.2-update-app.bin \
  0x410000 FocusMate-ESP32S3-v2.2.2-update-assets.bin
```

Lệnh này không ghi vào phân vùng NVS. File `factory-full` chỉ dành cho cài mới
hoặc phục hồi toàn bộ và có thể thay thế NVS/Wi-Fi/baseline; không dùng nó cho
cập nhật thông thường. Xem [FLASHING_v2.2.2.md](FLASHING_v2.2.2.md).

## Kiểm tra toàn bộ

```bash
./verify.sh       # Linux/macOS
./verify.ps1      # Windows
```

Lệnh chạy bootstrap, Python/Node contracts, Gradle test/lint/APK và firmware
clean build. Dependency Android được khóa bằng lockfile và verification metadata;
ESP component được pin trong `firmware/main/idf_component.yml`.
