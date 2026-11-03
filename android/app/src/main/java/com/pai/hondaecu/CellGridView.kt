package com.pai.hondaecu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The cell hit table as a grid like the tune map in TunerPro: RPM rows, TPS columns. Drag in
 * any direction to move around; the RPM and TPS headers stay in place. Tap a cell to select it.
 */
class CellGridView(context: Context) : View(context) {
    enum class Mode(val label: String) { TIME("Time s"), O2("O2 V"), LEAN("Lean %"), INJ("Inj ms"), SAMPLES("Samples") }

    var hits: Cells.Hits? = null
        set(v) { field = v; invalidate() }
    var mode = Mode.O2
        set(v) { field = v; invalidate() }
    var selected: Pair<Int, Int>? = null
        set(v) { field = v; invalidate() }
    var onSelect: ((Int, Int) -> Unit)? = null

    private val cellW = dp(50f)
    private val cellH = dp(30f)
    private val headW = dp(48f)
    private val headH = dp(26f)
    private var ox = 0f
    private var oy = 0f

    private val fill = Paint()
    private val gridLine = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
    private val selLine = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f); textAlign = Paint.Align.CENTER; color = Color.WHITE
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f); textAlign = Paint.Align.CENTER; color = Look.GREY
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollTo(ox + dx, oy + dy)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.x < headW || e.y < headH) return false
            val c = ((e.x - headW + ox) / cellW).toInt()
            val r = ((e.y - headH + oy) / cellH).toInt()
            if (r !in Cells.RPM_AXIS.indices || c !in Cells.TPS_AXIS.indices) return false
            selected = r to c
            onSelect?.invoke(r, c)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(e)
    }

    private fun scrollTo(x: Float, y: Float) {
        ox = x.coerceIn(0f, max(0f, headW + Cells.TPS_AXIS.size * cellW - width))
        oy = y.coerceIn(0f, max(0f, headH + Cells.RPM_AXIS.size * cellH - height))
        invalidate()
    }

    /** Scrolls so that cell (r, c) is near the middle of the view. */
    fun centerOn(r: Int, c: Int) {
        post { scrollTo(headW + (c + 0.5f) * cellW - width / 2f, headH + (r + 0.5f) * cellH - height / 2f) }
    }

    override fun onDraw(canvas: Canvas) {
        val h = hits ?: return
        canvas.drawColor(Look.BACKGROUND)
        val values = when (mode) {
            Mode.TIME -> h.seconds
            Mode.O2 -> h.o2
            Mode.LEAN -> h.leanPct
            Mode.INJ -> h.inj
            Mode.SAMPLES -> Array(h.n.size) { r -> DoubleArray(h.n[r].size) { c -> h.n[r][c].toDouble() } }
        }
        val top = values.flatMap { it.filter { v -> !v.isNaN() }.toList() }.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        // cells, clipped so they scroll under the fixed headers
        canvas.save()
        canvas.clipRect(headW, headH, width.toFloat(), height.toFloat())
        val r0 = max(0, ((oy) / cellH).toInt())
        val r1 = min(Cells.RPM_AXIS.size - 1, ((oy + height) / cellH).toInt())
        val c0 = max(0, (ox / cellW).toInt())
        val c1 = min(Cells.TPS_AXIS.size - 1, ((ox + width) / cellW).toInt())
        for (r in r0..r1) for (c in c0..c1) {
            val rect = cellRect(r, c)
            val v = values[r][c]
            val empty = if (mode == Mode.TIME) v < 0.05 else (h.n[r][c] == 0 || v.isNaN())
            if (!empty) {
                val few = mode != Mode.TIME && h.n[r][c] < CellsActivity.MIN_SAMPLES
                fill.color = color(v, top, few)
                canvas.drawRect(rect, fill)
                text.color = if (few) Look.GREY else Color.WHITE
                text.typeface = if (few) Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) else Typeface.DEFAULT
                canvas.drawText(format(v), rect.centerX(), rect.centerY() + sp(4f), text)
            }
            canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, gridLine)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, gridLine)
        }
        selected?.let { (r, c) -> canvas.drawRect(cellRect(r, c), selLine) }
        canvas.restore()

        // headers: TPS % across the top, RPM down the left, the selected ones in white
        fill.color = Look.TILE
        canvas.drawRect(0f, 0f, width.toFloat(), headH, fill)
        canvas.drawRect(0f, 0f, headW, height.toFloat(), fill)
        canvas.save()
        canvas.clipRect(headW, 0f, width.toFloat(), headH)
        for (c in c0..c1) {
            head.color = if (selected?.second == c) Color.WHITE else Look.GREY
            val tps = Cells.TPS_AXIS[c]
            canvas.drawText(if (tps == tps.toInt().toFloat()) "${tps.toInt()}" else "$tps",
                headW + (c + 0.5f) * cellW - ox, headH - dp(8f), head)
        }
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, headH, headW, height.toFloat())
        for (r in r0..r1) {
            head.color = if (selected?.first == r) Color.WHITE else Look.GREY
            canvas.drawText("${Cells.RPM_AXIS[r].toInt()}", headW / 2, headH + (r + 0.5f) * cellH - oy + sp(4f), head)
        }
        canvas.restore()
        head.color = Look.GREY
        canvas.drawText("rpm\\%", headW / 2, headH - dp(8f), head)
    }

    private fun cellRect(r: Int, c: Int): RectF {
        val x = headW + c * cellW - ox
        val y = headH + r * cellH - oy
        return RectF(x, y, x + cellW, y + cellH)
    }

    private fun format(v: Double) = when (mode) {
        Mode.TIME -> "%.1f".format(Locale.US, v)
        Mode.O2, Mode.INJ -> "%.2f".format(Locale.US, v)
        Mode.LEAN, Mode.SAMPLES -> "%.0f".format(Locale.US, v)
    }

    /** Dark tile blended toward a color: blue = lean side, orange/red = rich, teal = amount. */
    private fun color(v: Double, top: Double, few: Boolean): Int {
        val (target, f) = when (mode) {
            Mode.O2 -> if (v < Cells.LEAN_V) 0xFF4F8FF7.toInt() to (Cells.LEAN_V - v) / Cells.LEAN_V
                else 0xFFFF7043.toInt() to (v - Cells.LEAN_V) / 0.5
            Mode.LEAN -> 0xFF4F8FF7.toInt() to v / 100
            Mode.TIME, Mode.INJ -> 0xFF26A69A.toInt() to 0.15 + 0.85 * v / top
            Mode.SAMPLES -> 0xFF9E9E9E.toInt() to 0.15 + 0.85 * v / top
        }
        val k = (if (few) 0.35 else 1.0) * (0.25 + 0.75 * f.coerceIn(0.0, 1.0))
        fun mix(a: Int, b: Int) = (a + (b - a) * k).toInt()
        return Color.rgb(mix(Color.red(Look.TILE), Color.red(target)), mix(Color.green(Look.TILE), Color.green(target)),
            mix(Color.blue(Look.TILE), Color.blue(target)))
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
