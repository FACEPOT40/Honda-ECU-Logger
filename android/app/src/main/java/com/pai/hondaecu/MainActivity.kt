package com.pai.hondaecu

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialProber

/** One screen: a tile per value plus Connect, Record and Marker. Graphs stay on the laptop. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val values = mutableMapOf<String, TextView>()
    private lateinit var statusText: TextView
    private lateinit var ecmText: TextView
    private lateinit var recText: TextView
    private lateinit var simBox: CheckBox
    private lateinit var connectBtn: Button
    private lateinit var recordBtn: Button
    private lateinit var markBtn: Button

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startService(false)
            else toast("USB permission was refused")
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun onConnectClicked() {
        if (Live.connected) {
            send(LoggerService.ACTION_DISCONNECT)
            return
        }
        if (simBox.isChecked) {
            startService(true)
            return
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val device: UsbDevice? = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()?.device
        when {
            device == null -> toast("Cable not found. Check the USB adapter and the cable.")
            usb.hasPermission(device) -> startService(false)
            else -> usb.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun startService(sim: Boolean) {
        startForegroundService(Intent(this, LoggerService::class.java)
            .setAction(LoggerService.ACTION_CONNECT).putExtra(LoggerService.EXTRA_SIM, sim))
    }

    private fun send(action: String) {
        startService(Intent(this, LoggerService::class.java).setAction(action))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun render() {
        val on = Live.connected
        statusText.text = "● " + Live.status
        statusText.setTextColor(when {
            Live.status.startsWith("Connected") -> GREEN
            Live.status.startsWith("Error") || Live.status.startsWith("Disconnected") -> RED
            else -> ORANGE
        })
        ecmText.text = "ECM: " + Live.ecm
        connectBtn.text = if (on) "Disconnect" else "Connect"
        simBox.isEnabled = !on
        recordBtn.isEnabled = on
        recordBtn.text = if (Live.recording) "■ Stop" else "● Start Record"
        markBtn.isEnabled = Live.recording
        recText.setTextColor(if (Live.recordError.isEmpty() && Live.recording) RED else GREY)
        recText.text = when {
            Live.recordError.isNotEmpty() -> Live.recordError
            Live.recording -> {
                val el = (System.currentTimeMillis() - Live.recordingStart) / 1000
                "● REC %02d:%02d  %s".format(el / 60, el % 60, Live.recordingFile)
            }
            Live.recordingFile.isNotEmpty() -> "Saved " + Live.recordingFile
            else -> ""
        }
        val s = if (on) Live.sample else emptyMap()     // never show old numbers as if live
        val bad = Decode.alerts(s)
        for (f in Decode.FIELDS) {
            val t = values.getValue(f.key)
            t.text = Decode.fmtValue(f, s[f.key])
            t.setTextColor(if (f.key in bad) RED else Color.WHITE)
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusText = text(14f, ORANGE)
        ecmText = text(12f, GREY)
        root.addView(statusText)
        root.addView(ecmText)

        val grid = GridLayout(this).apply { columnCount = 2 }
        for (f in Decode.FIELDS) grid.addView(tile(f), GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        root.addView(ScrollView(this).apply { addView(grid) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        recText = text(12f, GREY)
        root.addView(recText)
        simBox = CheckBox(this).apply {
            text = "Simulator"
            setTextColor(GREY)
        }
        connectBtn = button("Connect") { onConnectClicked() }
        root.addView(row(simBox, connectBtn))
        recordBtn = button("● Start Record") {
            send(if (Live.recording) LoggerService.ACTION_STOP_RECORD else LoggerService.ACTION_RECORD)
        }
        markBtn = button("⚑ Marker") {
            send(LoggerService.ACTION_MARK)
            toast("Marker added")
        }
        root.addView(row(recordBtn, markBtn))

        // Android 15+ draws under the system bars: keep the content clear of them
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(12), b.top + dp(8), dp(12), b.bottom + dp(8))
            insets
        }
        return root
    }

    private fun tile(f: Decode.Field): View {
        val value = text(30f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
            text = "-"
        }
        values[f.key] = value
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(TILE)
                cornerRadius = dp(10).toFloat()
            }
            addView(text(13f, GREY).apply {
                text = if (f.unit.isEmpty()) f.label else "${f.label} (${f.unit})"
            })
            addView(value)
        }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pai.hondaecu.USB_PERMISSION"
        private val BACKGROUND = Color.parseColor("#101214")
        private val TILE = Color.parseColor("#1E2226")
        private val GREY = Color.parseColor("#A0A8B0")
        private val GREEN = Color.parseColor("#43A047")
        private val ORANGE = Color.parseColor("#FB8C00")
        private val RED = Color.parseColor("#EF5350")
    }
}
