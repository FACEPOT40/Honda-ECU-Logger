package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Test

class TileOrderTest {
    private val default = Decode.FIELDS.map { it.key }

    @Test fun savedOrderKeepsUnknownOutAndAddsNewValues() {
        // an older saved order without inj_duty, plus a key that no longer exists
        val o = ValueFilter.order(listOf("gps_kmh", "rpm", "old_value", "tps_pct"))
        assertEquals(listOf("gps_kmh", "rpm", "tps_pct"), o.take(3))
        assertEquals(default.size, o.size)
        assertEquals(default.filter { it !in setOf("gps_kmh", "rpm", "tps_pct") }, o.drop(3))
    }

    @Test fun dropTakesTheTargetsPlaceAndOthersShift() {
        val order = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("d", "a", "b", "c", "e"), ValueFilter.moved(order, emptySet(), "d", "a"))   // up
        assertEquals(listOf("b", "c", "a", "d", "e"), ValueFilter.moved(order, emptySet(), "a", "c"))   // down
        assertEquals(order, ValueFilter.moved(order, emptySet(), "c", "c"))
    }

    @Test fun hiddenValuesKeepTheirPlace() {
        // b and d hidden; shown a c e: drag e onto a -> shown e a c, hidden stay at 2nd and 4th
        val order = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("e", "b", "a", "d", "c"), ValueFilter.moved(order, setOf("b", "d"), "e", "a"))
    }

    @Test fun gpsSpeedCanMoveUpToTheTop() {
        val hidden = ValueFilter.DEFAULT_HIDDEN
        val o = ValueFilter.moved(default, hidden, "gps_kmh", "ect_c")
        val shown = o.filter { it !in hidden }
        assertEquals(listOf("rpm", "tps_pct", "gps_kmh", "ect_c", "batt_v"), shown.take(5))
    }
}
