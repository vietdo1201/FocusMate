# Contributing

1. Đọc `docs/GOVERNANCE.md` và ADR hiện hành.
2. Không commit model binary, ảnh khuôn mặt, raw participant data hoặc signing material.
3. Thay đổi rule/protocol phải có boundary/malformed tests và reason/schema version ổn định.
4. Chạy `./verify.ps1` trước khi mở pull request.
5. Không nâng BLE, firmware hoặc device status từ unit test synthetic.
