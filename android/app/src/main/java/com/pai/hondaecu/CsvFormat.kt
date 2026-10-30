package com.pai.hondaecu

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The ride CSV, identical to recorder.py on the PC so viewer.py opens it unchanged. */
class CsvFormat {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var t0: Long? = null
    private var pendingMarker = ""

    fun mark(label: String) {
        pendingMarker = label
    }

    /** One CSV line (without newline) for a sample; "t" is wall-clock milliseconds. */
    fun row(s: Map<String, Any?>): String {
        val t = s["t"] as Long
        val first = t0 ?: t.also { t0 = it }
        val cells = mutableListOf(stamp.format(Date(t)), "%.3f".format(Locale.US, (t - first) / 1000.0))
        VALUES.forEach { cells += s[it]?.toString() ?: "" }
        cells += pendingMarker
        RAW.forEach { cells += s[it]?.toString() ?: "" }
        pendingMarker = ""
        return cells.joinToString(",")
    }

    companion object {
        val VALUES = listOf("rpm", "tps_pct", "ect_c", "batt_v", "speed_kmh", "inj_ms", "ign_deg",
            "o2_v", "stft", "mode_d14", "neutral")
        val RAW = listOf("raw_17", "raw_20", "raw_d1")
        val HEADER = (listOf("timestamp", "elapsed_s") + VALUES + "marker" + RAW).joinToString(",")
    }
}
