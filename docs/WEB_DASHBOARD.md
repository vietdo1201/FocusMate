# FocusMate local realtime dashboard

Tài liệu này là contract HTTP cho dashboard local của ADR 0005 và đường pose local của ADR 0006. JSON dùng UTF-8, `Content-Type: application/json`, số geometry chuẩn hóa trong `[0,1]`, timestamp/age dùng monotonic milliseconds. Sidecar Watch normative nằm ở [LOCAL_FRAME_V1.md](LOCAL_FRAME_V1.md).

## Discovery và truy cập

- URL chuẩn trên LAN là `http://focusmate.local/`; URL ổn định trên AP `FocusMate-Setup` là `http://192.168.4.1/`.
- Trên LAN, hostname được công bố bằng mDNS và trỏ tới IP DHCP của STA. AP vẫn quảng bá mDNS/DNS `focusmate.local` để tương thích, nhưng dashboard không phụ thuộc hostname này: Android có thể chuyển DNS sang dữ liệu di động/Private DNS khi Wi-Fi báo “không có Internet”.
- `GET http://<IPv4 LAN>/` trả `302` về `http://focusmate.local/` để LAN có một origin/localStorage/cache. Riêng `GET http://192.168.4.1/` phục vụ dashboard trực tiếp, không redirect. Các API và route Watch theo IPv4 cũng không redirect.
- IP LAN là DHCP và được trả trong status; `192.168.1.4` không phải contract.
- Dashboard Web và các API điều khiển local không dùng mật khẩu/session cookie. Mật khẩu WPA2 của AP `FocusMate-Setup` vẫn được giữ để giới hạn truy cập provisioning.
- `GET /api/watch/frame` tiếp tục yêu cầu token boot-scoped từ encrypted GATT theo `LOCAL_FRAME_V1`; việc bỏ mật khẩu Web không làm yếu route Watch.

## Frame

`GET /camera.jpg?after=<uint32>` trả JPEG QVGA `320×240`, `Cache-Control: no-store` và các header:

- `X-FocusMate-Frame-Sequence`
- `X-FocusMate-Observed-Uptime-Ms`
- `X-FocusMate-Face-Detected`
- `X-FocusMate-Bbox` (`cx,cy,width,height`, sáu chữ số thập phân; vắng khi no-face)
- `X-FocusMate-Confidence`
- `X-FocusMate-Face-Meta-V1` (Base64URL không padding của sidecar public 32 byte Q16; atomic với JPEG)
- Nhóm `X-FocusMate-Yawn-*` tùy chọn chỉ chứa summary số Web → ESP → Watch; không chứa ảnh hoặc landmark.

Nếu chưa có frame mới trong 1 giây, server trả `204`. Chỉ một request frame dài hạn được giữ; client thứ hai nhận `409`. Đóng/ngắt client làm JPEG producer idle trong tối đa 2 giây.

## Frame cho Watch

Watch dùng đúng `GET /api/watch/frame?after=<uint32>` với `Authorization: FocusMate <32 lowercase hex token>`. Không POST, cookie, mDNS URL hoặc redirect. `200` trả JPEG + ba header `X-FocusMate-Frame-Sequence`, `X-FocusMate-Observed-Uptime-Ms`, `X-FocusMate-Face-Meta-V1`; `204` là chưa có frame mới; `401` buộc đọc lại Frame Access Info qua BLE. Watch và browser có lease độc lập. Toàn bộ validation, status và privacy ở [LOCAL_FRAME_V1.md](LOCAL_FRAME_V1.md).

Dashboard V2 chỉ gửi khi classifier tạo một event ngáp mới bằng
`POST /api/yawn/event`. Event mang session 128-bit ngẫu nhiên, client, event id,
frame sequence và uptime capture của ESP; retry cùng event là idempotent. ESP
giữ broker theo phiên trong RAM, gộp event Web/Watch cách nhau không quá 1,5
giây và piggyback state canonical qua nhóm header
`X-FocusMate-Yawn-Schema/Session/Revision/Total/Window`. Payload và header
legacy vẫn được giữ để tương thích dần.

Watch quản lý vòng đời phiên qua `GET /api/watch/yawn/state`,
`POST /api/watch/yawn/session` và `POST /api/watch/yawn/event`; cả ba dùng token
Frame Access. Checkpoint chỉ chứa tổng số cùng tuổi các event trong cửa sổ 10
phút, cho phép re-seed sau ESP reboot mà không lưu frame hoặc landmark.

## Status schema 1

`GET /api/status` trả object có các nhóm:

- `wifi`: `mode`, `ip`, `ssid`, `rssi`, `mdns`.
- `camera`: `healthy`, `width`, `height`, `format`, `capture_fps`, `jpeg_fps`, `average_jpeg_bytes`, `client_connected`, `encode_drops`, `errors`.
- `ble`: `connected`, `subscribed`, `mtu`, `rate_hz`, `observations`, `notification_attempts`, `notification_failures`.
- `face`: `detected`, bbox tùy chọn, `confidence`, `inference_ms`, `inference_count`, `observed_uptime_ms`, `age_ms`.
- `posture`: `source`, `calibrated`, `calibration_progress`, `calibration_reason`, `raw_state`, `state`, `raw_confidence`, `confidence`, `stable_ms`, feature/baseline và threshold hiện hành. `scale_evidence` là nhóm tùy chọn gồm ratio từng nguồn, số nguồn hợp lệ, số vote và lý do đồng thuận; client cũ có thể bỏ qua. `source` phân biệt `POSE_LOCAL` với fallback ESP; `raw_*` là mẫu mới nhất, còn `state`/`confidence` là nhãn đã ổn định nên luôn cùng nghĩa với nhau.
- `build`: firmware version từ app descriptor và SHA-256 của `asset-manifest.json` đang được mount. Dashboard hiển thị cả hai để nhận ra app/Web assets khác phiên bản sau khi flash.
- `memory`: `free_internal_heap`, `minimum_internal_heap`, `free_psram`.
- `privacy`: `frame_in_ram_only`, `storage`, `cloud`, `shadow_only`.

Posture vocabulary cố định: `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, `UNKNOWN`.

`POSE_LOCAL` dùng Pose Landmarker Lite và baseline/threshold/precedence khóa ở [LOCAL_FRAME_V1](LOCAL_FRAME_V1.md). Nó cần thấy nose, hai mắt và hai vai; hips là tùy chọn cho head-only nhưng bắt buộc cho claim thân/gù. Thiếu anatomy cần thiết trả `UNKNOWN` thay vì suy diễn từ bbox. Model/assets chạy hoàn toàn local, không CDN và không cần dataset người dùng.

Firmware đóng gói runtime WASM không-SIMD tương thích làm runtime chung và phục vụ cả hai tên file SIMD/no-SIMD mà MediaPipe có thể chọn. Nhờ đó Chrome Android cũ vẫn khởi tạo Pose hoàn toàn local; laptop dùng cùng pipeline và không phụ thuộc cache/CDN.

JPEG camera dùng QVGA quality 6 (ít nén hơn quality 8). Worker giữ ngưỡng tracking `0.65` và lọc thích nghi chỉ cho landmark hiển thị để giảm rung khuỷu/cổ tay; classifier posture và baseline vẫn nhận landmark thô, nên bộ lọc không che một chuyển động/tư thế thật. Xương tay có visibility/presence dưới `0.65` không được vẽ. Dashboard cảnh báo khi hai khuỷu/cổ tay không nằm trọn trong khung vì landmark ngoài ảnh chỉ là suy đoán và không thể được coi là chính xác.

Các luật dưới đây áp dụng cho fallback firmware `esp_face_scale_consensus_v3`/`BBOX_FALLBACK`:

- Calibration chỉ dùng bbox confidence ≥0,70. Sau khi có baseline, live classifier dùng bbox confidence ≥0,50; mốc này xuất phát từ bbox thật đúng ở confidence khoảng 0,59 khi mặt lệch trục. Mẫu dưới 0,50 vẫn là `UNKNOWN`.
- Mọi nhãn live, kể cả `UNKNOWN` và `FACE_MISSING`, cần ba mẫu liên tiếp trước khi thay nhãn ổn định. Freshness quá 3 giây vẫn chuyển `UNKNOWN` ngay và không tái dùng bbox cũ.
- `dx` dùng hệ quy chiếu của người ngồi, không phải phía ảnh: âm là người ngồi nghiêng trái, dương là nghiêng phải. Browser mirror chỉ đổi cách hiển thị, không đổi dấu geometry.
- `TOO_CLOSE` có ưu tiên cao nhất nhưng cần đồng thuận 2/2 giữa scale tuyến tính bbox và khoảng cách hai mắt từ detector năm keypoint: enter `1,35×`, exit dưới `1,20×`. Một bbox phình riêng lẻ do bàn tay không được phép tạo nhãn này. Khi cả `|dx| ≥ 0,15` và `dy ≥ 0,12`, classifier so độ lệch chuẩn hóa `|dx|/0,15` với `dy/0,12`: trục ngang lớn hơn **hoặc bằng** trả `LEAN_LEFT/RIGHT` (tie chọn lean), trục dọc lớn hơn trả `HEAD_DOWN/SLUMPED`. `SLUMPED` chỉ xuất hiện sau khi nhánh dọc có `dy ≥ 0,18` liên tục 5 giây.
- Đây vẫn là geometry fallback: `LEAN_*` chỉ nghĩa là tâm mặt dịch ngang và `HEAD_DOWN`/`SLUMPED` chỉ nghĩa là tâm mặt dịch xuống. Bbox **không** được dùng một mình để kết luận gần hoặc tuyên bố góc vai/độ cong lưng; khi pose runtime unavailable, UI phải ghi rõ nguồn fallback hoặc trả `UNKNOWN`.

## Control

- `POST /api/posture/calibrate`: thao tác nguyên tử cho classifier bbox ESP, xóa baseline RAM/NVS trước, fail-closed về `UNKNOWN`, chờ ổn định 1 giây rồi chỉ thu mẫu phát sinh sau khi bấm. Cửa sổ thu tối đa 15 giây để lấy 20 bbox confidence ≥0,70, không chạm biên crop và có spread geometry ổn định. Khoảng mất mẫu ngắn tối đa 1,5 giây được bỏ qua; gap dài hơn reset progress. Không có pre-buffer và không thể tái dùng baseline vừa xóa.
- Baseline revision hiện là `3`. Migration giữ nguyên baseline tâm/diện tích revision 2 và chỉ thu nền eye-scale 20 mẫu ổn định trong ít nhất 5 giây; không xóa Wi-Fi hay NVS khác. Trong lúc bổ sung scale, các nhãn tư thế khác vẫn hoạt động. Lỗi NVS làm calibration fail-closed thay vì giả vờ thành công.
- `POST /api/posture/reset`: xóa baseline bbox shadow khỏi RAM/NVS. Pose baseline tự thu 20 frame ổn định và reset riêng. Browser được lưu **chỉ** median/MAD số trong `localStorage` cùng classifier version, model hash, camera/source fingerprint và boot/session scope; mismatch hoặc record hỏng phải xóa. **CẤM** lưu frame, landmark list, token hoặc identifier người dùng. Watch có thể giữ baseline session-only.
- `GET /api/wifi/scan`, `POST /api/wifi/connect`, `POST /api/wifi/reset`, `POST /api/wifi/ap-password`: semantics tương đương dashboard camera lịch sử, có validate length và không echo password.
- Khởi động theo nguyên tắc AP-first: firmware luôn phát `FocusMate-Setup` ổn định và giữ thông tin SSID đã lưu nhưng không tự scan STA khi boot. STA chỉ bắt đầu sau thao tác “Lưu và kết nối”; nếu SSID không tồn tại/sai xác thực, firmware dừng retry nền để AP không biến mất theo chu kỳ scan. Mất kết nối tạm thời sau khi đã online vẫn được reconnect.

Dashboard giữ tối đa hai phút telemetry số trong RAM browser. Export JSON/CSV chỉ gồm status/geometry, không chứa JPEG hoặc identifier.
