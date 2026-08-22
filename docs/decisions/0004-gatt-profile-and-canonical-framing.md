# ADR 0004: GATT profile và canonical framing cho `FaceObservationV1`

- Status: Accepted
- Date: 2026-08-22
- Supersedes: không thay thế ADR nào. Bổ sung tầng vận chuyển cho split đã chốt ở ADR 0002; ràng buộc frame của ADR 0003 giữ nguyên và không thuộc đợt này.

## Context

`FaceObservationV1` đã có contract và unit test, nhưng chưa đóng băng được thành wire format:

- Payload là **JSON text**, và `encode()` dựng `JSONObject`. `org.json:json:20240303` trên JVM lưu key trong `HashMap` (thứ tự bất định), Android platform `org.json` dùng `LinkedHashMap` (thứ tự chèn). Cùng một observation cho ra hai chuỗi byte khác nhau tuỳ nền tảng, nên **golden vector byte-exact không thể viết được** và firmware C không có mục tiêu để đối chiếu.
- `decode()` thừa hưởng toàn bộ khác biệt lenient/strict giữa hai bản `org.json`: duplicate key, `"face_detected":"true"`, comment `//` và `/* */`, số nguyên vượt 63 bit, `quality_flags` không phải array, coerce phần tử array sang string, và byte rác sau `}` bị **cả hai** bỏ qua im lặng.
- Số thực không có định dạng chuẩn. `1.0` in ra `1`, `0.50` và `0.5` là hai byte string cho cùng giá trị.
- Ngân sách `MAX_QUALITY_FLAGS = 8` × 32 ký tự cho ra payload **521 byte** ở worst case, vượt `MAX_PAYLOAD_BYTES = 512`. Một `FaceObservationV1` mà constructor chấp nhận vẫn có thể làm `encode()` throw.
- `MAX_QUALITY_FLAGS`, `AREA_TOLERANCE`, `QUALITY_FLAG_PATTERN` là `private`; tác giả firmware không đọc được từ API.
- `FaceSequenceGate` so sánh `sequence` bằng `<=` trên `Long` và suy diễn reboot từ uptime giảm. Nếu ESP phát uint32 và wrap `0xFFFFFFFF → 0`, gate đọc thành out-of-order và **reject vĩnh viễn**; ngược lại một packet khai uptime thấp là reset được gate, mở cửa replay.
- Không có tầng nào chở `boot_id`, uptime anchor, capability hay rate, và không có framing cho trường hợp ATT MTU mặc định 23 (chỉ 20 byte/notification) trong khi payload thấp nhất đã là 122 byte.

## Decision

1. **Canonical form là bắt buộc.** Encoder viết tay (`StringBuilder`), thứ tự tối đa 11 key cố định, không whitespace. Detected-face có 11 key; no-face chỉ có 5 key vì sáu trường bbox phải vắng. `decode()` sau khi parse phải kiểm tra `payload.contentEquals(encode(decoded))`; payload không canonical bị reject. `encode` là total và canonicalize, `decode` là strict.
2. **Định dạng số cố định.** `sequence` và `esp_uptime_ms` là số nguyên thập phân không dấu, không leading zero, chặn ở `uint32` và 12 chữ số. Sáu trường bbox/confidence dùng đúng 6 chữ số thập phân. Unit được quantize qua micro-unit nguyên (`scale = 1_000_000`) bằng round-half-up; Kotlin và C đều format phần nguyên/phần dư bằng integer. `area` được dẫn xuất bằng integer từ width/height đã quantize. `width`/`height` tối thiểu `0.001000`, `area` tối thiểu `0.000001`.
3. **Ngân sách flag.** `MAX_QUALITY_FLAGS = 4`, pattern `[a-z0-9_]{1,16}`. Worst case canonical đo được **317 byte** (còn 195 byte dự phòng dưới cap 512); ngân sách cũ là 521 byte. Thêm property test khẳng định mọi observation hợp lệ đều encode được.
4. **Registry quality flag.** Vocabulary chốt trong `docs/GATT_PROFILE.md`. Chỉ `unstable` và `low_light` có ý nghĩa hành vi (`PostureClassifier` bỏ mẫu khi calibrate và trả `UNKNOWN` khi classify); flag khác là metadata. `MAX_QUALITY_FLAGS`, `MAX_QUALITY_FLAG_LENGTH`, `AREA_TOLERANCE`, `NUMBER_SCALE`, `MAX_SEQUENCE`, `MAX_UPTIME_MS` và pattern trở thành API public.
5. **Sequence và uptime nằm trong mỗi JSON observation; reboot/capability đặt ở Device Info.** `boot_id` và capability không được thêm vào payload. `esp_uptime_ms` lấy từ `esp_timer_get_time()/1000`; `sequence` là uint32 monotonic và gate so sánh modular. Reboot chỉ xác định bằng `boot_id`. Watch tạo cả wall-clock anchor để report và monotonic anchor để quyết định freshness; thay đổi đồng hồ hệ thống không được ảnh hưởng stale.
6. **Framing, MTU và rate.** Watch request ATT MTU 517; nếu được cấp thì một observation đi trong một notification. Không được cấp thì fragment với header 8 byte `[u8 framing_version][u8 msg_id][u8 idx][u8 count][u16 total_len][u16 crc16]`, CRC-16/CCITT-FALSE trên payload đã ghép, reassembly timeout 500 ms, mất chunk thì bỏ cả observation. CRC nằm ở header, **không** nhét vào JSON. Rate chuẩn 5 Hz (calibration cần 20 mẫu liên tiếp → 4 s). Ba characteristic 128-bit: Device Info/Capability (read), Face Observation (notify), Control (write).

Giữ `schema_version = focusmate_face_observation_v1`, **không** bump v2: canonical form đổi byte trên wire nhưng chưa từng có firmware hay BLE runtime nào phát v1 ra ngoài (0 file C trong repo tại thời điểm quyết định), nên không có tương thích nào bị phá. `protocol_version = 1` ở tầng GATT là số dùng để đàm phán về sau.

## Consequences

- `:protocol` mất phụ thuộc hành vi `org.json` ở đường encode; `org.json` chỉ còn dùng để parse, và mọi lệch lenient/strict bị canonical check chặn. `decode` được bọc trong `Result` để hai họ exception (`IllegalArgumentException` contract vs `JSONException` — `RuntimeException` trên JVM nhưng `Exception` trên Android) không bắt buộc caller viết hai `catch`.
- Golden vector byte-exact đặt được ở `tests/`, dùng chung cho test Kotlin và encoder C.
- `docs/Vi_du.md` phải sửa: `0.50`/`0.20`/`0.06` là những chuỗi encoder canonical không bao giờ phát ra.
- Ngân sách flag giảm từ 8×32 xuống 4×16 là **thu hẹp contract**. Không có producer nào đang chạy nên không phá tương thích, nhưng test nào dựng 8 flag hoặc flag dài quá 16 ký tự phải sửa.
- Đường fragment phải hoạt động thật, không chỉ là fallback trên giấy: chưa ai xác nhận Galaxy Watch 5 Pro có cấp MTU 517. MTU thực tế được cấp là số bắt buộc ghi trong report Giai đoạn 3.
- ADR này **không** nâng status: BLE GATT, firmware và camera vẫn `NOT_STARTED`. Bằng chứng của ADR là test local; `docs/STATUS.md` được cập nhật cùng change này và chỉ đổi evidence của hàng codec.
- Rule Engine không bị chạm. Posture vẫn là metadata, không vào `ReminderContext`, không đổi `shouldPrompt` (ADR 0002).

## Evidence

- Đo trực tiếp payload canonical: no-face tối thiểu 122 byte, detected 5 Hz điển hình 246 byte, worst case hợp lệ 317 byte, ngân sách cũ 8×32 flag 521 byte (vượt cap 512).
- CRC-16/CCITT-FALSE xác nhận bằng check value chuẩn `"123456789"` → `0x29B1`.
- Spec chuẩn: `docs/GATT_PROFILE.md`.
- Verification sẽ gắn với golden vector test (`:protocol`), simulator integration và ingestion test Robolectric (`:app`) ở Gate B; `./verify.ps1` phải pass từ build sạch.
