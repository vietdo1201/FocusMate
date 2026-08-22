# Governance và nguồn tài liệu chuẩn

## Ma trận source of truth

| Phạm vi | Nguồn chuẩn |
|---|---|
| Governance/status | `docs/GOVERNANCE.md`, `docs/STATUS.md` |
| Yêu cầu và an toàn | `help.md` |
| Quyết định kiến trúc | ADR trong `docs/decisions/` |
| Wire format và GATT profile | `docs/GATT_PROFILE.md` — normative, chốt bởi ADR 0004 |
| Phạm vi release | `soucre_code/from_On_Hand_3_android_wear/ROADMAP.md` |
| Definition of Done | `soucre_code/from_On_Hand_3_android_wear/IMPLEMENTATION_PLAN.md` |
| Hành vi triển khai | Source code + automated tests |
| Sơ đồ chân | `data/So_do_chan.md` — draft đến khi verified device |
| Golden vectors | `tests/golden/` — byte-exact, sinh theo `docs/GATT_PROFILE.md` |
| Báo cáo cũ | `CLEANUP_REPORT.md`, `reports/` — historical |

Ưu tiên: an toàn/pháp lý → ADR accepted → yêu cầu sản phẩm → protocol version hóa → roadmap/plan → README/ví dụ. ADR mới thay thế ADR cũ bằng liên kết; không sửa mất lịch sử.

## Trạng thái

Implementation: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED`.

Readiness: `TARGET`, `EXPERIMENTAL`, `RELEASE_ELIGIBLE`, `DEFERRED`, `NOT_APPLICABLE`.

Evidence: `UNVERIFIED`, `VERIFIED_LOCAL`, `VERIFIED_DEVICE`.

Quy tắc:

- Codec, classifier hoặc unit test chỉ là bằng chứng local; không tự nâng BLE/firmware/hardware.
- `RELEASE_ELIGIBLE` phải gắn với artifact hash, build, runtime, thiết bị/OS và report.
- Ngưỡng geometry hiện là experimental, không phải release claim.
- Thiếu capability phải báo unavailable; không tạo dữ liệu giả.
- Mọi thay đổi protocol, privacy, license hoặc kiến trúc cần ADR và verification.
