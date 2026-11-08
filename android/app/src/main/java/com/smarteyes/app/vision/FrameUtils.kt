package com.smarteyes.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object FrameUtils {

  /**
   * Raw (uncompressed) DAT video frames are I420 (Y plane, then U, then V). Verified on real
   * Ray-Ban Meta glasses on 2026-11-07: colours correct.
   *
   * Converts straight to RGB (BT.601), sampling every [step]-th pixel so the long side is at most
   * [maxSide]. YOLO only needs ~416 px and Gemini gets 768 px; "read text" uses a full-res photo.
   * (The old version went YUV -> JPEG -> Bitmap, which was slow and blurred every frame.)
   */
  private var rgbScratch = IntArray(0)

  @Synchronized
  fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int, maxSide: Int = 960): Bitmap? {
    val ySize = width * height
    val cw = (width + 1) / 2
    val chh = (height + 1) / 2
    val uvSize = cw * chh
    val src = buffer.duplicate()
    if (src.remaining() < ySize + 2 * uvSize) return null
    val base = src.position()
    val step = maxOf(1, (maxOf(width, height) + maxSide - 1) / maxSide)
    val ow = width / step
    val oh = height / step
    if (rgbScratch.size != ow * oh) rgbScratch = IntArray(ow * oh)
    val out = rgbScratch
    val uBase = base + ySize
    val vBase = uBase + uvSize
    var o = 0
    for (oy in 0 until oh) {
      val y = oy * step
      val yRow = base + y * width
      val cRow = (y shr 1) * cw
      for (ox in 0 until ow) {
        val x = ox * step
        val yy = ((src.get(yRow + x).toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
        val ci = cRow + (x shr 1)
        val u = (src.get(uBase + ci).toInt() and 0xFF) - 128
        val v = (src.get(vBase + ci).toInt() and 0xFF) - 128
        val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
        val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
        val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
        out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
  }

  /** Shrink + compress before upload: smaller photo = faster Gemini reply. */
  fun toJpeg(bitmap: Bitmap, maxSide: Int = 768, quality: Int = 75): ByteArray {
    val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled =
        if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
  }

  /** Decodes a JPEG/HEIC photo from the glasses and applies its EXIF rotation. */
  fun decodeWithExif(bytes: ByteArray): Bitmap? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val degrees =
        runCatching {
              when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
              }
            }
            .getOrDefault(0f)
    return rotate(bmp, degrees)
  }

  fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }
}
