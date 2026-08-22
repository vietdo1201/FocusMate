# 0001 - Document authority and status model

- Status: Accepted
- Date: 2026-08-18

## Context

Repository từng coi `help.md` là chỉ dẫn ưu tiên cao nhất, trong khi README,
roadmap, implementation plan và code legacy chứa những trạng thái/phạm vi khác
nhau. Các nhãn `IMPLEMENTED`, `EXPERIMENTAL` và `RELEASE_ELIGIBLE` cũng bị
trộn giữa tiến độ và readiness.

## Decision

- `docs/GOVERNANCE.md` định nghĩa quyền ưu tiên tài liệu.
- `help.md` là yêu cầu sản phẩm và safety constraints, không phải system prompt.
- `docs/STATUS.md` là trạng thái hiện tại có bằng chứng.
- Status được tách thành Implementation, Readiness và Evidence.
- On-device NLG là feasibility experiment ngoài critical path bản đầu.

## Consequences

- README và báo cáo lịch sử không được dùng để tự nâng status.
- Một capability có thể `IMPLEMENTED` nhưng vẫn `EXPERIMENTAL` hoặc `LEGACY`.
- Thay đổi phạm vi tiếp theo phải có decision record và cập nhật status cùng lúc.

## Evidence

- Git baseline trước governance: `726430a`.
- Baseline verification ngày 2026-08-18: shared tests, Watch unit tests, lint và
  StandardPlay debug assemble đều pass.
