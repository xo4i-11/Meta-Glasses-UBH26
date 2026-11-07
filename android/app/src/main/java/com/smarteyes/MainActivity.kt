package com.smarteyes

import android.Manifest
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
import java.io.IOException
import kotlinx.coroutines.launch

const val OFFLINE = "Connection lost, the cane is still active."

class MainActivity : ComponentActivity() {
  private val backend by lazy { Backend(BuildConfig.BACKEND_URL, BuildConfig.SHARED_SECRET) }
  private val player by lazy { Player(this) }
  private lateinit var source: FrameSource
  private lateinit var status: TextView
  private var lastClientMs: Int? = null

  private val perms = registerForActivityResult(RequestMultiplePermissions()) { source.start() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    source = PhoneFrameSource(this)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
    status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); text = "Ready" }
    root.addView(status)
    root.addView(button("Describe") { onButton(1) })
    root.addView(button("Read text") { onButton(2) })
    setContentView(root)
    perms.launch(arrayOf(Manifest.permission.CAMERA))
  }

  private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    contentDescription = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
    minHeight = 220
    setOnClickListener { onClick() }
  }

  /** event: 1 PRESS (describe), 2 HOLD (read text) — same values as the cane BLE contract. */
  private fun onButton(event: Int) {
    val mode = if (event == 2) "read_text" else "describe"
    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    val t0 = SystemClock.elapsedRealtime()
    lifecycleScope.launch {
      val jpeg = source.latestJpeg() ?: return@launch setStatus("No camera frame")
      try {
        val reply = backend.frame(jpeg, mode, lastClientMs)
        player.play(reply, backend) {
          lastClientMs = (SystemClock.elapsedRealtime() - t0).toInt()
          setStatus("${reply.say}\n\n$mode: ${lastClientMs} ms (server ${reply.serverMs} ms)")
        }
      } catch (e: IOException) {
        player.speakLocal(OFFLINE)
        setStatus("Backend unreachable: ${e.message}")
      }
    }
  }

  private fun setStatus(s: String) = runOnUiThread { status.text = s }
}
