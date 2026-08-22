# ADR 0005: Dashboard realtime local ở shadow mode

- Status: Accepted
- Date: 2026-08-22
- Supersedes: mở rộng ngoại lệ frame của ADR 0003 cho một client web local; không thay đổi Rule Engine của ADR 0002 hoặc `FaceObservationV1` của ADR 0004.

## Context

Geometry classifier trên Watch mới được kiểm bằng bbox synthetic. Việc chốt ngưỡng trên thiết bị thật cần nhìn đồng thời frame, bbox và các độ lệch hình học, nhưng không nên yêu cầu thu hoặc gắn nhãn một dataset ảnh cho từng tư thế.

ESP32-S3 N16R8 đủ tài nguyên để nén có điều kiện frame RGB565 hiện tại thành JPEG 240×240 cho một client local. Thư viện MediaPipe đầy đủ không chạy trên ESP và không cần thiết cho mục tiêu này.

## Decision

- ESP host dashboard tại `http://focusmate.local`; địa chỉ IPv4 do DHCP cấp và không hard-code.
- Nếu chưa có Wi-Fi hợp lệ, ESP mở AP WPA2 `FocusMate-Setup` tại `192.168.4.1` để provision.
- Camera vẫn capture `PIXFORMAT_RGB565`, `240×240`, một framebuffer. Chỉ khi có client dashboard đã xác thực, firmware mới tạo JPEG tối đa 5 Hz từ chính frame detector.
- Chỉ một client frame được phép. Backpressure làm bỏ frame web, không được chặn detector, BLE hoặc notification 5 Hz.
- Frame/JPEG chỉ tồn tại trong RAM, không ghi flash/storage, không upload, không cloud, không nhận dạng danh tính và không có endpoint dataset capture.
- Dashboard dùng classifier hình học cùng vocabulary và mặc định threshold với Watch, nhưng nguồn `esp_web_geometry_v1_shadow` chỉ để quan sát. Nó không ghi posture sang Watch, không thêm BLE characteristic và không ảnh hưởng `watch_rules_v2`.
- Calibration shadow cần 20 bbox mặt hợp lệ, được thu trong countdown 5 giây. Baseline lưu NVS theo version camera profile và bị hủy khi profile/mirror/flip thay đổi hoặc người dùng reset.
- Các endpoint frame, status chi tiết, calibration và Wi-Fi control yêu cầu dashboard session. Mật khẩu được lưu NVS; không log mật khẩu hoặc credential.
- Khi không có client frame, JPEG conversion phải dừng. BLE bbox-only tiếp tục chạy dù Wi-Fi, HTTP hoặc browser lỗi.

## Consequences

Dashboard cung cấp video, bbox, nhãn, confidence và số đo `dx`, `dy`, `area_ratio` ngay lập tức mà không cần model posture hoặc dataset mới. Kết quả shadow không phải bằng chứng `VERIFIED_DEVICE` cho posture cho tới khi các scenario thật và soak đồng thời Watch/Wi-Fi pass.

HTTP chạy trong mạng local tin cậy; WPA2 và dashboard password giảm truy cập ngoài ý muốn nhưng không thay thế TLS. Không công bố cổng ra Internet.

## Evidence bắt buộc

- Fixture parity Kotlin ↔ C++ cho calibration và classification.
- Test auth, single-client, frame backpressure, stale và camera-profile invalidation.
- Report COM4 + Galaxy Watch 5 Pro ghi FPS/latency/heap/PSRAM/BLE failures và bài soak.

