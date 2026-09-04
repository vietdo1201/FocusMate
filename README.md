<p align="center">
  <img src="docs/assets/focusmate-logo.svg" width="112" alt="FocusMate logo chữ F">
</p>

<h1 align="center">FocusMate</h1>

<p align="center"><strong>A local-first focus coach for WearOS and ESP32-S3.</strong></p>
<p align="center">Theo dõi tư thế, ngáp và nhắc nghỉ — xử lý hoàn toàn trên thiết bị nhỏ gọn</p>

<p align="center">
  <a href="docs/DEMO.md"><strong>Watch demo</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/vietdo1201/FocusMate/releases/latest"><strong>Download release</strong></a>
  &nbsp;·&nbsp;
  <a href="#how-it-works"><strong>How it works</strong></a>
</p>

<p align="center">
  <a href="https://github.com/vietdo1201/FocusMate/actions/workflows/verify.yml"><img src="https://github.com/vietdo1201/FocusMate/actions/workflows/verify.yml/badge.svg" alt="Verify"></a>
  <a href="tests/FocusMate_Test/TEST_MATRIX.md"><img src="https://img.shields.io/badge/System%20Test-24%2F24%20PASS-brightgreen.svg" alt="System Test: 24/24 PASS"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License: Apache-2.0"></a>
  <a href="REUSE.toml"><img src="https://img.shields.io/badge/REUSE-compliant-informational.svg" alt="REUSE compliant"></a>
</p>

<table>
  <tr>
    <td align="center" width="33%"><strong>100% local</strong><br><sub>No camera or health<br>cloud upload</sub></td>
    <td align="center" width="34%"><strong>Wear OS + ESP32</strong><br><sub>Real cross-device<br>prototype</sub></td>
    <td align="center" width="33%"><strong>Reproducible</strong><br><sub>Pinned models, SBOM<br>and tests</sub></td>
  </tr>
</table>

## How it works

```text
OV2640 → ESP32-S3: camera + face landmarks + Web dashboard
                    │ BLE GATT mã hóa: bbox, trạng thái, Yawn Sync V2
                    │ HTTP local có token: frame tạm thời, fallback tương thích
                    ▼
Galaxy Watch: Motion/HR + Pose/Face Landmarker → posture/yawn advisory
                                                → Rule Engine v2 → UI/report
```

## ✅ Kiểm thử hệ thống

> **Recorded System Test: 24 / 24 test cases PASS — 100%**
>
> Kiểm thử trên ESP32-S3 + OV2640 + Galaxy Watch 5 Pro + Web Dashboard,
> thực hiện ngày 28–29/08/2026.

| Kết quả | Số lượng |
|---|---:|
| ✅ Passed | **24** |
| ❌ Failed | **0** |
| ⏸ Not tested | **0** |
| **Pass rate** | **100%** |

**Phạm vi kiểm thử:** ESP32 → Wi-Fi → Camera → Web Dashboard → AI
Posture/Yawn → Watch Alert → Session → Report → Reconnect → phiên 61 phút đã ghi nhận.

```text
ESP32-S3
   ├─ Boot / Wi-Fi / OV2640 ................. PASS
   ▼
Web Dashboard
   ├─ Camera stream / Posture AI / Yawn AI .. PASS
   ▼
Galaxy Watch
   ├─ Connection / Alert / Session .......... PASS
   ▼
End-to-End
   └─ Events / Report / Reconnect / phiên 61 phút ... PASS

TOTAL: 24 / 24 PASS
```

- 📋 [Ma trận 24 test cases và evidence trực tiếp](tests/FocusMate_Test/TEST_MATRIX.md)
- 📸 [Toàn bộ ảnh/video bằng chứng](tests/FocusMate_Test/Evidence/)
- 📑 [Bản Excel kiểm thử](tests/FocusMate_Test/Excel/FocusMate_24_Test_Cases_Severity.xlsx)

Test suite xác minh các kịch bản chức năng đã được tài liệu hóa. Kết quả này
không phải tuyên bố tổng quát về độ chính xác AI, y tế, nhiệt độ hoặc độ tin cậy
dài hạn; các giới hạn đó tiếp tục được công bố trong [STATUS.md](docs/STATUS.md).

## Điểm nổi bật

- Phiên học, nhắc nghỉ và cooldown được quyết định bởi Rule Engine v2 có test.
- ESP32-S3 chạy camera/detector và dashboard offline; Watch tiếp tục hoạt động
  khi BLE hoặc Wi-Fi local gián đoạn.
- Pose và Face Landmarker chạy local, model được pin bằng SHA-256 và đóng gói
  lúc build; runtime không tải CDN.
- Yawn Sync V2 chống đếm trùng qua reconnect/reboot và có fallback với firmware
  cũ. Yawn/posture chỉ là lời khuyên, không thay đổi quyết định nghỉ.
- `TOO_CLOSE` dùng đồng thuận scale khuôn mặt từ Pose, Face và detector ESP;
  một bbox phình do bàn tay không còn đủ để kết luận người dùng ngồi quá gần.
- Chính sách pin Watch: BLE 5 Hz khi màn hình bật, 2 Hz khi tắt, 1 Hz khi nóng;
  inference/frame polling thích ứng, sensor batching 2 giây và retry backoff.
- Dự án không phải thiết bị y tế và không dùng kết quả để chẩn đoán.

## Build từ mã nguồn

Yêu cầu: Python 3.11+, Node.js 20+, JDK 17, Android SDK và ESP-IDF 5.5.5.

```powershell
python tools/bootstrap_assets.py
./verify.ps1
```

Linux/macOS sau khi kích hoạt ESP-IDF:

```bash
python tools/bootstrap_assets.py
./verify.sh
```

Bootstrap chỉ tải artifact đã khóa phiên bản và từ chối nếu SHA-256 sai. Không
cần sửa header hoặc mã nguồn thủ công. Chi tiết tại [BUILDING.md](docs/BUILDING.md).

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `wear/` | Ứng dụng Wear OS và module protocol Kotlin |
| `firmware/` | Firmware ESP-IDF, dashboard và detector |
| `tools/` | Bootstrap asset, verify và tạo SBOM |
| `docs/` | Protocol, ADR, AI, build và hồ sơ cuộc thi |
| `reports/` | Bằng chứng thiết bị có giới hạn được ghi rõ |
| `sbom/` | Software Bill of Materials SPDX 2.3 |

## Trạng thái và bằng chứng

Xem [STATUS.md](docs/STATUS.md) để phân biệt `VERIFIED_LOCAL`,
`VERIFIED_DEVICE` và hạng mục còn thử nghiệm. Release hiện hành là `v2.2.2`, gồm
Wi-Fi setup bền vững, Yawn Shape V5 và báo cáo `session_advice_v1`. Source tổ tiên
của V5 đã [flash/boot smoke trên ESP](reports/2026-08-26-yawn-shape-v5-firmware-flash.md),
nhưng artifact `v2.2.2` chính xác chưa được flash lại và không phải accuracy evidence.
Ảnh One UI Watch ghi nhận FocusMate
dùng 1,1% trong 11 giờ 49 phút là
[bằng chứng hỗ trợ](reports/2026-08-25-watch-battery-observation.md), không phải
benchmark trước–sau.

- [AI và giới hạn kỹ thuật](docs/AI.md)
- [GATT profile](docs/GATT_PROFILE.md)
- [Local frame protocol](docs/LOCAL_FRAME_V1.md)
- [Ma trận tiêu chí cuộc thi](docs/COMPETITION.md)
- [Kịch bản trình diễn](docs/DEMO.md)
- [Changelog](CHANGELOG.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md)
- [Bug tracker](https://github.com/vietdo1201/FocusMate/issues)

## Giấy phép

Mã dự án dùng Apache-2.0, ngoại trừ component `focusmate_dns` dùng MIT. Mỗi tệp
nguồn có SPDX; toàn văn giấy phép nằm trong `LICENSES/`. Dependency và model
được liệt kê trong [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) và
[SPDX SBOM](sbom/focusmate-v2.2.2.spdx.json).

## English summary

FocusMate is an open-source, local-first study assistant for Wear OS and an
ESP32-S3 camera board. Camera frames, sensor data, MediaPipe inference, posture
and yawn advisories, and the deterministic break rule engine stay on the local
devices. Reproducible build instructions, pinned model hashes, tests, licensing
metadata, an SPDX 2.3 SBOM, and device-evidence limitations are published here.
FocusMate is not a medical device.
