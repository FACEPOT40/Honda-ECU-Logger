package com.pai.hondaecu

/**
 * Honda/Keihin K-line messages. READ-ONLY by design, same as kline.py on the PC.
 *
 * Only three kinds of message can ever leave the app (whitelist):
 *   wake-up  FE 04 72 8C
 *   init     72 05 00 F0 99
 *   query    72 05 71 <table> <checksum>
 * Never add write / flash / EEPROM / security-access commands here.
 */
object Protocol {
    const val BAUD = 10400

    val WAKE_MSG = hex("FE 04 72 8C")
    val INIT_MSG = hex("72 05 00 F0 99")
    val INIT_OK = hex("02 04 00 FA")
    private val QUERY_PREFIX = hex("72 05 71")

    fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    fun checksum(data: ByteArray): Int =
        (0x100 - (data.sumOf { it.toInt() and 0xFF } and 0xFF)) and 0xFF

    fun queryMsg(table: Int): ByteArray {
        val body = byteArrayOf(0x72, 0x05, 0x71, table.toByte())
        return body + checksum(body).toByte()
    }

    /** Throws unless msg is on the whitelist. Every link calls this before sending. */
    fun requireAllowed(msg: ByteArray) {
        val ok = msg.contentEquals(WAKE_MSG) || msg.contentEquals(INIT_MSG) ||
            (msg.size == 5 && msg.copyOf(3).contentEquals(QUERY_PREFIX) &&
                checksum(msg.copyOf(4)) == (msg[4].toInt() and 0xFF))
        if (!ok) throw IllegalArgumentException("Blocked non-whitelisted command: " + toHex(msg))
    }

    /** Strip header (type, len), echoed '71 tt' and checksum. Empty = table not supported. */
    fun payload(reply: ByteArray, table: Int): ByteArray {
        if (reply.size < 3) return ByteArray(0)
        val body = reply.copyOfRange(2, reply.size - 1)
        return if (body.size >= 2 && body[0] == 0x71.toByte() && body[1] == table.toByte())
            body.copyOfRange(2, body.size) else body
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
