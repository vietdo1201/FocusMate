# Changelog

## Chưa phát hành — sau v2.2.1 (2026-08-26)

- Sửa Web yawn classifier khi Face Landmarker bản compact không có blendshape
  `jawOpen`: giữ `null` thay vì đổi thành `0` và dùng MAR làm tín hiệu chính.
- Nâng classifier baseline lên `YAWN_SHAPE_V5`: yêu cầu MAR ≥0,32, peak ≥0,55,
  mở liên tục ít nhất 1,6 giây và độ giãn ngang khóe miệng không quá 1,35×
  baseline để cười không bị tính là ngáp.
- Không thu mẫu hiệu chỉnh khi miệng đang mở; regression tests phủ ngáp
  MAR-only, mở ngắn/nông, cười vừa/cười rộng và thay đổi khoảng cách camera.
- Đồng bộ Watch classifier với cùng MAR floor, mouth-width/eye-width và thời
  lượng 1,6 giây; không còn tiêu chuẩn 1,0 giây nhạy hơn Web.
- Dashboard ưu tiên khung Face Landmarker còn mới trong 700 ms; bbox detector
  ESP chỉ còn là fallback nét đứt, không còn bị điều kiện sequence làm mất
  overlay chính xác.
- V5 đã build và flash app/assets kế thừa commit `64fcc7a` lên ESP32-S3 thật; đây là
  bằng chứng boot/smoke có giới hạn, chưa phải accuracy/thermal/soak và chưa là
  GitHub Release mới.

## 2.2.1 — 2026-08-25

- Sửa báo nhầm `TOO_CLOSE` khi chống tay bằng đồng thuận scale: Web/Watch cần
  tối thiểu hai trong ba nguồn Pose-eye, Face-eye và bbox ESP; firmware cần cả
  bbox lẫn khoảng cách hai mắt từ detector năm keypoint.
- Chuẩn hóa scale tuyến tính `1,35×`, hysteresis thoát `1,20×`, freshness 700 ms
  và baseline riêng 20 mẫu/5 giây; migration revision 3 giữ nguyên baseline tư
  thế, Wi-Fi và NVS hiện có.
- Dashboard vẽ ellipse/đường mắt màu cyan, hiển thị từng ratio, số vote, firmware
  version và hash manifest assets để phát hiện ngay app/Web bị lệch phiên bản.
- Đồng nhất bootstrap Windows, clean checkout và CI trên MediaPipe WASM không-SIMD
  tương thích Chrome Android; không thêm model hay lượt inference.
- Tách firmware update app/assets khỏi factory image để cập nhật mà không xóa NVS.

## 2.2.0 — 2026-08-25

- Thêm Yawn Sync V2 qua GATT với HTTP local fallback, checkpoint phiên và chống
  đếm trùng sau reconnect/reboot.
- Giảm tiêu thụ pin Watch bằng sensor batching, BLE/frame/inference thích ứng
  màn hình và nhiệt, cùng retry backoff tối đa 30 giây.
- Chuẩn hóa build clean checkout cho Android và ESP-IDF bằng model hash-pinned.
- Chuẩn hóa cây nguồn `wear/`, SPDX/REUSE, third-party notices và SPDX 2.3 SBOM.
- Nâng CI và signed release để phát hành APK, firmware, hash và hồ sơ license.

## 1.15-posture-orientation

- Đổi geometry ngang sang hệ quy chiếu của người ngồi để `LEAN_LEFT/RIGHT` không còn bị đảo theo phía ảnh camera.
- Khi nghiêng chéo, chọn trục lệch chuẩn hóa mạnh hơn; `HEAD_DOWN` không còn che một lateral lean rõ rệt chỉ vì mặt hạ nhẹ.
- Baseline revision 2 loại baseline NVS cũ; recalibration xóa baseline trước, chỉ thu 20 mẫu mới sau khi bấm và loại bbox chạm biên crop.
- Dashboard hiển thị baseline `cx/cy/revision`, lý do mẫu calibration bị loại và hướng dẫn trái/phải rõ ràng.
- Firmware `0.4.2-posture-orientation` đã flash COM4; baseline mới 20/20 cho `NORMAL` khi ngồi thẳng (`dx=0,002`, `dy=-0,004`). APK v16 đã cài trên Watch 5 Pro và reconnect GATT MTU 256; các tư thế còn lại và soak vẫn chưa được nâng `VERIFIED_DEVICE`.

## 1.14-posture-geometry

- Tách confidence calibration `0,70` khỏi live tracking `0,50`, dựa trên bbox lệch trục đúng đã đo ở khoảng `0,59` trên OV2640 + ESPDet.
- Debounce cả `UNKNOWN`/`FACE_MISSING` qua ba mẫu thay vì một frame yếu xóa nhãn ổn định.
- Sửa `SLUMPED`: chỉ tính 5 giây liên tục ở ngưỡng gù; `HEAD_DOWN` nhẹ, `TOO_CLOSE` và mẫu lỗi đều reset timer.
- Đồng bộ classifier Watch/firmware, bổ sung test threshold, precedence và temporal; dashboard tách raw/stable confidence và hiển thị ngưỡng.
- Firmware `0.4.1-shadow-posture` từng phát `HEAD_DOWN` trên camera thật, nhưng bằng chứng này bị rút lại: người dùng xác nhận lúc đo đang ngồi thẳng và baseline cũ bị lệch. Không dùng kết quả đó để nâng trạng thái thiết bị.

## 1.13-watch-rules-v2

- Watch Rule Engine v2 deterministic và cooldown 20 phút.
- Fatigue `1..10`, migration mood cũ.
- `FaceObservationV1` bbox protocol và sequence gate.
- Geometry posture classifier, temporal insight và end-session report.
- Wear app standalone một variant; loại bỏ các runtime/đường đồng bộ legacy.
- Canonical FaceObservation codec/framing, golden vectors và simulator fault injection.
- BLE Watch client + ESP-IDF 5.5.5 encrypted GATT stub; MTU 256 / 5,0 Hz và reboot reconnect đã kiểm tra ở Gate B.
- Camera/detector và `VERIFIED_DEVICE` vẫn chưa hoàn tất.
