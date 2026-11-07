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

/** One YOLO pass over the newest frame. */
class YoloScan(val dets: List<Det>, val latencyMs: Int)

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
    /** Runs YOLO on the newest frame (off the main thread inside); null = no frame. Null lambda = YOLO off. */
    private val yoloScan: (suspend () -> YoloScan?)? = null,
    /** Speaks the cached head-height clip, interrupting any answer. */
    private val playHazard: () -> Unit = {},
    /** Every hazard event: source (yolo|gemini), label, confidence, latency ms, status. Fire-and-forget. */
    private val logHazard: (String, String, Float?, Int, HazardStatus) -> Unit = { _, _, _, _, _ -> },
    /** Debug view hook: every YOLO pass with the warning it produced (null = none). */
    private val onYolo: (YoloScan, Det?, HazardStatus?) -> Unit = { _, _, _ -> },
    /** A non-hazard reply that is being spoken: mode, text, press-to-speech latency ms. */
    private val onReply: (String, String, Int) -> Unit = { _, _, _ -> },
    /** Backend reachability: true after any successful request, false when one fails to connect. */
    private val onOnline: (Boolean) -> Unit = {},
) {
  private val gate = Gate()
  private val offline = Throttle(30_000)
  private var inFlight: Job? = null
  private var loop: Job? = null
  private var lastClientMs: Int? = null
  private val quiet = QuietPeriod()
  private val tracker = YoloTracker()
  private var yoloLoop: Job? = null

  /** True while a voice question is in progress: hazard warnings are muted, not spoken. */
  @Volatile var recording = false

  val hazardsOn get() = loop?.isActive == true

  fun startHazards() {
    if (hazardsOn) return
    loop = scope.launch {
      while (isActive) {
        if (gate.hazardTick() == Decision.START) run("hazard")
        delay(cadenceMs)
      }
    }
    val scan = yoloScan ?: return
    yoloLoop = scope.launch {
      while (isActive) {
        // Sequential scan-then-wait: a slow inference just delays the next one, never queues.
        try {
          scan()?.let(::onYoloScan)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          onStatus("YOLO error: ${e.message}")
        }
        delay(HazardConfig.YOLO_PERIOD_MS)
      }
    }
  }

  fun stopHazards() {
    loop?.cancel()
    loop = null
    yoloLoop?.cancel()
    yoloLoop = null
  }

  private fun onYoloScan(s: YoloScan) {
    val warn = tracker.update(headHazard(s.dets))
    val status = warn?.let { quiet.decide(now(), recording) }
    if (warn != null && status != null) {
      logHazard("yolo", warn.label, warn.conf, s.latencyMs, status)
      // Muted only because a question was recording: don't lose this obstacle, warn again once it ends.
      if (status == HazardStatus.MUTED && recording) tracker.rearm()
      if (status == HazardStatus.SPOKEN) {
        try {
          playHazard()
        } catch (e: Exception) {
          speakLocal("Careful, head height.")
          onStatus("Hazard clip failed: ${e.message}")
        }
      }
    }
    onYolo(s, warn, status)
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
        if (mode == "ask") recording = true
        val reply = try {
          request(mode, lastClientMs) ?: return@launch onStatus("No camera frame")
        } finally {
          if (mode == "ask") recording = false
        }
        onOnline(true)
        if (mode == "hazard") {
          if (!reply.hazard) return@launch // nothing at head height
          // Server blanks `say` when its own per-clip cooldown hit; otherwise the phone's quiet period decides.
          val status = quiet.decide(now(), recording, serverCooldown = reply.say.isEmpty())
          logHazard("gemini", reply.say, null, (now() - t0).toInt(), status)
          if (status != HazardStatus.SPOKEN) return@launch
        }
        play(reply) {
          lastClientMs = (now() - t0).toInt()
          if (mode != "hazard") onReply(mode, reply.say, lastClientMs ?: 0)
          onStatus("${reply.say}\n\n$mode: $lastClientMs ms (server ${reply.serverMs} ms)")
        }
      } catch (e: IOException) {
        onOnline(false)
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
