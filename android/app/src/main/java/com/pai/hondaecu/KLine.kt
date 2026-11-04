package com.pai.hondaecu

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Real K-line link over the FTDI cable: port of the KLine class in kline.py. */
class KLine(context: Context) : Link {
    private val port: UsbSerialPort
    private var echo: Boolean? = null
    private var pending = ByteArray(0)
    private val readBuf = ByteArray(1024)

    init {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
            ?: throw IOException("Cable not found")
        val connection = usb.openDevice(driver.device)
            ?: throw IOException("No USB permission for the cable")
        port = driver.ports[0]
        port.open(connection)
        port.setParameters(Protocol.BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // default 16 ms would slow every reply down
        (port as? FtdiSerialDriver.FtdiSerialPort)?.setLatencyTimer(1)
    }

    override fun close() {
        try {
            port.close()
        } catch (_: IOException) {
        }
    }

    /** Like reset_input_buffer() on the PC: drop unread bytes, e.g. the 00 the wake-up break
     *  leaves behind. (The arguments are purgeWriteBuffers, purgeReadBuffers: the READ buffer
     *  is the one that matters; clearing only the write buffer left the 00 there, so the echo
     *  check saw "00 FE 04 72", decided "no echo" and every reply was misread.) */
    private fun purgeInput() {
        pending = ByteArray(0)
        try {
            port.purgeHwBuffers(false, true)
        } catch (_: UnsupportedOperationException) {
        }
        // anything the chip had already passed on: read it away, briefly
        val end = System.currentTimeMillis() + 20
        while (System.currentTimeMillis() < end && port.read(readBuf, 5) > 0) {
        }
    }

    override fun wake() {
        purgeInput()
        port.setBreak(true)
        Thread.sleep(70)
        port.setBreak(false)
        Thread.sleep(120)
    }

    private fun readExact(n: Int, limitMs: Long = 600): ByteArray {
        val deadline = System.currentTimeMillis() + limitMs
        while (pending.size < n && System.currentTimeMillis() < deadline) {
            val got = port.read(readBuf, 100)
            if (got > 0) pending += readBuf.copyOf(got)
        }
        val take = minOf(n, pending.size)
        val out = pending.copyOf(take)
        pending = pending.copyOfRange(take, pending.size)
        return out
    }

    override fun send(msg: ByteArray, timeoutMs: Long): ByteArray? {
        Protocol.requireAllowed(msg)
        purgeInput()
        port.write(msg, 500)
        val first = readExact(msg.size, timeoutMs)   // K-line is single-wire: our bytes usually echo back
        // decide "echo or not" only from a clean answer: no answer at all says nothing yet
        val hasEcho = echo ?: first.contentEquals(msg).also { if (first.isNotEmpty()) echo = it }
        return readReply(if (hasEcho) ByteArray(0) else first, timeoutMs)
    }

    private fun readReply(pre: ByteArray, timeoutMs: Long): ByteArray? {
        var hdr = pre.copyOf(minOf(2, pre.size))
        if (hdr.size < 2) hdr += readExact(2 - hdr.size, timeoutMs)
        if (hdr.size < 2) return null
        val length = hdr[1].toInt() and 0xFF
        val out = ByteArrayOutputStream()
        out.write(pre.copyOfRange(hdr.size.coerceAtMost(pre.size), pre.size))
        if (length > 2 && out.size() < length - 2) out.write(readExact(length - 2 - out.size(), timeoutMs))
        val reply = hdr + out.toByteArray()
        return if (reply.sumOf { it.toInt() and 0xFF } and 0xFF == 0) reply else null
    }
}
