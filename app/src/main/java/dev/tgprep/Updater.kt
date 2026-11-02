package dev.tgprep

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

/**
 * Updates the app from the latest GitHub release: CI publishes one per push to main, as
 * `v1.0.<build>` with `tgprep.apk`, and the build number is the app's versionCode.
 *
 * The APK goes straight into a PackageInstaller session; Android shows its own confirmation and
 * installs it only if it's signed with the same key as this app.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/iyazerski/tgprep/releases/latest"
    private const val APK = "tgprep.apk"
    private const val TIMEOUT_MS = 30_000
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** A release's build number and APK address. */
    private class Release(val build: Long, val name: String, val apk: String)

    /**
     * Check for a newer release and install it. [onState] gets short progress texts on the main
     * thread, and `null` when the work is done.
     */
    fun update(context: Context, onState: (String?) -> Unit) {
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val installed = info.longVersionCode
        onState("Checking…")
        executor.execute {
            try {
                val release = latest()
                if (release.build <= installed) {
                    done(onState) { toast(app, "TG Prep ${info.versionName} is up to date") }
                    return@execute
                }
                main.post { onState("Downloading ${release.name}…") }
                install(app, release)
                done(onState) {}
            } catch (e: IOException) {
                done(onState) { toast(app, "Couldn't update: ${e.message}") }
            } catch (e: JSONException) {
                done(onState) { toast(app, "Couldn't update: unexpected answer from GitHub") }
            }
        }
    }

    private fun done(onState: (String?) -> Unit, then: () -> Unit) {
        main.post {
            onState(null)
            then()
        }
    }

    private fun latest(): Release {
        val connection = open(LATEST).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        val json = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub answered ${connection.responseCode}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
        val tag = json.getString("tag_name")
        val build = tag.substringAfterLast('.').toLongOrNull() ?: throw IOException("Unexpected release $tag")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == APK) {
                return Release(build, tag.removePrefix("v"), asset.getString("browser_download_url"))
            }
        }
        throw IOException("Release $tag has no $APK")
    }

    /** Stream the APK into an install session and commit it; [InstallResult] takes it from there. */
    private fun install(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                // GitHub redirects the download to its file host, which HttpURLConnection follows (both https).
                val connection = open(release.apk)
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("The download answered ${connection.responseCode}")
                    }
                    session.openWrite(APK, 0, connection.contentLengthLong).use { out ->
                        connection.inputStream.use { it.copyTo(out) }
                        session.fsync(out)
                    }
                } finally {
                    connection.disconnect()
                }
                // Mutable: the installer adds the result to the intent.
                val result = PendingIntent.getBroadcast(
                    context, 0, Intent(context, InstallResult::class.java), PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

/** Gets the install session's result: shows Android's confirmation, or says why it failed. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Android closes the app and installs the new version.
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Updater.toast(
                context,
                "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}",
            )
        }
    }
}
