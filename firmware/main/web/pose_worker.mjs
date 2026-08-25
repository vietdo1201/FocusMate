// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
import {FaceLandmarker, FilesetResolver, PoseLandmarker} from "/assets/vision_bundle.mjs?v=tasks-vision-1.0.1-classic-1";
import {PosePostureClassifier, POSE_CLASSIFIER_VERSION} from "/assets/pose_classifier.mjs?v=classifier-4";
import {YawnClassifier, YAWN_CLASSIFIER_VERSION} from "/assets/yawn_classifier.mjs?v=yawn-3";

const POSE_MODEL_URL = "/assets/pose_landmarker_lite-v2.task";
const FACE_MODEL_URL = "/assets/face_landmarker-v1.task";
const POSE_MODEL_SHA256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a";
const FACE_MODEL_SHA256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff";
const PROFILE = "ov2640-qvga-canonical-v1";
const MOUTH_INDICES = [61,146,91,181,84,17,314,405,321,375,291,308,324,318,402,317,14,87,178,88,95,78,191,80,81,82,13,312,311,310,415];
const FACE_SCALE_LANDMARKS = Object.freeze({leftEye: 33, rightEye: 263, top: 10, bottom: 152, left: 234, right: 454});
const FACE_GEOMETRY_MAX_AGE_MS = 700;

let poseLandmarker = null;
let faceLandmarker = null;
let poseClassifier = null;
let yawnClassifier = null;
let lastInferenceAt = 0;
let lastFaceInferenceAt = -Infinity;
let lastFrameWallMs = 0;
let inferenceCount = 0;
let inferenceFps = 0;
let lastYawn = null;
let lastMouthLandmarks = null;
let lastFaceGeometry = null;
let smoothedDisplayLandmarks = null;
let lastDisplayLandmarkAt = 0;

const CORE_LANDMARKS = new Set([0, 2, 5, 7, 8, 11, 12, 23, 24]);
const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
const pointQuality = point => Math.min(point?.visibility ?? 1, point?.presence ?? 1);

async function initialize(savedBaseline, savedYawnBaseline) {
  // Version the loader directory because firmware assets are served immutable.
  // The enclosing classic bootstrap supplies MediaPipe's required globals.
  // The asset server maps both MediaPipe's SIMD and no-SIMD filenames to the
  // bundled compatibility runtime. This is required by older Android Chrome,
  // whose SIMD probe selects the `_nosimd` filenames.
  const nativeSimd = await FilesetResolver.isSimdSupported(false);
  const fileset = await FilesetResolver.forVisionTasks("/assets/wasm-compatible-v1", false);
  poseLandmarker = await PoseLandmarker.createFromOptions(fileset, {
    baseOptions: {
      modelAssetPath: POSE_MODEL_URL,
      delegate: "CPU",
    },
    runningMode: "VIDEO",
    numPoses: 1,
    minPoseDetectionConfidence: 0.55,
    minPosePresenceConfidence: 0.55,
    minTrackingConfidence: 0.65,
    outputSegmentationMasks: false,
  });
  poseClassifier = new PosePostureClassifier({
    fingerprint: `${PROFILE}:${POSE_MODEL_SHA256}`,
    baseline: savedBaseline,
  });
  let yawnAvailable = false;
  try {
    faceLandmarker = await FaceLandmarker.createFromOptions(fileset, {
      baseOptions: {
        modelAssetPath: FACE_MODEL_URL,
        delegate: "CPU",
      },
      runningMode: "VIDEO",
      numFaces: 1,
      minFaceDetectionConfidence: 0.6,
      minFacePresenceConfidence: 0.6,
      minTrackingConfidence: 0.6,
      outputFaceBlendshapes: true,
      outputFacialTransformationMatrixes: false,
    });
    yawnClassifier = new YawnClassifier({
      fingerprint: `${PROFILE}:${FACE_MODEL_SHA256}`,
      baseline: savedYawnBaseline,
    });
    yawnAvailable = true;
  } catch (error) {
    faceLandmarker = null;
    yawnClassifier = null;
    postMessage({type: "yawnUnavailable", error: String(error?.message || error)});
  }
  postMessage({
    type: "ready",
    modelSha256: POSE_MODEL_SHA256,
    faceModelSha256: FACE_MODEL_SHA256,
    classifierVersion: POSE_CLASSIFIER_VERSION,
    yawnClassifierVersion: YAWN_CLASSIFIER_VERSION,
    yawnAvailable,
    wasmProfile: "nosimd-compatible",
    nativeSimd,
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

// MediaPipe VIDEO mode tracks poses between detections, but its displayed
// elbow/wrist coordinates can still jump on a 5 Hz QVGA stream. Smooth only
// the visualization; posture classification continues to use raw landmarks
// so this filter cannot hide a real posture transition or alter calibration.
function smoothLandmarksForDisplay(landmarks, timestampMs) {
  if (!Array.isArray(landmarks)) {
    if (timestampMs - lastDisplayLandmarkAt > 600) smoothedDisplayLandmarks = null;
    return null;
  }
  const elapsedMs = timestampMs - lastDisplayLandmarkAt;
  if (!Array.isArray(smoothedDisplayLandmarks) || elapsedMs <= 0 || elapsedMs > 700) {
    smoothedDisplayLandmarks = landmarks.map(point => ({...point}));
    lastDisplayLandmarkAt = timestampMs;
    return smoothedDisplayLandmarks;
  }
  const timeScale = clamp(elapsedMs / 200, 0.5, 2.0);
  smoothedDisplayLandmarks = landmarks.map((point, index) => {
    const previous = smoothedDisplayLandmarks[index];
    if (!previous || !point) return point ? {...point} : point;
    const motion = Math.hypot(point.x - previous.x, point.y - previous.y);
    const base = CORE_LANDMARKS.has(index) ? 0.34 : 0.20;
    let alpha = clamp(base + motion * 3.5, base, 0.82);
    alpha = 1 - Math.pow(1 - alpha, timeScale);
    if (pointQuality(point) < 0.6) alpha *= 0.45;
    return {
      ...point,
      x: previous.x + (point.x - previous.x) * alpha,
      y: previous.y + (point.y - previous.y) * alpha,
      z: previous.z + (point.z - previous.z) * alpha,
    };
  });
  lastDisplayLandmarkAt = timestampMs;
  return smoothedDisplayLandmarks;
}

function serializeMouthLandmarks(result) {
  const face = result?.faceLandmarks?.[0];
  if (!face) return null;
  return MOUTH_INDICES.map(index => ({index, x: face[index].x, y: face[index].y, z: face[index].z}));
}

function serializeFaceGeometry(result, observedAtMs) {
  const face = result?.faceLandmarks?.[0];
  if (!face) return null;
  const point = name => face[FACE_SCALE_LANDMARKS[name]];
  const leftEye = point("leftEye"), rightEye = point("rightEye"), top = point("top"),
    bottom = point("bottom"), left = point("left"), right = point("right");
  if ([leftEye, rightEye, top, bottom, left, right].some(value =>
    !value || !Number.isFinite(value.x) || !Number.isFinite(value.y))) return null;
  const eyeScale = Math.hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y);
  if (!Number.isFinite(eyeScale) || eyeScale <= 0.005) return null;
  return {
    observedAtMs,
    eyeScale,
    cx: (left.x + right.x) / 2,
    cy: (top.y + bottom.y) / 2,
    width: Math.hypot(left.x - right.x, left.y - right.y),
    height: Math.hypot(top.x - bottom.x, top.y - bottom.y),
    leftEye: {x: leftEye.x, y: leftEye.y},
    rightEye: {x: rightEye.x, y: rightEye.y},
  };
}

function jawOpenScore(result) {
  const categories = result?.faceBlendshapes?.[0]?.categories || [];
  const match = categories.find(category => String(category.categoryName || "").toLowerCase() === "jawopen");
  return match ? Number(match.score) : null;
}

onmessage = async event => {
  const message = event.data || {};
  if (message.type === "init") {
    try {
      await initialize(message.baseline ?? null, message.yawnBaseline ?? null);
    } catch (error) {
      postMessage({type: "error", stage: "initialize", error: String(error?.message || error)});
    }
    return;
  }
  if (message.type === "reset") {
    poseClassifier?.reset();
    postMessage({type: "baseline", baseline: null});
    return;
  }
  if (message.type === "captureBaseline") {
    poseClassifier?.captureBaseline();
    postMessage({type: "baseline", baseline: null});
    return;
  }
  if (message.type === "resetYawn") {
    yawnClassifier?.reset();
    lastYawn = null;
    lastMouthLandmarks = null;
    lastFaceGeometry = null;
    postMessage({type: "yawnBaseline", baseline: null});
    return;
  }
  if (message.type !== "frame" || !poseLandmarker || !poseClassifier || !message.bitmap) return;

  const startedAt = performance.now();
  try {
    const timestampMs = Math.max(Number(message.captureUptimeMs) || 0, lastInferenceAt + 1);
    lastInferenceAt = timestampMs;
    lastFrameWallMs = performance.now();
    const result = poseLandmarker.detectForVideo(message.bitmap, timestampMs);
    const landmarks = serializeLandmarks(result);
    const displayLandmarks = smoothLandmarksForDisplay(landmarks, timestampMs);
    if (faceLandmarker && yawnClassifier && timestampMs - lastFaceInferenceAt >= 350) {
      lastFaceInferenceAt = timestampMs;
      const faceResult = faceLandmarker.detectForVideo(message.bitmap, timestampMs);
      const face = faceResult?.faceLandmarks?.[0] || null;
      lastMouthLandmarks = serializeMouthLandmarks(faceResult);
      lastFaceGeometry = serializeFaceGeometry(faceResult, timestampMs);
      lastYawn = yawnClassifier.observe({
        sequence: message.sequence,
        timestampMs,
        landmarks: face,
        jawOpen: jawOpenScore(faceResult),
      });
      if (lastYawn.baselineChanged) postMessage({type: "yawnBaseline", baseline: yawnClassifier.persistedBaseline()});
    }
    const faceGeometry = lastFaceGeometry && timestampMs - lastFaceGeometry.observedAtMs <= FACE_GEOMETRY_MAX_AGE_MS
      ? {...lastFaceGeometry, ageMs: timestampMs - lastFaceGeometry.observedAtMs} : null;
    const posture = poseClassifier.observe({
      sequence: message.sequence,
      timestampMs,
      landmarks,
      faceMeta: message.faceMeta ?? null,
      faceGeometry,
    });
    inferenceCount += 1;
    const elapsedMs = performance.now() - startedAt;
    inferenceFps = inferenceFps === 0 ? 1000 / Math.max(1, elapsedMs)
      : inferenceFps * 0.85 + (1000 / Math.max(1, elapsedMs)) * 0.15;
    const yawnForMessage = lastYawn;
    postMessage({
      type: "result",
      sequence: message.sequence,
      captureUptimeMs: message.captureUptimeMs,
      landmarks: displayLandmarks,
      posture,
      yawn: yawnForMessage,
      mouthLandmarks: lastMouthLandmarks,
      faceGeometry,
      inferenceMs: elapsedMs,
      inferenceCount,
      inferenceFps,
    });
    // A face inference can be reused by several faster Pose results. Event
    // flags are edge-triggered and must be emitted only once, otherwise one
    // physical yawn creates several Web outbox entries.
    if (lastYawn?.eventJustCounted || lastYawn?.alertJustTriggered || lastYawn?.persistenceChanged) {
      lastYawn = {
        ...lastYawn,
        eventJustCounted: false,
        alertJustTriggered: false,
        persistenceChanged: false,
      };
    }
    if (posture.baselineChanged) postMessage({type: "baseline", baseline: poseClassifier.persistedBaseline()});
  } catch (error) {
    postMessage({type: "error", stage: "inference", error: String(error?.message || error), sequence: message.sequence});
  } finally {
    message.bitmap.close();
    postMessage({type: "idle"});
  }
};

setInterval(() => {
  if (!poseClassifier || !lastFrameWallMs) return;
  const elapsed = performance.now() - lastFrameWallMs;
  if (elapsed <= 3000) return;
  const stale = poseClassifier.stale(lastInferenceAt + elapsed);
  const yawnStale = yawnClassifier?.stale(lastInferenceAt + elapsed) ?? null;
  if (stale && performance.now() - (self.__lastStaleNotice || 0) >= 1000) {
    self.__lastStaleNotice = performance.now();
    postMessage({type: "stale", posture: stale, yawn: yawnStale});
  }
}, 1000);
