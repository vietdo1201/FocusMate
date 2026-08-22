# Third-party notices

FocusMate firmware uses the following pinned Espressif components:

| Component | Version | License | Upstream |
|---|---:|---|---|
| `espressif/esp32-camera` | 2.1.7 | Apache-2.0 | <https://github.com/espressif/esp32-camera> |
| `espressif/human_face_detect` | 0.5.0 | MIT | <https://github.com/espressif/esp-dl/tree/master/models/human_face_detect> |
| `espressif/esp-dl` | 3.3.9 | MIT | <https://github.com/espressif/esp-dl> |

The downloaded MIT license file for `human_face_detect` and ESP-DL had SHA-256
`3513F1605EBCC227F7364956F6C30A303412597E232648CB3090E76697CA5AA3`.
The Apache-2.0 and MIT copyright/license terms remain authoritative in their
respective upstream components. No third-party model is redistributed as a
standalone repository artifact; ESP-IDF's component manager obtains it during
the pinned build.
