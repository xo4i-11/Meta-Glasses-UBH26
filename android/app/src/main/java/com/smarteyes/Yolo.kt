package com.smarteyes

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer

val COCO = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
    "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
)

/**
 * Decode a YOLO11 output tensor flattened channels-first: out[c * anchors + i], c = 0..83.
 * Rows 0-3 = cx, cy, w, h in input pixels; rows 4-83 = class scores. Returns normalised Dets after class-wise NMS.
 */
fun decodeYolo(out: FloatArray, anchors: Int, minConf: Float, iou: Float = 0.45f, inputSize: Float = 320f): List<Det> {
  val dets = ArrayList<Det>()
  for (i in 0 until anchors) {
    var best = -1
    var score = minConf
    for (c in 0 until 80) {
      val s = out[(4 + c) * anchors + i]
      if (s >= score) { score = s; best = c }
    }
    if (best < 0) continue
    dets += Det(COCO[best], score,
        out[i] / inputSize, out[anchors + i] / inputSize, out[2 * anchors + i] / inputSize, out[3 * anchors + i] / inputSize)
  }
  // ponytail: O(n²) NMS — fine for the handful of boxes left after the confidence cut.
  val kept = ArrayList<Det>()
  for (d in dets.sortedByDescending { it.conf }) {
    if (kept.none { it.label == d.label && iouOf(it, d) > iou }) kept += d
  }
  return kept
}

private fun iouOf(a: Det, b: Det): Float {
  val ix = maxOf(0f, minOf(a.cx + a.w / 2, b.cx + b.w / 2) - maxOf(a.cx - a.w / 2, b.cx - b.w / 2))
  val iy = maxOf(0f, minOf(a.cy + a.h / 2, b.cy + b.h / 2) - maxOf(a.cy - a.h / 2, b.cy - b.h / 2))
  val inter = ix * iy
  return inter / (a.w * a.h + b.w * b.h - inter)
}

/** YOLO11n (COCO-80) on ONNX Runtime, 320×320 input. Call detect() off the main thread. */
class Yolo(context: Context) : Closeable {
  private val env = OrtEnvironment.getEnvironment()
  private val session: OrtSession = env.createSession(
      context.assets.open("yolo11n_320.onnx").use { it.readBytes() },
      OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
  private val inputName = session.inputNames.first()
  private val size = 320
  private val pixels = IntArray(size * size)
  private val chw = FloatBuffer.allocate(3 * size * size)

  @Synchronized
  fun detect(bitmap: Bitmap, minConf: Float = 0.25f): List<Det> {
    // ponytail: plain stretch to 320×320 (no letterbox). Boxes stay normalised to the frame, which is what the
    // head-height rule needs; slight aspect distortion only nudges confidence.
    val b = Bitmap.createScaledBitmap(bitmap, size, size, true)
    b.getPixels(pixels, 0, size, 0, 0, size, size)
    chw.clear()
    val plane = size * size
    for (i in 0 until plane) {
      val p = pixels[i]
      chw.put(i, ((p shr 16) and 0xFF) / 255f)
      chw.put(plane + i, ((p shr 8) and 0xFF) / 255f)
      chw.put(2 * plane + i, (p and 0xFF) / 255f)
    }
    OnnxTensor.createTensor(env, chw, longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
      session.run(mapOf(inputName to input)).use { result ->
        val t = result[0] as OnnxTensor
        val anchors = t.info.shape[2].toInt()
        val fb = t.floatBuffer
        val out = FloatArray(fb.remaining()).also { fb.get(it) }
        return decodeYolo(out, anchors, minConf, inputSize = size.toFloat())
      }
    }
  }

  override fun close() {
    session.close()
  }
}
