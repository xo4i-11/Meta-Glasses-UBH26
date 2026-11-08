package com.smarteyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Large-Print Edition: the phone screen reads like a large-print page. Yellow board, ink, a white page with
 * what Smart Eyes last said; status on top, the main controls fixed at the bottom so a blind user finds them
 * by touch in the same place every time. Everything else lives in the "Helper tools" sheet.
 * One face (Atkinson Hyperlegible, by the Braille Institute) at three sizes only; no grey type.
 */
private object LP {
  val Yellow = Color(0xFFFFD400)
  val Ink = Color(0xFF111111)
  val Page = Color.White
  val Small = 13.sp
  val Body = 20.sp
  val Big = 31.sp
  val Stroke = 3.dp
  val Face = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal), Font(R.font.atkinson_bold, FontWeight.Bold))
}

@Composable
private fun LpText(
    text: String,
    size: TextUnit = LP.Body,
    bold: Boolean = false,
    color: Color = LP.Ink,
    modifier: Modifier = Modifier,
) = Text(text, modifier, color = color, style = TextStyle(fontFamily = LP.Face, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, lineHeight = size * 1.2f))

private fun Modifier.inked(shape: Shape, fill: Color) = this.background(fill, shape).border(BorderStroke(LP.Stroke, LP.Ink), shape)

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
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // raw glasses frames pause in the background
    controller.glasses.requestPermission = ::requestWearablesPermission
    setContent { MaterialTheme(colorScheme = lightColorScheme(primary = LP.Ink, surface = LP.Yellow, background = LP.Yellow)) { Screen() } }
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

  // ======================================================================== screen

  @Composable
  private fun Screen() {
    val isReady by ready
    Box(Modifier.fillMaxSize().background(LP.Yellow).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
      if (!isReady) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          LpText("Smart Eyes needs Bluetooth permission to reach the glasses.", LP.Big, bold = true)
          Pill("Grant permissions") { permissionLauncher.launch(androidPermissions) }
        }
        return@Box
      }
      val s by controller.ui.collectAsState()
      val reg by Wearables.registrationState.collectAsState(initial = null)
      LaunchedEffect(reg) { if (reg == RegistrationState.REGISTERED) controller.glasses.start() }
      var helperOpen by remember { mutableStateOf(false) }
      val bigFont = LocalDensity.current.fontScale >= 1.3f

      Column(Modifier.fillMaxSize()) {
        StatusRow(s, stacked = LocalDensity.current.fontScale >= 1.5f)
        Spacer(Modifier.height(14.dp))
        // Middle: the page scrolls on its own; the controls below never move.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
          if (s.alarm.isNotEmpty()) {
            AlarmBanner(s.alarm) { controller.clearAlarm() }
            Spacer(Modifier.height(12.dp))
          }
          if (!s.serverUp) {
            Banner("Server offline. Warnings and the cane still work.")
            Spacer(Modifier.height(12.dp))
          }
          SaidPage(s)
          Spacer(Modifier.height(14.dp))
          Pill("Helper tools") { helperOpen = true }
        }
        Spacer(Modifier.height(14.dp))
        GuidancePill(s.autoAnnounce) { controller.toggleAuto() }
        Spacer(Modifier.height(12.dp))
        Tiles(s, stacked = bigFont)
      }
      if (helperOpen) HelperSheet(s, reg) { helperOpen = false }
    }
  }

  @Composable
  private fun StatusRow(s: UiState, stacked: Boolean) {
    val glassesOn = !s.usingPhoneCamera && s.glasses.lowercase().let { "stream" in it || "started" in it }
    val glassesText = when {
      s.usingPhoneCamera -> "not in use"
      glassesOn -> "streaming"
      "waiting" in s.glasses || "connecting" in s.glasses -> "connecting"
      else -> s.glasses.substringBefore(" (").substringBefore(":").take(16)
    }
    val caneText = when {
      s.caneOnline && s.hole -> "HOLE"
      s.caneOnline -> if (s.distanceCm < 0) "clear" else "${s.distanceCm} cm"
      else -> "offline"
    }
    val chips = listOf(
        Triple("Glasses", glassesOn, glassesText),
        Triple("Cane", s.caneOnline, caneText),
        Triple("Server", s.serverUp, if (s.serverUp) "online" else "offline"))
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { (n, on, t) -> Chip(n, on, t, Modifier.fillMaxWidth()) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { (n, on, t) -> Chip(n, on, t, Modifier.weight(1f)) } }
  }

  @Composable
  private fun Chip(name: String, on: Boolean, state: String, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.heightIn(min = 64.dp).inked(shape, if (on) LP.Ink else LP.Yellow).padding(horizontal = 10.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "$name, $state"; liveRegion = LiveRegionMode.Polite }) {
      LpText(name, LP.Body, bold = true, color = if (on) LP.Yellow else LP.Ink)
      LpText(state, LP.Small, color = if (on) LP.Yellow else LP.Ink)
    }
  }

  @Composable
  private fun SaidPage(s: UiState) {
    // The page: what Smart Eyes last said, in large print. Bound edge left, fore edge right.
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 6.dp)
    Column(Modifier.fillMaxWidth().inked(shape, LP.Page).padding(20.dp)) {
      LpText(s.lastSaid.ifBlank { "Press Describe, or turn on Guidance." }, LP.Big, bold = true)
      Spacer(Modifier.height(14.dp))
      LpText(
          when {
            s.busy.isNotEmpty() -> "${s.busy.replaceFirstChar { it.uppercase() }}…"
            s.lastSaid.isBlank() -> "Nothing said yet"
            else -> "Last thing said"
          },
          LP.Small)
    }
  }

  @Composable
  private fun GuidancePill(on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(48.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).inked(shape, if (on) LP.Ink else LP.Yellow)
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics(mergeDescendants = true) {
              contentDescription = "Guidance"
              stateDescription = if (on) "On" else "Off"
              toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
          val knob = @Composable { Box(Modifier.size(68.dp).background(if (on) LP.Yellow else LP.Ink, CircleShape)) }
          if (!on) knob()
          Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            LpText(if (on) "Guidance on" else "Guidance off", LP.Body, bold = true, color = if (on) LP.Yellow else LP.Ink)
            LpText(if (on) "Announcing what's around you" else "Tap to start", LP.Small, color = if (on) LP.Yellow else LP.Ink)
          }
          if (on) knob()
        }
  }

  /** The cane joystick gesture, drawn: press = dot, up = triangle up, down = triangle down. */
  @Composable
  private fun GestureMark(gesture: String, color: Color) {
    Canvas(Modifier.size(width = 64.dp, height = 28.dp)) {
      val r = 9.dp.toPx()
      val cy = size.height / 2
      when (gesture) {
        "up", "down" -> {
          val p = Path().apply {
            val top = cy - r; val bottom = cy + r; val left = r * 0.2f; val right = left + r * 2.4f
            if (gesture == "up") { moveTo(left, bottom); lineTo(right, bottom); lineTo((left + right) / 2, top) }
            else { moveTo(left, top); lineTo(right, top); lineTo((left + right) / 2, bottom) }
            close()
          }
          drawPath(p, color)
        }
        else -> drawCircle(color, r, Offset(r * 1.4f, cy))
      }
    }
  }

  @Composable
  private fun Tiles(s: UiState, stacked: Boolean) {
    val tiles = listOf(
        Triple("Describe", "press", { controller.describe() }),
        Triple("Read", "up", { controller.readText() }),
        Triple("Ask", "down", { controller.askByVoice() }))
    @Composable
    fun tile(t: Triple<String, String, () -> Unit>, modifier: Modifier) {
      val shape = RoundedCornerShape(20.dp)
      Column(
          modifier.heightIn(min = if (stacked) 72.dp else 118.dp).inked(shape, LP.Page)
              .clickable(role = Role.Button, onClick = t.third)
              .clearAndSetSemantics { contentDescription = "${t.first}. On the cane joystick: ${t.second}." }
              .padding(12.dp),
          verticalArrangement = Arrangement.Bottom) {
            GestureMark(t.second, LP.Ink)
            Spacer(Modifier.height(6.dp))
            LpText(t.first, LP.Body, bold = true)
            LpText("joystick: ${t.second}", LP.Small)
          }
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { tiles.forEach { tile(it, Modifier.fillMaxWidth()) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { tiles.forEach { tile(it, Modifier.weight(1f)) } }
  }

  @Composable
  private fun Banner(text: String) {
    LpText(text, LP.Body, bold = true, color = LP.Yellow,
        modifier = Modifier.fillMaxWidth().background(LP.Ink, RoundedCornerShape(20.dp)).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
  }

  /** SOS / fall alarm: the state owns the ground. Tap cancels (also stops the phone's backup SOS). */
  @Composable
  private fun AlarmBanner(text: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LP.Ink, RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onCancel)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .padding(18.dp)) {
      LpText(text, LP.Big, bold = true, color = LP.Yellow)
      LpText("Tap to cancel", LP.Small, color = LP.Yellow)
    }
  }

  @Composable
  private fun Pill(label: String, modifier: Modifier = Modifier.fillMaxWidth(), filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(48.dp)
    Box(
        modifier.heightIn(min = 64.dp).inked(shape, if (filled) LP.Ink else LP.Yellow)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart) {
          LpText(label, LP.Body, bold = true, color = if (filled) LP.Yellow else LP.Ink)
        }
  }

  // ======================================================================== helper tools

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun HelperSheet(s: UiState, reg: RegistrationState?, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LP.Yellow,
        contentColor = LP.Ink) {
      Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LpText("Helper tools", LP.Big, bold = true)

        LpText("Cane", LP.Small, bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Pill("Find cane", Modifier.weight(1f)) { controller.findCane() }
          Pill("Sky Mode", Modifier.weight(1f)) { controller.skyMode() }
        }

        LpText("Camera", LP.Small, bold = true)
        Pill(if (s.usingPhoneCamera) "Camera: phone. Tap for glasses" else "Camera: glasses. Tap for phone") {
          controller.usePhoneCamera(!s.usingPhoneCamera)
        }
        if (reg != RegistrationState.REGISTERED) {
          Pill("Connect glasses (Meta AI app)") { Wearables.startRegistration(this@MainActivity) }
        } else {
          Pill("Restart glasses") { controller.glasses.stop(); controller.glasses.start() }
        }
        Pill("Mock glasses (no Ray-Bans needed)") { controller.useMockGlasses() }

        LpText("Guidance: announce every ${s.announceSeconds} s", LP.Small, bold = true)
        Slider(
            value = s.announceSeconds.toFloat(),
            onValueChange = { controller.setAnnounceSeconds(it.toInt()) },
            valueRange = 5f..10f,
            steps = 4,
            colors = SliderDefaults.colors(thumbColor = LP.Ink, activeTrackColor = LP.Ink, inactiveTrackColor = LP.Page))

        LpText("SOS", LP.Small, bold = true)
        var number by remember { mutableStateOf(s.caregiver) }
        Field(number, "Caregiver phone number (SOS text)", KeyboardType.Phone) { number = it; controller.setCaregiver(it) }
        Pill("Send a test SOS text") { controller.testSos() }

        LpText("Server", LP.Small, bold = true)
        var server by remember { mutableStateOf(s.serverUrl) }
        Field(server, "Server address", KeyboardType.Uri) { server = it }
        Pill("Save server address", filled = true) { controller.setServerUrl(server) }

        LpText(
            "YOLO: ${if (s.yoloReady) "ready · ${s.yoloMs} ms per frame · ${s.yoloEngine}" else "loading…"}\n" +
                "Glasses: ${s.glasses} (${reg?.toString()?.lowercase() ?: "…"})",
            LP.Small)
        LpText("Log", LP.Small, bold = true)
        s.log.take(20).forEach { LpText(it, LP.Small) }
      }
    }
  }

  @Composable
  private fun Field(value: String, label: String, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { LpText(label, LP.Small) },
        textStyle = TextStyle(fontFamily = LP.Face, fontSize = LP.Body, color = LP.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LP.Ink, unfocusedBorderColor = LP.Ink, focusedContainerColor = LP.Page,
            unfocusedContainerColor = LP.Page, cursorColor = LP.Ink, focusedLabelColor = LP.Ink, unfocusedLabelColor = LP.Ink),
        modifier = Modifier.fillMaxWidth())
  }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) controller.stop()
  }
}
