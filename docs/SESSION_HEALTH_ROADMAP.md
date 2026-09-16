# FocusMate — roadmap phiên học và sức khỏe

Ngày cập nhật: 2026-09-16. Baseline trước triển khai: `0de490ee25a829e5033749d943539138ef9a4b10`.
Source đã phát hành: `v2.3.0`, commit `d7c072e`. Các kết quả dưới đây được gắn
với đúng tag, current main hoặc report lịch sử theo [STATUS.md](STATUS.md).

## Mục tiêu

FocusMate quản lý phiên học, hỗ trợ nghỉ chủ động và đưa ra lời đề nghị nghỉ ít gián đoạn. Sản phẩm không chẩn đoán mệt, stress, bệnh hoặc mức hồi phục. Pilot đầu dành cho người học 18+; nhóm THCS–THPT cần bằng chứng theo tuổi và quy trình đồng thuận riêng.

## Hợp đồng bằng chứng

| Mã | Nguồn | Điều được dùng | Giới hạn sản phẩm |
|---|---|---|---|
| HEALTH-01 | AHA, All About Heart Rate | HR chịu nhiều yếu tố ảnh hưởng | HR không điều khiển timing, focus hoặc fatigue |
| HEALTH-02 | UK HSE, Work routine and breaks | nghỉ ngắn, thường xuyên và quyền lựa chọn là nguyên tắc thiết kế | không suy ra một lịch nghỉ y khoa chung |
| HEALTH-03 | Albulescu et al., PLOS ONE 2022, DOI 10.1371/journal.pone.0272460 | hiệu ứng trung bình nhỏ với fatigue/vigor | không hứa tăng hiệu suất hoặc giảm mệt chắc chắn |
| HEALTH-04 | WHO, Physical activity | giảm tĩnh tại/tăng hoạt động là mục tiêu có ích | cảm biến cổ tay không xác nhận vận động toàn thân hay sức khỏe |
| HEALTH-05 | AOA; Johnson & Rosenfield 2023, DOI 10.1097/OPX.0000000000001971 | nghỉ mắt có thể là hướng dẫn tùy chọn | không xác nhận hiệu quả trị liệu của đúng con số 20-20-20; không reset focus block |

Registry có cấu trúc, ngày truy cập và mapping requirement nằm trong `HealthEvidence.kt`. Các mốc 30/45/60, cooldown 20 phút và break 5 phút là luật sản phẩm hiện có, không phải ngưỡng y khoa.

## Kiến trúc đã thêm

- `SessionContracts.kt`: `TimePoint`, trạng thái, command, `BreakPolicy`, baseline và shadow.
- `SessionTimeline.kt`: reducer monotonic/boot-aware; wall-clock jump không đổi duration, boot khác yêu cầu recovery.
- `FocusMateSessionDatabase.kt`: SQLite authoritative với snapshot, event, reminder, check-in, break, feedback, comparison và recovery rows.
- `StudySessionRepository.kt`: adapter cho call-site cũ; migration preferences → SQLite một lần, kiểm ID trước khi chuyển nguồn đọc.
- `BreakReminderScheduler/Receiver`: một initial + một retry 5 phút; retry quá grace không rung; recovery đủ điều kiện được xét ngay.
- `AccCollector`: window có elapsed start/end, boot, block, sequence, valid duration; repository loại trùng/giao nhau/sai block/gap.
- `MainActivity`: nghỉ chủ động, pause/resume, check-in tự nguyện; shadow không tạo notification.
- `SessionSensorService`: motion kích hoạt đánh giá event-driven; ngáp yên lặng.

## Trạng thái lát cắt

| Lát | Trạng thái | Kế hoạch evidence |
|---|---|---|
| A — đặc tả/evidence/ADR | Đã có code registry + ADR 0008 + tài liệu này | rà soát nội dung bởi chuyên môn trước pilot |
| B — regression | Đã có timer callback trễ, pause, motion identity, retry, shadow, callback cũ hơn checkpoint và chuỗi transition seed cố định | process-kill thật vẫn cần device/instrumentation |
| C — store/clock/controller | SQLite/migration/contracts/reducer đã có; command pause/resume/break/recovery/finish/cancel dùng identity và idempotency; checkpoint 30 giây chạy trong service; host failure-injection phủ accept/resume/finish/migration | xác minh process death/force-stop thật trên Watch |
| D — delivery | alarm dùng elapsed realtime trong cùng boot; retry budget, late grace, delivery slot cho cả hai loại reminder, immediate motion check và silent yawn đã có | đo Doze/permission thực tế trên Watch |
| E — check-in/report/shadow | check-in tự nguyện, chuyển nhiệm vụ, before/after/end; after-break chỉ hiện lựa chọn không tự bật dialog; ghép fatigue theo break, shadow comparison và lịch sử từng delivery/feedback đã có | rà UX màn hình tròn và report phiên thật nhiều break |
| F — device/pilot | Lộ trình pilot | Watch-only trước, Watch+ESP sau; 10 người lớn × 6 phiên nếu được đồng thuận |

## Acceptance và trạng thái hiện tại

| Mã | Bằng chứng hiện có | Cổng còn thiếu |
|---|---|---|
| TIME-01/02 | unit test callback break trễ và chờ resume; thời gian chờ không thành study | chạy phiên thật nhiều break |
| TIME-03/04 | pause test và monotonic wall-clock ±2 giờ | đổi giờ trên Watch thật |
| REC-01 | reducer boot mismatch + Robolectric receiver reconcile trước schedule | reboot Watch khi đang học và đang nghỉ |
| REC-02 | command ID từ reminder làm start-break idempotent; SQLite trigger chứng minh rollback atomically cho accept/resume/finish/migration | giết process thật đúng ranh giới trên Watch |
| RULE-01/02 | truth table v2 và recovery phút 52 | Doze/permission timing trên Watch |
| MOTION-01 | duplicate, overlap, out-of-order, gap và old-block tests | soak Watch-only rồi Watch+ESP |
| DELIVERY-01 | một initial + một retry; late retry chuyển quiet | đo giao thực tế màn hình tắt |
| DELIVERY-02/03 | delivery-blocked, permission off→on chỉ dùng slot còn lại và stale action guards ở repository/receiver | device alarm trễ/quyền thật |
| HEALTH-06 | `PolicyContext` không có HR/posture/yawn; shadow không có delivery dependency | instrumentation chứng minh không rung |
| CHECKIN-01 | TTL monotonic, same-block, missing-field fallback, break linkage và idempotent insert | kiểm tra UX trên màn hình tròn |
| REPORT-01 | report tách study/break/pause/unknown, lưu từng reminder cùng scheduled/receiver/post/result/response/feedback và ghép fatigue cùng break trong ±5 phút | xác minh UI thật với nhiều block/break |
| PRIVACY-01 | test 500 phiên chứng minh xóa child rows bị đẩy khỏi giới hạn; test expiry xóa child + recovery payload; xóa toàn bộ có test riêng | xác minh erase/retention trên thiết bị |

Host tests và device reports được công bố thành hai lớp evidence riêng, cùng
commit/hash và điều kiện chạy tương ứng.

## Câu chữ

Cho phép: “ít chuyển động cổ tay”, “chưa đủ dữ liệu”, “bạn tự đánh giá mức mệt…”. Không cho phép: “AI xác định stress”, “không ngáp nên khỏe”, “HR giảm nên đã hồi phục”, hoặc lời hứa hiệu suất.

## Verification hiện tại

- `python tools/bootstrap_assets.py` đã khôi phục và kiểm tra hai model generated.
- Python: 33 test qua; Node: 19 test qua; firmware ESP-IDF clean build qua.
- Android lần cuối: 152/152 unit/Robolectric test và 26/26 protocol test; audit
  recovery độc lập 2/2; lint 0 lỗi, debug APK và release APK đều build thành công.
- Release tối ưu R8 giữ đủ member JNI/protobuf MediaPipe cần cho graph/model startup.
- Firmware artifact đã build: `firmware/build/focusmate_esp.bin`, 3.158.688 byte.
- Source archive candidate 349 mục đã được giải nén ngoài repository và chạy lại toàn bộ các cổng trên; firmware dùng đúng lock `dl_fft 0.6.0` và còn 25% app partition.
- SBOM candidate có 86 package; REUSE đạt 303/303 tệp và compliance/secret-pattern check đạt. Hai model revision 1 đã được nối từ SHA-256 tới bảng bundle chính thức và model card Apache-2.0 theo thành phần.
- Trạng thái hiện tại: `code-ready`, đã [phát hành v2.3.0](https://github.com/vietdo1201/FocusMate/releases/tag/v2.3.0).
  Nhãn `device-verified` được quản lý theo từng component/report; tiêu chí
  `RELEASE_ELIGIBLE` đối chiếu riêng với checklist của Ban tổ chức.
