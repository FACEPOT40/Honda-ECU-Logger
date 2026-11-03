package com.pai.hondaecu

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Name, version, what the app does and doesn't do, credits, license: the same as AboutDialog in app.py. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@AboutActivity).apply { setImageResource(R.drawable.ic_launcher) },
                LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@AboutActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(uiText(24f, Look.TEXT, "H-ECU Logger").apply { setTypeface(typeface, Typeface.BOLD) })
                addView(uiText(14f, Look.AMBER, "K-line · version $version"))
                addView(uiText(13f, Look.GREY, "Live data logger for Honda / Keihin ECUs"))
            })
        })
        for ((title, body) in SECTIONS) {
            col.addView(uiText(15f, Look.TEXT, title).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(2))
            })
            col.addView(uiText(14f, Look.GREY, body))
        }
        col.addView(uiText(12f, Look.GREY, "Not affiliated with or endorsed by Honda Motor Co., Ltd. " +
            "Honda is a trademark of its owner.").apply { setPadding(0, dp(16), 0, dp(12)) })
        col.addView(uiButton("Source on GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        })
        col.addView(uiButton("Close", Tone.PRIMARY) { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
            addView(col)
        })
    }

    companion object {
        const val REPO_URL = "https://github.com/FACEPOT40/Honda-ECU-Logger"
        private val SECTIONS = listOf(
            "Read only" to "Reads live data and fault codes, nothing else. It never writes, flashes or " +
                "clears anything in the ECU.",
            "Ride safe" to "Never look at the screen while riding: record, ride, and look at the graphs afterwards.",
            "Tested on" to "Honda Wave 125i (2021), ECU 38770-K73-T61, with an FTDI FT232R K-line cable " +
                "and a USB OTG adapter.",
            "Thanks to" to "AutotronicCommunity (K-line protocol notes) · HondaECU by RyanHope / MCU Innovations " +
                "(value formulas, fault-code reads, GPL-3.0) · RE-ECU-th (PGM-FI live data sheet)",
            "License" to "Made by FACEPOT40. GPL-3.0, free and open source.",
        )
    }
}
