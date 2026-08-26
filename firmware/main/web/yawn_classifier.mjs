// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
export const YAWN_CLASSIFIER_VERSION = "YAWN_MAR_V4";

const REQUIRED_SAMPLES = 20;
const CALIBRATION_SPAN_MS = 5000;
const OPEN_JAW_FLOOR = 0.32;
const PEAK_JAW_FLOOR = 0.42;
const CALIBRATION_JAW_MAX = 0.30;
// The compact Web face task intentionally omits the blendshape head, so MAR
// must also be able to reject an open mouth during calibration by itself.
const CALIBRATION_MAR_MAX = 0.30;
const OPEN_DURATION_MS = 1000;
const OPEN_GAP_MS = 250;
// Face inference normally arrives every 350-500 ms. Consecutive open samples
// must not be mistaken for a >250 ms interruption merely because of cadence.
const MAX_OPEN_SAMPLE_GAP_MS = 900;
const CLOSE_HOLD_MS = 500;
const EVENT_COOLDOWN_MS = 2000;
const STALE_MS = 1000;
const WINDOW_MS = 10 * 60_000;
const ALERT_COOLDOWN_MS = 10 * 60_000;

const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
const median = values => {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};

function summarize(values) {
  const center = median(values);
  const noise = Math.max(0.002, median(values.map(value => Math.abs(value - center))));
  return {center, noise};
}

function validBaseline(value, fingerprint) {
  return value?.schema === 2 && value?.classifierVersion === YAWN_CLASSIFIER_VERSION &&
    value?.fingerprint === fingerprint && Number.isFinite(value.center) && value.center > 0 &&
    Number.isFinite(value.noise) && value.noise > 0 &&
    Number.isFinite(value.jawCenter) && value.jawCenter >= 0 &&
    Number.isFinite(value.jawNoise) && value.jawNoise > 0;
}

export function extractMouthAspectRatio(landmarks) {
  if (!Array.isArray(landmarks) || landmarks.length <= 308) return null;
  const upper = landmarks[13], lower = landmarks[14], left = landmarks[78], right = landmarks[308];
  if (![upper, lower, left, right].every(point => point && Number.isFinite(point.x) && Number.isFinite(point.y))) return null;
  const width = distance(left, right);
  if (!Number.isFinite(width) || width < 0.01) return null;
  const mar = distance(upper, lower) / width;
  return Number.isFinite(mar) && mar >= 0 && mar < 2 ? mar : null;
}

export class YawnClassifier {
  constructor({fingerprint, baseline = null} = {}) {
    this.fingerprint = fingerprint || "unknown";
    this.baseline = validBaseline(baseline, this.fingerprint) ? baseline : null;
    this.samples = [];
    this.resetTemporal();
    this.totalCount = 0;
    this.totalDurationMs = 0;
    this.events = [];
    this.lastAlertAt = -Infinity;
    this.lastResult = null;
  }

  resetTemporal() {
    this.candidateStartedAt = null;
    this.lastOpenAt = null;
    this.closedSince = null;
    this.peakJaw = 0;
    this.peakMar = 0;
    this.countedCurrentOpen = false;
    this.currentYawnStartedAt = null;
    this.lastEventAt = -Infinity;
  }

  reset() {
    this.baseline = null;
    this.samples = [];
    this.resetTemporal();
    this.lastResult = null;
  }

  persistedBaseline() {
    return this.baseline ? {...this.baseline} : null;
  }

  observe({sequence, timestampMs, landmarks, jawOpen}) {
    const mar = extractMouthAspectRatio(landmarks);
    // Number(null) is 0, which previously made an unavailable blendshape look
    // like a real closed-jaw measurement and permanently blocked Web yawns.
    const jaw = jawOpen == null ? null : Number(jawOpen);
    const hasJaw = Number.isFinite(jaw);
    if (!Number.isFinite(mar) || (jaw != null && !hasJaw)) {
      return this.unavailable(timestampMs, "face_missing");
    }

    if (!this.baseline) {
      const calibrationEligible = mar < CALIBRATION_MAR_MAX && (!hasJaw || jaw < CALIBRATION_JAW_MAX);
      if (calibrationEligible) {
        const previous = this.samples.at(-1);
        if (!previous || previous.sequence !== sequence) this.samples.push({sequence, timestampMs, mar, jaw});
      }
      while (this.samples.length > REQUIRED_SAMPLES) this.samples.shift();
      let reason = calibrationEligible ? "collecting_closed_mouth" : "mouth_moving";
      if (this.samples.length === REQUIRED_SAMPLES &&
          this.samples.at(-1).timestampMs - this.samples[0].timestampMs >= CALIBRATION_SPAN_MS) {
        const summary = summarize(this.samples.map(sample => sample.mar));
        const jawSamples = this.samples.map(sample => sample.jaw).filter(Number.isFinite);
        const jawSummary = jawSamples.length === REQUIRED_SAMPLES ? summarize(jawSamples) : {center: 0, noise: 0.002};
        if (summary.noise <= 0.025 && (!hasJaw || jawSummary.noise <= 0.04)) {
          this.baseline = {
            schema: 2,
            classifierVersion: YAWN_CLASSIFIER_VERSION,
            fingerprint: this.fingerprint,
            center: summary.center,
            noise: summary.noise,
            jawCenter: jawSummary.center,
            jawNoise: jawSummary.noise,
          };
          this.samples = [];
          reason = "baseline_ready";
        } else {
          this.samples.shift();
          reason = "mouth_moving";
        }
      }
      return this.publish({
        state: "CALIBRATING", timestampMs, jawOpen: jaw, mar,
        calibrationProgress: this.samples.length,
        calibrationRequired: REQUIRED_SAMPLES,
        calibrationSpanMs: this.samples.length > 1 ? this.samples.at(-1).timestampMs - this.samples[0].timestampMs : 0,
        reason,
        baselineChanged: reason === "baseline_ready",
      });
    }

    this.pruneEvents(timestampMs);
    const marThreshold = this.baseline.center + Math.max(3 * this.baseline.noise, 0.05);
    const marPeakThreshold = this.baseline.center + Math.max(5 * this.baseline.noise, 0.10);
    const closeThreshold = this.baseline.center + Math.max(2 * this.baseline.noise, 0.035);
    const jawThreshold = Math.max(OPEN_JAW_FLOOR,
      this.baseline.jawCenter + Math.max(4 * this.baseline.jawNoise, 0.18));
    const jawPeakThreshold = Math.max(PEAK_JAW_FLOOR,
      this.baseline.jawCenter + Math.max(6 * this.baseline.jawNoise, 0.28));
    const jawCloseThreshold = this.baseline.jawCenter + Math.max(2 * this.baseline.jawNoise, 0.10);
    // Web ships the landmarks-only face task to fit the fixed flash partition.
    // MAR is therefore the primary signal there; jaw remains a corroborating
    // signal on runtimes (such as Watch) that actually provide blendshapes.
    const open = mar >= marThreshold && (!hasJaw || jaw >= jawThreshold);
    let eventJustCounted = false;
    let alertJustTriggered = false;
    let persistenceChanged = false;
    let state = "IDLE";

    if (open) {
      this.closedSince = null;
      if (this.lastOpenAt == null || timestampMs - this.lastOpenAt > MAX_OPEN_SAMPLE_GAP_MS) {
        if (!this.countedCurrentOpen) this.candidateStartedAt = timestampMs;
        this.peakJaw = hasJaw ? jaw : 0;
        this.peakMar = mar;
      }
      this.lastOpenAt = timestampMs;
      if (hasJaw) this.peakJaw = Math.max(this.peakJaw, jaw);
      this.peakMar = Math.max(this.peakMar, mar);
      state = this.countedCurrentOpen ? "YAWNING" : "MOUTH_OPEN";
      if (!this.countedCurrentOpen && this.candidateStartedAt != null &&
          timestampMs - this.candidateStartedAt >= OPEN_DURATION_MS &&
          ((hasJaw && this.peakJaw >= jawPeakThreshold) || this.peakMar >= marPeakThreshold) &&
          timestampMs - this.lastEventAt >= EVENT_COOLDOWN_MS) {
        this.countedCurrentOpen = true;
        this.currentYawnStartedAt = this.candidateStartedAt;
        this.lastEventAt = timestampMs;
        this.totalCount += 1;
        this.events.push(timestampMs);
        eventJustCounted = true;
        persistenceChanged = true;
        state = "YAWNING";
        this.pruneEvents(timestampMs);
        if (this.events.length >= 3 && timestampMs - this.lastAlertAt >= ALERT_COOLDOWN_MS) {
          this.lastAlertAt = timestampMs;
          alertJustTriggered = true;
        }
      }
    } else {
      const clearlyClosed = mar <= closeThreshold || (hasJaw && jaw <= jawCloseThreshold);
      if (clearlyClosed) this.closedSince ??= timestampMs;
      if (!this.countedCurrentOpen && this.lastOpenAt != null && timestampMs - this.lastOpenAt > OPEN_GAP_MS) {
        this.candidateStartedAt = null;
        this.lastOpenAt = null;
        this.peakJaw = 0;
        this.peakMar = 0;
      }
      if (this.countedCurrentOpen && this.closedSince != null && timestampMs - this.closedSince >= CLOSE_HOLD_MS &&
          timestampMs - this.lastEventAt >= EVENT_COOLDOWN_MS) {
        if (this.currentYawnStartedAt != null) {
          this.totalDurationMs += Math.max(OPEN_DURATION_MS, timestampMs - this.currentYawnStartedAt);
          persistenceChanged = true;
        }
        this.candidateStartedAt = null;
        this.lastOpenAt = null;
        this.peakJaw = 0;
        this.peakMar = 0;
        this.countedCurrentOpen = false;
        this.currentYawnStartedAt = null;
      }
      if (this.countedCurrentOpen) state = "YAWNING";
    }

    const advisory = this.events.length >= 3;
    return this.publish({
      state, timestampMs, jawOpen: jaw, mar, marThreshold, jawThreshold,
      calibrationProgress: REQUIRED_SAMPLES, calibrationRequired: REQUIRED_SAMPLES,
      calibrationSpanMs: CALIBRATION_SPAN_MS, reason: advisory ? "repeated_yawn" : "live",
      totalCount: this.totalCount, totalDurationMs: this.totalDurationMs,
      eventsInWindow: this.events.length, advisory, eventJustCounted, alertJustTriggered,
      persistenceChanged,
    });
  }

  stale(timestampMs) {
    if (!this.lastResult || timestampMs - this.lastResult.timestampMs <= STALE_MS) return null;
    return this.unavailable(timestampMs, "stale");
  }

  unavailable(timestampMs, reason) {
    this.candidateStartedAt = null;
    this.lastOpenAt = null;
    this.peakJaw = 0;
    this.peakMar = 0;
    if (!this.countedCurrentOpen) this.closedSince = null;
    return this.publish({
      state: this.baseline ? "UNAVAILABLE" : "CALIBRATING", timestampMs,
      jawOpen: null, mar: null, calibrationProgress: this.samples.length,
      calibrationRequired: REQUIRED_SAMPLES, calibrationSpanMs: 0, reason,
    });
  }

  pruneEvents(timestampMs) {
    this.events = this.events.filter(value => timestampMs - value <= WINDOW_MS);
  }

  publish(value) {
    this.pruneEvents(value.timestampMs);
    this.lastResult = {
      totalCount: this.totalCount,
      totalDurationMs: this.totalDurationMs,
      eventsInWindow: this.events.length,
      advisory: this.events.length >= 3,
      baselineChanged: false,
      eventJustCounted: false,
      alertJustTriggered: false,
      persistenceChanged: false,
      ...value,
      calibrated: Boolean(this.baseline),
    };
    return this.lastResult;
  }
}
