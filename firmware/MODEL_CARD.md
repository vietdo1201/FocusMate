# FocusMate face detector model card

## Identity and provenance

- Intended target: ESP32-S3 N16R8 with OV2640, FocusMate posture assistance.
- Producer: Espressif Systems.
- Component: `espressif/human_face_detect` `0.5.0`.
- Source revision declared by the component: `f43b41fd533da882382f9cd3ef305c829c138189`.
- Runtime: `espressif/esp-dl` `3.3.9` on ESP-IDF `5.5.5`.
- Architecture selected: two-stage `MSRMNP_S8_V1`.
- License: MIT for `human_face_detect`, its two model files, and ESP-DL. See
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Pinned S3 model files:

| File | Bytes | SHA-256 |
|---|---:|---|
| `human_face_detect_msr_s8_v1.espdl` | 61,168 | `AB705D4B831EEAE9FF21FABFE4471AFFAE1006A4A2C273DE022BAC26DB4DF973` |
| `human_face_detect_mnp_s8_v1.espdl` | 129,968 | `E981FE2107281F25E8C54F5F091C1037C8343A9E23F4C51FCC22BD37728C0157` |

The build tool packages these two files into a 191,296-byte PDL3 blob. The
observed package SHA-256 is
`6AD9B124F61DF63B2A7A1368310853899EC4DF8ED89AC355A7F34159ABFF65F0`.

## Input, processing and output

- Camera input: RGB565 big-endian, 240×240, one framebuffer in PSRAM.
- Vendor model inputs: MSR 120×160×3 followed by MNP 48×48×3.
- Default score/NMS thresholds: 0.5/0.5 for both stages.
- If several faces pass the threshold, firmware selects highest score, then
  larger area, then lexicographically smaller pixel box for deterministic ties.
- Pixel boxes are clipped to the image and treated as inclusive. Width/height
  therefore use `right-left+1`; center and size are converted by integer
  half-up division to scaled `1e6` values before canonical JSON encoding.
- Output over BLE: face/no-face, normalized bbox, confidence, sequence and
  monotonic ESP uptime. No frame, crop, landmark, embedding or identifier is
  stored or transmitted.

## Measured device behavior

On the recorded ESP32-S3 rev 0.2 at 240 MHz, the model loaded from flash rodata
and completed its first real inference in 47 ms. A roughly 8 minute 40 second
observation window completed 3,900 inferences with zero inference failures and
47.0–47.1 ms average model-path latency. Camera cadence remained about 7.45
FPS. Later device runs confirmed the positive bbox path at roughly 55–57 ms
with confidence up to about 0.99. See
[`../reports/2026-08-22-gate-c-face-detector.md`](../reports/2026-08-22-gate-c-face-detector.md).

## Quality and limitations

- Espressif reports mAP50-95 `0.367` for the combined MSR+MNP model on its
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
