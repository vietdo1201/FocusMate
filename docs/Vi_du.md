# Ví dụ dữ liệu v2

Trạng thái: minh họa synthetic, không phải golden vector hay bằng chứng phần cứng.

```json
{
  "schema_version": "focusmate_face_observation_v1",
  "sequence": 42,
  "esp_uptime_ms": 12345,
  "face_detected": true,
  "cx": 0.50,
  "cy": 0.40,
  "width": 0.20,
  "height": 0.30,
  "area": 0.06,
  "confidence": 0.91,
  "quality_flags": ["stable", "well_lit"]
}
```

Watch thu 20 bbox ổn định để lấy median calibration. Chuỗi synthetic sau đó tạo `HEAD_DOWN` liên tục 180 giây và ghi `INSIGHT_V2_POSTURE_CONTINUOUS`; không hiện notification khi đang học. Khi bắt đầu nghỉ, Watch có thể hiện một lời khuyên chỉnh tư thế. Cuối phiên report ghi episode count và total duration.

Ví dụ rule: tại 45:00, fatigue 6 tạo một break suggestion với `RULE_V1_DURATION_FATIGUE`. Nếu đang trong cooldown, reason vẫn được ghi và decision thêm `SUPPRESSED_COOLDOWN` nhưng không prompt.

Yawn/PFLD: `deferred/unavailable`.
