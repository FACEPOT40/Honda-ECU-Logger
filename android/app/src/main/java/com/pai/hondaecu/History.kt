package com.pai.hondaecu

/**
 * The last few minutes of samples, for the live graphs. The poller thread adds to it and the
 * screen reads it, so every method is synchronized. Recording does not use it.
 */
class History(private val capacity: Int = 3000) : GraphSource {
    private val keys = Decode.FIELDS.map { it.key }
    private val times = LongArray(capacity)
    private val data = Array(keys.size) { FloatArray(capacity) }
    private var start = 0
    private var size = 0
    private val marks = ArrayDeque<Pair<Long, String>>()

    class Series(val t: LongArray, val v: FloatArray)

    @Synchronized fun clear() {
        start = 0
        size = 0
        marks.clear()
    }

    /** Adds one sample; once full, the oldest one is overwritten. Missing values become NaN. */
    @Synchronized fun add(s: Map<String, Any?>) {
        val t = (s["t"] as? Number)?.toLong() ?: return
        val i = (start + size) % capacity
        if (size == capacity) start = (start + 1) % capacity else size++
        times[i] = t
        for ((k, key) in keys.withIndex()) data[k][i] = (s[key] as? Number)?.toFloat() ?: Float.NaN
        while (marks.isNotEmpty() && marks.first().first < times[start]) marks.removeFirst()
    }

    @Synchronized fun mark(t: Long, label: String) {
        marks.addLast(t to label)
    }

    /** Samples of one value from [from] to [to] (both included), oldest first. */
    @Synchronized override fun series(key: String, from: Long, to: Long): Series {
        val k = keys.indexOf(key)
        var first = 0
        while (first < size && times[(start + first) % capacity] < from) first++
        var end = size
        while (end > first && times[(start + end - 1) % capacity] > to) end--
        val n = if (k < 0) 0 else end - first
        val t = LongArray(n)
        val v = FloatArray(n)
        for (j in 0 until n) {
            val i = (start + first + j) % capacity
            t[j] = times[i]
            v[j] = data[k][i]
        }
        return Series(t, v)
    }

    @Synchronized override fun marks(from: Long, to: Long): List<Pair<Long, String>> =
        marks.filter { it.first in from..to }
}
