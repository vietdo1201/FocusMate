# FocusMate local realtime dashboard

Tài liệu này là contract HTTP cho dashboard shadow của ADR 0005. JSON dùng UTF-8, `Content-Type: application/json`, số geometry chuẩn hóa trong `[0,1]`, timestamp/age dùng monotonic milliseconds.

## Discovery và session

- LAN URL chuẩn: `http://focusmate.local/`.
- Setup fallback: `http://192.168.4.1/` trên AP `FocusMate-Setup`.
- IP LAN là DHCP và được trả trong status; `192.168.1.4` không phải contract.
- `POST /api/auth/login` nhận `{ "password": "..." }`, trả session cookie `HttpOnly`, `SameSite=Strict`. Login failure không tiết lộ credential và bị rate-limit.
- `POST /api/auth/logout` hủy session. Mọi route `/api/*` khác và `/camera.jpg` yêu cầu session, trừ status setup tối thiểu cần để provision.

## Frame

`GET /camera.jpg?after=<uint32>` trả JPEG `240×240`, `Cache-Control: no-store` và các header:

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
- `posture`: `source`, `calibrated`, `calibration_progress`, `raw_state`, `state`, `confidence`, `stable_ms`, `dx`, `dy`, `area_ratio` và threshold hiện hành.
- `memory`: `free_internal_heap`, `minimum_internal_heap`, `free_psram`.
- `privacy`: `frame_in_ram_only`, `storage`, `cloud`, `shadow_only`.

Posture vocabulary cố định: `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, `UNKNOWN`.

## Control

- `POST /api/posture/calibrate`: bắt đầu cửa sổ 5 giây; chỉ mẫu hợp lệ tăng progress.
- `POST /api/posture/reset`: xóa baseline shadow khỏi RAM/NVS.
- `GET /api/wifi/scan`, `POST /api/wifi/connect`, `POST /api/wifi/reset`, `POST /api/wifi/ap-password`: semantics tương đương dashboard camera lịch sử, có validate length và không echo password.

Dashboard giữ tối đa hai phút telemetry số trong RAM browser. Export JSON/CSV chỉ gồm status/geometry, không chứa JPEG hoặc identifier.

