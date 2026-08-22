# Third-party notices

FocusMate firmware uses the following pinned Espressif components:

| Component | Version | License | Upstream |
|---|---:|---|---|
| `espressif/esp32-camera` | 2.1.7 | Apache-2.0 | <https://github.com/espressif/esp32-camera> |
| `espressif/human_face_detect` | 0.5.0 | MIT | <https://github.com/espressif/esp-dl/tree/master/models/human_face_detect> |
| `espressif/esp-dl` | 3.3.9 | MIT | <https://github.com/espressif/esp-dl> |
| `@mediapipe/tasks-vision` (Web) | 1.0.1 | Apache-2.0 | <https://www.npmjs.com/package/@mediapipe/tasks-vision/v/1.0.1> |
| MediaPipe Pose Landmarker Lite float16 | revision 1 | Apache-2.0 | <https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task> |

The downloaded MIT license file for `human_face_detect` and ESP-DL had SHA-256
`3513F1605EBCC227F7364956F6C30A303412597E232648CB3090E76697CA5AA3`.
The Apache-2.0 and MIT copyright/license terms remain authoritative in their
respective upstream components. No third-party model is redistributed as a
standalone repository artifact; ESP-IDF's component manager obtains it during
the pinned build.

The Web Tasks package tarball is pinned to SHA-256
`EE318EAA3D42230AA10910D114FAF2A488C577C4E4D33C7CB04126924ACA505F`.
The Pose Landmarker Lite task bundle is 5,777,746 bytes and is pinned to
SHA-256 `59929E1D1EE95287735DDD833B19CF4AC46D29BC7AFDDBBF6753C459690D574A`.
The verified asset preparation script packages these artifacts for offline,
local-only inference; it does not fetch them at runtime.
