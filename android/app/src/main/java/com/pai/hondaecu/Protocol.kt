package com.pai.hondaecu

/**
 * Honda/Keihin K-line messages. READ-ONLY by design, same as kline.py on the PC.
 *
 * Only these messages can ever leave the app (whitelist, see requireAllowed):
 *   wake-up      FE 04 72 8C
 *   init         72 05 00 F0 99
 *   live data    72 05 71 <table> <checksum>
 *   fault codes  72 05 74 <page> <checksum>  current     (read only; added 3 Nov 2026 with
 *                72 05 73 <page> <checksum>  past/stored  Pai's OK, from HondaECU's source)
 * Never add clear-codes (72 05 60 03), write, flash, EEPROM or security-access commands here.
 */
object Protocol {
    const val BAUD = 10400

    val WAKE_MSG = hex("FE 04 72 8C")
    val INIT_MSG = hex("72 05 00 F0 99")
    val INIT_OK = hex("02 04 00 FA")
    const val LIVE = 0x71
    const val DTC_CURRENT = 0x74
    const val DTC_PAST = 0x73
    private val READ_QUERIES = setOf(LIVE, DTC_CURRENT, DTC_PAST)

    fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    fun checksum(data: ByteArray): Int =
        (0x100 - (data.sumOf { it.toInt() and 0xFF } and 0xFF)) and 0xFF

    fun queryMsg(table: Int, query: Int = LIVE): ByteArray {
        val body = byteArrayOf(0x72, 0x05, query.toByte(), table.toByte())
        return body + checksum(body).toByte()
    }

    /** True only for wake-up, init, or a 5-byte read query with a correct checksum. */
    fun isAllowed(msg: ByteArray): Boolean =
        msg.contentEquals(WAKE_MSG) || msg.contentEquals(INIT_MSG) ||
            (msg.size == 5 && msg[0] == 0x72.toByte() && msg[1] == 0x05.toByte() &&
                (msg[2].toInt() and 0xFF) in READ_QUERIES &&
                checksum(msg.copyOf(4)) == (msg[4].toInt() and 0xFF))

    /** Throws unless msg is on the whitelist. Every link calls this before sending. */
    fun requireAllowed(msg: ByteArray) {
        if (!isAllowed(msg)) throw IllegalArgumentException("Blocked non-whitelisted command: " + toHex(msg))
    }

    /** Strip header (type, len), the echoed query + table (e.g. '71 17') and checksum. Empty = not supported. */
    fun payload(reply: ByteArray, table: Int): ByteArray {
        if (reply.size < 3) return ByteArray(0)
        val body = reply.copyOfRange(2, reply.size - 1)
        return if (body.size >= 2 && (body[0].toInt() and 0xFF) in READ_QUERIES && body[1] == table.toByte())
            body.copyOfRange(2, body.size) else body
    }

    /** Current and past fault codes, read only, like read_dtcs in kline.py. */
    class Faults(val current: List<Pair<Int, Int>>, val past: List<Pair<Int, Int>>, val supported: Boolean)

    fun readFaults(link: Link): Faults {
        val lists = mutableMapOf(DTC_CURRENT to mutableListOf<Pair<Int, Int>>(), DTC_PAST to mutableListOf())
        var supported = false
        for (query in listOf(DTC_CURRENT, DTC_PAST)) {
            for (page in 1..Decode.DTC_PAGES) {
                val r = link.send(queryMsg(page, query)) ?: break     // no answer: not supported / no more
                supported = true
                val d = payload(r, page)
                if (d.isEmpty()) break                                  // no such page
                for (code in Decode.decodeDtc(d)) if (code !in lists.getValue(query)) lists.getValue(query) += code
            }
        }
        return Faults(lists.getValue(DTC_CURRENT), lists.getValue(DTC_PAST), supported)
    }

    fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Something that can exchange K-line messages: the real cable or the simulator. */
interface Link {
    fun wake()
    fun send(msg: ByteArray): ByteArray?
    fun close()
}
