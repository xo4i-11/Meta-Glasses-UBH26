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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        Modifier.fillMaxSize().background(Color(0xFF0E1116)).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Smart Eyes", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
          if (!isReady) {
            Text("Smart Eyes needs Bluetooth permission to reach the glasses.", color = Color.White, fontSize = 20.sp)
            BigButton("Grant permissions") { permissionLauncher.launch(androidPermissions) }
            return@Column
          }
          val s by controller.ui.collectAsState()
          val reg by Wearables.registrationState.collectAsState(initial = null)
          LaunchedEffect(reg) { if (reg == RegistrationState.REGISTERED) controller.glasses.start() }

          if (s.alarm.isNotEmpty()) {
            Button(
                onClick = { controller.clearAlarm() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD64545))) {
                  Text(s.alarm, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
          }

          Status(s, reg)

          Text(
              s.lastSaid.ifBlank { "Use the cane joystick: press to describe, up to read, down to ask." },
              color = Color.White,
              fontSize = 21.sp,
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

          BigButton(if (s.busy == "describing") "Describing…" else "Describe (Gemini)") { controller.describe() }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Read text", Modifier.weight(1f)) { controller.readText() }
            BigButton("Ask", Modifier.weight(1f)) { controller.askByVoice() }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (s.autoAnnounce) "Announce: ON" else "Announce: OFF", Modifier.weight(1f)) { controller.toggleAuto() }
            BigButton("Find cane", Modifier.weight(1f)) { controller.findCane() }
          }

          Text("Announce every ${s.announceSeconds} s", color = Color(0xFFB8C2D0), fontSize = 15.sp)
          Slider(
              value = s.announceSeconds.toFloat(),
              onValueChange = { controller.setAnnounceSeconds(it.toInt()) },
              valueRange = 5f..10f,
              steps = 4)

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
              modifier = Modifier.fillMaxWidth())

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Test SOS text", Modifier.weight(1f)) { controller.testSos() }
            SmallButton("Sky Mode", Modifier.weight(1f)) { controller.skyMode() }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(if (s.usingPhoneCamera) "Phone camera: ON" else "Phone camera: OFF", Modifier.weight(1f)) {
              controller.usePhoneCamera(!s.usingPhoneCamera)
            }
            if (reg != RegistrationState.REGISTERED) {
              SmallButton("Connect glasses", Modifier.weight(1f)) { Wearables.startRegistration(this@MainActivity) }
            } else {
              SmallButton("Restart glasses", Modifier.weight(1f)) {
                controller.glasses.stop()
                controller.glasses.start()
              }
            }
          }
          SmallButton("Mock glasses (no Ray-Bans needed)", Modifier.fillMaxWidth()) { controller.useMockGlasses() }

          var server by remember { mutableStateOf(s.serverUrl) }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.weight(1f))
            SmallButton("Save") { controller.setServerUrl(server) }
          }

          s.log.forEach { Text(it, color = Color(0xFF8B95A5), fontSize = 13.sp) }
        }
  }

  @Composable
  private fun Status(s: UiState, reg: RegistrationState?) {
    val dist = if (s.hole) "HOLE" else if (s.distanceCm < 0) "clear" else "${s.distanceCm} cm"
    val lines =
        listOf(
            "Glasses: ${if (s.usingPhoneCamera) "phone camera" else s.glasses} (${reg?.toString()?.lowercase() ?: "…"})",
            "Cane: ${if (s.caneOnline) "online · $dist" else if (s.serverUp) "offline (still beeps on its own)" else "—"}",
            "Server: ${if (s.serverUp) "connected" else "can't reach ${s.serverUrl}"}${if (s.busy.isNotEmpty()) " · ${s.busy}" else ""}",
            "YOLO: ${if (s.yoloReady) "ready · ${s.yoloMs} ms/frame · ${s.yoloEngine}" else "loading…"}",
        )
    Column { lines.forEach { Text(it, color = Color(0xFFB8C2D0), fontSize = 15.sp) } }
  }

  @Composable
  private fun BigButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(68.dp)) { Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
  }

  @Composable
  private fun SmallButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(label, fontSize = 14.sp) }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) controller.stop()
  }
}
