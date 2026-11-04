package com.pai.hondaecu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PollerTest {
    /** The simulator, but silent while [silent] is set: like a loose plug on a bump. */
    private class FlakyLink(val silent: () -> Boolean, val dtcQueries: AtomicInteger) : Link {
        private val sim = SimEcu()
        override fun wake() {}
        override fun close() {}
        override fun send(msg: ByteArray, timeoutMs: Long): ByteArray? {
            if (silent()) {
                Thread.sleep(timeoutMs)              // no reply: the query times out
                return null
            }
            val q = msg[2].toInt() and 0xFF
            if (q == Protocol.DTC_CURRENT || q == Protocol.DTC_PAST) dtcQueries.incrementAndGet()
            return sim.send(msg, timeoutMs)
        }
    }

    @Test
    fun dropoutReconnectsQuicklyAndReadsFaultsOnlyOnce() {
        val dtc = AtomicInteger()
        val samples = mutableListOf<Long>()
        val silentUntil = java.util.concurrent.atomic.AtomicLong(0)
        val p = Poller(
            openLink = { FlakyLink({ System.currentTimeMillis() < silentUntil.get() }, dtc) },
            simulate = true, onStatus = {}, onEcm = {},
            onSample = { synchronized(samples) { samples += System.currentTimeMillis() } },
        )
        val th = Thread(p::run).apply { start() }
        Thread.sleep(1500)
        val faultQueriesFirst = dtc.get()
        silentUntil.set(System.currentTimeMillis() + 1500)       // contact lost long enough to need a reconnect
        Thread.sleep(4000)
        p.stop()
        th.join(3000)

        assertTrue("faults were read on connect", faultQueriesFirst > 0)
        assertEquals("faults are not read again after a dropout", faultQueriesFirst, dtc.get())
        val gaps = synchronized(samples) { samples.zipWithNext { a, b -> b - a } }
        val worst = gaps.maxOrNull() ?: Long.MAX_VALUE
        println("longest gap after a 1.5 s dropout: $worst ms")
        assertTrue("logging resumed, longest gap $worst ms", worst < 3000)
    }
}
