# FocusMate — trợ lý tập trung local-first

[![Verify](https://github.com/vietdo1201/FocusMate/actions/workflows/verify.yml/badge.svg)](https://github.com/vietdo1201/FocusMate/actions/workflows/verify.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![REUSE](https://img.shields.io/badge/REUSE-compliant-informational.svg)](REUSE.toml)

FocusMate là phần mềm nguồn mở kết hợp **Galaxy Watch 5 Pro** và
**ESP32-S3 N16R8 + OV2640** để hỗ trợ phiên học. Toàn bộ camera, AI, cảm biến và
quyết định nhắc nghỉ chạy cục bộ; dự án không tải ảnh hoặc dữ liệu sức khỏe lên
cloud.

```text
OV2640 → ESP32-S3: camera + face landmarks + Web dashboard
                    │ BLE GATT mã hóa: bbox, trạng thái, Yawn Sync V2
                    │ HTTP local có token: frame tạm thời, fallback tương thích
                    ▼
Galaxy Watch: Motion/HR + Pose/Face Landmarker → posture/yawn advisory
                                                → Rule Engine v2 → UI/report
```

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
`VERIFIED_DEVICE` và hạng mục còn thử nghiệm. Ảnh One UI Watch ghi nhận FocusMate
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
[SPDX SBOM](sbom/focusmate-v2.2.1.spdx.json).

## English summary

FocusMate is an open-source, local-first study assistant for Wear OS and an
ESP32-S3 camera board. Camera frames, sensor data, MediaPipe inference, posture
and yawn advisories, and the deterministic break rule engine stay on the local
devices. Reproducible build instructions, pinned model hashes, tests, licensing
metadata, an SPDX 2.3 SBOM, and device-evidence limitations are published here.
FocusMate is not a medical device.
