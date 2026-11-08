package com.pai.hondaecu

import android.content.Context

/**
 * Alert limits the rider sets on the phone (⋮ → Settings → Alert limits): a low and/or a high
 * limit per value. At or beyond a limit the value turns red and, if switched on, the screen
 * fades red. Display only: nothing here changes what is polled or recorded.
 * The PC keeps the fixed rules of decode.alerts() (Decode.alerts here).
 */
object Alerts {
    /** null = no limit on that side. Red when value <= low or value >= high. */
    data class Limit(val low: Double?, val high: Double?) {
        val isEmpty get() = low == null && high == null
    }

    /** Values that can have limits: the measured ones (Mode and Gear are not numbers to judge). */
    val KEYS = Decode.FIELDS.map { it.key }.filter { it != "mode_d14" && it != "neutral" }

    /** Until the rider changes them: the same as the old fixed rules (and the PC). */
    val DEFAULTS = mapOf("ect_c" to Limit(null, 120.0), "batt_v" to Limit(12.5, null),
        "inj_duty" to Limit(null, 85.0))    // injector near its limit, like decode.DUTY_ALERT

    /** The battery only counts above this RPM: at idle the charging system is low by design. */
    const val BATT_MIN_RPM = 1500.0

    fun check(s: Map<String, Any?>, limits: Map<String, Limit>, o2Lean: Boolean): Set<String> {
        val bad = mutableSetOf<String>()
        val rpm = (s["rpm"] as Number?)?.toDouble() ?: 0.0
        for ((k, lim) in limits) {
            val v = (s[k] as Number?)?.toDouble() ?: continue
            if (k == "batt_v" && rpm <= BATT_MIN_RPM) continue
            if ((lim.low != null && v <= lim.low) || (lim.high != null && v >= lim.high)) bad += k
        }
        // lean at high load: O2 itself swings 0..1 V all the time, so it gets a rule, not limits
        val o2 = (s["o2_v"] as Number?)?.toDouble()
        val tps = (s["tps_pct"] as Number?)?.toDouble() ?: 0.0
        if (o2Lean && o2 != null && o2 < 0.45 && tps > 50 && rpm > 3000) bad += "o2_v"
        return bad
    }

    // ------------------------------------------------------------ saved on the phone

    private const val KEY_O2 = "alert_o2_lean"
    private const val KEY_RED = "alert_red_screen"

    private fun prefs(c: Context) = c.getSharedPreferences(ValueFilter.PREFS, Context.MODE_PRIVATE)

    /** Stored as text: missing = the default, "" = no limit. */
    private fun side(c: Context, key: String, which: String, default: Double?): Double? {
        val v = prefs(c).getString("alert_${key}_$which", null) ?: return default
        return v.toDoubleOrNull()
    }

    fun limit(c: Context, key: String): Limit {
        val d = DEFAULTS[key]
        return Limit(side(c, key, "lo", d?.low), side(c, key, "hi", d?.high))
    }

    fun limits(c: Context): Map<String, Limit> =
        KEYS.associateWith { limit(c, it) }.filterValues { !it.isEmpty }

    fun setLimit(c: Context, key: String, l: Limit) = prefs(c).edit()
        .putString("alert_${key}_lo", l.low?.toString() ?: "")
        .putString("alert_${key}_hi", l.high?.toString() ?: "")
        .apply()

    fun o2Lean(c: Context) = prefs(c).getBoolean(KEY_O2, true)
    fun setO2Lean(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_O2, on).apply()

    /** Fade the whole screen red while something is red. */
    fun redScreen(c: Context) = prefs(c).getBoolean(KEY_RED, true)
    fun setRedScreen(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_RED, on).apply()
}
