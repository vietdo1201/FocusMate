# LOCAL_FRAME_V1 — frame local cho Watch

Trạng thái: **normative spec**, chốt bởi [ADR 0006](decisions/0006-local-frame-and-pose-posture.md). Implementation/evidence nằm ở [STATUS.md](STATUS.md); tài liệu này không phải bằng chứng thiết bị.

`local_frame_version = 1`. Từ khoá **PHẢI**, **CẤM**, **NÊN**, **CÓ THỂ** dùng theo RFC 2119. ESP là ESP32-S3 GATT/HTTP server; Watch là Wear OS client.

## 1. Phạm vi và ranh giới

`LOCAL_FRAME_V1` là sidecar opt-in của GATT profile: GATT chỉ công bố địa chỉ và credential; JPEG đi bằng HTTP nội bộ. Nó không thêm ảnh vào `FaceObservationV1`, không đổi notification bbox 5 Hz và không thay Rule Engine `watch_rules_v2`.

- Frame chỉ tồn tại trong RAM, **CẤM** ghi flash/storage, log, telemetry export hoặc cloud.
- **CẤM** tạo endpoint capture/dataset, nhận dạng danh tính, embedding hoặc identifier người dùng.
- Mất Wi-Fi, HTTP, model hoặc client Watch **CẤM** làm BLE detector dừng; Watch fail-closed về geometry bbox/`UNKNOWN` theo freshness.
- HTTP local không có TLS. Token giảm truy cập ngoài ý muốn nhưng không cung cấp tính bí mật trước thiết bị khác trên cùng LAN. **CẤM** port-forward/công bố endpoint ra Internet.

## 2. Capability và GATT discovery

Device Info `capability_bits` bit 5 (`CAP_LOCAL_FRAME_V1`) báo characteristic sau tồn tại. Bit này không có nghĩa endpoint đang online; trạng thái online nằm trong `flags`.

Characteristic `Frame Access Info V1`:

- UUID: `f26cf312-b841-46f5-a172-6b53713a37f3`.
- Property: encrypted **Read**, không Notify/Write.
- Value: packed đúng 40 byte, không padding.

| Offset | Size | Trường | Encoding |
|---:|---:|---|---|
| 0 | 1 | `version` | `1` |
| 1 | 1 | `flags` | bitfield bên dưới |
| 2 | 2 | `http_port` | uint16 little-endian |
| 4 | 4 | `ipv4` | bốn octet theo network byte order |
| 8 | 16 | `boot_id` | raw 128-bit, byte-exact với Device Info |
| 24 | 16 | `token` | random 128-bit, raw bytes |

`flags`:

| Bit | Tên | Nghĩa |
|---:|---|---|
| 0 | `LAN_READY` | IPv4/port hiện dùng được trên LAN |
| 1 | `TOKEN_AUTH_REQUIRED` | endpoint bắt buộc header token |
| 2 | `FACE_META_V1` | response chứa header FaceMetaV1 |
| 3–7 | reserved | **PHẢI** bằng 0 |

Watch **PHẢI** đọc sau Device Info ở mỗi connect/reconnect nếu capability bit 5 bật. Nó chỉ bật frame path khi value đúng 40 byte, `version = 1`, reserved bằng 0, cả bit 1 và 2 bật, `LAN_READY` bật, port khác 0, IPv4 numeric không phải `0.0.0.0`/broadcast, token khác toàn 0 và `boot_id` khớp Device Info. Không thỏa thì bbox BLE vẫn chạy; **CẤM** đoán layout hoặc dùng endpoint cũ.

Khi `LAN_READY = 0`, port 0 và `0.0.0.0` là biểu diễn hợp lệ của trạng thái chưa sẵn sàng. Token phải sinh từ CSPRNG, boot-scoped/rotatable, không dẫn xuất từ MAC/eFuse/serial. Watch **CẤM** persist, log hoặc export token. ESP chỉ cho đọc characteristic trên link đã encrypt và bonded; so token constant-time.

## 3. HTTP Watch endpoint

Watch dựng origin từ IPv4 numeric và port vừa đọc, **không** dùng mDNS, DNS redirect, cookie hoặc credential dashboard:

```http
GET /api/watch/frame?after=<uint32> HTTP/1.1
Authorization: FocusMate <32 lowercase hex token>
Accept: image/jpeg
```

`after` là decimal `0..4294967295`; request đầu có thể dùng `4294967295` làm sentinel “chưa nhận frame”. Token chỉ ở header, **CẤM** đặt trong URL/query/log. Client **PHẢI** tắt redirect và cache.

Response:

| Status | Nghĩa/Hành vi Watch |
|---:|---|
| `200` | JPEG mới nhất, hợp lệ và mới hơn `after` |
| `204` | chưa có frame mới; body rỗng |
| `400` | query không canonical/ngoài uint32 |
| `401` | token sai/đã rotate; bỏ endpoint và đọc lại characteristic, không retry token cũ |
| `409` | lease Watch khác đang giữ; backoff, không chiếm lease browser |
| `503` | Wi-Fi/camera/frame broker tạm unavailable |

Response `200` **PHẢI** có `Content-Type: image/jpeg`, `Cache-Control: no-store` và:

- `X-FocusMate-Frame-Sequence`: uint32 decimal.
- `X-FocusMate-Observed-Uptime-Ms`: monotonic ESP uptime decimal.
- `X-FocusMate-Face-Meta-V1`: Base64URL canonical của sidecar public 32 byte ở mục 4.

JPEG tối đa 512 KiB, bắt đầu `FF D8`, kết thúc `FF D9`. Watch reject body/header hỏng, quá lớn, sequence replay/out-of-order hoặc uptime lùi trong cùng `boot_id`. Sequence so modular như `FaceSequenceGate`; frame bỏ qua vì backpressure là gap hợp lệ. Header sequence/uptime và FaceMeta **PHẢI** thuộc đúng cùng một snapshot JPEG.

## 4. `FaceMetaV1`

Header `X-FocusMate-Face-Meta-V1` là RFC 4648 Base64URL (`A–Z a–z 0–9 - _`) **không padding**, không whitespace, đúng 43 ký tự. Decode phải cho đúng 32 byte gồm 16 word uint16 little-endian:

| Offset | Size | Trường | Contract |
|---:|---:|---|---|
| 0 | 2 | `flags` | bit 0 `FACE_DETECTED`; bit 1–15 = 0 |
| 2 | 2 | `confidence_q16` | normalized confidence |
| 4 | 2 | `cx_q16` | bbox center X |
| 6 | 2 | `cy_q16` | bbox center Y |
| 8 | 2 | `width_q16` | bbox width |
| 10 | 2 | `height_q16` | bbox height |
| 12 | 4 | keypoint 0 | left eye `(x_q16,y_q16)` |
| 16 | 4 | keypoint 1 | left mouth `(x_q16,y_q16)` |
| 20 | 4 | keypoint 2 | nose `(x_q16,y_q16)` |
| 24 | 4 | keypoint 3 | right eye `(x_q16,y_q16)` |
| 28 | 4 | keypoint 4 | right mouth `(x_q16,y_q16)` |

Q16 là uint16 normalized: decoder dùng `value / 65535`; encoder từ Q6 dùng `min(65535, floor((q6 × 65535 + 500000) / 1000000))`. Khi `FACE_DETECTED = 0`, 15 word còn lại phải bằng 0. Khi có mặt, width/height phải lớn hơn 0. Header sequence/uptime ở mục 3 là định danh atomic; chúng **không** lặp trong FaceMeta.

Firmware có thể giữ struct broker nội bộ 84 byte gồm sequence/latency/Q6 để đồng bộ task, nhưng struct đó **không phải HTTP wire** và **CẤM** Base64 trực tiếp. Metadata public 32 byte chỉ ghép bbox/năm keypoint detector với JPEG; năm điểm mặt không phải MediaPipe Pose landmarks và không đủ để suy ra vai/hông/tư thế thân.

## 5. Lease, backpressure và freshness

- Broker có đúng một lease `WATCH` và một lease `BROWSER` độc lập. Browser đang mở **CẤM** làm Watch nhận `409`, và ngược lại.
- Latest-frame-wins: client chậm làm bỏ JPEG cũ; **CẤM** chặn camera, detector hoặc BLE notification.
- Đóng client/timeout phải giải phóng lease. Khi không còn Watch/browser, broker dừng copy/nén frame trong tối đa 2 giây.
- Watch dùng monotonic clock; frame cũ quá 3 giây trở thành `UNKNOWN` ngay. Wall clock chỉ dành cho UI/report.
- `boot_id` đổi, uptime regression, model/camera-profile/source/fingerprint đổi hoặc session policy yêu cầu reset phải hủy frame state, temporal timer và baseline không còn đúng scope.

## 6. Pose local, baseline và nhãn

MediaPipe Pose Landmarker Lite chạy local trên JPEG; không cần dataset ảnh do người dùng tự gắn nhãn. Input classifier **PHẢI** là ảnh sensor canonical không mirror; mirror UI không đổi dấu geometry.

Contract `POSE_LOCAL_V1` bắt buộc MediaPipe nose 0, eyes 2/5 và shoulders 11/12; quality là min visibility/presence của năm điểm và phải ≥ `0.70`. Hips 23/24 là tùy chọn cho head-only labels nhưng bắt buộc khi tuyên bố torso lean/compression. Pose không tìm thấy đồng thời FaceMeta báo no-face → `FACE_MISSING`; còn thiếu điểm, quality thấp, stale hoặc geometry xung đột → `UNKNOWN`.

Hệ trục “subject-left” lấy từ right shoulder tới left shoulder trên ảnh canonical. Mọi phép chiếu trái/phải theo trục giải phẫu này; display mirror/flip chỉ đổi render, không đổi input classifier. Feature tối thiểu:

- head roll = eye-line angle trừ shoulder-line angle;
- lateral head = chiếu `(nose - shoulder_mid)` lên subject-left, chia shoulder width;
- head/eye height = khoảng cách nose/eye-mid tới shoulder-mid, chia shoulder width;
- torso lean/compression dùng shoulder-mid và hip-mid khi cả hai hông đủ quality;
- face pitch dùng nose so với eye/mouth midpoint từ FaceMeta khi năm keypoint detector hợp lệ;
- close scale ưu tiên face scale `sqrt(width × height)`, fallback shoulder width.

Baseline dùng 20 frame sequence forward, không trùng, và mẫu đầu/cuối phải cách nhau ít nhất 5 giây. Mẫu upright cần `|head_roll| ≤ 10°`, `|torso_lean| ≤ 8°` khi có hông, `|lateral_head| ≤ 0.12` shoulder width và head-height trong `0.35..2.50`. Giữa hai mẫu liên tiếp, head roll/torso lean/lateral/head-height không được nhảy quá `4°/4°/0.04/0.08`. Baseline và noise là median/MAD theo từng feature.

Watch **CÓ THỂ** giữ baseline session-only. Browser **CÓ THỂ** persist duy nhất median/MAD số cùng schema/classifier version, model hash, camera profile/orientation, coordinate/source fingerprint và boot/session scope để dùng lại sau reload. Record phải bị xóa khi bất kỳ scope/fingerprint nào đổi hoặc record hỏng. **CẤM** persist frame, JPEG, landmark list, token hay identifier người dùng; `boot_id` nếu lưu làm scope chỉ được dùng để invalidation, không log/export.

Ngưỡng enter là `max(floor, 6 × baseline MAD)`: head roll `10°`, torso lean `8°`, lateral head `0.12`, head drop `0.12`, eye drop `0.10`, face pitch `0.08`. Luật nhãn/precedence:

1. `TOO_CLOSE` enter ở scale ratio `1.35`, giữ tới khi ratio < `1.20` để chống rung.
2. `SLUMPED` cần head-down và collapse liên tục 5 giây: torso compression ≥ `0.10` khi có hips, hoặc đồng thời head drop ≥ `max(0.18, threshold)` và eye drop ≥ `max(0.16, threshold)`. Mất continuity >3 giây reset timer.
3. Lean score là mức vượt lớn nhất của head roll, torso lean và lateral head. Tín hiệu trái/phải đối nghịch gần nhau (trong tỉ lệ `1.2`) trả `UNKNOWN`.
4. `HEAD_DOWN` cần ít nhất hai trong ba signal head-drop/eye-drop/face-pitch vượt ngưỡng, hoặc head-drop đạt `1.5 × threshold`.
5. Khi lean và head-down cùng có, score lớn hơn ít nhất `1.2×` thắng; nếu không, trả `UNKNOWN`. Không có candidate thì `NORMAL`.

Dấu dương trên subject-left axis → `LEAN_LEFT`, âm → `LEAN_RIGHT`. Vocabulary duy nhất: `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, `UNKNOWN`. Mọi đổi nhãn cần ba mẫu liên tiếp; stale 3 giây chuyển `UNKNOWN` ngay. Đây là geometry từ model pretrained, không phải chẩn đoán công thái học/y tế; không thấy đủ anatomy thì hướng dẫn camera hoặc trả `UNKNOWN`, không bịa nhãn từ bbox.

## 7. Dependency và artifact policy

Không runtime nào được tải CDN/model sau khi app/firmware đã build. Artifact được pin immutable và build **PHẢI** fail khi SHA-256 lệch:

| Runtime/artifact | Version/revision | SHA-256 |
|---|---|---|
| Watch `com.google.mediapipe:tasks-vision` AAR | `1.0.0` | `53d45569649ff7e9d84457f070481dbb779ddd057671d4adcd9abcb77ff172c6` |
| Web `@mediapipe/tasks-vision` tarball | `1.0.1` | `ee318eaa3d42230aa10910d114faf2a488c577c4e4d33c7cb04126924aca505f` |
| `pose_landmarker_lite.task`, float16 revision 1 | immutable model URL | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |

Generated web manifest phải ghi hash từng JS/WASM/model file. Report thiết bị phải ghi runtime version, model hash, app/firmware hash và license/NOTICE inventory; URL `latest`, dependency range hoặc hash chỉ ghi trong tài liệu đều không đủ.

## 8. Evidence gates

`VERIFIED_LOCAL` cần: golden vector FrameAccess C/Kotlin byte-equality; negative flags/version/boot/token; HTTP 200/204/401; FaceMeta public 32-byte Q16/Base64URL byte-equality và atomic headers; JPEG corrupt/oversize; sequence wrap/stale; lease/backpressure; baseline persistence allow-list + fingerprint invalidation; model hash/no-CDN; lifecycle/thermal cleanup; fixture feature/label parity Kotlin ↔ JS.

`VERIFIED_DEVICE` cần report Galaxy Watch 5 Pro + ESP32-S3 N16R8/OV2640 ghi build/hash/OS/board, Wi-Fi/BLE đồng thời, token rotate/reboot/reconnect/Wi-Fi loss/low light, toàn bộ nhãn bằng scenario người dùng xác nhận, battery/temperature/heap/PSRAM/FPS/latency/notification failures và soak 2 giờ. Test/docs/build thành công **CẤM** tự nâng status hay posture tổng thể.
