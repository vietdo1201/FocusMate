# FocusMate Wear OS

Ứng dụng Wear OS standalone, build bằng JDK 17 và Android SDK API 35, gồm hai module:

- `app`: phiên học, motion/HR, reminder, BLE/local-frame client, Pose/Face
  Landmarker, posture và yawn advisory.
- `protocol`: codec/versioned contract dùng chung với ESP32-S3, gồm
  `FaceObservationV1`, framing, Frame Access và Yawn Sync V2.

Chuẩn bị model và build từ root:

```powershell
python tools/bootstrap_assets.py
cd wear
./gradlew.bat --no-daemon clean :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Gradle tự xác minh hash hai model trước `preBuild` nhưng không tự tải qua mạng.
Thiếu/sai model thì quay lại root và chạy bootstrap. APK hiện giới hạn ABI
`armeabi-v7a`, tương ứng Galaxy Watch 5 Pro SM-R925F dùng trong hồ sơ; các Watch
hoặc ABI khác chưa được xác nhận bằng kiểm thử thiết bị của dự án.

APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`. APK release chỉ
được ký khi bốn biến môi trường `FOCUSMATE_RELEASE_*` được cung cấp; không có
khóa thì Gradle tạo APK release unsigned để kiểm tra build.

BLE dùng rate thích ứng 5/2/1 Hz theo trạng thái màn hình/nhiệt. HTTP frame và
yawn fallback chỉ chạy trong mạng local, token chỉ ở header và không được log.
Xem [status](../docs/STATUS.md), [build](../docs/BUILDING.md) và
[GATT profile](../docs/GATT_PROFILE.md).
