package com.phlox.tvwebbrowser.utils

import android.content.Context
import java.io.File

/**
 * The folders where downloads of video streams keep their segments until they are packed into the file: one per
 * download, by its id, in the app's own storage (not backed up), kept while the download is paused.
 */
object StreamWorkDir {
    private const val DIR = "stream-downloads"

    fun root(context: Context): File = File(context.noBackupFilesDir, DIR)

    fun of(context: Context, id: Long): File = File(root(context), id.toString())

    /** Blocking */
    fun delete(context: Context, id: Long) {
        if (id <= 0) return
        of(context, id).deleteRecursively()
    }

    /** Deletes the folders of downloads not in [keep] (ended ones a dead process left). Blocking */
    fun deleteOthers(context: Context, keep: Set<Long>) {
        root(context).listFiles()?.forEach { dir ->
            if (dir.name.toLongOrNull() !in keep) dir.deleteRecursively()
        }
    }
}
