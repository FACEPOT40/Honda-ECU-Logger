package com.pai.hondaecu

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * One recorded ride as graphs, like viewer.py on the PC: pinch to zoom, drag sideways to move,
 * tap for every value at that moment, markers to jump to. Uses the same Values choice as the
 * main screen. Read only: it never changes the file.
 */
class RideViewActivity : Activity() {
    private lateinit var graph: GraphView
    private lateinit var info: TextView
    private lateinit var cursorText: TextView
    private lateinit var valuesBtn: Button
    private lateinit var markerRow: LinearLayout
    private lateinit var fullBtn: Button
    private var ride: RideData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Ride"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }
        info = uiText(12f, Look.GREY, "Loading…")
        valuesBtn = uiButton("Filter") { ValueFilter.choose(this) { showFields() } }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@RideViewActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(uiText(15f, android.graphics.Color.WHITE, name))
                addView(info)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valuesBtn)
        })
        cursorText = uiText(13f, android.graphics.Color.WHITE)
        root.addView(cursorText)
        markerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        markerRow.addView(uiButton("Summary") { showSummary() })
        markerRow.addView(uiButton("Cells") { openCells(name) })
        fullBtn = uiButton("Full ride") { ride?.let { graph.setRange(it.start, it.end) } }
        markerRow.addView(fullBtn)
        root.addView(HorizontalScrollView(this).apply { addView(markerRow) })

        graph = GraphView(this).apply {
            live = false
            colors = Look.COLORS
            tileColor = Look.TILE
            onChanged = { updateCursorText() }
        }
        root.addView(ScrollView(this).apply { addView(graph) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showFields()
        load()
    }

    private fun load() {
        val uri = intent.data ?: return
        Thread {
            val result = try {
                contentResolver.openInputStream(uri)!!.bufferedReader().useLines { RideData.parse(it) }
            } catch (e: Exception) {
                e
            }
            runOnUiThread { if (result is RideData) show(result) else info.text = "Could not open: ${(result as Exception).message}" }
        }.start()
    }

    private fun show(r: RideData) {
        ride = r
        OpenRide.ride = r
        val rate = if (r.durationMs > 0) (r.t.size - 1) * 1000.0 / r.durationMs else 0.0
        info.text = "Start ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(r.start))} · " +
            "Length ${formatDuration(r.durationMs)} · ${"%,d".format(r.t.size)} samples · ${"%.1f".format(Locale.US, rate)}/s"
        graph.source = r
        graph.limits = r.start..r.end
        for ((t, m) in r.markers) {
            markerRow.addView(uiButton("⚑ $m  ${CLOCK.format(Date(t))}") { jumpTo(t) })
        }
        graph.setRange(r.start, r.end)
    }

    /** Centres the view on [t] (at most 60 s wide) and puts the cursor there. */
    private fun jumpTo(t: Long) {
        val span = min(graph.toMs - graph.fromMs, 60_000L)
        graph.cursorMs = t
        graph.setRange(t - span / 2, t + span / 2)
    }

    private fun showFields() {
        val shown = ValueFilter.shown(this)
        graph.fields = shown
        valuesBtn.text = "Filter ${shown.size}/${Decode.FIELDS.size}"
    }

    private fun updateCursorText() {
        val r = ride ?: return
        // "Full ride" zooms back out: dimmed while the whole ride is already showing
        val zoomedIn = graph.fromMs > r.start || graph.toMs < r.end
        fullBtn.alpha = if (zoomedIn) 1f else 0.4f
        val c = graph.cursorMs
        cursorText.text = if (c == null) "Tap a graph to read every value at that moment. Pinch to zoom, drag sideways to move."
            else "Cursor ${CURSOR_CLOCK.format(Date(c))}  (+${formatDuration(c - r.start)})" +
                (Cells.cellAt(r, r.indexAt(c))?.let { (row, col) -> "\nMap cell ${Cells.name(row, col)}" } ?: "")
    }

    private fun showSummary() {
        val r = ride ?: return
        val text = RideSummary.lines(r, OpenRide.steady()).joinToString("\n\n") { (k, v) -> "$k\n$v" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ride summary")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    /** The cell table for the time range now in view; it starts at the cursor's cell. */
    private fun openCells(name: String) {
        if (ride == null) return
        startActivity(android.content.Intent(this, CellsActivity::class.java)
            .putExtra(CellsActivity.EXTRA_FROM, graph.fromMs)
            .putExtra(CellsActivity.EXTRA_TO, graph.toMs)
            .putExtra(CellsActivity.EXTRA_CURSOR, graph.cursorMs ?: -1L)
            .putExtra(CellsActivity.EXTRA_NAME, name))
    }

    companion object {
        const val EXTRA_NAME = "name"
        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val CURSOR_CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
