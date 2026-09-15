# Third-party notices

FocusMate uses dependencies from their normal package managers; their source is
not copied into this repository. Exact resolved Android/JVM versions are in the
Gradle lockfiles and verification metadata. Exact ESP-IDF component versions
are in `firmware/dependencies.lock`.

| Runtime component | Pinned version | License | Upstream |
|---|---:|---|---|
| Android/JVM runtime dependency graph | exact versions in lockfiles | version-specific POM/model-card audit | `sbom/license-provenance.json`, current SPDX SBOM |
| MediaPipe Tasks Vision Android | 1.0.0 | Apache-2.0 | version-specific Google Maven POM in `sbom/license-provenance.json` |
| MediaPipe Tasks Vision Web | 1.0.1 | Apache-2.0 | `package.json` inside the hash-pinned npm tarball; source recorded in `sbom/license-provenance.json` |
| Guava Android | 31.1-android | Apache-2.0 | version-matched parent POM in `sbom/license-provenance.json` |
| Protobuf Java Lite | 4.26.1 | BSD-3-Clause | version-matched parent POM in `sbom/license-provenance.json` |
| Checker compat annotations | 2.5.3 | GPL-2.0 with Classpath exception OR MIT; FocusMate uses the MIT option | version-specific Maven POM; MIT text in `LICENSES/` |
| JSON-java | 20240303 | `LicenseRef-Public-Domain` | version-tagged upstream LICENSE; extracted text in current SPDX SBOM |
| ESP-IDF | 5.5.5 | Apache-2.0 | <https://github.com/espressif/esp-idf> |
| dl_fft | 0.6.0 | MIT | commit-pinned LICENSE in `sbom/license-provenance.json` |
| esp32-camera | 2.1.7 | Apache-2.0 | commit-pinned LICENSE in `sbom/license-provenance.json` |
| esp-dl | 3.3.9 | MIT | commit-pinned LICENSE in `sbom/license-provenance.json` |
| esp_jpeg | 1.3.1 | Apache-2.0 | commit-pinned license in `sbom/license-provenance.json` |
| esp_new_jpeg | 1.0.2 | `LicenseRef-Espressif-MIT` | upstream terms restrict use to Espressif products; not represented as standard MIT |
| human_face_detect | 0.5.0 | MIT | commit-pinned LICENSE in `sbom/license-provenance.json` |
| mdns | 1.9.1 | Apache-2.0 | commit-pinned LICENSE in `sbom/license-provenance.json` |
| Pose Landmarker Lite float16 | revision 1 | Apache-2.0 | official bundle table and linked BlazePose GHUM 3D model card in `sbom/license-provenance.json` |
| Face Landmarker float16 | revision 1 | Apache-2.0 | official bundle table and linked BlazeFace, Face Mesh V2 and Blendshape V2 cards in `sbom/license-provenance.json` |
| MediaPipe Tasks JNI native runtime (`libmediapipe_tasks_jni.so`) | from Android artifact 1.0.0 | Apache-2.0 declared by the version-specific POM | bundled unchanged; Android packaging could not strip debug symbols and retained the upstream binary |

Model and package hashes are enforced by `tools/bootstrap_assets.py`. Release
binaries contain runtime dependencies and models as required for offline local
inference; no separately vendored dependency source is included. The historical
`v2.2.2` SBOM is unchanged. The current runtime audit is generated separately,
does not infer licenses from package namespaces. The two exact MediaPipe model
files are mapped separately from the runtime through Google's official bundle
tables and linked model cards; revision 1 and `latest` were verified byte-identical
on 2026-09-16. The generated SPDX document includes every
component resolved by `firmware/dependencies.lock`; it records the non-standard
`esp_new_jpeg` terms as an extracted `LicenseRef` instead of relabeling them MIT.
This inventory is not a complete legal audit. Full Apache and MIT texts governing
repository material are in `LICENSES/`; custom license texts are extracted in the
current SPDX SBOM, and exact third-party source links are in the provenance file.
