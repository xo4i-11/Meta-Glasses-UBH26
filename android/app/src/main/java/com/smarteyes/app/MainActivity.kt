package com.smarteyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

// High-contrast palette: warm yellow on near-black reads well for low-vision users.
private val Bg = Color(0xFF0B0E13)
private val CardBg = Color(0xFF151A22)
private val Accent = Color(0xFFFFD54F)
private val OnAccent = Color(0xFF1A1400)
private val TextMain = Color(0xFFF2F4F7)
private val TextDim = Color(0xFF9AA4B2)
private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)
private val Danger = Color(0xFFD64545)

private val SmartEyesColors =
    darkColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        secondaryContainer = Color(0xFF263040),
        onSecondaryContainer = TextMain,
        background = Bg,
        surface = CardBg,
        onSurface = TextMain,
        onSurfaceVariant = TextDim,
        outline = Color(0xFF3A4555),
    )

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
    setContent { MaterialTheme(colorScheme = SmartEyesColors) { Screen() } }
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
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Header()
          if (!isReady) {
            Section {
              Text("Smart Eyes needs Bluetooth permission to reach the glasses.", color = TextMain, fontSize = 20.sp)
              BigButton("Grant permissions") { permissionLauncher.launch(androidPermissions) }
            }
            return@Column
          }
          val s by controller.ui.collectAsState()
          val reg by Wearables.registrationState.collectAsState(initial = null)
          LaunchedEffect(reg) { if (reg == RegistrationState.REGISTERED) controller.glasses.start() }

          if (s.alarm.isNotEmpty()) {
            Button(
                onClick = { controller.clearAlarm() },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)) {
                  Text("${s.alarm}  ·  tap to clear", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
          }

          Status(s, reg)

          CameraCard(s)

          // What the app last said out loud
          Box(
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(18.dp))
                  .background(Color(0xFF1C2330))
                  .padding(horizontal = 18.dp, vertical = 16.dp)
                  .animateContentSize()) {
                Column {
                  Text("LAST SPOKEN", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                  Spacer(Modifier.height(6.dp))
                  Text(
                      s.lastSaid.ifBlank { "Use the cane joystick: press to describe, up to read, down to ask." },
                      color = TextMain,
                      fontSize = 21.sp,
                      lineHeight = 28.sp,
                      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
              }

          BigButton(if (s.busy == "describing") "Describing…" else "Describe scene") { controller.describe() }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalButton("Read text", Modifier.weight(1f)) { controller.readText() }
            TonalButton("Ask", Modifier.weight(1f)) { controller.askByVoice() }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalButton(if (s.autoAnnounce) "Announce: ON" else "Announce: OFF", Modifier.weight(1f), on = s.autoAnnounce) {
              controller.toggleAuto()
            }
            TonalButton("Find cane", Modifier.weight(1f)) { controller.findCane() }
          }

          Section("Announcements") {
            Text("Say what YOLO sees every ${s.announceSeconds} s", color = TextDim, fontSize = 15.sp)
            Slider(
                value = s.announceSeconds.toFloat(),
                onValueChange = { controller.setAnnounceSeconds(it.toInt()) },
                valueRange = 5f..10f,
                steps = 4)
          }

          Section("Emergency") {
            var number by remember { mutableStateOf(s.caregiver) }
            OutlinedTextField(
                value = number,
                onValueChange = {
                  number = it
                  controller.setCaregiver(it)
                },
                label = { Text("Caregiver phone number (SOS text)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth())
            if (s.caregiver.isBlank()) Text("No number set: SOS can't text anyone yet.", color = Warn, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SmallButton("Test SOS text", Modifier.weight(1f), color = Bad) { controller.testSos() }
              SmallButton("Sky Mode", Modifier.weight(1f)) { controller.skyMode() }
            }
          }

          var showMore by remember { mutableStateOf(false) }
          Section(if (showMore) "Setup  ▾" else "Setup  ▸", onTitleClick = { showMore = !showMore }) {
            if (showMore) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reg != RegistrationState.REGISTERED) {
                  SmallButton("Connect glasses", Modifier.weight(1f)) { Wearables.startRegistration(this@MainActivity) }
                } else {
                  SmallButton("Restart glasses", Modifier.weight(1f)) {
                    controller.glasses.stop()
                    controller.glasses.start()
                  }
                }
                SmallButton(if (s.usingPhoneCamera) "Phone cam: ON" else "Phone cam: OFF", Modifier.weight(1f)) {
                  controller.usePhoneCamera(!s.usingPhoneCamera)
                }
              }
              SmallButton("Mock glasses (no Ray-Bans needed)", Modifier.fillMaxWidth()) { controller.useMockGlasses() }

              var server by remember { mutableStateOf(s.serverUrl) }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f))
                SmallButton("Save") { controller.setServerUrl(server) }
              }
            } else {
              Text("Glasses, phone camera, server address", color = TextDim, fontSize = 14.sp)
            }
          }

          if (s.log.isNotEmpty()) {
            Section("Activity") {
              s.log.forEach {
                Text(it, color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
  }

  @Composable
  private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
      Box(Modifier.size(14.dp).clip(CircleShape).background(Accent))
      Spacer(Modifier.size(10.dp))
      Column {
        Text("Smart Eyes", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.semantics { heading() })
        Text("Glasses · Cane · Phone", fontSize = 14.sp, color = TextDim)
      }
    }
  }

  @Composable
  private fun Status(s: UiState, reg: RegistrationState?) {
    val dist = if (s.distanceCm < 0) "path clear" else "${s.distanceCm} cm ahead"
    val glassesText = if (s.usingPhoneCamera) "phone camera" else s.glasses
    val glassesColor =
        when {
          s.usingPhoneCamera || glassesText == "streaming" -> Good
          reg != RegistrationState.REGISTERED || "denied" in glassesText || "failed" in glassesText -> Bad
          else -> Warn
        }
    Section {
      StatusRow("Glasses", glassesText, glassesColor)
      StatusRow(
          "Cane",
          if (s.caneOnline) "online · $dist" else if (s.serverUp) "offline (still beeps on its own)" else "—",
          if (s.caneOnline) Good else Warn)
      StatusRow(
          "Server",
          (if (s.serverUp) "connected" else "can't reach ${s.serverUrl}") + if (s.busy.isNotEmpty()) " · ${s.busy}" else "",
          if (s.serverUp) Good else Bad)
      StatusRow(
          "YOLO",
          if (s.yoloReady) "${s.yoloMs} ms/frame · ${s.yoloEngine}" else "loading…",
          if (s.yoloReady) Good else Warn)
    }
  }

  /** Live view of whatever camera feeds YOLO (glasses or phone), with YOLO's boxes drawn on top. */
  @Composable
  private fun CameraCard(s: UiState) {
    Section {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Camera view",
            color = TextMain,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() })
        SmallButton(if (s.showFeed) "Hide" else "Show") { controller.toggleFeed() }
      }
      if (!s.showFeed) return@Section
      val p by controller.preview.collectAsState()
      val frame = p
      if (frame == null) {
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F131A)),
            contentAlignment = Alignment.Center) {
              Text(
                  if (s.usingPhoneCamera) "Starting phone camera…" else "Waiting for the glasses camera…",
                  color = TextDim,
                  fontSize = 15.sp)
            }
        return@Section
      }
      val bmp = frame.frame
      val ratio = bmp.width.toFloat() / bmp.height
      val labelPaint = remember {
        android.graphics.Paint().apply {
          isAntiAlias = true
          textSize = 34f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
      }
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(max = 420.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp))) {
          Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "Live camera view",
              contentScale = ContentScale.FillBounds,
              modifier = Modifier.fillMaxSize())
          Canvas(Modifier.fillMaxSize()) {
            frame.dets.forEach { d ->
              val l = d.box.left * size.width
              val t = d.box.top * size.height
              drawRect(
                  Accent,
                  topLeft = Offset(l, t),
                  size = Size(d.box.width() * size.width, d.box.height() * size.height),
                  style = Stroke(width = 3.dp.toPx()))
              val text = "${d.label} ${(d.score * 100).toInt()}%"
              val tw = labelPaint.measureText(text)
              val ty = (t - 4.dp.toPx()).coerceAtLeast(labelPaint.textSize)
              drawRect(Accent, topLeft = Offset(l, ty - labelPaint.textSize), size = Size(tw + 12f, labelPaint.textSize + 8f))
              labelPaint.color = android.graphics.Color.rgb(0x1A, 0x14, 0x00)
              drawContext.canvas.nativeCanvas.drawText(text, l + 6f, ty, labelPaint)
            }
          }
        }
      }
      Text(
          "${if (s.usingPhoneCamera) "Phone camera" else "Glasses"} · ${bmp.width}×${bmp.height} · ${if (s.autoAnnounce) "${frame.dets.size} objects" else "boxes off (Announce is off)"}",
          color = TextDim,
          fontSize = 13.sp)
    }
  }

  @Composable
  private fun StatusRow(name: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
      Spacer(Modifier.size(12.dp))
      Text(name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.3f))
      Text(value, color = TextDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.7f))
    }
  }

  @Composable
  private fun Section(title: String? = null, onTitleClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth().animateContentSize()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
              Text(
                  title,
                  color = TextMain,
                  fontSize = 17.sp,
                  fontWeight = FontWeight.Bold,
                  modifier =
                      Modifier.fillMaxWidth()
                          .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                          .semantics { heading() })
            }
            content()
          }
        }
  }

  @Composable
  private fun BigButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(76.dp), shape = RoundedCornerShape(20.dp)) {
      Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
  }

  @Composable
  private fun TonalButton(label: String, modifier: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(18.dp),
        colors =
            if (on) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3B3415), contentColor = Accent)
            else ButtonDefaults.filledTonalButtonColors()) {
          Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
  }

  @Composable
  private fun SmallButton(label: String, modifier: Modifier = Modifier, color: Color = TextMain, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
          Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) controller.stop()
  }
}
