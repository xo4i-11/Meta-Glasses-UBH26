package com.smarteyes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * On-site tuning overlay: the frame YOLO saw, its boxes (amber = hazard class, grey = other),
 * the head-height line at TOP_ZONE, and whether a warning fired.
 */
class DebugView(context: Context) : View(context) {
  private var frame: Bitmap? = null
  private var dets: List<Det> = emptyList()
  private var banner = "—"
  private var bannerColor = Color.GRAY
  private val dst = RectF()
  private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f }
  private val text = Paint().apply { textSize = 40f; isFakeBoldText = true; isAntiAlias = true }
  private val zone = Paint().apply { color = Color.CYAN; strokeWidth = 5f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(30f, 20f), 0f) }
  private val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }

  /** Call on the main thread. */
  fun show(bitmap: Bitmap?, scan: YoloScan, warn: Det?, status: HazardStatus?) {
    frame = bitmap
    dets = scan.dets
    val hit = headHazard(scan.dets)
    banner = when {
      warn != null -> "WARN ${warn.label} ${(warn.conf * 100).toInt()}% → ${status?.wire?.uppercase()}"
      hit != null -> "in zone: ${hit.label} ${(hit.conf * 100).toInt()}% (already warned / persisting)"
      else -> "no head-height hazard"
    } + "   ${scan.latencyMs} ms"
    bannerColor = if (warn != null) Color.rgb(245, 165, 36) else Color.LTGRAY
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val w = MeasureSpec.getSize(widthMeasureSpec)
    val f = frame
    val h = if (f != null && f.width > 0) w * f.height / f.width else w * 16 / 9
    setMeasuredDimension(w, h)
  }

  override fun onDraw(c: Canvas) {
    val f = frame ?: return
    if (height != width * f.height / f.width) requestLayout()
    dst.set(0f, 0f, width.toFloat(), height.toFloat())
    c.drawBitmap(f, null, dst, null)
    val zy = HazardConfig.TOP_ZONE * height
    c.drawLine(0f, zy, width.toFloat(), zy, zone)
    text.color = Color.CYAN
    c.drawText("head-height zone (box centre above)", 16f, zy - 12f, text)
    for (d in dets) {
      val hazard = d.label in HazardConfig.HAZARD_CLASSES
      box.color = if (hazard) Color.rgb(245, 165, 36) else Color.GRAY
      val l = (d.cx - d.w / 2) * width; val t = (d.cy - d.h / 2) * height
      c.drawRect(l, t, (d.cx + d.w / 2) * width, (d.cy + d.h / 2) * height, box)
      text.color = box.color
      c.drawText("${d.label} ${(d.conf * 100).toInt()}% a=${"%.2f".format(d.w * d.h)}", l + 8f, t + 44f, text)
      c.drawCircle(d.cx * width, d.cy * height, 10f, box)
    }
    c.drawRect(0f, height - 70f, width.toFloat(), height.toFloat(), bg)
    text.color = bannerColor
    c.drawText(banner, 16f, height - 22f, text)
  }
}
