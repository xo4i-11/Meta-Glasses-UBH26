package com.smarteyes.app.vision

/**
 * Turns YOLO detections into speech, without being asked:
 *
 *  1. ANNOUNCE every [announceEveryMs] (5-10 s): a short summary of what's in view,
 *     closest first. "Chair ahead, close. Two people on your left. Car on your right."
 *     Skipped when nothing is in view or the scene hasn't changed in the last 20 s.
 *  2. URGENT, immediately: something big right in front or at head height.
 *     "Umbrella at head height, duck." Can be turned off with [urgentEnabled].
 *
 * Note: YOLO knows the 80 COCO classes only (no "branch", no "keys"). Gemini covers the rest
 * when the user presses describe.
 */
class HazardEngine {
  data class Seen(val label: String, val direction: String, val near: Boolean, val headHeight: Boolean, val area: Float)

  var announceEveryMs = 7000L
  var urgentEnabled = true

  /** Last ~1.5 s of frames. Objects must show up in 2+ of them (kills one-frame ghosts), and a
   *  single missed frame no longer drops an object from the summary. */
  private val recent = ArrayDeque<Pair<Long, List<Seen>>>()
  var windowMs = 1500L
  private val urgentCooldown = HashMap<String, Long>()
  private var lastUrgentAt = 0L
  private var lastAnnounceAt = 0L
  private var lastAnnounceText = ""
  private var lastAnnounceTextAt = 0L

  /** Labels worth announcing. Small table-top things are left for the memory log. */
  private val announceable =
      setOf(
          "person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light", "fire hydrant", "stop sign",
          "parking meter", "bench", "dog", "cat", "backpack", "umbrella", "handbag", "suitcase", "kite", "chair",
          "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "refrigerator", "sink", "oven",
          "skateboard", "sports ball", "bottle", "cup", "cell phone", "book", "clock", "vase")

  private val obstacles =
      setOf(
          "person", "bicycle", "car", "motorcycle", "bus", "truck", "fire hydrant", "stop sign", "parking meter",
          "bench", "dog", "backpack", "umbrella", "suitcase", "kite", "chair", "couch", "potted plant", "bed",
          "dining table", "toilet", "tv", "refrigerator", "skateboard")

  fun describe(d: Detection): Seen {
    val b = d.box
    val cx = b.centerX()
    val direction = if (cx < 0.33f) "left" else if (cx > 0.67f) "right" else "ahead"
    val head = b.top < 0.2f && b.bottom < 0.55f && b.width() > 0.15f
    val near = b.height() > 0.45f || b.width() * b.height() > 0.18f
    return Seen(d.label, direction, near, head, b.width() * b.height())
  }

  /** Call on every frame. Returns at most one thing to say right now (urgent wins). */
  fun process(dets: List<Detection>, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
    val seen = dets.filter { it.label in announceable }.map(::describe)
    recent.addLast(now to seen)
    while (recent.isNotEmpty() && (now - recent.first().first > windowMs || recent.size > 20)) recent.removeFirst()
    fun stable(label: String) = recent.count { (_, s) -> s.any { it.label == label } } >= minOf(2, recent.size)

    // 1) urgent
    if (urgentEnabled && now - lastUrgentAt > 1500) {
      val hit =
          seen
              .filter { it.label in obstacles && stable(it.label) && (it.headHeight || (it.near && it.direction == "ahead")) }
              .filter { now - (urgentCooldown[it.label] ?: 0L) > 5000 }
              .maxByOrNull { it.area + if (it.headHeight) 1f else 0f }
      if (hit != null) {
        urgentCooldown[hit.label] = now
        lastUrgentAt = now
        lastAnnounceAt = now // push the next summary back so they don't collide
        val name = hit.label.replaceFirstChar { it.uppercase() }
        val text = if (hit.headHeight) "$name at head height, duck" else "$name right ahead, step ${if (hit.direction == "left") "right" else "left"}"
        return text to true
      }
    }

    // 2) periodic summary
    if (now - lastAnnounceAt < announceEveryMs) return null
    lastAnnounceAt = now
    // newest sighting of every stable label in the window (not just this one frame)
    val windowSeen =
        recent.map { it.second }.flatten().map { it.label }.distinct().filter(::stable).flatMap { label ->
          recent.last { (_, s) -> s.any { it.label == label } }.second.filter { it.label == label }
        }
    val text = summarize(windowSeen)
    if (text.isEmpty()) return null
    if (text == lastAnnounceText && now - lastAnnounceTextAt < 20_000) return null
    lastAnnounceText = text
    lastAnnounceTextAt = now
    return text to false
  }

  /** "Chair ahead, close. Two people on your left." Up to 3 groups, closest first. */
  fun summarize(seen: List<Seen>): String {
    if (seen.isEmpty()) return ""
    return seen
        .groupBy { it.label to it.direction }
        .map { (key, items) -> Triple(key, items.size, items.maxOf { it.area }) to items.any { it.near } }
        .sortedByDescending { it.first.third }
        .take(3)
        .joinToString(". ") { (info, near) ->
          val (key, count, _) = info
          val (label, dir) = key
          val noun = if (count == 1) label else "${NUMBERS.getOrElse(count) { count.toString() }} ${plural(label)}"
          val where = if (dir == "ahead") "ahead" else "on your $dir"
          "${noun.replaceFirstChar { it.uppercase() }} $where${if (near) ", close" else ""}"
        } + "."
  }

  /** For the memory log: "chair ahead; backpack on your left" + structured objects. */
  fun memoryEntry(dets: List<Detection>): Pair<String, List<Map<String, String>>>? {
    if (dets.isEmpty()) return null
    val seen = dets.map(::describe)
    val objects = seen.map { mapOf("label" to it.label, "position" to it.direction, "distance" to if (it.near) "near" else "medium") }
    return seen.joinToString("; ") { "${it.label} ${if (it.direction == "ahead") "ahead" else "on the ${it.direction}"}" } to objects
  }

  fun noteSpoke(now: Long = System.currentTimeMillis()) {
    lastAnnounceAt = now
  }

  private fun plural(label: String) =
      when (label) {
        "person" -> "people"
        "bus" -> "buses"
        "couch" -> "couches"
        "bench" -> "benches"
        else -> label + "s"
      }

  companion object {
    private val NUMBERS = listOf("zero", "one", "two", "three", "four", "five", "six")
  }
}
