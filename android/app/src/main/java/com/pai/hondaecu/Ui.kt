package com.pai.hondaecu

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
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

fun Context.uiButton(label: String, tone: Tone = Tone.PLAIN, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
    tone(tone)
}

/** Button looks, like the PC's theme.py: PRIMARY = blue (Connect, chosen option),
 *  RECORD = amber (Start Record), STOP = red (Stop while recording). */
enum class Tone { PLAIN, PRIMARY, RECORD, STOP }

fun Button.tone(t: Tone) {
    val (fill, edge, label) = when (t) {
        Tone.PLAIN -> Triple(Look.TILE, Look.LINE, Look.TEXT)
        Tone.PRIMARY -> Triple(Look.BLUE, Look.BLUE, Color.WHITE)
        Tone.RECORD -> Triple(Look.AMBER, Look.AMBER, Look.BACKGROUND)
        Tone.STOP -> Triple(Look.RED, Look.RED, Color.WHITE)
    }
    val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
    val shape = GradientDrawable().apply {
        cornerRadius = context.dp(8).toFloat()
        color = ColorStateList(states, intArrayOf(Look.TILE, fill))
        setStroke(context.dp(1), ColorStateList(states, intArrayOf(Look.TILE, edge)))
    }
    val gap = context.dp(3)    // the space between buttons the default look had
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), InsetDrawable(shape, gap), null)
    setTextColor(ColorStateList(states, intArrayOf(Look.DISABLED, label)))
    stateListAnimator = null
    setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
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

/** Colors shared by every screen: the brand colors are the icon's and theme.py's on the PC.
 *  Value colors are the same as COLORS in viewer.py. */
object Look {
    val COLORS = mapOf(
        "rpm" to "#4fc3f7", "tps_pct" to "#ffb74d", "ect_c" to "#ef5350",
        "batt_v" to "#9ccc65", "speed_kmh" to "#ba68c8", "gps_kmh" to "#7986cb", "inj_ms" to "#4db6ac",
        "ign_deg" to "#fff176", "o2_v" to "#f06292", "stft" to "#e0e0e0", "inj_duty" to "#26c6da",
        "mode_d14" to "#90a4ae", "neutral" to "#a1887f",
    ).mapValues { Color.parseColor(it.value) }
    val BACKGROUND = Color.parseColor("#08090C")
    val TILE = Color.parseColor("#141A22")
    val LINE = Color.parseColor("#232C38")
    val BLUE = Color.parseColor("#1976D2")
    val AMBER = Color.parseColor("#FFA000")
    val TEXT = Color.parseColor("#E8EDF3")
    val GREY = Color.parseColor("#8A96A6")
    val DISABLED = Color.parseColor("#4A5462")
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
    private const val KEY_DEFAULTS = "filter_defaults_v1"
    private const val KEY_DEFAULTS_2 = "filter_defaults_v2"

    /** Hidden until the rider shows them: Mode (d14) never changes on the Wave, Gear is only
     *  N / in gear, and ECU speed is 0 on bikes with a cable speedometer (GPS speed shows). */
    val DEFAULT_HIDDEN = setOf("mode_d14", "neutral", "speed_kmh")

    fun hidden(c: Context): Set<String> {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DEFAULTS, false)) {
            // once, on updating to 0.3: apply the default unless the rider already hid something
            val edit = prefs.edit().putBoolean(KEY_DEFAULTS, true)
            if (prefs.getStringSet(KEY_HIDDEN, null).isNullOrEmpty()) edit.putStringSet(KEY_HIDDEN, DEFAULT_HIDDEN)
            edit.apply()
        }
        if (!prefs.getBoolean(KEY_DEFAULTS_2, false)) {
            // once, on updating to the version with Inj duty: hide ECU speed (Pai's choice, GPS
            // speed is the one to read); the new Inj duty tile shows because it isn't hidden
            val now = prefs.getStringSet(KEY_HIDDEN, null) ?: DEFAULT_HIDDEN
            prefs.edit().putBoolean(KEY_DEFAULTS_2, true).putStringSet(KEY_HIDDEN, now + "speed_kmh").apply()
        }
        return prefs.getStringSet(KEY_HIDDEN, null) ?: DEFAULT_HIDDEN
    }

    fun shown(c: Context): List<Decode.Field> = hidden(c).let { h -> ordered(c).filter { it.key !in h } }

    // ------------------------------------------------------------ order (hold a tile and drag it)

    private const val KEY_ORDER = "value_order"

    /** Every value in the rider's order: the saved order first, then any value it doesn't know
     *  yet (added in a later version) in the default place order. Tiles, live graphs and the
     *  recorded-ride graphs all follow it. */
    fun ordered(c: Context): List<Decode.Field> {
        val saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORDER, null)
        return order(saved?.split(",").orEmpty()).map { k -> Decode.FIELDS.first { it.key == k } }
    }

    /** The full key order from a saved list: unknown keys dropped, missing ones appended. */
    fun order(saved: List<String>): List<String> {
        val all = Decode.FIELDS.map { it.key }
        val known = saved.filter { it in all }.distinct()
        return known + all.filter { it !in known }
    }

    /**
     * [key] dropped on [target]: it takes the target's place among the shown values and the ones
     * in between shift by one. Hidden values keep their places in the full order, so they come
     * back where they were when shown again.
     */
    fun moved(order: List<String>, hidden: Set<String>, key: String, target: String): List<String> {
        val shown = order.filter { it !in hidden }.toMutableList()
        val to = shown.indexOf(target)
        if (key == target || to < 0 || !shown.remove(key)) return order
        shown.add(to, key)
        val next = shown.iterator()
        return order.map { k -> if (k in hidden) k else next.next() }
    }

    fun move(c: Context, key: String, target: String) {
        val now = ordered(c).map { it.key }
        val next = moved(now, hidden(c), key, target)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ORDER, next.joinToString(",")).apply()
    }

    fun resetOrder(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ORDER).apply()

    fun choose(c: Context, onChanged: () -> Unit) {
        val fields = ordered(c)
        val hidden = hidden(c)
        val checked = BooleanArray(fields.size) { fields[it].key !in hidden }
        fun save(keys: Set<String>) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN, keys).apply()
            onChanged()
        }
        AlertDialog.Builder(c)
            .setTitle("Filter: values to show (all are still recorded)")
            .setMultiChoiceItems(Array(fields.size) { fields[it].title }, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                save(fields.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet())
            }
            .setNeutralButton("Show all") { _, _ -> save(emptySet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
