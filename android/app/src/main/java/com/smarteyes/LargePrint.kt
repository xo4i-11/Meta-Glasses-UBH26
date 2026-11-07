package com.smarteyes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Large-Print Edition: the Smart Eyes visual system. Yellow board, ink, white page; one face (Atkinson
 * Hyperlegible, made by the Braille Institute) at three sizes only. No grey type, no shadows.
 */
object LP {
  val YELLOW = Color.parseColor("#FFD400")
  val INK = Color.parseColor("#111111")
  val PAGE = Color.WHITE

  const val SMALL = 13f
  const val BODY = 20f
  const val BIG = 31f
  /** The one exception to the three-step scale: the full-screen hazard flash. */
  const val HAZARD = 50f

  const val STROKE = 3 // dp
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.lpFont(bold: Boolean): Typeface =
    resources.getFont(if (bold) R.font.atkinson_bold else R.font.atkinson_regular)

/** A text line in the system: one of the three sizes, regular or bold, ink by default. */
fun Context.lpText(size: Float, bold: Boolean = false, color: Int = LP.INK) = TextView(this).apply {
  setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
  typeface = lpFont(bold)
  setTextColor(color)
  includeFontPadding = false
  setLineSpacing(0f, if (size >= LP.BIG) 1.12f else 1.2f)
}

/** Filled shape with the 3dp ink outline. Radii in dp: tl, tr, br, bl. */
fun Context.lpShape(fill: Int, vararg radii: Int, stroke: Int = LP.INK) = GradientDrawable().apply {
  setColor(fill)
  setStroke(dp(LP.STROKE), stroke)
  val r = if (radii.size == 1) IntArray(4) { radii[0] } else radii
  cornerRadii = floatArrayOf(
      dp(r[0]).toFloat(), dp(r[0]).toFloat(), dp(r[1]).toFloat(), dp(r[1]).toFloat(),
      dp(r[2]).toFloat(), dp(r[2]).toFloat(), dp(r[3]).toFloat(), dp(r[3]).toFloat())
}

fun View.haptic() = performHapticFeedback(HapticFeedbackConstants.CONFIRM)

/** Glasses / Cane / Server: name plus a state line. Connected = ink ground, yellow type. */
class StatusChip(context: Context, private val name: String) : LinearLayout(context) {
  private val title = context.lpText(LP.BODY, bold = true)
  private val state = context.lpText(LP.SMALL)
  var connected = false
    private set
  private var stateText = ""

  init {
    orientation = VERTICAL
    setPadding(context.dp(10), context.dp(8), context.dp(8), context.dp(8))
    minimumHeight = context.dp(64)
    title.text = name
    addView(title)
    addView(state)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    set(false, "off")
  }

  /** Returns true when the visible state changed (so the caller can announce it once). */
  fun set(on: Boolean, text: String): Boolean {
    val changed = on != connected || text != stateText
    connected = on
    stateText = text
    state.text = text
    background = context.lpShape(if (on) LP.INK else LP.YELLOW, 20)
    title.setTextColor(if (on) LP.YELLOW else LP.INK)
    state.setTextColor(if (on) LP.YELLOW else LP.INK)
    contentDescription = "$name, $text"
    return changed
  }
}

/** The one big control: Guidance on/off, exposed to TalkBack as a switch. Knob sits on the "on" side. */
class GuidancePill(context: Context, private val onToggle: (Boolean) -> Unit) : LinearLayout(context) {
  var on = false
    private set
  private val title = context.lpText(LP.BODY, bold = true)
  private val sub = context.lpText(LP.SMALL)
  private val knob = View(context)
  private val textCol = LinearLayout(context).apply {
    orientation = VERTICAL
    addView(title)
    addView(sub, LayoutParams(-2, -2).apply { topMargin = context.dp(4) })
  }

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = context.dp(96)
    setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    isClickable = true
    isFocusable = true
    setOnClickListener { haptic(); render(!on); onToggle(on) }
    render(false)
  }

  fun render(value: Boolean) {
    on = value
    background = context.lpShape(if (on) LP.INK else LP.YELLOW, 48)
    title.text = if (on) "Guidance on" else "Guidance off"
    sub.text = if (on) "Watching for head height" else "Tap to start"
    title.setTextColor(if (on) LP.YELLOW else LP.INK)
    sub.setTextColor(if (on) LP.YELLOW else LP.INK)
    knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) LP.YELLOW else LP.INK) }
    removeAllViews()
    val k = LayoutParams(context.dp(68), context.dp(68))
    val t = LayoutParams(0, -2, 1f).apply { marginStart = context.dp(16); marginEnd = context.dp(16) }
    if (on) { addView(textCol, t); addView(knob, k) } else { addView(knob, k); addView(textCol, t) }
    contentDescription = "Guidance"
    stateDescription = if (on) "On" else "Off"
  }

  override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
    super.onInitializeAccessibilityNodeInfo(info)
    info.className = android.widget.Switch::class.java.name
    info.isCheckable = true
    info.isChecked = on
  }
}

/** The cane button gesture drawn as marks: press = one dot, hold = one long bar, double press = two dots. */
class GestureMarks(context: Context, private val gesture: String) : View(context) {
  private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  var color = LP.INK
    set(v) { field = v; invalidate() }

  override fun onDraw(c: android.graphics.Canvas) {
    paint.color = color
    val r = context.dp(9).toFloat()
    val y = height / 2f
    when (gesture) {
      "hold" -> c.drawRoundRect(r, y - r, r + context.dp(64), y + r, r, r, paint)
      "twice" -> { c.drawCircle(r * 2, y, r, paint); c.drawCircle(r * 5, y, r, paint) }
      else -> c.drawCircle(r * 2, y, r, paint)
    }
  }
}

/** Describe / Read / Ask: a big white tile; the matching cane gesture is drawn above the label. */
class ActionTile(context: Context, label: String, gesture: String, onTap: () -> Unit) : LinearLayout(context) {
  private val marks = GestureMarks(context, gesture)

  init {
    orientation = VERTICAL
    gravity = Gravity.BOTTOM or Gravity.START
    setPadding(context.dp(12), context.dp(12), context.dp(10), context.dp(12))
    minimumHeight = context.dp(72)
    isClickable = true
    isFocusable = true
    background = StateListDrawable().apply {
      addState(intArrayOf(android.R.attr.state_pressed), context.lpShape(LP.INK, 20))
      addState(intArrayOf(), context.lpShape(LP.PAGE, 20))
    }
    val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(LP.YELLOW, LP.INK))
    addView(marks, LayoutParams(-1, context.dp(28)).apply { bottomMargin = context.dp(6) })
    addView(context.lpText(LP.BODY, bold = true).apply { text = label; setTextColor(colors); isDuplicateParentStateEnabled = true })
    addView(context.lpText(LP.SMALL).apply { text = "cane: $gesture"; setTextColor(colors); isDuplicateParentStateEnabled = true })
    contentDescription = "$label. On the cane: ${if (gesture == "twice") "double press" else gesture}."
    setOnClickListener { haptic(); onTap() }
  }

  override fun drawableStateChanged() {
    super.drawableStateChanged()
    marks.color = if (isPressed) LP.YELLOW else LP.INK
  }
}

/** Outlined pill for helper tools (secondary actions). */
fun Context.lpPill(label: String, onTap: (View) -> Unit) = lpText(LP.BODY, bold = true).apply {
  text = label
  contentDescription = label
  gravity = Gravity.CENTER_VERTICAL
  minHeight = dp(64)
  setPadding(dp(20), 0, dp(20), 0)
  background = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), lpShape(LP.INK, 48))
    addState(intArrayOf(), lpShape(LP.YELLOW, 48))
  }
  setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(LP.YELLOW, LP.INK)))
  isClickable = true
  setOnClickListener { haptic(); onTap(it) }
}

/** Makes a pill read as a switch to TalkBack, with its on/off state. */
fun View.asSwitch(isOn: () -> Boolean) {
  accessibilityDelegate = object : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
      super.onInitializeAccessibilityNodeInfo(host, info)
      info.className = android.widget.Switch::class.java.name
      info.isCheckable = true
      info.isChecked = isOn()
    }
  }
}
