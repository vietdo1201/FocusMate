# Sơ đồ đấu chân ESP32-S3 N16R8

Trạng thái: `DRAFT / UNVERIFIED`. Firmware đã có smoke-test bị khóa mặc định; chưa được phép bật cho tới khi xác nhận đúng board revision, toàn bộ dây và nguồn XCLK trên chính phần cứng đang cắm.

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
| XCLK | chưa chốt; hiện để trống theo giả định module có oscillator riêng |

Camera dùng 3,3 V và GND chung. Không cấp 5 V vào 3V3/GPIO. USB chỉ dành cho nạp/log. Phải ghi lại ảnh/nhãn board và oscillator trước khi chuyển hai gate `FOCUSMATE_CAMERA_ENABLE` + `FOCUSMATE_CAMERA_PINOUT_CONFIRMED` sang `y`. Kiến trúc hiện hành không có loa/audio.

Đường v2 hiện tại chỉ phát bbox/quality. Kiến trúc cho phép bổ sung một đường frame riêng để model posture chạy trên Watch: frame phải giảm độ phân giải/nén, truyền tạm thời theo capability đã thương lượng, không ghi xuống storage và không đi qua Internet. Frame không được nhét vào `FaceObservationV1`; đường này cần protocol/version và benchmark pin/băng thông riêng trước khi bật.
