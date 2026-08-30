# FocusMate — ma trận kiểm thử hệ thống

> **Recorded System Test: 24 / 24 test cases PASS — 100%**
>
> Thiết bị: ESP32-S3 N16R8 + OV2640 + Galaxy Watch 5 Pro + Web Dashboard
> Ngày thực hiện: 28–29/08/2026

Kết quả dưới đây xác minh các kịch bản chức năng đã được tài liệu hóa. Đây không
phải tuyên bố tổng quát về độ chính xác AI, y tế, nhiệt độ hoặc độ tin cậy dài
hạn.

## Bằng chứng nổi bật

| Luồng quan trọng | Kết quả | Evidence |
|---|---|---|
| Camera ESP32 → Web | ✅ PASS | [TC05](Evidence/TC05_camera_web.png) |
| AI nhận diện posture | ✅ PASS | [TC08](Evidence/TC08_head_down.png) |
| AI phát hiện ngáp | ✅ PASS | [TC12](Evidence/TC12_yawn..png) |
| Watch nhận cảnh báo/rung | ✅ PASS | [Video](Evidence/Test_rungnhe_canhbao_ngap.mp4) |
| Mất kết nối → reconnect | ✅ PASS | [Disconnect](Evidence/TC21_Dis.mp4) · [Reconnect](Evidence/TC22_Reconnect.mp4) |
| Phiên dài → báo cáo | ✅ PASS | [Video](Evidence/TC24_KetThucPhienDongHo.mp4) |

## Đầy đủ 24 test cases

| ID | Chức năng | Kết quả | Thiết bị/phạm vi | Evidence |
|---|---|---|---|---|
| TC01 | Khởi động ESP32 | ✅ PASS | ESP32-S3 | [Ảnh](Evidence/TC01_ESP32_Boot.png) |
| TC02 | Kết nối Wi-Fi | ✅ PASS | ESP32-S3 | [Ảnh](Evidence/TC02_wifi.png) |
| TC03 | Khởi động camera OV2640 | ✅ PASS | ESP32-S3 / OV2640 | [Ảnh](Evidence/TC03_camera.png) |
| TC04 | Truy cập Web Dashboard | ✅ PASS | ESP32 / Web | [Ảnh](Evidence/TC04_web.png) |
| TC05 | Camera ESP32 hiển thị trên Web | ✅ PASS | ESP32 / Web | [Ảnh](Evidence/TC05_camera_web.png) |
| TC06 | Bắt đầu phiên học | ✅ PASS | Galaxy Watch | [Ảnh](Evidence/TC06_session_start.jpg) |
| TC07 | Nhận diện tư thế bình thường | ✅ PASS | AI / Web | [Ảnh](Evidence/TC07_normal_pose_web.png) |
| TC08 | Phát hiện cúi đầu | ✅ PASS | AI / Web | [Ảnh](Evidence/TC08_head_down.png) |
| TC09 | Phát hiện nghiêng trái | ✅ PASS | AI / Web | [Ảnh](Evidence/TC09_tilt_left.png) |
| TC10 | Phát hiện nghiêng phải | ✅ PASS | AI / Web | [Ảnh](Evidence/TC10_tilt_right.png) |
| TC11 | Phát hiện ngồi quá gần | ✅ PASS | AI / Web | [Ảnh](Evidence/TC11_slouch.png) |
| TC12 | Phát hiện ngáp | ✅ PASS | AI / Web | [Ảnh](Evidence/TC12_yawn..png) |
| TC13 | Không cảnh báo sai khi bình thường | ✅ PASS | AI / Web | [Video 60 giây](Evidence/TC_13.mp4) |
| TC14 | 100 inference liên tục, 0 lỗi | ✅ PASS | ESP32 / AI | [Ảnh log](Evidence/TC14_ai_repeated.png) |
| TC15 | Kích hoạt cảnh báo trạng thái | ✅ PASS | Full system | [Video](Evidence/test_canhbao.mp4) |
| TC16 | Smartwatch nhận cảnh báo | ✅ PASS | ESP32 / Galaxy Watch | [Video](Evidence/test_canhbao.mp4) |
| TC17 | Smartwatch rung/hiển thị thông báo | ✅ PASS | Galaxy Watch | [Video](Evidence/Test_rungnhe_canhbao_ngap.mp4) |
| TC18 | Ghi nhận sự kiện trong session | ✅ PASS | Web / Galaxy Watch | [Web](Evidence/thongke_w.jpg) · [Watch](Evidence/thongke_watch.jpg) |
| TC19 | Tổng hợp và gợi ý cuối phiên | ✅ PASS | Galaxy Watch | [Sự kiện](Evidence/TC19_session_events.jpg) · [Gợi ý](Evidence/TC19_summary_suggestion.jpg) |
| TC20 | Kết thúc phiên, dữ liệu không mất | ✅ PASS | Galaxy Watch | [Ảnh](Evidence/TC20_session_end.jpg) · [Video](Evidence/test_khi-ketthuc.mp4) |
| TC21 | Mất Wi-Fi, hệ thống không crash | ✅ PASS | ESP32 / Web | [Video](Evidence/TC21_Dis.mp4) |
| TC22 | Khôi phục kết nối Wi-Fi | ✅ PASS | ESP32 / Web | [Video](Evidence/TC22_Reconnect.mp4) |
| TC23 | Toàn bộ luồng tích hợp | ✅ PASS | Full system | [Web/AI](Evidence/test_demo_web.mp4) · [Watch](Evidence/test_canhbao.mp4) · [Kết thúc](Evidence/test_khi-ketthuc.mp4) |
| TC24 | Phiên dài, kết thúc và báo cáo | ✅ PASS | Full system | [61 phút](Evidence/TC24_Phiendai.jpg) · [Video kết thúc](Evidence/TC24_KetThucPhienDongHo.mp4) |

## Hồ sơ gốc

- [Bảng Excel: expected, actual, severity, ngày test và người test](Excel/FocusMate_24_Test_Cases_Severity.xlsx)
- [Toàn bộ thư mục Evidence](Evidence/)
- [Tổng quan bộ test](README.md)
