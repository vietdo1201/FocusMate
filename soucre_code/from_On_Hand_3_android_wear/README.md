# FocusMate Wear OS

Module này là app Wear OS standalone và module contract ESP–Watch.

- `app`: session, fatigue/focus input, motion/HR, Rule Engine v2, reminder, posture classifier/insight/report.
- `protocol`: `FaceObservationV1` bbox-only codec và sequence gate.

BLE runtime và firmware chưa triển khai. Geometry thresholds là experimental và mới được test bằng bbox synthetic.

Build:

```powershell
.\gradlew.bat --no-daemon :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

APK: `app/build/outputs/apk/debug/app-debug.apk` và `app/build/outputs/apk/release/app-release-unsigned.apk`.

Xem [status](../../docs/STATUS.md), [roadmap](ROADMAP.md), [implementation plan](IMPLEMENTATION_PLAN.md) và [ADR 0002](../../docs/decisions/0002-watch-rule-engine-and-detector-split.md).
