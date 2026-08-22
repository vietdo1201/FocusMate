# ADR 0005: Dashboard realtime local ở shadow mode

- Status: Accepted
- Date: 2026-08-22
- Supersedes: mở rộng ngoại lệ frame của ADR 0003 cho một client web local; không thay đổi Rule Engine của ADR 0002 hoặc `FaceObservationV1` của ADR 0004.

## Context

Geometry classifier trên Watch mới được kiểm bằng bbox synthetic. Việc chốt ngưỡng trên thiết bị thật cần nhìn đồng thời frame, bbox và các độ lệch hình học, nhưng không nên yêu cầu thu hoặc gắn nhãn một dataset ảnh cho từng tư thế.

ESP32-S3 N16R8 đủ tài nguyên để dùng trực tiếp JPEG QVGA từ OV2640 cho một client local và giải mã một bản RGB888 chỉ dành cho detector. Thư viện MediaPipe đầy đủ không chạy trên ESP và không cần thiết cho mục tiêu này.

## Decision

- ESP host dashboard tại `http://focusmate.local`; địa chỉ IPv4 do DHCP cấp và không hard-code.
- Nếu chưa có Wi-Fi hợp lệ, ESP mở AP WPA2 `FocusMate-Setup` tại `192.168.4.1` để provision.
- Camera capture trực tiếp `PIXFORMAT_JPEG`, QVGA `320×240`, quality 8, một framebuffer. Sensor sửa xoay 180° để dashboard và detector cùng nhận ảnh đúng chiều. Detector giải mã RGB888 đầy đủ rồi center-crop `240×240` cho model ESPDet Pico 224; bbox được quy đổi lại hệ tọa độ `320×240`.
- Dashboard không nén lại ảnh. Chỉ khi có client đã xác thực, broker mới copy JPEG vào ba slot PSRAM cố định; không có client thì dừng toàn bộ copy/phục vụ frame web.
- Chỉ một client frame được phép. Backpressure làm bỏ frame web, không được chặn detector, BLE hoặc notification 5 Hz.
- Frame/JPEG chỉ tồn tại trong RAM, không ghi flash/storage, không upload, không cloud, không nhận dạng danh tính và không có endpoint dataset capture.
- Dashboard dùng classifier hình học cùng vocabulary và mặc định threshold với Watch, nhưng nguồn `esp_web_geometry_v1_shadow` chỉ để quan sát. Nó không ghi posture sang Watch, không thêm BLE characteristic và không ảnh hưởng `watch_rules_v2`.
- Calibration shadow cần 20 bbox mặt hợp lệ với confidence ≥0,70. Firmware duy trì pre-buffer tối đa 10 giây để nút calibrate có thể dùng ngay 20 mẫu ổn định gần nhất; nếu chưa đủ thì tiếp tục thu với deadline 8 giây. Trên ESPDet Pico đo được khoảng 2,6–3,0 inference/s, nên cửa sổ 5 giây chỉ chứa tối đa khoảng 15 mẫu và không thể đạt gate. Baseline lưu NVS theo version camera profile và bị hủy khi profile sensor thay đổi hoặc người dùng reset. Lật hiển thị trong browser không đổi geometry detector.
- Confidence gate live tách khỏi calibration: calibration giữ `0,70`, còn bbox live dùng `0,50` sau khi baseline đã hợp lệ. Run thiết bị thật ghi bbox đúng ở khoảng `0,593` khi mặt lệch trục; gate cũ `0,70` biến các tư thế đó thành `UNKNOWN` trước khi xét geometry. Không hạ xuống floor detector `0,35`. Mọi nhãn live cần ba mẫu liên tiếp, kể cả `UNKNOWN`/`FACE_MISSING`; riêng stale quá 3 giây fail-closed về `UNKNOWN` ngay.
- Timer `SLUMPED` chỉ chạy trong lúc `dy ≥ 0,18`; `HEAD_DOWN` nhẹ, `TOO_CLOSE`, mất mặt hoặc dữ liệu không hợp lệ đều reset timer. Các state vẫn là approximation từ bbox, không phải ước lượng landmark/góc thân người.
- Các endpoint frame, status chi tiết, calibration và Wi-Fi control yêu cầu dashboard session. Mật khẩu được lưu NVS; không log mật khẩu hoặc credential.
- Khi không có client frame, broker không copy JPEG. Camera/detector và BLE bbox-only tiếp tục chạy dù Wi-Fi, HTTP hoặc browser lỗi.

## Consequences

Dashboard cung cấp video, bbox, nhãn, confidence và số đo `dx`, `dy`, `area_ratio` ngay lập tức mà không cần model posture hoặc dataset mới. Kết quả shadow không phải bằng chứng `VERIFIED_DEVICE` cho posture cho tới khi các scenario thật và soak đồng thời Watch/Wi-Fi pass.

HTTP chạy trong mạng local tin cậy; WPA2 và dashboard password giảm truy cập ngoài ý muốn nhưng không thay thế TLS. Không công bố cổng ra Internet.

## Evidence bắt buộc

- Fixture parity Kotlin ↔ C++ cho calibration và classification.
- Test auth, single-client, frame backpressure, stale và camera-profile invalidation.
- Report COM4 + Galaxy Watch 5 Pro ghi FPS/latency/heap/PSRAM/BLE failures và bài soak.
