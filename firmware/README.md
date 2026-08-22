# FocusMate ESP32-S3 firmware

ESP-IDF 5.5.5 project for the ESP32-S3 N16R8. The first hardware gate is a
privacy-safe GATT vertical slice: it advertises the normative service, exposes
the exact 34-byte Device Info structure, accepts control commands, and emits
canonical no-face observations at 5 Hz. No image bytes leave the ESP.

The camera smoke test must remain a separate evidence step until the connected
OV2640 module/PCB revision and its XCLK source are recorded. Do not infer an
XCLK wiring choice from the sensor name alone.

Build and flash from an exported ESP-IDF 5.5.5 shell:

```text
idf.py set-target esp32s3
idf.py build
idf.py -p COM4 flash monitor
```
