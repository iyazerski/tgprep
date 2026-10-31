package dev.tgprep

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Single screen: receive a video, optimize it with one tap, send the result to Telegram. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var primary: Button
    private lateinit var save: Button
    private lateinit var pick: Button

    private var source: Uri? = null
    private var sourceInfo: SourceInfo? = null
    private var output: File? = null
    private var job: Optimizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (job == null) handleIntent(intent)
    }

    override fun onDestroy() {
        job?.cancelled = true
        super.onDestroy()
    }

    @Deprecated("Plain Activity result API; the app has no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) data?.data?.let(::load)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) pendingLoad?.also { pendingLoad = null }?.let(::load)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) load(uri) else showIdle()
    }

    private var pendingLoad: Uri? = null

    private fun load(uri: Uri) {
        // Without ACCESS_MEDIA_LOCATION, MediaProvider strips the GPS from the shared file. Ask once;
        // continue either way.
        if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_LOCATION, false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_LOCATION, true).apply()
            pendingLoad = uri
            requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQUEST_LOCATION)
            return
        }
        source = uri
        output = null
        status.text = "Reading video…"
        Thread {
            val result = runCatching { Optimizer(this, uri).probe() }
            runOnUiThread {
                result.onSuccess { info ->
                    sourceInfo = info
                    status.text = if (info.isHdr) "HDR video · ready" else "Video · ready"
                    details.text = info.describe()
                    primary.text = "Optimize for Telegram"
                    primary.isEnabled = true
                    primary.setOnClickListener { optimize() }
                    primary.setOnLongClickListener(null)
                    save.visibility = View.GONE
                    progress.visibility = View.GONE
                    pick.visibility = View.VISIBLE
                }.onFailure {
                    status.text = "Can't read this video"
                    details.text = it.toString()
                    showIdle(keepText = true)
                }
            }
        }.start()
    }

    private fun optimize() {
        val uri = source ?: return
        val info = sourceInfo ?: return
        OutputProvider.clear(this)
        val base = sourceBaseName(uri) ?: "TgPrep_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        // The full file keeps the location for the gallery copy. Telegram uploads files untouched, so the
        // copy that gets sent has the location removed to avoid sharing GPS with the chat.
        val out = File(OutputProvider.outputDir(this), "${base}_TG.full.mp4")
        val shareFile = File(OutputProvider.outputDir(this), "${base}_TG.mp4")
        val optimizer = Optimizer(this, uri)
        job = optimizer
        primary.isEnabled = false
        pick.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Optimizing…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val plan = optimizer.plan(info)
        Thread {
            val result = runCatching {
                val report = optimizer.run(info, plan, out) { p -> runOnUiThread { progress.progress = (p * 1000).toInt() } }
                Mp4Meta.copyWithoutLocation(out, shareFile)
                report
            }
            runOnUiThread {
                job = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                pick.isEnabled = true
                progress.visibility = View.GONE
                result.onSuccess { report ->
                    output = out
                    // Telegram's editor preselects at most its 3rd quality step (720p for videos above 1280 px),
                    // and only its top step sends the file untouched.
                    status.text = "Done. In Telegram, tap the quality button\nand pick ${min(plan.width, plan.height)}p (top) so it's sent as is."
                    details.text = "Source: ${info.describe()}\n\n$report"
                    primary.text = "Send to Telegram"
                    primary.isEnabled = true
                    primary.setOnClickListener { share(shareFile, direct = true) }
                    primary.setOnLongClickListener { share(shareFile, direct = false); true }
                    save.visibility = View.VISIBLE
                }.onFailure {
                    Log.w("optimize failed: ${it.stackTraceToString()}")
                    status.text = "Failed"
                    // Full trace goes to logcat; the screen shows the readable chain of causes.
                    details.text = generateSequence(it) { e -> e.cause }.take(3).joinToString("\n\ncaused by: ") { e -> e.toString() }
                    primary.text = "Retry"
                    primary.isEnabled = true
                    primary.setOnClickListener { optimize() }
                }
            }
        }.start()
    }

    private fun share(file: File, direct: Boolean) {
        val uri = OutputProvider.uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val telegram = TELEGRAM_PACKAGES.firstOrNull { isInstalled(it) }
        if (direct && telegram != null) {
            startActivity(send.setPackage(telegram))
        } else {
            startActivity(Intent.createChooser(send, "Send video"))
        }
    }

    private fun saveToGallery(file: File) {
        val createdMs = sourceInfo?.createdMs
        Thread {
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name.removeSuffix(".full.mp4") + ".mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    // The camera folder, so the copy shows up in Photos next to the original.
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    createdMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            runOnUiThread {
                Toast.makeText(this, if (result.isSuccess) "Saved to DCIM/Camera" else "Save failed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Return the source's file name without extension, reduced to file-safe characters, or null if unknown. */
    private fun sourceBaseName(uri: Uri): String? {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return name?.substringAfterLast('/')?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun showIdle(keepText: Boolean = false) {
        if (!keepText) {
            status.text = "Share a video to TG Prep, or pick one."
            details.text = ""
        }
        primary.text = "Pick video"
        primary.isEnabled = true
        primary.setOnClickListener { pickVideo() }
        primary.setOnLongClickListener(null)
        save.visibility = View.GONE
        progress.visibility = View.GONE
        pick.visibility = View.GONE
    }

    private fun pickVideo() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*"), REQUEST_PICK)
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 3, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        details = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        primary = Button(this).apply { textSize = 18f }
        save = Button(this).apply {
            text = "Save copy to gallery"
            visibility = View.GONE
            setOnClickListener { output?.let(::saveToGallery) }
        }
        pick = Button(this).apply {
            text = "Pick another video"
            setOnClickListener { pickVideo() }
        }
        val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(status, full)
        root.addView(progress, full)
        root.addView(primary, full)
        root.addView(save, full)
        root.addView(details, full)
        root.addView(pick, full)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    private companion object {
        const val REQUEST_PICK = 1
        const val REQUEST_LOCATION = 2
        const val PREF_ASKED_LOCATION = "asked_media_location"
        val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")
    }
}
