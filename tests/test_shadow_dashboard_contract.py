from __future__ import annotations

import csv
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class ShadowDashboardContractTest(unittest.TestCase):
    def test_dashboard_is_local_authenticated_and_shadow_only(self) -> None:
        source = read("firmware/main/dashboard.cpp")
        html = read("firmware/main/web/index.html")
        adr = read("docs/decisions/0005-local-realtime-shadow-dashboard.md")
        for route in (
            "/camera.jpg",
            "/api/status",
            "/api/viewer/release",
            "/api/auth/login",
            "/api/posture/calibrate",
            "/api/posture/reset",
            "/api/wifi/scan",
            "/api/wifi/connect",
            "/api/wifi/reset",
            "/api/wifi/ap-password",
        ):
            self.assertIn(route, source + html)
        self.assertIn("SameSite=Strict", source)
        self.assertIn("focusmate.local", source)
        self.assertIn("shadow_only", source)
        self.assertIn("watch_rules_v2", adr)
        self.assertNotIn("https://", html)
        self.assertNotIn("mediapipe", html.lower())

    def test_direct_jpeg_camera_and_bounded_broker_are_preserved(self) -> None:
        camera = read("firmware/main/camera_smoke.c")
        broker = read("firmware/main/frame_broker.cpp")
        detector = read("firmware/main/face_detector.cpp")
        self.assertIn("PIXFORMAT_JPEG", camera)
        self.assertIn("FRAMESIZE_QVGA", camera)
        self.assertIn("kFrameWidth = 320U", broker)
        self.assertIn("kJpegSlotCount = 3U", broker)
        self.assertIn("kJpegSlotCapacity = 128U * 1024U", broker)
        self.assertIn("kOfferPeriodUs = 200000U", broker)
        self.assertIn("direct camera JPEG broker", broker)
        self.assertIn("std::memcpy(slot.data, frame->buf, frame->len)", broker)
        self.assertNotIn("frame2jpg_cb", broker)
        self.assertIn("viewer_active(current_us)", broker)
        self.assertIn("encode_drops", broker)
        self.assertIn("kDetectorWidth = 240U", detector)
        self.assertIn("kDetectorHeight = 240U", detector)
        self.assertIn("JPEG_IMAGE_SCALE_0", detector)
        self.assertIn("kDetectorLeft = (kCameraWidth - kDetectorWidth) / 2U", detector)
        self.assertIn("kDetectorScoreThreshold = 0.35F", detector)
        posture = read("firmware/main/shadow_posture.cpp")
        self.assertIn("kCalibrationMinimumConfidence = 700000U", posture)
        self.assertIn("kLiveMinimumConfidence = 500000U", posture)

    def test_safe_wifi_pending_promote_and_rollback_contract(self) -> None:
        source = read("firmware/main/dashboard.cpp")
        for token in (
            "pending_ssid",
            "pending_pass",
            "promote_pending",
            "rollback_pending",
            "pending Wi-Fi timed out; restored previous network",
            "FocusMate-Setup",
            "192.168.4.1",
        ):
            self.assertIn(token, source)
        self.assertIn('espressif/mdns: "1.9.1"', read("firmware/main/idf_component.yml"))
        self.assertIn("station_associated_", source)
        self.assertIn("!online && !associated && reconnect_due", source)

    def test_kotlin_and_firmware_thresholds_match(self) -> None:
        firmware = read("firmware/main/shadow_posture.cpp")
        kotlin = read(
            "soucre_code/from_On_Hand_3_android_wear/app/src/main/kotlin/"
            "vn/edu/uit/tpkd/wear/cogload/PostureClassifier.kt"
        )
        expected = {
            "minimumLiveDetectorConfidence": ("kLiveMinimumConfidence", 0.50, 500000),
            "minimumCalibrationDetectorConfidence": ("kCalibrationMinimumConfidence", 0.70, 700000),
            "leanEnterDelta": ("kLeanDelta", 0.15, 150000),
            "headDownEnterDelta": ("kHeadDownDelta", 0.12, 120000),
            "slumpedEnterDelta": ("kSlumpedDelta", 0.18, 180000),
            "tooCloseAreaRatio": ("kTooCloseRatio", 1.60, 1600000),
        }
        for kotlin_name, (firmware_name, decimal, scaled) in expected.items():
            self.assertRegex(kotlin, rf"{kotlin_name}: Double = {decimal:.2f}")
            self.assertRegex(firmware, rf"{firmware_name} = {scaled}")

    def test_live_confidence_debounce_and_slumped_timer_are_fail_safe(self) -> None:
        firmware = read("firmware/main/shadow_posture.cpp")
        kotlin = read(
            "soucre_code/from_On_Hand_3_android_wear/app/src/main/kotlin/"
            "vn/edu/uit/tpkd/wear/cogload/PostureClassifier.kt"
        )
        dashboard = read("firmware/main/dashboard.cpp")
        html = read("firmware/main/web/index.html")
        self.assertIn("advance_stable_state", firmware)
        self.assertIn("FOCUSMATE_POSTURE_UNKNOWN, stable, candidate, count", firmware)
        self.assertNotIn("if (state == FOCUSMATE_POSTURE_UNKNOWN)", firmware)
        self.assertIn("slumped_since_ms", firmware)
        self.assertNotIn("head_down_since_ms", firmware)
        self.assertIn("slumpedSinceMs", kotlin)
        self.assertNotIn("headDownSinceMs", kotlin)
        self.assertIn('"raw_confidence"', dashboard)
        self.assertIn('"live_confidence"', dashboard)
        self.assertIn('"calibration_confidence"', dashboard)
        self.assertIn("rawConfidence", html)

    def test_calibration_window_can_physically_collect_twenty_samples(self) -> None:
        firmware = read("firmware/main/shadow_posture.cpp")
        html = read("firmware/main/web/index.html")
        self.assertIn("kCalibrationSamples = 20U", firmware)
        self.assertIn("kCalibrationWindowMs = 8000U", firmware)
        self.assertIn("kCalibrationPrebufferMaxAgeMs = 10000U", firmware)
        self.assertIn("copy_recent_calibration", firmware)
        self.assertIn("Hiệu chỉnh 20 mẫu", html)
        self.assertNotIn("Hiệu chỉnh 5 giây", html)

    def test_shared_geometry_vectors_follow_classifier_precedence(self) -> None:
        fixture = ROOT / "tests/golden/posture_geometry_v1.tsv"
        with fixture.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        self.assertEqual(
            [row["expected"] for row in rows],
            ["NORMAL", "HEAD_DOWN", "LEAN_LEFT", "LEAN_RIGHT", "TOO_CLOSE", "SLUMPED"],
        )
        for row in rows:
            dx = float(row["dx"])
            dy = float(row["dy"])
            ratio = float(row["area_ratio"])
            hold = int(row["hold_ms"])
            if ratio >= 1.60:
                actual = "TOO_CLOSE"
            elif dy >= 0.18:
                actual = "SLUMPED" if hold >= 5000 else "HEAD_DOWN"
            elif dy >= 0.12:
                actual = "HEAD_DOWN"
            elif dx <= -0.15:
                actual = "LEAN_LEFT"
            elif dx >= 0.15:
                actual = "LEAN_RIGHT"
            else:
                actual = "NORMAL"
            self.assertEqual(row["expected"], actual, row["name"])


if __name__ == "__main__":
    unittest.main()
