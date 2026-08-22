from __future__ import annotations

import csv
import unittest
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class ShadowDashboardContractTest(unittest.TestCase):
    def test_dashboard_is_local_authenticated_and_landmark_primary(self) -> None:
        source = read("firmware/main/dashboard.cpp")
        html = read("firmware/main/web/index.html")
        adr = read("docs/decisions/0005-local-realtime-shadow-dashboard.md")
        for route in (
            "/camera.jpg",
            "/api/watch/frame",
            "/assets/*",
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
        self.assertIn("MediaPipe Pose", html)
        self.assertIn("POSE_LOCAL", html)
        self.assertIn("pose_worker_bootstrap.js", html)
        self.assertIn("worker-src 'self'", source)
        self.assertIn("X-FocusMate-Face-Meta-V1", source)
        self.assertIn("Authorization", source)

    def test_direct_jpeg_camera_and_bounded_broker_are_preserved(self) -> None:
        camera = read("firmware/main/camera_smoke.c")
        broker = read("firmware/main/frame_broker.cpp")
        detector = read("firmware/main/face_detector.cpp")
        self.assertIn("PIXFORMAT_JPEG", camera)
        self.assertIn("FRAMESIZE_QVGA", camera)
        self.assertIn("kFrameWidth = 320U", broker)
        self.assertIn("kJpegSlotCount = 3U", broker)
        self.assertIn("kJpegSlotCapacity = 128U * 1024U", broker)
        self.assertIn("kOfferPeriodUs = 150000U", broker)
        self.assertIn("direct camera JPEG broker", broker)
        self.assertIn("std::memcpy(slot.data, frame->buf, frame->len)", broker)
        self.assertNotIn("frame2jpg_cb", broker)
        self.assertIn("viewer_active(current_us)", broker)
        self.assertIn("FOCUSMATE_FRAME_CONSUMER_BROWSER", broker)
        self.assertIn("FOCUSMATE_FRAME_CONSUMER_WATCH", broker)
        self.assertIn("keypoint_count", detector)
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
        self.assertIn('"baseline_revision"', dashboard)
        self.assertIn("esp_bbox_fallback_v2", dashboard)
        self.assertIn("watch_geometry_v2_experimental", kotlin)
        self.assertIn("rawConfidence", html)

    def test_landmark_calibration_is_automatic_and_bbox_fallback_stays_bounded(self) -> None:
        firmware = read("firmware/main/shadow_posture.cpp")
        html = read("firmware/main/web/index.html")
        self.assertIn("kCalibrationSamples = 20U", firmware)
        self.assertIn("kCalibrationSettleMs = 1000U", firmware)
        self.assertIn("kCalibrationCollectionMs = 15000U", firmware)
        self.assertIn("kCalibrationMaximumGapMs = 1500U", firmware)
        self.assertIn("calibration_bbox_fully_visible", firmware)
        self.assertIn("runtime.calibrated = false", firmware)
        self.assertIn("erase_baseline()", firmware)
        self.assertIn('runtime.calibration_reason = "storage_error"', firmware)
        self.assertNotIn("complete_not_persisted", firmware + html)
        self.assertNotIn("recent_", firmware)
        self.assertNotIn("copy_recent_calibration", firmware)
        classifier = read("firmware/main/web/pose_classifier.mjs")
        self.assertIn("REQUIRED_BASELINE_SAMPLES = 20", classifier)
        self.assertIn("REQUIRED_BASELINE_MS = 5000", classifier)
        self.assertIn("features.quality < 0.7", classifier)
        self.assertIn("auto_calibrating", classifier)
        self.assertIn("Xóa baseline và tự hiệu chỉnh lại", html)
        self.assertNotIn('id="calibrate"', html)
        self.assertNotIn("Hiệu chỉnh 5 giây", html)

    def test_offline_mediapipe_assets_are_pinned_and_partitioned(self) -> None:
        script = read("firmware/tools/prepare_mediapipe_assets.ps1")
        cmake = read("firmware/main/CMakeLists.txt")
        partitions = read("firmware/partitions.csv")
        assets = read("firmware/main/web_assets.cpp")
        self.assertIn("1.0.1", script)
        self.assertIn("EE318EAA3D42230AA10910D114FAF2A488C577C4E4D33C7CB04126924ACA505F", script)
        self.assertIn("59929E1D1EE95287735DDD833B19CF4AC46D29BC7AFDDBBF6753C459690D574A", script)
        self.assertIn("spiffs_create_partition_image(mp_assets", cmake)
        self.assertIn("mp_assets", partitions)
        self.assertIn('Content-Encoding", "gzip', assets)
        self.assertIn("pose_landmarker_lite.task.gz", assets)
        self.assertIn("vwi.wasm.gz", assets)
        self.assertIn("globalThis.ModuleFactory = ModuleFactory", script)
        self.assertIn('console.error("FocusMate Pose"', read("firmware/main/web/index.html"))
        worker = read("firmware/main/web/pose_worker.mjs")
        bootstrap = read("firmware/main/web/pose_worker_bootstrap.js")
        self.assertIn("wasm-classic-v1", worker)
        self.assertIn("wasm-classic-v1", bootstrap)
        self.assertIn("wasm-classic-v1", assets)
        self.assertIn("importScripts", bootstrap)
        self.assertIn("pose-local-classic-2", bootstrap)
        self.assertIn("pose-worker-classic-2", read("firmware/main/web/index.html"))
        self.assertIn("classifier-2", worker)
        self.assertIn('asset->gzip', assets)
        self.assertIn('"no-cache"', assets)

    def test_subject_relative_horizontal_axis_invalidates_old_baseline(self) -> None:
        firmware = read("firmware/main/shadow_posture.cpp")
        kotlin = read(
            "soucre_code/from_On_Hand_3_android_wear/app/src/main/kotlin/"
            "vn/edu/uit/tpkd/wear/cogload/PostureClassifier.kt"
        )
        self.assertIn("kBaselineRevision = 2U", firmware)
        self.assertIn("kProfileFingerprint = 0x4A032182U", firmware)
        self.assertIn("runtime.baseline_cx) - static_cast<int32_t>(result->cx_q6", firmware)
        self.assertIn("baselineCxQ6 - observedCxQ6", kotlin)
        self.assertIn("lateralQ6 * headDownDeltaQ6 >= dyQ6 * leanDeltaQ6", kotlin)
        self.assertIn("medianQ6", kotlin)
        self.assertIn("maximumContinuousGapMs", kotlin)
        self.assertIn("UINT32_MAX", firmware)
        self.assertIn("coerceAtMost(UINT32_MAX)", kotlin)
        self.assertIn("lean_dominant", firmware)
        self.assertIn("leanDominant", kotlin)

    def test_shared_geometry_vectors_follow_classifier_precedence(self) -> None:
        fixture = ROOT / "tests/golden/posture_geometry_v2.tsv"
        with fixture.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        self.assertEqual(
            [row["expected"] for row in rows],
            [
                "NORMAL", "HEAD_DOWN", "LEAN_LEFT", "LEAN_RIGHT", "TOO_CLOSE", "SLUMPED",
                "LEAN_LEFT", "LEAN_RIGHT", "HEAD_DOWN", "LEAN_LEFT",
            ],
        )
        for row in rows:
            dx_q6 = int(Decimal(row["dx"]) * 1_000_000)
            dy_q6 = int(Decimal(row["dy"]) * 1_000_000)
            ratio_q6 = int(Decimal(row["area_ratio"]) * 1_000_000)
            hold = int(row["hold_ms"])
            lateral_q6 = abs(dx_q6)
            lean_candidate = lateral_q6 >= 150_000
            head_candidate = dy_q6 >= 120_000
            lean_dominant = lean_candidate and (
                not head_candidate or lateral_q6 * 120_000 >= dy_q6 * 150_000
            )
            if ratio_q6 >= 1_600_000:
                actual = "TOO_CLOSE"
            elif lean_dominant:
                actual = "LEAN_LEFT" if dx_q6 < 0 else "LEAN_RIGHT"
            elif dy_q6 >= 180_000:
                actual = "SLUMPED" if hold >= 5000 else "HEAD_DOWN"
            elif dy_q6 >= 120_000:
                actual = "HEAD_DOWN"
            elif dx_q6 <= -150_000:
                actual = "LEAN_LEFT"
            elif dx_q6 >= 150_000:
                actual = "LEAN_RIGHT"
            else:
                actual = "NORMAL"
            self.assertEqual(row["expected"], actual, row["name"])


if __name__ == "__main__":
    unittest.main()
