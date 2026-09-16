# Kịch bản trình diễn v2.3.0

Kịch bản thao tác dự kiến; mỗi lần chạy được ghi thành report với phiên bản và
hash artifact. Các video ngày 28–29/08/2026 được giới thiệu là recording lịch sử.

1. Mở dashboard ESP và Watch, xác nhận firmware/app `2.3.0`, Watch code 26, hash Web assets,
   BLE bonded và model đều sẵn sàng.
2. Bắt đầu phiên học; cho thấy motion/HR, camera và posture cập nhật cục bộ.
3. Minh họa màn hình Watch tắt làm BLE/frame giảm rate, bật lại khôi phục 5 Hz.
4. Ngồi chuẩn, chống tay ít nhất 10 giây rồi tiến gần camera; chỉ tình huống cuối
   được báo `TOO_CLOSE`. Chỉ vào ba scale và số vote trên dashboard.
5. Minh họa posture/yawn advisory yên lặng. Giải thích HR, posture, yawn và
   check-in shadow tách biệt với quyết định nhắc nghỉ của Rule Engine.
6. Ngắt Wi-Fi/BLE ngắn để cho thấy Watch tiếp tục phiên và tự reconnect/backoff.
7. Thực hiện pause/resume, nghỉ chủ động và check-in tự nguyện. Chỉ ra thời gian
   học và nghỉ được lưu riêng; đề nghị nghỉ foreground dùng thẻ không chặn.
8. Kết thúc phiên, mở báo cáo học/nghỉ/tạm dừng/chưa xác định, lịch sử lời nhắc
   và feedback; giới thiệu thiết kế xử lý dữ liệu trong mạng local.

Chuẩn bị sẵn video WebM quay từ đúng bản release làm phương án dự phòng, ghi rõ
đó là recording. Không dùng fake/demo mode và không gắn nhãn `VERIFIED_DEVICE`
cho tình huống chưa có report thật.
