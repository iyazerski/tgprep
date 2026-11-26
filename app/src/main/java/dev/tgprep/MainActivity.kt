@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.tgprep

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single screen: receive a video, optimize it, send the result to Telegram.
 *
 * Opened from Share or "Open with", it runs everything at once and hands over to Telegram. Opened
 * from the launcher, it shows the controls for picking, optimizing, sending, and saving. Technical
 * details about the files are kept in a sheet behind the info button.
 */
class MainActivity : ComponentActivity() {
    private val snackbar = SnackbarHostState()
    private var stage by mutableStateOf<Stage>(Stage.Idle)
    /** How much of the running optimization is done, 0 to 1. The optimizer's thread writes it. */
    private var progress by mutableFloatStateOf(0f)
    private var thumbnail by mutableStateOf<ImageBitmap?>(null)
    /** Technical description of the source and the last run, shown in the details sheet. */
    private var technical by mutableStateOf("")
    private var saving by mutableStateOf(Saving.Idle)
    /** The update check's progress, or null when idle. */
    private var updating by mutableStateOf<String?>(null)

    private var pendingLoad: Pair<Uri, Boolean>? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { load(it, autoSend = false) }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingLoad?.also { pendingLoad = null }?.let { (uri, autoSend) -> load(uri, autoSend) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TgPrepTheme { Screen() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (stage is Stage.Optimizing) {
            if (intent.sharedUri() != null) {
                Toast.makeText(this, R.string.wait_for_current, Toast.LENGTH_SHORT).show()
            }
            return
        }
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        (stage as? Stage.Optimizing)?.optimizer?.cancelled = true
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        // Reopening from Recents replays the original Share intent; don't re-send an old video.
        val fromHistory = intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val uri = if (fromHistory) null else intent?.sharedUri()
        if (uri != null) load(uri, autoSend = true) else showIdle()
    }

    private fun Intent.sharedUri(): Uri? = when (action) {
        Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW -> data
        else -> null
    }

    private fun load(uri: Uri, autoSend: Boolean) {
        // The picker can still open from buttons that are fading out as optimizing starts.
        if (stage is Stage.Optimizing) return
        pendingLoad = null
        // Without ACCESS_MEDIA_LOCATION, MediaProvider strips the GPS from the video. Only the
        // launcher flow can save a gallery copy with GPS; Share never writes location.
        val prefs = getPreferences(MODE_PRIVATE)
        if (!autoSend &&
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_ASKED_LOCATION, false)
        ) {
            prefs.edit { putBoolean(PREF_ASKED_LOCATION, true) }
            pendingLoad = uri to autoSend
            locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        technical = ""
        thumbnail = null
        saving = Saving.Idle
        val opening = Stage.Opening(uri, autoSend)
        stage = opening
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Optimizer(this@MainActivity, uri).probe() } }
            val preview = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
            }
            val size = withContext(Dispatchers.IO) { querySize(uri) }
            // Another video came in, or the app returned to Idle, while this one was read.
            if (stage !== opening) return@launch
            thumbnail = preview?.asImageBitmap()
            result.onSuccess { info ->
                val video = LoadedVideo(uri, info, size, autoSend)
                technical = "Source\n${info.describe()}"
                if (autoSend) {
                    optimize(video)
                } else {
                    stage = Stage.Ready(video, R.string.ready_to_optimize)
                }
            }.onFailure {
                technical = it.toString()
                fail(R.string.cant_read, it, retry = null)
            }
        }
    }

    private fun optimize(video: LoadedVideo) {
        if (stage is Stage.Optimizing) return
        val (uri, info, _, autoSend) = video
        val base = sourceBaseName(uri) ?: "TgPrep_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        // Only write a separate full file with location when the user can save a gallery copy and the
        // source has GPS. Telegram uploads files untouched, so the shared file must never carry GPS.
        val needsFullCopy = !autoSend && info.location != null
        val shareFile = File(OutputProvider.outputDir(this), "${base}_TG.mp4")
        val out = if (needsFullCopy) File(OutputProvider.outputDir(this), "${base}_TG.full.mp4") else shareFile
        OutputProvider.prune(this, keep = setOf(shareFile, out))
        val optimizer = Optimizer(this, uri)
        progress = 0f
        stage = Stage.Optimizing(video, optimizer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val plan = optimizer.plan(info)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = optimizer.run(info, plan, out, keepLocation = needsFullCopy) { progress = it }
                    if (needsFullCopy) Mp4Meta.copyWithoutLocation(out, shareFile)
                    report
                }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // A cancel during the last copy lets the run succeed, but the result mustn't be sent.
            if (optimizer.cancelled) {
                if (autoSend) {
                    finishAndRemoveTask()
                } else {
                    stage = Stage.Ready(video, R.string.cancelled)
                }
                return@launch
            }
            result.onSuccess { report ->
                technical = "Source\n${info.describe()}\n\nResult\n$report"
                if (autoSend) {
                    Toast.makeText(this@MainActivity, getString(R.string.pick_top_quality, min(plan.width, plan.height)), Toast.LENGTH_LONG).show()
                    share(shareFile, direct = true)
                    // Use finish(), not finishAndRemoveTask(): a share chooser opens in our task and
                    // removing the task would close it. Recents replay is blocked by LAUNCHED_FROM_HISTORY.
                    finish()
                    return@launch
                }
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                saving = Saving.Idle
                stage = Stage.Done(video, plan, shareFile.length(), shareFile, out)
            }.onFailure {
                Log.w("optimize failed: ${it.stackTraceToString()}")
                // Full trace goes to logcat; the details sheet shows the readable chain of causes.
                technical = "Source\n${info.describe()}\n\nError\n" +
                    generateSequence(it) { e -> e.cause }.take(3).joinToString("\n\ncaused by: ") { e -> e.toString() }
                fail(R.string.couldnt_optimize, it, retry = video)
            }
        }
    }

    private fun share(file: File, direct: Boolean) {
        val uri = OutputProvider.uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (direct) {
            for (pkg in TELEGRAM_PACKAGES) {
                try {
                    startActivity(Intent(send).setPackage(pkg))
                    return
                } catch (_: ActivityNotFoundException) {
                    // Try the next Telegram variant, then fall back to the chooser.
                }
            }
        }
        startActivity(Intent.createChooser(send, getString(R.string.send_video)))
    }

    private fun saveToGallery(done: Stage.Done) {
        if (saving != Saving.Idle) return
        val file = done.fullFile
        val createdMs = done.video.info.createdMs
        saving = Saving.Busy
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        val displayName = if (file.name.endsWith(".full.mp4")) file.name.removeSuffix(".full.mp4") + ".mp4" else file.name
                        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        // The camera folder, so the copy shows up in Photos next to the original.
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                        createdMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Couldn't create gallery entry")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } ?: error("Couldn't open gallery entry")
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    } catch (e: Throwable) {
                        runCatching { contentResolver.delete(uri, null, null) }
                        throw e
                    }
                }
            }
            // The user may have moved on to another video (or re-run this one) while it was copied.
            if (stage !== done) return@launch
            if (result.isSuccess) {
                saving = Saving.Done
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                saving = Saving.Idle
                snackbar.showSnackbar(getString(R.string.save_failed))
            }
        }
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

    private fun pickVideo() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun checkForUpdates() {
        if (!packageManager.canRequestPackageInstalls()) {
            // Android asks once per app before it may install anything, updates included.
            Toast.makeText(this, R.string.allow_installs, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
            return
        }
        Updater.update(this) { updating = it }
    }

    private fun showIdle() {
        technical = ""
        thumbnail = null
        saving = Saving.Idle
        stage = Stage.Idle
    }

    private fun fail(@StringRes heading: Int, error: Throwable, retry: LoadedVideo?) {
        val cause = generateSequence(error) { it.cause }.last()
        stage = Stage.Failed(heading, cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName, retry)
    }

    // ---- Screen ----

    @Composable
    private fun Screen() {
        val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val motion = MaterialTheme.motionScheme
        var details by rememberSaveable { mutableStateOf(false) }
        // Cards in the bright surface color stand out from it, in light and dark alike.
        val background = MaterialTheme.colorScheme.surfaceContainer
        Scaffold(
            modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    subtitle = { Text(stringResource(R.string.tagline)) },
                    actions = {
                        AnimatedVisibility(
                            technical.isNotEmpty(),
                            enter = scaleIn(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                        ) {
                            IconButton(onClick = { details = true }) { Symbol(R.drawable.sym_info, description = stringResource(R.string.technical_details)) }
                        }
                        Menu()
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    scrollBehavior = bar,
                )
            },
            bottomBar = { Actions(stage) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { insets ->
            Column(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(insets)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UpdateProgress(updating)
                Hero(heroView(stage), thumbnail, progress.takeIf { stage is Stage.Optimizing })
            }
        }
        if (details) DetailsSheet(technical) { details = false }
    }

    /** The top bar's overflow menu: updates and the version. */
    @Composable
    private fun Menu() {
        var open by remember { mutableStateOf(false) }
        val versionName = remember { packageManager.getPackageInfo(packageName, 0).versionName }
        Box {
            IconButton(onClick = { open = true }) { Symbol(R.drawable.sym_more_vert, description = stringResource(R.string.more)) }
            DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            checkForUpdates()
                        },
                        text = { Text(stringResource(R.string.check_for_updates)) },
                        supportingText = { Text(stringResource(R.string.version, versionName.orEmpty())) },
                        shape = MenuDefaults.itemShape(0, 1).shape,
                        leadingIcon = { Symbol(R.drawable.sym_system_update) },
                        enabled = updating == null,
                    )
                }
            }
        }
    }

    /** The buttons for [stage], at the bottom where the thumb reaches them. */
    @Composable
    private fun Actions(stage: Stage) {
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = stage,
            // Only a new kind of stage swaps the buttons.
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 }) togetherWith
                    fadeOut(motion.fastEffectsSpec())
            },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(16.dp),
            label = "actions",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (shown) {
                    Stage.Idle -> MainButton(stringResource(R.string.pick_video), R.drawable.sym_video_library, onClick = ::pickVideo)
                    is Stage.Opening -> Unit
                    is Stage.Ready -> {
                        MainButton(stringResource(R.string.optimize), R.drawable.sym_auto_awesome) { optimize(shown.video) }
                        SecondaryButtons(null)
                    }
                    is Stage.Optimizing ->
                        MainButton(stringResource(R.string.cancel), R.drawable.sym_close, tonal = true) { shown.optimizer.cancelled = true }
                    is Stage.Done -> {
                        SendButton(shown.shareFile)
                        SecondaryButtons(shown)
                    }
                    is Stage.Failed ->
                        if (shown.retry != null) {
                            MainButton(stringResource(R.string.try_again), R.drawable.sym_refresh) { optimize(shown.retry) }
                            SecondaryButtons(null)
                        } else {
                            MainButton(stringResource(R.string.pick_video), R.drawable.sym_video_library, onClick = ::pickVideo)
                        }
                }
            }
        }
    }

    /** Send to Telegram, with a menu to pick another app. */
    @Composable
    private fun SendButton(file: File) {
        val height = SplitButtonDefaults.MediumContainerHeight
        var menu by remember { mutableStateOf(false) }
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = { share(file, direct = true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = height),
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                    contentPadding = SplitButtonDefaults.MediumLeadingButtonContentPadding,
                ) {
                    Symbol(R.drawable.sym_send, Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(height)))
                    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
                    Text(stringResource(R.string.send_to_telegram), style = ButtonDefaults.textStyleFor(height))
                }
            },
            trailingButton = {
                Box {
                    val turn by animateFloatAsState(if (menu) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "turn")
                    SplitButtonDefaults.TrailingButton(
                        checked = menu,
                        onCheckedChange = { menu = it },
                        modifier = Modifier.heightIn(min = height),
                        shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                        contentPadding = SplitButtonDefaults.MediumTrailingButtonContentPadding,
                    ) {
                        Symbol(
                            R.drawable.sym_keyboard_arrow_down,
                            Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(height)).rotate(turn),
                            description = stringResource(R.string.more_ways_to_send),
                        )
                    }
                    DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                            DropdownMenuItem(
                                onClick = {
                                    menu = false
                                    share(file, direct = false)
                                },
                                text = { Text(stringResource(R.string.send_with_another_app)) },
                                shape = MenuDefaults.itemShape(0, 1).shape,
                                leadingIcon = { Symbol(R.drawable.sym_share) },
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** Save to gallery (once there's a [result]) and Another video, side by side. */
    @Composable
    private fun SecondaryButtons(result: Stage.Done?) {
        val height = ButtonDefaults.MediumContainerHeight
        ButtonGroup(overflowIndicator = {}, modifier = Modifier.fillMaxWidth()) {
            if (result != null) {
                customItem(
                    buttonGroupContent = {
                        val interaction = remember { MutableInteractionSource() }
                        val (label, icon) = when (saving) {
                            Saving.Idle -> stringResource(R.string.save_to_gallery) to R.drawable.sym_download
                            Saving.Busy -> stringResource(R.string.saving) to R.drawable.sym_download
                            Saving.Done -> stringResource(R.string.saved_to_gallery) to R.drawable.sym_check
                        }
                        FilledTonalButton(
                            onClick = { saveToGallery(result) },
                            modifier = Modifier.weight(1f).animateWidth(interaction).heightIn(min = height),
                            enabled = saving == Saving.Idle,
                            shapes = ButtonDefaults.shapesFor(height),
                            contentPadding = ButtonDefaults.contentPaddingFor(height),
                            interactionSource = interaction,
                        ) { ButtonLabel(label, icon) }
                    },
                    menuContent = {},
                )
            }
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = ::pickVideo,
                        modifier = Modifier.weight(1f).animateWidth(interaction).heightIn(min = height),
                        shapes = ButtonDefaults.shapesFor(height),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        interactionSource = interaction,
                    ) { ButtonLabel(stringResource(R.string.another_video), R.drawable.sym_video_library) }
                },
                menuContent = {},
            )
        }
    }
}

/** A probed video and whether it should auto-optimize and send without taps. */
private data class LoadedVideo(val uri: Uri, val info: SourceInfo, val size: Long?, val autoSend: Boolean)

/** What the screen shows. */
private sealed interface Stage {
    data object Idle : Stage

    /** Reading the video; from Share, optimizing and sending follow without a tap. */
    data class Opening(val uri: Uri, val autoSend: Boolean) : Stage

    data class Ready(val video: LoadedVideo, @StringRes val note: Int) : Stage

    data class Optimizing(val video: LoadedVideo, val optimizer: Optimizer) : Stage

    /** [shareFile] ([bytes] long) has the location removed for chats; [fullFile] keeps it for the gallery copy. */
    data class Done(val video: LoadedVideo, val plan: Plan, val bytes: Long, val shareFile: File, val fullFile: File) : Stage

    /** [message] is the system's own words for the error, so it stays as the system put it. */
    data class Failed(@StringRes val heading: Int, val message: String, val retry: LoadedVideo? = null) : Stage
}

private enum class Saving { Idle, Busy, Done }

/** How a [Stage] reads in the hero card. */
private class HeroView(
    val title: String,
    val detail: String,
    val tags: List<String>,
    val shape: RoundedPolygon,
    val icon: Int,
    val container: Color,
    val accent: Color,
    val onAccent: Color,
    /** A loading indicator takes the place of the shape and icon. */
    val busy: Boolean = false,
)

@Composable
private fun heroView(stage: Stage): HeroView {
    val colors = MaterialTheme.colorScheme
    return when (stage) {
        Stage.Idle -> HeroView(
            stringResource(R.string.idle_title),
            stringResource(R.string.idle_detail),
            emptyList(),
            MaterialShapes.Cookie12Sided,
            R.drawable.sym_movie,
            colors.surfaceBright,
            colors.primaryContainer,
            colors.onPrimaryContainer,
        )
        is Stage.Opening -> HeroView(
            stringResource(if (stage.autoSend) R.string.preparing else R.string.opening),
            "",
            emptyList(),
            MaterialShapes.Cookie12Sided,
            R.drawable.sym_movie,
            colors.surfaceBright,
            colors.primaryContainer,
            colors.onPrimaryContainer,
            busy = true,
        )
        is Stage.Ready -> HeroView(
            videoTitle(stage.video.info),
            stringResource(stage.note),
            sourceTags(stage.video.info),
            MaterialShapes.Clover4Leaf,
            R.drawable.sym_movie,
            colors.surfaceBright,
            colors.primary,
            colors.onPrimary,
        )
        is Stage.Optimizing -> HeroView(
            stringResource(if (stage.video.autoSend) R.string.preparing else R.string.optimizing),
            "",
            sourceTags(stage.video.info),
            MaterialShapes.Clover4Leaf,
            R.drawable.sym_movie,
            colors.surfaceBright,
            colors.primary,
            colors.onPrimary,
            busy = true,
        )
        is Stage.Done -> HeroView(
            stringResource(R.string.ready_to_send),
            // Telegram never preselects its top quality step, and only that step sends the file untouched.
            stringResource(R.string.pick_top_quality, min(stage.plan.width, stage.plan.height)),
            resultTags(stage.plan, stage.bytes, stage.video.size),
            MaterialShapes.Cookie9Sided,
            R.drawable.sym_check,
            colors.primaryContainer,
            colors.primary,
            colors.onPrimary,
        )
        is Stage.Failed -> HeroView(
            stringResource(stage.heading),
            stage.message,
            emptyList(),
            MaterialShapes.SoftBurst,
            R.drawable.sym_priority_high,
            colors.errorContainer,
            colors.error,
            colors.onError,
        )
    }
}

@Composable
private fun videoTitle(info: SourceInfo): String {
    val created = info.createdMs ?: return stringResource(R.string.video)
    val locale = LocalConfiguration.current.locales[0]
    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMHHmm"), locale).format(Date(created))
    return stringResource(R.string.video_taken, date)
}

@Composable
private fun sourceTags(info: SourceInfo): List<String> {
    val kind = when {
        info.dvProfile != null -> "Dolby Vision"
        info.isHdr -> "HDR"
        else -> "SDR"
    }
    val seconds = (info.durationUs / 1_000_000.0).roundToInt()
    return listOf(
        kind,
        stringResource(R.string.video_format, min(info.width, info.height), info.fps.roundToInt()),
        "%d:%02d".format(seconds / 60, seconds % 60),
    )
}

@Composable
private fun resultTags(plan: Plan, bytes: Long, sourceSize: Long?): List<String> {
    val tags = mutableListOf(
        stringResource(R.string.video_format, min(plan.width, plan.height), plan.fps),
        Formatter.formatShortFileSize(LocalContext.current, bytes),
    )
    sourceSize?.takeIf { it > bytes }?.let {
        val saved = ((1 - bytes.toDouble() / it) * 100).roundToInt()
        tags += "−" + NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(saved / 100.0)
    }
    return tags
}

/** The video and where it stands: preview, a badge that morphs with the stage, title, and short facts. */
@Composable
private fun Hero(view: HeroView, thumbnail: ImageBitmap?, progress: Float?) {
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(view.container, motion.defaultEffectsSpec(), label = "container")
    // Looked up from the target color: the in-between colors of the animation match no scheme role.
    val content by animateColorAsState(contentColorFor(view.container), motion.defaultEffectsSpec(), label = "content")
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(motion.defaultSpatialSpec()).padding(24.dp)) {
            if (thumbnail != null) {
                Box {
                    Image(
                        thumbnail,
                        contentDescription = stringResource(R.string.video_preview),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.extraLarge),
                    )
                    StageBadge(view, Modifier.align(Alignment.BottomStart).padding(12.dp).size(72.dp))
                }
            } else {
                StageBadge(view, Modifier.size(96.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(view.title, style = MaterialTheme.typography.headlineMediumEmphasized)
            if (view.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(view.detail, style = MaterialTheme.typography.bodyLarge)
            }
            if (progress != null) {
                val shown by animateFloatAsState(progress, ProgressIndicatorDefaults.ProgressAnimationSpec, label = "progress")
                Spacer(Modifier.height(4.dp))
                val locale = LocalConfiguration.current.locales[0]
                val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
                Text(
                    percent.format((progress * 100).toInt() / 100.0),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
            }
            if (view.tags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    view.tags.forEach { Tag(it) }
                }
            }
        }
    }
}

/** The stage as a shape with an icon, or a loading indicator while busy. */
@Composable
private fun StageBadge(view: HeroView, modifier: Modifier) {
    val motion = MaterialTheme.motionScheme
    val accent by animateColorAsState(view.accent, motion.defaultEffectsSpec(), label = "accent")
    val shapeAlpha by animateFloatAsState(if (view.busy) 0f else 1f, motion.defaultEffectsSpec(), label = "shape")
    // While busy, the hidden shape keeps the one it had, so it morphs from it to the next stage's.
    var badge by remember { mutableStateOf(view) }
    SideEffect { if (!view.busy) badge = view }
    val shown = if (view.busy) badge else view
    Box(modifier) {
        MorphingShape(shown.shape, accent, Modifier.fillMaxSize().graphicsLayer { alpha = shapeAlpha }) {
            Symbol(shown.icon, Modifier.fillMaxSize(0.42f), tint = shown.onAccent)
        }
        AnimatedVisibility(
            view.busy,
            enter = scaleIn(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) { ContainedLoadingIndicator(Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Tag(text: String) {
    // A tint of the card's text color reads on every card color.
    Surface(shape = CircleShape, color = LocalContentColor.current.copy(alpha = 0.1f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun MainButton(text: String, icon: Int, tonal: Boolean = false, onClick: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    val modifier = Modifier.fillMaxWidth().heightIn(min = height)
    val shapes = ButtonDefaults.shapesFor(height)
    val padding = ButtonDefaults.contentPaddingFor(height)
    val content: @Composable RowScope.() -> Unit = {
        Symbol(icon, Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, modifier = modifier, shapes = shapes, contentPadding = padding, content = content)
    } else {
        Button(onClick = onClick, modifier = modifier, shapes = shapes, contentPadding = padding, content = content)
    }
}

/** Icon and text for a button in a [ButtonGroup], on one line. */
@Composable
private fun RowScope.ButtonLabel(text: String, icon: Int) {
    Symbol(icon, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The update check's progress, shown while there is some. */
@Composable
private fun UpdateProgress(progress: String?) {
    // Keeps the last text on screen while the card collapses.
    var last by remember { mutableStateOf("") }
    SideEffect { if (progress != null) last = progress }
    AnimatedVisibility(progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    progress ?: last,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** File and codec details of the source and the last run, selectable for copying. */
@Composable
private fun DetailsSheet(text: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.technical_details), style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val PREF_ASKED_LOCATION = "asked_media_location"
private val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")
