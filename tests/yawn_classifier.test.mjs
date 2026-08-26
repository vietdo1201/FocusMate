// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import {YawnClassifier} from "../firmware/main/web/yawn_classifier.mjs";

function mouthLandmarks(mar, {mouthWidth = 0.4, eyeWidth = 0.5} = {}) {
  const landmarks = Array.from({length: 309}, () => ({x: 0.5, y: 0.5}));
  landmarks[33] = {x: 0.5 - eyeWidth / 2, y: 0.4};
  landmarks[263] = {x: 0.5 + eyeWidth / 2, y: 0.4};
  landmarks[78] = {x: 0.5 - mouthWidth / 2, y: 0.5};
  landmarks[308] = {x: 0.5 + mouthWidth / 2, y: 0.5};
  landmarks[13] = {x: 0.5, y: 0.5 - mar * mouthWidth / 2};
  landmarks[14] = {x: 0.5, y: 0.5 + mar * mouthWidth / 2};
  return landmarks;
}

function calibrateWithoutBlendshapes(classifier) {
  let result;
  for (let index = 0; index < 20; index += 1) {
    result = classifier.observe({
      sequence: index + 1,
      timestampMs: index * 300,
      landmarks: mouthLandmarks(0.08),
      jawOpen: null,
    });
  }
  assert.equal(result.calibrated, true);
  return result;
}

test("landmarks-only Web inference counts a sustained high-MAR yawn", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);

  let result;
  for (let index = 0; index < 5; index += 1) {
    result = classifier.observe({
      sequence: 21 + index,
      timestampMs: 6000 + index * 400,
      landmarks: mouthLandmarks(1.248),
      jawOpen: null,
    });
  }

  assert.equal(result.state, "YAWNING");
  assert.equal(result.totalCount, 1);
  assert.equal(result.eventJustCounted, true);
  assert.equal(result.jawOpen, null, "missing jaw must not be displayed as 0%");
  assert.ok(Math.abs(result.mar - 1.248) < 1e-9);
});

test("an open mouth is rejected as a closed-mouth calibration sample", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  let result;
  for (let index = 0; index < 20; index += 1) {
    result = classifier.observe({
      sequence: index + 1,
      timestampMs: index * 300,
      landmarks: mouthLandmarks(1.248),
      jawOpen: null,
    });
  }

  assert.equal(result.calibrated, false);
  assert.equal(result.calibrationProgress, 0);
  assert.equal(result.reason, "mouth_moving");
});

test("a brief mouth opening is not counted as a yawn", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);
  classifier.observe({sequence: 21, timestampMs: 6000, landmarks: mouthLandmarks(1.1), jawOpen: null});
  const result = classifier.observe({sequence: 22, timestampMs: 6400, landmarks: mouthLandmarks(0.08), jawOpen: null});

  assert.equal(result.totalCount, 0);
  assert.equal(result.eventJustCounted, false);
});

test("a sustained but shallow mouth movement does not satisfy the yawn peak", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);
  let result;
  for (let index = 0; index < 5; index += 1) {
    result = classifier.observe({
      sequence: 21 + index,
      timestampMs: 6000 + index * 400,
      landmarks: mouthLandmarks(0.14),
      jawOpen: null,
    });
  }

  assert.equal(result.totalCount, 0);
  assert.equal(result.eventJustCounted, false);
});

test("a sustained wide open laugh is rejected by horizontal mouth expansion", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);
  let result;
  for (let index = 0; index < 8; index += 1) {
    result = classifier.observe({
      sequence: 21 + index,
      timestampMs: 6000 + index * 400,
      landmarks: mouthLandmarks(0.8, {mouthWidth: 0.58}),
      jawOpen: null,
    });
  }

  assert.equal(result.state, "IDLE");
  assert.equal(result.totalCount, 0);
  assert.equal(result.eventJustCounted, false);
  assert.equal(result.reason, "smile_like");
  assert.ok(result.mouthWidthExpansion > result.maxMouthWidthExpansion);
});

test("a long moderate laugh remains below the absolute yawn MAR floor", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);
  let result;
  for (let index = 0; index < 8; index += 1) {
    result = classifier.observe({
      sequence: 21 + index,
      timestampMs: 6000 + index * 400,
      landmarks: mouthLandmarks(0.28, {mouthWidth: 0.5}),
      jawOpen: null,
    });
  }

  assert.equal(result.totalCount, 0);
  assert.equal(result.eventJustCounted, false);
});

test("moving closer scales mouth and eyes together without looking like a smile", () => {
  const classifier = new YawnClassifier({fingerprint: "web-landmarks-only"});
  calibrateWithoutBlendshapes(classifier);
  let result;
  for (let index = 0; index < 5; index += 1) {
    result = classifier.observe({
      sequence: 21 + index,
      timestampMs: 6000 + index * 400,
      landmarks: mouthLandmarks(1.1, {mouthWidth: 0.48, eyeWidth: 0.6}),
      jawOpen: null,
    });
  }

  assert.equal(result.state, "YAWNING");
  assert.equal(result.totalCount, 1);
  assert.ok(result.mouthWidthExpansion < result.maxMouthWidthExpansion);
});
