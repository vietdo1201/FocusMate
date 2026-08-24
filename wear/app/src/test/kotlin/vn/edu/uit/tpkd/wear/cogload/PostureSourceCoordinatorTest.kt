// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Test

class PostureSourceCoordinatorTest {
    @Test
    fun poseWinsWhileFreshThenGeometryResumesWithoutDoubleCounting() {
        val updates = mutableListOf<PostureIngestionUpdate>()
        val sources = mutableListOf<PostureSource>()
        val coordinator = PostureSourceCoordinator(
            onUpdate = updates::add,
            onSource = { source, _ -> sources += source },
        )
        coordinator.acceptGeometry(classification(PostureState.NORMAL, 1_000, GeometryPostureClassifier.SOURCE))
        coordinator.acceptPose(classification(PostureState.LEAN_LEFT, 1_100, PosePostureClassifier.SOURCE))
        coordinator.acceptGeometry(classification(PostureState.HEAD_DOWN, 2_000, GeometryPostureClassifier.SOURCE))
        coordinator.acceptGeometry(classification(PostureState.NORMAL, 3_700, GeometryPostureClassifier.SOURCE))

        assertEquals(listOf(PostureState.NORMAL, PostureState.LEAN_LEFT, PostureState.NORMAL), updates.map { it.classification.state })
        assertEquals(listOf(PostureSource.BLE_GEOMETRY, PostureSource.MEDIAPIPE_POSE_LITE, PostureSource.BLE_GEOMETRY), sources)
    }

    @Test
    fun explicitPoseUnavailabilityAllowsImmediateGeometryFallback() {
        val updates = mutableListOf<PostureIngestionUpdate>()
        val coordinator = PostureSourceCoordinator(onUpdate = updates::add)
        coordinator.acceptPose(classification(PostureState.NORMAL, 1_000, PosePostureClassifier.SOURCE))
        coordinator.poseUnavailable()
        coordinator.acceptGeometry(classification(PostureState.HEAD_DOWN, 1_100, GeometryPostureClassifier.SOURCE))
        assertEquals(listOf(PostureState.NORMAL, PostureState.HEAD_DOWN), updates.map { it.classification.state })
    }

    @Test
    fun bleTransportUpdatesPreserveSelectedPoseAndThermalProjection() {
        PostureRuntimeStore.reset()
        val pose = classification(PostureState.NORMAL, 1_000, PosePostureClassifier.SOURCE)
        PostureRuntimeStore.updateLocalPose(LocalPosePhase.LIVE, PostureThermalState.MODERATE, "2 FPS")
        PostureRuntimeStore.updateSelectedSource(PostureSource.MEDIAPIPE_POSE_LITE, pose)
        PostureRuntimeStore.update(
            PostureRuntimeSnapshot(
                phase = PostureRuntimePhase.LIVE,
                detail = "BLE OK",
                mtu = 517,
                classification = classification(PostureState.HEAD_DOWN, 1_100, GeometryPostureClassifier.SOURCE),
            ),
        )
        val snapshot = PostureRuntimeStore.snapshot
        assertEquals(PostureSource.MEDIAPIPE_POSE_LITE, snapshot.source)
        assertEquals(PostureState.NORMAL, snapshot.classification?.state)
        assertEquals(PostureThermalState.MODERATE, snapshot.thermalState)
        assertEquals(517, snapshot.mtu)
        PostureRuntimeStore.reset()
    }

    private fun classification(state: PostureState, atMs: Long, source: String) =
        PostureClassification(state, atMs, 0.9, source)
}
