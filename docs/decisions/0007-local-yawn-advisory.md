# ADR 0007: Local yawn advisory

## Quyết định

Web và Watch chạy MediaPipe Face Landmarker revision 1 trên cùng JPEG local đã
dùng cho Pose. Một classifier temporal kết hợp `jawOpen` và Mouth Aspect Ratio,
baseline miệng khép cá nhân, thời lượng mở và cooldown để đếm sự kiện ngáp.

Ba sự kiện trong cửa sổ 10 phút tạo cảnh báo buồn ngủ/mệt độc lập: Watch rung
ngắn, chỉ hiện banner khi màn hình đang bật, và lưu summary số vào báo cáo.
Không suy luận chán nản, không tự sửa fatigue và không gọi `WatchRuleEngine`.

## Ranh giới

- Không đổi GATT UUID, BLE payload hay `FaceObservationV1`. HTTP local có broker
  event V2 tùy chọn theo phiên; firmware/app cũ tiếp tục dùng summary legacy.
- Frame và landmark chỉ ở RAM; storage chỉ chứa baseline số trên Web và count,
  duration, alert count cùng timestamp cửa sổ gần nhất trong phiên Watch.
- Tính năng là advisory thử nghiệm, không phải chẩn đoán y tế và không được ghi
  `VERIFIED_DEVICE` chỉ từ kết quả build/flash/install.

ADR này thay thế riêng quyết định defer Yawn/PFLD trong ADR 0002; mọi ranh giới
Rule Engine và quyền riêng tư của ADR 0002 vẫn giữ nguyên.
