package com.smarteyes

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloDecodeTest {
  private val umbrella = COCO.indexOf("umbrella")
  private val backpack = COCO.indexOf("backpack")

  /** Build a channels-first [84][n] tensor; boxes in input pixels (0..320) like the exported model. */
  private fun tensor(vararg anchors: Triple<FloatArray, Int, Float>): FloatArray {
    val n = anchors.size
    val out = FloatArray(84 * n)
    anchors.forEachIndexed { i, (box, cls, score) ->
      for (c in 0..3) out[c * n + i] = box[c]
      out[(4 + cls) * n + i] = score
    }
    return out
  }

  @Test fun labelsAreCoco80() {
    assertEquals(80, COCO.size); assertEquals("person", COCO[0]); assertEquals(25, umbrella)
  }

  @Test fun decodeKeepsBestClassAboveThresholdAndNormalises() {
    val t = tensor(Triple(floatArrayOf(160f, 80f, 128f, 96f), umbrella, .9f), Triple(floatArrayOf(10f, 10f, 5f, 5f), umbrella, .1f))
    val d = decodeYolo(t, anchors = 2, minConf = .4f, inputSize = 320f)
    assertEquals(1, d.size)
    assertEquals(Det("umbrella", .9f, .5f, .25f, .4f, .3f), d[0])
  }

  @Test fun decodeNmsDropsOverlap() {
    val t = tensor(Triple(floatArrayOf(160f, 80f, 128f, 96f), umbrella, .9f), Triple(floatArrayOf(162f, 82f, 128f, 96f), umbrella, .8f))
    assertEquals(listOf(.9f), decodeYolo(t, 2, .4f, inputSize = 320f).map { it.conf })
  }

  @Test fun decodeKeepsDifferentClasses() {
    val t = tensor(Triple(floatArrayOf(160f, 80f, 128f, 96f), umbrella, .9f), Triple(floatArrayOf(162f, 82f, 128f, 96f), backpack, .8f))
    assertEquals(setOf("umbrella", "backpack"), decodeYolo(t, 2, .4f, inputSize = 320f).map { it.label }.toSet())
  }
}
