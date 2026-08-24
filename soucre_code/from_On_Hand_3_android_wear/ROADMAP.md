# Roadmap ESP32-S3 + Wear OS

Status canonical: [`../../docs/STATUS.md`](../../docs/STATUS.md).

## Thiết bị của hồ sơ

| Vai trò | Thiết bị | Ghi chú |
|---|---|---|
| Watch | Galaxy Watch 5 Pro (Wear OS, API ≥ 33) | Thiết bị kiểm chứng của Giai đoạn 3; `VERIFIED_DEVICE` chỉ được cấp cho những gì đã chạy trên máy này |
| Detector | ESP32-S3 N16R8 + OV2640 | Sơ đồ chân còn `DRAFT/UNVERIFIED` trong [`../../data/So_do_chan.md`](../../data/So_do_chan.md) |

Galaxy Watch FE vẫn nằm trong dải tương thích của `minSdk 30` nhưng **không** phải thiết bị kiểm chứng của đợt này và không có bằng chứng thiết bị. Report thiết bị phải ghi model + phiên bản OS thực tế, không suy diễn sang model khác.

## Giai đoạn 0 — Watch rules/protocol local

Đầu ra: app standalone một variant, fatigue `1..10`, Rule Engine v2, `FaceObservationV1`, geometry classifier, posture insight/report policy và clean verification. Đây là bằng chứng local, chưa phải hardware readiness.

## Giai đoạn 1 — BLE simulator và GATT contract

- Chốt UUID, characteristic, reconnect/time/sequence semantics và golden vectors.
- ESP peripheral/server; Watch central/client.
- Watch bỏ qua payload malformed/replay; mất BLE không chặn v1.

Gate: integration test với simulator. BLE vẫn không được gọi verified device.

## Giai đoạn 2 — Firmware detector

- Tạo ESP-IDF project cho ESP32-S3 N16R8 + OV2640.
- Chạy face detector nhẹ và phát bbox/quality; không phát ảnh/crop/landmark.
- Ghi model card/license/hash và benchmark Flash/PSRAM/latency.

Gate: build firmware và smoke test camera/detector trên board thật.

## Giai đoạn 3 — Device integration

- Watch calibration median, geometry/temporal classification từ bbox thật.
- Kiểm tra reconnect, freshness, missing capability và battery.
- Xác nhận Rule Engine/notification trên Galaxy Watch 5 Pro (Wear OS, API ≥ 33).

Gate: report `VERIFIED_DEVICE` có app/firmware build, hardware revision của ESP32-S3, model Watch và phiên bản OS của Watch.

## Giai đoạn 4 — Alpha

- Privacy/delete/export review, accessibility, long-session soak và failure recovery.
- Yawn advisory Face Landmarker local đã chuyển sang `IMPLEMENTED / EXPERIMENTAL / UNVERIFIED`; không thuộc Rule Engine và không phải suy luận cảm xúc.
- Model posture nếu nghiên cứu chỉ chạy bbox feature ở shadow mode, không quyết định break.

### Nhánh nghiên cứu frame-on-Watch

Sau khi đường bbox ổn định, có thể thử frame giảm độ phân giải/nén từ ESP sang Watch bằng protocol riêng. Gate gồm consent/capability negotiation, không persistence, benchmark băng thông/pin/nhiệt và model shadow trước khi ảnh hưởng posture state.
