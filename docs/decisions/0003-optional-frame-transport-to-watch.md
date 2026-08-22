# ADR 0003: Cho phép frame transport tùy chọn tới Watch

- Status: Accepted
- Date: 2026-08-22
- Supersedes: phần cấm tuyệt đối truyền frame trong ADR 0002

## Context

Một số model posture cần pixel input và phù hợp hơn khi chạy trên Watch. `FaceObservationV1` bbox-only vẫn là đường nhẹ, ổn định cho v2 nhưng không nên khóa vĩnh viễn khả năng thử model ảnh trên Watch.

## Decision

- Giữ `FaceObservationV1` bbox-only làm đường mặc định.
- Cho phép một protocol version hóa riêng để truyền frame giảm độ phân giải và nén từ ESP sang Watch.
- Frame chỉ tồn tại trong memory, không ghi storage, không đưa lên cloud và không dùng nhận dạng danh tính.
- Transport phải có opt-in/capability negotiation, sequence/integrity/size limits và tự tắt khi quá nhiệt, pin thấp hoặc dữ liệu stale.
- Model frame trên Watch bắt đầu ở shadow mode. Rule Engine deterministic tiếp tục là nguồn quyết định break duy nhất.

## Consequences

APK hiện tại chưa có frame transport hoặc image model runtime. Trước khi triển khai cần chọn BLE chunking hay Wi-Fi, benchmark băng thông/pin/nhiệt trên Galaxy Watch FE và bổ sung privacy/test plan. Không thêm bytes ảnh vào `FaceObservationV1`.
