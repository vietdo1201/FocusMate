# FocusMate ESP32-S3 + Wear OS

[![Giấy phép Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

FocusMate là nguyên mẫu local-first hỗ trợ phiên học trên Wear OS. Đường v2 mặc định dùng ESP32-S3 N16R8 chạy face detector nhẹ và gửi bounding box chuẩn hóa cùng confidence/quality. Watch thực hiện calibration, phân loại posture, tổng hợp insight và đưa ra reminder bằng rule deterministic. Một đường frame nén, tạm thời để model posture chạy trên Watch được cho phép về kiến trúc nhưng chưa triển khai trong APK hiện tại.

Thiết bị kiểm chứng của hồ sơ: **Galaxy Watch 5 Pro** (Wear OS, API ≥ 33) + **ESP32-S3 N16R8 + OV2640**. Galaxy Watch FE nằm trong dải tương thích `minSdk 30` nhưng không phải thiết bị kiểm chứng và không có bằng chứng thiết bị — xem [Roadmap](soucre_code/from_On_Hand_3_android_wear/ROADMAP.md).

```text
OV2640 → ESP32-S3 face detector → FaceObservationV1 (bbox/quality, không ảnh)
                                      ↓ BLE GATT (chưa triển khai)
Wear OS (Galaxy Watch 5 Pro): calibration → geometry/temporal posture → Rule Engine v2 → UI/report
                         motion/HR ────────────────────────────────┘
```

Rule Engine v2 là nguồn quyết định break duy nhất. Posture không làm gián đoạn lúc học; lời khuyên chỉ xuất hiện khi bắt đầu nghỉ và trong báo cáo cuối phiên. Model posture tương lai bắt đầu ở shadow mode và có thể nhận feature bbox hoặc frame nén tạm thời qua protocol opt-in riêng.

## Trạng thái

- Watch app, protocol contract, rule v2 và classifier hình học đã có unit test local.
- BLE client, GATT server, firmware ESP-IDF, camera và face detector trên phần cứng: `NOT_STARTED / UNVERIFIED`.
- Posture trên chuỗi bbox hiện mới là synthetic test; chưa xác nhận với ESP32-S3 và Galaxy Watch 5 Pro thật.
- Yawn/PFLD: `deferred/unavailable` trong v2 và alpha.

Không truyền frame trong `FaceObservationV1`. Đường frame tương lai phải là protocol opt-in riêng, không lưu trữ, không cloud và không chứa định danh. Dự án không phải thiết bị y tế.

## Tài liệu

- [Yêu cầu sản phẩm](help.md)
- [Governance](docs/GOVERNANCE.md)
- [Trạng thái có bằng chứng](docs/STATUS.md)
- [Roadmap](soucre_code/from_On_Hand_3_android_wear/ROADMAP.md)
- [Kế hoạch triển khai](soucre_code/from_On_Hand_3_android_wear/IMPLEMENTATION_PLAN.md)
- [ADR 0002](docs/decisions/0002-watch-rule-engine-and-detector-split.md)
- [ADR 0003](docs/decisions/0003-optional-frame-transport-to-watch.md)
- [ADR 0004](docs/decisions/0004-gatt-profile-and-canonical-framing.md)
- [GATT profile (normative)](docs/GATT_PROFILE.md)

Chạy kiểm chứng từ root:

```powershell
./verify.ps1
```
