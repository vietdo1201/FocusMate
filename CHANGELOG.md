# Changelog

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
