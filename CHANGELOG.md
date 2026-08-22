# Changelog

## 1.14-posture-geometry

- Tách confidence calibration `0,70` khỏi live tracking `0,50`, dựa trên bbox lệch trục đúng đã đo ở khoảng `0,59` trên OV2640 + ESPDet.
- Debounce cả `UNKNOWN`/`FACE_MISSING` qua ba mẫu thay vì một frame yếu xóa nhãn ổn định.
- Sửa `SLUMPED`: chỉ tính 5 giây liên tục ở ngưỡng gù; `HEAD_DOWN` nhẹ, `TOO_CLOSE` và mẫu lỗi đều reset timer.
- Đồng bộ classifier Watch/firmware, bổ sung test threshold, precedence và temporal; dashboard tách raw/stable confidence và hiển thị ngưỡng.
- Firmware `0.4.1-shadow-posture` đã flash COM4 và nhận `HEAD_DOWN` thật; chưa coi đủ bộ posture là `VERIFIED_DEVICE` khi chưa chạy từng scenario và soak.

## 1.13-watch-rules-v2

- Watch Rule Engine v2 deterministic và cooldown 20 phút.
- Fatigue `1..10`, migration mood cũ.
- `FaceObservationV1` bbox protocol và sequence gate.
- Geometry posture classifier, temporal insight và end-session report.
- Wear app standalone một variant; loại bỏ các runtime/đường đồng bộ legacy.
- Canonical FaceObservation codec/framing, golden vectors và simulator fault injection.
- BLE Watch client + ESP-IDF 5.5.5 encrypted GATT stub; MTU 256 / 5,0 Hz và reboot reconnect đã kiểm tra ở Gate B.
- Camera/detector và `VERIFIED_DEVICE` vẫn chưa hoàn tất.
