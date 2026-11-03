package com.pai.hondaecu

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Logs tab: recorded rides on this phone (Documents/HondaECULogger, newest first), plus "Open other file"
 * for a CSV copied from the laptop. Tapping one opens it in RideViewActivity.
 */
class RidesActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(uiText(18f, Look.GREY, "Recorded logs"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(uiButton("Open other file") { pickFile() })
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        showRides()
    }

    private fun showRides() {
        list.removeAllViews()
        val rides = queryRides()
        if (rides.isEmpty()) {
            list.addView(uiText(14f, Look.GREY,
                "No rides recorded on this phone yet.\n\nTo look at a ride from the laptop, copy its CSV " +
                "to the phone and use Open other file.").apply { setPadding(dp(8), dp(32), dp(8), dp(8)) })
            return
        }
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (r in rides) {
            list.addView(uiButton("${r.name}\n${when_.format(Date(r.modified))} · ${r.size / 1024} KB") {
                open(r.uri, r.name)
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
    }

    private class Ride(val uri: Uri, val name: String, val size: Long, val modified: Long)

    /** The app's own recordings: no storage permission is needed to see files it created. */
    private fun queryRides(): List<Ride> {
        val files = MediaStore.Files.getContentUri("external")
        val out = ArrayList<Ride>()
        try {
            contentResolver.query(files,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("Documents/HondaECULogger%", "%.csv"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Ride(ContentUris.withAppendedId(files, c.getLong(0)), c.getString(1),
                        c.getLong(2), c.getLong(3) * 1000)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values",
                "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
        }, PICK_FILE)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || uri == null) return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "ride.csv"
        open(uri, name)
    }

    private fun open(uri: Uri, name: String) {
        startActivity(Intent(this, RideViewActivity::class.java)
            .setData(uri)
            .putExtra(RideViewActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        private const val PICK_FILE = 1
    }
}
