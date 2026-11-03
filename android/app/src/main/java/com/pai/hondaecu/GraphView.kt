package com.pai.hondaecu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Live graphs: one panel per shown value over the last [windowMs], read from Live.history.
 * Each panel scales itself to the values in view; markers are red dashed lines, as on the PC.
 */
class GraphView(context: Context) : View(context) {
    var fields: List<Decode.Field> = Decode.FIELDS
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    var windowMs = 30_000L
    var colors: Map<String, Int> = emptyMap()
    var tileColor = Color.DKGRAY

    private val panelH = dp(120f)
    private val gap = dp(8f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply {
        color = 0x26FFFFFF
        strokeWidth = dp(1f)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val title = text(13f, Color.WHITE)
    private val value = text(15f, Color.WHITE).apply { isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    private val small = text(10f, Color.parseColor("#A0A8B0"))
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (fields.size * (panelH + gap)).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val since = now - windowMs
        val marks = Live.history.marks(since)
        fields.forEachIndexed { i, f ->
            val top = i * (panelH + gap)
            drawPanel(canvas, f, RectF(0f, top, width.toFloat(), top + panelH), since, marks, i == 0)
        }
    }

    private fun drawPanel(c: Canvas, f: Decode.Field, r: RectF, since: Long,
                          marks: List<Pair<Long, String>>, first: Boolean) {
        val color = colors[f.key] ?: Color.WHITE
        fill.color = tileColor
        c.drawRoundRect(r, dp(10f), dp(10f), fill)

        val s = Live.history.series(f.key, since)
        val last = s.v.lastOrNull { !it.isNaN() }
        title.color = color
        c.drawText(if (f.unit.isEmpty()) f.label else "${f.label} (${f.unit})", r.left + dp(10f), r.top + dp(18f), title)
        c.drawText(Decode.fmtValue(f, last?.toDouble()), r.right - dp(10f), r.top + dp(19f), value)

        // plot area, leaving room for the scale on the left and the time below
        val p = RectF(r.left + dp(52f), r.top + dp(28f), r.right - dp(10f), r.bottom - dp(16f))
        c.drawText("-${windowMs / 1000} s", p.left, r.bottom - dp(4f), small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText("now", p.right, r.bottom - dp(4f), small)
        small.textAlign = Paint.Align.LEFT

        // y range from the values in view
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in s.v) if (!v.isNaN()) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        if (f.key == "neutral") { lo = 0f; hi = 1f }
        if (lo > hi) {
            c.drawText("no data", p.left, p.centerY(), small)
            return
        }
        if (hi - lo < 1e-6f) { val pad = max(abs(hi) * 0.05f, 1f); lo -= pad; hi += pad }
        else if (f.key != "neutral") { val pad = (hi - lo) * 0.08f; lo -= pad; hi += pad }
        val y = { v: Float -> p.bottom - (v - lo) / (hi - lo) * p.height() }
        val x = { t: Long -> p.left + (t - since).toFloat() / windowMs * p.width() }

        for (v in floatArrayOf(lo, (lo + hi) / 2, hi)) {
            c.drawLine(p.left, y(v), p.right, y(v), grid)
            val label = if (f.key == "neutral") Decode.fmtValue(f, v.toDouble()) else "%.${f.decimals}f".format(java.util.Locale.US, v)
            c.drawText(label, r.left + dp(6f), y(v) + dp(4f), small)
        }

        for ((t, m) in marks) {
            c.drawLine(x(t), p.top, x(t), p.bottom, marker)
            if (first) c.drawText(m, x(t) + dp(3f), p.top + dp(10f), small)
        }

        // the line; a missing value breaks it instead of joining across the gap
        path.reset()
        var drawing = false
        for (j in s.t.indices) {
            val v = s.v[j]
            if (v.isNaN()) { drawing = false; continue }
            if (drawing) path.lineTo(x(s.t[j]), y(v)) else path.moveTo(x(s.t[j]), y(v))
            drawing = true
        }
        line.color = color
        c.drawPath(path, line)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun text(sp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        this.color = color
    }
}
