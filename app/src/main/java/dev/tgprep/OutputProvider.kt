package dev.tgprep

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider that exposes finished videos from [outputDir] to the share target.
 *
 * A framework-only replacement for AndroidX FileProvider, so the app has no dependencies.
 */
class OutputProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val file = File(outputDir(context!!), name)
        // Only plain names inside the output dir are served.
        if (file.parentFile != outputDir(context!!) || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val AUTHORITY = "dev.tgprep.out"

        fun outputDir(context: Context): File = File(context.cacheDir, "out").apply { mkdirs() }

        /** Delete previous results; only the latest output is kept. */
        fun clear(context: Context) {
            outputDir(context).listFiles()?.forEach { it.delete() }
        }

        fun uriFor(file: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
    }
}
