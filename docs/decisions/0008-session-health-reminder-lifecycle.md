# ADR 0008: Vòng đời phiên, bằng chứng sức khỏe và hạn mức nhắc nghỉ

- Trạng thái: Accepted
- Ngày: 2026-09-15
- Thay thế: phần hành vi rung ngáp riêng trong ADR 0007; không thay kiến trúc pose/yawn của ADR 0006/0007

## Bối cảnh

FocusMate đã có `watch_rules_v2`, timer nghỉ và dữ liệu cảm biến nhưng trạng thái nằm trong JSON/SharedPreferences, thời lượng dựa trên wall clock, lời nhắc có ba lượt 0/2/5 phút và ngáp rung riêng. Điều này không đủ bền vững cho mục tiêu quản lý phiên và có thể tạo diễn giải sức khỏe quá mức.

## Quyết định

1. `watch_rules_v2` tiếp tục là policy duy nhất điều khiển lời nhắc. `checkin_shadow_v1` chỉ ghi so sánh.
2. HR, tư thế và ngáp không được sửa focus/fatigue hoặc thời điểm nghỉ. Ngáp/tư thế chỉ hiển thị yên lặng.
3. Mỗi đề nghị có một lượt ban đầu, tối đa một retry sau 5 phút; retry quá trễ 5 phút bị bỏ và chuyển yên lặng.
4. Nghỉ và khoảng chờ resume đều dừng study timer. Resume sau break tạo block mới; pause không reset block.
5. Có nghỉ chủ động và check-in tự nguyện. Không check-in bằng rung/alarm định kỳ.
6. SQLite là nguồn lưu authoritative. Snapshot/event liên quan transition phải cùng transaction và command có ID duy nhất.
7. Khoảng thời gian trong cùng boot dùng elapsed realtime; boot khác/không xác định chuyển `RECOVERY_REQUIRED`. Wall time chỉ để hiển thị.
8. UI gọi `SessionConfidence` là “độ đầy đủ dữ liệu”, không phải xác suất sức khỏe/tập trung.

## Phân loại tham số

- `EXISTING_PRODUCT_RULE`: 30/45/60 phút, cooldown 20 phút, nghỉ 5 phút, coverage 80%, freshness 60 giây.
- `ENGINEERING_DEFAULT`: retry 5 phút, grace 5 phút, checkpoint 30 giây, gap motion 2 giây.
- `EXPERIMENTAL`: TTL check-in shadow 20 phút.

Các giá trị trên không phải ngưỡng y khoa.

## Hệ quả

- Không phát notification/rung từ ngáp, tư thế hoặc shadow policy.
- Không nói “stress”, “hồi phục” hoặc “sức khỏe bình thường” từ sensor.
- SQLite migration phải giữ raw payload hỏng trong recovery store local và không dual-write lâu dài.
- Doze có thể làm retry trễ; ứng dụng đo và công bố độ trễ thay vì cam kết thời gian giao tuyệt đối.

## Bằng chứng

Registry chạy cùng ứng dụng nằm tại `HealthEvidence.kt`; ánh xạ chi tiết và giới hạn nằm trong `docs/SESSION_HEALTH_ROADMAP.md`.
