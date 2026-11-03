package com.pai.hondaecu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live state the screen reads. Everything is replaced whole, so reads need no lock. */
object Live {
    @Volatile var connected = false
    @Volatile var status = "Disconnected"
    @Volatile var ecm = "-"
    @Volatile var sample: Map<String, Any?> = emptyMap()
    @Volatile var recording = false
    @Volatile var recordingFile = ""
    @Volatile var recordingStart = 0L
    @Volatile var recordError = ""
    val history = History()                     // for the live graphs only
    @Volatile var faults: Protocol.Faults? = null   // fault codes read after connecting / on request
}

/**
 * Keeps polling and recording with the screen off. A foreground service shows a notification
 * while it runs; the partial wake lock stops the CPU from sleeping during a ride.
 */
class LoggerService : Service() {
    @Volatile private var poller: Poller? = null
    private val gps by lazy { GpsSpeed(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var csv: CsvFormat? = null
    private var markerN = 0
    private var lastFlush = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent.getBooleanExtra(EXTRA_SIM, false))
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_RECORD -> startRecording()
            ACTION_STOP_RECORD -> stopRecording()
            ACTION_MARK -> mark()
            ACTION_READ_FAULTS -> {
                Live.faults = null                  // "reading…" until the poller answers
                poller?.requestFaults()
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(sim: Boolean) {
        // the location type lets GPS keep working with the screen off; only if it was allowed
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (gps.hasPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        startForeground(NOTIFICATION_ID, notification("Connecting…"), type)
        if (poller != null) return
        gps.start()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HondaECU:logging").apply { acquire() }
        // A stopped poller's thread can still report once more: ignore it, or its last
        // update brings back the notification and the tile values after Disconnect.
        lateinit var p: Poller
        p = Poller(
            openLink = { if (sim) SimEcu() else KLine(this) },
            simulate = sim,
            onStatus = {
                synchronized(lock) {
                    if (poller === p) {
                        Live.status = it
                        updateNotification()
                    }
                }
            },
            onEcm = { if (poller === p) Live.ecm = it },
            onSample = { if (poller === p) onSample(it) },
            onFaults = { if (poller === p) Live.faults = it },
        )
        poller = p
        Live.history.clear()
        Live.connected = true
        Thread(p::run, "kline-poller").start()
    }

    private fun onSample(ecu: Map<String, Any?>) {
        val s = ecu + ("gps_kmh" to gps.current())     // GPS updates once a second: repeat the last speed
        Live.sample = s
        Live.history.add(s)
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(csv!!.row(s))
                w.newLine()
                val now = System.currentTimeMillis()
                if (now - lastFlush > 1000) {     // a crash or dead battery loses at most ~1 s
                    w.flush()
                    lastFlush = now
                }
            } catch (e: Exception) {
                Live.recordError = "Recording failed: ${e.message}"
                closeWriter()
            }
        }
    }

    private fun startRecording() {
        synchronized(lock) {
            if (writer != null) return
            try {
                val name = "ride_%s.csv".format(
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/HondaECULogger")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: error("could not create the file")
                val out = contentResolver.openOutputStream(uri) ?: error("could not open the file")
                writer = out.bufferedWriter().also {
                    it.write(CsvFormat.HEADER)
                    it.newLine()
                    it.flush()
                }
                csv = CsvFormat()
                markerN = 0
                Live.recordError = ""
                Live.recordingFile = "Documents/HondaECULogger/$name"
                Live.recordingStart = System.currentTimeMillis()
                Live.recording = true
            } catch (e: Exception) {
                Live.recordError = "Could not start recording: ${e.message}"
            }
        }
        updateNotification()
    }

    private fun stopRecording() {
        synchronized(lock) { closeWriter() }
        updateNotification()
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        Live.recording = false
    }

    private fun mark() {
        synchronized(lock) {
            if (writer != null) {
                val label = "M${++markerN}"
                csv?.mark(label)
                Live.history.mark(System.currentTimeMillis(), label)
            }
        }
    }

    private fun disconnect() {
        stopRecording()
        poller?.stop()
        synchronized(lock) { poller = null }
        gps.stop()
        Live.connected = false
        Live.status = "Disconnected"
        Live.ecm = "-"
        Live.faults = null
        Live.sample = emptyMap()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        poller?.stop()
        synchronized(lock) { poller = null }
        gps.stop()
        stopRecording()
        Live.connected = false
        Live.status = "Disconnected"
        Live.ecm = "-"
        Live.faults = null
        Live.sample = emptyMap()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun updateNotification() {
        if (poller == null) return      // disconnected: the notification must stay gone
        val text = (if (Live.recording) "● Recording · " else "") + Live.status
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ECU logging", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("H-ECU Logger")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_RECORD = "record"
        const val ACTION_STOP_RECORD = "stop_record"
        const val ACTION_MARK = "mark"
        const val ACTION_READ_FAULTS = "read_faults"
        const val EXTRA_SIM = "sim"
        private const val CHANNEL = "logging"
        private const val NOTIFICATION_ID = 1
    }
}
