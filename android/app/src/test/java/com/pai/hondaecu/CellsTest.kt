package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CellsTest {
    @Test fun nearestMatchesCellsPy() {
        // same cases as the check of cells.py: ties go to the lower point, NaN = -1
        assertEquals(listOf(0, 0, 1, 16, 17, -1),
            listOf(790f, 950f, 1150f, 4120f, 4130f, Float.NaN).map { Cells.nearest(Cells.RPM_AXIS, it) })
        assertEquals(listOf(0, 1, 2, 12, 13, 27),
            listOf(0f, 0.39f, 0.41f, 14.6f, 14.8f, 94f).map { Cells.nearest(Cells.TPS_AXIS, it) })
        assertEquals("4250 rpm × 16.4 %", Cells.name(17, 13))
        assertEquals("1200 rpm × 0 %", Cells.name(2, 0))
    }

    private fun ride(vararg rows: String) = RideData.parse(
        (listOf("timestamp,rpm,tps_pct,o2_v,inj_ms") + rows).asSequence())

    @Test fun hitsCountTimeAndOnlyUsableO2() {
        val r = ride(
            "2026-11-03 10:00:00.000,4250,16.4,0.10,4.8",
            "2026-11-03 10:00:00.100,4250,16.4,0.90,4.8",
            "2026-11-03 10:00:00.200,4250,16.4,4.98,4.8",      // cold sensor: not used for O2
            "2026-11-03 10:00:00.300,4250,16.4,0.20,0",        // fuel cut: not used for O2
            "2026-11-03 10:00:05.300,0,0,0.5,0",               // 5 s gap counts 0.5 s; engine off
        )
        val h = Cells.hits(r, r.start, r.end, null)
        assertEquals(0.3 + 0.5, h.seconds[17][13], 1e-9)
        assertEquals(2, h.n[17][13])
        assertEquals(0.5, h.o2[17][13], 1e-6)
        assertEquals(50.0, h.leanPct[17][13], 1e-9)
        assertEquals(0.8, h.totalSeconds, 1e-9)
        assertTrue(h.ect[17][13].isNaN())                       // no ect_c column: no temp
    }

    @Test fun cellTempIsTheAverageOfTheO2Samples() {
        val r = RideData.parse(sequenceOf("timestamp,rpm,tps_pct,o2_v,inj_ms,ect_c",
            "2026-11-03 10:00:00.000,4250,16.4,0.10,4.8,90",
            "2026-11-03 10:00:00.100,4250,16.4,0.20,4.8,100",
            "2026-11-03 10:00:00.200,4250,16.4,0.30,0,130",     // fuel cut: not an O2 sample
            "2026-11-03 10:00:00.300,4250,16.4,0.40,4.8,"))     // no temp in this sample
        val h = Cells.hits(r, r.start, r.end, null)
        assertEquals(3, h.n[17][13])
        assertEquals(95.0, h.ect[17][13], 1e-9)
    }

    @Test fun steadyNeedsHalfASecondOfCalmThrottle() {
        val t = LongArray(8) { it * 100L }
        val rpm = FloatArray(8) { 4000f }
        val tps = floatArrayOf(10f, 10f, 10f, 10f, 10f, 15f, 15f, 15f)
        val s = Cells.steady(t, rpm, tps)
        assertFalse(s[2])           // only 0.2 s of history
        assertTrue(s[4])
        assertFalse(s[5])           // throttle just jumped
    }

    /** Optional: -Dride.file=... prints the cell numbers to compare with cells.py on the PC. */
    @Test fun realRideIfGiven() {
        val path = System.getProperty("ride.file")?.takeIf { it.isNotEmpty() } ?: return
        val r = File(path).bufferedReader().useLines { RideData.parse(it) }
        val steady = Cells.steady(r.t, r.values("rpm")!!, r.values("tps_pct")!!)
        val h = Cells.hits(r, r.start, r.end, steady)
        println("CELLS total_s=%.3f steady=%d".format(java.util.Locale.US, h.totalSeconds, steady.count { it }))
        for (row in h.n.indices) for (c in h.n[row].indices) if (h.seconds[row][c] > 0 || h.n[row][c] > 0) {
            println("CELL %d %d %.3f %d %.4f %.2f %.4f %.3f %.3f".format(java.util.Locale.US, row, c,
                h.seconds[row][c], h.n[row][c], h.o2[row][c], h.leanPct[row][c], h.inj[row][c], h.ect[row][c], h.duty[row][c]))
        }
        for ((k, v) in RideSummary.lines(r, steady)) println("SUMMARY $k: ${v.replace("\n", " | ")}")
    }
}
