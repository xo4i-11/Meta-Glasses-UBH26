package com.smarteyes

import android.Manifest
import android.os.Bundle
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val backend by lazy { Backend(BuildConfig.BACKEND_URL, BuildConfig.SHARED_SECRET) }
  private val player by lazy { Player(this) }
  private lateinit var phone: PhoneFrameSource
  private lateinit var glasses: GlassesFrameSource
  private lateinit var source: FrameSource
  private lateinit var status: TextView
  private lateinit var sourceBtn: Button
  private lateinit var hub: Hub
  private var datReady = false

  private val perms = registerForActivityResult(RequestMultiplePermissions()) {
    Wearables.initialize(this)
    datReady = true
    phone.start()
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
    hub = Hub(lifecycleScope, { source }, backend, player, ::setStatus, ::vibrate)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
    status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); text = "Ready" }
    root.addView(status)
    root.addView(button("Describe") { hub.onButton(1) })
    root.addView(button("Read text") { hub.onButton(2) })
    root.addView(button("Hazard warnings: off") {
      if (hub.hazardsOn) hub.stopHazards() else hub.startHazards()
      (it as Button).text = if (hub.hazardsOn) "Hazard warnings: on" else "Hazard warnings: off"
    })
    sourceBtn = button("Camera: phone. Tap to connect glasses") { toggleSource() }
    root.addView(sourceBtn)
    setContentView(root)
    perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT))
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
        setStatus("Registering with Meta AI app… tap again when done")
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

  private fun vibrate() =
      getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

  private fun setStatus(s: String) = runOnUiThread { status.text = s }
}
