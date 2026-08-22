# Gate B — BLE vertical slice

- Ngày: 2026-08-22 (Asia/Saigon)
- Kết luận: `IN_PROGRESS / VERIFIED_LOCAL`
- Không phải `VERIFIED_DEVICE`: firmware hiện chỉ là transport stub no-face; camera/detector, hiệu chỉnh bbox thật, low-light/doze và phiên 2–3 giờ chưa đạt.

## Builds và thiết bị

| Hạng mục | Giá trị |
|---|---|
| Watch | Samsung Galaxy Watch 5 Pro SM-R925F, Android 16 / API 36 |
| ESP | ESP32-S3 revision 0.2, flash 16 MB, octal PSRAM 8 MB |
| App | `versionCode 14`, `1.13-watch-rules-v2`; debug APK 12,360,507 byte; SHA-256 `BB5588F2916B4D474D1C754DE352B82B2CCE9B527F24AEE58F7210BBFEEB301E` |
| Firmware | `0.1.0-gatt-stub`, ESP-IDF 5.5.5; `focusmate_esp.bin` 521,776 byte; SHA-256 `69EC82A665284C955BD31819ADF0831FFAB01F8B14B9CDED33DFF1E7159B68A3` |
| Protocol | protocol `1`, framing `1`, capability `0x1c` (cố ý không claim camera/detector) |
| Source commits | Wear/app `a962aa0`; firmware `48f63f7` |

Không ghi ADB serial, BLE address, ảnh khuôn mặt hay identifier trong report.

## Kết quả

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| Canonical C golden self-test | PASS | Boot log: `C canonical golden self-test passed` |
| Clean local gate | PASS | `verify.ps1`: 103 tác vụ, `BUILD SUCCESSFUL in 3m 49s` |
| APK install và foreground service | PASS | Cài qua ADB; service chạy `health | connectedDevice` |
| Link security | PASS | ESP log `encryption change ... status=0` trước khi stream |
| Device Info 34 byte | PASS | Watch parse protocol/framing `1/1`, capability `0x1c`; giới hạn flag `4/16` |
| Bond + reconnect | PASS | Sau reset ESP: Android báo disconnect status `8`, dùng bonded device và kết nối lại khoảng 1,6 giây sau; không có status `5/133` |
| MTU | PASS | Subscription khôi phục ở MTU 23; Watch đàm phán MTU 256 trước stream ổn định |
| Notification rate | PASS | ESP: 150 observation / 150 notify attempt / 0 failure; Watch UI: `MTU 256 • 5.0 Hz` |
| Framing | PASS cục bộ + đã đi qua MTU 23 ở lần reboot trước | Unit tests MTU 23/517, CRC, reorder/drop/duplicate/supersede/timeout; hardware subscription ở MTU 23. Detected payload 246 byte chưa được phát bởi detector thật |
| Safety capability | PASS | UI giữ `KHÔNG TƯƠNG THÍCH • Transport OK; camera/detector chưa sẵn sàng`; không bịa `LIVE` |
| Camera/detector | CHƯA CHẠY | Capability bit 0/1 vẫn tắt |
| Phiên 2–3 giờ, doze, low-light | CHƯA CHẠY | Thuộc Gate C/D |

Ảnh/UI dump đã tuyển chọn: [PNG](../artifacts/focusmate-watch-encrypted-5hz-status.png), [XML](../artifacts/focusmate-watch-encrypted-5hz-status.xml).

## Commands tái lập

```powershell
$env:JAVA_HOME='C:\Users\vietdo1201\Java\jdk-17.0.20+8'
.\verify.ps1

. 'C:\Users\vietdo1201\esp\esp-idf-v5.5.5\export.ps1'
idf.py build
idf.py -p COM4 flash
idf.py -p COM4 monitor

adb install -r soucre_code/from_On_Hand_3_android_wear/app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s FocusMateBLE:I '*:S'
```

## Gate còn thiếu

- Xác minh chính xác OV2640 dùng oscillator riêng hay cần XCLK, pinout, 3,3 V/GND và revision PCB trước khi cấp nguồn camera.
- Camera smoke test trong firmware hiện tại, detector/model card/license/hash và benchmark nguồn/nhiệt/FPS/latency.
- Bbox thật cho từng tư thế, chốt threshold từ số đo; test reconnect/reboot/stale/low-light/doze trong phiên 2–3 giờ.
