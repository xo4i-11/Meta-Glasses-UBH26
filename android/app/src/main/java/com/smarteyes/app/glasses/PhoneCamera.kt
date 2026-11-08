package com.smarteyes.app.glasses

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.smarteyes.app.vision.FrameUtils
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Backup "eyes": the phone's own back camera (phone on a chest strap / lanyard).
 * Used when the glasses are unavailable, so the demo never depends on one Bluetooth link.
 */
class PhoneCamera(private val context: Context) {
  private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val frames: SharedFlow<Bitmap> = _frames
  private val executor = Executors.newSingleThreadExecutor()
  private var provider: ProcessCameraProvider? = null
  private var lastFrameAt = 0L
  var maxFps = 8

  fun start() {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        {
          val p = future.get()
          provider = p
          val analysis =
              ImageAnalysis.Builder()
                  .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                  .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                  .build()
          analysis.setAnalyzer(executor) { image ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt >= 1000L / maxFps) {
              lastFrameAt = now
              val bmp = FrameUtils.rotate(image.toBitmap(), image.imageInfo.rotationDegrees.toFloat())
              _frames.tryEmit(bmp)
            }
            image.close()
          }
          p.unbindAll()
          p.bindToLifecycle(ProcessLifecycleOwner.get(), CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        },
        ContextCompat.getMainExecutor(context))
  }

  fun stop() {
    ContextCompat.getMainExecutor(context).execute { provider?.unbindAll() }
  }
}
