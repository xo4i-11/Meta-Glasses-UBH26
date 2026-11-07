package com.smarteyes

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class YuvTest {
  @Test fun i420ToNv21_interleavesVU() {
    // 2x2 image: Y=4 bytes, U=1, V=1
    val i420 = byteArrayOf(1, 2, 3, 4, /*U*/ 50, /*V*/ 60)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 60, 50), i420ToNv21(i420, 2, 2))
  }

  @Test fun i420ToNv21_4x2() {
    val y = ByteArray(8) { it.toByte() }
    val i420 = y + byteArrayOf(10, 11) + byteArrayOf(20, 21)
    assertArrayEquals(y + byteArrayOf(20, 10, 21, 11), i420ToNv21(i420, 4, 2))
  }
}
