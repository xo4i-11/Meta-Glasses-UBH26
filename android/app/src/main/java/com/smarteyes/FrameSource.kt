package com.smarteyes

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

interface FrameSource {
  suspend fun latestJpeg(): ByteArray?
  fun start()
  fun stop()
}

/** Scale so the long edge is 512 px, JPEG q80. */
fun Bitmap.toJpeg512(): ByteArray {
  val scale = 512f / maxOf(width, height)
  val b = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
  return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}

/** Fallback when the glasses stream isn't available: Pixel rear camera via CameraX. */
class PhoneFrameSource(private val activity: ComponentActivity) : FrameSource {
  private var provider: ProcessCameraProvider? = null
  private var capture: ImageCapture? = null
  private val executor = Executors.newSingleThreadExecutor()

  override fun start() {
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
      val p = future.get()
      val c = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
      p.unbindAll()
      p.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, c)
      provider = p
      capture = c
    }, activity.mainExecutor)
  }

  override suspend fun latestJpeg(): ByteArray? {
    val c = capture ?: return null
    return suspendCancellableCoroutine { cont ->
      c.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          val rot = image.imageInfo.rotationDegrees
          val bmp = image.toBitmap()
          image.close()
          val upright = if (rot == 0) bmp else
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
          cont.resume(upright.toJpeg512())
        }

        override fun onError(e: ImageCaptureException) = cont.resume(null)
      })
    }
  }

  override fun stop() {
    provider?.unbindAll()
    capture = null
  }
}

/** Planar I420 (Y, U, V) -> NV21 (Y, interleaved VU) for YuvImage. */
fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
  val ySize = width * height
  val q = ySize / 4
  val out = i420.copyOf(ySize + 2 * q)
  for (i in 0 until q) {
    out[ySize + 2 * i] = i420[ySize + q + i] // V
    out[ySize + 2 * i + 1] = i420[ySize + i] // U
  }
  return out
}

private class Frame(val yuv: ByteArray, val width: Int, val height: Int)

/**
 * Ray-Ban Meta camera via the Device Access Toolkit. Keeps the latest decoded frame in memory.
 * One fixed StreamConfiguration for the whole session (toolkit can't change it mid-session).
 */
class GlassesFrameSource(private val scope: CoroutineScope, private val onStatus: (String) -> Unit) : FrameSource {
  private val latest = AtomicReference<Frame?>(null)
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private val jobs = mutableListOf<Job>()

  override fun start() {
    if (session != null) return
    Wearables.createSession(AutoDeviceSelector()).fold(
        onSuccess = { s ->
          session = s
          jobs += scope.launch { s.errors.collect { onStatus("Glasses error: ${it.description}") } }
          jobs += scope.launch {
            s.state.collect { st ->
              onStatus("Glasses: $st")
              if (st == DeviceSessionState.STARTED && camera == null) addCamera(s)
              if (st == DeviceSessionState.STOPPED) stop()
            }
          }
          s.start()
        },
        onFailure = { e, _ -> onStatus("Glasses: ${e.description}") },
    )
  }

  private fun addCamera(s: DeviceSession) {
    s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7)).fold(
        onSuccess = { c ->
          camera = c
          jobs += scope.launch(Dispatchers.Default) {
            c.stream.videoStream.collect { f ->
              if (f.isCompressed) return@collect
              val buf = f.buffer.duplicate().apply { rewind() }
              latest.set(Frame(ByteArray(buf.remaining()).also { buf.get(it) }, f.width, f.height))
            }
          }
          jobs += scope.launch { c.stream.errorStream.collect { onStatus("Stream error: ${it.description}") } }
          c.stream.start().onFailure { e, _ -> onStatus("Stream: ${e.description}") }
        },
        onFailure = { e, _ -> onStatus("Camera: ${e.description}") },
    )
  }

  // ponytail: assumes tightly packed I420 (decoder color-format 19, no stride padding). If frames look
  // sheared on device, read stride/slice-height from the decoder or switch to stream.capturePhoto().
  override suspend fun latestJpeg(): ByteArray? = withContext(Dispatchers.Default) {
    val f = latest.get() ?: return@withContext null
    if (f.yuv.size < f.width * f.height * 3 / 2) return@withContext null
    val nv21 = i420ToNv21(f.yuv, f.width, f.height)
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, f.width, f.height, null).compressToJpeg(Rect(0, 0, f.width, f.height), 90, out)
    val bmp = android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    bmp.toJpeg512()
  }

  override fun stop() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    camera?.stop()
    session?.stop()
    camera = null
    session = null
    latest.set(null)
  }
}
