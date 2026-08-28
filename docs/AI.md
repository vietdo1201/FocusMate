# AI, dữ liệu và giới hạn

FocusMate dùng model pretrained MediaPipe Pose Landmarker Lite và Face
Landmarker, cùng detector landmark của Espressif trên ESP32-S3. Không huấn luyện
trên dữ liệu người dùng và không gửi frame lên cloud.

| Khối | Thiết bị | Đầu ra | Vai trò |
|---|---|---|---|
| Face detector | ESP32-S3 | bbox/landmarks/quality | Theo dõi mặt và dashboard |
| Pose Landmarker Lite | Watch/Web | 33 landmarks | Posture advisory |
| Face Landmarker | Watch/Web | mouth landmarks; `jawOpen` chỉ có ở Watch | Yawn advisory |
| Rule Engine v2 | Watch | break decision/reason | Nguồn quyết định nghỉ duy nhất |
| Session Advice v1 | Watch | tối đa ba action code + evidence | Lời khuyên cuối phiên, không phát reminder |

Model/runtime được khóa bằng phiên bản và SHA-256 trong
`tools/bootstrap_assets.py`. Frame local là dữ liệu tạm thời trong RAM, không
được ghi file hoặc đưa vào báo cáo. BLE được bond/mã hóa; HTTP local dùng token
boot-scoped chỉ truyền trong header.

Web dùng Face Landmarker compact không có blendshape head để vừa phân vùng asset,
do đó yawn V5 dùng Mouth Aspect Ratio (MAR) cùng độ giãn ngang khóe miệng đã
chuẩn hóa theo khoảng cách hai mắt để loại nụ cười rộng. Watch dùng cùng shape
gate và thêm blendshape `jawOpen`. Cả hai chỉ đếm sau ít nhất 1,6 giây mở rõ;
đây là heuristic chưa có device accuracy claim.

Posture và yawn là tín hiệu hỗ trợ, có thể sai khi thiếu sáng, bị che mặt hoặc
góc camera không phù hợp. Chúng không chẩn đoán sức khỏe, không sửa fatigue score
và không thay đổi `watch_rules_v2`.

`session_advice_v1` chạy deterministic và local khi đóng phiên. Engine ưu tiên
reason code v1/v2, sau đó mới dùng posture/ngáp và nhịp tim. Nhịp tim chỉ được
so với baseline của chính phiên khi có ít nhất năm mẫu baseline, năm mẫu sau
baseline, tăng đồng thời ít nhất 15 BPM và 15%, và có thêm fatigue/ngáp/rule nghỉ
xác nhận. Đây là tín hiệu để nghỉ và đo lại, không phải ngưỡng y khoa. Báo cáo
lưu action code/evidence cùng các summary số, không lưu câu chữ hoặc mẫu sensor thô.
