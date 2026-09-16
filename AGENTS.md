# FocusMate contributor handoff

- Canonical scope and health limits: `docs/SESSION_HEALTH_ROADMAP.md` and ADR 0008.
- Build and verification: `docs/BUILDING.md`; release status: `docs/STATUS.md`.
- Preserve `watch_rules_v2`, the BLE/frame protocol, model hashes and local-first architecture unless a requirement explicitly changes them.
- Do not present product timing as medical thresholds. HR, posture, yawn and `checkin_shadow_v1` must not trigger reminders.
- Before a commit, run the affected tests plus `python tools/check_compliance.py`, `python -m reuse lint`, and `git diff --check`.
- Report tests not run and device evidence separately. Do not commit secrets, participant data, generated models or build caches.
- Do not commit, push, publish a release or flash devices unless the user explicitly asks.
