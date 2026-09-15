# FocusMate v2.3.0 — bản nháp

Đây là release note cho ứng viên, chưa phải bằng chứng release đã phát hành hay đã kiểm thử thiết bị.

- Sửa clock monotonic và deadline nghỉ để checkpoint không kéo dài break.
- Gộp chấp nhận đề nghị và bắt đầu nghỉ vào một transaction; bổ sung schema SQLite v2, delivery slots và unknown intervals.
- Thay nhắc nghỉ foreground bằng thẻ không chặn, giữ một initial + một retry và quiet-open.
- Tăng identity callback motion theo session/block/generation; HR, posture và yawn vẫn không điều khiển timing.
- Bổ sung tổng thời gian học/nghỉ/tạm dừng/chưa xác định cùng lịch sử từng reason,
  scheduled/receiver/post/result, response và feedback vào báo cáo.
- Bổ sung test rollback accept/resume/finish/migration, retention 500 phiên và dọn
  dữ liệu con/recovery payload theo thời hạn.
- Dùng manifest phiên bản chung cho Watch, firmware, workflow và SBOM candidate.
- Giữ loader MediaPipe nguyên byte; ghi manifest phép đóng gói lại Face Landmarker.

Candidate đã clean-build từ source archive ngoài repository và đạt cổng `code-ready`. Hai bundle model revision 1 đã được đối chiếu byte-exact với URL chính thức và model card Apache-2.0 theo từng thành phần. Giới hạn: người dùng xác nhận đã cập nhật/kiểm thử thiết bị, nhưng chưa có device report byte-exact cho artifact CI 2.3.0 với đầy đủ bài soak/Doze và thống kê delivery timing.
