# Session Advice v1 — device smoke 2026-08-27

Trạng thái bằng chứng: `VERIFIED_DEVICE` cho build/install/flash, BLE transport,
lưu schema và UX báo cáo của một phiên ngắn không có cảnh báo; `UNVERIFIED` cho
các nhánh lời khuyên rule dài, posture, ngáp và nhịp tim trên người dùng thật.

## Phạm vi và thiết bị

| Trường | Giá trị |
|---|---|
| Source | Worktree chứa report này; chưa phải commit hoặc release |
| Watch | Galaxy Watch 5 Pro `SM-R925F`, Wear OS/Android 16, API 36 |
| Watch build | Debug APK `versionCode 24`, `versionName 2.2.1` |
| ESP | ESP32-S3 N16R8 rev 0.2, MAC `28:84:85:86:8c:94`, COM4 |
| Camera | OV2640 PID `0x26`; không chủ động hướng camera vào người dùng, môi trường tối |
| Firmware | Descriptor `2.2.1`, ESP-IDF `5.5.5`, capability `0x3f` |

Đây là smoke test của source sau release `v2.2.1`; version descriptor chưa được
bump nên các binary không được gọi là artifact release mới.

## Artifact đã dùng

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 32,261,562 | `E082AEEFE87649299058DB1523997A4C1032A0F7B7A6CB201A3A634E62377431` |
| `focusmate_esp.bin` | 3,178,320 | `BA26BF6F5F58741D916D702E6E4D2FDECF3B9B33043382E202007F5A98A1B4E9` |
| `mp_assets.bin` | 12,517,376 | `1167CD8B09684B09E4A9A7BFD165A3BD64850704A1B5D5E7D85E4D2320664412` |

Bootloader, partition table, app và assets được ghi bằng `idf.py flash`; NVS
không bị xóa. Esptool xác minh hash từng image trước hard reset.

## Kết quả

- APK cài bằng ADB thành công; các quyền notification, Bluetooth, activity
  recognition, body sensor và heart rate vẫn được cấp.
- ESP boot xác nhận PSRAM 8 MB, canonical self-test, detector geometry self-test,
  assets mount `11,396,655 / 11,498,561` byte, dashboard và GATT advertising.
- Camera startup smoke đạt 25 frame hợp lệ, 0 lỗi ở 7,58 FPS. Warm-up có
  `FB-OVF`/`NO-EOI`/`NO-SOI` nhưng smoke sau đó pass.
- Watch reconnect bonded GATT, encryption status 0, MTU 256 và START 50 dHz.
  Log đạt ít nhất 500 observation/notify attempt, 0 notify failure; màn hình tắt
  giảm còn 20 dHz và bật lại khôi phục 50 dHz.
- Phiên test khoảng hai phút kết thúc sạch; ESP nhận STOP, unsubscribe và remote
  disconnect rồi quay lại advertising.
- Bản ghi phiên mới chứa `advice_rule_version=session_advice_v1`,
  `ADVICE_MAINTAIN_GOOD_SESSION`, evidence rỗng, `break_reason_codes=[]` và
  `yawn_recent_window_count=0`. Các bản ghi cũ vẫn đọc được với advice rỗng.
- Báo cáo trên màn hình tròn 450×450 hiển thị đúng lời khen, lý do, summary đầu
  phiên; thao tác cuộn tới được dữ liệu phiên, độ đầy đủ, ghi chú điểm đầu phiên
  và nút `ĐÓNG`. Review chỉ xuất hiện sau khi đóng báo cáo.
- Không thấy crash `AndroidRuntime` trong cửa sổ test. Sau test không còn active
  session hoặc pending review.

## Giới hạn

- Camera không được chủ động bật/hướng vào người dùng và môi trường tối. Các log
  `FACE_MISSING`, `HEAD_DOWN`, `TOO_CLOSE` hoặc `NORMAL` trong phiên này không
  được dùng làm bằng chứng accuracy posture.
- Phiên ngắn chỉ kiểm nhánh fallback tích cực; chưa chờ ngưỡng 30/45/60 phút,
  chưa tạo immobility 30 phút và chưa kiểm advice posture/ngáp/heart-rate thật.
- Không chạy low-light matrix, thermal 30 phút, soak hai giờ hoặc benchmark pin.
- `VERIFIED_LOCAL` từ automated tests vẫn là bằng chứng chính cho toàn bộ ma trận
  rule và migration; smoke này chỉ bổ sung bằng chứng runtime cho một nhánh.
