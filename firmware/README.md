# FocusMate ESP32-S3 firmware

ESP-IDF 5.5.5 project for the ESP32-S3 N16R8. The first hardware gate is a
privacy-safe camera-to-GATT path: it advertises the normative service, exposes
the versioned Device Info structure, accepts control commands, and emits
canonical face/landmark observations at an adaptive 5/2/1 Hz. An opt-in,
authenticated local HTTP endpoint can stream a temporary JPEG to one Watch or
browser consumer; frames never leave the LAN and are not persisted.

Camera and detector dependencies are pinned to Espressif `esp32-camera` 2.1.7
(Apache-2.0), `human_face_detect` 0.5.0 (MIT) and `esp-dl` 3.3.9 (MIT).
Model provenance, hashes, limitations and acceptance scope are recorded in
[`MODEL_CARD.md`](MODEL_CARD.md).

The camera smoke test has a two-part Kconfig interlock. Both gates are enabled
for the recorded 18-pin OV2640 wiring after owner confirmation and comparison
with the previously working Arduino sketch (hash recorded in
`data/So_do_chan.md`). The module supplies its own oscillator (`pin_xclk=-1`,
declared 24 MHz). Smoke frames are counted and immediately returned; no image
bytes are stored or transmitted. A successful build is not device evidence:
the camera capability is set only after 24/25 valid RGB565 240x240 frames.

The detector uses the lightweight MSR+MNP S8 pair and receives the camera's
RGB565 big-endian framebuffer directly. Detector capability is set only after
the model loads and one real inference completes. The latest result is copied
to the BLE task as integer micro-units; an inference older than 1 second is not
transmitted. The 4 MiB factory partition preserves the standard NVS address at
`0x9000`, including stored BLE bond keys.

Prepare pinned assets, then build from an exported ESP-IDF 5.5.5 shell:

```text
python ../tools/bootstrap_assets.py
idf.py set-target esp32s3
idf.py build
idf.py -p COM4 flash monitor
```
