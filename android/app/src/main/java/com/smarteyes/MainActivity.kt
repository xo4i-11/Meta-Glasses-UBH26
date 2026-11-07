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
  private lateinit var pageScroll: ScrollView
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
    // Large-print users run big system font sizes: stack rows instead of squeezing three across.
    val scale = resources.configuration.fontScale
    val stackTiles = scale >= 1.3f
    val stackChips = scale >= 1.5f

    // Top: status chips.
    glassesChip = StatusChip(this, "Glasses")
    caneChip = StatusChip(this, "Cane")
    serverChip = StatusChip(this, "Server")
    val chips = row(stackChips, listOf(glassesChip, caneChip, serverChip), gap = dp(8))
    chip(glassesChip, false, "not in use")
    chip(caneChip, false, "off")
    chip(serverChip, false, "checking")

    // Middle: the page, what Smart Eyes last said, in large print. It scrolls on its own so a long answer
    // never moves the controls below it. Bound edge left, fore edge right.
    said = lpText(LP.BIG, bold = true).apply { text = "Press Describe, or turn on Guidance." }
    saidMeta = lpText(LP.SMALL).apply { text = "Nothing said yet" }
    val page = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(20), dp(20), dp(18))
      background = lpShape(LP.PAGE, 6, 24, 24, 6)
      addView(said)
      addView(saidMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    helperPill = lpPill("Helper tools") { openHelper() }
    val middle = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(page, LinearLayout.LayoutParams(-1, -2))
      addView(helperPill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    pageScroll = ScrollView(this).apply { addView(middle); isVerticalScrollBarEnabled = false; clipToPadding = false }

    // The offline banner overlays the top of the page area instead of pushing anything down.
    offlineBanner = lpText(LP.BODY, bold = true, color = LP.YELLOW).apply {
      text = "Server offline. Warnings still work."
      setPadding(dp(16), dp(14), dp(16), dp(14))
      background = lpShape(LP.INK, 20)
      visibility = View.GONE
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val middleFrame = FrameLayout(this).apply {
      addView(pageScroll, FrameLayout.LayoutParams(-1, -1))
      addView(offlineBanner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    // Bottom: the controls, always in the same place above the navigation bar.
    guidance = GuidancePill(this) { on ->
      if (on) hub.startHazards() else hub.stopHazards()
      player.speakLocal(if (on) "Guidance on." else "Guidance off.") // TalkBack already reads the switch state
    }
    val tiles = row(stackTiles, listOf(
        ActionTile(this, "Describe", "press") { hub.onButton(1) },
        ActionTile(this, "Read", "hold") { hub.onButton(2) },
        ActionTile(this, "Ask", "twice") { hub.onButton(3) },
    ), gap = dp(10))
    if (!stackTiles) for (i in 0 until tiles.childCount) tiles.getChildAt(i).minimumHeight = dp(118)

    val col = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(12), dp(16), dp(16))
      addView(chips, LinearLayout.LayoutParams(-1, -2))
      addView(middleFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
      addView(guidance, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
      addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // Helper tools live in a bottom sheet (built once, opened on demand).
    sourcePill = lpPill("Camera: phone. Tap for glasses") { toggleSource() }
    debugPill = lpPill("Tuning view: off") {
      debug.visibility = if (debug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
      setPill(debugPill, if (debug.visibility == View.VISIBLE) "Tuning view: on" else "Tuning view: off")
    }.apply { asSwitch { debug.visibility == View.VISIBLE } }
    details = lpText(LP.SMALL).apply { text = "Ready." }
    debug = DebugView(this).apply { visibility = View.GONE }
    helper = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(20), dp(16), dp(24))
      addView(lpText(LP.BODY, bold = true).apply { text = "Helper tools"; isFocusable = true })
      addView(sourcePill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
      addView(debugPill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
      addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
      addView(debug, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // Hazard flash: the whole screen turns black with yellow type, held briefly. Audio carries the warning;
    // this is for anyone looking (helper, judges, low-vision user). Hazard-state exception to the type scale.
    hazardFlash = lpText(LP.BIG, bold = true, color = LP.YELLOW).apply {
      text = "Stop.\nHead height."
      gravity = Gravity.CENTER
      setBackgroundColor(LP.INK)
      visibility = View.GONE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LP.HAZARD)
    }

    // At very large font sizes the controls cannot all fit above the fold: the whole screen scrolls instead,
    // keeping the same order (status, page, guidance, actions).
    val body: View = if (stackChips) {
      (middleFrame.layoutParams as LinearLayout.LayoutParams).apply { height = -2; weight = 0f }
      ScrollView(this).apply { addView(col) }
    } else col
    val root = FrameLayout(this).apply {
      setBackgroundColor(LP.YELLOW)
      addView(body, FrameLayout.LayoutParams(-1, -1))
      addView(hazardFlash, FrameLayout.LayoutParams(-1, -1))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      (body.layoutParams as FrameLayout.LayoutParams).setMargins(0, bars.top, 0, bars.bottom)
      body.requestLayout()
      insets
    }
    return root
  }

  /** Three items across, or stacked full width when the font scale is large. */
  private fun row(stack: Boolean, items: List<View>, gap: Int) = LinearLayout(this).apply {
    orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    items.forEachIndexed { i, v ->
      addView(v, if (stack) LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap }
                 else LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = gap })
    }
  }

  private fun openHelper() {
    (helper.parent as? android.view.ViewGroup)?.removeView(helper)
    android.app.Dialog(this).apply {
      setContentView(ScrollView(this@MainActivity).apply { addView(helper) })
      window?.apply {
        setBackgroundDrawable(lpShape(LP.YELLOW, 28, 28, 0, 0))
        setGravity(Gravity.BOTTOM)
        setLayout(-1, -2)
      }
      show()
    }
  }

  private fun setPill(pill: TextView, label: String) {
    pill.text = label
    pill.contentDescription = label
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
    chip(serverChip, online, if (online) "online" else "offline", speak = online) // offline: the banner speaks
    offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
    // The page slides below the banner; the controls at the bottom never move.
    offlineBanner.post { pageScroll.setPadding(0, if (online) 0 else offlineBanner.height + dp(12), 0, 0) }
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
      chip(glassesChip, false, "not in use", speak = true)
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
