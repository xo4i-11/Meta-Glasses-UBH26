package com.smarteyes

import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubTest {
  private val crashes = mutableListOf<Throwable>()
  private val played = mutableListOf<Reply>()
  private val spoken = mutableListOf<String>()
  private var calls = 0
  private val hazardLog = mutableListOf<String>()
  private var hazardClips = 0

  /** Main.immediate-like: launch runs the body right away, as lifecycleScope does. */
  private fun hub(
      test: TestScope,
      yoloScan: (suspend () -> YoloScan?)? = null,
      request: suspend (String, Int?) -> Reply?,
  ) = Hub(
      scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler) + CoroutineExceptionHandler { _, e -> crashes += e }),
      request = { m, c -> calls++; request(m, c) },
      play = { r, onStarted -> played += r; onStarted() },
      speakLocal = { spoken += it },
      onStatus = {},
      buzz = {},
      now = { test.testScheduler.currentTime },
      yoloScan = yoloScan,
      playHazard = { hazardClips++ },
      logHazard = { src, label, _, _, status -> hazardLog += "$src $label ${status.wire}" },
  )

  private val umbrella = Det("umbrella", .8f, .5f, .25f, .4f, .3f)

  @Test fun hazardWithSayButNoAudioIsStillSpoken() = TestScope().run {
    val h = hub(this) { _, _ -> Reply("Stop, head height", true, null, 0) }
    h.startHazards(); runCurrent(); h.stopHazards()
    assertEquals(listOf("Stop, head height"), played.map { it.say })
  }

  @Test fun hazardWithNothingToSayStaysSilent() = TestScope().run {
    val h = hub(this) { _, _ -> Reply("", false, null, 0) }
    h.startHazards(); runCurrent(); h.stopHazards()
    assertTrue(played.isEmpty())
  }

  @Test fun gateFreedWhenRequestReturnsWithoutSuspending() = TestScope().run {
    val h = hub(this) { _, _ -> null } // e.g. no camera frame yet
    h.onButton(1); h.onButton(1)
    assertEquals(2, calls)
  }

  @Test fun unexpectedExceptionDoesNotCrashAndFreesGate() = TestScope().run {
    val h = hub(this) { _, _ -> throw IllegalStateException("captive portal html") }
    h.onButton(1); h.onButton(1)
    assertTrue(crashes.toString(), crashes.isEmpty())
    assertEquals(2, calls)
  }

  @Test fun offlinePhraseThrottled() = TestScope().run {
    val h = hub(this) { _, _ -> throw IOException("down") }
    h.onButton(1); h.onButton(1)
    assertEquals(listOf(OFFLINE), spoken)
  }

  @Test fun yoloSpeaksOnceThenGeminiConfirmationMuted() = TestScope().run {
    val h = hub(this, yoloScan = { YoloScan(listOf(umbrella), 30) }) { mode, _ ->
      delay(1000) // Gemini answers after YOLO already spoke
      Reply("Duck, head height", true, "hazard_duck", 0).takeIf { mode == "hazard" }
    }
    h.startHazards(); advanceTimeBy(1500); h.stopHazards()
    assertEquals(listOf("yolo umbrella spoken", "gemini Duck, head height muted"), hazardLog)
    assertEquals(1, hazardClips)
    assertTrue(played.isEmpty())
  }

  @Test fun geminiServerCooldownLoggedNotSpoken() = TestScope().run {
    val h = hub(this) { _, _ -> Reply("", true, null, 0) }
    h.startHazards(); runCurrent(); h.stopHazards()
    assertEquals(listOf("gemini  cooldown"), hazardLog)
    assertTrue(played.isEmpty())
  }

  @Test fun geminiHazardSpokenWhenQuiet() = TestScope().run {
    val h = hub(this) { _, _ -> Reply("Duck, head height", true, "hazard_duck", 0) }
    h.startHazards(); runCurrent(); h.stopHazards()
    assertEquals(listOf("gemini Duck, head height spoken"), hazardLog)
    assertEquals(1, played.size)
  }
}
