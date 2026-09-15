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

Kết quả gần nhất: Android có 148 unit/Robolectric test, 0 failure; lint, debug/release APK đều xanh. Python có 33 test, Node có 19 test. Source archive candidate 349 mục đã clean-build ngoài repository với ESP-IDF 5.5.5, firmware 2.3.0 và `dl_fft 0.6.0`. Candidate đạt `code-ready`; Watch/ESP chưa được cài hoặc flash nên chưa `device-verified`, và blocker license model/chữ ký vẫn khiến bản này chưa `release-ready`.

Không commit/push/flash từ handoff này nếu người dùng chưa yêu cầu.
