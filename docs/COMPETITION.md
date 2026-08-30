# Ma trận tiêu chí phần mềm nguồn mở

| Tiêu chí | Bằng chứng trong repository |
|---|---|
| Mã nguồn công khai | GitHub public, lịch sử commit, web viewer, Issues |
| OSI-approved license | Apache-2.0 + MIT, SPDX/REUSE, `LICENSES/`, NOTICE |
| Release có phiên bản | GitHub Release v2.2.2; artifact cập nhật độc lập, factory image ghi nhãn riêng, hash và chữ ký APK |
| Build từ source | `docs/BUILDING.md`, bootstrap hash-pinned, CI Android + ESP-IDF |
| Dependency/bundling | Lockfile, verification metadata, IDF pin, notices, SPDX SBOM |
| Tài liệu/giao tiếp | README, changelog, security, contributing, issue templates |
| Tính nguyên gốc | Kiến trúc local-first ESP + Watch, deterministic rules, dual transport |
| Mức độ hoàn thiện | [24/24 System Test PASS](../tests/FocusMate_Test/TEST_MATRIX.md), gồm TC01–TC24 và evidence trực tiếp |
| Thân thiện người dùng | [TC06 Start Session](../tests/FocusMate_Test/Evidence/TC06_session_start.jpg), [TC19 Session Report](../tests/FocusMate_Test/Evidence/TC19_session_events.jpg), [TC20 End Session](../tests/FocusMate_Test/Evidence/TC20_session_end.jpg) |
| AI | [TC07–TC14 Posture/Yawn](../tests/FocusMate_Test/TEST_MATRIX.md#đầy-đủ-24-test-cases), model card, pinned local inference, privacy/limitations rõ ràng |
| Tích hợp thiết bị | [TC01–TC05 ESP/Web và TC15–TC24 Watch/full-flow](../tests/FocusMate_Test/TEST_MATRIX.md#đầy-đủ-24-test-cases) |
| Độ ổn định | [TC21 disconnect](../tests/FocusMate_Test/Evidence/TC21_Dis.mp4), [TC22 reconnect](../tests/FocusMate_Test/Evidence/TC22_Reconnect.mp4), [TC24 long-session](../tests/FocusMate_Test/Evidence/TC24_KetThucPhienDongHo.mp4) |
| Cộng đồng | Apache/MIT contribution path, bug tracker và release notes song ngữ |

Không sử dụng ảnh pin 1,1% để tuyên bố mức cải thiện định lượng. Trạng thái sản
phẩm và bằng chứng thiết bị tuân theo taxonomy trong `docs/STATUS.md`.
Recorded System Test xác minh các kịch bản chức năng đã tài liệu hóa; không phải
tuyên bố tổng quát về accuracy AI, y tế, nhiệt hoặc độ tin cậy dài hạn.
