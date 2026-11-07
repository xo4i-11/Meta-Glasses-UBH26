package com.smarteyes

enum class Decision { START, CANCEL_AND_START, IGNORE }

/** One request at a time. Hazard ticks never queue; a button pre-empts a pending hazard request. */
class Gate {
  private enum class State { IDLE, HAZARD, BUTTON }
  private var state = State.IDLE

  @Synchronized fun hazardTick(): Decision =
      if (state == State.IDLE) Decision.START.also { state = State.HAZARD } else Decision.IGNORE

  @Synchronized fun button(): Decision = when (state) {
    State.IDLE -> Decision.START.also { state = State.BUTTON }
    State.HAZARD -> Decision.CANCEL_AND_START.also { state = State.BUTTON }
    State.BUTTON -> Decision.IGNORE
  }

  @Synchronized fun done() { state = State.IDLE }
}

/** Allows once per period (e.g. the offline phrase every 30 s at most). */
class Throttle(private val periodMs: Long) {
  private var last: Long? = null

  @Synchronized fun allow(nowMs: Long): Boolean {
    val l = last
    if (l != null && nowMs - l < periodMs) return false
    last = nowMs
    return true
  }
}
