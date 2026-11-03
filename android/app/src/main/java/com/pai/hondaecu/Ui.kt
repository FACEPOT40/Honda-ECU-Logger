package com.pai.hondaecu

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.uiText(size: Float, color: Int, value: String = "") = TextView(this).apply {
    textSize = size
    setTextColor(color)
    text = value
}

fun Context.uiButton(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
}

/**
 * Android 15+ draws under the system bars: keep the content clear of them. In landscape the
 * navigation bar and the camera cutout are on the sides, so the left and right count too.
 */
fun View.padForSystemBars() = setOnApplyWindowInsetsListener { v, insets ->
    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    v.setPadding(b.left + context.dp(12), b.top + context.dp(8), b.right + context.dp(12), b.bottom + context.dp(8))
    insets
}

/** "6:18", or "1:02:03" from one hour, like fmt_dur in viewer.py. */
fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Colors shared by every screen. Value colors are the same as COLORS in viewer.py. */
object Look {
    val COLORS = mapOf(
        "rpm" to "#4fc3f7", "tps_pct" to "#ffb74d", "ect_c" to "#ef5350",
        "batt_v" to "#9ccc65", "speed_kmh" to "#ba68c8", "gps_kmh" to "#7986cb", "inj_ms" to "#4db6ac",
        "ign_deg" to "#fff176", "o2_v" to "#f06292", "stft" to "#e0e0e0",
        "mode_d14" to "#90a4ae", "neutral" to "#a1887f",
    ).mapValues { Color.parseColor(it.value) }
    val BACKGROUND = Color.parseColor("#101214")
    val TILE = Color.parseColor("#1E2226")
    val GREY = Color.parseColor("#A0A8B0")
    val GREEN = Color.parseColor("#43A047")
    val ORANGE = Color.parseColor("#FB8C00")
    val RED = Color.parseColor("#EF5350")
}

/**
 * Which values are on screen: tiles, live graphs and recorded graphs share one choice.
 * Display only: polling and the CSV never look at it.
 */
object ValueFilter {
    const val PREFS = "screen"
    private const val KEY_HIDDEN = "hidden_values"

    fun hidden(c: Context): Set<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, null) ?: emptySet()

    fun shown(c: Context): List<Decode.Field> = hidden(c).let { h -> Decode.FIELDS.filter { it.key !in h } }

    fun choose(c: Context, onChanged: () -> Unit) {
        val fields = Decode.FIELDS
        val hidden = hidden(c)
        val checked = BooleanArray(fields.size) { fields[it].key !in hidden }
        fun save(keys: Set<String>) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN, keys).apply()
            onChanged()
        }
        AlertDialog.Builder(c)
            .setTitle("Values to show (all are still recorded)")
            .setMultiChoiceItems(Array(fields.size) { fields[it].title }, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                save(fields.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet())
            }
            .setNeutralButton("Show all") { _, _ -> save(emptySet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
