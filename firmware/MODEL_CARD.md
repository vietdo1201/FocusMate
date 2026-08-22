# FocusMate face detector model card

## Identity and provenance

- Intended target: ESP32-S3 N16R8 with OV2640, FocusMate posture assistance.
- Producer: Espressif Systems.
- Component: `espressif/human_face_detect` `0.5.0`.
- Source revision declared by the component: `f43b41fd533da882382f9cd3ef305c829c138189`.
- Runtime: `espressif/esp-dl` `3.3.9` on ESP-IDF `5.5.5`.
- Architecture selected: one-stage `ESPDET_PICO_224_224_FACE`.
- License: MIT for `human_face_detect`, the selected model file, and ESP-DL. See
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Pinned S3 model files:

| File | Bytes | SHA-256 |
|---|---:|---|
| `espdet_pico_224_224_face.espdl` | 480,384 | `C9A991E00AECA4009EB2771E3FCCF7A7FF8A47781A90DC66701692FCDF1F1B5E` |

The build tool packages this file into a 480,496-byte PDL3 blob. The observed
package SHA-256 is `BB8AC5DF0BCC00F00D84CDCA02BFBA8686CA7B938B3600CB23D8DD6F598D1B4`.

## Input, processing and output

- Camera input: direct JPEG QVGA 320×240, quality 8, one framebuffer in PSRAM.
- Firmware decodes RGB888 at 320×240, center-crops 240×240 without aspect distortion, then ESP-DL preprocesses for the vendor model input 224×224×3.
- Firmware score threshold: 0.35. Calibration requires confidence ≥0.70 and a fully visible bbox; live posture after a valid baseline requires confidence ≥0.50.
- If several faces pass the threshold, firmware selects highest score, then
  larger area, then lexicographically smaller pixel box for deterministic ties.
- Pixel boxes are clipped to the image and treated as inclusive. Width/height
  therefore use `right-left+1`; center and size are converted by integer
  half-up division to scaled `1e6` values before canonical JSON encoding.
- Output over BLE: face/no-face, normalized bbox, confidence, sequence and
  monotonic ESP uptime. No frame, crop, landmark, embedding or identifier is
  stored or transmitted.

## Measured device behavior

On the recorded ESP32-S3 rev 0.2 at 240 MHz, the current ESPDet build loaded
from flash rodata and produced real positive bboxes with confidence observed up
to about 0.90. End-to-end detector latency, including JPEG decode and square
crop, measured about 294–304 ms (roughly 2.6–3.0 FPS). A no-person interval
completed more than 50 consecutive inferences without a false positive. BLE
continued at 5 Hz, MTU 256, with zero notification failures during the short
concurrent run. This supersedes the older MSR+MNP benchmark recorded in
[`../reports/2026-08-22-gate-c-face-detector.md`](../reports/2026-08-22-gate-c-face-detector.md); long-run evidence is still required.

## Quality and limitations

- Espressif reports mAP50-95 `0.504` for ESPDet Pico 224 on its
  custom validation set. That number is vendor context, not FocusMate device
  acceptance and not a guarantee for this camera placement.
- Device runs verified model load, repeated no-face inference, positive bbox
  and transport. They did not obtain a stable per-posture baseline, low-light
  dataset, demographic evaluation, or calibrated posture threshold.
- Transient camera DMA overflow diagnostics were observed during the short
  engineering run without a failed inference; long-session stability remains
  an explicit acceptance gate.
- Do not use the detector for identity, surveillance, diagnosis, safety
  decisions or the Rule Engine's break decision. Posture remains advisory.
