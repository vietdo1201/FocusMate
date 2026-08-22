# FocusMate local realtime dashboard

Tài liệu này là contract HTTP cho dashboard shadow của ADR 0005. JSON dùng UTF-8, `Content-Type: application/json`, số geometry chuẩn hóa trong `[0,1]`, timestamp/age dùng monotonic milliseconds.

## Discovery và session

- LAN URL chuẩn: `http://focusmate.local/`.
- Setup fallback: `http://192.168.4.1/` trên AP `FocusMate-Setup`.
- IP LAN là DHCP và được trả trong status; `192.168.1.4` không phải contract.
- `POST /api/auth/login` nhận `{ "password": "..." }`, trả session cookie `HttpOnly`, `SameSite=Strict`. Login failure không tiết lộ credential và bị rate-limit.
- `POST /api/auth/logout` hủy session. Mọi route `/api/*` khác và `/camera.jpg` yêu cầu session, trừ status setup tối thiểu cần để provision.

## Frame

`GET /camera.jpg?after=<uint32>` trả JPEG QVGA `320×240`, `Cache-Control: no-store` và các header:

- `X-FocusMate-Frame-Sequence`
- `X-FocusMate-Observed-Uptime-Ms`
- `X-FocusMate-Face-Detected`
- `X-FocusMate-Bbox` (`cx,cy,width,height`, sáu chữ số thập phân; vắng khi no-face)
- `X-FocusMate-Confidence`

Nếu chưa có frame mới trong 1 giây, server trả `204`. Chỉ một request frame dài hạn được giữ; client thứ hai nhận `409`. Đóng/ngắt client làm JPEG producer idle trong tối đa 2 giây.

## Status schema 1

`GET /api/status` trả object có các nhóm:

- `wifi`: `mode`, `ip`, `ssid`, `rssi`, `mdns`.
- `camera`: `healthy`, `width`, `height`, `format`, `capture_fps`, `jpeg_fps`, `average_jpeg_bytes`, `client_connected`, `encode_drops`, `errors`.
- `ble`: `connected`, `subscribed`, `mtu`, `rate_hz`, `observations`, `notification_attempts`, `notification_failures`.
- `face`: `detected`, bbox tùy chọn, `confidence`, `inference_ms`, `inference_count`, `observed_uptime_ms`, `age_ms`.
- `posture`: `source`, `calibrated`, `calibration_progress`, `raw_state`, `state`, `raw_confidence`, `confidence`, `stable_ms`, `dx`, `dy`, `area_ratio` và threshold hiện hành. `raw_*` là mẫu mới nhất; `state`/`confidence` là nhãn đã ổn định nên luôn cùng nghĩa với nhau.
- `memory`: `free_internal_heap`, `minimum_internal_heap`, `free_psram`.
- `privacy`: `frame_in_ram_only`, `storage`, `cloud`, `shadow_only`.

Posture vocabulary cố định: `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, `UNKNOWN`.

- Calibration chỉ dùng bbox confidence ≥0,70. Sau khi có baseline, live classifier dùng bbox confidence ≥0,50; mốc này xuất phát từ bbox thật đúng ở confidence khoảng 0,59 khi mặt lệch trục. Mẫu dưới 0,50 vẫn là `UNKNOWN`.
- Mọi nhãn live, kể cả `UNKNOWN` và `FACE_MISSING`, cần ba mẫu liên tiếp trước khi thay nhãn ổn định. Freshness quá 3 giây vẫn chuyển `UNKNOWN` ngay và không tái dùng bbox cũ.
- Precedence: `TOO_CLOSE` → `SLUMPED`/`HEAD_DOWN` → `HEAD_DOWN` → `LEAN_LEFT` → `LEAN_RIGHT` → `NORMAL`. `SLUMPED` chỉ xuất hiện sau khi `dy ≥ 0,18` liên tục 5 giây; thời gian `HEAD_DOWN` nhẹ hoặc `TOO_CLOSE` không được cộng dồn.
- Đây là geometry bbox: `LEAN_*` nghĩa là tâm mặt dịch ngang, `HEAD_DOWN`/`SLUMPED` nghĩa là tâm mặt dịch xuống và `TOO_CLOSE` nghĩa là diện tích bbox tăng. Không suy diễn góc vai, độ cong lưng hoặc landmark mà camera không cung cấp.

## Control

- `POST /api/posture/calibrate`: thử dùng 20 mẫu hợp lệ gần nhất trong pre-buffer tối đa 10 giây; nếu chưa đủ thì mở cửa sổ tối đa 8 giây để thu tiếp. Chỉ bbox có confidence ≥0,70 tăng progress và baseline chỉ được lưu nếu spread geometry ổn định.
- `POST /api/posture/reset`: xóa baseline shadow khỏi RAM/NVS.
- `GET /api/wifi/scan`, `POST /api/wifi/connect`, `POST /api/wifi/reset`, `POST /api/wifi/ap-password`: semantics tương đương dashboard camera lịch sử, có validate length và không echo password.

Dashboard giữ tối đa hai phút telemetry số trong RAM browser. Export JSON/CSV chỉ gồm status/geometry, không chứa JPEG hoặc identifier.
