// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

/**
 * Owns the one session-level tracker. Pose is preferred while it is fresh;
 * BLE geometry remains a no-network/no-model fallback and is never double-counted.
 */
class PostureSourceCoordinator(
    private val tracker: PostureInsightTracker = PostureInsightTracker(),
    private val poseFreshnessMs: Long = 2_500L,
    private val onUpdate: (PostureIngestionUpdate) -> Unit,
    private val onSource: (PostureSource, PostureClassification?) -> Unit = { _, _ -> },
) {
    private var activeSource = PostureSource.NONE
    private var lastPoseObservedAtMs: Long? = null
    private var lastPublishedAtMs = -1L

    @Synchronized
    fun acceptGeometry(classification: PostureClassification) {
        val poseAt = lastPoseObservedAtMs
        if (poseAt != null && classification.observedAtMs - poseAt <= poseFreshnessMs) return
        if (poseAt != null) lastPoseObservedAtMs = null
        publish(PostureSource.BLE_GEOMETRY, classification)
    }

    @Synchronized
    fun acceptPose(classification: PostureClassification) {
        lastPoseObservedAtMs = classification.observedAtMs
        publish(PostureSource.MEDIAPIPE_POSE_LITE, classification)
    }

    @Synchronized
    fun poseUnavailable() {
        lastPoseObservedAtMs = null
        if (activeSource == PostureSource.MEDIAPIPE_POSE_LITE) {
            tracker.pause()
            activeSource = PostureSource.NONE
            onSource(PostureSource.NONE, null)
        }
    }

    @Synchronized
    fun reset() {
        tracker.reset()
        activeSource = PostureSource.NONE
        lastPoseObservedAtMs = null
        lastPublishedAtMs = -1L
        onSource(PostureSource.NONE, null)
    }

    private fun publish(source: PostureSource, classification: PostureClassification) {
        if (classification.observedAtMs < lastPublishedAtMs) return
        if (source != activeSource) {
            tracker.pause()
            activeSource = source
        }
        lastPublishedAtMs = classification.observedAtMs
        val insights = tracker.observe(classification.state, classification.observedAtMs)
        onUpdate(
            PostureIngestionUpdate(
                classification = classification,
                summaries = tracker.summaries(classification.observedAtMs),
                insights = insights,
            ),
        )
        onSource(source, classification)
    }
}
