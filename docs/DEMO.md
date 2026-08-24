# Kịch bản trình diễn

1. Mở dashboard ESP và Watch, xác nhận firmware/app `2.2.0`, BLE bonded và model
   đều sẵn sàng.
2. Bắt đầu phiên học; cho thấy motion/HR, camera và posture cập nhật cục bộ.
3. Minh họa màn hình Watch tắt làm BLE/frame giảm rate, bật lại khôi phục 5 Hz.
4. Minh họa posture/yawn advisory và giải thích chúng không thay đổi Rule Engine.
5. Ngắt Wi-Fi/BLE ngắn để cho thấy Watch tiếp tục phiên và tự reconnect/backoff.
6. Kết thúc phiên, mở báo cáo và chỉ ra dữ liệu không rời mạng local.

Chuẩn bị sẵn video WebM quay từ đúng bản release làm phương án dự phòng, ghi rõ
đó là recording. Không dùng fake/demo mode và không gắn nhãn `VERIFIED_DEVICE`
cho tình huống chưa có report thật.
