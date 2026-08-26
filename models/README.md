# Model index

Repository không commit model binary trực tiếp. Build lấy các model đã khóa
revision/hash từ nguồn upstream và từ chối artifact sai SHA-256:

- Espressif MSR/MNP S8 chạy trên ESP32-S3; provenance, license, hash và benchmark
  nằm trong [`../firmware/MODEL_CARD.md`](../firmware/MODEL_CARD.md).
- MediaPipe Pose Landmarker Lite và Face Landmarker chạy local trên Web/Watch;
  bootstrap và manifest nằm trong `tools/bootstrap_assets.py`.

Model posture/yawn vẫn experimental; model có mặt và build đúng hash không tự
chứng minh accuracy, thermal hoặc `VERIFIED_DEVICE`.
