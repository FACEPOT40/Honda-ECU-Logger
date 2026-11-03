package com.pai.hondaecu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private val tiles = mutableMapOf<String, View>()
    private lateinit var grid: GridLayout
    private lateinit var valuesBtn: Button
    private lateinit var noTilesText: TextView
    private lateinit var modeBtn: Button
    private lateinit var tilesScroll: View
    private lateinit var graphPanel: View
    private lateinit var graphView: GraphView
    private lateinit var graphHint: TextView
    private val windowBtns = mutableMapOf<Int, Button>()
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
        if (graphPanel.visibility == View.VISIBLE) {
            graphHint.visibility = if (on) View.GONE else View.VISIBLE
            graphView.visibility = if (on) View.VISIBLE else View.GONE
            graphView.invalidate()
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
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(ecmText)
        }
        valuesBtn = button("Values") { chooseValues() }
        modeBtn = button("Graphs") { setGraphMode(graphPanel.visibility != View.VISIBLE) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeBtn)
            addView(valuesBtn)
        })

        for (f in Decode.FIELDS) tiles[f.key] = tile(f)
        grid = GridLayout(this).apply { columnCount = 2 }
        noTilesText = text(14f, GREY).apply {
            text = "All values are hidden. They are still recorded."
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(32), dp(12), dp(32))
        }
        root.addView(noTilesText)
        tilesScroll = ScrollView(this).apply { addView(grid) }
        root.addView(tilesScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildGraphPanel(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        showTiles()
        setGraphMode(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GRAPHS, false))

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

    private fun hiddenKeys(): Set<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_HIDDEN, null) ?: emptySet()

    /** Only changes which tiles are on screen: polling and the CSV never look at this. */
    private fun showTiles() {
        val hidden = hiddenKeys()
        val shown = Decode.FIELDS.filter { it.key !in hidden }
        grid.removeAllViews()
        for (f in shown) grid.addView(tiles.getValue(f.key), GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        graphView.fields = shown
        noTilesText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        valuesBtn.text = "Values ${shown.size}/${Decode.FIELDS.size}"
    }

    private fun buildGraphPanel(): View {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        graphView = GraphView(this).apply {
            colors = COLORS
            tileColor = TILE
            windowMs = prefs.getInt(KEY_WINDOW, 30) * 1000L
        }
        graphHint = text(14f, GREY).apply {
            text = "Connect to see live graphs."
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(32), dp(12), dp(32))
        }
        val windowRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        windowRow.addView(text(12f, GREY).apply { text = "Window" },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for (sec in intArrayOf(10, 30, 60)) {
            windowBtns[sec] = button("$sec s") { setWindow(sec) }.also { windowRow.addView(it) }
        }
        setWindow(prefs.getInt(KEY_WINDOW, 30))
        graphPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(windowRow)
            addView(graphHint)
            addView(ScrollView(this@MainActivity).apply { addView(graphView) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return graphPanel
    }

    /** Graphs only change the screen: polling and the CSV are the same in both modes. */
    private fun setGraphMode(on: Boolean) {
        graphPanel.visibility = if (on) View.VISIBLE else View.GONE
        tilesScroll.visibility = if (on) View.GONE else View.VISIBLE
        modeBtn.text = if (on) "Tiles" else "Graphs"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_GRAPHS, on).apply()
    }

    private fun setWindow(sec: Int) {
        graphView.windowMs = sec * 1000L
        for ((s, b) in windowBtns) b.alpha = if (s == sec) 1f else 0.45f
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_WINDOW, sec).apply()
        graphView.invalidate()
    }

    private fun chooseValues() {
        val fields = Decode.FIELDS
        val hidden = hiddenKeys()
        val checked = BooleanArray(fields.size) { fields[it].key !in hidden }
        val names = Array(fields.size) { fieldName(fields[it]) }
        fun save(keys: Set<String>) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN, keys).apply()
            showTiles()
        }
        AlertDialog.Builder(this)
            .setTitle("Values to show (all are still recorded)")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                save(fields.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet())
            }
            .setNeutralButton("Show all") { _, _ -> save(emptySet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fieldName(f: Decode.Field) =
        if (f.unit.isEmpty()) f.label else "${f.label} (${f.unit})"

    private fun tile(f: Decode.Field): View {
        val color = COLORS.getValue(f.key)
        val value = text(30f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
            text = "-"
        }
        values[f.key] = value
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(2).toFloat()
            }
        }
        val label = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
            addView(text(13f, color).apply { text = fieldName(f) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(TILE)
                setStroke(dp(1), (color and 0x00FFFFFF) or 0x66000000)
                cornerRadius = dp(10).toFloat()
            }
            addView(label)
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
        private const val PREFS = "screen"
        private const val KEY_HIDDEN = "hidden_values"
        private const val KEY_GRAPHS = "show_graphs"
        private const val KEY_WINDOW = "graph_window_s"

        // Same colors as COLORS in viewer.py, so a value looks the same on the phone and the PC
        private val COLORS = mapOf(
            "rpm" to "#4fc3f7", "tps_pct" to "#ffb74d", "ect_c" to "#ef5350",
            "batt_v" to "#9ccc65", "speed_kmh" to "#ba68c8", "inj_ms" to "#4db6ac",
            "ign_deg" to "#fff176", "o2_v" to "#f06292", "stft" to "#e0e0e0",
            "mode_d14" to "#90a4ae", "neutral" to "#a1887f",
        ).mapValues { Color.parseColor(it.value) }
        private val BACKGROUND = Color.parseColor("#101214")
        private val TILE = Color.parseColor("#1E2226")
        private val GREY = Color.parseColor("#A0A8B0")
        private val GREEN = Color.parseColor("#43A047")
        private val ORANGE = Color.parseColor("#FB8C00")
        private val RED = Color.parseColor("#EF5350")
    }
}
