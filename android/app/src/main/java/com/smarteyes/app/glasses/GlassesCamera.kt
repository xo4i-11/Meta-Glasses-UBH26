package com.smarteyes.app.glasses

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.smarteyes.app.vision.FrameUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Ray-Ban Meta camera via the Meta Wearables Device Access Toolkit (DAT 1.0.x).
 * API usage mirrors Meta's CameraAccess sample (facebook/meta-wearables-dat-android).
 *
 * Toolkit caveat: stream settings can't change mid-session. We pick one (MEDIUM, 15 fps, raw)
 * and keep it. Raw frames pause when the app is backgrounded, so keep the app on screen.
 */
class GlassesCamera(private val context: Context, private val scope: CoroutineScope) {
  private val _state = MutableStateFlow("not started")
  val state: StateFlow<String> = _state

  private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val frames: SharedFlow<Bitmap> = _frames

  /** Set by MainActivity: opens the Meta AI app to grant camera access to our app. */
  var requestPermission: (suspend (Permission) -> PermissionStatus)? = null

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private val jobs = mutableListOf<Job>()
  private var lastFrameAt = 0L
  var maxFps = 10
  private var wanted = false
  private var retryJob: Job? = null
  private var attempts = 0
  private var watchdog: Job? = null
  @Volatile private var aliveAt = 0L // last sign of life: session created, camera attached, or a frame

  fun start() {
    wanted = true
    startWatchdog()
    retryJob?.cancel()
    retryJob = null
    if (session != null) return
    _state.value = "connecting"
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { s ->
          session = s
          attempts = 0
          aliveAt = SystemClock.elapsedRealtime()
          jobs +=
              scope.launch {
                s.state.collect { st ->
                  Log.i(TAG, "session $st")
                  if (st == DeviceSessionState.STARTED) ensureCamera(s)
                  if (st == DeviceSessionState.STOPPED) {
                    // Glasses dropped (taken off, folded, Bluetooth hiccup), maybe before the camera was attached:
                    // always tidy up and, unless we were asked to stop, reconnect in 3 s.
                    cleanup("glasses disconnected, reconnecting…")
                    if (wanted) retryJob = scope.launch {
                      delay(3000)
                      if (wanted && session == null) start()
                    }
                  }
                }
              }
          s.start()
        }
        .onFailure { error, _ ->
          attempts++
          Log.e(TAG, "createSession failed (try $attempts): ${error.description}")
          // Glasses asleep, folded, in the case or still linking to Meta AI: keep trying every 3 s
          _state.value = "waiting for glasses (put them on, try $attempts): ${error.description}"
          if (wanted) retryJob = scope.launch {
            delay(3000)
            if (wanted && session == null) start()
          }
        }
  }

  private fun ensureCamera(s: DeviceSession) {
    if (camera != null) return
    scope.launch {
      val status =
          Wearables.checkPermissionStatus(Permission.CAMERA).fold(onSuccess = { it }, onFailure = { _, _ -> PermissionStatus.Denied })
      val granted = status == PermissionStatus.Granted || requestPermission?.invoke(Permission.CAMERA) == PermissionStatus.Granted
      if (!granted) {
        _state.value = "camera permission denied (grant it in the Meta AI app)"
        return@launch
      }
      s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15, compressVideo = false))
          .onSuccess { cam ->
            camera = cam
            aliveAt = SystemClock.elapsedRealtime()
            val st = cam.stream
            stream = st
            jobs += scope.launch { st.videoStream.collect { onFrame(it) } }
            jobs += scope.launch { st.state.collect { _state.value = it.toString().lowercase() } }
            st.start().onFailure { error, _ -> _state.value = "stream failed: ${error.description}" }
          }
          .onFailure { error, _ -> _state.value = "camera failed: ${error.description}" }
    }
  }

  private fun onFrame(frame: VideoFrame) {
    if (frame.isCompressed) return // we asked for raw; ignore HEVC
    val now = SystemClock.elapsedRealtime()
    aliveAt = now
    if (now - lastFrameAt < 1000L / maxFps) return
    lastFrameAt = now
    FrameUtils.i420ToBitmap(frame.buffer, frame.width, frame.height)?.let { _frames.tryEmit(it) }
  }

  /** Full-quality still for reading text. Slower than grabbing a video frame (~1 s). */
  suspend fun capturePhoto(): Bitmap? {
    val st = stream ?: return null
    return st.capturePhoto().fold(onSuccess = { decode(it) }, onFailure = { error, _ ->
      Log.w(TAG, "capturePhoto failed: ${error.description}")
      null
    })
  }

  private fun decode(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val buf = photo.data.duplicate().apply { rewind() }
          val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
          FrameUtils.decodeWithExif(bytes)
        }
      }

  /**
   * The session can die without ever reporting STOPPED (e.g. "Link switch timed out", "Timed out bootstrapping
   * DWA stream channel"), leaving us stuck at "starting". No sign of life for 15 s -> tear down and reconnect.
   * ponytail: 15 s also covers the Meta AI permission screen; raise it if that prompt gets cut off.
   */
  private fun startWatchdog() {
    if (watchdog?.isActive == true) return
    watchdog = scope.launch {
      while (true) {
        delay(5000)
        val idle = SystemClock.elapsedRealtime() - aliveAt
        if (wanted && session != null && idle > 15_000) {
          Log.w(TAG, "no video for ${idle / 1000} s, reconnecting")
          camera?.stop()
          session?.stop()
          cleanup("no video, reconnecting…")
          delay(3000)
          if (wanted && session == null) start()
        }
      }
    }
  }

  fun stop() {
    wanted = false
    watchdog?.cancel()
    retryJob?.cancel()
    camera?.stop()
    session?.stop()
    cleanup("stopped")
  }

  private fun cleanup(reason: String) {
    jobs.forEach { it.cancel() }
    jobs.clear()
    camera = null
    stream = null
    session = null
    _state.value = reason
  }

  /**
   * Mock Device Kit: fake Ray-Bans that stream the PHONE's back camera through the real DAT code
   * path. Lets person 1 build everything before the glasses are free. Call before [start].
   */
  suspend fun enableMockGlasses(): Boolean {
    val kit = MockDeviceKit.getInstance(context.applicationContext)
    kit.enable()
    val device =
        kit.pairGlasses(GlassesModel.RAYBAN_META)
            .fold(
                onSuccess = { it },
                onFailure = { error, _ ->
                  Log.e(TAG, "Mock pairing failed: $error")
                  null
                }) ?: return false
    device.powerOn()
    device.unfold()
    device.don()
    device.services.camera.setCameraFeed(CameraFacing.BACK)
    return true
  }

  companion object {
    private const val TAG = "GlassesCamera"
  }
}
