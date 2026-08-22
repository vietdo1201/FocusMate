# Sơ đồ đấu chân ESP32-S3 N16R8

Trạng thái: `DRAFT / UNVERIFIED`. Chưa có firmware smoke test; phải xác nhận đúng board revision trước khi cấp nguồn.

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
| XCLK | bỏ trống theo giả định module có clock |

Camera dùng 3,3 V và GND chung. Không cấp 5 V vào 3V3/GPIO. USB chỉ dành cho nạp/log. Kiến trúc hiện hành không có loa/audio.

Đường v2 hiện tại chỉ phát bbox/quality. Kiến trúc cho phép bổ sung một đường frame riêng để model posture chạy trên Watch: frame phải giảm độ phân giải/nén, truyền tạm thời theo capability đã thương lượng, không ghi xuống storage và không đi qua Internet. Frame không được nhét vào `FaceObservationV1`; đường này cần protocol/version và benchmark pin/băng thông riêng trước khi bật.
