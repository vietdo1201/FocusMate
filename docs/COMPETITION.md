# Ma trận tiêu chí phần mềm nguồn mở

| Tiêu chí | Bằng chứng trong repository |
|---|---|
| Mã nguồn công khai | GitHub public, lịch sử commit, web viewer, Issues |
| OSI-approved license | Apache-2.0 + MIT, SPDX/REUSE, `LICENSES/`, NOTICE |
| Release có phiên bản | GitHub Release v2.2.0; artifact độc lập, hash và chữ ký APK |
| Build từ source | `docs/BUILDING.md`, bootstrap hash-pinned, CI Android + ESP-IDF |
| Dependency/bundling | Lockfile, verification metadata, IDF pin, notices, SPDX SBOM |
| Tài liệu/giao tiếp | README, changelog, security, contributing, issue templates |
| Tính nguyên gốc | Kiến trúc local-first ESP + Watch, deterministic rules, dual transport |
| Hoàn thiện/UX | Dashboard + Watch UI, fail-safe offline, báo cáo phiên |
| AI | Model card, pinned local inference, privacy/limitations rõ ràng |
| Cộng đồng | Apache/MIT contribution path, bug tracker và release notes song ngữ |

Không sử dụng ảnh pin 1,1% để tuyên bố mức cải thiện định lượng. Trạng thái sản
phẩm và bằng chứng thiết bị tuân theo taxonomy trong `docs/STATUS.md`.
