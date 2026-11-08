package com.pai.hondaecu

/**
 * Wake -> init -> ECM ID -> poll tables forever: port of PollWorker in worker.py.
 * Unlike the PC app it also keeps retrying after an error, because on a ride a shaken
 * USB plug must not end the recording. Reconnecting is kept short (a bumpy road on a loose
 * Type-C plug lost ~2.8 s per bump on 4 Nov): fault codes are read once per Connect, not on
 * every reconnect, and short reply timeouts notice a lost link quickly.
 */
class Poller(
    private val openLink: () -> Link,
    private val simulate: Boolean,
    private val onStatus: (String) -> Unit,
    private val onEcm: (String) -> Unit,
    private val onSample: (Map<String, Any?>) -> Unit,
    private val onFaults: (Protocol.Faults) -> Unit = {},
) {
    @Volatile private var running = true
    @Volatile private var wantFaults = false
    private var faultsRead = false          // fault codes are read once per Connect (or on request)

    /** Read the fault codes again between two polling cycles (read only). */
    fun requestFaults() {
        wantFaults = true
    }

    fun stop() {
        running = false
    }

    private fun connect(): Link? {
        val link = openLink()
        for (attempt in 1..5) {
            if (!running) break
            onStatus("Waking ECU (try $attempt/5)…")
            link.wake()
            link.send(Protocol.WAKE_MSG, WAKE_REPLY_MS)     // the ECU may answer it or not: don't wait long
            if (link.send(Protocol.INIT_MSG)?.contentEquals(Protocol.INIT_OK) == true) return link
            Thread.sleep(500)
        }
        link.close()
        return null
    }

    fun run() {
        while (running) {
            var link: Link? = null
            try {
                link = connect()
                if (link == null) {
                    if (running) onStatus("No reply from ECU. Key ON? Cable in DLC? Retrying…")
                    Thread.sleep(2000)
                    continue
                }
                pollUntilLost(link)
                if (running) onStatus("Connection lost, reconnecting…")
            } catch (e: Exception) {          // e.g. cable unplugged, or a loose plug on a bump
                if (running) {
                    onStatus("Error: ${e.message}. Retrying…")
                    Thread.sleep(RETRY_MS)      // try again soon: the plug is often back within a second
                }
            } finally {
                link?.close()
            }
        }
        onStatus("Disconnected")
    }

    private fun pollUntilLost(link: Link) {
        link.send(Protocol.queryMsg(0x00), REPLY_MS)?.let { r ->
            // the Wave sends 5 ID bytes + 5 zero bytes; the ID is the first 5
            val id = Protocol.payload(r, 0x00).take(5).joinToString(" ") { "%02X".format(it) }
            onEcm("$id  (${ECU_NAMES[id] ?: "unknown"})")
        }
        val connected = "Connected" + if (simulate) " (SIMULATOR)" else ""
        if (!faultsRead || wantFaults) {           // about 22 short queries: not again after a dropout
            wantFaults = false
            onFaults(Protocol.readFaults(link))
            faultsRead = true
        }
        onStatus(connected)

        val tables = Decode.DECODERS.keys.toMutableList()
        val last = mutableMapOf<Int, Map<String, Any?>>()   // slow tables, repeated in every sample
        var cycle = 0
        var misses = 0
        while (running && misses < MAX_MISSES) {
            if (wantFaults) {
                wantFaults = false
                onFaults(Protocol.readFaults(link))
            }
            val s = mutableMapOf<String, Any?>("t" to System.currentTimeMillis())
            var got = false
            for (t in tables.toList()) {
                if (cycle % (EVERY[t] ?: 1) != 0) continue
                val r = link.send(Protocol.queryMsg(t), REPLY_MS) ?: continue
                got = true
                val d = Protocol.payload(r, t)
                if (d.isEmpty()) {            // "02 05 71 tt cs": the ECU has no such table
                    tables.remove(t)
                    onStatus("$connected, table 0x%02X not supported".format(t))
                    continue
                }
                val vals = Decode.DECODERS.getValue(t)(d) + ("raw_%02x".format(t) to Protocol.toHex(d))
                if (t in EVERY) last[t] = vals else s += vals
            }
            last.values.forEach { s += it }
            cycle++
            if (got) {
                misses = 0
                onSample(Decode.addDerived(s))
            } else {
                misses++
            }
        }
    }

    companion object {
        /** A table reply normally arrives in ~30 ms; after this the query counts as missed. */
        const val REPLY_MS = 300L
        /** The wake message may or may not get an answer; this is long enough to catch one. */
        const val WAKE_REPLY_MS = 150L
        /** Cycles in a row without any reply before waking the ECU again. */
        const val MAX_MISSES = 3
        /** Pause before retrying after a USB error. */
        const val RETRY_MS = 300L
        /** Tables that change slowly are asked only every Nth cycle. */
        val EVERY = mapOf(0xD1 to 5)
        val ECU_NAMES = mapOf(
            "01 02 58 0D 01" to "38770-K73-T32",
            "01 03 3E 0D 01" to "38770-K73-T61",
            "01 03 4F 18 01" to "38770-K73-TC2",
        )
    }
}
