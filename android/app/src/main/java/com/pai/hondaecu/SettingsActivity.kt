package com.pai.hondaecu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** App settings, saved on the phone. Display and comfort only: nothing here changes what is sent to the ECU. */
object Settings {
    private const val KEY_SCREEN = "keep_screen_on"

    enum class ScreenOn(val label: String) {
        RECORDING("While recording"),
        CONNECTED("While connected"),
        ALWAYS("Whenever the app is open"),
        NEVER("Never (the phone's own screen timeout)"),
    }

    private fun prefs(c: Context) = c.getSharedPreferences(ValueFilter.PREFS, Context.MODE_PRIVATE)

    fun screenOn(c: Context): ScreenOn =
        prefs(c).getString(KEY_SCREEN, null)?.let { v -> ScreenOn.values().firstOrNull { it.name == v } }
            ?: ScreenOn.RECORDING

    fun setScreenOn(c: Context, v: ScreenOn) = prefs(c).edit().putString(KEY_SCREEN, v.name).apply()
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
        show()
    }

    private fun show() {
        list.removeAllViews()
        list.addView(uiText(22f, Look.TEXT, "Settings").apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        val screen = Settings.screenOn(this)
        list.addView(row("Keep screen on", screen.label,
            "For a phone on the handlebar. Uses more battery while the screen stays on.") {
            val all = Settings.ScreenOn.values()
            AlertDialog.Builder(this).setTitle("Keep screen on")
                .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(screen)) { d, i ->
                    Settings.setScreenOn(this, all[i])
                    d.dismiss()
                    show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        list.addView(uiButton("Close", Tone.PRIMARY) { finish() }.apply { setPadding(0, dp(12), 0, dp(12)) })
    }

    private fun row(title: String, value: String, hint: String, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Look.TILE)
            setStroke(dp(1), Look.LINE)
            cornerRadius = dp(10).toFloat()
        }
        addView(uiText(16f, Look.TEXT, title))
        addView(uiText(15f, 0xFF64B5F6.toInt(), value))   // lighter blue: readable on the dark tile
        addView(uiText(12f, Look.GREY, hint) as TextView)
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
}
