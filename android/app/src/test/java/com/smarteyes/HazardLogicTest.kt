package com.smarteyes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HazardLogicTest {
  private val hung = Det("umbrella", .8f, .5f, .25f, .4f, .3f)

  @Test fun hungUmbrellaTriggers() = assertNotNull(headHazard(listOf(hung)))
  @Test fun floorUmbrellaIgnored() = assertNull(headHazard(listOf(hung.copy(cy = .8f))))
  @Test fun smallFarUmbrellaIgnored() = assertNull(headHazard(listOf(hung.copy(w = .1f, h = .1f))))
  @Test fun personIgnored() = assertNull(headHazard(listOf(hung.copy(label = "person"))))
  @Test fun chairIgnored() = assertNull(headHazard(listOf(hung.copy(label = "chair"))))
  @Test fun lowConfidenceIgnored() = assertNull(headHazard(listOf(hung.copy(conf = .2f))))
  @Test fun backpackTriggers() = assertNotNull(headHazard(listOf(hung.copy(label = "backpack"))))
  @Test fun picksHighestConfidence() =
      assertEquals(.9f, headHazard(listOf(hung, hung.copy(conf = .9f)))!!.conf)

  private fun run(t: YoloTracker, pattern: String) = pattern.count { t.update(if (it == 'H') hung else null) != null }

  @Test fun warnsOnceWhileObstacleStays() = assertEquals(1, run(YoloTracker(), "HHHHHHHHHH"))
  @Test fun singleFrameDoesNotWarn() = assertEquals(0, run(YoloTracker(), "H.H.H."))
  @Test fun rearmsAfterObstacleGone() = assertEquals(2, run(YoloTracker(), "HHH....HH"))
  @Test fun flickerDoesNotRearm() = assertEquals(1, run(YoloTracker(), "HHH..HH"))

  @Test fun geminiInsideQuietPeriodMuted() {
    val q = QuietPeriod(5000)
    assertEquals(HazardStatus.SPOKEN, q.decide(0, false))
    assertEquals(HazardStatus.MUTED, q.decide(2000, false))
    assertEquals(HazardStatus.SPOKEN, q.decide(5001, false))
  }

  @Test fun recordingMutes() {
    val q = QuietPeriod(5000)
    assertEquals(HazardStatus.MUTED, q.decide(0, recording = true))
    assertEquals(HazardStatus.SPOKEN, q.decide(100, false)) // muted event did not start the window
  }

  @Test fun serverCooldownReported() =
      assertEquals(HazardStatus.COOLDOWN, QuietPeriod(5000).decide(0, false, serverCooldown = true))

  @Test fun wireNames() = assertEquals(listOf("spoken", "muted", "cooldown"), HazardStatus.entries.map { it.wire })
}
