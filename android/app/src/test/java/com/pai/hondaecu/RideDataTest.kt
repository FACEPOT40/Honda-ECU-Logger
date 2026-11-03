package com.pai.hondaecu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RideDataTest {
    private val phoneCsv = """
        ${"﻿"}timestamp,elapsed_s,rpm,tps_pct,ect_c,batt_v,speed_kmh,inj_ms,ign_deg,o2_v,stft,mode_d14,neutral,marker,raw_17,raw_20,raw_d1
        2026-11-03 14:52:14.851,0.000,1200,0.0,48,13.7,0,3.19,15.0,0.9,1.0,128,1,,aa,bb,cc
        2026-11-03 14:52:14.951,0.100,1300,1.5,48,13.7,0,3.20,15.5,0.8,1.0,128,1,M1,aa,bb,cc
        not a time,0.2,1,1,1,1,1,1,1,1,1,1,1,,aa,bb,cc
        2026-11-03 14:52:15.051,0.200,,2.0,48,13.6,0,3.30,16.0,0.7,1.0,128,0,,aa,bb,cc
    """.trimIndent()

    // a PC log from before 25 Oct: fast_idle instead of mode_d14, no neutral or raw_d1
    private val oldPcCsv = """
        timestamp,elapsed_s,rpm,tps_pct,ect_c,batt_v,speed_kmh,inj_ms,ign_deg,o2_v,stft,fast_idle,marker,raw_17,raw_20
        2026-10-24 21:25:13.151,0.000,0,0.0,36,11.7,0,0.0,0.0,4.98,1.0,1,,00,ff
        2026-10-24 21:25:13.228,0.077,0,0.0,36,11.7,0,0.0,0.0,4.98,1.0,1,,00,ff
    """.trimIndent()

    @Test fun readsPhoneCsv() {
        val r = RideData.parse(phoneCsv.lineSequence())
        assertEquals(3, r.t.size)                                  // the bad timestamp row is skipped
        assertEquals(200L, r.durationMs)
        val rpm = r.series("rpm", r.start, r.end)
        assertEquals(1200f, rpm.v[0], 0f)
        assertTrue(rpm.v[2].isNaN())                               // empty cell
        assertEquals(listOf(r.start + 100 to "M1"), r.markers)
        assertArrayEquals(longArrayOf(r.start + 100), r.series("tps_pct", r.start + 50, r.start + 150).t)
        assertEquals(0f, r.series("neutral", r.end, r.end).v[0], 0f)
    }

    @Test fun readsOldPcCsvWithMissingColumns() {
        val r = RideData.parse(oldPcCsv.lineSequence())
        assertEquals(2, r.t.size)
        assertTrue(r.has("ect_c"))
        assertFalse(r.has("neutral"))
        assertFalse(r.has("mode_d14"))
        assertEquals(0, r.series("neutral", r.start, r.end).t.size)
    }

    @Test fun parsesExcelTimeFormat() {
        assertEquals(RideData.parseTime("2026-10-25 01:19:52"), RideData.parseTime("25/10/2026 01:19:52"))
        assertEquals(null, RideData.parseTime("2026-10-25 01:19:52 extra"))
    }

    /** Optional: -Dride.file=path\to\ride.csv prints a summary to compare with the PC viewer. */
    @Test fun realRideIfGiven() {
        val path = System.getProperty("ride.file")?.takeIf { it.isNotEmpty() } ?: return
        val r = File(path).bufferedReader().useLines { RideData.parse(it) }
        println("RIDE ${File(path).name}: rows=${r.t.size} duration_s=${r.durationMs / 1000.0} markers=${r.markers}")
        for (f in Decode.FIELDS) {
            val v = r.series(f.key, r.start, r.end).v.filter { !it.isNaN() }
            println("RIDE   ${f.key}: n=${v.size} min=${v.minOrNull()} max=${v.maxOrNull()}")
        }
    }
}
