# Phát hành FocusMate APK

## Signing key

APK release phải dùng cùng một key cho mọi bản cập nhật. Không commit keystore/password. Gradle đọc bốn biến môi trường:

- `FOCUSMATE_RELEASE_STORE_FILE`
- `FOCUSMATE_RELEASE_STORE_PASSWORD`
- `FOCUSMATE_RELEASE_KEY_ALIAS`
- `FOCUSMATE_RELEASE_KEY_PASSWORD`

GitHub Actions cần thêm bốn repository secrets tương ứng, trong đó `FOCUSMATE_RELEASE_KEYSTORE_BASE64` là nội dung keystore mã hóa base64.

## Build local

Sau khi đặt các biến môi trường:

```powershell
./release.ps1
```

APK signed được tạo tại `soucre_code/from_On_Hand_3_android_wear/app/build/outputs/apk/release/app-release.apk`.

## Cài lên Galaxy Watch

1. Bật Developer options, ADB debugging và Wireless debugging trên Watch.
2. Pair/connect Watch bằng `adb pair` và `adb connect`.
3. Chạy `adb install -r <đường-dẫn-apk>`.

Đây là bản experimental; BLE/camera posture chưa hoạt động trên phần cứng thật.
