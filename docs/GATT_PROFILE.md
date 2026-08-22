# GATT profile — FocusMate Face Observation

Trạng thái: **normative spec**, chốt bởi [ADR 0004](decisions/0004-gatt-profile-and-canonical-framing.md) và mở rộng bởi [ADR 0006](decisions/0006-local-frame-and-pose-posture.md).
Implementation status của BLE/firmware nằm ở [STATUS.md](STATUS.md); tài liệu này mô tả contract phải triển khai, **không** phải bằng chứng đã triển khai.

`protocol_version = 1`. `framing_version = 1`. `schema_version = focusmate_face_observation_v1`.

## 0. Từ khoá

**MUST**/**PHẢI**, **MUST NOT**/**CẤM**, **SHOULD**/**NÊN**, **MAY**/**CÓ THỂ** theo nghĩa thông thường của RFC 2119. Vai trò: **ESP** = ESP32-S3 (GATT server, peripheral, advertiser), **Watch** = Wear OS app (GATT client, central).

## 1. Ràng buộc privacy (cao nhất, không thương lượng)

- ESP **CẤM** phát frame, crop, ảnh thumbnail, landmark, embedding, tên, MAC của thiết bị khác, hay bất kỳ identifier nào của người dùng qua profile này.
- Chỉ bbox chuẩn hoá, confidence và quality flag trong danh sách đăng ký được phép.
- `boot_id` là số random sinh mỗi lần boot, **CẤM** dẫn xuất từ MAC, eFuse, serial hay bất kỳ giá trị bền vững nào của chip.
- Đường frame nén theo [LOCAL_FRAME_V1](LOCAL_FRAME_V1.md) là protocol opt-in **riêng**. Profile này chỉ chở capability, địa chỉ và token; byte ảnh không đi qua GATT.
- Watch **CẤM** ghi payload thô ra log ở build release.

## 2. Advertising

- ESP **PHẢI** advertise Service UUID ở bảng dưới trong Service Data hoặc Complete List of 128-bit Service UUIDs.
- Local Name **NÊN** là `FocusMate-Det` cộng 4 hex ký tự cuối của `boot_id`; **CẤM** chứa định danh người dùng.
- Advertising interval **NÊN** trong 100–500 ms khi chưa kết nối, và ESP **NÊN** dừng advertise sau khi có kết nối.
- Watch **PHẢI** scan theo Service UUID, không quét theo tên.

## 3. Service và characteristic

Service UUID: `3a9190ce-8e4e-4792-830b-4a04f637446e` (primary)

| Characteristic | UUID | Properties | Kích thước |
|---|---|---|---|
| Device Info / Capability | `8c441643-7770-406d-9ddc-9c0b15d5c138` | Read | 34 byte, cố định |
| Frame Access Info V1 | `f26cf312-b841-46f5-a172-6b53713a37f3` | Read | 40 byte, cố định |
| Face Observation | `f8c18a21-0a62-4a67-8b0d-c5efd5b81263` | Notify | ≤ (ATT MTU − 3) mỗi notification |
| Control | `50bf0d4c-ce93-4d39-acce-a0b5b32f4049` | Write (with response) | 1–2 byte |

- **CẤM** thêm characteristic chở dữ liệu ảnh vào service này.
- Cả bốn characteristic **PHẢI** yêu cầu link đã encrypt. Bonding: Just Works, LE Secure Connections; ESP **PHẢI** chấp nhận bond và **NÊN** giới hạn 1 bond.
- Face Observation **CẤM** hỗ trợ Read: dữ liệu chỉ có nghĩa khi tươi, đọc lại là replay.

## 4. Device Info / Capability (read)

Struct nhị phân **little-endian**, packed, tổng 34 byte. Không JSON. Không padding.

| Offset | Kích thước | Trường | Giá trị |
|---:|---:|---|---|
| 0 | 2 | `protocol_version` | `1` |
| 2 | 1 | `framing_version` | `1` |
| 3 | 16 | `boot_id` | random mỗi boot |
| 19 | 8 | `esp_uptime_ms` | uptime tại thời điểm đọc |
| 27 | 1 | `max_quality_flags` | `4` |
| 28 | 1 | `max_flag_length` | `16` |
| 29 | 1 | `nominal_rate_dhz` | rate deci-Hz, `50` = 5,0 Hz |
| 30 | 4 | `capability_bits` | xem dưới |

`capability_bits` (bit 0 là LSB):

| Bit | Ý nghĩa |
|---:|---|
| 0 | detector đã load model và chạy được |
| 1 | camera init thành công |
| 2 | ESP hỗ trợ `SET_RATE` |
| 3 | ESP báo được `low_light` |
| 4 | ESP báo được `unstable` |
| 5 | `CAP_LOCAL_FRAME_V1`: có characteristic Frame Access Info V1 |
| 6–31 | reserved, **PHẢI** = 0 |

- Watch **PHẢI** đọc characteristic này ngay sau khi kết nối và sau **mỗi** lần reconnect, **trước** khi bật notification.
- `protocol_version` khác `1` → Watch **PHẢI** ngắt kết nối và báo `unavailable`; **CẤM** đoán layout.
- Bit 0 hoặc bit 1 = 0 → Watch **PHẢI** báo `unavailable`, **CẤM** tạo dữ liệu giả (GOVERNANCE.md).
- 34 byte vượt 20 byte của MTU mặc định, nên ESP **PHẢI** hỗ trợ ATT Read Blob (long read). Watch **NÊN** request MTU trước khi đọc.

### 4.1 Frame Access Info V1 (read)

Characteristic 40 byte này chỉ công bố endpoint/credential sidecar; không chứa JPEG hoặc landmark. Layout khóa là `[u8 version][u8 flags][u16_le port][4 byte IPv4 network order][16 byte boot_id][16 byte token]`. `boot_id` phải khớp Device Info; flags bit 0 = `LAN_READY`, bit 1 = `TOKEN_AUTH_REQUIRED`, bit 2 = `FACE_META_V1`, bit 3–7 reserved bằng 0.

Watch chỉ đọc khi capability bit 5 bật, sau mỗi connect/reconnect và khi HTTP trả `401`. Parser, validation, token lifetime, endpoint và FaceMeta normative nằm ở [LOCAL_FRAME_V1.md](LOCAL_FRAME_V1.md). Trạng thái `LAN_READY = 0` không làm BLE bbox unavailable.

## 5. Control (write with response)

| Opcode | Payload | Ý nghĩa |
|---:|---|---|
| `0x01` | `[u8 rate_dhz]` | START: bắt đầu phát observation ở rate cho trước |
| `0x02` | — | STOP: dừng phát, giữ kết nối |
| `0x03` | `[u8 rate_dhz]` | SET_RATE |
| `0x04` | — | RESYNC: reset `msg_id`, `sequence` giữ nguyên |

- `rate_dhz` hợp lệ trong `10..100` (1,0–10,0 Hz). Ngoài khoảng → ESP **PHẢI** trả `ATT_ERROR_VALUE_NOT_ALLOWED (0x13)` và giữ rate cũ.
- Opcode lạ → `ATT_ERROR_REQUEST_NOT_SUPPORTED (0x06)`.
- ESP **CẤM** phát observation trước khi nhận START.
- STOP rồi START lại **CẤM** làm `sequence` lùi.

## 6. Face Observation (notify) và framing

Một observation là một payload canonical ở mục 7. Nếu `payload.size <= ATT_MTU - 3` thì ESP **PHẢI** gửi nguyên payload trong một notification, **không** header. Ngược lại **PHẢI** fragment.

### 6.1 MTU

- Watch **PHẢI** gọi `requestMtu(517)` sau khi connect và trước khi bật notification, rồi dùng MTU thực tế được cấp.
- ESP **PHẢI** chấp nhận MTU tới 517 và **PHẢI** hoạt động đúng ở MTU mặc định 23.
- MTU thực tế được cấp **PHẢI** ghi vào report thiết bị. Đường fragment là đường bắt buộc hoạt động, không phải fallback trên giấy.

### 6.2 Header fragment (8 byte, little-endian)

| Offset | Kích thước | Trường | Ghi chú |
|---:|---:|---|---|
| 0 | 1 | `framing_version` | `1` |
| 1 | 1 | `msg_id` | tăng mod 256 mỗi observation được fragment |
| 2 | 1 | `idx` | 0-based |
| 3 | 1 | `count` | tổng số chunk, `1..255` |
| 4 | 2 | `total_len` | độ dài payload đã ghép, byte |
| 6 | 2 | `crc16` | CRC của payload đã ghép |

- Mỗi chunk: 8 byte header + tối đa `ATT_MTU - 3 - 8` byte dữ liệu. Chunk cuối **CÓ THỂ** ngắn hơn; các chunk trước **PHẢI** đầy.
- Header xuất hiện ở **mọi** chunk và giống nhau ở cả 4 trường cuối trong cùng một observation.
- Nhận diện: notification có `payload[0] == 1` và độ dài ≥ 8 **CÓ THỂ** là fragment. Vì payload canonical luôn bắt đầu bằng `{` (`0x7B`), byte đầu tiên phân biệt được hai dạng một cách không nhập nhằng.

### 6.3 CRC-16

CRC-16/CCITT-FALSE: poly `0x1021`, init `0xFFFF`, không reflect input/output, không xorout. Check value chuẩn: `"123456789"` → `0x29B1`.

### 6.4 Reassembly (Watch)

- Buffer theo `msg_id`. `framing_version` khác `1` → bỏ chunk và đếm vào metric lỗi.
- Chunk có `msg_id` mới **PHẢI** hủy buffer đang dở.
- Timeout 500 ms tính từ chunk đầu; hết hạn → **PHẢI** bỏ cả observation.
- Thiếu chunk, `total_len` không khớp tổng, hoặc CRC sai → **PHẢI** bỏ cả observation. **CẤM** ghép một phần.
- Observation bị bỏ **CẤM** làm `FaceSequenceGate` tiến; gate chỉ tiến khi decode thành công.

## 7. Canonical payload

Payload là JSON text UTF-8, có **tối đa 11 key**, `MAX_PAYLOAD_BYTES = 512`. Detected-face có 11 key; no-face có đúng 5 key.

### 7.1 Luật canonical

1. Không whitespace ngoài chuỗi. Không newline, không BOM, không byte nào sau `}`.
2. Thứ tự key **PHẢI** đúng: `schema_version`, `sequence`, `esp_uptime_ms`, `face_detected`, `cx`, `cy`, `width`, `height`, `area`, `confidence`, `quality_flags`.
3. `face_detected = true` → sáu key `cx`…`confidence` **PHẢI** có mặt. `false` → **PHẢI** vắng cả sáu (không `null`).
4. `schema_version` **PHẢI** là `"focusmate_face_observation_v1"`.
5. `sequence`: số nguyên thập phân `0..4294967295`, không dấu, không leading zero (`0` là hợp lệ).
6. `esp_uptime_ms`: số nguyên thập phân `0..999999999999`, cùng luật.
7. `face_detected`: literal `true` hoặc `false`. **CẤM** `"true"`, `1`, `0`.
8. `cx`, `cy`, `width`, `height`, `area`, `confidence`: **đúng 6 chữ số thập phân**, luôn có `0.` hoặc `1.` ở đầu — `0.500000`, `1.000000`, `0.000000`. **CẤM** exponent, dấu `+`/`-`, trailing zero bị lược, hay số nguyên không có phần thập phân.
9. `quality_flags`: array các string, **sắp xếp tăng theo byte**, không trùng, `0..4` phần tử, mỗi phần tử khớp `[a-z0-9_]{1,16}`. Array rỗng viết `[]`.
10. Vì `schema_version` và flag chỉ chứa `[a-z0-9_]`, **không có** escape sequence nào trong payload hợp lệ. Encoder C không cần escape; decoder gặp `\` là reject.
11. Không key lạ, không duplicate key, không comment.

### 7.2 Quantize và invariant `area`

- `cx`, `cy`, `width`, `height`, `confidence`: đổi sang micro-unit nguyên bằng `q = floor(value × 1_000_000 + 0.5)`, rồi format phần nguyên và phần dư sáu chữ số. Vì unit luôn không âm, đây là round-half-up.
- `area` **PHẢI** được dẫn xuất bằng integer: `qArea = floor((qWidth × qHeight + 500_000) / 1_000_000)`. Đây là luật dẫn xuất, không phải giá trị độc lập.
- Luật này idempotent, nên `decode(encode(x))` rồi `encode` lại cho đúng byte cũ.
- Kotlin và C **PHẢI** format từ micro-unit nguyên; cấm dùng `BigDecimal(double)` hoặc `snprintf("%.6f")` làm định nghĩa wire.
- `width` và `height` tối thiểu `0.001000`; `area` tối thiểu `0.000001`, để quantize không biến detected-face thành bbox bằng 0.

### 7.3 Bất đối xứng encode/decode

- `encode` là **total** và canonicalize: nhận observation hợp lệ theo contract, luôn cho ra byte canonical.
- `decode` là **strict**: sau khi parse **PHẢI** kiểm tra `payload.contentEquals(encode(decoded))`. Không khớp → reject. Một luật này chặn hết duplicate key, coerce string↔bool, comment, byte rác sau `}`, số không canonical và lệch thứ tự key — không phụ thuộc bản `org.json` nào đang chạy.

### 7.4 Ngân sách byte

| Trường hợp | Byte |
|---|---:|
| No-face nhỏ nhất (`sequence` 1 chữ số, uptime 1 chữ số, `[]`) | 122 |
| No-face, `sequence` uint32 max, uptime 12 chữ số | 142 |
| Detected điển hình (`["stable","well_lit"]`) | 246 |
| Detected worst case hợp lệ (4 flag × 16 ký tự, seq/uptime max) | **317** |
| Ngân sách cũ 8 flag × 32 ký tự — **vượt cap** | 521 |

Còn 195 byte dự phòng dưới cap 512. Mọi observation hợp lệ theo contract **PHẢI** encode được; property test khẳng định điều này.

### 7.5 Registry quality flag

| Flag | Ý nghĩa | Hành vi trên Watch |
|---|---|---|
| `stable` | bbox jitter dưới ngưỡng trong window gần nhất | metadata |
| `unstable` | bbox jitter trên ngưỡng | **behavioral**: bỏ mẫu khi calibrate, classify trả `UNKNOWN` |
| `well_lit` | luma trung bình trong dải danh nghĩa | metadata |
| `low_light` | luma trung bình dưới ngưỡng | **behavioral**: bỏ mẫu khi calibrate, classify trả `UNKNOWN` |
| `motion_blur` | reserved, chưa có hành vi | metadata |
| `partial_face` | reserved, bbox chạm biên frame | metadata |
| `multi_face` | reserved, detector thấy nhiều hơn một khuôn mặt | metadata |
| `sensor_warmup` | reserved, AGC/AEC chưa ổn định | metadata |

- `stable`/`unstable` loại trừ nhau; `well_lit`/`low_light` loại trừ nhau. ESP **CẤM** gửi cả hai của một cặp.
- Watch **PHẢI** chấp nhận flag lạ đúng pattern và coi là metadata; **CẤM** reject observation chỉ vì flag chưa biết. Thêm flag mới vào registry không cần ADR mới, nhưng gán **hành vi** cho một flag thì cần.
- Chỉ hai flag behavioral là điểm kết nối thật với `GeometryPostureClassifier`; mọi thứ khác không đổi quyết định nào.

## 8. Sequence, reboot và anti-replay

- `sequence` là uint32 monotonic tăng đúng 1 mỗi observation **được phát** (kể cả no-face), wrap `0xFFFFFFFF → 0`.
- Watch so sánh **modular**: chấp nhận khi `((s - last) mod 2^32) ∈ 1..2^31`. Ngoài khoảng đó là replay/out-of-order và bị bỏ. Luật này xử lý đúng cả wrap và cả gap do mất chunk.
- Reboot **PHẢI** xác định bằng `boot_id` khác lần đọc trước, **CẤM** suy diễn từ uptime giảm. Khi `boot_id` đổi, Watch reset `lastSequence`, reset anchor thời gian, và **PHẢI** hủy baseline calibration (camera có thể đã đổi vị trí).
- `boot_id` giống nhau nhưng `esp_uptime_ms` giảm là **bất thường**: Watch **PHẢI** bỏ observation đó và đếm vào metric lỗi, **CẤM** dùng nó để reset gate.
- Gate **PHẢI** an toàn cho truy cập từ nhiều thread (callback BLE đến trên binder thread).
- Gate **NÊN** persist `(boot_id, lastSequence)` để anti-replay không mất khi app restart giữa phiên.

## 9. Mô hình thời gian

- ESP `esp_uptime_ms` **PHẢI** lấy từ `esp_timer_get_time() / 1000`. **CẤM** `xTaskGetTickCount()`: uint32 ms wrap sau ~49,71 ngày và làm gate đọc thành reboot.
- Watch đặt đồng thời hai anchor khi đọc Device Info: wall clock để hiển thị/report và monotonic clock (`SystemClock.elapsedRealtime()`) để kiểm tra freshness.
- `observedAtWallClockMs = anchorWallClockMs + (espUptimeMs - anchorUptimeMs)`; giá trị này **CẤM** dùng để quyết định stale.
- `observedAtMonotonicMs = anchorMonotonicMs + (espUptimeMs - anchorUptimeMs)`.
- Bias dương của anchor bị chặn bởi RTT/2 của lần đọc (dưới một connection interval) và không ảnh hưởng đo episode vì posture chỉ dùng thời gian tương đối.
- Re-anchor khi reconnect hoặc khi `boot_id` đổi. **CẤM** re-anchor giữa dòng notification (sẽ làm timeline nhảy).
- Freshness: `nowMonotonicMs - observedAtMonotonicMs > 3 s` → posture về `UNKNOWN`/unavailable. Thay đổi wall clock không được ảnh hưởng kết quả. **CẤM** dùng lại giá trị cũ.

## 10. Rate và tham số kết nối

- Rate chuẩn **5 Hz** (`rate_dhz = 50`). Calibration cần 20 mẫu liên tiếp → 4 s ở rate chuẩn.
- ESP **NÊN** yêu cầu connection interval 30–50 ms và slave latency 0 khi đang stream.
- Ở MTU 23 một observation 317 byte cần 27 chunk; ở 5 Hz là 135 notification/s. ESP **NÊN** giảm rate hoặc Watch **NÊN** chấp nhận rate thấp hơn khi MTU nhỏ. Rate thực tế đạt được **PHẢI** ghi trong report thiết bị.
- Mất kết nối BLE **CẤM** chặn phiên học. Watch reconnect với backoff (1 s, 2 s, 4 s, … tối đa 30 s) và hiển thị `disconnected`; session, motion/HR và Rule Engine tiếp tục chạy.

## 11. Danh sách reject của `decode`

Decode **PHẢI** reject, không được sửa im lặng:

| # | Trường hợp | Bắt bởi |
|---:|---|---|
| 1 | payload rỗng hoặc > 512 byte | size check |
| 2 | không phải UTF-8 hợp lệ | parse |
| 3 | JSON cắt cụt | parse |
| 4 | byte rác sau `}` | canonical check |
| 5 | duplicate key | canonical check |
| 6 | comment `//` hoặc `/* */` | canonical check |
| 7 | `schema_version` khác | so sánh trực tiếp |
| 8 | key lạ | allow-list |
| 9 | thiếu key bắt buộc | contract |
| 10 | `"face_detected":"true"` | canonical check |
| 11 | `sequence` hoặc `esp_uptime_ms` vượt biên | range check |
| 12 | số có exponent, dấu, hay khác 6 chữ số thập phân | canonical check |
| 13 | `area` lệch `width*height` quá `1e-6` | contract |
| 14 | unit ngoài `[0,1]`, hoặc `width`/`height`/`area` = 0 | contract |
| 15 | bbox có mặt khi `face_detected = false` | contract |
| 16 | `quality_flags` không phải array, hoặc phần tử không phải string | canonical check |
| 17 | > 4 flag, flag > 16 ký tự, flag sai pattern, flag trùng, flag không sắp xếp | contract + canonical check |
| 18 | thứ tự key khác spec | canonical check |
| 19 | cặp loại trừ cùng xuất hiện (`stable`+`unstable`, `well_lit`+`low_light`) | contract |

Watch **PHẢI** đếm reject theo lý do và hiển thị được ở trạng thái debug; **CẤM** đếm reject thành posture state.

## 12. Golden vectors

Đặt ở `tests/golden/face_observation_v1.json`, dùng chung cho test Kotlin (`:protocol`, `:app` Robolectric) và cho encoder C ở host build.

Payload lưu dưới dạng JSON string để giữ đúng byte, không phụ thuộc trailing newline hay `.gitattributes`. Case cần byte không hợp lệ UTF-8 dùng `payload_base64`.

Bộ tối thiểu — positive: `no_face_min`, `no_face_max_digits`, `detected_typical`, `detected_max_flags`, `detected_worst_size`, `detected_bounds` (`cx=0.000000`, `confidence=1.000000`). Negative: đủ 19 dòng ở mục 11.

Mỗi positive entry ghi kèm `crc16` để kiểm chứng framing. Ví dụ: `detected_typical` (246 byte) có `crc16 = 0xD073`.

## 13. Hằng số

| Tên | Giá trị |
|---|---|
| `MAX_PAYLOAD_BYTES` | 512 |
| `MAX_QUALITY_FLAGS` | 4 |
| `MAX_QUALITY_FLAG_LENGTH` | 16 |
| `QUALITY_FLAG_PATTERN` | `[a-z0-9_]{1,16}` |
| `NUMBER_SCALE` | 6 |
| `AREA_TOLERANCE` | `1e-6` |
| `MAX_SEQUENCE` | 4294967295 |
| `MAX_UPTIME_MS` | 999999999999 |
| `FRAME_HEADER_BYTES` | 8 |
| `REASSEMBLY_TIMEOUT_MS` | 500 |
| `STALE_THRESHOLD_MS` | 3000 |
| `NOMINAL_RATE_DHZ` | 50 |
| `PREFERRED_MTU` | 517 |
| `FRAME_ACCESS_INFO_BYTES` | 40 |
| `CAP_LOCAL_FRAME_V1` | bit 5 |

Tất cả **PHẢI** là API public của `:protocol`; tác giả firmware đọc contract từ đây, không đoán.

## 14. Ngoài phạm vi profile này

- Byte frame/ảnh nén không đi qua GATT; sidecar opt-in nằm ở [LOCAL_FRAME_V1.md](LOCAL_FRAME_V1.md). Embedding và identifier vẫn ngoài phạm vi tuyệt đối.
- Yawn/PFLD: `deferred/unavailable`.
- OTA, cloud, telemetry, nhiều detector cùng lúc.
- Bất kỳ đường nào để posture ảnh hưởng quyết định break: Rule Engine `watch_rules_v2` vẫn là nguồn duy nhất (ADR 0002).
