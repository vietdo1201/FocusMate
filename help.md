# Yêu cầu sản phẩm FocusMate

Đây là nguồn chuẩn cho yêu cầu sản phẩm và ràng buộc an toàn. Ý nghĩa trạng thái nằm trong `docs/GOVERNANCE.md`.

## Kiến trúc bắt buộc

- ESP32-S3 N16R8 + camera chỉ chạy face detector nhẹ.
- ESP phát `FaceObservationV1`: version, sequence, uptime, `faceDetected`, bbox chuẩn hóa, confidence và quality flags.
- `FaceObservationV1` không chứa frame, crop, landmark hoặc dữ liệu nhận dạng.
- Watch calibration bằng median của cửa sổ bbox ổn định, phân loại tám state và tổng hợp temporal insight.
- Watch Rule Engine deterministic là nguồn quyết định reminder duy nhất.
- Model posture tương lai bắt đầu ở shadow mode. Model có thể nhận feature bbox hoặc frame nén tạm thời qua một protocol riêng đã opt-in; frame không được lưu hay gửi cloud.
- Không yêu cầu Internet hay dataset riêng của người dùng.

Tám state: `NORMAL`, `HEAD_DOWN`, `LEAN_LEFT`, `LEAN_RIGHT`, `TOO_CLOSE`, `SLUMPED`, `FACE_MISSING`, `UNKNOWN`.

Yawn advisory chạy local trên Web/Watch bằng Face Landmarker. Hệ thống chỉ đếm
chu kỳ ngáp và báo dấu hiệu buồn ngủ/mệt khi có ít nhất 3 lần trong 10 phút;
không suy luận chán nản, không sửa fatigue do người dùng nhập và không gọi Rule Engine.
Classifier phải loại nụ cười/khóe miệng giãn ngang, mở ngắn/nông và dữ liệu
thiếu; Web/Watch dùng cùng gate hình dạng/thời lượng trước khi phát event.

## Input phiên học

- Mức mệt: số nguyên `1..10`, nhập trước phiên.
- Mức tập trung: số nguyên `1..5`, nhập trước phiên.
- Motion từ accelerometer/gyroscope; HR chỉ dùng khi quyền/capability khả dụng.
- Thiếu BLE, calibration, motion hoặc HR không được crash hay chặn luật v1.

## Rule Engine v2

V2 giữ nguyên v1:

1. `studyDuration >= 45 phút && fatigue >= 6`.
2. `studyDuration >= 60 phút`, không phụ thuộc fatigue/focus.
3. `studyDuration >= 30 phút && fatigue >= 8 && focus <= 3`.
4. Từ chối chặn mọi break suggestion đúng 20 phút; dữ liệu vẫn được ghi, hết cooldown đánh giá lại ngay.
5. Nhiều rule đúng chỉ tạo một reminder và giữ toàn bộ reason code.
6. Cooldown ưu tiên hơn luật 60 phút; retry/duplicate guard vẫn áp dụng, từ chối phải hủy retry.

V2 bổ sung:

- Sau 45 phút học, bất động liên tục 30 phút, coverage ≥80% và sample fresh tạo suggestion ngay.
- Posture xấu liên tục 3 phút hoặc cùng lỗi ≥4 episode/15 phút tạo insight, không notification lúc học.
- Khi bắt đầu nghỉ, hiển thị tối đa một lời khuyên từ lỗi có tổng duration lớn nhất.
- Cuối phiên hiển thị episode count, tổng duration và khuyến nghị theo state.

## Lời khuyên cuối phiên

`session_advice_v1` là engine local, deterministic và tách khỏi nguồn quyết định
reminder. Khi lưu phiên, Watch đóng băng reason code v1/v2 rồi kết hợp bất động,
posture/ngáp từ pipeline ESP/Watch và nhịp tim tương đối của Watch để chọn một
hành động chính cùng tối đa hai hành động phụ. Mỗi hành động lưu code và evidence;
câu tiếng Việt chỉ được render khi mở báo cáo.

- Tín hiệu trùng nhau phải gộp thành một hành động, không lặp nhiều câu “hãy nghỉ”.
- Posture chỉ góp lời khuyên sau insight 3 phút liên tục hoặc 4 episode/15 phút.
- Ngáp chỉ là tín hiệu mạnh khi có alert hoặc ít nhất 3 lần/10 phút.
- Nhịp tim cần đủ mẫu, tăng cả 15 BPM lẫn 15% so với baseline và có tín hiệu khác
  xác nhận; không dùng BPM tuyệt đối và không chẩn đoán.
- Thiếu dữ liệu phải ghi rõ độ đầy đủ, không được suy diễn phiên hoàn toàn tốt.
- Nếu không có cảnh báo đủ tin cậy, báo cáo khen việc hoàn thành và đưa một gợi ý
  duy trì đổi tư thế/nghỉ ngắn.

Reason code ổn định:

`RULE_V1_DURATION_FATIGUE`, `RULE_V1_HARD_60`, `RULE_V1_HIGH_FATIGUE_LOW_FOCUS`, `RULE_V2_IMMOBILITY`, `INSIGHT_V2_POSTURE_CONTINUOUS`, `INSIGHT_V2_POSTURE_REPEATED`, `SUPPRESSED_COOLDOWN`.

## Quyền riêng tư và an toàn

- Local-first; không lưu ảnh camera trong Watch protocol hoặc report.
- Không nhận dạng danh tính/cảm xúc, không chẩn đoán hay điều trị.
- Model/dependency phải có provenance và license rõ ràng; không commit model binary.
