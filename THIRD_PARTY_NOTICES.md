# Third-party notices

FocusMate uses dependencies from their normal package managers; their source is
not copied into this repository. Exact resolved Android/JVM versions are in the
Gradle lockfiles and verification metadata. Exact ESP-IDF component versions
are in `firmware/main/idf_component.yml`.

| Runtime component | Pinned version | License | Upstream |
|---|---:|---|---|
| Direct AndroidX runtime dependencies | exact versions in lockfile | Apache-2.0 where version-specific POM is recorded; other transitives `NOASSERTION` | `sbom/license-provenance.json` |
| MediaPipe Tasks Vision Android | 1.0.0 | Apache-2.0 | version-specific Google Maven POM in `sbom/license-provenance.json` |
| MediaPipe Tasks Vision Web | 1.0.1 | Apache-2.0 | `package.json` inside the hash-pinned npm tarball; source recorded in `sbom/license-provenance.json` |
| Guava Android | 31.1-android | Apache-2.0 | version-matched parent POM in `sbom/license-provenance.json` |
| ESP-IDF | 5.5.5 | Apache-2.0 | <https://github.com/espressif/esp-idf> |
| esp32-camera | 2.1.7 | Apache-2.0 | commit-pinned LICENSE in `sbom/license-provenance.json` |
| esp-dl | 3.3.9 | MIT | commit-pinned LICENSE in `sbom/license-provenance.json` |
| human_face_detect | 0.5.0 | MIT | commit-pinned LICENSE in `sbom/license-provenance.json` |
| mdns | 1.9.1 | Apache-2.0 | commit-pinned LICENSE in `sbom/license-provenance.json` |
| Pose Landmarker Lite float16 | revision 1 | `NOASSERTION` in current audit | <https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker> |
| Face Landmarker float16 | revision 1 | `NOASSERTION` in current audit | <https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker> |

Model and package hashes are enforced by `tools/bootstrap_assets.py`. Release
binaries contain runtime dependencies and models as required for offline local
inference; no separately vendored dependency source is included. The historical
`v2.2.2` SBOM is unchanged. The current runtime audit is generated separately,
does not infer licenses from package namespaces, and intentionally leaves
unverified entries as `NOASSERTION`. This inventory is not a complete legal
audit; the transitive firmware components still require the same version-specific
review. Full Apache and MIT license texts are in `LICENSES/`.
