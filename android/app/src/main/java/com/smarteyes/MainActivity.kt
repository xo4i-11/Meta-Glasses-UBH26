package com.smarteyes

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val backend by lazy { Backend(BuildConfig.BACKEND_URL, BuildConfig.SHARED_SECRET) }
  private val player by lazy { Player(this) }
  private lateinit var phone: PhoneFrameSource
  private lateinit var glasses: GlassesFrameSource
  private lateinit var source: FrameSource
  private lateinit var status: TextView
  private lateinit var sourceBtn: Button
  private lateinit var hub: Hub
  private lateinit var cane: CaneBle
  private lateinit var caneStatus: TextView
  private var datReady = false
  @Volatile private var yolo: Yolo? = null
  @Volatile private var lastYoloFrame: Bitmap? = null
  private lateinit var debug: DebugView

  private val perms = registerForActivityResult(RequestMultiplePermissions()) { granted ->
    Wearables.initialize(this)
    datReady = true
    if (granted[Manifest.permission.CAMERA] != false) phone.start()
    if (granted[Manifest.permission.BLUETOOTH_SCAN] != false && granted[Manifest.permission.BLUETOOTH_CONNECT] != false) cane.start()
    else caneStatus.text = "Cane: Bluetooth permission denied"
  }

  private val glassesCameraPerm = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
    if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) useGlasses()
    else setStatus("Glasses camera permission denied")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    phone = PhoneFrameSource(this)
    glasses = GlassesFrameSource(lifecycleScope, ::setStatus)
    source = phone
    val recorder = VoiceRecorder(this)
    hub = Hub(
        scope = lifecycleScope,
        request = { mode, clientMs ->
          if (mode == "ask") {
            player.speakLocal("Listening")
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
        logHazard = { src, label, conf, ms, st -> lifecycleScope.launch { backend.hazardEvent(src, label, conf, ms, st.wire) } },
        onYolo = { scan, warn, st -> onYoloResult(scan, warn, st) },
    )
    lifecycleScope.launch(Dispatchers.Default) {
      yolo = try { Yolo(this@MainActivity) } catch (e: Exception) { setStatus("YOLO failed to load: ${e.message}"); null }
    }
    lifecycleScope.launch { player.cacheHazardClip(backend) }
    cane = CaneBle(this, hub::onButton) { s -> runOnUiThread { caneStatus.text = s } }
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
    status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); text = "Ready" }
    caneStatus = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); text = "Cane: off" }
    root.addView(caneStatus)
    root.addView(status)
    root.addView(button("Describe") { hub.onButton(1) })
    root.addView(button("Read text") { hub.onButton(2) })
    root.addView(button("Ask: where are my keys?") { hub.onButton(3) })
    root.addView(button("Hazard warnings: off") {
      if (hub.hazardsOn) hub.stopHazards() else hub.startHazards()
      (it as Button).text = if (hub.hazardsOn) "Hazard warnings: on" else "Hazard warnings: off"
    })
    sourceBtn = button("Camera: phone. Tap to connect glasses") { toggleSource() }
    root.addView(sourceBtn)
    debug = DebugView(this).apply { visibility = android.view.View.GONE }
    root.addView(button("Debug view: off") {
      debug.visibility = if (debug.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
      (it as Button).text = if (debug.visibility == android.view.View.VISIBLE) "Debug view: on" else "Debug view: off"
    })
    root.addView(debug)
    setContentView(android.widget.ScrollView(this).apply { addView(root) })
    perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.RECORD_AUDIO))
  }

  override fun onDestroy() {
    cane.stop()
    super.onDestroy()
  }

  private fun toggleSource() {
    if (!datReady) return setStatus("Waiting for permissions")
    if (source === glasses) {
      glasses.stop()
      source = phone
      sourceBtn.text = "Camera: phone. Tap to connect glasses"
      return
    }
    lifecycleScope.launch {
      if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
        setStatus("Registering with Meta AI app... tap again when done")
        Wearables.startRegistration(this@MainActivity)
        return@launch
      }
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { if (it == PermissionStatus.Granted) useGlasses() else glassesCameraPerm.launch(Permission.CAMERA) }
          .onFailure { e, _ -> setStatus("Glasses permission check failed: ${e.description}") }
    }
  }

  private fun useGlasses() {
    glasses.start()
    source = glasses
    sourceBtn.text = "Camera: glasses. Tap for phone"
  }

  private fun button(label: String, onClick: (android.view.View) -> Unit) = Button(this).apply {
    text = label
    contentDescription = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
    minHeight = 220
    setOnClickListener { onClick(it) }
  }

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
    if (debug.visibility == android.view.View.VISIBLE) debug.show(lastYoloFrame, scan, warn, status)
  }

  private fun vibrate() =
      getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

  private fun setStatus(s: String) = runOnUiThread { status.text = s }
}
