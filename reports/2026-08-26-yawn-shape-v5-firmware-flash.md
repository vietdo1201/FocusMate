# Flash firmware Yawn Shape V5 — 2026-08-26

Trạng thái bằng chứng: `VERIFIED_DEVICE` chỉ cho build/flash/boot smoke;
`UNVERIFIED` cho accuracy ngáp/cười, độ chính xác overlay, thermal và soak.

## Phạm vi

| Trường | Giá trị |
|---|---|
| Source | Nội dung source của commit chứa report này; kế thừa bản vá `64fcc7a` |
| Board | ESP32-S3 N16R8 rev 0.2, MAC `28:84:85:86:8c:94` |
| Camera | OV2640 PID `0x26` |
| Cổng flash | `COM4`, USB Serial/JTAG `VID_303A:PID_1001` |
| ESP-IDF | `5.5.5` |
| Firmware descriptor | `2.2.1` |
| Phạm vi source | Web `YAWN_SHAPE_V5`, cache-busted worker, overlay Face Landmarker + ESP fallback |

Đây là source sau GitHub Release `v2.2.1`. Descriptor chưa được bump nên các
binary dưới đây không được gọi là release `v2.2.2` hoặc artifact `v2.2.1` đã
phát hành.

## Thay đổi V5

- `jawOpen = null` trên Web compact không bị đổi thành `0%`.
- MAR mở phải ≥0,32, peak ≥0,55 và kéo dài ít nhất 1,6 giây.
- Độ rộng miệng được chuẩn hóa theo khoảng cách hai mắt; giãn ngang trên 1,35×
  baseline được phân loại `smile_like` và không đếm ngáp.
- Baseline V4 tự hết hiệu lực do classifier version mới; người dùng phải giữ
  miệng khép tự nhiên để thu 20 mẫu/5 giây.
- Watch source dùng cùng MAR/shape/duration và thêm `jawOpen`; APK mới chưa được
  cài/retest trên Watch trong report firmware này.

## Artifact đã flash

| Artifact | Offset | Bytes | SHA-256 |
|---|---:|---:|---|
| `focusmate_esp.bin` | `0x10000` | 3,178,320 | `77D30FC4942AF7075F40299D6B4F6FB13663CF6168650C4F5539BFC0D0DEB219` |
| `mp_assets.bin` | `0x410000` | 12,517,376 | `1167CD8B09684B09E4A9A7BFD165A3BD64850704A1B5D5E7D85E4D2320664412` |

Bootloader và partition table cũng được ghi bởi lệnh chuẩn của ESP-IDF. NVS
không bị xóa.

## Command và kết quả

```powershell
node --test tests/*.test.mjs
python -m unittest discover -s tests -p "test_*.py"
python tools/check_compliance.py
python tools/verify.py --no-firmware --no-assets
idf.py -C firmware build
idf.py -C firmware -p COM4 flash
idf.py -C firmware -p COM4 monitor
```

- Node tests: 19/19 pass, gồm ngáp MAR-only, mở ngắn/nông, cười vừa/cười rộng
  và scale-invariance khi đổi khoảng cách camera.
- Kotlin Watch regression tests pass cho cười rộng, cười vừa và ngáp dọc 1,6 giây.
- Full Watch verification pass 108 tác vụ: unit/Robolectric, lint, debug và
  release assemble (`BUILD SUCCESSFUL`).
- Python contracts: 15/15 pass; compliance/SPDX/release identity/secret scan pass.
- ESP-IDF build: app còn `0xF80B0` byte, khoảng 24% partition.
- Esptool ghi bootloader/app/partition/assets và báo `Hash of data verified` cho
  mọi image trước khi hard reset.
- Boot xác nhận PSRAM 8 MB, camera OV2640, C golden self-test, detector integer
  self-test và initial inference.
- Camera smoke sau warm-up: 25 frame hợp lệ, 0 lỗi, 8,44 FPS.
- MediaPipe assets mount: `11,396,655 / 11,498,561` byte.
- Dashboard sẵn sàng tại `http://focusmate.local` và `http://192.168.4.1`.
- BLE GATT advertising sẵn sàng với capability `0x3f`.
- Detector đạt ít nhất 75 inference, 0 failure trong cửa sổ monitor sau boot.

Trong warm-up camera có log `FB-OVF`, `NO-EOI` và `NO-SOI`; smoke sau đó đạt
25/25. Report này không kết luận các cảnh báo chỉ xảy ra lúc khởi động—soak vẫn
bắt buộc.

## Điều được phép kết luận

- Binary và asset chứa V5 đã được ghi byte-exact lên đúng ESP32-S3 và boot.
- Camera, detector, asset partition, dashboard server và BLE GATT đều đi qua
  smoke startup của build này.
- Automated tests chứng minh classifier loại hai mẫu cười synthetic đã khai
  báo và vẫn đếm mẫu ngáp dọc MAR 1,248; đây chưa phải accuracy test trên người.

## Chưa được phép kết luận

- Chưa chạy ma trận ngáp thật/cười/nói/uống nước trên dashboard sau flash.
- Chưa đo độ khớp khung Face Landmarker trên nhiều góc mặt/ánh sáng.
- Chưa retest Watch với source này, network capture, low-light, thermal 30 phút,
  soak hai giờ hoặc benchmark pin có đối chứng.
- Không nâng posture/yawn/frame transport tổng thể lên `VERIFIED_DEVICE` từ
  smoke này.
