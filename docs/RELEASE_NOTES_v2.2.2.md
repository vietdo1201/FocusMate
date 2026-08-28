# FocusMate v2.2.2

## Tiếng Việt

### Điểm mới

- Sửa quét Wi-Fi làm dashboard treo, mất AP hoặc ESP reset. Scan chạy trong
  worker riêng, quét từng kênh với Wi-Fi/BLE coexistence, cache kết quả và cung
  cấp API trạng thái bất đồng bộ.
- Thêm nhập SSID thủ công; người dùng vẫn cấu hình được mạng 2,4 GHz khi không
  muốn hoặc không thể quét.
- Yawn Shape V5 giữ `jawOpen` ở trạng thái unavailable trên Web compact, yêu cầu
  MAR mở/peak, thời lượng 1,6 giây và loại cười giãn ngang. Watch dùng thêm
  blendshape `jawOpen`; Yawn Sync V2 tiếp tục chống đếm trùng.
- Thêm `session_advice_v1`: báo cáo cuối phiên cuộn, deterministic, tối đa ba
  hành động có evidence từ rule, bất động, posture, ngáp và nhịp tim tương đối.
  Engine này không phát reminder và không thay `watch_rules_v2`.
- Dashboard ưu tiên Face Landmarker mới cho overlay; bbox ESP chỉ là fallback.

### Artifact

Release cung cấp APK Wear đã ký, firmware update app, Web/MediaPipe assets update,
factory image, chứng thư APK, báo cáo chữ ký, SHA-256, hướng dẫn flash, notices và
SPDX 2.3 SBOM. Dùng hai file `update-*` để giữ NVS; chỉ dùng `factory-full` khi
cài mới hoặc phục hồi.

### Giới hạn bằng chứng

Tag `v2.2.2` được build/test bằng CI và local gate. Không có Watch/ESP tại thời
điểm phát hành nên byte-exact APK/firmware của release chưa được cài/flash lại.
Device smoke trước đó chỉ là bằng chứng cho source tổ tiên. Accuracy ngáp/cười/
nói, posture đủ tám state, low-light, thermal và soak dài vẫn là experimental;
đây không phải tính năng chẩn đoán y tế.

## English

This release makes Wi-Fi setup resilient with an asynchronous per-channel scan,
cached status API, Wi-Fi/BLE coexistence, and manual SSID entry. It adds Yawn
Shape V5 safeguards, a deterministic evidence-based end-of-session advice report,
and a Face Landmarker-first dashboard overlay. Yawn/posture signals remain local
advisories and never replace the deterministic break-rule authority.

The release includes a signed Wear APK, separate app/assets update images, a
factory image, signature evidence, checksums, flashing instructions, notices,
and an SPDX 2.3 SBOM. Exact v2.2.2 artifacts were built and tested but could not
be installed or flashed at release time because the Watch and ESP32-S3 were not
available. Real-user accuracy, low-light, thermal, and long soak claims remain
explicitly out of scope.
