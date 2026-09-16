# Phát hành FocusMate APK

## Bản hiện hành

[FocusMate v2.3.0](https://github.com/vietdo1201/FocusMate/releases/tag/v2.3.0)
đã phát hành tại commit `d7c072e`: Watch `versionName 2.3.0`, `versionCode 26`,
firmware `2.3.0`. Tải `FocusMate-Wear-v2.3.0.apk`, `SHA256SUMS.txt` và
`APK-SIGNATURE.txt` cùng release; kiểm tra hash trước khi cài.

Firmware có [hướng dẫn flash riêng](docs/FLASHING_v2.3.0.md). Source `.tar.gz`,
SBOM, notices và license cũng nằm trong release. Xem thêm
[hồ sơ kiểm chứng theo phiên bản](docs/STATUS.md).

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

Script chạy bootstrap hash-pinned trước Gradle. Bản thân Gradle cũng chạy
`:app:verifyWearModels` trước đóng gói, nên model thiếu/sai hash không thể âm thầm
tạo APK. APK signed được tạo tại
`wear/app/build/outputs/apk/release/app-release.apk`.

Build kiểm tra từ source không cần signing key của tác giả: chạy bootstrap rồi
`:app:assembleDebug` hoặc `:app:assembleRelease`; bản release không có bốn biến
trên là unsigned và chỉ dùng để xác minh build.

## Cài lên Galaxy Watch

1. Bật Developer options, ADB debugging và Wireless debugging trên Watch.
2. Pair/connect Watch bằng `adb pair` và `adb connect`.
3. Chạy `adb install -r <đường-dẫn-apk>`.

Nếu báo lỗi chữ ký, dừng và đối chiếu certificate; không gỡ app hoặc xóa dữ liệu
để bỏ qua lỗi. Giữ dữ liệu phiên trước khi kiểm tra migration SQLite của 2.3.0.

Xem `docs/STATUS.md` để biết chính xác phần nào đã `VERIFIED_DEVICE`; không suy
diễn trạng thái chỉ từ việc APK cài được hoặc workflow build thành công.
