# AI, dữ liệu và giới hạn

FocusMate dùng model pretrained MediaPipe Pose Landmarker Lite và Face
Landmarker, cùng detector landmark của Espressif trên ESP32-S3. Không huấn luyện
trên dữ liệu người dùng và không gửi frame lên cloud.

| Khối | Thiết bị | Đầu ra | Vai trò |
|---|---|---|---|
| Face detector | ESP32-S3 | bbox/landmarks/quality | Theo dõi mặt và dashboard |
| Pose Landmarker Lite | Watch/Web | 33 landmarks | Posture advisory |
| Face Landmarker | Watch/Web | mouth landmarks | Yawn advisory |
| Rule Engine v2 | Watch | break decision/reason | Nguồn quyết định nghỉ duy nhất |

Model/runtime được khóa bằng phiên bản và SHA-256 trong
`tools/bootstrap_assets.py`. Frame local là dữ liệu tạm thời trong RAM, không
được ghi file hoặc đưa vào báo cáo. BLE được bond/mã hóa; HTTP local dùng token
boot-scoped chỉ truyền trong header.

Posture và yawn là tín hiệu hỗ trợ, có thể sai khi thiếu sáng, bị che mặt hoặc
góc camera không phù hợp. Chúng không chẩn đoán sức khỏe, không sửa fatigue score
và không thay đổi `watch_rules_v2`.
