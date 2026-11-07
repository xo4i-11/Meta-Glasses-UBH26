package com.smarteyes

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
