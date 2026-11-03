package com.pai.hondaecu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where a graph reads its samples: the live History, or a ride loaded from a CSV. */
interface GraphSource {
    /** Samples of one value from [from] to [to] (both included), oldest first. */
    fun series(key: String, from: Long, to: Long): History.Series
    fun marks(from: Long, to: Long): List<Pair<Long, String>>
}

/**
 * One panel per shown value between [fromMs] and [toMs], each scaled to the values in view;
 * markers are red dashed lines, as on the PC.
 * Live: the caller moves the range every frame; labels read "-30 s" … "now".
 * Recorded ([live] = false): pinch to zoom, drag sideways to move, tap for a cursor that shows
 * every value at that moment; each panel also shows min · avg · max of the range in view.
 */
class GraphView(context: Context) : View(context) {
    var fields: List<Decode.Field> = Decode.FIELDS
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    var source: GraphSource? = null
    var fromMs = 0L
    var toMs = 1L
    var live = true
        set(v) {
            field = v
            requestLayout()
        }
    var cursorMs: Long? = null
    /** Recorded mode: the whole ride, so zooming and moving stay inside it. */
    var limits: LongRange? = null
    /** Recorded mode: called after the range or the cursor changed. */
    var onChanged: (() -> Unit)? = null
    var colors: Map<String, Int> = emptyMap()
    var tileColor = Color.DKGRAY

    private val panelH get() = dp(if (live) 120f else 136f)
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
    private val cursor = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val title = text(13f, Color.WHITE)
    private val value = text(15f, Color.WHITE).apply { isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    private val small = text(10f, Color.parseColor("#A0A8B0"))
    private val path = Path()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------------------------------------------------------------- gestures (recorded mode)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom(1f / d.scaleFactor, d.focusX)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (abs(dx) < abs(dy)) return false              // mostly vertical: let the page scroll
            parent?.requestDisallowInterceptTouchEvent(true)
            pan(dx)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cursorMs = timeAt(e.x)
            invalidate()
            onChanged?.invoke()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (live) return false
        if (e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun plotLeft() = dp(52f)
    private fun plotWidth() = max(width - dp(10f) - plotLeft(), 1f)
    private fun timeAt(x: Float) = fromMs + ((x - plotLeft()) / plotWidth() * (toMs - fromMs)).toLong()

    /** Moves the view by [dx] pixels (positive = later in the ride). */
    private fun pan(dx: Float) {
        val shift = (dx / plotWidth() * (toMs - fromMs)).toLong()
        setRange(fromMs + shift, toMs + shift)
    }

    /** Zooms around the time under [focusX]: [factor] < 1 zooms in. */
    private fun zoom(factor: Float, focusX: Float) {
        val focus = timeAt(focusX)
        val full = limits?.let { it.last - it.first } ?: Long.MAX_VALUE
        val span = ((toMs - fromMs) * factor).toLong().coerceIn(MIN_SPAN_MS, max(full, MIN_SPAN_MS))
        val left = ((focusX - plotLeft()) / plotWidth()).coerceIn(0f, 1f)
        val from = focus - (span * left).toLong()
        setRange(from, from + span)
    }

    /** Sets the range, kept inside [limits] without changing its length. */
    fun setRange(from: Long, to: Long) {
        var f = from
        var t = to
        limits?.let { l ->
            val span = min(t - f, l.last - l.first)
            if (f < l.first) { f = l.first; t = f + span }
            if (t > l.last) { t = l.last; f = t - span }
        }
        fromMs = f
        toMs = max(t, f + 1)
        invalidate()
        onChanged?.invoke()
    }

    // ---------------------------------------------------------------- drawing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (fields.size * (panelH + gap)).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val src = source ?: return
        val marks = src.marks(fromMs, toMs)
        fields.forEachIndexed { i, f ->
            val top = i * (panelH + gap)
            drawPanel(canvas, src, f, RectF(0f, top, width.toFloat(), top + panelH), marks, i == 0)
        }
    }

    private fun drawPanel(c: Canvas, src: GraphSource, f: Decode.Field, r: RectF,
                          marks: List<Pair<Long, String>>, first: Boolean) {
        val color = colors[f.key] ?: Color.WHITE
        fill.color = tileColor
        c.drawRoundRect(r, dp(10f), dp(10f), fill)
        val s = src.series(f.key, fromMs, toMs)
        val cur = cursorMs?.takeIf { !live && it in fromMs..toMs }

        // the value: the newest one when live, the one under the cursor when recorded
        val shownValue = if (cur != null) valueAt(s, cur) else s.v.lastOrNull { !it.isNaN() }
        title.color = color
        c.drawText(f.title, r.left + dp(10f), r.top + dp(18f), title)
        c.drawText(Decode.fmtValue(f, shownValue?.toDouble()), r.right - dp(10f), r.top + dp(19f), value)

        // plot area, leaving room for the scale on the left and the time below
        val p = RectF(plotLeft(), r.top + dp(if (live) 28f else 40f), plotLeft() + plotWidth(), r.bottom - dp(16f))
        val (leftLabel, rightLabel) = if (live) "-${(toMs - fromMs + 500) / 1000} s" to "now"
            else clock.format(Date(fromMs)) to clock.format(Date(toMs))
        c.drawText(leftLabel, p.left, r.bottom - dp(4f), small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText(rightLabel, p.right, r.bottom - dp(4f), small)
        small.textAlign = Paint.Align.LEFT

        // y range from the values in view
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var n = 0
        for (v in s.v) if (!v.isNaN()) { lo = minOf(lo, v); hi = maxOf(hi, v); sum += v; n++ }
        if (lo > hi) {
            c.drawText("no data", p.left, p.centerY(), small)
            return
        }
        if (!live && f.key != "neutral") {
            val fmt = { v: Double -> Decode.fmtValue(f, v) }
            c.drawText("min ${fmt(lo.toDouble())} · avg ${fmt(sum / n)} · max ${fmt(hi.toDouble())}",
                r.left + dp(10f), r.top + dp(33f), small)
        }
        val gear = f.key == "neutral"
        val nonNegative = lo >= 0f          // e.g. TPS or speed: the margin must not show a negative scale
        if (gear) { lo = -0.15f; hi = 1.15f }           // Gear / N with a little room above and below
        else if (hi - lo < 1e-6f) {
            // a flat line (Speed is always 0 on the Wave): keep whole-number labels
            val pad = max(abs(hi) * 0.05f, 1f)
            lo -= pad
            hi += pad
            if (nonNegative && lo < 0f) { hi -= lo; lo = 0f }
        } else {
            val pad = (hi - lo) * 0.08f
            lo -= pad
            hi += pad
            if (nonNegative && lo < 0f) lo = 0f
        }
        val y = { v: Float -> p.bottom - (v - lo) / (hi - lo) * p.height() }
        val x = { t: Long -> p.left + (t - fromMs).toFloat() / (toMs - fromMs) * p.width() }

        for (v in if (gear) floatArrayOf(0f, 1f) else floatArrayOf(lo, (lo + hi) / 2, hi)) {
            c.drawLine(p.left, y(v), p.right, y(v), grid)
            val label = if (gear) Decode.fmtValue(f, v.toDouble()) else "%.${f.decimals}f".format(Locale.US, v)
            c.drawText(label, r.left + dp(6f), y(v) + dp(4f), small)
        }

        for ((t, m) in marks) {
            c.drawLine(x(t), p.top, x(t), p.bottom, marker)
            if (first) c.drawText(m, x(t) + dp(3f), p.top + dp(10f), small)
        }

        drawLine(c, s, x, y, p.width())
        line.color = color
        c.drawPath(path, line)
        if (cur != null) c.drawLine(x(cur), p.top, x(cur), p.bottom, cursor)
    }

    /**
     * Builds the line; a missing value breaks it instead of joining across the gap. With more
     * samples than pixels (a whole ride), each pixel column draws its min and max, so peaks stay.
     */
    private fun drawLine(c: Canvas, s: History.Series, x: (Long) -> Float, y: (Float) -> Float, w: Float) {
        path.reset()
        var drawing = false
        if (s.t.size <= w * 2) {
            for (j in s.t.indices) {
                val v = s.v[j]
                if (v.isNaN()) { drawing = false; continue }
                if (drawing) path.lineTo(x(s.t[j]), y(v)) else path.moveTo(x(s.t[j]), y(v))
                drawing = true
            }
            return
        }
        var col = Int.MIN_VALUE
        var lo = 0f
        var hi = 0f
        var has = false
        fun flush() {
            if (!has) return
            val px = col.toFloat()
            if (drawing) path.lineTo(px, y(lo)) else path.moveTo(px, y(lo))
            path.lineTo(px, y(hi))
            drawing = true
            has = false
        }
        for (j in s.t.indices) {
            val v = s.v[j]
            if (v.isNaN()) { flush(); drawing = false; continue }
            val px = x(s.t[j]).toInt()
            if (px != col) { flush(); col = px; lo = v; hi = v; has = true }
            else { lo = minOf(lo, v); hi = maxOf(hi, v) }
        }
        flush()
    }

    /** The sample nearest to [t] in [s], or null if there is none. */
    private fun valueAt(s: History.Series, t: Long): Float? {
        if (s.t.isEmpty()) return null
        var i = s.t.binarySearch(t)
        if (i < 0) {
            i = -i - 1
            if (i >= s.t.size || (i > 0 && t - s.t[i - 1] < s.t[i] - t)) i--
        }
        return s.v[i].takeIf { !it.isNaN() }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun text(sp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        this.color = color
    }

    companion object {
        const val MIN_SPAN_MS = 2_000L
    }
}
