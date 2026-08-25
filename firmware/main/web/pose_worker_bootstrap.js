// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
// MediaPipe Tasks 1.0.1 ships an Emscripten loader that expects classic
// Worker semantics (global `var` bindings and importScripts). Keep this tiny
// bootstrap classic, then load our application code as an ES module.
const queuedMessages = [];
self.onmessage = event => queuedMessages.push(event);

importScripts("/assets/wasm-compatible-v1/vision_wasm_internal.js");
import("/assets/pose_worker.mjs?v=scale-consensus-1")
  .then(() => {
    const moduleHandler = self.onmessage;
    if (typeof moduleHandler !== "function") {
      throw new Error("Pose worker module did not install a message handler");
    }
    for (const event of queuedMessages.splice(0)) moduleHandler(event);
  })
  .catch(error => {
    self.postMessage({
      type: "error",
      stage: "bootstrap",
      error: String(error?.message || error),
    });
  });
