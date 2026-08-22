# Báo cáo dọn thư mục Android/Wear OS

Ngày thực hiện: 2026-08-18  
Phạm vi: `soucre_code/from_On_Hand_3_android_wear`

## Căn cứ quyết định

Phần này ghi lại căn cứ tại thời điểm cleanup. Sau khi Git baseline được tạo,
quyền ưu tiên hiện hành nằm trong [`../../docs/GOVERNANCE.md`](../../docs/GOVERNANCE.md).

> **Addendum 2026-08-22 (historical, không sửa thân báo cáo).** Ba câu trong báo cáo
> này đã lệch so với hiện tại và **không** được dùng làm nguồn chuẩn:
> `settings.gradle.kts` nay là `:app` + `:protocol` (không còn `:shared`); repo Git
> đã được khởi tạo lại tại `D:\FocusMate-main` nên commit `726430a` không tồn tại
> trong lịch sử hiện hành; thiết bị kiểm chứng nay là Galaxy Watch 5 Pro
> ([ROADMAP.md](ROADMAP.md)). Trạng thái hiện hành: [`../../docs/STATUS.md`](../../docs/STATUS.md).

1. `../../help.md` là nguồn yêu cầu kỹ thuật ưu tiên cao nhất.
2. `../../Readme.md` xác định hai thành phần chính là Galaxy Watch FE và ESP32-S3.
3. `../../docs/Vi_du.md` mô tả event posture/yawn trong một phiên.
4. `../../data/So_do_chan.md` xác định phần cứng OV2640 và ESP32-S3.
5. README/PHONE_WEAR_SYNC cũ mô tả Phone-Wear, Gemma và llama.cpp; phần này mâu
   thuẫn với kiến trúc local face detector trên ESP32-S3 nên không còn là nguồn chuẩn.

Thư mục không nằm trong Git repository tại thời điểm dọn. Người dùng đã cấp quyền
dọn dẹp; các thay đổi cấu hình, tệp văn bản và thư mục nhị phân đã được áp dụng.
Script cleanup đã kiểm tra đường dẫn nằm trong phạm vi mục tiêu trước khi xóa.

## Đã giữ

| Thành phần | Lý do |
|---|---|
| `app/` | Có UI Wear OS, session, heart rate/motion, reminder, persistence và tests có thể tái sử dụng. |
| `shared/` | Baseline `app` còn phụ thuộc; sẽ bóc legacy sau khi BLE protocol thay thế có test. |
| `gradle/`, wrapper, Gradle config | Cần để build/test lặp lại. |
| Unit/instrumentation tests và resources | Bảo vệ phần code được giữ trong quá trình chuyển đổi. |
| `README.md` | Đã viết lại để phản ánh đúng trạng thái và kiến trúc đích. |

Một số lớp Gemma/Phone còn nằm trong `app`/`shared` vì đang đan xen với session,
repository và reminder. Giữ tạm an toàn hơn việc làm hỏng baseline; kế hoạch bóc theo
adapter và test được ghi trong `IMPLEMENTATION_PLAN.md`.

## Đã xóa vật lý

| Thành phần | Cách xác nhận |
|---|---|
| .claude/settings.local.json | Đã xóa bằng patch; thư mục .claude hiện rỗng. |
| PHONE_WEAR_SYNC.md | Đã xóa bằng patch. |
| local.properties | Đã xóa bằng patch; Android Studio sẽ tạo lại theo máy. |
| app/build/, shared/build/ | Đã xóa bằng gradlew clean. |

## Đã xóa vật lý các phần lệch kiến trúc

| Thành phần | Quy mô trước khi xóa | Lý do |
|---|---:|---|
| `mobile/` | 78 tệp, khoảng 242.05 MiB | Phone companion, cloud/gateway/Gemma không thuộc kiến trúc đích. |
| `gemma_model/` | 3 tệp, khoảng 241.39 MiB | Asset pack chứa một bản GGUF Gemma 241 MiB. |
| `third_party/` | 2,967 tệp, khoảng 147.77 MiB | Bản sao llama.cpp chỉ phục vụ Gemma trên điện thoại. |
| `.idea/` | 18 tệp, khoảng 0.14 MiB | Workspace/cache/deployment riêng của máy. |
| `.gradle/`, `.kotlin/` | phát sinh khi kiểm tra | Cache Gradle/Kotlin có thể tái tạo. |

Các mục trong bảng đã được xóa vật lý bằng script sau khi kiểm tra đường dẫn:

    .\cleanup_obsolete.ps1 -Apply

Hai file Gemma chính có cùng SHA-256
`b1baabd6b729e4041822220d3e648e00d99cac5df86b10dffb77bcccf0688e39`,
xác nhận là hai bản sao của cùng model. Script đã giải phóng
xấp xỉ 631 MiB, chưa tính cache/output build.

## Thay đổi cấu hình

- `settings.gradle.kts` chỉ còn `:app` và `:shared`.
- Root `build.gradle.kts` bỏ plugin Android asset pack không còn dùng.
- Thêm `.gitignore` cho cache, cấu hình local, signing material và model binary.
- Thêm `ROADMAP.md`, `IMPLEMENTATION_PLAN.md` và báo cáo này.

## Kiểm tra

Trước dọn, baseline đã chạy thành công:

```powershell
.\gradlew.bat --no-daemon :shared:test `
  :app:testStandardPlayDebugUnitTest `
  :app:lintStandardPlayDebug `
  :app:assembleStandardPlayDebug
```

Sau dọn, cùng lệnh đã được chạy lại ngày 2026-08-18 và trả `BUILD SUCCESSFUL`
với 58 actionable tasks. `local.properties` không được lưu; Android Studio hoặc
người phát triển tạo lại để trỏ tới Android SDK của máy.

Git baseline trước thay đổi governance: `726430a`.

## Nợ kỹ thuật còn lại

- Wear app chưa có BLE GATT client cho ESP32-S3.
- Chưa có firmware ESP-IDF/camera/model/posture pipeline trong thư mục này.
- Code Gemma/Phone Data Layer còn đan xen trong `app` và `shared`.
- Flavor `pilot` và logic thu dữ liệu nghiên cứu cũ cần đánh giá/xóa sau khi đường
  posture/sensor mới có test thay thế.
- Git đã có ở root; bước tiếp theo là kiểm tra clone sạch/CI trên môi trường thứ hai.
