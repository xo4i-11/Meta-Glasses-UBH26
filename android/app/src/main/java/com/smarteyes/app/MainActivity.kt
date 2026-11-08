package com.smarteyes.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
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
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Smart Eyes · DEV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
          if (!isReady) {
            Btn("Grant permissions", Modifier.fillMaxWidth()) { permissionLauncher.launch(androidPermissions) }
            return@Column
          }
          val s by controller.ui.collectAsState()
          val reg by Wearables.registrationState.collectAsState(initial = null)
          LaunchedEffect(reg) { if (reg == RegistrationState.REGISTERED) controller.glasses.start() }
          val registered = reg == RegistrationState.REGISTERED

          if (s.alarm.isNotEmpty()) Btn("⚠ ${s.alarm}  (tap to clear)", Modifier.fillMaxWidth(), Bad) { controller.clearAlarm() }

          // ---- status
          val dist = if (s.hole) "HOLE" else if (s.distanceCm < 0) "no echo" else "${s.distanceCm} cm"
          Stat("GLASSES", if (s.usingPhoneCamera) "phone camera" else "${s.glasses} · ${reg?.toString()?.lowercase() ?: "…"}",
              if (s.usingPhoneCamera || s.glasses.startsWith("streaming")) Ok else Warn)
          Stat("CANE", if (s.caneOnline) "online · $dist" else "offline", if (s.caneOnline) Ok else Bad)
          Stat("SERVER", "${if (s.serverUp) "up" else "DOWN"} · ${s.serverUrl}", if (s.serverUp) Ok else Bad)
          Stat("YOLO", if (s.yoloReady) "${s.yoloMs} ms · ${s.yoloEngine}" else "loading…", if (s.yoloReady) Ok else Warn)
          Stat("SEES", s.yoloSeen.ifBlank { "—" }, Color.White)
          Stat("BUSY", s.busy.ifBlank { "idle" }, if (s.busy.isBlank()) Dim else Warn)
          Text(s.lastSaid.ifBlank { "(nothing said yet)" }, color = Color.White, fontSize = 16.sp)

          // ---- actions, 3 per row
          Grid(
              listOf(
                  "Describe" to { controller.describe() },
                  "Gemini only" to { controller.describeWithGemini() },
                  "Read" to { controller.readText() },
                  "Ask (voice)" to { controller.askByVoice() },
                  "Sky" to { controller.skyMode() },
                  "Find cane" to { controller.findCane() },
                  (if (s.autoAnnounce) "Guide: ON" else "Guide: OFF") to { controller.toggleAuto() },
                  "Test SOS" to { controller.testSos() },
                  (if (s.usingPhoneCamera) "Cam: phone" else "Cam: glasses") to { controller.usePhoneCamera(!s.usingPhoneCamera) },
                  (if (registered) "Restart glasses" else "Connect glasses") to {
                    if (registered) {
                      controller.glasses.stop()
                      controller.glasses.start()
                    } else Wearables.startRegistration(this@MainActivity)
                  },
                  "Mock glasses" to { controller.useMockGlasses() },
              ))

          // ---- settings
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

          // ---- log
          Text("LOG", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          s.log.forEach { Text(it, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
  }

  @Composable
  private fun Stat(key: String, value: String, color: Color) =
      Row {
        Text(key, color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(76.dp))
        Text(value, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }

  @Composable
  private fun Grid(items: List<Pair<String, () -> Unit>>) =
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (label, onClick) -> Btn(label, Modifier.weight(1f), onClick = onClick) }
            repeat(3 - row.size) { Text("", Modifier.weight(1f)) }
          }
        }
      }

  @Composable
  private fun Btn(label: String, modifier: Modifier = Modifier, color: Color = Color(0xFF2B3442), onClick: () -> Unit) =
      Button(onClick, modifier.height(48.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 6.dp),
          colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
            Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) controller.stop()
  }
}
