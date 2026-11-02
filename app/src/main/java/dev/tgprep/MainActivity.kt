package dev.tgprep

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single screen: receive a video, optimize it, send the result to Telegram.
 *
 * Opened from Share or "Open with", it runs everything at once and hands over to Telegram. Opened
 * from the launcher, it shows the controls for picking, optimizing, sending, and saving. Technical
 * details about the files are kept in a sheet behind the info button.
 */
class MainActivity : Activity() {

    private lateinit var backdrop: Backdrop
    private lateinit var content: LinearLayout
    private lateinit var card: GlassPanel
    private lateinit var thumb: ImageView
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var chips: LinearLayout
    private lateinit var progress: GlassProgress
    private lateinit var primary: GlassButton
    private lateinit var secondaryRow: LinearLayout
    private lateinit var save: GlassButton
    private lateinit var pick: GlassButton
    private lateinit var info: GlassButton
    private lateinit var scrim: View
    private lateinit var sheet: GlassPanel
    private lateinit var detailsText: TextView
    private lateinit var detailsScroll: ScrollView

    private var source: Uri? = null
    private var sourceInfo: SourceInfo? = null
    private var sourceSize: Long? = null
    private var output: File? = null
    private var job: Optimizer? = null
    /** True when the video came from Share/Open with: optimize and send without any taps. */
    private var autoSend = false
    /** Technical description of the source and the last run, shown in the details sheet. */
    private var technical = ""
    private val closeSheetOnBack = OnBackInvokedCallback { hideDetails() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Style.EASE).setDuration(600).start()
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
        autoSend = uri != null
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
        show(
            title = if (autoSend) "Preparing for Telegram" else "Opening video…",
            subtitle = "",
            busy = true,
        )
        progress.reset()
        Thread {
            val result = runCatching { Optimizer(this, uri).probe() }
            val preview = runCatching { contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
            val size = querySize(uri)
            runOnUiThread {
                setThumbnail(preview)
                result.onSuccess { info ->
                    sourceInfo = info
                    sourceSize = size
                    technical = "Source\n${info.describe()}"
                    if (autoSend) {
                        optimize()
                    } else {
                        show(
                            title = videoTitle(info),
                            subtitle = "Ready to optimize for Telegram.",
                            chips = sourceChips(info),
                            primaryText = "Optimize for Telegram",
                            onPrimary = ::optimize,
                            showPick = true,
                        )
                    }
                }.onFailure {
                    technical = it.toString()
                    showFailure("Can't read this video", it, retry = null)
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
        show(
            title = if (autoSend) "Preparing for Telegram" else "Optimizing…",
            subtitle = "0%",
            chips = sourceChips(info),
            busy = true,
            primaryText = "Cancel",
            primaryAccent = false,
            onPrimary = { optimizer.cancelled = true },
        )
        progress.setProgress(0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val plan = optimizer.plan(info)
        Thread {
            val result = runCatching {
                val report = optimizer.run(info, plan, out) { p ->
                    runOnUiThread {
                        progress.setProgress(p)
                        subtitle.text = "${(p * 100).toInt()}%"
                    }
                }
                Mp4Meta.copyWithoutLocation(out, shareFile)
                report
            }
            runOnUiThread {
                job = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                result.onSuccess { report ->
                    output = out
                    technical = "Source\n${info.describe()}\n\nResult\n$report"
                    // Telegram never preselects its top quality step, and only that step sends the file untouched.
                    val hint = "In Telegram, pick ${min(plan.width, plan.height)}p (top quality) so it's sent as is."
                    if (autoSend) {
                        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                        share(shareFile, direct = true)
                        finish()
                        return@runOnUiThread
                    }
                    content.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    show(
                        title = "Ready to send",
                        subtitle = hint,
                        chips = resultChips(plan, shareFile.length()),
                        primaryText = "Send to Telegram",
                        onPrimary = { share(shareFile, direct = true) },
                        onPrimaryLong = { share(shareFile, direct = false) },
                        showSave = true,
                        showPick = true,
                    )
                }.onFailure {
                    if (optimizer.cancelled) {
                        if (autoSend) {
                            finish()
                        } else {
                            show(
                                title = videoTitle(info),
                                subtitle = "Cancelled.",
                                chips = sourceChips(info),
                                primaryText = "Optimize for Telegram",
                                onPrimary = ::optimize,
                                showPick = true,
                            )
                        }
                        return@runOnUiThread
                    }
                    Log.w("optimize failed: ${it.stackTraceToString()}")
                    // Full trace goes to logcat; the details sheet shows the readable chain of causes.
                    technical = "Source\n${info.describe()}\n\nError\n" +
                        generateSequence(it) { e -> e.cause }.take(3).joinToString("\n\ncaused by: ") { e -> e.toString() }
                    showFailure("Couldn't optimize this video", it, retry = ::optimize)
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
        save.isEnabled = false
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
                if (result.isSuccess) {
                    save.text = "Saved to gallery"
                    content.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    save.isEnabled = true
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                }
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

    /** Return the source's size in bytes, or null if the provider doesn't report it. */
    private fun querySize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun pickVideo() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*"), REQUEST_PICK)
    }

    // ---- Screen states ----

    private fun showIdle() {
        technical = ""
        setThumbnail(null)
        show(
            title = "Share a video to TG Prep",
            subtitle = "Or pick one here. It's converted for Telegram with true-to-life colors and smooth motion.",
            primaryText = "Pick video",
            onPrimary = ::pickVideo,
        )
    }

    private fun showFailure(heading: String, error: Throwable, retry: (() -> Unit)?) {
        val cause = generateSequence(error) { it.cause }.last()
        show(
            title = heading,
            subtitle = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName,
            primaryText = if (retry != null) "Try again" else "Pick video",
            onPrimary = retry ?: ::pickVideo,
            showPick = retry != null,
        )
    }

    /** Morph the card and buttons into a new state. */
    private fun show(
        title: String,
        subtitle: String,
        chips: List<String> = emptyList(),
        busy: Boolean = false,
        primaryText: String? = null,
        primaryAccent: Boolean = true,
        onPrimary: (() -> Unit)? = null,
        onPrimaryLong: (() -> Unit)? = null,
        showSave: Boolean = false,
        showPick: Boolean = false,
    ) {
        TransitionManager.beginDelayedTransition(
            content,
            AutoTransition().apply {
                duration = 420
                interpolator = Style.EASE
            },
        )
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        setChips(chips)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        primary.visibility = if (primaryText != null) View.VISIBLE else View.GONE
        primaryText?.let { primary.text = it }
        primary.accent = primaryAccent
        primary.isEnabled = true
        primary.setOnClickListener { onPrimary?.invoke() }
        if (onPrimaryLong != null) {
            primary.setOnLongClickListener { onPrimaryLong(); true }
        } else {
            primary.setOnLongClickListener(null)
            primary.isLongClickable = false
        }
        save.visibility = if (showSave) View.VISIBLE else View.GONE
        save.text = "Save to gallery"
        save.isEnabled = true
        pick.visibility = if (showPick) View.VISIBLE else View.GONE
        // The gap between the two buttons only applies when both are shown.
        (save.layoutParams as LinearLayout.LayoutParams).marginEnd = if (showPick) dpi(6) else 0
        (pick.layoutParams as LinearLayout.LayoutParams).marginStart = if (showSave) dpi(6) else 0
        secondaryRow.visibility = if (showSave || showPick) View.VISIBLE else View.GONE
        info.visibility = if (technical.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setThumbnail(bitmap: Bitmap?) {
        thumb.setImageBitmap(bitmap)
        thumb.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        icon.visibility = if (bitmap != null) View.GONE else View.VISIBLE
    }

    private fun setChips(labels: List<String>) {
        chips.removeAllViews()
        chips.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        labels.forEachIndexed { i, label ->
            chips.addView(
                TextView(this).apply {
                    text = label
                    textSize = 13f
                    typeface = Style.SEMIBOLD
                    setTextColor(Style.TEXT)
                    setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14f)
                        setColor(0x14FFFFFF)
                        setStroke(dpi(1), 0x26FFFFFF)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = dpi(8)
                },
            )
        }
    }

    private fun videoTitle(info: SourceInfo): String =
        info.createdMs?.let { "Video · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(it))}" } ?: "Video"

    private fun sourceChips(info: SourceInfo): List<String> {
        val kind = when {
            info.dvProfile != null -> "Dolby Vision"
            info.isHdr -> "HDR"
            else -> "SDR"
        }
        val short = min(info.width, info.height)
        val seconds = (info.durationUs / 1_000_000.0).roundToInt()
        return listOf(kind, "${short}p · ${info.fps.roundToInt()} fps", "%d:%02d".format(seconds / 60, seconds % 60))
    }

    private fun resultChips(plan: Plan, bytes: Long): List<String> {
        val chips = mutableListOf("${min(plan.width, plan.height)}p · ${plan.fps} fps", "%.1f MB".format(bytes / 1e6))
        sourceSize?.takeIf { it > bytes }?.let { chips += "−${((1 - bytes.toDouble() / it) * 100).roundToInt()}%" }
        return chips
    }

    // ---- Details sheet ----

    private fun showDetails() {
        detailsText.text = technical
        // Long details scroll inside the sheet instead of pushing it off screen.
        val maxHeight = (content.height * 0.6f).toInt()
        detailsText.measure(
            View.MeasureSpec.makeMeasureSpec(content.width - dpi(64), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        detailsScroll.layoutParams = detailsScroll.layoutParams.apply { height = min(detailsText.measuredHeight, maxHeight) }
        scrim.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(250).start()
        sheet.post {
            sheet.translationY = sheet.height.toFloat() + dp(40f)
            sheet.animate().translationY(0f).setInterpolator(Style.EASE).setDuration(480).start()
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeSheetOnBack)
    }

    private fun hideDetails() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeSheetOnBack)
        scrim.animate().alpha(0f).setDuration(250).withEndAction { scrim.visibility = View.GONE }.start()
        sheet.animate().translationY(sheet.height.toFloat() + dp(40f)).setInterpolator(Style.EASE).setDuration(320)
            .withEndAction { sheet.visibility = View.GONE }.start()
    }

    // ---- Layout ----

    private fun buildUi() {
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header: app name and the info button for technical details.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "TG Prep"
                textSize = 28f
                typeface = Style.BOLD
                setTextColor(Style.TEXT)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        info = GlassButton(this).apply {
            text = "i"
            minimumHeight = 0
            cornerRadius = dp(22f)
            backdrop = this@MainActivity.backdrop
            contentDescription = "Technical details"
            setOnClickListener { showDetails() }
            visibility = View.GONE
        }
        header.addView(info, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Main card: preview, title, short facts, progress.
        card = GlassPanel(this).apply {
            backdrop = this@MainActivity.backdrop
            cornerRadius = dp(32f)
        }
        val cardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(20), dpi(20), dpi(20), dpi(24))
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22f))
                }
            }
            visibility = View.GONE
        }
        cardBody.addView(thumb, LinearLayout.LayoutParams(MATCH, dpi(260)).apply { bottomMargin = dpi(20) })
        icon = ImageView(this).apply { setImageResource(R.drawable.ic_play) }
        cardBody.addView(icon, LinearLayout.LayoutParams(dpi(88), dpi(88)).apply { bottomMargin = dpi(16) })
        title = TextView(this).apply {
            textSize = 22f
            typeface = Style.BOLD
            setTextColor(Style.TEXT)
            gravity = Gravity.CENTER
        }
        cardBody.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        subtitle = TextView(this).apply {
            textSize = 15f
            setTextColor(Style.TEXT_DIM)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        cardBody.addView(subtitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(8) })
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        cardBody.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(16) })
        progress = GlassProgress(this)
        cardBody.addView(progress, LinearLayout.LayoutParams(MATCH, dpi(8)).apply { topMargin = dpi(20) })
        card.addView(cardBody, FrameLayout.LayoutParams(MATCH, WRAP))
        content.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Actions.
        primary = GlassButton(this).apply {
            backdrop = this@MainActivity.backdrop
            accent = true
        }
        content.addView(primary, LinearLayout.LayoutParams(MATCH, WRAP))
        secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        save = GlassButton(this).apply {
            backdrop = this@MainActivity.backdrop
            setOnClickListener { output?.let(::saveToGallery) }
        }
        pick = GlassButton(this).apply {
            text = "Another video"
            backdrop = this@MainActivity.backdrop
            setOnClickListener { pickVideo() }
        }
        secondaryRow.addView(save, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dpi(6) })
        secondaryRow.addView(pick, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(6) })
        content.addView(secondaryRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(12) })

        // Details sheet over a dimmed screen.
        scrim = View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            setOnClickListener { hideDetails() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = GlassPanel(this).apply {
            backdrop = this@MainActivity.backdrop
            tint = 0xF00B0B0C.toInt()
            visibility = View.GONE
            isClickable = true
        }
        val sheetBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(20), dpi(22), dpi(16))
        }
        sheetBody.addView(
            TextView(this).apply {
                text = "Technical details"
                textSize = 18f
                typeface = Style.BOLD
                setTextColor(Style.TEXT)
            },
        )
        detailsText = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Style.TEXT_DIM)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
        }
        detailsScroll = ScrollView(this).apply { addView(detailsText) }
        sheetBody.addView(
            detailsScroll,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(12) },
        )
        sheetBody.addView(
            GlassButton(this).apply {
                text = "Close"
                backdrop = this@MainActivity.backdrop
                setOnClickListener { hideDetails() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(16) },
        )
        sheet.addView(sheetBody, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // Edge to edge: the backdrop fills the screen; content stays clear of the system bars.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val side = dpi(20)
            content.setPadding(bars.left + side, bars.top + dpi(12), bars.right + side, bars.bottom + dpi(16))
            (sheet.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + dpi(10), 0, bars.right + dpi(10), bars.bottom + dpi(10))
            insets
        }
        setContentView(root)
    }

    private companion object {
        const val REQUEST_PICK = 1
        const val REQUEST_LOCATION = 2
        const val PREF_ASKED_LOCATION = "asked_media_location"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")
    }
}
