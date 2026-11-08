package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DutyTest {
    @Test fun dutyMatchesDecodePy() {
        // decode.inj_duty(12.04, 8300) = 83.3; no RPM = no duty
        assertEquals(83.3, Decode.injDuty(12.04, 8300.0)!!, 1e-9)
        assertNull(Decode.injDuty(2.7, 0.0))
        assertNull(Decode.injDuty(null, 1500.0))
        val s = Decode.addDerived(mutableMapOf("inj_ms" to 12.0, "rpm" to 8500))
        assertEquals(85.0, s["inj_duty"] as Double, 1e-9)
        assertTrue("inj_duty" in Decode.alerts(s))
    }

    @Test fun recordedRidesGetDutyFromInjectorAndRpm() {
        val r = RideData.parse(sequenceOf("timestamp,rpm,inj_ms",
            "2026-11-08 10:00:00.000,6000,10.0",
            "2026-11-08 10:00:00.100,0,0"))
        val d = r.values("inj_duty")!!
        assertEquals(50f, d[0], 1e-4f)
        assertTrue(d[1].isNaN())
    }

    @Test fun dutyAlertsAtTheDefaultLimit() {
        val lim = mapOf("inj_duty" to Alerts.DEFAULTS.getValue("inj_duty"))
        assertTrue("inj_duty" in Alerts.check(mapOf("inj_duty" to 85.0, "rpm" to 9000), lim, false))
        assertTrue("inj_duty" !in Alerts.check(mapOf("inj_duty" to 84.9, "rpm" to 9000), lim, false))
    }

    @Test fun tilesPairInjectorTimeAndDuty() {
        val shown = Decode.FIELDS.map { it.key }.filter { it !in ValueFilter.DEFAULT_HIDDEN }
        val i = shown.indexOf("inj_ms")
        assertEquals("inj_duty", shown[i + 1])
        assertEquals("Inj at the start of a row of two", 0, i % 2)
        assertTrue("speed_kmh" in ValueFilter.DEFAULT_HIDDEN)
    }
}
