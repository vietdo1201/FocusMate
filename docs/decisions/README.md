# Decision records

ADR là append-only. Xem `0001-document-authority-and-status.md`, `0002-watch-rule-engine-and-detector-split.md`, `0003-optional-frame-transport-to-watch.md`, `0004-gatt-profile-and-canonical-framing.md` và `0005-local-realtime-shadow-dashboard.md`. ADR 0003 chỉ thay đổi ràng buộc truyền frame; Rule Engine deterministic của ADR 0002 vẫn giữ nguyên. ADR 0004 chốt canonical wire format và GATT profile; spec normative nằm ở `../GATT_PROFILE.md`. ADR 0005 cho phép một dashboard frame local có xác thực ở shadow mode; contract HTTP nằm ở `../WEB_DASHBOARD.md`.
