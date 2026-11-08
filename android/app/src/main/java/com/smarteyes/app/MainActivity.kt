package com.smarteyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Dev console: every status and every control on one screen, no menus. Not meant for the blind user. */
private val Bg = Color(0xFF0E1116)
private val Dim = Color(0xFF8B95A5)
private val Ok = Color(0xFF4CC38A)
private val Bad = Color(0xFFE5534B)
private val Warn = Color(0xFFE3B341)

class MainActivity : ComponentActivity() {
  private val controller by lazy { (application as SmartEyesApp).controller }
  private val ready = mutableStateOf(false)

  private val androidPermissions =
      arrayOf(
          Manifest.permission.BLUETOOTH_CONNECT, // glasses
          Manifest.permission.CAMERA, // phone-camera fallback
          Manifest.permission.RECORD_AUDIO, // voice questions
          Manifest.permission.ACCESS_FINE_LOCATION, // SOS location + Sky Mode
          Manifest.permission.SEND_SMS, // SOS text to caregiver
      )

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

  // Glasses camera permission is granted inside the Meta AI app (same pattern as Meta's sample)
  private var datContinuation: CancellableContinuation<PermissionStatus>? = null
  private val datMutex = Mutex()
  private val datPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        datContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        datContinuation = null
      }

  private suspend fun requestWearablesPermission(p: Permission): PermissionStatus =
      datMutex.withLock {
        suspendCancellableCoroutine { cont ->
          datContinuation = cont
          cont.invokeOnCancellation { datContinuation = null }
          datPermissionLauncher.launch(p)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // raw glasses frames pause in the background
    controller.glasses.requestPermission = ::requestWearablesPermission
    setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
  }

  override fun onStart() {
    super.onStart()
    if (!ready.value) permissionLauncher.launch(androidPermissions)
  }

  private fun onPermissionsResult() {
    if (ready.value) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
    Wearables.initialize(this) // REQUIRED before any other Wearables API
    ready.value = true
    controller.start()
  }

  @Composable
  private fun Screen() {
    val isReady by ready
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (!isReady) {
        Text("Smart Eyes · DEV", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Btn("Grant permissions", Modifier.fillMaxWidth()) { permissionLauncher.launch(androidPermissions) }
        return@Column
      }
      val s by controller.ui.collectAsState()
      val reg by Wearables.registrationState.collectAsState(initial = null)
      LaunchedEffect(reg) { if (reg == RegistrationState.REGISTERED) controller.glasses.start() }
      val registered = reg == RegistrationState.REGISTERED
      var settingsOpen by remember { mutableStateOf(false) }

      if (s.alarm.isNotEmpty()) Btn("⚠ ${s.alarm}  (tap to clear)", Modifier.fillMaxWidth(), Bad) { controller.clearAlarm() }

      // ---- what the camera sees, with YOLO boxes
      Preview()

      // ---- one dot per part: green ok, yellow waiting, red down
      val glassesOk = s.usingPhoneCamera || s.glasses.startsWith("streaming")
      val dist = if (s.hole) "HOLE" else if (s.distanceCm < 0) "no echo" else "${s.distanceCm} cm"
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(if (s.usingPhoneCamera) "Phone cam" else "Glasses", if (glassesOk) Ok else Warn)
        Dot("Cane ${if (s.caneOnline) dist else "off"}", if (s.caneOnline) Ok else Bad)
        Dot("Server", if (s.serverUp) Ok else Bad)
        Dot("YOLO ${s.yoloMs}ms", if (s.yoloReady) Ok else Warn)
      }
      if (!glassesOk) Mono(if (s.usingPhoneCamera) "phone camera" else s.glasses, Warn, 1)
      if (s.busy.isNotBlank()) Mono("busy: ${s.busy}…", Warn, 1)
      Text(s.lastSaid.ifBlank { "(nothing said yet)" }, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

      // ---- actions, 4 per row
      Grid(
          listOf(
              "Describe" to { controller.describe() },
              "Gemini" to { controller.describeWithGemini() },
              "Read" to { controller.readText() },
              "Ask 🎤" to { controller.askByVoice() },
              (if (s.autoAnnounce) "Guide ✓" else "Guide ✗") to { controller.toggleAuto() },
              "Sky" to { controller.skyMode() },
              "Beep cane" to { controller.beepCane() },
              "Find cane" to { controller.findCane() },
              (if (s.usingPhoneCamera) "Cam: phone" else "Cam: glass") to { controller.usePhoneCamera(!s.usingPhoneCamera) },
              (if (registered) "Restart gl." else "Connect gl.") to {
                if (registered) {
                  controller.glasses.stop()
                  controller.glasses.start()
                } else Wearables.startRegistration(this@MainActivity)
              },
              "Mock gl." to { controller.useMockGlasses() },
              "Test SOS" to { controller.testSos() },
          ))

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn(if (settingsOpen) "Settings ▾" else "Settings ▸", Modifier.weight(1f)) { settingsOpen = !settingsOpen }
        Btn("Clear log", Modifier.weight(1f)) { controller.clearLog() }
      }
      if (settingsOpen) Settings(s)

      // ---- log: newest first, takes the rest of the screen
      Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF161B22), RoundedCornerShape(8.dp)).padding(8.dp).verticalScroll(rememberScrollState())) {
        if (s.log.isEmpty()) Mono("log is empty: press a button or the joystick", Dim, 1)
        s.log.forEach { Mono(it, if ("⚠" in it || "fail" in it.lowercase()) Bad else Dim, 3) }
      }
    }
  }

  @Composable
  private fun Preview() {
    val p by controller.preview.collectAsState()
    val paint = remember { android.graphics.Paint().apply { color = android.graphics.Color.GREEN; textSize = 30f; isFakeBoldText = true } }
    if (p == null) {
      Text("No video yet: wake the glasses and tap Restart gl., or tap Cam to use the phone camera", color = Dim, fontSize = 13.sp,
          modifier = Modifier.fillMaxWidth().height(250.dp).background(Color.Black, RoundedCornerShape(8.dp)).padding(16.dp))
      return
    }
    Canvas(Modifier.fillMaxWidth().height(250.dp).background(Color.Black, RoundedCornerShape(8.dp))) {
      val (bmp, dets) = p ?: return@Canvas
      val k = minOf(size.width / bmp.width, size.height / bmp.height)
      val w = bmp.width * k
      val h = bmp.height * k
      val x0 = (size.width - w) / 2
      val y0 = (size.height - h) / 2
      drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(x0.toInt(), y0.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
      dets.forEach { d ->
        val l = x0 + d.box.left * w
        val t = y0 + d.box.top * h
        drawRect(Ok, Offset(l, t), Size(d.box.width() * w, d.box.height() * h), style = Stroke(3f))
        drawContext.canvas.nativeCanvas.drawText("${d.label} ${(d.score * 100).toInt()}%", l + 4, t + 28, paint)
      }
    }
  }

  @Composable
  private fun Settings(s: UiState) =
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
          Text("Guide every ${s.announceSeconds} s", color = Dim, fontSize = 13.sp, modifier = Modifier.width(120.dp).padding(top = 14.dp))
          Slider(s.announceSeconds.toFloat(), { controller.setAnnounceSeconds(it.toInt()) }, valueRange = 5f..10f, steps = 4)
        }
        var number by remember { mutableStateOf(s.caregiver) }
        OutlinedTextField(number, { number = it; controller.setCaregiver(it) }, Modifier.fillMaxWidth(),
            label = { Text("Caregiver number (SOS)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        var server by remember { mutableStateOf(s.serverUrl) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(server, { server = it }, Modifier.weight(1f), label = { Text("Server") }, singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
          Btn("Save", Modifier.padding(top = 8.dp)) { controller.setServerUrl(server) }
        }
        Mono("YOLO engine: ${s.yoloEngine} · glasses: ${s.glasses}", Dim, 3)
      }

  @Composable
  private fun Dot(label: String, color: Color) =
      Row {
        Text("● ", color = color, fontSize = 13.sp)
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
      }

  @Composable
  private fun Mono(text: String, color: Color, lines: Int) =
      Text(text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = lines, overflow = TextOverflow.Ellipsis)

  @Composable
  private fun Grid(items: List<Pair<String, () -> Unit>>) =
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(4).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (label, onClick) -> Btn(label, Modifier.weight(1f), onClick = onClick) }
            repeat(4 - row.size) { Text("", Modifier.weight(1f)) }
          }
        }
      }

  @Composable
  private fun Btn(label: String, modifier: Modifier = Modifier, color: Color = Color(0xFF2B3442), onClick: () -> Unit) =
      Button(onClick, modifier.height(44.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 4.dp),
          colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
            Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) controller.stop()
  }
}
