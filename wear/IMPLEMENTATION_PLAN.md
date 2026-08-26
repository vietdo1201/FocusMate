# Kế hoạch triển khai kỹ thuật

Trạng thái canonical nằm trong [`../docs/STATUS.md`](../docs/STATUS.md). Tài liệu
này mô tả Definition of Done hiện hành; report thiết bị không được suy diễn từ
build hoặc unit test.

## Đã triển khai

- Wear app standalone một variant, `versionCode 24`, `versionName 2.2.1`.
- `protocol`: `FaceObservationV1`, canonical framing, strict codec, sequence và
  freshness gate, Device Info, Frame Access và Yawn Sync V2.
- Rule Engine `watch_rules_v2`, fatigue `1..10`, focus `1..5`, motion/HR,
  reminder/cooldown, session report và migration dữ liệu cũ.
- BLE encrypted GATT Watch ↔ ESP với bond/reconnect, MTU/framing, rate thích ứng
  5/2/1 Hz và fail-closed khi capability/dữ liệu thiếu.
- ESP-IDF camera OV2640, MSR/MNP detector năm điểm, Web dashboard, dual-client
  JPEG broker và `LOCAL_FRAME_V1` có token boot-scoped.
- Pose Landmarker Lite và Face Landmarker chạy local trên Web/Watch; posture và
  yawn là advisory, không thay đổi quyết định của Rule Engine.
- Yawn Sync V2 có checkpoint/outbox/idempotency/dedupe; Web/Watch yawn V5 dùng
  chung MAR, mouth-width/eye-width và duration; Watch thêm `jawOpen`.
- Build hash-pinned, SPDX/REUSE/SBOM, unit/Robolectric/Node/Python tests, lint và
  ESP-IDF build sạch.

## Definition of Done còn lại cho device integration

1. Chạy bài posture 90 giây đủ tám state trên đúng ESP32-S3 N16R8 + OV2640 và
   Galaxy Watch 5 Pro; ghi độ đúng, false-positive, trái/phải và confusion.
2. Chạy yawn acceptance gồm ngáp thật và negative controls: nói, cười, uống
   nước, há miệng ngắn/nông, nhiều góc mặt và low-light.
3. Chụp network trace xác nhận frame/token không rời LAN và không xuất hiện
   trong URL/log; retest reconnect, reboot, doze và adaptive rate.
4. Chạy thermal 30 phút và soak ít nhất hai giờ, ghi camera errors, FPS, heap,
   PSRAM, BLE notify failure, nhiệt và mức pin Watch.
5. Chạy benchmark pin có đối chứng cùng điều kiện; ảnh One UI 1,1% chỉ là bằng
   chứng hỗ trợ, không thay benchmark.
6. Lập report chứa build/version/hash/thiết bị/OS/command/kết quả trước khi nâng
   bất kỳ hàng posture/yawn/frame nào lên `VERIFIED_DEVICE`.

## Definition of Done cho release tiếp theo

- CI của commit release phải xanh; local verification không thay thế CI.
- Bump đồng bộ Wear `versionCode/versionName`, firmware `PROJECT_VER`, changelog,
  release notes, flashing guide, SBOM và tên artifact.
- Release workflow không khóa cứng tag cũ và vẫn kiểm chữ ký APK/checksum.
- Signed APK, update app, update assets và factory image được tạo từ cùng tag;
  SHA-256 và public certificate đi kèm.
- Privacy/delete/export review, accessibility và failure recovery được ghi nhận.
- Git tracked state không chứa keystore, secret, frame người dùng hoặc build
  artifact sinh cục bộ.

## Ngoài phạm vi sản phẩm

- Chẩn đoán y tế, nhận dạng danh tính, surveillance hoặc suy luận cảm xúc.
- Cloud inference, upload frame/landmark/sensor hoặc companion-phone backend.
- Dùng posture/yawn trực tiếp làm quyết định nghỉ; Rule Engine v2 vẫn là nguồn
  quyết định duy nhất.
