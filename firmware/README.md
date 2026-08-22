# FocusMate ESP32-S3 firmware

ESP-IDF 5.5.5 project for the ESP32-S3 N16R8. The first hardware gate is a
privacy-safe GATT vertical slice: it advertises the normative service, exposes
the exact 34-byte Device Info structure, accepts control commands, and emits
canonical no-face observations at 5 Hz. No image bytes leave the ESP.

The camera transport dependency is pinned to Espressif `esp32-camera` 2.1.7
(Apache-2.0); detector dependencies are intentionally not present until the
camera smoke gate has passed on the recorded hardware revision.

The camera smoke test is compiled but disabled by default. It has a two-part
Kconfig interlock: both `FOCUSMATE_CAMERA_ENABLE` and
`FOCUSMATE_CAMERA_PINOUT_CONFIRMED` must be enabled after the connected OV2640
module/PCB revision and its XCLK source are physically recorded. Do not infer an
XCLK wiring choice from the sensor name alone. Smoke frames are counted and
immediately returned; no image bytes are stored or transmitted.

Build and flash from an exported ESP-IDF 5.5.5 shell:

```text
idf.py set-target esp32s3
idf.py build
idf.py -p COM4 flash monitor
```
