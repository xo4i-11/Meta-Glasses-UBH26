package com.smarteyes.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Detection(val label: String, val score: Float, val box: RectF) // box normalised 0..1 of the frame

/**
 * On-phone YOLO11n via ONNX Runtime. No internet needed.
 *
 * Model: app/src/main/assets/yolo11n.onnx, exported by tools/export_yolo.py with a DYNAMIC input.
 * Each frame keeps its aspect ratio (letterbox on grey 114, like YOLO's training): long side
 * [longSide], short side rounded up to a multiple of 32. A portrait 9:16 frame runs at 256x416,
 * about the same work as the old stretched 320x320 but with no distortion and more detail.
 * Output [1, 84, N] = cx, cy, w, h (input pixels) + 80 COCO class scores.
 * tools/verify_yolo_decode.py mirrors this code and checks it against Ultralytics.
 */
class ObstacleDetector(context: Context, modelName: String = "yolo11n.onnx", private val longSide: Int = 416) {
  private val env = OrtEnvironment.getEnvironment()
  private var session: OrtSession? = null
  private var inputName = "images"
  private var fixedSize = 0 // >0 if an old fixed-shape model is bundled
  var engine = "none"
    private set
  val isReady get() = session != null

  // reused between frames (one detect() at a time)
  private var lbBitmap: Bitmap? = null
  private var pixels = IntArray(0)
  private var input = FloatArray(0)
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

  init {
    try {
      val bytes = context.assets.open(modelName).use { it.readBytes() }
      val probe = env.createSession(bytes, OrtSession.SessionOptions())
      inputName = probe.inputNames.first()
      val shape = (probe.inputInfo[inputName]!!.info as TensorInfo).shape // [1,3,H,W], -1 if dynamic
      fixedSize = if (shape[2] > 0) shape[2].toInt() else 0
      probe.close()
      session = pickFastest(bytes)
      Log.i(TAG, "YOLO ready: $modelName, ${if (fixedSize > 0) "fixed ${fixedSize}px" else "dynamic, long side $longSide"}, engine $engine")
    } catch (e: Exception) {
      Log.e(TAG, "Could not load $modelName. Camera warnings disabled; the cane still works.", e)
    }
  }

  /**
   * XNNPACK is sometimes faster on ARM, sometimes slower (it splits the graph). Time both on THIS
   * phone for ~0.5 s at startup and keep the winner. With XNNPACK the ORT pool must be 1 thread.
   */
  private fun pickFastest(bytes: ByteArray): OrtSession {
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    val cpu = env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(cores) })
    val xnn =
        runCatching {
              env.createSession(
                  bytes,
                  OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    addXnnpack(mapOf("intra_op_num_threads" to cores.toString()))
                  })
            }
            .getOrNull()
    val h = if (fixedSize > 0) fixedSize else longSide
    val w = if (fixedSize > 0) fixedSize else 256
    val cpuMs = bench(cpu, w, h)
    val xnnMs = xnn?.let { bench(it, w, h) } ?: Long.MAX_VALUE
    Log.i(TAG, "benchmark ${w}x$h: cpu ${cpuMs} ms, xnnpack ${if (xnn == null) "n/a" else "$xnnMs ms"}")
    return if (xnn != null && xnnMs < cpuMs) {
      cpu.close()
      engine = "xnnpack"
      xnn
    } else {
      xnn?.close()
      engine = "cpu×$cores"
      cpu
    }
  }

  private fun bench(s: OrtSession, w: Int, h: Int): Long {
    val buf = FloatBuffer.allocate(3 * w * h)
    var best = Long.MAX_VALUE
    repeat(4) { i ->
      val t = SystemClock.elapsedRealtime()
      OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor -> s.run(mapOf(inputName to tensor)).close() }
      if (i > 0) best = min(best, SystemClock.elapsedRealtime() - t) // first run = warm-up
      buf.rewind()
    }
    return best
  }

  @Synchronized
  fun detect(bitmap: Bitmap, minScore: Float = 0.3f): List<Detection> {
    val s = session ?: return emptyList()
    // 1) input shape that keeps the aspect ratio
    val (w, h) =
        if (fixedSize > 0) fixedSize to fixedSize
        else if (bitmap.width >= bitmap.height) longSide to roundUp32(bitmap.height.toFloat() * longSide / bitmap.width)
        else roundUp32(bitmap.width.toFloat() * longSide / bitmap.height) to longSide
    // 2) letterbox: scale to fit, centre on grey
    val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
    val nw = (bitmap.width * scale).roundToInt()
    val nh = (bitmap.height * scale).roundToInt()
    val dx = (w - nw) / 2
    val dy = (h - nh) / 2
    val lb = lbBitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { lbBitmap = it }
    Canvas(lb).apply {
      drawColor(Color.rgb(114, 114, 114))
      drawBitmap(bitmap, null, RectF(dx.toFloat(), dy.toFloat(), (dx + nw).toFloat(), (dy + nh).toFloat()), paint)
    }
    // 3) NCHW floats 0..1
    val plane = w * h
    if (pixels.size != plane) {
      pixels = IntArray(plane)
      input = FloatArray(3 * plane)
    }
    lb.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in 0 until plane) {
      val p = pixels[i]
      input[i] = ((p shr 16) and 0xFF) * INV255
      input[plane + i] = ((p shr 8) and 0xFF) * INV255
      input[2 * plane + i] = (p and 0xFF) * INV255
    }
    // 4) run + decode straight from the output buffer (no boxing into nested arrays)
    val raw = ArrayList<Detection>()
    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
      s.run(mapOf(inputName to tensor)).use { result ->
        val outT = result[0] as OnnxTensor
        val shape = outT.info.shape // [1, 84, N]
        val ch = shape[1].toInt()
        val n = shape[2].toInt()
        val out = outT.floatBuffer
        for (i in 0 until n) {
          var best = -1
          var bestScore = minScore
          for (c in 4 until ch) {
            val sc = out.get(c * n + i)
            if (sc > bestScore) {
              bestScore = sc
              best = c - 4
            }
          }
          if (best < 0) continue
          val cx = out.get(i)
          val cy = out.get(n + i)
          val bw = out.get(2 * n + i)
          val bh = out.get(3 * n + i)
          // undo the letterbox -> 0..1 of the original frame
          val l = ((cx - bw / 2 - dx) / scale / bitmap.width).coerceIn(0f, 1f)
          val t = ((cy - bh / 2 - dy) / scale / bitmap.height).coerceIn(0f, 1f)
          val r = ((cx + bw / 2 - dx) / scale / bitmap.width).coerceIn(0f, 1f)
          val b = ((cy + bh / 2 - dy) / scale / bitmap.height).coerceIn(0f, 1f)
          raw += Detection(COCO.getOrElse(best) { "object" }, bestScore, RectF(l, t, r, b))
        }
      }
    }
    return nms(raw)
  }

  private fun roundUp32(v: Float) = max(32, (ceil(v / 32f) * 32f).toInt())

  private fun nms(dets: List<Detection>, iouThresh: Float = 0.5f): List<Detection> {
    val result = ArrayList<Detection>()
    for ((_, group) in dets.groupBy { it.label }) {
      val sorted = group.sortedByDescending { it.score }.toMutableList()
      while (sorted.isNotEmpty()) {
        val top = sorted.removeAt(0)
        result += top
        sorted.removeAll { iou(top.box, it.box) > iouThresh }
      }
    }
    return result
  }

  private fun iou(a: RectF, b: RectF): Float {
    val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
    val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
    val inter = ix * iy
    val union = a.width() * a.height() + b.width() * b.height() - inter
    return if (union <= 0f) 0f else inter / union
  }

  fun close() = session?.close()

  companion object {
    private const val TAG = "ObstacleDetector"
    private const val INV255 = 1f / 255f
    val COCO =
        listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
            "teddy bear", "hair drier", "toothbrush")
  }
}
