# FocusMate v2.2.1

## Tiếng Việt

Bản vá này sửa báo nhầm `QUÁ GẦN` khi chống tay. Web và Watch chỉ kết luận khi
ít nhất 2/3 nguồn scale Pose-eye, Face-eye và bbox ESP cùng vượt `1,35×` baseline;
firmware fallback cần cả bbox và khoảng cách hai mắt detector. Một bbox phình
riêng lẻ không còn đủ để tạo nhãn.

Dashboard hiển thị ellipse khuôn mặt, đường hai mắt, từng ratio và số vote. API
công bố firmware version cùng hash Web assets để phát hiện flash lệch phiên bản.
Baseline revision 3 bổ sung eye-scale nhưng giữ baseline tư thế, Wi-Fi và NVS.
Không có model hoặc lượt inference mới; cadence Face Landmarker vẫn 350 ms.

Artifact `update-app` và `update-assets` dùng để nâng cấp mà không xóa NVS.
Artifact `factory-full` được ghi nhãn riêng và chỉ dành cho cài mới/phục hồi;
xem `FLASHING.txt` trước khi dùng. Mọi artifact có SHA-256 trong
`SHA256SUMS.txt`; APK kèm chứng thư công khai và báo cáo xác minh chữ ký.

## English

This patch prevents false `TOO_CLOSE` results when a hand inflates the ESP face
box. Web and Watch require a 2-of-3 scale consensus; firmware requires both its
box and existing five-keypoint eye distance. It adds no model or inference pass.
The dashboard exposes per-source ratios, votes, firmware version, and Web asset
manifest hash. Use the separate app/assets update images to preserve NVS; the
clearly named factory image is only for a full installation or recovery.
