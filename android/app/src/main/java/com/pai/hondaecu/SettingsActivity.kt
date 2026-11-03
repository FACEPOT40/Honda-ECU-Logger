package com.pai.hondaecu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** App settings, saved on the phone. Display and comfort only: nothing here changes what is sent to the ECU. */
object Settings {
    private const val KEY_SCREEN = "keep_screen_on"
    private const val KEY_AUTO_START = "auto_start_record"
    private const val KEY_AUTO_STOP = "auto_stop_record_s"

    enum class ScreenOn(val label: String) {
        RECORDING("While recording"),
        CONNECTED("While connected"),
        ALWAYS("Whenever the app is open"),
        NEVER("Never (the phone's own screen timeout)"),
    }

    /** Seconds with the engine off before a recording stops by itself; 0 = never. */
    val AUTO_STOP_CHOICES = listOf(0, 10, 30, 60)

    private fun prefs(c: Context) = c.getSharedPreferences(ValueFilter.PREFS, Context.MODE_PRIVATE)

    fun screenOn(c: Context): ScreenOn =
        prefs(c).getString(KEY_SCREEN, null)?.let { v -> ScreenOn.values().firstOrNull { it.name == v } }
            ?: ScreenOn.CONNECTED

    fun setScreenOn(c: Context, v: ScreenOn) = prefs(c).edit().putString(KEY_SCREEN, v.name).apply()

    /** Start recording when the engine starts (while connected). Off by default. */
    fun autoStart(c: Context) = prefs(c).getBoolean(KEY_AUTO_START, false)
    fun setAutoStart(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO_START, on).apply()

    fun autoStopSeconds(c: Context) = prefs(c).getInt(KEY_AUTO_STOP, 0)
    fun setAutoStopSeconds(c: Context, s: Int) = prefs(c).edit().putInt(KEY_AUTO_STOP, s).apply()
}

/** ⋮ → Settings: one row per setting; tap a row to change it. */
class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        show()                      // the alert limits may have changed on their own page
    }

    private fun show() {
        list.removeAllViews()
        title("Settings")

        section("Screen")
        val screen = Settings.screenOn(this)
        list.addView(settingRow(this, "Keep screen on", screen.label,
            "For a phone on the handlebar. Uses more battery while the screen stays on.") {
            choose("Keep screen on", Settings.ScreenOn.values().map { it.label }, screen.ordinal) {
                Settings.setScreenOn(this, Settings.ScreenOn.values()[it])
            }
        })

        section("Alerts")
        val lims = Alerts.limits(this)
        val summary = if (lims.isEmpty()) "No limits set" else lims.entries.joinToString("  ·  ") { (k, l) ->
            describe(k, l)
        }
        list.addView(settingRow(this, "Alert limits", summary,
            "A value at or beyond its limit turns red. Tap to set a low and / or high limit per value.") {
            startActivity(Intent(this, AlertLimitsActivity::class.java))
        })
        val red = Alerts.redScreen(this)
        list.addView(settingRow(this, "Red screen on alerts", onOff(red),
            "The whole screen fades in and out red while a value is red.") {
            Alerts.setRedScreen(this, !red)
            show()
        })
        val o2 = Alerts.o2Lean(this)
        list.addView(settingRow(this, "O2 lean warning", onOff(o2),
            "O2 under 0.45 V at more than 50 % throttle and 3,000 rpm (O2 swings 0-1 V all the time, " +
                "so it has this rule instead of limits).") {
            Alerts.setO2Lean(this, !o2)
            show()
        })

        section("Recording")
        val start = Settings.autoStart(this)
        list.addView(settingRow(this, "Auto-start recording", onOff(start),
            "Starts recording when the engine starts while connected. Stop by hand still works.") {
            Settings.setAutoStart(this, !start)
            show()
        })
        val stop = Settings.autoStopSeconds(this)
        val stopLabels = Settings.AUTO_STOP_CHOICES.map { if (it == 0) "Off" else "After $it s with the engine off" }
        list.addView(settingRow(this, "Auto-stop recording", stopLabels[Settings.AUTO_STOP_CHOICES.indexOf(stop).coerceAtLeast(0)],
            "Stops a recording by itself once the engine has been off for a while.") {
            choose("Auto-stop recording", stopLabels, Settings.AUTO_STOP_CHOICES.indexOf(stop)) {
                Settings.setAutoStopSeconds(this, Settings.AUTO_STOP_CHOICES[it])
            }
        })

        list.addView(uiButton("Close", Tone.PRIMARY) { finish() })
    }

    private fun title(t: String) = list.addView(uiText(22f, Look.TEXT, t).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    })

    private fun section(t: String) = list.addView(uiText(13f, Look.GREY, t.uppercase(Locale.US)).apply {
        setPadding(dp(4), dp(14), 0, dp(6))
    })

    private fun choose(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                onPick(i)
                d.dismiss()
                show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"
}

/** ⋮ → Settings → Alert limits: a low and / or high limit for each value. */
class AlertLimitsActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
            addView(list)
        })
        show()
    }

    private fun show() {
        list.removeAllViews()
        list.addView(uiText(22f, Look.TEXT, "Alert limits").apply { setTypeface(typeface, Typeface.BOLD) })
        list.addView(uiText(13f, Look.GREY, "Red at or below the low limit, or at or above the high limit. " +
            "Empty = no limit. The battery only counts above ${Alerts.BATT_MIN_RPM.toInt()} rpm: at idle " +
            "it is low by design.").apply { setPadding(0, dp(4), 0, dp(12)) })
        for (key in Alerts.KEYS) {
            val f = Decode.FIELDS.first { it.key == key }
            val lim = Alerts.limit(this, key)
            list.addView(settingRow(this, f.title, if (lim.isEmpty) "No limits" else describe(key, lim), null) {
                edit(f, lim)
            })
        }
        list.addView(uiButton("Back to defaults") {
            for (k in Alerts.KEYS) Alerts.setLimit(this, k, Alerts.DEFAULTS[k] ?: Alerts.Limit(null, null))
            show()
        })
        list.addView(uiButton("Close", Tone.PRIMARY) { finish() })
    }

    private fun edit(f: Decode.Field, lim: Alerts.Limit) {
        fun field(hint: String, v: Double?) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(v?.let { plain(it) } ?: "")
        }
        val low = field("Red at or below (empty = none)", lim.low)
        val high = field("Red at or above (empty = none)", lim.high)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(uiText(13f, Look.GREY, "Low limit"))
            addView(low)
            addView(uiText(13f, Look.GREY, "High limit"))
            addView(high)
        }
        AlertDialog.Builder(this).setTitle(f.title).setView(box)
            .setPositiveButton("Save") { _, _ ->
                Alerts.setLimit(this, f.key, Alerts.Limit(low.text.toString().trim().toDoubleOrNull(),
                    high.text.toString().trim().toDoubleOrNull()))
                show()
            }
            .setNeutralButton("No limits") { _, _ ->
                Alerts.setLimit(this, f.key, Alerts.Limit(null, null))
                show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** "Eng temp ≤ 30 / ≥ 120 °C" */
private fun describe(key: String, l: Alerts.Limit): String {
    val f = Decode.FIELDS.first { it.key == key }
    val parts = listOfNotNull(l.low?.let { "≤ ${plain(it)}" }, l.high?.let { "≥ ${plain(it)}" })
    return "${f.label} ${parts.joinToString(" / ")}${if (f.unit.isEmpty()) "" else " ${f.unit}"}"
}

/** 120.0 -> "120", 12.5 -> "12.5" */
private fun plain(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** A tappable settings row: title, current value (light blue), optional hint. */
private fun settingRow(c: Context, title: String, value: String, hint: String?, onTap: () -> Unit) =
    LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        background = GradientDrawable().apply {
            setColor(Look.TILE)
            setStroke(c.dp(1), Look.LINE)
            cornerRadius = c.dp(10).toFloat()
        }
        addView(c.uiText(16f, Look.TEXT, title))
        addView(c.uiText(15f, 0xFF64B5F6.toInt(), value))      // lighter blue: readable on the dark tile
        if (hint != null) addView(c.uiText(12f, Look.GREY, hint))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = c.dp(8) }
    }
