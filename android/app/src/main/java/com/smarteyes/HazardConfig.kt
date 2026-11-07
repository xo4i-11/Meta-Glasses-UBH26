package com.smarteyes

/**
 * Tuning knobs for on-phone YOLO hazard warnings. Tune on site with the Debug view
 * (see docs/yolo-tuning-test-plan.md). All coordinates are normalised 0..1, y = 0 at the top.
 */
object HazardConfig {
  /** Box centre must be above this line (0.40 = upper 40% of the frame) to count as head height. */
  const val TOP_ZONE = 0.40f
  /** Minimum box area (w*h) as a share of the frame. Bigger = must be closer before warning. */
  const val MIN_AREA = 0.05f
  /** Minimum YOLO confidence. */
  const val MIN_CONF = 0.40f
  /** COCO classes that count as head-height hazards. */
  val HAZARD_CLASSES = setOf("umbrella", "backpack")
  /** Never warn for these (people are left to the cane and Gemini). */
  val IGNORED_CLASSES = setOf("person")
  /** Consecutive hit frames before warning (anti-flicker). */
  const val PERSIST_FRAMES = 2
  /** Consecutive miss frames before re-arming (~1.6 s at 400 ms). */
  const val REARM_FRAMES = 4
  /** After a spoken hazard, all other hazard speech is dropped for this long. */
  const val QUIET_MS = 5_000L
  /** YOLO runs on the newest frame this often. */
  const val YOLO_PERIOD_MS = 400L
}
