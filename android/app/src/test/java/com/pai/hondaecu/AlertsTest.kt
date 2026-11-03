package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertsTest {
    private val running = mapOf("rpm" to 3000, "tps_pct" to 10.0)

    @Test
    fun defaultsMatchTheOldFixedRules() {
        val d = Alerts.DEFAULTS
        assertEquals(setOf("ect_c"), Alerts.check(running + ("ect_c" to 120), d, false))
        assertEquals(emptySet<String>(), Alerts.check(running + ("ect_c" to 119), d, false))
        assertEquals(setOf("batt_v"), Alerts.check(running + ("batt_v" to 12.4), d, false))
    }

    @Test
    fun lowAndHighLimitsBothCount() {
        val lim = mapOf("ect_c" to Alerts.Limit(30.0, 120.0))
        assertEquals(setOf("ect_c"), Alerts.check(running + ("ect_c" to 25), lim, false))   // cold
        assertEquals(emptySet<String>(), Alerts.check(running + ("ect_c" to 80), lim, false))
        assertEquals(setOf("ect_c"), Alerts.check(running + ("ect_c" to 125), lim, false))  // hot
    }

    @Test
    fun batteryIsIgnoredAtIdle() {
        val idle = mapOf("rpm" to 1400, "batt_v" to 12.0)
        assertEquals(emptySet<String>(), Alerts.check(idle, Alerts.DEFAULTS, false))
    }

    @Test
    fun missingValuesAndTheO2RuleSwitch() {
        assertEquals(emptySet<String>(), Alerts.check(mapOf("rpm" to 3000), Alerts.DEFAULTS, true))
        val lean = mapOf("rpm" to 5000, "tps_pct" to 80.0, "o2_v" to 0.2)
        assertEquals(setOf("o2_v"), Alerts.check(lean, emptyMap(), true))
        assertEquals(emptySet<String>(), Alerts.check(lean, emptyMap(), false))
    }
}
