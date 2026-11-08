package com.pai.hondaecu

/**
 * Map cells: which fuel / ignition map cell (RPM x TPS) a moment of a ride falls in, and a
 * "cell hit" summary per cell. Port of cells.py on the PC (keep the two in step; CellsTest
 * checks it). Analysis of a CSV only: nothing is ever sent to the ECU.
 *
 * The axes are the K73-T61 tune's map axes as TunerPro shows them; the fuel and ignition maps
 * use the same ones. Each moment counts in its nearest cell.
 */
object Cells {
    val RPM_AXIS = floatArrayOf(800f, 1100f, 1200f, 1300f, 1400f, 1500f, 1600f, 1800f, 2000f,
        2250f, 2500f, 2750f, 3000f, 3250f, 3500f, 3750f, 4000f, 4250f, 4500f, 5000f, 5500f,
        6000f, 6500f, 7000f, 7500f, 8000f, 8500f, 9000f, 9400f)
    val TPS_AXIS = floatArrayOf(0.0f, 0.3f, 0.5f, 1.0f, 1.5f, 2.6f, 4.0f, 5.2f, 6.5f, 7.8f, 9.2f,
        10.5f, 13.0f, 16.4f, 19.7f, 22.9f, 26.2f, 29.4f, 32.7f, 36.1f, 41.4f, 48.1f, 55.5f, 63.4f,
        72.4f, 73.3f, 78.5f, 91.7f, 100.6f)

    const val LEAN_V = 0.45f        // narrowband O2 below this = lean side
    const val O2_MAX_V = 1.2f       // above this the sensor is still cold: not a mixture
    const val STEADY_MS = 500L      // "steady": throttle and RPM stayed within these over 0.5 s
    const val STEADY_TPS = 1.3f
    const val STEADY_RPM = 400f
    const val GAP_MS = 500L         // a gap in the log counts at most this long

    /** Index of the nearest axis point (the lower one on a tie, like cells.py); -1 for NaN. */
    fun nearest(axis: FloatArray, v: Float): Int {
        if (v.isNaN()) return -1
        var i = axis.size - 1
        for (k in 1 until axis.size) if (axis[k] >= v) { i = k; break }
        return if (v - axis[i - 1] <= axis[i] - v) i - 1 else i
    }

    fun name(r: Int, c: Int): String {
        val tps = TPS_AXIS[c]
        val t = if (tps == tps.toInt().toFloat()) tps.toInt().toString() else tps.toString()
        return "${RPM_AXIS[r].toInt()} rpm × $t %"
    }

    /** The cell of sample [i], or null if the engine was off or a value is missing. */
    fun cellAt(ride: RideData, i: Int): Pair<Int, Int>? {
        val rpm = ride.values("rpm")?.get(i) ?: return null
        val tps = ride.values("tps_pct")?.get(i) ?: return null
        if (rpm.isNaN() || tps.isNaN() || rpm <= 0f) return null
        return nearest(RPM_AXIS, rpm) to nearest(TPS_AXIS, tps)
    }

    /** True where throttle and RPM stayed nearly constant over the last STEADY_MS. */
    fun steady(t: LongArray, rpm: FloatArray, tps: FloatArray): BooleanArray {
        val out = BooleanArray(t.size)
        var a = 0
        for (i in t.indices) {
            while (t[a] < t[i] - STEADY_MS) a++
            if (t[i] - t[a] < STEADY_MS * 6 / 10) continue        // not enough history yet
            var tLo = Float.POSITIVE_INFINITY; var tHi = Float.NEGATIVE_INFINITY
            var rLo = Float.POSITIVE_INFINITY; var rHi = Float.NEGATIVE_INFINITY
            var ok = true
            for (j in a..i) {
                if (tps[j].isNaN() || rpm[j].isNaN()) { ok = false; break }
                tLo = minOf(tLo, tps[j]); tHi = maxOf(tHi, tps[j])
                rLo = minOf(rLo, rpm[j]); rHi = maxOf(rHi, rpm[j])
            }
            out[i] = ok && tHi - tLo <= STEADY_TPS && rHi - rLo <= STEADY_RPM
        }
        return out
    }

    class Hits(val seconds: Array<DoubleArray>, val n: Array<IntArray>, val o2: Array<DoubleArray>,
               val leanPct: Array<DoubleArray>, val inj: Array<DoubleArray>,
               /** Average engine temp of the same samples: lean when cold (warm-up) or hot. */
               val ect: Array<DoubleArray>) {
        val totalSeconds get() = seconds.sumOf { it.sum() }
    }

    /**
     * Per-cell summary of the samples from [from] to [to].
     * seconds: time the engine ran in the cell (fuel cut included). n, o2, leanPct, inj: from
     * samples with fuel on and a warm O2 sensor, and only steady ones if [steadyMask] is given.
     * ect: the average engine temp of those samples (where the log has one).
     */
    fun hits(ride: RideData, from: Long, to: Long, steadyMask: BooleanArray?): Hits {
        val rows = RPM_AXIS.size
        val cols = TPS_AXIS.size
        val seconds = Array(rows) { DoubleArray(cols) }
        val n = Array(rows) { IntArray(cols) }
        val o2Sum = Array(rows) { DoubleArray(cols) }
        val lean = Array(rows) { IntArray(cols) }
        val injSum = Array(rows) { DoubleArray(cols) }
        val tN = Array(rows) { IntArray(cols) }
        val tSum = Array(rows) { DoubleArray(cols) }
        val t = ride.t
        val rpm = ride.values("rpm")
        val tps = ride.values("tps_pct")
        val o2 = ride.values("o2_v")
        val inj = ride.values("inj_ms")
        val ect = ride.values("ect_c")
        if (rpm != null && tps != null) {
            for (i in ride.indexRange(from, to)) {
                val r = nearest(RPM_AXIS, rpm[i])
                val c = nearest(TPS_AXIS, tps[i])
                if (r < 0 || c < 0 || !(rpm[i] > 0f)) continue
                val dt = if (i + 1 < t.size) (t[i + 1] - t[i]).coerceIn(0, GAP_MS) else 0L
                seconds[r][c] += dt / 1000.0
                val ov = o2?.get(i) ?: Float.NaN
                val iv = inj?.get(i) ?: Float.NaN
                if (!(iv > 0f) || !(ov >= 0f && ov <= O2_MAX_V)) continue
                if (steadyMask != null && !steadyMask[i]) continue
                n[r][c]++
                o2Sum[r][c] += ov.toDouble()
                if (ov < LEAN_V) lean[r][c]++
                injSum[r][c] += iv.toDouble()
                val tv = ect?.get(i) ?: Float.NaN
                if (!tv.isNaN()) { tN[r][c]++; tSum[r][c] += tv.toDouble() }
            }
        }
        val avg = { s: Array<DoubleArray> -> Array(rows) { r -> DoubleArray(cols) { c -> if (n[r][c] > 0) s[r][c] / n[r][c] else Double.NaN } } }
        return Hits(seconds, n, avg(o2Sum),
            Array(rows) { r -> DoubleArray(cols) { c -> if (n[r][c] > 0) 100.0 * lean[r][c] / n[r][c] else Double.NaN } },
            avg(injSum),
            Array(rows) { r -> DoubleArray(cols) { c -> if (tN[r][c] > 0) tSum[r][c] / tN[r][c] else Double.NaN } })
    }
}
