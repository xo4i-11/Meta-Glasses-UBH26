package com.smarteyes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
  @Test fun idleHazardStarts() = assertEquals(Decision.START, Gate().hazardTick())

  @Test fun hazardSkippedWhileInFlight() {
    val g = Gate(); g.hazardTick()
    assertEquals(Decision.IGNORE, g.hazardTick())
  }

  @Test fun buttonPreemptsHazard() {
    val g = Gate(); g.hazardTick()
    assertEquals(Decision.CANCEL_AND_START, g.button())
  }

  @Test fun idleButtonStarts() = assertEquals(Decision.START, Gate().button())

  @Test fun hazardSkippedWhileButton() {
    val g = Gate(); g.button()
    assertEquals(Decision.IGNORE, g.hazardTick())
  }

  @Test fun secondButtonIgnored() {
    val g = Gate(); g.button()
    assertEquals(Decision.IGNORE, g.button())
  }

  @Test fun doneFrees() {
    val g = Gate(); g.button(); g.done()
    assertEquals(Decision.START, g.hazardTick())
  }
}

class ThrottleTest {
  @Test fun throttles() {
    val t = Throttle(30_000)
    assertTrue(t.allow(0)); assertFalse(t.allow(29_999)); assertTrue(t.allow(30_000))
  }
}
