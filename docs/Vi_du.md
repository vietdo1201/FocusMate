# Ví dụ dữ liệu v2

Trạng thái: minh họa synthetic, không phải golden vector hay bằng chứng phần cứng. Golden vector byte-exact nằm ở `tests/golden/`; luật canonical nằm ở [GATT_PROFILE.md](GATT_PROFILE.md).

Payload thật trên wire không có whitespace và mọi số thực có đúng 6 chữ số thập phân:

```text
{"schema_version":"focusmate_face_observation_v1","sequence":42,"esp_uptime_ms":12345,"face_detected":true,"cx":0.500000,"cy":0.400000,"width":0.200000,"height":0.300000,"area":0.060000,"confidence":0.910000,"quality_flags":["stable","well_lit"]}
```

Dạng xuống dòng dưới đây chỉ để đọc; encoder canonical **không** phát ra nó, và `decode()` reject nó:

```json
{
  "schema_version": "focusmate_face_observation_v1",
  "sequence": 42,
  "esp_uptime_ms": 12345,
  "face_detected": true,
  "cx": 0.500000,
  "cy": 0.400000,
  "width": 0.200000,
  "height": 0.300000,
  "area": 0.060000,
  "confidence": 0.910000,
  "quality_flags": ["stable", "well_lit"]
}
```

Khi không thấy mặt, sáu trường bbox/confidence vắng hoàn toàn:

```text
{"schema_version":"focusmate_face_observation_v1","sequence":43,"esp_uptime_ms":12545,"face_detected":false,"quality_flags":["low_light"]}
```

Watch thu 20 bbox ổn định để lấy median calibration. Chuỗi synthetic sau đó tạo `HEAD_DOWN` liên tục 180 giây và ghi `INSIGHT_V2_POSTURE_CONTINUOUS`; không hiện notification khi đang học. Khi bắt đầu nghỉ, Watch có thể hiện một lời khuyên chỉnh tư thế. Cuối phiên report ghi episode count và total duration.

Ví dụ rule: tại 45:00, fatigue 6 tạo một break suggestion với `RULE_V1_DURATION_FATIGUE`. Nếu đang trong cooldown, reason vẫn được ghi và decision thêm `SUPPRESSED_COOLDOWN` nhưng không prompt.

Yawn advisory: ba chu kỳ ngáp hợp lệ trong cửa sổ 10 phút tạo một rung ngắn và
banner nếu màn hình Watch đang bật. Sự kiện được ghi vào báo cáo nhưng không tạo break suggestion.
