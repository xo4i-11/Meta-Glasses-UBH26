package com.smarteyes

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    /** Sends one request for `mode`; null means no camera frame was available. */
    private val request: suspend (mode: String, clientMs: Int?) -> Reply?,
    private val play: (Reply, onStarted: () -> Unit) -> Unit,
    private val speakLocal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val buzz: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
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
    val t0 = now()
    // LAZY + assign before start: on Main.immediate the body may finish synchronously, and the
    // finally below must already see this job as inFlight or the gate never frees.
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        val reply = request(mode, lastClientMs) ?: return@launch onStatus("No camera frame")
        // Server blanks `say` for no-hazard and cooldown; say without audio (TTS down) still gets spoken locally.
        if (mode == "hazard" && reply.say.isEmpty()) return@launch
        play(reply) {
          lastClientMs = (now() - t0).toInt()
          onStatus("${reply.say}\n\n$mode: $lastClientMs ms (server ${reply.serverMs} ms)")
        }
      } catch (e: IOException) {
        if (offline.allow(now())) speakLocal(OFFLINE)
        onStatus("Backend unreachable: ${e.message}")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) { // bad JSON (captive portal), bitmap decode, camera errors: never crash the app
        onStatus("Error: ${e.message}")
      } finally {
        // A pre-empted hazard job must not free the gate the button now holds.
        if (inFlight === currentCoroutineContext().job) gate.done()
      }
    }
    inFlight = job
    job.start()
  }
}
