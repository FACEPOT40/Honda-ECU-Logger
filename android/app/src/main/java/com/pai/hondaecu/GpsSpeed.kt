package com.pai.hondaecu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * Speed from the phone's GPS, about once a second (the Wave's ECU has no speed sensor).
 * Only the speed is kept: the position is never stored or recorded.
 */
class GpsSpeed(private val context: Context) : LocationListener {
    @Volatile private var kmh: Double? = null
    @Volatile private var at = 0L                  // elapsedRealtime of the last fix

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) return
        try {
            context.getSystemService(LocationManager::class.java)
                ?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {           // no GPS on this device, or permission removed
        }
    }

    fun stop() {
        context.getSystemService(LocationManager::class.java)?.removeUpdates(this)
        kmh = null
    }

    override fun onLocationChanged(l: Location) {
        if (!l.hasSpeed()) return
        kmh = Math.round(l.speed * 3.6 * 10) / 10.0
        at = SystemClock.elapsedRealtime()
    }

    override fun onProviderDisabled(provider: String) {
        kmh = null
    }

    /** The latest speed in km/h, or null without a fix in the last [MAX_AGE_MS] (lost signal). */
    fun current(): Double? = kmh?.takeIf { SystemClock.elapsedRealtime() - at <= MAX_AGE_MS }

    companion object {
        const val MAX_AGE_MS = 3_000L
    }
}
