#pragma once

#include <stdbool.h>

/**
 * Runs a privacy-safe OV2640 smoke test. Frames are counted and immediately
 * returned to the driver; no image bytes are stored or transmitted.
 */
bool focusmate_camera_smoke_init(void);
