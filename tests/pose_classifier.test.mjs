// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {
  PosePostureClassifier,
  canonicalizePoseLandmarks,
  extractPoseFeatures,
} from "../firmware/main/web/pose_classifier.mjs";

const neutral = Object.freeze({
  quality: 0.95,
  headRollDeg: 0,
  torsoLeanDeg: 0,
  shoulderAngleDeg: 0,
  lateralHead: 0,
  headHeight: 1,
  eyeHeight: 0.9,
  facePitch: 0.5,
  faceScale: 0.2,
  shoulderWidth: 0.3,
  torsoLength: 0.4,
  hasHips: true,
});

const LABEL_DEBOUNCE_MS = 1000;
const DEFAULT_SAMPLE_INTERVAL_MS = 300;

function calibrated() {
  const classifier = new PosePostureClassifier({fingerprint: "test-profile-v1"});
  for (let index = 0; index < 20; index += 1) {
    classifier.observeFeatures({
      sequence: index + 1,
      timestampMs: index * 300,
      features: {...neutral},
      faceDetected: true,
    });
  }
  assert.ok(classifier.persistedBaseline(), "automatic baseline should complete");
  return classifier;
}

function settle(
  classifier,
  features,
  startSequence,
  startMs,
  count = Math.ceil(LABEL_DEBOUNCE_MS / DEFAULT_SAMPLE_INTERVAL_MS) + 1,
  spacing = DEFAULT_SAMPLE_INTERVAL_MS,
) {
  let result;
  for (let index = 0; index < count; index += 1) {
    result = classifier.observeFeatures({
      sequence: startSequence + index,
      timestampMs: startMs + index * spacing,
      features,
      faceDetected: true,
    });
  }
  return result;
}

test("automatic baseline never reports NORMAL before 20 unique samples and five seconds", () => {
  const classifier = new PosePostureClassifier({fingerprint: "test-profile-v1"});
  let result;
  for (let index = 0; index < 19; index += 1) {
    result = classifier.observeFeatures({sequence: index + 1, timestampMs: index * 300, features: neutral, faceDetected: true});
  }
  assert.equal(result.state, "UNKNOWN");
  assert.equal(result.calibrated, false);
  result = classifier.observeFeatures({sequence: 20, timestampMs: 5700, features: neutral, faceDetected: true});
  assert.equal(result.calibrated, true);
  assert.equal(result.state, "UNKNOWN");
  result = settle(classifier, neutral, 21, 6000);
  assert.equal(result.state, "NORMAL");
});

test("calibration rejects discontinuous samples and Pose remains primary without an ESP bbox", () => {
  const classifier = new PosePostureClassifier({fingerprint: "test-profile-v1"});
  for (let index = 0; index < 19; index += 1) {
    classifier.observeFeatures({sequence: index + 1, timestampMs: index * 250, features: neutral, faceDetected: false});
  }
  classifier.observeFeatures({sequence: 20, timestampMs: 7000, features: neutral, faceDetected: false});
  assert.equal(classifier.persistedBaseline(), null, "a >1.5s gap must reset calibration");
  for (let index = 0; index < 21; index += 1) {
    classifier.observeFeatures({sequence: 21 + index, timestampMs: 7300 + index * 300, features: neutral, faceDetected: false});
  }
  assert.ok(classifier.persistedBaseline(), "valid Pose landmarks do not require an ESP bbox");
  const leaned = settle(classifier, {...neutral, headRollDeg: 13}, 50, 14000);
  assert.equal(leaned.state, "LEAN_LEFT");
});

test("automatic baseline ignores an isolated noisy inference without erasing stable history", () => {
  const classifier = new PosePostureClassifier({fingerprint: "test-profile-v1"});
  let result = classifier.observeFeatures({sequence: 1, timestampMs: 0, features: neutral, faceDetected: true});
  assert.equal(result.calibrationProgress, 1);
  result = classifier.observeFeatures({sequence: 2, timestampMs: 250, features: {...neutral, headRollDeg: 6}, faceDetected: true});
  assert.equal(result.calibrationProgress, 1);
  result = classifier.observeFeatures({sequence: 3, timestampMs: 500, features: neutral, faceDetected: true});
  assert.equal(result.calibrationProgress, 2);
});

test("subject-relative lean directions cannot invert", () => {
  const left = calibrated();
  assert.equal(settle(left, {...neutral, headRollDeg: 13}, 30, 7000).state, "LEAN_LEFT");
  const right = calibrated();
  assert.equal(settle(right, {...neutral, torsoLeanDeg: -11}, 30, 7000).state, "LEAN_RIGHT");
});

test("head down, too close, slumped and missing use distinct evidence", () => {
  const head = calibrated();
  assert.equal(settle(head, {...neutral, headHeight: 0.75, eyeHeight: 0.68}, 30, 7000).state, "HEAD_DOWN");

  const close = calibrated();
  assert.equal(settle(close, {...neutral, faceScale: 0.29}, 30, 7000).state, "TOO_CLOSE");

  const slump = calibrated();
  const collapsed = {...neutral, headHeight: 0.70, eyeHeight: 0.63, torsoLength: 0.34};
  let result = settle(slump, collapsed, 30, 7000, 18, 400);
  result = settle(slump, collapsed, 48, 14200, 3, 400);
  assert.equal(result.state, "SLUMPED");

  const missing = calibrated();
  for (let index = 0; index < 5; index += 1) {
    result = missing.observeFeatures({sequence: 30 + index, timestampMs: 7000 + index * 300, features: null, faceDetected: false});
  }
  assert.equal(result.state, "FACE_MISSING");
});

test("duplicate frames do not advance debounce", () => {
  const classifier = calibrated();
  const leaned = {...neutral, headRollDeg: 15};
  classifier.observeFeatures({sequence: 30, timestampMs: 7000, features: leaned, faceDetected: true});
  for (let index = 0; index < 10; index += 1) {
    classifier.observeFeatures({sequence: 30, timestampMs: 7000, features: leaned, faceDetected: true});
  }
  assert.notEqual(classifier.snapshot().state, "LEAN_LEFT");
  const result = settle(classifier, leaned, 31, 7300, 4);
  assert.equal(result.state, "LEAN_LEFT");
});

test("explicit exit hysteresis and monotonic stale transition are fail-closed", () => {
  const classifier = calibrated();
  let result = settle(classifier, {...neutral, faceScale: 0.29}, 30, 7000);
  assert.equal(result.state, "TOO_CLOSE");
  result = settle(classifier, {...neutral, faceScale: 0.245}, 35, 8500);
  assert.equal(result.state, "TOO_CLOSE", "1.225x remains TOO_CLOSE until below 1.20x");
  result = settle(classifier, {...neutral, faceScale: 0.235}, 40, 10000);
  assert.equal(result.state, "NORMAL");
  assert.equal(classifier.stale(14_201).state, "UNKNOWN");
});

test("camera mirror and rotation are undone before anatomical extraction", () => {
  const landmarks = Array.from({length: 25}, () => ({x: 0.5, y: 0.5, visibility: 1, presence: 1}));
  landmarks[0] = {x: 0.56, y: 0.20, visibility: 1, presence: 1};
  landmarks[2] = {x: 0.57, y: 0.24, visibility: 1, presence: 1};
  landmarks[5] = {x: 0.47, y: 0.20, visibility: 1, presence: 1};
  landmarks[11] = {x: 0.65, y: 0.45, visibility: 1, presence: 1};
  landmarks[12] = {x: 0.35, y: 0.45, visibility: 1, presence: 1};
  landmarks[23] = {x: 0.62, y: 0.80, visibility: 1, presence: 1};
  landmarks[24] = {x: 0.38, y: 0.80, visibility: 1, presence: 1};
  const original = extractPoseFeatures(landmarks);
  const mirrored = canonicalizePoseLandmarks(
    landmarks.map(point => ({...point, x: 1 - point.x})),
    {mirrorX: true},
  );
  const rotated = canonicalizePoseLandmarks(
    landmarks.map(point => ({...point, x: 1 - point.x, y: 1 - point.y})),
    {mirrorX: true, flipY: true},
  );
  for (const features of [extractPoseFeatures(mirrored), extractPoseFeatures(rotated)]) {
    assert.ok(Math.abs(features.headRollDeg - original.headRollDeg) < 0.0001);
    assert.ok(Math.abs(features.lateralHead - original.lateralHead) < 0.0001);
    assert.ok(Math.abs(features.torsoLeanDeg - original.torsoLeanDeg) < 0.0001);
  }
});

test("shared landmark golden vectors preserve label vocabulary and precedence", () => {
  const rows = readFileSync(new URL("./golden/posture_landmarks_v1.tsv", import.meta.url), "utf8")
    .trim().split(/\r?\n/);
  const names = rows.shift().split("\t");
  for (const line of rows) {
    const values = line.split("\t");
    const row = Object.fromEntries(names.map((name, index) => [name, values[index]]));
    const classifier = calibrated();
    const features = {
      ...neutral,
      headRollDeg: Number(row.head_roll_deg),
      torsoLeanDeg: Number(row.torso_lean_deg),
      lateralHead: Number(row.lateral_head),
      headHeight: Number(row.head_height),
      eyeHeight: Number(row.eye_height),
      facePitch: Number(row.face_pitch),
      faceScale: Number(row.face_scale),
      torsoLength: Number(row.torso_length),
    };
    const holdMs = Number(row.hold_ms);
    const requiredDurationMs = Math.max(LABEL_DEBOUNCE_MS, holdMs) + DEFAULT_SAMPLE_INTERVAL_MS;
    const frames = Math.ceil(requiredDurationMs / DEFAULT_SAMPLE_INTERVAL_MS) + 1;
    const result = settle(classifier, features, 30, 7000, frames, DEFAULT_SAMPLE_INTERVAL_MS);
    assert.equal(result.state, row.expected, row.name);
  }
});
