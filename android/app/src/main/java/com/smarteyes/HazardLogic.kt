package com.smarteyes

import com.smarteyes.HazardConfig.HAZARD_CLASSES
import com.smarteyes.HazardConfig.IGNORED_CLASSES
import com.smarteyes.HazardConfig.MIN_AREA
import com.smarteyes.HazardConfig.MIN_CONF
import com.smarteyes.HazardConfig.PERSIST_FRAMES
import com.smarteyes.HazardConfig.QUIET_MS
import com.smarteyes.HazardConfig.REARM_FRAMES
import com.smarteyes.HazardConfig.TOP_ZONE

/** One YOLO detection, normalised 0..1, y down. */
data class Det(val label: String, val conf: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

/** The most confident detection that is a head-height hazard right now, or null. */
fun headHazard(dets: List<Det>): Det? = dets
    .filter { it.label in HAZARD_CLASSES && it.label !in IGNORED_CLASSES }
    .filter { it.conf >= MIN_CONF && it.cy <= TOP_ZONE && it.w * it.h >= MIN_AREA }
    .maxByOrNull { it.conf }

/** Warn once per obstacle: fire after PERSIST_FRAMES hits, re-arm only after REARM_FRAMES misses. */
class YoloTracker {
  private var hits = 0
  private var misses = 0
  private var armed = true

  /** Returns the detection on the frame a warning should fire, else null. */
  fun update(hit: Det?): Det? {
    if (hit == null) {
      hits = 0
      if (++misses >= REARM_FRAMES) armed = true
      return null
    }
    misses = 0
    hits++
    if (armed && hits >= PERSIST_FRAMES) {
      armed = false
      return hit
    }
    return null
  }
}

enum class HazardStatus {
  SPOKEN, MUTED, COOLDOWN;

  val wire get() = name.lowercase()
}

/** Shared by YOLO and Gemini: after a spoken hazard, everything else is muted for `ms`. */
class QuietPeriod(private val ms: Long = QUIET_MS) {
  private var spokenAt: Long? = null

  @Synchronized
  fun decide(nowMs: Long, recording: Boolean, serverCooldown: Boolean = false): HazardStatus {
    if (serverCooldown) return HazardStatus.COOLDOWN
    val last = spokenAt
    if (recording || (last != null && nowMs - last < ms)) return HazardStatus.MUTED
    spokenAt = nowMs
    return HazardStatus.SPOKEN
  }
}
