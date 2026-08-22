# Cross-device test index

Thư mục dành cho golden vectors và replay data synthetic dùng chung giữa các runtime.

- `golden/face_observation_v1.json`: canonical JSON/CRC của `FaceObservationV1`.
- `golden/frame_access_info_v1.json`: struct GATT 40 byte của `FrameAccessInfoV1`, gồm cả thứ tự byte IPv4 và port little-endian.
- `golden/face_meta_v1.json`: sidecar HTTP public 32 byte Q16/base64url; không phải struct broker nội bộ 84 byte.
- `golden/posture_geometry_v2.tsv`: fixture geometry synthetic; không phải bằng chứng thiết bị.

Codec/unit test hiện nằm trong module `protocol`; fixture và test local không tự nâng BLE, firmware hoặc posture lên `VERIFIED_DEVICE`.
