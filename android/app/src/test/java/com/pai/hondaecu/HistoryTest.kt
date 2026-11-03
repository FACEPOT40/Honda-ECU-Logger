package com.pai.hondaecu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun sample(t: Long, rpm: Int?) = mapOf("t" to t, "rpm" to rpm, "neutral" to 1)

    @Test fun keepsOrderAndWindow() {
        val h = History(capacity = 10)
        for (i in 0 until 5) h.add(sample(1000L + i * 100, 1000 + i))
        val s = h.series("rpm", since = 1200)
        assertArrayEquals(longArrayOf(1200, 1300, 1400), s.t)
        assertArrayEquals(floatArrayOf(1002f, 1003f, 1004f), s.v, 0f)
        assertEquals(1f, h.series("neutral", 0).v[0], 0f)
    }

    @Test fun overwritesOldestWhenFull() {
        val h = History(capacity = 3)
        for (i in 0 until 5) h.add(sample(i.toLong(), i))
        val s = h.series("rpm", since = 0)
        assertArrayEquals(longArrayOf(2, 3, 4), s.t)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), s.v, 0f)
    }

    @Test fun missingValueIsNaNAndMarksFollowTheBuffer() {
        val h = History(capacity = 3)
        h.add(sample(0, null))
        h.mark(0, "M1")
        assertTrue(h.series("rpm", 0).v[0].isNaN())
        for (i in 1..3) h.add(sample(i.toLong(), i))      // sample at t=0 drops out
        assertTrue(h.marks(0).isEmpty())
        h.mark(3, "M2")
        assertEquals(listOf(3L to "M2"), h.marks(0))
        h.clear()
        assertEquals(0, h.series("rpm", 0).t.size)
    }
}
