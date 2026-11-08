package com.smarteyes.app.vision

/**
 * YOLO-first answers, built on the phone in ~0.1 s with no internet. Gemini is only asked when these return null.
 * Wording rules come from the owner: path first, then left/right, at most 3 things, no metres.
 */

/** One detection, normalised 0..1 (y down). Plain data so the wording can be unit-tested off-device. */
data class Det(val label: String, val conf: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

const val PATH_CLEAR = "Your path appears clear, but I can't guarantee there are no obstacles."

private const val MIN_CONF = 0.45f
private const val MIN_AREA = 0.01f

private enum class Pos(val phrase: String) {
  LEFT("on your left"), SLIGHT_LEFT("slightly to your left"), AHEAD("ahead of you"),
  SLIGHT_RIGHT("slightly to your right"), RIGHT("on your right"),
}

private fun posOf(cx: Float) = when {
  cx < 0.25f -> Pos.LEFT
  cx < 0.40f -> Pos.SLIGHT_LEFT
  cx <= 0.60f -> Pos.AHEAD
  cx <= 0.75f -> Pos.SLIGHT_RIGHT
  else -> Pos.RIGHT
}

private fun usable(d: Det) = d.conf >= MIN_CONF && d.w * d.h >= MIN_AREA

private val COUNT = listOf("", "a", "two", "three", "four", "five")

private fun plural(label: String) = when {
  label == "person" -> "people"
  label.endsWith("ch") || label.endsWith("sh") || label.endsWith("s") -> label + "es"
  else -> label + "s"
}

private fun nounPhrase(label: String, n: Int): String = when (n) {
  1 -> (if (label.first() in "aeiou") "an " else "a ") + label
  else -> "${COUNT.getOrElse(n) { "several" }} ${plural(label)}"
}

/** A short spoken description of the frame, or null when YOLO found nothing confident (ask Gemini instead). */
fun describeLocal(dets: List<Det>): String? {
  val groups = dets.filter(::usable)
      .groupBy { it.label to posOf(it.cx) }
      .map { (key, ds) -> Triple(key.first, key.second, ds) }
  if (groups.isEmpty()) return null
  val top = groups
      .sortedWith(compareByDescending<Triple<String, Pos, List<Det>>> { it.second == Pos.AHEAD }
          .thenByDescending { g -> g.third.maxOf { it.w * it.h } })
      .take(3)
  val parts = top.map { (label, pos, ds) -> "${nounPhrase(label, ds.size)} ${pos.phrase}" }
  val opener = if (top.first().third.size > 1) "There are " else "There's "
  val list = when (parts.size) {
    1 -> parts[0]
    2 -> "${parts[0]} and ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
  }
  val warn = if (top.any { it.second == Pos.AHEAD }) " Keep an eye on the obstacle ahead." else ""
  return "$opener$list.$warn"
}
