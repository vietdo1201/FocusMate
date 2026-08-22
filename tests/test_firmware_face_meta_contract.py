from __future__ import annotations

import struct
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class FirmwareFaceMetaContractTest(unittest.TestCase):
    def test_msr_mnp_is_pinned_and_all_five_points_are_normalized(self) -> None:
        detector = read("firmware/main/face_detector.cpp")
        header = read("firmware/main/face_detector.h")
        defaults = read("firmware/sdkconfig.defaults")

        self.assertIn("HumanFaceDetect::MSRMNP_S8_V1", detector)
        self.assertIn("set_score_thr(kDetectorScoreThreshold, 0)", detector)
        self.assertIn("set_score_thr(kDetectorScoreThreshold, 1)", detector)
        self.assertIn("CONFIG_FLASH_HUMAN_FACE_DETECT_MSRMNP_S8_V1=y", defaults)
        self.assertIn("CONFIG_HUMAN_FACE_DETECT_MSRMNP_S8_V1=y", defaults)
        self.assertNotIn("CONFIG_FLASH_ESPDET_PICO_224_224_FACE=y", defaults)
        expected_order = (
            "FOCUSMATE_FACE_KEYPOINT_LEFT_EYE",
            "FOCUSMATE_FACE_KEYPOINT_LEFT_MOUTH",
            "FOCUSMATE_FACE_KEYPOINT_NOSE",
            "FOCUSMATE_FACE_KEYPOINT_RIGHT_EYE",
            "FOCUSMATE_FACE_KEYPOINT_RIGHT_MOUTH",
        )
        offsets = [header.index(name) for name in expected_order]
        self.assertEqual(offsets, sorted(offsets))
        self.assertIn("candidate.limit_keypoint", detector)
        self.assertIn("detector_point_to_camera_q6", detector)
        self.assertIn("result.keypoint_count = FOCUSMATE_FACE_KEYPOINT_COUNT", detector)

    def test_face_meta_v1_is_fixed_size_and_frame_detector_aligned(self) -> None:
        header = read("firmware/main/frame_broker.h")
        broker = read("firmware/main/frame_broker.cpp")

        # BBBB + frame/detector/inference + uptime + bbox/confidence + 5 xy pairs.
        self.assertEqual(struct.calcsize("<BBBBIIIQ" + "I" * 5 + "I" * 10), 84)
        self.assertIn("FOCUSMATE_FACE_META_V1_SIZE 84U", header)
        self.assertIn("static_assert(sizeof(focusmate_face_meta_v1_t)", broker)
        self.assertIn("meta.frame_sequence = frame_sequence", broker)
        self.assertIn("meta.detector_sequence = face.inference_count", broker)
        self.assertIn("meta.observed_uptime_ms = face.observed_uptime_ms", broker)
        self.assertIn("slot.meta = make_face_meta(slot.sequence, slot.face)", broker)
        self.assertLess(
            detector_position := read("firmware/main/face_detector.cpp").index(
                "result.inference_count = next_inference_count++"
            ),
            read("firmware/main/face_detector.cpp").index(
                "focusmate_frame_broker_offer(frame, &result)", detector_position
            ),
        )

    def test_browser_and_watch_have_independent_latest_frame_leases(self) -> None:
        header = read("firmware/main/frame_broker.h")
        broker = read("firmware/main/frame_broker.cpp")
        dashboard = read("firmware/main/dashboard.cpp")

        self.assertIn("FOCUSMATE_FRAME_CONSUMER_BROWSER", header)
        self.assertIn("FOCUSMATE_FRAME_CONSUMER_WATCH", header)
        self.assertIn("std::array<ConsumerLease, FOCUSMATE_FRAME_CONSUMER_COUNT>", broker)
        self.assertIn("focusmate_frame_broker_try_acquire_consumer", broker)
        self.assertIn("focusmate_frame_broker_acquire(after, 0U", dashboard)
        self.assertIn("focusmate_frame_broker_try_acquire_consumer(", dashboard)
        self.assertIn("acquire_once(consumer, client_id, after_sequence, 0U, out)", broker)
        self.assertIn("slot.sequence != after_sequence", broker)
        self.assertIn("if (!viewer_active(current_us)) reset_stream_state()", broker)
        self.assertIn(
            "FOCUSMATE_FRAME_CONSUMER_BROWSER, client_id",
            broker,
        )

    def test_ble_v1_encoder_remains_bbox_only(self) -> None:
        ble = read("firmware/main/focusmate_main.c")
        task = ble[ble.index("static void observation_task"):ble.index("void app_main")]
        self.assertIn("focusmate_encode_face", task)
        for field in ("cx_q6", "cy_q6", "width_q6", "height_q6", "confidence_q6"):
            self.assertIn(f"result.{field}", task)
        self.assertNotIn("keypoint", task)
        self.assertNotIn("FaceMeta", task)

    def test_frame_access_is_encrypted_boot_scoped_and_never_puts_token_in_url(self) -> None:
        ble = read("firmware/main/focusmate_main.c")
        dashboard = read("firmware/main/dashboard.cpp")
        header = read("firmware/main/dashboard.h")
        self.assertIn("FRAME_ACCESS_INFO_SIZE 40U", ble)
        self.assertIn("f26cf312-b841-46f5-a172-6b53713a37f3", ble)
        self.assertIn("BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC", ble)
        self.assertIn("memcpy(value + 8, boot_id", ble)
        self.assertIn("memcpy(value + 24, token", ble)
        self.assertIn("capabilities |= (1U << 5)", ble)
        self.assertIn("ble_svc_gatt_changed(0x0001U, 0xffffU)", ble)
        self.assertIn("focusmate_dashboard_frame_access_snapshot", header)
        self.assertIn('"/api/watch/frame"', dashboard)
        self.assertIn('"Authorization"', dashboard)
        self.assertIn('constexpr char prefix[] = "FocusMate "', dashboard)
        self.assertNotIn("token=", dashboard)
        self.assertIn("FOCUSMATE_FRAME_CONSUMER_WATCH", dashboard)
        self.assertIn("address.ss_family == AF_INET6", dashboard)
        self.assertIn("mapped[10] == 0xffU && mapped[11] == 0xffU", dashboard)
        self.assertIn("X-FocusMate-Face-Meta-V1", dashboard)


if __name__ == "__main__":
    unittest.main()
