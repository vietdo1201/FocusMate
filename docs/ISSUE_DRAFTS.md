# Draft issue cho ứng viên 2.3.0

Không coi các mục dưới đây là issue đã đăng. Khi được phép tạo issue, mỗi mục cần link commit/test thực tế rồi mới đóng.

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
- Regression cần bổ sung trước release: mở fixture v1, migrate hai lần, failure injection và erase dataset lớn.

## Packaging và provenance

- Actual: release workflow khóa cứng 2.2.2, loader MediaPipe bị append mã, model compact thiếu manifest thành viên.
- Expected: version manifest chung, loader byte-exact, transform manifest, source `.tar.gz`, license/notices/SBOM/checksum.
- Kết quả: model revision 1 đã được nối từ SHA-256 tới bảng bundle chính thức và model card Apache-2.0; Face compact giữ nguyên byte các thành viên được giữ lại.
