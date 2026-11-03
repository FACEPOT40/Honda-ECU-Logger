package com.pai.hondaecu

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A recorded ride read from a CSV, as written by the phone or the PC (same columns). Values are
 * found by column name, so older CSVs with fewer columns still open; missing values are NaN.
 * Port of load_csv in viewer.py.
 */
class RideData(
    val t: LongArray,
    private val cols: Map<String, FloatArray>,
    val markers: List<Pair<Long, String>>,
) : GraphSource {
    val start get() = t.first()
    val end get() = t.last()
    val durationMs get() = end - start

    /** True if the CSV has at least one value for [key]. */
    fun has(key: String) = cols[key]?.any { !it.isNaN() } == true

    override fun series(key: String, from: Long, to: Long): History.Series {
        val v = cols[key] ?: return History.Series(LongArray(0), FloatArray(0))
        val a = lowerBound(from)
        val b = lowerBound(to + 1)
        return History.Series(t.copyOfRange(a, b), v.copyOfRange(a, b))
    }

    override fun marks(from: Long, to: Long) = markers.filter { it.first in from..to }

    /** First index with a time >= [x]. */
    private fun lowerBound(x: Long): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        // the same formats viewer.py accepts, including CSVs re-saved by Excel
        private val TIME_FORMATS = listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss.SSS", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm")

        fun parseTime(s: String): Long? {
            val text = s.trim()
            for (f in TIME_FORMATS) {
                val pos = ParsePosition(0)
                val d = SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(text, pos)
                if (d != null && pos.index == text.length) return d.time
            }
            return null
        }

        fun parse(lines: Sequence<String>): RideData {
            val it = lines.iterator()
            require(it.hasNext()) { "The file is empty." }
            val header = it.next().removePrefix("﻿").trim().split(",")
            val ti = header.indexOf("timestamp")
            require(ti >= 0) { "No timestamp column: this is not a ride log." }
            val mi = header.indexOf("marker")
            val keys = Decode.FIELDS.map { it.key }.filter { it in header }
            val idx = keys.map { header.indexOf(it) }
            val times = ArrayList<Long>()
            val values = keys.map { ArrayList<Float>() }
            val markers = ArrayList<Pair<Long, String>>()
            while (it.hasNext()) {
                val cells = it.next().trimEnd('\r').split(",")
                val t = cells.getOrNull(ti)?.let(::parseTime) ?: continue
                times += t
                for ((k, i) in idx.withIndex()) values[k] += cells.getOrNull(i)?.toFloatOrNull() ?: Float.NaN
                val m = if (mi >= 0) cells.getOrNull(mi)?.trim().orEmpty() else ""
                if (m.isNotEmpty()) markers += t to m
            }
            require(times.isNotEmpty()) { "No data rows with a valid timestamp were found in this file." }
            return RideData(times.toLongArray(), keys.zip(values) { k, v -> k to v.toFloatArray() }.toMap(), markers)
        }
    }
}
