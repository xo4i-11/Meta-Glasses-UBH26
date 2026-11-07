package com.smarteyes

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

const val OFFLINE = "Connection lost, the cane is still active."

/** Hazard loop + button handling. `scope` must be main-thread (Activity lifecycleScope). */
class Hub(
    private val scope: CoroutineScope,
    private val source: () -> FrameSource,
    private val backend: Backend,
    private val player: Player,
    private val onStatus: (String) -> Unit,
    private val buzz: () -> Unit,
    private val recordQuestion: suspend () -> ByteArray?,
    private val cadenceMs: Long = 4000,
) {
  private val gate = Gate()
  private val offline = Throttle(30_000)
  private var inFlight: Job? = null
  private var loop: Job? = null
  private var lastClientMs: Int? = null

  val hazardsOn get() = loop?.isActive == true

  fun startHazards() {
    if (hazardsOn) return
    loop = scope.launch {
      while (isActive) {
        if (gate.hazardTick() == Decision.START) run("hazard")
        delay(cadenceMs)
      }
    }
  }

  fun stopHazards() {
    loop?.cancel()
    loop = null
  }

  /** event uses the cane BLE values: 1 PRESS, 2 HOLD, 3 DOUBLE. */
  fun onButton(event: Int) {
    val mode = when (event) {
      1 -> "describe"
      2 -> "read_text"
      3 -> "ask"
      else -> return
    }
    buzz()
    when (gate.button()) {
      Decision.IGNORE -> return
      Decision.CANCEL_AND_START -> inFlight?.cancel() // pending hazard result is dropped
      Decision.START -> Unit
    }
    run(mode)
  }

  private fun run(mode: String) {
    val t0 = SystemClock.elapsedRealtime()
    inFlight = scope.launch {
      try {
        val reply = if (mode == "ask") {
          player.speakLocal("Listening")
          delay(800)
          // Empty audio -> backend falls back to "where are my keys?"
          backend.ask(recordQuestion() ?: ByteArray(0), "question.m4a", lastClientMs)
        } else {
          val jpeg = source().latestJpeg() ?: return@launch onStatus("No camera frame")
          backend.frame(jpeg, mode, lastClientMs)
        }
        if (mode == "hazard" && reply.audioId == null) return@launch // nothing at head height: stay silent
        player.play(reply, backend) {
          lastClientMs = (SystemClock.elapsedRealtime() - t0).toInt()
          onStatus("${reply.say}\n\n$mode: $lastClientMs ms (server ${reply.serverMs} ms)")
        }
      } catch (e: IOException) {
        if (offline.allow(SystemClock.elapsedRealtime())) player.speakLocal(OFFLINE)
        onStatus("Backend unreachable: ${e.message}")
      } finally {
        // A pre-empted hazard job must not free the gate the button now holds.
        if (inFlight === currentCoroutineContext().job) gate.done()
      }
    }
  }
}
