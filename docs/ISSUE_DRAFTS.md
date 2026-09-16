# Draft issue — hồ sơ công việc v2.3.0

Các mục dưới đây là hồ sơ Actual/Expected, không phải issue đã đăng. Khi mở issue,
mỗi mục liên kết commit và test thực tế trước khi đóng.

`v2.3.0` đã phát hành tại `d7c072e`. Các mục Actual/Expected dưới đây ghi lại
bối cảnh trước triển khai; kết quả host tests nằm trong
[handoff](SESSION_REMINDER_HANDOFF.md). Checklist tiếp theo xem
[kế hoạch đồng bộ/kiểm chứng](V2.3.0_SYNC_PLAN.md).

## Checkpoint làm mới deadline nghỉ

- Actual: checkpoint 30 giây dời `enteredAt`; thời gian nghỉ còn lại được tính lại từ mốc này.
- Expected: deadline monotonic bất biến trong một break.
- Regression: `SessionTimelineTest.checkpointsDoNotMoveBreakStartOrDeadline` và test repository tương ứng.

## Crash giữa Accept và StartBreak

- Actual: response và transition nghỉ nằm ở hai lần ghi; crash có thể để `accepted=true` nhưng vẫn `STUDYING`.
- Expected: response, reminder state, snapshot/event và BreakEpisode cùng transaction, command gửi lại không nhân bản.
- Regression: `StudySessionRepositoryRobolectricTest.acceptingReminderAtomicallyCreatesOneBreakAndClearsPrompt`.

## Quiet-open chặn UI và không gộp reason

- Actual: foreground dialog không cancel; receiver ngừng đánh giá khi có reminder mở.
- Expected: thẻ inline, policy/shadow vẫn đánh giá, reason mới gộp nhưng không cấp lại ngân sách rung.
- Regression cần giữ: interaction coordinator merge, retry budget và stale action receiver tests.

## SQLite v1 → v2 và retention

- Actual: schema v1 không có deadline/slot/unknown interval/check-in break link.
- Expected: migration cộng dồn, không phá hủy; dữ liệu phụ bị xóa theo session, recovery record tối đa 30 ngày.
- Checklist regression: mở fixture v1, migrate hai lần, failure injection và erase dataset lớn; đối chiếu các test repository hiện có trước khi bổ sung.

## Packaging và provenance

- Actual: release workflow khóa cứng 2.2.2, loader MediaPipe bị append mã, model compact thiếu manifest thành viên.
- Expected: version manifest chung, loader byte-exact, transform manifest, source `.tar.gz`, license/notices/SBOM/checksum.
- Kết quả: model revision 1 đã được nối từ SHA-256 tới bảng bundle chính thức và model card Apache-2.0; Face compact giữ nguyên byte các thành viên được giữ lại.
