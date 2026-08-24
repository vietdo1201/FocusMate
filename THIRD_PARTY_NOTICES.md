# Third-party notices

FocusMate uses dependencies from their normal package managers; their source is
not copied into this repository. Exact resolved Android/JVM versions are in the
Gradle lockfiles and verification metadata. Exact ESP-IDF component versions
are in `firmware/main/idf_component.yml`.

| Runtime component | Pinned version | License | Upstream |
|---|---:|---|---|
| AndroidX libraries | lockfile | Apache-2.0 | <https://github.com/androidx/androidx> |
| MediaPipe Tasks Vision Android | 1.0.0 | Apache-2.0 | <https://github.com/google-ai-edge/mediapipe> |
| MediaPipe Tasks Vision Web | 1.0.1 | Apache-2.0 | <https://www.npmjs.com/package/@mediapipe/tasks-vision/v/1.0.1> |
| Guava Android | 31.1-android | Apache-2.0 | <https://github.com/google/guava> |
| ESP-IDF | 5.5.5 | Apache-2.0 | <https://github.com/espressif/esp-idf> |
| esp32-camera | 2.1.7 | Apache-2.0 | <https://github.com/espressif/esp32-camera> |
| esp-dl | 3.3.9 | MIT | <https://github.com/espressif/esp-dl> |
| human_face_detect | 0.5.0 | MIT | <https://components.espressif.com/components/espressif/human_face_detect> |
| mdns | 1.9.1 | Apache-2.0 | <https://components.espressif.com/components/espressif/mdns> |
| Pose Landmarker Lite float16 | revision 1 | Apache-2.0 | <https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker> |
| Face Landmarker float16 | revision 1 | Apache-2.0 | <https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker> |

Model and package hashes are enforced by `tools/bootstrap_assets.py`. Release
binaries contain runtime dependencies and models as required for offline local
inference; no separately vendored dependency source is included. Full Apache
and MIT license texts are in `LICENSES/`.
