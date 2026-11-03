package com.pai.hondaecu

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Raw table bytes -> real units. Port of decode.py on the PC: see its docstring for the
 * byte map and what is confirmed on the Wave. Keep the two in step; DecodeTest compares them.
 */
object Decode {
    const val INJ_DIVISOR = 65535 / 265.5

    class Field(val key: String, val label: String, val unit: String, val decimals: Int) {
        val title get() = if (unit.isEmpty()) label else "$label ($unit)"
    }

    val FIELDS = listOf(
        Field("rpm", "RPM", "rpm", 0),
        Field("tps_pct", "TPS", "%", 1),
        Field("ect_c", "Eng temp", "°C", 0),
        Field("batt_v", "Batt", "V", 1),
        Field("speed_kmh", "Speed", "km/h", 0),
        Field("gps_kmh", "GPS speed", "km/h", 0),      // from the phone's GPS, not the ECU
        Field("inj_ms", "Inj", "ms", 2),
        Field("ign_deg", "Ign", "°", 1),
        Field("o2_v", "O2", "V", 2),
        Field("stft", "STFT", "", 3),
        Field("mode_d14", "Mode (d14)", "", 0),
        Field("neutral", "Gear", "", 0),
    )

    /** Same rounding as Python's round(): exact binary value, ties to even. */
    private fun round(x: Double, digits: Int): Double =
        BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    private fun u(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    /** °C from a raw temperature byte; 0xFF = sensor not fitted. */
    private fun temp(raw: Int): Int? = if (raw == 0xFF) null else raw - 40

    fun decode17(d: ByteArray): Map<String, Any?> {
        if (d.size < 16) return emptyMap()
        return mapOf(
            "rpm" to (u(d, 0) shl 8 or u(d, 1)),
            "tps_v" to round(u(d, 2) * 5 / 256.0, 2),
            "tps_pct" to round(u(d, 3) / 1.6, 2),
            "ect_c" to temp(u(d, 5)),
            "eot_c" to temp(u(d, 9)),
            "batt_v" to round(u(d, 10) * 0.1, 1),
            "inj_ms" to round((u(d, 11) shl 8 or u(d, 12)) / INJ_DIVISOR, 2),
            "ign_deg" to round(-64 + u(d, 13) * 0.5, 1),
            "mode_d14" to u(d, 14),
            "speed_kmh" to u(d, 15),
        )
    }

    fun decode20(d: ByteArray): Map<String, Any?> {
        if (d.size < 3) return emptyMap()
        return mapOf(
            "o2_v" to round(u(d, 0) * 5 / 256.0, 2),
            "stft" to round(u(d, 1) / 128.0, 4),
            "o2_heater_v" to round(u(d, 2) * 5 / 256.0, 2),
        )
    }

    fun decodeD1(d: ByteArray): Map<String, Any?> =
        if (d.isEmpty()) emptyMap() else mapOf("neutral" to if (u(d, 0) == 0x01) 1 else 0)

    val DECODERS: Map<Int, (ByteArray) -> Map<String, Any?>> =
        linkedMapOf(0x17 to ::decode17, 0x20 to ::decode20, 0xD1 to ::decodeD1)

    /** Text for a value on screen: "-" when missing, words for on/off values. */
    fun fmtValue(f: Field, v: Any?): String {
        if (v == null) return "-"
        if (f.key == "neutral") return if ((v as Number).toInt() == 1) "N" else "Gear"
        return "%,.${f.decimals}f".format(java.util.Locale.US, (v as Number).toDouble())
    }

    /** Keys that should be shown in red; same rules as alerts() in decode.py. */
    fun alerts(s: Map<String, Any?>): Set<String> {
        val bad = mutableSetOf<String>()
        val ect = (s["ect_c"] as Number?)?.toDouble()
        val batt = (s["batt_v"] as Number?)?.toDouble()
        val rpm = (s["rpm"] as Number?)?.toDouble() ?: 0.0
        val o2 = (s["o2_v"] as Number?)?.toDouble()
        val tps = (s["tps_pct"] as Number?)?.toDouble() ?: 0.0
        if (ect != null && ect >= 120) bad += "ect_c"
        if (batt != null && rpm > 1500 && batt < 12.5) bad += "batt_v"
        if (o2 != null && o2 < 0.45 && tps > 50 && rpm > 3000) bad += "o2_v"
        return bad
    }
}
