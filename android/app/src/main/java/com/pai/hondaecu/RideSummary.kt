package com.pai.hondaecu

/**
 * One screen of key numbers for a recorded ride: how long, how hard, how hot, the battery,
 * GPS speed, and where the mixture read lean. Each line is (label, text).
 */
object RideSummary {
    const val HOT_C = 120f                 // same as the red warning in Decode.alerts
    const val RIDING_RPM = 1500f           // battery "while riding" ignores starting and idle
    const val MIN_CELL_SECONDS = 2.0       // a lean cell must have this much time to be listed
    const val MIN_CELL_SAMPLES = 5

    fun lines(r: RideData, steady: BooleanArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val n = r.t.size
        val dt = DoubleArray(n) { i -> if (i + 1 < n) (r.t[i + 1] - r.t[i]).coerceIn(0, Cells.GAP_MS) / 1000.0 else 0.0 }
        val rpm = r.values("rpm")
        val tps = r.values("tps_pct")
        fun secondsWhere(test: (Int) -> Boolean) = (0 until n).sumOf { if (test(it)) dt[it] else 0.0 }
        fun fmt(v: Float, dec: Int) = "%,.${dec}f".format(java.util.Locale.US, v)

        val rate = if (r.durationMs > 0) (n - 1) * 1000.0 / r.durationMs else 0.0
        out += "Length" to "${formatDuration(r.durationMs)} · ${"%,d".format(n)} samples · ${"%.1f".format(java.util.Locale.US, rate)}/s"
        if (rpm != null) {
            val running = secondsWhere { rpm[it] > 0f }
            out += "Engine running" to formatDuration((running * 1000).toLong())
            out += "Highest RPM" to fmt(rpm.maxOrNan(), 0)
            if (tps != null) {
                out += "Idle (throttle closed)" to formatDuration((secondsWhere { rpm[it] in 1f..1499f && tps[it] == 0f } * 1000).toLong())
                out += "Highest throttle" to "${fmt(tps.maxOrNan(), 1)} %"
            }
            r.values("inj_ms")?.let { inj ->
                out += "Fuel cut (deceleration)" to formatDuration((secondsWhere { rpm[it] > 1500f && inj[it] == 0f } * 1000).toLong())
                // injector duty: open time against one engine cycle (2 turns) at that RPM
                var best = -1.0; var at = -1
                for (i in 0 until n) if (rpm[i] > 1000f && inj[i] > 0f) {
                    val duty = inj[i] / (120_000.0 / rpm[i])
                    if (duty > best) { best = duty; at = i }
                }
                if (at >= 0) out += "Highest injector duty" to
                    "${"%.0f".format(best * 100)} % (${fmt(inj[at], 2)} ms at ${fmt(rpm[at], 0)} rpm)"
            }
        }
        r.values("ect_c")?.let { ect ->
            if (ect.any { !it.isNaN() }) {
                val hot = secondsWhere { ect[it] >= HOT_C }
                out += "Engine temp" to "${fmt(ect.minOrNan(), 0)} → ${fmt(ect.maxOrNan(), 0)} °C max" +
                    if (hot > 0) " · ${formatDuration((hot * 1000).toLong())} at ${HOT_C.toInt()} °C+" else ""
            }
        }
        r.values("batt_v")?.let { b ->
            val riding = if (rpm != null) (0 until n).filter { rpm[it] > RIDING_RPM && !b[it].isNaN() }.minOfOrNull { b[it] } else null
            out += "Battery lowest" to "${fmt(b.minOrNan(), 1)} V" +
                (riding?.let { " (starting) · ${fmt(it, 1)} V while riding" } ?: "")
        }
        r.values("gps_kmh")?.let { g ->
            val moving = g.filter { it > 5f }
            if (moving.isNotEmpty()) out += "GPS speed" to
                "top ${fmt(g.maxOrNan(), 0)} km/h · average ${fmt(moving.average().toFloat(), 0)} km/h while moving"
        }
        if (rpm != null && tps != null) {
            val h = Cells.hits(r, r.start, r.end, steady)
            var used = 0; var lean = 0.0
            for (row in h.n.indices) for (c in h.n[row].indices) if (h.n[row][c] > 0) {
                used += h.n[row][c]; lean += h.leanPct[row][c] / 100 * h.n[row][c]
            }
            if (used > 0) out += "O2 lean side" to
                "${"%.0f".format(100 * lean / used)} % of steady moments (below ${Cells.LEAN_V} V)"
            val leanest = h.n.indices.flatMap { row -> h.n[row].indices.map { c -> row to c } }
                .filter { (row, c) -> h.n[row][c] >= MIN_CELL_SAMPLES && h.seconds[row][c] >= MIN_CELL_SECONDS && h.o2[row][c] < Cells.LEAN_V }
                .sortedBy { (row, c) -> h.o2[row][c] }
                .take(3)
            out += "Leanest cells" to if (leanest.isEmpty()) "none (no cell under ${Cells.LEAN_V} V with ${MIN_CELL_SECONDS.toInt()} s of data)"
                else leanest.joinToString("\n") { (row, c) ->
                    "${Cells.name(row, c)}: ${"%.2f".format(java.util.Locale.US, h.o2[row][c])} V, ${"%.1f".format(java.util.Locale.US, h.seconds[row][c])} s" +
                        (if (h.ect[row][c].isNaN()) "" else ", ${"%.0f".format(h.ect[row][c])} °C")
                }
        }
        out += "Markers" to if (r.markers.isEmpty()) "none" else r.markers.joinToString(" · ") { it.second }
        return out
    }

    private fun FloatArray.maxOrNan() = filter { !it.isNaN() }.maxOrNull() ?: Float.NaN
    private fun FloatArray.minOrNan() = filter { !it.isNaN() }.minOrNull() ?: Float.NaN
}
