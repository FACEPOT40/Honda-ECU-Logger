package com.pai.hondaecu

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cell hits for the ride open in RideViewActivity, over the time range that was in view there:
 * time, O2, lean share, injector or samples per map cell. Port of the PC viewer's table.
 */
class CellsActivity : Activity() {
    private lateinit var grid: CellGridView
    private lateinit var details: TextView
    private lateinit var steadyBox: CheckBox
    private val modeBtns = mutableMapOf<CellGridView.Mode, Button>()
    private lateinit var ride: RideData
    private var from = 0L
    private var to = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ride = OpenRide.ride ?: run { finish(); return }        // the app was restarted: go back
        from = intent.getLongExtra(EXTRA_FROM, ride.start)
        to = intent.getLongExtra(EXTRA_TO, ride.end)
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }
        // landscape: a title line, one row of controls, the grid, then one line of details
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(uiText(14f, Color.WHITE, "Cell hits · ${intent.getStringExtra(EXTRA_NAME) ?: ""}"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(uiText(11f, Look.GREY, "Range ${clock.format(Date(from))} – ${clock.format(Date(to))}  ·  " +
                "rows = RPM, columns = TPS %  ·  O2: blue = lean, orange/red = rich  ·  faint = under $MIN_SAMPLES samples"))
        })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (m in CellGridView.Mode.values()) {
            modeBtns[m] = uiButton(m.label) { setMode(m) }.also { controls.addView(it) }
        }
        steadyBox = CheckBox(this).apply {
            text = "Steady throttle only"
            setTextColor(Look.GREY)
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        controls.addView(steadyBox)
        root.addView(HorizontalScrollView(this).apply { addView(controls) })

        grid = CellGridView(this).apply { onSelect = { r, c -> showDetails(r, c) } }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        details = uiText(13f, Color.WHITE, "Drag to move around, tap a cell for its numbers.")
        root.addView(details)
        setContentView(root)

        setMode(CellGridView.Mode.O2)
        // start at the cursor's cell, or else the cell where the engine spent the most time
        val h = grid.hits!!
        val start = intent.getLongExtra(EXTRA_CURSOR, -1).takeIf { it >= 0 }
            ?.let { Cells.cellAt(ride, ride.indexAt(it)) }
            ?: h.seconds.indices.flatMap { r -> h.seconds[r].indices.map { c -> r to c } }
                .maxByOrNull { (r, c) -> h.seconds[r][c] }
        start?.let { (r, c) ->
            grid.selected = r to c
            grid.centerOn(r, c)
            showDetails(r, c)
        }
    }

    private fun setMode(m: CellGridView.Mode) {
        grid.mode = m
        for ((mode, b) in modeBtns) b.alpha = if (mode == m) 1f else 0.45f
        refresh()
    }

    private fun refresh() {
        grid.hits = Cells.hits(ride, from, to, if (steadyBox.isChecked) OpenRide.steady() else null)
        grid.selected?.let { (r, c) -> showDetails(r, c) }
    }

    private fun showDetails(r: Int, c: Int) {
        val h = grid.hits ?: return
        val s = StringBuilder("${Cells.name(r, c)}: ${"%.1f".format(Locale.US, h.seconds[r][c])} s running")
        if (h.n[r][c] > 0) s.append("  ·  O2 ${"%.2f".format(Locale.US, h.o2[r][c])} V average, " +
            "${"%.0f".format(h.leanPct[r][c])} % lean  ·  injector ${"%.2f".format(Locale.US, h.inj[r][c])} ms  ·  ${h.n[r][c]} samples")
        else s.append("  ·  no steady fuel-on samples here")
        details.text = s
    }

    companion object {
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_CURSOR = "cursor"
        const val EXTRA_NAME = "name"
        const val MIN_SAMPLES = 5
    }
}

/** The ride open in the viewer, shared with the Cells screen (too big for an Intent). */
object OpenRide {
    var ride: RideData? = null
        set(v) { field = v; steadyMask = null }
    private var steadyMask: BooleanArray? = null

    /** Steady-throttle moments of the open ride, worked out once. */
    fun steady(): BooleanArray {
        val r = ride ?: return BooleanArray(0)
        return steadyMask ?: Cells.steady(r.t, r.values("rpm") ?: FloatArray(r.t.size) { Float.NaN },
            r.values("tps_pct") ?: FloatArray(r.t.size) { Float.NaN }).also { steadyMask = it }
    }
}
