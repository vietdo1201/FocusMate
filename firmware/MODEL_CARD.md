# FocusMate face detector model card

## Identity and provenance

- Intended target: ESP32-S3 N16R8 with OV2640, FocusMate posture assistance.
- Producer: Espressif Systems.
- Component: `espressif/human_face_detect` `0.5.0`.
- Source revision declared by the component: `f43b41fd533da882382f9cd3ef305c829c138189`.
- Runtime: `espressif/esp-dl` `3.3.9` on ESP-IDF `5.5.5`.
- Architecture selected: two-stage `MSRMNP_S8_V1` (MSR proposal network +
  MNP landmark/refinement network).
- License: MIT for `human_face_detect`, the selected model file, and ESP-DL. See
  [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

Pinned S3 model files:

| File | Bytes | SHA-256 |
|---|---:|---|
| `human_face_detect_msr_s8_v1.espdl` | 61,168 | `AB705D4B831EEAE9FF21FABFE4471AFFAE1006A4A2C273DE022BAC26DB4DF973` |
| `human_face_detect_mnp_s8_v1.espdl` | 129,968 | `E981FE2107281F25E8C54F5F091C1037C8343A9E23F4C51FCC22BD37728C0157` |

The build tool packages both files into a 191,296-byte PDL3 blob. The observed
package SHA-256 is `3195826BCCB7AF3A8262B3DE188D2120A4C858BEA96B9A6708B0F55BAA97BFC5`.

## Input, processing and output

- Camera input: direct JPEG QVGA 320×240, quality 6, one framebuffer in PSRAM.
- Firmware decodes RGB888 at 320×240 and center-crops 240×240 without aspect
  distortion before the vendor model preprocessing.
- Firmware score threshold: 0.35. Calibration requires confidence ≥0.70 and a fully visible bbox; live posture after a valid baseline requires confidence ≥0.50.
- If several faces pass the threshold, firmware selects highest score, then
  larger area, then lexicographically smaller pixel box for deterministic ties.
- Pixel boxes are clipped to the image and treated as inclusive. Width/height
  therefore use `right-left+1`; center and size are converted by integer
  half-up division to scaled `1e6` values before canonical JSON encoding.
- Output over BLE remains face/no-face, normalized bbox, confidence, sequence
  and monotonic ESP uptime. The authenticated local-frame HTTP path can attach
  bbox and five normalized face points in `FaceMetaV1` to the matching JPEG.
  Frames and landmarks are not persisted, uploaded, embedded or used as an
  identifier.

## Measured device behavior

The current MSR/MNP detector, five-point metadata and dual-client broker have
device evidence on ESP32-S3 rev 0.2: Web inference 38–49 ms in the recorded pass,
BLE 5 Hz and zero notification failures. This supports `VERIFIED_DEVICE` for the
recorded smoke/vertical slice only. Low-light precision, false-positive rate,
thermal behavior and long-run stability remain unverified and are not inferred
from build or CI success.

## Quality and limitations

- The five MSR/MNP points describe face geometry only. They do not provide
  shoulders, hips or a reliable full-body posture result; MediaPipe Pose on a
  local consumer is the primary posture source.
- The score threshold is an engineering default, not a calibrated accuracy
  claim. No demographic or low-light evaluation has been completed.
- Camera placement that excludes the head or both shoulders must fail closed
  to `UNKNOWN`; the system must not infer a posture from face bbox alone.
- Do not use the detector for identity, surveillance, diagnosis, safety
  decisions or the Rule Engine's break decision. Posture remains advisory.

## Local yawn consumer

- Web and Watch additionally run the pinned MediaPipe Face Landmarker float16
  revision 1 locally. The compact Web task supplies lip landmarks only; Watch
  also enables the `jawOpen` blendshape. No face image, landmark list or
  embedding is persisted.
- Yawn classifier V5 uses MAR plus mouth-width/eye-width expansion on Web and
  Watch to reject wide smiles; Watch additionally requires `jawOpen`. The
  conservative gate requires MAR ≥0.32, peak ≥0.55, at least 1.6 seconds open and horizontal
  expansion ≤1.35× the personal baseline. Three events in ten minutes create an advisory
  sleepiness/fatigue signal; they do not diagnose boredom or alter Rule Engine
  decisions.
- This path is `EXPERIMENTAL` and `UNVERIFIED` on device until camera angle,
  speech false positives, thermal behavior and real yaw events are evaluated.
