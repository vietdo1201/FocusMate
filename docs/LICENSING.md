# Chính sách license

Mã nguồn dự án dùng Apache License 2.0, xem `LICENSE`. Dependency bên thứ ba giữ license của tác giả.

Model hoặc detector trước khi phân phối phải ghi nguồn, immutable revision, SHA-256, kích thước, format/conversion, SPDX/license, runtime revision và benchmark trên target. Không commit model binary vào Git.

Face detector ESP tương lai phải có provenance đầy đủ. Interface posture shadow không cho phép thêm runtime/model mà bỏ qua review license. Dữ liệu synthetic phải ghi cách tạo; không đưa ảnh khuôn mặt hoặc dữ liệu nhận dạng vào fixture/report.

Khi phân phối binary, tạo inventory từ lockfile, kiểm tra NOTICE/attribution và cập nhật `NOTICE` nếu cần.

## MediaPipe Pose local

Artifact được phép đưa vào build thử nghiệm chỉ khi hash khớp contract `docs/LOCAL_FRAME_V1.md`:

- Watch: `com.google.mediapipe:tasks-vision:1.0.0`, AAR SHA-256 `53d45569649ff7e9d84457f070481dbb779ddd057671d4adcd9abcb77ff172c6`.
- Web: `@mediapipe/tasks-vision@1.0.1`, package SHA-256 `ee318eaa3d42230aa10910d114faf2a488c577c4e4d33c7cb04126924aca505f`.
- Model: Pose Landmarker Lite float16 revision 1, SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`.
- Model: Face Landmarker float16 revision 1, 3,758,596 bytes, SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`.

Hash chỉ chứng minh artifact byte-exact, không tự chứng minh quyền phân phối. Trước release phải lưu trang/model card hoặc license đi kèm của đúng revision, SPDX, attribution/NOTICE và điều khoản redistribution vào inventory report. Thiếu bất kỳ mục nào thì model path vẫn `EXPERIMENTAL`, không `RELEASE_ELIGIBLE`.
