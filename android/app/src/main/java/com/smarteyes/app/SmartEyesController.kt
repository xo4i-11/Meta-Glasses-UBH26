package com.smarteyes.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smarteyes.app.audio.Listener
import com.smarteyes.app.audio.Voice
import com.smarteyes.app.cane.CaneEvent
import com.smarteyes.app.cane.CaneLink
import com.smarteyes.app.glasses.GlassesCamera
import com.smarteyes.app.glasses.PhoneCamera
import com.smarteyes.app.net.Backend
import com.smarteyes.app.sensors.Pointing
import com.smarteyes.app.sos.SosSender
import com.smarteyes.app.vision.Detection
import com.smarteyes.app.vision.FrameUtils
import com.smarteyes.app.vision.HazardEngine
import com.smarteyes.app.vision.ObstacleDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val glasses: String = "not started",
    val usingPhoneCamera: Boolean = false,
    val serverUp: Boolean = false,
    val caneOnline: Boolean = false,
    val distanceCm: Int = -1,
    val autoAnnounce: Boolean = true,
    val announceSeconds: Int = 7,
    val yoloReady: Boolean = false,
    val yoloMs: Long = 0,
    val yoloEngine: String = "",
    val busy: String = "",
    val lastSaid: String = "",
    val caregiver: String = "",
    val serverUrl: String = "",
    val alarm: String = "",
    val showFeed: Boolean = true,
    val log: List<String> = emptyList(),
)

/** The newest camera frame and what YOLO found in it, for the on-screen camera view. */
data class Preview(val frame: Bitmap, val dets: List<Detection>)

/**
 * The hub on the phone.
 *  - YOLO runs on every camera frame (on the phone, offline). It announces what it sees every
 *    5-10 s and warns instantly about something big right ahead. The cane's joystick turns this on/off.
 *  - Gemini runs ONLY when the joystick asks (describe / read / ask).
 *  - The cane's own beeps (distance, fall siren) never depend on this app.
 */
class SmartEyesController(private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val prefs = context.getSharedPreferences("smarteyes", Context.MODE_PRIVATE)
  val backend = Backend(prefs.getString("server_url", null) ?: BuildConfig.BACKEND_URL.trimEnd('/'))
  val voice = Voice(context, backend::ttsUrl)
  val glasses = GlassesCamera(context, scope)
  private val phoneCamera = PhoneCamera(context)
  val cane = CaneLink({ backend.baseUrl }, BuildConfig.APP_TOKEN)
  private val sos = SosSender(context)
  private val listener = Listener(context)
  private val pointing = Pointing(context)
  private val detector by lazy { ObstacleDetector(context) }
  private val hazards = HazardEngine()

  private val _ui =
      MutableStateFlow(
          UiState(caregiver = sos.caregiverNumber, serverUrl = backend.baseUrl, showFeed = prefs.getBoolean("show_feed", true)))
  val ui: StateFlow<UiState> = _ui

  // Only filled while the camera view is switched on, so a hidden view costs nothing.
  private val _preview = MutableStateFlow<Preview?>(null)
  val preview: StateFlow<Preview?> = _preview

  @Volatile private var latestFrame: Bitmap? = null
  @Volatile private var latestDets: List<Detection> = emptyList()
  private val cloudBusy = AtomicBoolean(false)
  private var started = false
  private var phoneCamJob: Job? = null
  private var lastMemoryLog = 0L
  private var lastMemoryText = ""
  private var fallFailsafe: Job? = null

  fun start() {
    if (started) return
    started = true
    voice.onSpeakStart = { engine, ms -> log("voice ($engine) started after $ms ms") }
    scope.launch { _ui.update { it.copy(yoloReady = detector.isReady, yoloEngine = detector.engine) } } // ~0.5 s benchmark, off the UI thread

    // camera frames -> YOLO
    scope.launch { glasses.frames.collect { onFrame(it) } }
    scope.launch { glasses.state.collect { st -> _ui.update { it.copy(glasses = st) } } }
    glasses.start()

    // cane (through the backend relay)
    cane.start()
    scope.launch { cane.serverUp.collect { up -> _ui.update { it.copy(serverUp = up) } } }
    scope.launch {
      cane.caneOnline.collect { on ->
        _ui.update { it.copy(caneOnline = on) }
        if (on) cane.setAuto(_ui.value.autoAnnounce)
      }
    }
    scope.launch { cane.events.collect { onCane(it) } }

    pointing.start()

    // location -> backend every 30 s (attached to SOS from the cane), telemetry -> dashboard
    scope.launch {
      var tick = 0
      while (isActive) {
        if (tick % 100 == 0) pointing.lastLocation()?.let { cane.sendLocation(it.latitude, it.longitude) }
        val s = _ui.value
        backend.live(
            mapOf(
                "glasses" to if (s.usingPhoneCamera) "phone camera" else s.glasses,
                "auto" to s.autoAnnounce,
                "latency" to if (s.yoloMs > 0) mapOf("yolo · on-phone" to s.yoloMs) else null,
            ))
        tick++
        delay(300)
      }
    }
  }

  fun stop() {
    glasses.stop()
    phoneCamera.stop()
    cane.stop()
    pointing.stop()
  }

  // ======================================================================== YOLO loop (offline)

  private fun onFrame(bmp: Bitmap) {
    latestFrame = bmp
    if (!detector.isReady) {
      if (_ui.value.showFeed) _preview.value = Preview(bmp, emptyList())
      return
    }
    val t0 = SystemClock.elapsedRealtime()
    val dets = detector.detect(bmp)
    val ms = SystemClock.elapsedRealtime() - t0
    latestDets = dets
    if (_ui.value.showFeed) _preview.value = Preview(bmp, shownDets(dets))
    _ui.update { it.copy(yoloMs = ms) }
    logMemory(dets)

    if (!_ui.value.autoAnnounce) return
    val (text, urgent) = hazards.process(dets) ?: return
    voice.warn(text)
    if (urgent) {
      cane.tone(2400, 3, 80, 60)
      backend.postEvent(mapOf("kind" to "hazard", "label" to text, "source" to "yolo", "latency_ms" to ms))
      log("⚠ $text")
    } else {
      log("👁 $text")
    }
  }

  /** Every 10 s, if the view changed, log labels for "where's my backpack?" (no images stored). */
  private fun logMemory(dets: List<Detection>) {
    val now = System.currentTimeMillis()
    if (now - lastMemoryLog < 10_000) return
    val (text, objects) = hazards.memoryEntry(dets) ?: return
    if (text == lastMemoryText) return
    lastMemoryLog = now
    lastMemoryText = text
    val loc = pointing.lastLocation()
    backend.postSighting(text, objects, loc?.latitude, loc?.longitude)
  }

  // ======================================================================== cane events

  private fun onCane(e: CaneEvent) {
    when (e) {
      is CaneEvent.Telemetry -> _ui.update { it.copy(distanceCm = e.distanceCm) }
      is CaneEvent.Button ->
          when (e.name) {
            "describe" -> describe()
            "read" -> readText()
            "ask" -> askByVoice()
            "repeat" -> voice.say(voice.lastSaid.ifBlank { "Nothing said yet" })
            "auto" -> toggleAuto()
          }
      CaneEvent.FallCountdown -> {
        // The cane is already sounding its locator siren; it sends "sos" after 5 s unless the joystick is pressed
        _ui.update { it.copy(alarm = "Cane fell: press the joystick within 5 s to cancel") }
        voice.warn("Your cane fell. Press the joystick to cancel, or I will send an SOS.")
        scope.launch { pointing.freshLocation(8000) } // warm up a GPS fix for the SOS text
        fallFailsafe?.cancel()
        fallFailsafe = scope.launch { // backup: cane lost Wi-Fi mid-countdown -> the phone sends the SOS itself
          delay(FALL_FAILSAFE_MS)
          sendSos("fall detected, no response (phone backup)", null, null)
        }
        log("⏱ fall countdown")
      }
      CaneEvent.Cancelled -> {
        fallFailsafe?.cancel()
        _ui.update { it.copy(alarm = "") }
        voice.warn("Okay, cancelled")
        log("✓ SOS cancelled")
      }
      is CaneEvent.Sos -> {
        fallFailsafe?.cancel()
        sendSos(e.reason, e.lat, e.lon)
      }
    }
  }

  // ======================================================================== Gemini (on demand only)

  fun describe() = cloudCall("describing") {
    val t0 = System.currentTimeMillis()
    val frame = latestFrame ?: return@cloudCall voice.warn("No camera yet")
    val loc = pointing.lastLocation()
    val r = backend.describe(FrameUtils.toJpeg(frame), "describe", lat = loc?.latitude, lon = loc?.longitude)
    hazards.noteSpoke()
    voice.say(r.text, requestedAtMs = t0)
    backend.postEvent(mapOf("kind" to "describe", "label" to r.text, "source" to "joystick", "latency_ms" to (System.currentTimeMillis() - t0)))
    log("🗣 ${r.text} (Gemini ${r.latencyMs} ms)")
  }

  fun readText() = cloudCall("reading") {
    val t0 = System.currentTimeMillis()
    voice.warn("Reading")
    val photo = if (!_ui.value.usingPhoneCamera) glasses.capturePhoto() else null // full-res still reads better
    val frame = photo ?: latestFrame ?: return@cloudCall voice.warn("No camera yet")
    val r = backend.describe(FrameUtils.toJpeg(frame, maxSide = 1280, quality = 85), "read")
    hazards.noteSpoke()
    voice.say(r.text, requestedAtMs = t0)
    log("📖 ${r.text}")
  }

  fun askByVoice() = cloudCall("listening") {
    voice.warn("Yes?")
    delay(700)
    val q = listener.listenOnce()
    if (q.isNullOrBlank()) return@cloudCall voice.warn("Sorry, I didn't catch that")
    log("❓ $q")
    ask(q)
  }

  /** Routes a spoken question: find the cane, memory, Sky Mode, or a question about the scene (Gemini). */
  suspend fun ask(q: String) {
    val lower = q.lowercase()
    val t0 = System.currentTimeMillis()
    if (Regex("""\b(cane|stick)\b""").containsMatchIn(lower) && Regex("""\b(where|find|lost)\b""").containsMatchIn(lower)) {
      cane.find()
      return voice.warn(if (_ui.value.caneOnline) "Your cane is beeping. Follow the sound." else "I can't reach your cane right now.")
    }
    val answer =
        when {
          Regex("""\bwhere\b.*\b(my|i)\b|\bdid i (leave|put)\b|\blast see\b""").containsMatchIn(lower) -> backend.askMemory(q)
          Regex("""\b(sky|planet|star|moon|space station|iss)\b""").containsMatchIn(lower) -> skyText()
          else -> {
            val frame = latestFrame ?: return voice.warn("No camera yet")
            backend.describe(FrameUtils.toJpeg(frame), "ask", question = q).text
          }
        }
    hazards.noteSpoke()
    voice.say(answer, requestedAtMs = t0)
    log("💬 $answer")
  }

  fun skyMode() = cloudCall("sky") { voice.say(skyText()) }

  private suspend fun skyText(): String {
    val loc = pointing.lastLocation() ?: return "I need your location for Sky Mode. Turn on location."
    return backend.sky(loc.latitude, loc.longitude, pointing.azimuthDeg, pointing.altitudeDeg)
  }

  // ======================================================================== settings / misc

  fun toggleAuto() {
    val on = !_ui.value.autoAnnounce
    _ui.update { it.copy(autoAnnounce = on) }
    _preview.value?.let { p -> _preview.value = Preview(p.frame, shownDets(latestDets)) } // boxes on/off right away
    cane.setAuto(on)
    voice.warn(if (on) "Announcements on" else "Announcements off")
  }

  fun toggleFeed() {
    val on = !_ui.value.showFeed
    prefs.edit().putBoolean("show_feed", on).apply()
    _ui.update { it.copy(showFeed = on) }
    _preview.value = if (on) latestFrame?.let { Preview(it, shownDets(latestDets)) } else null
  }

  /** YOLO boxes are drawn only while Announce is on; with it off the camera view shows the bare picture. */
  private fun shownDets(dets: List<Detection>) = if (_ui.value.autoAnnounce) dets else emptyList()

  fun setAnnounceSeconds(sec: Int) {
    val s = sec.coerceIn(5, 10)
    hazards.announceEveryMs = s * 1000L
    _ui.update { it.copy(announceSeconds = s) }
  }

  fun setCaregiver(number: String) {
    sos.caregiverNumber = number
    _ui.update { it.copy(caregiver = sos.caregiverNumber) }
  }

  fun testSos() = sendSos("TEST from the Smart Eyes app (no emergency)", null, null)

  /** SMS to the caregiver with a fresh GPS fix (Google Maps link), plus a dashboard event. */
  private fun sendSos(reason: String, caneLat: Double?, caneLon: Double?) =
      scope.launch {
        _ui.update { it.copy(alarm = "Sending SOS…") }
        val loc = pointing.freshLocation(4000)
        val lat = loc?.latitude ?: caneLat
        val lon = loc?.longitude ?: caneLon
        val status = sos.send(reason, lat, lon)
        backend.postEvent(mapOf("kind" to "sos", "label" to reason, "source" to "phone", "lat" to lat, "lon" to lon))
        _ui.update { it.copy(alarm = "SOS · $status") }
        voice.warn(if (reason.startsWith("TEST")) status else "Help is on the way. $status")
        log("🆘 $reason · $status · ${if (lat != null) "%.5f, %.5f".format(lat, lon) else "no location"}")
      }

  fun findCane() {
    cane.find()
    log("🔊 find my cane")
  }

  /** Server address typed in the app (e.g. http://10.0.0.5:8000), so a new network needs no rebuild. */
  fun setServerUrl(raw: String) {
    var url = raw.trim().trimEnd('/')
    if (url.isEmpty()) url = BuildConfig.BACKEND_URL.trimEnd('/')
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
    if (url.count { it == ':' } == 1) url += ":8000"
    prefs.edit().putString("server_url", url).apply()
    backend.baseUrl = url
    _ui.update { it.copy(serverUrl = url) }
    cane.reconnect()
    log("server -> $url")
  }

  fun clearAlarm() {
    fallFailsafe?.cancel() // tapping the red banner on the phone also stops the backup SOS
    _ui.update { it.copy(alarm = "") }
  }

  /** Fallback when the glasses won't connect: phone camera on a chest strap. */
  fun usePhoneCamera(enable: Boolean) {
    _ui.update { it.copy(usingPhoneCamera = enable) }
    if (enable) {
      phoneCamera.start()
      phoneCamJob = scope.launch { phoneCamera.frames.conflate().collect { onFrame(it) } }
      log("using phone camera")
    } else {
      phoneCamJob?.cancel()
      phoneCamera.stop()
    }
  }

  fun useMockGlasses() =
      scope.launch {
        glasses.stop()
        val ok = glasses.enableMockGlasses()
        log(if (ok) "mock glasses paired" else "mock glasses failed")
        glasses.start()
      }

  /** One Gemini request at a time; a second press while busy gets a short low beep on the cane. */
  private fun cloudCall(label: String, block: suspend () -> Unit) {
    if (!cloudBusy.compareAndSet(false, true)) {
      cane.tone(500, 1, 120, 10)
      return
    }
    scope.launch {
      _ui.update { it.copy(busy = label) }
      try {
        block()
      } catch (e: Exception) {
        Log.w("SmartEyes", "$label failed", e)
        voice.warn("I can't reach the internet right now. Your cane still works.")
        log("✖ $label failed: ${e.message}")
      } finally {
        _ui.update { it.copy(busy = "") }
        cloudBusy.set(false)
      }
    }
  }

  private fun log(line: String) {
    Log.i("SmartEyes", line)
    _ui.update { it.copy(log = (listOf(line) + it.log).take(40), lastSaid = voice.lastSaid) }
  }

  companion object {
    /** cane countdown (5 s) + Wi-Fi slack: if no SOS/cancel arrived by then, the phone sends it */
    private const val FALL_FAILSAFE_MS = 9000L
  }
}
