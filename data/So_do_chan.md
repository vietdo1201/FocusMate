# Sơ đồ đấu chân ESP32-S3 N16R8

Trạng thái: `PINOUT_CONFIRMED / VERIFIED_DEVICE`. Chủ thiết bị xác nhận dây đang cắm đúng; camera smoke đã lặp lại trên firmware ESP-IDF hiện hành, gần nhất ngày 2026-08-26. Source đối chiếu ban đầu: `C:\Users\vietdo1201\Documents\Arduino\sketch_aug12a\sketch_aug12a.ino`, SHA-256 `16C8DF6F47ECAACB807F503F053DB55B3002FA08A76F8DCF1B5F123667D49AAF` (2026-08-12).

| OV2640 | ESP32-S3 |
|---|---:|
| GND | GND |
| 3.3V | 3V3 |
| SCL | GPIO2 |
| SDA | GPIO1 |
| D0..D3 | GPIO4, 5, 6, 7 |
| D4..D7 | GPIO15, 16, 17, 18 |
| PCLK | GPIO39 |
| HREF | GPIO41 |
| VSYNC | GPIO42 |
| RST | GPIO40 |
| PWDN | GPIO38 |
| XCLK | không nối (`-1`); module 18 chân có oscillator riêng, khai báo 24 MHz |

Camera dùng 3,3 V và GND chung. Không cấp 5 V vào 3V3/GPIO. USB chỉ dành cho nạp/log. Hai gate `FOCUSMATE_CAMERA_ENABLE` + `FOCUSMATE_CAMERA_PINOUT_CONFIRMED` đã được bật sau xác nhận của chủ thiết bị và đối chiếu source chạy trước đây; log smoke 2026-08-22 và 2026-08-26 xác nhận pinout trên board thật. Kiến trúc hiện hành không có loa/audio.

Đường v2 hiện tại chỉ phát bbox/quality. Kiến trúc cho phép bổ sung một đường frame riêng để model posture chạy trên Watch: frame phải giảm độ phân giải/nén, truyền tạm thời theo capability đã thương lượng, không ghi xuống storage và không đi qua Internet. Frame không được nhét vào `FaceObservationV1`; đường này cần protocol/version và benchmark pin/băng thông riêng trước khi bật.
