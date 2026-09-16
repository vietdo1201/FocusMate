# Handoff — FocusMate session/reminder

Ngày: 2026-09-16. Đây là index triển khai; đặc tả và evidence canonical ở `SESSION_HEALTH_ROADMAP.md` và ADR 0008.

## Không được thay đổi

- `watch_rules_v2` là live baseline; giữ truth table 30/45/60 và cooldown 20 phút.
- `checkin_shadow_v1`, HR, posture và yawn không được gọi notification/vibrator.
- Không thêm server/model/cloud/protocol ESP32/UI framework.
- Không gọi con số sản phẩm là ngưỡng y khoa.

## Điểm vào code

| Việc | File |
|---|---|
| State/time/contracts | `SessionContracts.kt`, `SessionTimeline.kt`, `StudySession.kt` |
| SQLite/migration/idempotency | `FocusMateSessionDatabase.kt`, `StudySessionRepository.kt` |
| Baseline/shadow | `WatchRuleEngine.kt`, `SessionContracts.kt` |
| Delivery | `BreakReminderScheduler.kt`, `BreakReminderReceiver.kt` |
| Motion | `AccCollector.kt`, `SessionSensorService.kt` |
| UI/check-in/nghỉ chủ động | `MainActivity.kt`, `activity_main.xml` |
| Evidence | `HealthEvidence.kt`, ADR 0008 |

## Việc tiếp theo theo thứ tự

1. Chạy process-kill/force-stop thật cho accept, resume, finish và migration; host SQLite failure-injection của cả bốn đường đã xanh.
2. Chạy permission/channel matrix trên Watch thật; Robolectric off→on, slot budget và callback lặp đã xanh.
3. Giữ host verification xanh; sau đó test Watch-only dưới screen-off/Doze/battery saver/reboot/quyền.
4. Chỉ sau gate Watch-only mới chạy Watch+ESP và pilot 18+.

## Lệnh kiểm tra

Từ `wear/`: `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease`. Hai model generated hiện đã được bootstrap và task `verifyWearModels` qua. Không coi host build là device verification.

Hồ sơ hiện tại ghi nhận: Android 152/152 unit/Robolectric test, protocol 26/26,
audit recovery độc lập 2/2, lint 0 lỗi, debug/release APK qua; Python 33/33 và
Node 19/19. Release tối ưu giữ đủ member JNI MediaPipe trong DEX. Source archive
candidate 349 mục đã clean-build ngoài repository với ESP-IDF 5.5.5 và
`dl_fft 0.6.0`. `v2.3.0` đã phát hành tại `d7c072e`, Watch code 26. Xem
[STATUS.md](STATUS.md) và [hồ sơ đồng bộ](V2.3.0_SYNC_PLAN.md); các cải tiến cũ
được đối chiếu theo từng nhóm để giữ nguyên session UI và hợp đồng ADR 0008.
