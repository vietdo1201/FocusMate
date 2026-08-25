// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
export const POSTURE_STATES = Object.freeze([
  "NORMAL",
  "HEAD_DOWN",
  "LEAN_LEFT",
  "LEAN_RIGHT",
  "TOO_CLOSE",
  "SLUMPED",
  "FACE_MISSING",
  "UNKNOWN",
]);

export const POSE_CLASSIFIER_VERSION = 3;
export const REQUIRED_BASELINE_SAMPLES = 20;
export const REQUIRED_BASELINE_MS = 5000;
export const STALE_MS = 3000;
const LABEL_DEBOUNCE_MS = 1000;
const INVALID_HOLD_MS = 800;
const SCALE_SOURCE_NAMES = Object.freeze(["poseEyeScale", "faceEyeScale", "espBboxScale"]);
const SCALE_ENTER_RATIO = 1.35;
const SCALE_EXIT_RATIO = 1.20;

const LANDMARK = Object.freeze({
  NOSE: 0,
  LEFT_EYE: 2,
  RIGHT_EYE: 5,
  LEFT_EAR: 7,
  RIGHT_EAR: 8,
  LEFT_SHOULDER: 11,
  RIGHT_SHOULDER: 12,
  LEFT_HIP: 23,
  RIGHT_HIP: 24,
});

const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
const median = values => {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};
const mad = values => {
  const center = median(values);
  return median(values.map(value => Math.abs(value - center)));
};
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
const midpoint = (a, b) => ({x: (a.x + b.x) / 2, y: (a.y + b.y) / 2});
const visibility = point => Math.min(point?.visibility ?? 1, point?.presence ?? 1);
const normalizedAngle = degrees => {
  let value = degrees;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
};
const fingerprintOf = value => typeof value === "string" && value.length >= 8 ? value : "unknown-profile";

export function canonicalizePoseLandmarks(landmarks, {mirrorX = false, flipY = false} = {}) {
  if (!Array.isArray(landmarks)) return landmarks;
  return landmarks.map(point => point ? {
    ...point,
    x: mirrorX ? 1 - point.x : point.x,
    y: flipY ? 1 - point.y : point.y,
  } : point);
}

function featureSummary(samples, name) {
  const values = samples.map(sample => sample[name]).filter(Number.isFinite);
  return {value: median(values), noise: mad(values)};
}

function q(value, fallback = 0) {
  return Number.isFinite(value) ? value : fallback;
}

/**
 * Converts MediaPipe's anatomical landmarks into subject-relative geometry.
 * Positive lateral values always mean the person's left, independent of the
 * display mirror setting. Classification must receive the unmirrored image.
 */
export function extractPoseFeatures(landmarks, faceMeta = null, faceGeometry = null) {
  if (!Array.isArray(landmarks) || landmarks.length < 25) return null;
  const nose = landmarks[LANDMARK.NOSE];
  const leftEye = landmarks[LANDMARK.LEFT_EYE];
  const rightEye = landmarks[LANDMARK.RIGHT_EYE];
  const leftShoulder = landmarks[LANDMARK.LEFT_SHOULDER];
  const rightShoulder = landmarks[LANDMARK.RIGHT_SHOULDER];
  const required = [nose, leftEye, rightEye, leftShoulder, rightShoulder];
  if (required.some(point => !point || !Number.isFinite(point.x) || !Number.isFinite(point.y))) return null;

  const quality = Math.min(...required.map(visibility));
  const shoulderWidth = distance(leftShoulder, rightShoulder);
  if (!Number.isFinite(shoulderWidth) || shoulderWidth < 0.04) return null;
  const shoulderMid = midpoint(leftShoulder, rightShoulder);
  const subjectLeft = {
    x: (leftShoulder.x - rightShoulder.x) / shoulderWidth,
    y: (leftShoulder.y - rightShoulder.y) / shoulderWidth,
  };
  const shoulderAngleDeg = Math.atan2(subjectLeft.y, subjectLeft.x) * 180 / Math.PI;
  const eyeWidth = distance(leftEye, rightEye);
  const eyeAngleDeg = eyeWidth > 0.005
    ? Math.atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x) * 180 / Math.PI
    : shoulderAngleDeg;
  const headRollDeg = normalizedAngle(eyeAngleDeg - shoulderAngleDeg);
  const eyeMid = midpoint(leftEye, rightEye);
  const lateralHead = ((nose.x - shoulderMid.x) * subjectLeft.x +
    (nose.y - shoulderMid.y) * subjectLeft.y) / shoulderWidth;
  const headHeight = distance(nose, shoulderMid) / shoulderWidth;
  const eyeHeight = distance(eyeMid, shoulderMid) / shoulderWidth;

  const leftHip = landmarks[LANDMARK.LEFT_HIP];
  const rightHip = landmarks[LANDMARK.RIGHT_HIP];
  const hasHips = leftHip && rightHip && visibility(leftHip) >= 0.5 && visibility(rightHip) >= 0.5;
  let torsoLeanDeg = shoulderAngleDeg;
  let torsoLength = null;
  if (hasHips) {
    const hipMid = midpoint(leftHip, rightHip);
    torsoLength = distance(hipMid, shoulderMid);
    if (torsoLength > 0.02) {
      const lateral = ((shoulderMid.x - hipMid.x) * subjectLeft.x +
        (shoulderMid.y - hipMid.y) * subjectLeft.y) / torsoLength;
      torsoLeanDeg = Math.asin(clamp(lateral, -1, 1)) * 180 / Math.PI;
    }
  }

  let facePitch = null;
  let espBboxScale = null;
  if (faceMeta?.detected) {
    if (Number.isFinite(faceMeta.width) && Number.isFinite(faceMeta.height)) {
      espBboxScale = Math.sqrt(Math.max(0.000001, faceMeta.width * faceMeta.height));
    }
    const points = faceMeta.points;
    if (points?.leftEye && points?.rightEye && points?.leftMouth && points?.rightMouth && points?.nose) {
      const faceEyeMid = midpoint(points.leftEye, points.rightEye);
      const mouthMid = midpoint(points.leftMouth, points.rightMouth);
      const eyeMouth = distance(faceEyeMid, mouthMid);
      if (eyeMouth > 0.005) facePitch = (points.nose.y - faceEyeMid.y) / eyeMouth;
    }
  }

  return {
    quality,
    headRollDeg,
    torsoLeanDeg,
    shoulderAngleDeg,
    lateralHead,
    headHeight,
    eyeHeight,
    facePitch,
    // Keep faceScale for v2 fixture/export compatibility. Classification uses
    // the explicit sources below and never trusts a single enlarged bbox.
    faceScale: espBboxScale ?? eyeWidth,
    poseEyeScale: eyeWidth > 0.005 ? eyeWidth : null,
    faceEyeScale: Number.isFinite(faceGeometry?.eyeScale) && faceGeometry.ageMs <= 700
      ? faceGeometry.eyeScale : null,
    espBboxScale,
    shoulderWidth,
    torsoLength,
    hasHips: Boolean(hasHips),
  };
}

function validPostureBaseline(candidate, fingerprint) {
  if (!candidate || ![2, POSE_CLASSIFIER_VERSION].includes(candidate.version) || candidate.fingerprint !== fingerprint) return false;
  const required = ["headRollDeg", "torsoLeanDeg", "lateralHead", "headHeight", "eyeHeight", "faceScale"];
  return required.every(name => Number.isFinite(candidate.values?.[name]) && Number.isFinite(candidate.noise?.[name]));
}

function restoreBaseline(candidate, fingerprint) {
  if (!validPostureBaseline(candidate, fingerprint)) return null;
  const restored = JSON.parse(JSON.stringify(candidate));
  restored.version = POSE_CLASSIFIER_VERSION;
  restored.values ||= {};
  restored.noise ||= {};
  if (candidate.version === 2 && Number.isFinite(candidate.values.faceScale)) {
    restored.values.espBboxScale = candidate.values.faceScale;
    restored.noise.espBboxScale = q(candidate.noise.faceScale);
    restored.migratedFromVersion = 2;
  }
  restored.scaleReady = SCALE_SOURCE_NAMES.filter(name =>
    Number.isFinite(restored.values[name]) && restored.values[name] > 0).length >= 2;
  return restored;
}

export class PosePostureClassifier {
  constructor({fingerprint, baseline = null} = {}) {
    this.fingerprint = fingerprintOf(fingerprint);
    this.baseline = restoreBaseline(baseline, this.fingerprint);
    this.samples = [];
    this.baselineStartedAt = 0;
    this.baselineLastAt = 0;
    this.calibrationOutlier = null;
    this.manualCalibration = false;
    this.lastSequence = null;
    this.lastTimestampMs = 0;
    this.lastFeatures = null;
    this.missingCount = 0;
    this.candidate = "UNKNOWN";
    this.candidateSinceMs = 0;
    this.state = "UNKNOWN";
    this.stateSinceMs = 0;
    this.lastUsableAtMs = 0;
    this.slumpedSinceMs = 0;
    this.normalSinceMs = 0;
    this.adaptationSamples = [];
    this.scaleSamples = [];
    this.scaleLastAt = 0;
    this.scaleLastFeatures = null;
  }

  reset() {
    this.baseline = null;
    this.samples = [];
    this.baselineStartedAt = 0;
    this.baselineLastAt = 0;
    this.calibrationOutlier = null;
    this.manualCalibration = false;
    this.lastSequence = null;
    this.lastTimestampMs = 0;
    this.lastFeatures = null;
    this.candidate = "UNKNOWN";
    this.candidateSinceMs = 0;
    this.state = "UNKNOWN";
    this.stateSinceMs = 0;
    this.slumpedSinceMs = 0;
    this.normalSinceMs = 0;
    this.lastUsableAtMs = 0;
    this.adaptationSamples = [];
    this.scaleSamples = [];
    this.scaleLastAt = 0;
    this.scaleLastFeatures = null;
  }

  captureBaseline() {
    this.reset();
    this.manualCalibration = true;
  }

  persistedBaseline() {
    return this.baseline ? JSON.parse(JSON.stringify(this.baseline)) : null;
  }

  observe({sequence, timestampMs, landmarks, faceMeta, faceGeometry}) {
    const features = extractPoseFeatures(landmarks, faceMeta, faceGeometry);
    return this.observeFeatures({sequence, timestampMs, features, faceDetected: Boolean(faceMeta?.detected)});
  }

  observeFeatures({sequence, timestampMs, features, faceDetected = false}) {
    if (!Number.isInteger(sequence) || !Number.isFinite(timestampMs)) return this.snapshot("invalid_frame");
    if (this.lastSequence === sequence) return this.snapshot("duplicate_frame");
    this.lastSequence = sequence;
    this.lastTimestampMs = timestampMs;

    if (!features || features.quality < 0.7) {
      this.missingCount += 1;
      const raw = !features && !faceDetected ? "FACE_MISSING" : "UNKNOWN";
      if (this.baseline && raw === "UNKNOWN" && this.lastUsableAtMs &&
          timestampMs - this.lastUsableAtMs <= INVALID_HOLD_MS) {
        return this.snapshot(features ? "low_visibility_hold" : "pose_missing_hold", 0, features, raw);
      }
      return this.commit(raw, timestampMs, 0, features, features ? "low_visibility" : "pose_missing");
    }
    this.missingCount = 0;
    this.lastUsableAtMs = timestampMs;

    if (!this.baseline) {
      const calibrationReason = this.collectBaseline(features, timestampMs);
      return this.commit("UNKNOWN", timestampMs, features.quality, features,
        this.baseline ? "baseline_ready" : calibrationReason);
    }

    const scaleBaselineChanged = this.collectScaleBaseline(features, timestampMs);
    const evaluatedFeatures = {...features};
    const classification = this.classify(evaluatedFeatures, timestampMs);
    const result = this.commit(classification.state, timestampMs, classification.confidence,
      evaluatedFeatures, classification.reason, classification.rawState);
    if (scaleBaselineChanged) result.baselineChanged = true;
    this.maybeAdapt(features, timestampMs, result.state);
    return result;
  }

  stale(nowMs) {
    if (!this.lastTimestampMs || nowMs - this.lastTimestampMs <= STALE_MS) return null;
    this.candidate = "UNKNOWN";
    this.candidateSinceMs = nowMs - LABEL_DEBOUNCE_MS;
    this.state = "UNKNOWN";
    this.stateSinceMs = nowMs;
    return this.snapshot("stale");
  }

  collectBaseline(features, timestampMs) {
    if (!Number.isFinite(features.headHeight) || features.headHeight < 0.25 || features.headHeight > 3 ||
        !Number.isFinite(features.shoulderWidth) || features.shoulderWidth < 0.04) {
      return "invalid_geometry";
    }
    if (this.baselineLastAt &&
        (timestampMs <= this.baselineLastAt || timestampMs - this.baselineLastAt > 1500)) {
      this.samples = [];
      this.baselineStartedAt = 0;
      this.baselineLastAt = 0;
      this.lastFeatures = null;
      this.calibrationOutlier = null;
    }
    if (this.lastFeatures && !this.isStablePair(features, this.lastFeatures)) {
      const previousOutlier = this.calibrationOutlier;
      this.calibrationOutlier = {features, timestampMs};
      if (!previousOutlier || timestampMs - previousOutlier.timestampMs > 1500 ||
          !this.isStablePair(features, previousOutlier.features)) return "moving_too_much";
      // Two consecutive samples around a new position start a new stable window;
      // a single noisy inference never destroys good history.
      this.samples = [{...previousOutlier.features, _timestampMs: previousOutlier.timestampMs}];
      this.baselineStartedAt = previousOutlier.timestampMs;
      this.baselineLastAt = previousOutlier.timestampMs;
      this.lastFeatures = previousOutlier.features;
    }
    this.calibrationOutlier = null;
    if (!this.baselineStartedAt) this.baselineStartedAt = timestampMs;
    this.baselineLastAt = timestampMs;
    this.lastFeatures = features;
    this.samples.push({...features, _timestampMs: timestampMs});
    if (this.samples.length > 60) this.samples.shift();
    if (this.samples.length < REQUIRED_BASELINE_SAMPLES ||
        this.samples.at(-1)._timestampMs - this.samples[0]._timestampMs < REQUIRED_BASELINE_MS) {
      return this.manualCalibration ? "manual_calibrating" : "auto_calibrating";
    }

    const names = ["headRollDeg", "torsoLeanDeg", "shoulderAngleDeg", "lateralHead", "headHeight",
      "eyeHeight", "facePitch", "faceScale", "poseEyeScale", "faceEyeScale", "espBboxScale",
      "shoulderWidth", "torsoLength"];
    const values = {}, noise = {};
    for (const name of names) {
      const summary = featureSummary(this.samples, name);
      values[name] = summary.value;
      noise[name] = summary.noise;
    }
    this.baseline = {
      version: POSE_CLASSIFIER_VERSION,
      fingerprint: this.fingerprint,
      createdAtMs: timestampMs,
      values,
      noise,
      scaleReady: SCALE_SOURCE_NAMES.filter(name =>
        this.samples.filter(sample => Number.isFinite(sample[name])).length >= REQUIRED_BASELINE_SAMPLES &&
        Number.isFinite(values[name]) && values[name] > 0).length >= 2,
    };
    this.samples = [];
    this.baselineStartedAt = 0;
    this.baselineLastAt = 0;
    this.lastFeatures = null;
    this.manualCalibration = false;
    return "baseline_ready";
  }

  isStablePair(current, previous) {
    return Math.abs(current.headRollDeg - previous.headRollDeg) <= 4 &&
      Math.abs(current.torsoLeanDeg - previous.torsoLeanDeg) <= 4 &&
      Math.abs(current.lateralHead - previous.lateralHead) <= 0.04 &&
      Math.abs(current.headHeight - previous.headHeight) <= 0.08;
  }

  collectScaleBaseline(features, timestampMs) {
    if (!this.baseline || this.baseline.scaleReady) return false;
    const validSources = SCALE_SOURCE_NAMES.filter(name => Number.isFinite(features[name]) && features[name] > 0);
    if (validSources.length < 2 || (this.scaleLastFeatures && !this.isStablePair(features, this.scaleLastFeatures))) {
      this.scaleSamples = [];
      this.scaleLastAt = 0;
      this.scaleLastFeatures = null;
      return false;
    }
    if (this.scaleLastAt && (timestampMs <= this.scaleLastAt || timestampMs - this.scaleLastAt > 1500)) {
      this.scaleSamples = [];
    }
    this.scaleLastAt = timestampMs;
    this.scaleLastFeatures = features;
    this.scaleSamples.push({...features, _timestampMs: timestampMs});
    if (this.scaleSamples.length > 60) this.scaleSamples.shift();
    if (this.scaleSamples.length < REQUIRED_BASELINE_SAMPLES ||
        this.scaleSamples.at(-1)._timestampMs - this.scaleSamples[0]._timestampMs < REQUIRED_BASELINE_MS) return false;
    let ready = 0;
    for (const name of SCALE_SOURCE_NAMES) {
      const samples = this.scaleSamples.filter(sample => Number.isFinite(sample[name]));
      if (samples.length < REQUIRED_BASELINE_SAMPLES) continue;
      const summary = featureSummary(samples, name);
      if (!Number.isFinite(summary.value) || summary.value <= 0) continue;
      this.baseline.values[name] = summary.value;
      this.baseline.noise[name] = summary.noise;
      ready += 1;
    }
    this.baseline.scaleReady = ready >= 2;
    if (!this.baseline.scaleReady) return false;
    delete this.baseline.migratedFromVersion;
    this.scaleSamples = [];
    this.scaleLastAt = 0;
    this.scaleLastFeatures = null;
    return true;
  }

  scaleEvidence(features) {
    const threshold = this.state === "TOO_CLOSE" ? SCALE_EXIT_RATIO : SCALE_ENTER_RATIO;
    const sources = SCALE_SOURCE_NAMES.map(name => {
      const current = features?.[name];
      const baseline = this.baseline?.values?.[name];
      const ratio = Number.isFinite(current) && Number.isFinite(baseline) && baseline > 0
        ? current / baseline : null;
      return {name, ratio, valid: Number.isFinite(ratio), close: Number.isFinite(ratio) && ratio >= threshold};
    });
    const valid = sources.filter(source => source.valid);
    const close = valid.filter(source => source.close);
    const agreeingRatio = close.map(source => source.ratio).sort((a, b) => b - a)[1] ?? null;
    return {
      ready: Boolean(this.baseline?.scaleReady),
      threshold,
      requiredVotes: 2,
      validVotes: valid.length,
      closeVotes: close.length,
      consensus: Boolean(this.baseline?.scaleReady) && valid.length >= 2 && close.length >= 2,
      agreeingRatio,
      sources,
    };
  }

  classify(features, timestampMs) {
    const base = this.baseline.values;
    const noise = this.baseline.noise;
    const leanHysteresis = ["LEAN_LEFT", "LEAN_RIGHT"].includes(this.state) ? 0.65 : 1;
    const downHysteresis = ["HEAD_DOWN", "SLUMPED"].includes(this.state) ? 0.65 : 1;
    const enter = {
      headRoll: Math.max(10, 6 * q(noise.headRollDeg)) * leanHysteresis,
      torsoLean: Math.max(8, 6 * q(noise.torsoLeanDeg)) * leanHysteresis,
      lateral: Math.max(0.12, 6 * q(noise.lateralHead)) * leanHysteresis,
      headDrop: Math.max(0.12, 6 * q(noise.headHeight)) * downHysteresis,
      eyeDrop: Math.max(0.10, 6 * q(noise.eyeHeight)) * downHysteresis,
      pitch: Math.max(0.08, 6 * q(noise.facePitch)) * downHysteresis,
    };
    const headRoll = features.headRollDeg - base.headRollDeg;
    const torsoLean = features.torsoLeanDeg - base.torsoLeanDeg;
    const lateral = features.lateralHead - base.lateralHead;
    const headDrop = base.headHeight - features.headHeight;
    const eyeDrop = base.eyeHeight - features.eyeHeight;
    const pitch = Number.isFinite(features.facePitch) && Number.isFinite(base.facePitch)
      ? features.facePitch - base.facePitch : 0;
    const scaleEvidence = this.scaleEvidence(features);
    features.scaleEvidence = scaleEvidence;
    const torsoCompression = Number.isFinite(features.torsoLength) && base.torsoLength > 0
      ? (base.torsoLength - features.torsoLength) / base.torsoLength : 0;

    const leanScores = [
      {direction: Math.sign(headRoll), score: Math.abs(headRoll) / enter.headRoll},
      {direction: Math.sign(torsoLean), score: Math.abs(torsoLean) / enter.torsoLean},
      {direction: Math.sign(lateral), score: Math.abs(lateral) / enter.lateral},
    ].filter(value => value.direction !== 0 && value.score >= 1);
    leanScores.sort((a, b) => b.score - a.score);
    let leanScore = leanScores[0]?.score ?? 0;
    let leanDirection = leanScores[0]?.direction ?? 0;

    const downSignals = [headDrop / enter.headDrop, eyeDrop / enter.eyeDrop, pitch / enter.pitch];
    const downCount = downSignals.filter(value => value >= 1).length;
    const downScore = [...downSignals].sort((a, b) => b - a)[1] ?? 0;
    const headDown = downCount >= 2 || headDrop / enter.headDrop >= 1.5;

    if (scaleEvidence.consensus) {
      this.slumpedSinceMs = 0;
      return {state: "TOO_CLOSE", rawState: "TOO_CLOSE",
        confidence: clamp((scaleEvidence.agreeingRatio - 1) / 0.55, 0, 1), reason: "scale_consensus"};
    }

    const slumpEvidence = headDown && (torsoCompression >= 0.10 ||
      (headDrop >= Math.max(0.18, enter.headDrop) && eyeDrop >= Math.max(0.16, enter.eyeDrop)));
    if (slumpEvidence) {
      if (!this.slumpedSinceMs) this.slumpedSinceMs = timestampMs;
      if (timestampMs - this.slumpedSinceMs >= 5000) {
        return {state: "SLUMPED", rawState: "SLUMPED", confidence: clamp(Math.max(downScore, torsoCompression / 0.20), 0, 1), reason: "sustained_collapse"};
      }
    } else {
      this.slumpedSinceMs = 0;
    }

    if (leanScore >= 1 && (!headDown || leanScore >= downScore)) {
      const state = leanDirection > 0 ? "LEAN_LEFT" : "LEAN_RIGHT";
      return {state, rawState: state, confidence: clamp(leanScore / 1.8, 0, 1), reason: "anatomical_lean"};
    }
    if (headDown) {
      return {state: "HEAD_DOWN", rawState: "HEAD_DOWN", confidence: clamp(Math.max(downScore, headDrop / enter.headDrop) / 1.8, 0, 1), reason: "head_geometry"};
    }
    this.slumpedSinceMs = 0;
    return {state: "NORMAL", rawState: "NORMAL", confidence: features.quality, reason: "within_baseline"};
  }

  commit(rawState, timestampMs, confidence, features, reason, explicitRawState = null) {
    if (rawState !== this.candidate) {
      this.candidate = rawState;
      this.candidateSinceMs = timestampMs;
    }
    if (this.state !== rawState && timestampMs - this.candidateSinceMs >= LABEL_DEBOUNCE_MS) {
      this.state = rawState;
      this.stateSinceMs = timestampMs;
      if (rawState !== "NORMAL") this.normalSinceMs = 0;
    }
    if (this.state === "NORMAL" && !this.normalSinceMs) this.normalSinceMs = timestampMs;
    return this.snapshot(reason, confidence, features, explicitRawState ?? rawState);
  }

  maybeAdapt(features, timestampMs, state) {
    if (state !== "NORMAL" || !this.normalSinceMs || timestampMs - this.normalSinceMs < 30000) {
      this.adaptationSamples = [];
      return;
    }
    this.adaptationSamples.push(features);
    if (this.adaptationSamples.length < 20) return;
    const rate = 0.02;
    for (const name of Object.keys(this.baseline.values)) {
      if (SCALE_SOURCE_NAMES.includes(name)) continue;
      const candidate = featureSummary(this.adaptationSamples, name).value;
      if (!Number.isFinite(candidate)) continue;
      const current = this.baseline.values[name];
      const maximumStep = Math.max(Math.abs(current) * 0.002, name.includes("Deg") ? 0.05 : 0.0005);
      this.baseline.values[name] += clamp((candidate - current) * rate, -maximumStep, maximumStep);
    }
    this.adaptationSamples = [];
  }

  snapshot(reason = "ready", confidence = 0, features = null, rawState = this.candidate) {
    return {
      source: "POSE_LOCAL",
      state: this.state,
      rawState,
      confidence: clamp(confidence, 0, 1),
      stableMs: this.stateSinceMs && this.lastTimestampMs >= this.stateSinceMs
        ? this.lastTimestampMs - this.stateSinceMs : 0,
      reason,
      calibrated: Boolean(this.baseline),
      calibrationProgress: this.baseline ? REQUIRED_BASELINE_SAMPLES : this.samples.length,
      calibrationRequired: REQUIRED_BASELINE_SAMPLES,
      calibrationStableMs: this.baseline ? REQUIRED_BASELINE_MS :
        (this.samples.length > 1 ? this.samples.at(-1)._timestampMs - this.samples[0]._timestampMs : 0),
      calibrationMode: this.manualCalibration ? "manual" : "auto",
      features,
      baselineChanged: reason === "baseline_ready",
      scaleCalibrationProgress: this.baseline?.scaleReady ? REQUIRED_BASELINE_SAMPLES : this.scaleSamples.length,
      scaleCalibrationRequired: REQUIRED_BASELINE_SAMPLES,
    };
  }
}
