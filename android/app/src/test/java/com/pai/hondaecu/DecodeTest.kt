package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecodeTest {
    private val keys17 = listOf("rpm", "tps_pct", "ect_c", "batt_v", "speed_kmh", "inj_ms", "ign_deg", "mode_d14")
    private val keys20 = listOf("o2_v", "stft")

    /**
     * Each line: table,raw hex,then "key=value" as printed by decode.py on the PC.
     * The committed file has made-up bytes. Pass -Dgolden.file=... for vectors from real rides.
     */
    private fun check(lines: List<String>) {
        var n = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split(",")
            val d = Protocol.hex(parts[1].chunked(2).joinToString(" "))
            val got = if (parts[0] == "17") Decode.decode17(d) else Decode.decode20(d)
            for (kv in parts.drop(2)) {
                val (k, v) = kv.split("=")
                assertEquals("$line [$k]", v, got[k]?.toString() ?: "")
            }
            n++
        }
        println("compared $n vectors")
        assertTrue(n > 0)
    }

    @Test
    fun matchesPythonOnCommittedVectors() {
        check(javaClass.getResource("/golden_vectors.txt")!!.readText().lines())
    }

    @Test
    fun matchesPythonOnRealRides() {
        val path = System.getProperty("golden.file") ?: ""
        if (path.isNotEmpty()) check(File(path).readLines())
    }

    @Test
    fun neutralByte() {
        assertEquals(1, Decode.decodeD1(Protocol.hex("01 00 00 00 01 10"))["neutral"])
        assertEquals(0, Decode.decodeD1(Protocol.hex("00 00 00 00 01 10"))["neutral"])
    }

    @Test
    fun whitelistAllowsOnlyReadOnlyMessages() {
        Protocol.requireAllowed(Protocol.WAKE_MSG)
        Protocol.requireAllowed(Protocol.INIT_MSG)
        Protocol.requireAllowed(Protocol.queryMsg(0x17))
        assertEquals("72057117" + "01", Protocol.toHex(Protocol.queryMsg(0x17)))
        for (bad in listOf("72 05 72 17 00", "7D 01 01", "72 06 71 17 00 00", "72 05 71 17 02")) {
            var blocked = false
            try {
                Protocol.requireAllowed(Protocol.hex(bad))
            } catch (_: IllegalArgumentException) {
                blocked = true
            }
            assertTrue("should block $bad", blocked)
        }
    }

    @Test
    fun simulatorRepliesAreValid() {
        val sim = SimEcu()
        val r = sim.send(Protocol.queryMsg(0x17))!!
        assertEquals(0, r.sumOf { it.toInt() and 0xFF } and 0xFF)
        assertEquals(19, Protocol.payload(r, 0x17).size)
        assertTrue(Protocol.payload(sim.send(Protocol.queryMsg(0x10))!!, 0x10).isEmpty())
        assertFalse(Decode.decode17(Protocol.payload(r, 0x17)).isEmpty())
    }

    @Test
    fun csvMatchesThePcFormat() {
        assertEquals("timestamp,elapsed_s,rpm,tps_pct,ect_c,batt_v,speed_kmh,inj_ms,ign_deg,o2_v,stft," +
            "mode_d14,neutral,gps_kmh,marker,raw_17,raw_20,raw_d1", CsvFormat.HEADER)
        val c = CsvFormat()
        c.mark("M1")
        val row = c.row(mapOf("t" to 1_000_000L, "rpm" to 1420, "stft" to 1.0, "raw_17" to "00ff"))
        assertTrue(row, row.endsWith(",0.000,1420,,,,,,,,1.0,,,,M1,00ff,,"))
    }

    @Test
    fun alertsFollowThePcRules() {
        assertTrue("ect_c" in Decode.alerts(mapOf("ect_c" to 120)))
        assertFalse("ect_c" in Decode.alerts(mapOf("ect_c" to 119)))
    }
}
