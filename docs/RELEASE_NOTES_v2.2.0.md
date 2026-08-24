# FocusMate v2.2.0

Bản phát hành nguồn mở ổn định cho Galaxy Watch 5 Pro và ESP32-S3 N16R8 +
OV2640.

## Thay đổi chính

- Yawn Sync V2 qua BLE với HTTP local fallback và chống đếm trùng.
- Chính sách tiết kiệm pin 5/2/1 Hz, inference thích ứng, sensor batching và
  retry backoff.
- Build Android/firmware tái lập từ clean checkout với model SHA-256 cố định.
- SPDX/REUSE, third-party notices, SPDX 2.3 SBOM và tài liệu kỹ thuật/cuộc thi.

## Artifact

- `FocusMate-Wear-v2.2.0.apk`: APK Wear OS đã ký.
- `FocusMate-ESP32S3-v2.2.0.bin`: firmware merged để flash tại offset `0x0`.
- `focusmate-v2.2.0.spdx.json`: SBOM SPDX 2.3.
- `THIRD_PARTY_NOTICES.txt`, chứng thư công khai, thông tin chữ ký và
  `SHA256SUMS.txt`.

Đối chiếu SHA-256 trước khi cài/flash. Chỉ flash đúng ESP32-S3 N16R8 đã xác nhận
pinout OV2640. FocusMate không phải thiết bị y tế.

## English summary

This release adds reconnect-safe Yawn Sync V2 and adaptive Watch power policies,
while making Android and ESP-IDF builds reproducible from a clean checkout. All
inference remains local. The release includes a signed APK, merged firmware,
checksums, public signing certificate, third-party notices, and an SPDX 2.3 SBOM.
