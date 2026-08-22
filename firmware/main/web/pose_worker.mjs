import {FilesetResolver, PoseLandmarker} from "/assets/vision_bundle.mjs?v=tasks-vision-1.0.1-classic-1";
import {PosePostureClassifier, POSE_CLASSIFIER_VERSION} from "/assets/pose_classifier.mjs?v=classifier-2";

const MODEL_URL = "/assets/pose_landmarker_lite-v2.task";
const MODEL_SHA256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a";
const PROFILE = "ov2640-qvga-canonical-v1";

let landmarker = null;
let classifier = null;
let lastInferenceAt = 0;
let lastFrameWallMs = 0;
let inferenceCount = 0;
let inferenceFps = 0;

async function initialize(savedBaseline) {
  // Version the loader directory because firmware assets are served immutable.
  // The enclosing classic bootstrap supplies MediaPipe's required globals.
  const fileset = await FilesetResolver.forVisionTasks("/assets/wasm-classic-v1", false);
  landmarker = await PoseLandmarker.createFromOptions(fileset, {
    baseOptions: {
      modelAssetPath: MODEL_URL,
      delegate: "CPU",
    },
    runningMode: "VIDEO",
    numPoses: 1,
    minPoseDetectionConfidence: 0.55,
    minPosePresenceConfidence: 0.55,
    minTrackingConfidence: 0.55,
    outputSegmentationMasks: false,
  });
  classifier = new PosePostureClassifier({
    fingerprint: `${PROFILE}:${MODEL_SHA256}`,
    baseline: savedBaseline,
  });
  postMessage({
    type: "ready",
    modelSha256: MODEL_SHA256,
    classifierVersion: POSE_CLASSIFIER_VERSION,
  });
}

function serializeLandmarks(result) {
  const pose = result?.landmarks?.[0];
  if (!pose) return null;
  return pose.map(point => ({
    x: point.x,
    y: point.y,
    z: point.z,
    visibility: point.visibility,
    presence: point.presence,
  }));
}

onmessage = async event => {
  const message = event.data || {};
  if (message.type === "init") {
    try {
      await initialize(message.baseline ?? null);
    } catch (error) {
      postMessage({type: "error", stage: "initialize", error: String(error?.message || error)});
    }
    return;
  }
  if (message.type === "reset") {
    classifier?.reset();
    postMessage({type: "baseline", baseline: null});
    return;
  }
  if (message.type !== "frame" || !landmarker || !classifier || !message.bitmap) return;

  const startedAt = performance.now();
  try {
    const timestampMs = Math.max(Number(message.captureUptimeMs) || 0, lastInferenceAt + 1);
    lastInferenceAt = timestampMs;
    lastFrameWallMs = performance.now();
    const result = landmarker.detectForVideo(message.bitmap, timestampMs);
    const landmarks = serializeLandmarks(result);
    const posture = classifier.observe({
      sequence: message.sequence,
      timestampMs,
      landmarks,
      faceMeta: message.faceMeta ?? null,
    });
    inferenceCount += 1;
    const elapsedMs = performance.now() - startedAt;
    inferenceFps = inferenceFps === 0 ? 1000 / Math.max(1, elapsedMs)
      : inferenceFps * 0.85 + (1000 / Math.max(1, elapsedMs)) * 0.15;
    postMessage({
      type: "result",
      sequence: message.sequence,
      landmarks,
      posture,
      inferenceMs: elapsedMs,
      inferenceCount,
      inferenceFps,
    });
    if (posture.baselineChanged) postMessage({type: "baseline", baseline: classifier.persistedBaseline()});
  } catch (error) {
    postMessage({type: "error", stage: "inference", error: String(error?.message || error), sequence: message.sequence});
  } finally {
    message.bitmap.close();
    postMessage({type: "idle"});
  }
};

setInterval(() => {
  if (!classifier || !lastFrameWallMs) return;
  const elapsed = performance.now() - lastFrameWallMs;
  if (elapsed <= 3000) return;
  const stale = classifier.stale(lastInferenceAt + elapsed);
  if (stale && performance.now() - (self.__lastStaleNotice || 0) >= 1000) {
    self.__lastStaleNotice = performance.now();
    postMessage({type: "stale", posture: stale});
  }
}, 1000);
