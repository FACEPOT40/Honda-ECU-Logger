package com.pai.hondaecu

import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Simulated ECU with plausible riding data (no bike needed). Same layout as the real Wave. */
class SimEcu : Link {
    private val t0 = System.currentTimeMillis()

    override fun wake() = Thread.sleep(190)
    override fun close() {}

    override fun send(msg: ByteArray): ByteArray? {
        Protocol.requireAllowed(msg)
        Thread.sleep(20)
        if (msg.contentEquals(Protocol.WAKE_MSG)) return Protocol.hex("0E 04 72 7C")
        if (msg.contentEquals(Protocol.INIT_MSG)) return Protocol.INIT_OK
        val query = msg[2].toInt() and 0xFF
        val table = msg[3].toInt() and 0xFF
        if (query == Protocol.DTC_CURRENT || query == Protocol.DTC_PAST) {
            // no current faults; one stored: 07-01 (engine temp sensor, low voltage), like kline.py
            val codes = if (query == Protocol.DTC_PAST && table == 1) bytes(7, 1, 0, 0, 0, 0) else ByteArray(6)
            val body = bytes(0x02, 12, query, table, 0) + codes
            return body + Protocol.checksum(body).toByte()
        }
        val t = (System.currentTimeMillis() - t0) / 1000.0
        val cyc = (sin(t / 3) + 1) / 2       // 0..1 slow throttle wave
        val data: ByteArray = when (table) {
            0x00 -> Protocol.hex("01 03 3E 0D 01 00 00 00 00 00")
            0x17 -> {
                val rpm = (1400 + cyc * 7000 + Random.nextDouble(-40.0, 40.0)).toInt()
                val inj = ((2.5 + cyc * 4) * Decode.INJ_DIVISOR).toInt()
                val ect = min(40 + t * 0.5, 112.0).toInt()
                bytes(rpm shr 8, rpm, 0x19 + (cyc * 120).toInt(), (cyc * 150).toInt(), 0x75, ect + 40,
                    0xFF, 0xFF, 0xFF, 0xFF, 138 - Random.nextInt(6), inj shr 8, inj,
                    ((16 + cyc * 30 + 64) * 2).toInt(), 0x80, 0, 0, 0, 0)
            }
            0x20 -> bytes(if (cyc < 0.6) Random.nextInt(20, 231) else Random.nextInt(180, 231),
                128 + Random.nextInt(-6, 7), 0)
            0xD1 -> bytes(if (cyc < 0.15) 1 else 0, 0, 0, 0, 1, 0x10)
            else -> ByteArray(0)             // like a real ECU: "02 05 71 tt cs" = no such table
        }
        val body = bytes(0x02, data.size + 5, 0x71, table) + data
        return body + Protocol.checksum(body).toByte()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
