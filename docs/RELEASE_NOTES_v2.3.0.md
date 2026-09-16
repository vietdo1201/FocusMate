# FocusMate v2.3.0

[Release công khai](https://github.com/vietdo1201/FocusMate/releases/tag/v2.3.0)
đã phát hành lúc **02:07:07 ngày 16/09/2026 (+07:00)**, tương ứng
2026-09-15 19:07:07 UTC. Tag trỏ tới commit `d7c072e`; Watch code 26,
Watch/firmware version 2.3.0. [CI phát hành](https://github.com/vietdo1201/FocusMate/actions/runs/35010338290)
hoàn tất thành công.

## Thay đổi

- Sửa clock monotonic và deadline nghỉ để checkpoint không kéo dài break.
- Gộp chấp nhận đề nghị và bắt đầu nghỉ vào một transaction; bổ sung schema SQLite v2, delivery slots và unknown intervals.
- Thay nhắc nghỉ foreground bằng thẻ không chặn, giữ một initial + một retry và quiet-open.
- Tăng identity callback motion theo session/block/generation; HR, posture và yawn vẫn không điều khiển timing.
- Bổ sung tổng thời gian học/nghỉ/tạm dừng/chưa xác định cùng lịch sử từng reason,
  scheduled/receiver/post/result, response và feedback vào báo cáo.
- Bổ sung test rollback accept/resume/finish/migration, retention 500 phiên và dọn
  dữ liệu con/recovery payload theo thời hạn.
- Dùng manifest phiên bản chung cho Watch, firmware, workflow và SBOM.
- Giữ loader MediaPipe nguyên byte; ghi manifest phép đóng gói lại Face Landmarker.

## Cập nhật source sau phát hành

- APK release tối ưu giữ các field/callback JNI và protobuf mà MediaPipe tra cứu
  khi khởi tạo graph/model.
- Migration phiên cũ bảo toàn thời gian đã biết, tách khoảng chưa định lượng và
  đưa focus block sau recovery về mốc 0.
- Android 14+ chọn foreground-service type theo quyền thực tế; motion fallback
  tiếp tục thu thập khi service không khởi động.
- Regression hiện tại đạt Android 152/152, protocol 26/26 và audit recovery 2/2;
  lint 0 lỗi, debug/release APK build thành công.

Các cập nhật source này nằm sau tag `v2.3.0`; asset, chữ ký và checksum của release
đã công bố vẫn giữ nguyên lịch sử.

## Artifact

Release có 16 asset do dự án đính kèm (không tính hai source archive tự sinh
của GitHub): APK ký số, firmware update app/assets và factory image, source
`FocusMate-2.3.0-source.tar.gz`, SBOM, metadata/chữ ký/certificate APK, hướng dẫn
flash, license/notices và `SHA256SUMS.txt`.

Dùng [hướng dẫn build](BUILDING.md), [cài APK](../RELEASE.md) và
[flash giữ NVS](FLASHING_v2.3.0.md). Không dùng factory image cho cập nhật thông thường.

## Hồ sơ kiểm chứng

Hồ sơ chuẩn bị 2.3.0 ghi nhận clean-build từ source archive ngoài repository;
CI của tag xác minh build/test và đóng gói phát hành. Hai bundle model revision 1
đã được đối chiếu byte-exact với URL chính thức và model card Apache-2.0 theo
từng thành phần.

Bộ 24/24 test ngày 28–29/08/2026 ghi lại các luồng chức năng trên ESP32-S3,
Galaxy Watch và Web. Các mốc kiểm chứng được phân loại theo phiên bản tại
[STATUS.md](STATUS.md); [kế hoạch tiếp theo](V2.3.0_SYNC_PLAN.md) hướng dẫn
ghi nhận kiểm thử thiết bị, Doze và delivery timing cho artifact 2.3.0.

Đính chính tài liệu sau phát hành: bản note ban đầu còn chữ “bản nháp”. Bản này
sửa mô tả trạng thái; không đổi tag, binary hoặc checksum đã phát hành. Nội dung
trên trang GitHub Release cần được cập nhật riêng sau khi chủ dự án duyệt.

## English summary

FocusMate v2.3.0 was published at commit `d7c072e` with signed Wear APK,
ESP32 firmware, source archive, SBOM, notices and checksums. It adds crash-safe
session timing/storage and bounded reminders. HR, posture, yawn and voluntary
check-in shadow outputs remain separate from reminder decisions. Verification
records distinguish historical device tests from version-specific build and CI results.
