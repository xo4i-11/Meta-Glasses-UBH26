package com.smarteyes

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val backend by lazy { Backend(BuildConfig.BACKEND_URL, BuildConfig.SHARED_SECRET) }
  private val player by lazy { Player(this) }
  private lateinit var phone: PhoneFrameSource
  private lateinit var glasses: GlassesFrameSource
  private lateinit var source: FrameSource
  private lateinit var hub: Hub
  private lateinit var cane: CaneBle
  private var datReady = false
  @Volatile private var yolo: Yolo? = null
  @Volatile private var lastYoloFrame: Bitmap? = null

  // Screen
  private lateinit var glassesChip: StatusChip
  private lateinit var caneChip: StatusChip
  private lateinit var serverChip: StatusChip
  private lateinit var offlineBanner: TextView
  private lateinit var said: TextView
  private lateinit var saidMeta: TextView
  private lateinit var guidance: GuidancePill
  private lateinit var sourcePill: TextView
  private lateinit var debugPill: TextView
  private lateinit var helperPill: TextView
  private lateinit var helper: LinearLayout
  private lateinit var details: TextView
  private lateinit var debug: DebugView
  private lateinit var hazardFlash: TextView
  private var lastSaidAt = 0L
  private var lastSaidSource = ""
  private var flashJob: Job? = null

  private val perms = registerForActivityResult(RequestMultiplePermissions()) { granted ->
    Wearables.initialize(this)
    datReady = true
    if (granted[Manifest.permission.CAMERA] != false) phone.start()
    if (granted[Manifest.permission.BLUETOOTH_SCAN] != false && granted[Manifest.permission.BLUETOOTH_CONNECT] != false) cane.start()
    else chip(caneChip, false, "no permission")
  }

  private val glassesCameraPerm = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
    if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) useGlasses()
    else setStatus("Glasses camera permission denied")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    phone = PhoneFrameSource(this)
    glasses = GlassesFrameSource(lifecycleScope, ::onGlassesStatus)
    source = phone
    val recorder = VoiceRecorder(this)
    hub = Hub(
        scope = lifecycleScope,
        request = { mode, clientMs ->
          if (mode == "ask") {
            player.speakLocal("Listening.")
            delay(800)
            // Empty audio -> backend falls back to "where are my keys?"
            backend.ask(recorder.record4s() ?: ByteArray(0), "question.m4a", clientMs)
          } else {
            source.latestJpeg()?.let { backend.frame(it, mode, clientMs) }
          }
        },
        play = { reply, onStarted -> player.play(reply, backend, onStarted) },
        speakLocal = player::speakLocal,
        onStatus = ::setStatus,
        buzz = ::vibrate,
        yoloScan = ::scanYolo,
        playHazard = player::playHazard,
        logHazard = { src, label, conf, ms, st ->
          if (st == HazardStatus.SPOKEN) runOnUiThread { flashHazard() }
          lifecycleScope.launch { backend.hazardEvent(src, label, conf, ms, st.wire) }
        },
        onYolo = { scan, warn, st -> onYoloResult(scan, warn, st) },
        onReply = { mode, text, ms -> runOnUiThread { showSaid(mode, text, ms) } },
        onOnline = { online -> runOnUiThread { setOnline(online) } },
    )
    lifecycleScope.launch(Dispatchers.Default) {
      yolo = try { Yolo(this@MainActivity) } catch (e: Exception) { setStatus("YOLO failed to load: ${e.message}"); null }
    }
    cane = CaneBle(this, hub::onButton) { s -> runOnUiThread { onCaneStatus(s) } }
    setContentView(buildScreen())
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    lifecycleScope.launch {
      player.cacheHazardClip(backend)
      setOnline(backend.ping())
    }
    lifecycleScope.launch { while (isActive) { delay(10_000); refreshSaidAge() } }
    perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.RECORD_AUDIO))
  }

  override fun onDestroy() {
    cane.stop()
    yolo?.close()
    super.onDestroy()
  }

  // ---------------------------------------------------------------- layout

  private fun buildScreen(): View {
    val gap = dp(12)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(24)) }
    fun add(v: View, top: Int = gap, h: Int = -2) = col.addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = top })

    offlineBanner = lpText(LP.BODY, bold = true, color = LP.YELLOW).apply {
      text = "Server offline. Warnings still work."
      setPadding(dp(16), dp(14), dp(16), dp(14))
      background = lpShape(LP.INK, 14)
      visibility = View.GONE
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    add(offlineBanner, top = 0)

    glassesChip = StatusChip(this, "Glasses")
    caneChip = StatusChip(this, "Cane")
    serverChip = StatusChip(this, "Server")
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(glassesChip, caneChip, serverChip).forEachIndexed { i, c ->
      chips.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(8) }) // equal heights
    }
    add(chips)
    chip(glassesChip, false, "phone camera")
    chip(caneChip, false, "off")
    chip(serverChip, false, "checking")

    // The page: what Smart Eyes last said, in large print. Bound edge left, fore edge right.
    said = lpText(LP.BIG, bold = true).apply { text = "Press Describe, or turn on Guidance." }
    saidMeta = lpText(LP.SMALL).apply { text = "Nothing said yet" }
    val page = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(20), dp(20), dp(18))
      minimumHeight = dp(196)
      background = lpShape(LP.PAGE, 6, 24, 24, 6)
      addView(said)
      addView(saidMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    add(page, top = dp(16))

    guidance = GuidancePill(this) { on ->
      if (on) hub.startHazards() else hub.stopHazards()
      player.speakLocal(if (on) "Guidance on." else "Guidance off.")
      announce(if (on) "Guidance on" else "Guidance off")
    }
    add(guidance, top = dp(20))

    val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(
        ActionTile(this, "Describe", "press") { hub.onButton(1) },
        ActionTile(this, "Read", "hold") { hub.onButton(2) },
        ActionTile(this, "Ask", "double press") { hub.onButton(3) },
    ).forEachIndexed { i, t -> tiles.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) }) }
    add(tiles, top = dp(16))

    // Helper tools: for the sighted helper. Collapsed by default so the blind user's swipe order stays short.
    helperPill = lpPill("Helper tools") { toggleHelper() }
    add(helperPill, top = dp(28), h = -2)
    helper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    sourcePill = lpPill("Camera: phone. Tap for glasses") { toggleSource() }
    debugPill = lpPill("Tuning view: off") {
      debug.visibility = if (debug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
      setPill(debugPill, if (debug.visibility == View.VISIBLE) "Tuning view: on" else "Tuning view: off")
    }
    details = lpText(LP.SMALL).apply { text = "Ready." }
    debug = DebugView(this).apply { visibility = View.GONE }
    helper.addView(sourcePill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
    helper.addView(debugPill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
    helper.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
    helper.addView(debug, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
    add(helper, top = 0)

    val scroll = ScrollView(this).apply { addView(col); isFillViewport = true }

    // Hazard flash: the whole screen turns black with yellow type, held briefly. Audio carries the warning;
    // this is for anyone looking (helper, judges, low-vision user).
    hazardFlash = lpText(LP.BIG, bold = true, color = LP.YELLOW).apply {
      text = "Stop.\nHead height."
      gravity = Gravity.CENTER
      setBackgroundColor(LP.INK)
      visibility = View.GONE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LP.BIG * 1.6f)
    }

    val root = FrameLayout(this).apply {
      setBackgroundColor(LP.YELLOW)
      addView(scroll, FrameLayout.LayoutParams(-1, -1))
      addView(hazardFlash, FrameLayout.LayoutParams(-1, -1))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      // Content scrolls below the status bar, never under it; the yellow board shows through behind the bars.
      (scroll.layoutParams as FrameLayout.LayoutParams).setMargins(0, bars.top, 0, 0)
      scroll.setPadding(0, dp(12), 0, bars.bottom)
      scroll.clipToPadding = false
      scroll.requestLayout()
      insets
    }
    return root
  }

  private fun setPill(pill: TextView, label: String) {
    pill.text = label
    pill.contentDescription = label
  }

  private fun toggleHelper() {
    val open = helper.visibility != View.VISIBLE
    helper.visibility = if (open) View.VISIBLE else View.GONE
    helperPill.stateDescription = if (open) "Expanded" else "Collapsed"
  }

  // ---------------------------------------------------------------- state

  private fun chip(c: StatusChip, on: Boolean, text: String, speak: Boolean = false) {
    if (c.set(on, text) && speak) announce(c.contentDescription.toString())
  }

  private fun announce(text: String) = window.decorView.announceForAccessibility(text)

  private fun onGlassesStatus(s: String) = runOnUiThread {
    setStatus(s)
    if (source !== glasses) return@runOnUiThread
    when {
      "STARTED" in s -> chip(glassesChip, true, "streaming", speak = true)
      "STARTING" in s -> chip(glassesChip, false, "starting")
      "error" in s.lowercase() -> chip(glassesChip, false, "problem", speak = true)
      else -> chip(glassesChip, false, "off", speak = true)
    }
  }

  private fun onCaneStatus(s: String) {
    setStatus(s)
    when {
      "connected" in s && "dis" !in s -> chip(caneChip, true, "connected", speak = true)
      "disconnected" in s -> chip(caneChip, false, "lost", speak = true)
      "searching" in s -> chip(caneChip, false, "searching")
      else -> chip(caneChip, false, s.removePrefix("Cane: "))
    }
  }

  private fun setOnline(online: Boolean) {
    chip(serverChip, online, if (online) "online" else "offline", speak = true)
    offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
  }

  private fun showSaid(mode: String, text: String, ms: Int) {
    if (text.isBlank()) return
    said.text = text
    lastSaidAt = SystemClock.elapsedRealtime()
    lastSaidSource = when (mode) { "describe" -> "Described"; "read_text" -> "Read"; "ask" -> "Answered"; else -> "Said" }
    saidMeta.tag = "%.1f s".format(ms / 1000f)
    refreshSaidAge()
  }

  private fun refreshSaidAge() {
    if (lastSaidAt == 0L) return
    val s = (SystemClock.elapsedRealtime() - lastSaidAt) / 1000
    val age = when { s < 10 -> "just now"; s < 60 -> "$s s ago"; else -> "${s / 60} min ago" }
    saidMeta.text = "$lastSaidSource $age · took ${saidMeta.tag}"
  }

  private fun flashHazard() {
    flashJob?.cancel()
    hazardFlash.visibility = View.VISIBLE
    flashJob = lifecycleScope.launch { delay(2500); hazardFlash.visibility = View.GONE }
  }

  // ---------------------------------------------------------------- camera source

  private fun toggleSource() {
    if (!datReady) return setStatus("Waiting for permissions")
    if (source === glasses) {
      glasses.stop()
      source = phone
      setPill(sourcePill, "Camera: phone. Tap for glasses")
      chip(glassesChip, false, "phone camera", speak = true)
      return
    }
    lifecycleScope.launch {
      if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
        setStatus("Approve Smart Eyes in the Meta AI app, then tap again.")
        Wearables.startRegistration(this@MainActivity)
        return@launch
      }
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { if (it == PermissionStatus.Granted) useGlasses() else glassesCameraPerm.launch(Permission.CAMERA) }
          .onFailure { e, _ -> setStatus("Glasses permission check failed: ${e.description}") }
    }
  }

  private fun useGlasses() {
    source = glasses
    setPill(sourcePill, "Camera: glasses. Tap for phone")
    chip(glassesChip, false, "starting")
    glasses.start()
  }

  // ---------------------------------------------------------------- YOLO

  /** One YOLO pass on the newest frame, off the main thread. Null when the model or a frame isn't ready. */
  private suspend fun scanYolo(): YoloScan? = withContext(Dispatchers.Default) {
    val y = yolo ?: return@withContext null
    val t0 = SystemClock.elapsedRealtime()
    val jpeg = source.latestJpeg() ?: return@withContext null
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext null
    lastYoloFrame = bmp
    YoloScan(y.detect(bmp), (SystemClock.elapsedRealtime() - t0).toInt())
  }

  private fun onYoloResult(scan: YoloScan, warn: Det?, status: HazardStatus?) {
    if (debug.visibility == View.VISIBLE) debug.show(lastYoloFrame, scan, warn, status)
  }

  private fun vibrate() =
      getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

  private fun setStatus(s: String) = runOnUiThread { if (::details.isInitialized) details.text = s }
}
