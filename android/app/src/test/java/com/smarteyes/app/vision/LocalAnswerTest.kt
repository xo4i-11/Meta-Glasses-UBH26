package com.smarteyes.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAnswerTest {
  private fun d(label: String, cx: Float, w: Float = .2f, h: Float = .3f, conf: Float = .8f) = Det(label, conf, cx, .5f, w, h)

  @Test fun ownerExample() = assertEquals(
      "There's a person ahead of you, a chair on your left, and a dining table slightly to your right. Keep an eye on the obstacle ahead.",
      describeLocal(listOf(d("chair", .1f), d("person", .5f), d("dining table", .7f))))

  @Test fun nothingConfidentReturnsNull() = assertNull(describeLocal(listOf(d("chair", .5f, conf = .3f))))
  @Test fun tinyBoxesIgnored() = assertNull(describeLocal(listOf(d("cup", .5f, w = .05f, h = .1f))))
  @Test fun emptyReturnsNull() = assertNull(describeLocal(emptyList()))

  @Test fun noPathWarningWhenNothingAhead() =
      assertEquals("There's a laptop on your right.", describeLocal(listOf(d("laptop", .9f))))

  @Test fun anForVowels() = assertEquals("There's an umbrella slightly to your left.", describeLocal(listOf(d("umbrella", .3f))))

  @Test fun atMostThreeAheadFirstThenBiggest() = assertEquals(
      "There's a person ahead of you, a couch on your right, and a chair on your left. Keep an eye on the obstacle ahead.",
      describeLocal(listOf(d("cup", .1f, w = .15f, h = .15f), d("chair", .1f), d("couch", .9f, w = .4f, h = .4f), d("person", .5f), d("book", .8f, w = .12f, h = .12f))))

  @Test fun countsSameThingSamePlace() = assertEquals(
      "There are two people ahead of you. Keep an eye on the obstacle ahead.",
      describeLocal(listOf(d("person", .45f), d("person", .55f))))

  @Test fun clearSentence() = assertEquals("Your path appears clear, but I can't guarantee there are no obstacles.", PATH_CLEAR)
}
