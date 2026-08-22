# Chính sách license

Mã nguồn dự án dùng Apache License 2.0, xem `LICENSE`. Dependency bên thứ ba giữ license của tác giả.

Model hoặc detector trước khi phân phối phải ghi nguồn, immutable revision, SHA-256, kích thước, format/conversion, SPDX/license, runtime revision và benchmark trên target. Không commit model binary vào Git.

Face detector ESP tương lai phải có provenance đầy đủ. Interface posture shadow không cho phép thêm runtime/model mà bỏ qua review license. Dữ liệu synthetic phải ghi cách tạo; không đưa ảnh khuôn mặt hoặc dữ liệu nhận dạng vào fixture/report.

Khi phân phối binary, tạo inventory từ lockfile, kiểm tra NOTICE/attribution và cập nhật `NOTICE` nếu cần.
