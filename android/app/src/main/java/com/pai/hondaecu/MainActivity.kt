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
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Main screen, made for a phone on the handlebar (portrait):
 *  - top bar: status chip (tap: connection details), faults chip (tap: the codes), ⋮ menu
 *    (Filter, Simulator, About)
 *  - tabs: Live (tiles) | Graphs | Logs (recorded rides)
 *  - tiles fill the space, so fewer values = bigger numbers
 *  - two big buttons: Connect / Disconnect + Start Record; while recording Marker + Stop (hold)
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val values = mutableMapOf<String, TextView>()
    private val labels = mutableMapOf<String, TextView>()
    private val tiles = mutableMapOf<String, View>()
    private lateinit var tilesPanel: LinearLayout
    private lateinit var noTilesText: TextView
    private lateinit var graphPanel: View
    private lateinit var graphView: GraphView
    private lateinit var graphHint: TextView
    private val windowBtns = mutableMapOf<Int, Button>()
    private var windowMs = 30_000L
    private val tabs = mutableMapOf<Tab, TextView>()
    private lateinit var statusChip: TextView
    private lateinit var faultsChip: TextView
    private lateinit var recText: TextView
    private lateinit var leftBtn: Button
    private lateinit var rightBtn: Button
    private var buttonsState: String? = null     // buttons are re-styled only when this changes
    private var keptOn = false
    private var simulate = false

    private enum class Tab { LIVE, GRAPHS, LOGS }

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
        simulate = prefs().getBoolean(KEY_SIM, false)
        setContentView(buildScreen())
        // notifications show that logging runs; location is only for GPS speed (refusing it is fine)
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
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
        showTiles()                 // the Filter may have changed in the ride viewer
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // ------------------------------------------------------------ actions

    private fun onConnectClicked() {
        if (Live.connected) {
            send(LoggerService.ACTION_DISCONNECT)
            return
        }
        if (simulate) {
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

    private fun onLeftClicked() {
        if (!Live.recording) {
            onConnectClicked()
            return
        }
        send(LoggerService.ACTION_MARK)
        buzz(60)
        toast("Marker M${Live.markers + 1}", short = true)
    }

    private fun onRightClicked() {
        if (Live.recording) toast("Hold to stop recording", short = true)
        else send(LoggerService.ACTION_RECORD)
    }

    /** Stop needs a long press, so a bump on the road cannot end a recording. */
    private fun onRightHeld(): Boolean {
        if (!Live.recording) return false
        send(LoggerService.ACTION_STOP_RECORD)
        buzz(150)
        return true
    }

    private fun startService(sim: Boolean) {
        startForegroundService(Intent(this, LoggerService::class.java)
            .setAction(LoggerService.ACTION_CONNECT).putExtra(LoggerService.EXTRA_SIM, sim))
    }

    private fun send(action: String) {
        startService(Intent(this, LoggerService::class.java).setAction(action))
    }

    private fun toast(s: String, short: Boolean = false) =
        Toast.makeText(this, s, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()

    /** A short vibration, so a marker or a stop can be felt without looking. */
    private fun buzz(ms: Long) {
        @Suppress("DEPRECATION")
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Filter (${ValueFilter.shown(this@MainActivity).size}/${Decode.FIELDS.size} shown)")
            menu.add(0, 2, 1, "Simulator").apply {
                isCheckable = true
                isChecked = simulate
                isEnabled = !Live.connected       // pick it before connecting
            }
            menu.add(0, 3, 2, "About")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> ValueFilter.choose(this@MainActivity) { showTiles() }
                    2 -> {
                        simulate = !simulate
                        prefs().edit().putBoolean(KEY_SIM, simulate).apply()
                        toast(if (simulate) "Simulator on: Connect uses fake data" else "Simulator off", short = true)
                    }
                    3 -> startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun showConnection() {
        val source = if (simulate) "Simulator (fake data)" else "K-line cable (USB OTG)"
        AlertDialog.Builder(this).setTitle("Connection")
            .setMessage("Status: ${Live.status}\nECM ID: ${Live.ecm}\nSource: $source\n\n" +
                "Read only: the app only reads live data and fault codes.")
            .setPositiveButton("Close", null)
            .show()
    }

    /** Fault codes read from the ECU (read only: the app never clears them). */
    private fun showFaults() {
        val f = Live.faults
        val text = when {
            !Live.connected -> "Connect first: the app reads the fault codes each time it connects."
            f == null -> "Reading the fault codes…"
            !f.supported -> "The ECU did not answer the fault-code queries."
            else -> listOf("Current faults" to f.current, "Past (stored) faults" to f.past).joinToString("\n\n") { (title, codes) ->
                title + "\n" + (if (codes.isEmpty()) "none" else codes.joinToString("\n") { (m, s) -> Decode.dtcText(m, s) })
            } + "\n\nThe number before the dash is the MIL blink count. Names are the usual Honda " +
                "PGM-FI groups: check the Wave 125i service manual. This app only reads codes; it never clears them."
        }
        val dialog = AlertDialog.Builder(this).setTitle("Fault codes (DTC)").setMessage(text)
            .setPositiveButton("Close", null)
        if (Live.connected) dialog.setNeutralButton("Read again") { _, _ -> send(LoggerService.ACTION_READ_FAULTS) }
        dialog.show()
    }

    // ------------------------------------------------------------ screen updates (10 per second)

    private fun render() {
        val on = Live.connected
        val rec = Live.recording

        val (label, color) = when {
            !on -> "Not connected" to GREY
            Live.status.startsWith("Connected") -> (if (simulate) "Sim" else "ECU") to GREEN
            Live.status.startsWith("Error") || Live.status.startsWith("Disconnected") -> "Error" to RED
            Live.status.startsWith("No reply") -> "No reply" to RED       // tap the chip for the full message
            Live.status.startsWith("Waking") -> "Waking ECU…" to ORANGE
            else -> "Connecting…" to ORANGE
        }
        statusChip.text = SpannableString("● $label").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val f = Live.faults
        faultsChip.visibility = if (on) View.VISIBLE else View.GONE
        val n = (f?.current?.size ?: 0) + (f?.past?.size ?: 0)
        val (ftext, fcolor) = when {
            f == null -> "Faults…" to GREY
            !f.supported -> "Faults ?" to GREY
            f.current.isNotEmpty() -> "$n fault${if (n > 1) "s" else ""}" to RED
            f.past.isNotEmpty() -> "$n past" to ORANGE
            else -> "No faults" to GREEN
        }
        faultsChip.text = ftext
        faultsChip.setTextColor(fcolor)
        (faultsChip.background as GradientDrawable).setStroke(dp(1), fcolor)

        tabs.getValue(Tab.LOGS).alpha = if (rec) 0.35f else 1f

        recText.setTextColor(if (Live.recordError.isEmpty() && rec) RED else GREY)
        recText.textSize = if (rec) 22f else 12f
        recText.text = when {
            Live.recordError.isNotEmpty() -> Live.recordError
            rec -> {
                val el = (System.currentTimeMillis() - Live.recordingStart) / 1000
                val m = Live.markers
                "● REC %02d:%02d  ·  %d marker%s".format(el / 60, el % 60, m, if (m == 1) "" else "s")
            }
            Live.recordingFile.isNotEmpty() -> "Saved " + Live.recordingFile
            else -> ""
        }
        recText.visibility = if (recText.text.isEmpty()) View.GONE else View.VISIBLE

        val state = when {
            rec -> "rec"
            on -> "on"
            else -> "off"
        }
        if (state != buttonsState) {
            buttonsState = state
            when (state) {
                "rec" -> {
                    leftBtn.text = "⚑ Marker"
                    leftBtn.tone(Tone.RECORD)
                    rightBtn.text = "■ Stop (hold)"
                    rightBtn.tone(Tone.STOP)
                }
                else -> {
                    leftBtn.text = if (on) "Disconnect" else "Connect"
                    leftBtn.tone(if (on) Tone.PLAIN else Tone.PRIMARY)
                    rightBtn.text = "● Start Record"
                    rightBtn.tone(Tone.RECORD)
                }
            }
            rightBtn.isEnabled = on
            // the screen stays on while recording, so the mounted phone keeps showing the values
            if (rec != keptOn) {
                if (rec) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                keptOn = rec
            }
        }

        if (graphPanel.visibility == View.VISIBLE) {
            graphHint.visibility = if (on) View.GONE else View.VISIBLE
            graphView.visibility = if (on) View.VISIBLE else View.GONE
            val now = System.currentTimeMillis()
            graphView.fromMs = now - windowMs
            graphView.toMs = now
            graphView.invalidate()
        }
        val s = if (on) Live.sample else emptyMap()     // never show old numbers as if live
        val bad = Decode.alerts(s)
        for (fld in Decode.FIELDS) {
            val t = values.getValue(fld.key)
            t.text = Decode.fmtValue(fld, s[fld.key])
            t.setTextColor(if (fld.key in bad) RED else Color.WHITE)
        }
    }

    // ------------------------------------------------------------ layout

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        // top bar
        statusChip = chip().apply { setOnClickListener { showConnection() } }
        faultsChip = chip().apply { setOnClickListener { showFaults() } }
        val menuBtn = text(24f, GREY).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            setOnClickListener { showMenu(this) }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(20f, TEXT).apply {
                text = "H-ECU"
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(10) })
            addView(statusChip)
            addView(faultsChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(menuBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        })

        // tabs
        val tabRow = LinearLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(TILE)
                cornerRadius = dp(10).toFloat()
            }
        }
        for ((tab, name) in listOf(Tab.LIVE to "Live", Tab.GRAPHS to "Graphs", Tab.LOGS to "Logs")) {
            tabs[tab] = text(16f, GREY).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { onTab(tab) }
            }.also { tabRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        root.addView(tabRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(8)) })

        // tiles or graphs
        for (f in Decode.FIELDS) tiles[f.key] = tile(f)
        tilesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeValues() }
        }
        noTilesText = text(14f, GREY).apply {
            text = "All values are filtered out. They are still recorded: ⋮ → Filter."
            gravity = Gravity.CENTER
        }
        val content = FrameLayout(this).apply {
            addView(tilesPanel)
            addView(noTilesText)
            addView(buildGraphPanel())
        }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // recording line and the two big buttons
        recText = text(12f, GREY).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(recText)
        leftBtn = uiButton("Connect", Tone.PRIMARY) { onLeftClicked() }
        rightBtn = uiButton("● Start Record", Tone.RECORD) { onRightClicked() }.apply {
            setOnLongClickListener { onRightHeld() }
        }
        root.addView(LinearLayout(this).apply {
            for (b in listOf(leftBtn, rightBtn)) {
                b.textSize = 18f
                b.setTypeface(b.typeface, Typeface.BOLD)
                addView(b, LinearLayout.LayoutParams(0, dp(72), 1f))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })

        showTiles()
        onTab(if (prefs().getBoolean(KEY_GRAPHS, false)) Tab.GRAPHS else Tab.LIVE)

        // Android 15+ draws under the system bars: keep the content clear of them
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(12), b.top + dp(4), dp(12), b.bottom + dp(8))
            insets
        }
        return root
    }

    private fun onTab(tab: Tab) {
        if (tab == Tab.LOGS) {
            if (Live.recording) toast("Stop recording first, then open the logs.", short = true)
            else startActivity(Intent(this, RidesActivity::class.java))
            return
        }
        val graphs = tab == Tab.GRAPHS
        graphPanel.visibility = if (graphs) View.VISIBLE else View.GONE
        tilesPanel.visibility = if (graphs) View.GONE else View.VISIBLE
        noTilesText.visibility = if (!graphs && ValueFilter.shown(this).isEmpty()) View.VISIBLE else View.GONE
        for ((t, v) in tabs) {
            val chosen = t == tab
            v.setTextColor(if (chosen) Color.WHITE else GREY)
            v.background = if (chosen) GradientDrawable().apply {
                setColor(BLUE)
                cornerRadius = dp(8).toFloat()
            } else null
        }
        prefs().edit().putBoolean(KEY_GRAPHS, graphs).apply()
    }

    /** Only changes which tiles are on screen: polling and the CSV never look at this.
     *  The shown tiles share the space, so fewer values get bigger numbers (1 column up to 3). */
    private fun showTiles() {
        val shown = ValueFilter.shown(this)
        tilesPanel.removeAllViews()
        for (t in tiles.values) (t.parent as? LinearLayout)?.removeView(t)
        val cols = if (shown.size <= 3) 1 else 2
        for (row in shown.chunked(cols)) {
            val line = LinearLayout(this)
            for (f in row) line.addView(tiles.getValue(f.key), LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            if (row.size < cols) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            tilesPanel.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val big = shown.size <= 4
        for (l in labels.values) l.textSize = if (big) 18f else 13f
        graphView.fields = shown
        if (tilesPanel.visibility == View.VISIBLE) noTilesText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        tilesPanel.post { sizeValues() }
    }

    /** One number size for every tile, as big as the tiles allow ("88,888" must fit). */
    private fun sizeValues() {
        val tile = ValueFilter.shown(this).firstOrNull()?.let { tiles[it.key] } ?: return
        val w = tile.width - dp(24)
        val h = tile.height - dp(16) - (labels.values.first().height)
        if (w <= 0 || h <= 0) return
        val paint = Paint().apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 100f
        }
        val byWidth = 100f * w / paint.measureText("88,888")
        val px = minOf(byWidth, h * 0.85f, dp(120).toFloat())
        for (v in values.values) {
            if (v.textSize != px) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }
    }

    private fun buildGraphPanel(): View {
        graphView = GraphView(this).apply {
            colors = COLORS
            tileColor = TILE
            source = Live.history
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
            windowBtns[sec] = uiButton("$sec s") { setWindow(sec) }.also { windowRow.addView(it) }
        }
        setWindow(prefs().getInt(KEY_WINDOW, 30))
        graphPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(windowRow)
            addView(graphHint)
            addView(ScrollView(this@MainActivity).apply { addView(graphView) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return graphPanel
    }

    private fun setWindow(sec: Int) {
        windowMs = sec * 1000L
        for ((s, b) in windowBtns) b.tone(if (s == sec) Tone.PRIMARY else Tone.PLAIN)
        prefs().edit().putInt(KEY_WINDOW, sec).apply()
        graphView.invalidate()
    }

    private fun tile(f: Decode.Field): View {
        val color = COLORS.getValue(f.key)
        val value = text(30f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            text = "-"
        }
        values[f.key] = value
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(2).toFloat()
            }
        }
        val name = text(13f, color).apply {
            text = f.title
            maxLines = 1
        }
        labels[f.key] = name
        val label = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
            addView(name)
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
            addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun chip() = text(13f, TEXT).apply {
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(TILE)
            setStroke(dp(1), Look.LINE)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pai.hondaecu.USB_PERMISSION"
        private const val PREFS = ValueFilter.PREFS
        private const val KEY_GRAPHS = "show_graphs"
        private const val KEY_WINDOW = "graph_window_s"
        private const val KEY_SIM = "simulator"
        private val COLORS = Look.COLORS
        private val BACKGROUND = Look.BACKGROUND
        private val TILE = Look.TILE
        private val GREY = Look.GREY
        private val TEXT = Look.TEXT
        private val BLUE = Look.BLUE
        private val GREEN = Look.GREEN
        private val ORANGE = Look.ORANGE
        private val RED = Look.RED
    }
}
