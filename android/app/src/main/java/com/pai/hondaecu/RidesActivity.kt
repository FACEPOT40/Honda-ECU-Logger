package com.pai.hondaecu

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Logs tab: recorded rides on this phone (Documents/HondaECULogger, newest first), plus "Open other file"
 * for a CSV copied from the laptop. Tapping one opens it in RideViewActivity.
 * Each log's ⋮ renames or deletes it; Select (or a long press) picks several logs to delete at once.
 * Only the app's own recordings are listed; files it made before a reinstall need the phone's OK
 * (a system dialog) to change.
 */
class RidesActivity : Activity() {
    private lateinit var header: LinearLayout
    private lateinit var list: LinearLayout
    private var rides = emptyList<Ride>()
    private var selecting = false
    private val selected = mutableSetOf<Uri>()
    private var pendingRename: Pair<Uri, String>? = null     // waiting for the system's OK
    private var backCallback: Any? = null                     // OnBackInvokedCallback (Android 13+)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }
        header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(header)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Read the files again and redraw (after a rename / delete, or coming back to the screen). */
    private fun refresh() {
        rides = queryRides()
        selected.retainAll(rides.map { it.uri }.toSet())
        if (rides.isEmpty()) setSelecting(false) else render()
    }

    private fun render() {
        showHeader()
        list.removeAllViews()
        if (rides.isEmpty()) {
            list.addView(uiText(14f, Look.GREY,
                "No rides recorded on this phone yet.\n\nTo look at a ride from the laptop, copy its CSV " +
                "to the phone and use Open other file.").apply { setPadding(dp(8), dp(32), dp(8), dp(8)) })
            return
        }
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (r in rides) {
            val info = "${r.name}\n${when_.format(Date(r.modified))} · ${r.size / 1024} KB"
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (selecting) {
                val on = r.uri in selected
                row.addView(uiButton((if (on) "☑  " else "☐  ") + info, if (on) Tone.PRIMARY else Tone.PLAIN) {
                    toggle(r.uri)
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                row.addView(uiButton(info) { open(r.uri, r.name) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnLongClickListener {
                        setSelecting(true)
                        toggle(r.uri)
                        true
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(uiButton("⋮") {}.apply { setOnClickListener { rowMenu(this, r) } },
                    LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.MATCH_PARENT))
            }
            list.addView(row)
        }
    }

    private fun showHeader() {
        header.removeAllViews()
        fun title(t: String) = header.addView(uiText(18f, Look.GREY, t).apply { setPadding(dp(6), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selecting) {
            title("${selected.size} selected")
            val all = selected.size == rides.size
            header.addView(uiButton(if (all) "None" else "All") {
                if (all) selected.clear() else selected += rides.map { it.uri }
                render()
            })
            header.addView(uiButton("Delete", Tone.STOP) { confirmDelete(rides.filter { it.uri in selected }) }
                .apply { isEnabled = selected.isNotEmpty() })
            header.addView(uiButton("Cancel") { setSelecting(false) })
        } else {
            title("Recorded logs")
            if (rides.isNotEmpty()) header.addView(uiButton("Select") { setSelecting(true) })
            header.addView(uiButton("Open other file") { pickFile() })
        }
    }

    private fun toggle(uri: Uri) {
        if (!selected.remove(uri)) selected += uri
        render()
    }

    /** Selection mode on / off. While it is on, Back leaves the selection, not the screen. */
    private fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) selected.clear()
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = backCallback as OnBackInvokedCallback?
            if (on && cb == null) {
                val c = OnBackInvokedCallback { setSelecting(false) }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, c)
                backCallback = c
            } else if (!on && cb != null) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
                backCallback = null
            }
        }
        render()
    }

    @Deprecated("Back on Android 10-12; Android 13+ uses the OnBackInvokedCallback")
    override fun onBackPressed() {
        if (selecting) setSelecting(false) else super.onBackPressed()
    }

    private fun rowMenu(anchor: android.view.View, r: Ride) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Open")
            menu.add(0, 2, 1, "Rename")
            menu.add(0, 3, 2, "Delete")
            menu.add(0, 4, 3, "Select")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> open(r.uri, r.name)
                    2 -> askRename(r)
                    3 -> confirmDelete(listOf(r))
                    4 -> {
                        setSelecting(true)
                        toggle(r.uri)
                    }
                }
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------ rename

    private fun askRename(r: Ride) {
        if (isRecording(r)) return busy()
        val field = EditText(this).apply {
            setText(r.name.removeSuffix(".csv"))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
            addView(TextView(context).apply { text = ".csv is added by itself." })
        }
        AlertDialog.Builder(this).setTitle("Rename log").setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val name = cleanName(field.text.toString())
                when {
                    name == null -> toast("Use a name without / \\ : * ? \" < > |")
                    name == r.name -> {}
                    rides.any { it.uri != r.uri && it.name.equals(name, ignoreCase = true) } ->
                        toast("A log called $name already exists")
                    else -> rename(r.uri, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rename(uri: Uri, name: String) {
        try {
            val n = contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }, null, null)
            if (n == 0) toast("Could not rename it")
            refresh()
        } catch (e: SecurityException) {
            // not the app's own file any more (e.g. made before a reinstall): the phone asks first
            pendingRename = uri to name
            val sender = when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender == null) {
                pendingRename = null
                toast("The phone does not allow renaming this file")
            } else {
                startIntentSenderForResult(sender, ASK_RENAME, null, 0, 0, 0)
            }
        }
    }

    /** "my ride" -> "my ride.csv"; null if it is empty or has characters a file name can't have. */
    private fun cleanName(input: String): String? {
        val base = input.trim().removeSuffix(".csv").removeSuffix(".CSV").trim()
        if (base.isEmpty() || base.any { it in "/\\:*?\"<>|" || it < ' ' }) return null
        return "$base.csv"
    }

    // ------------------------------------------------------------ delete

    private fun confirmDelete(which: List<Ride>) {
        if (which.isEmpty()) return
        if (which.any { isRecording(it) }) return busy()
        val what = if (which.size == 1) which[0].name else "${which.size} logs"
        AlertDialog.Builder(this).setTitle("Delete $what?")
            .setMessage("The CSV file${if (which.size == 1) " is" else "s are"} removed from the phone. " +
                "This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> delete(which.map { it.uri }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(uris: List<Uri>) {
        val notOwn = ArrayList<Uri>()
        var done = 0
        for (u in uris) {
            try {
                done += contentResolver.delete(u, null, null)
            } catch (_: SecurityException) {
                notOwn += u
            }
        }
        if (notOwn.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            // files from before a reinstall: one system dialog for all of them
            startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, notOwn).intentSender,
                ASK_DELETE, null, 0, 0, 0)
        } else if (notOwn.isNotEmpty()) {
            toast("${notOwn.size} file(s) could not be deleted here; use the phone's Files app")
        }
        if (done > 0) toast(if (done == 1) "Deleted" else "Deleted $done logs")
        rides = queryRides()
        if (notOwn.isEmpty()) setSelecting(false) else refresh()
    }

    private fun isRecording(r: Ride) = Live.recording && Live.recordingFile.endsWith("/" + r.name)

    private fun busy() = toast("That log is still being recorded. Stop recording first.")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ list + open

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
        when (requestCode) {
            ASK_RENAME -> {
                val p = pendingRename
                pendingRename = null
                if (resultCode == RESULT_OK && p != null) rename(p.first, p.second)
            }
            ASK_DELETE -> {
                rides = queryRides()
                if (resultCode == RESULT_OK) setSelecting(false) else refresh()
            }
            PICK_FILE -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) return
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "ride.csv"
                open(uri, name)
            }
        }
    }

    private fun open(uri: Uri, name: String) {
        startActivity(Intent(this, RideViewActivity::class.java)
            .setData(uri)
            .putExtra(RideViewActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        private const val PICK_FILE = 1
        private const val ASK_RENAME = 2
        private const val ASK_DELETE = 3
    }
}
