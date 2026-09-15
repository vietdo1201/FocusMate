# Ma trận bằng chứng PoF — ứng viên FocusMate 2.3.0

Ngày rà soát: 2026-09-16. Tài liệu này là checklist trước khi nộp, không phải tuyên bố điểm chính thức của Ban tổ chức.

| Tiêu chí | Điểm tối đa | Bằng chứng trong dự án | Trạng thái / việc còn thiếu |
|---|---:|---|---|
| Kho mã nguồn Internet | 5 | Kho công khai `vietdo1201/FocusMate`, lịch sử Git, GitHub web viewer và CI Verify | Có bằng chứng. Trước nộp phải xác nhận URL truy cập được khi đăng xuất. |
| Giấy phép OSI-approved | 10 | `LICENSE`, `LICENSES/Apache-2.0.txt`, `LICENSES/MIT.txt`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, SPDX/REUSE | Mã FocusMate có phạm vi rõ. Hai model revision 1 đã được nối từ hash byte-exact tới bảng bundle chính thức và các model card Apache-2.0; provenance ghi riêng, không suy từ license runtime. |
| Ít nhất một release | 5 | Release lịch sử v2.2.0–v2.2.2; workflow tạo draft, checksum và chữ ký | Đã có release trước hạn. 2.3.0 mới là candidate, chưa tag/publish. Cần BTC xác nhận cách hiểu “định dạng mở” đối với APK/firmware và source `.tar.gz`. |
| Build từ nguồn | 10 | `docs/BUILDING.md`, wrapper Gradle, ESP-IDF manifest, bootstrap hash-pinned, CI | Source archive candidate 349 mục đã clean-build ngoài repository ngày 2026-09-16: Python 33/33, Web 19/19, Android 148 test + lint/debug/release và firmware 2.3.0 với `dl_fft 0.6.0`. Hash nằm trong tệp checksum đi kèm artifact; device/khóa ký không được suy ra từ build unsigned. |
| Thư viện và bundling | 10 | lockfile, verification metadata, SBOM lịch sử + SBOM candidate, provenance, notices, manifest model | SBOM candidate có 86 package, đủ 8 component firmware và không còn license `NOASSERTION`. `esp_new_jpeg` và JSON-java giữ LicenseRef chính xác. Loader upstream giữ nguyên byte; Face task compact có manifest thành viên/hash chuyển đổi. |
| Tài liệu và giao tiếp | 10 | README, BUILDING, CONTRIBUTING, CHANGELOG, bug templates, ADR, roadmap/handoff, release notes | Draft issue ở `docs/ISSUE_DRAFTS.md`; chỉ dẫn link issue thật sau khi người dùng cho phép đăng. Device report 2.3.0 chưa có. |

## Cổng trạng thái

- `code-ready`: host tests, lint, compliance và build từ source archive đạt.
- `device-verified`: đúng hash APK/firmware/model đã chạy Watch-only và Watch+ESP, có điều kiện và timing report.
- `release-ready`: thêm quyền phân phối model, chữ ký nâng cấp hợp lệ, notices/SBOM/checksum và draft release đã đối chiếu.

Không gộp ba nhãn trên thành một chữ “đạt”. Candidate hiện đạt `code-ready`; chưa `device-verified` và chưa `release-ready`.

## Câu hỏi cần gửi BTC

1. APK, firmware `.bin`, model/runtime tích hợp để chạy offline có bị coi là “gói đính kèm của dự án khác” hay không, và bằng chứng attribution/SBOM nào được chấp nhận?
2. Ban tổ chức có chấp nhận source `.tar.gz` cùng hướng dẫn bootstrap hash-pinned, trong khi APK và firmware là artifact cài đặt bắt buộc của sản phẩm không?
3. Việc đóng gói lại model task nhưng giữ nguyên byte từng thành viên phải được mô tả/đính kèm bằng chứng nào?
4. “Không dùng ZIP/RAR” có áp dụng cho định dạng nội tại của APK/task bundle hay chỉ định dạng tải source/release do đội thi lựa chọn?
