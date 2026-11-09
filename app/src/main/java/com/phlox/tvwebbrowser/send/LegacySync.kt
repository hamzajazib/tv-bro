package com.phlox.tvwebbrowser.send

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyStore

/**
 * The phone sync of 3.0 betas left a key in the Android keystore, prefs and the backup of bookmarks;
 * its table goes with the migration of the database to 29.
 */
object LegacySync {
    private const val TAG = "LegacySync"
    private const val KEY_ALIAS = "tvbro_sync_pairings"

    /** Blocking: call it off the main thread */
    fun drop(context: Context, settings: SendSettings) {
        settings.dropLegacy()
        File(context.filesDir, "sync").takeIf { it.exists() }?.deleteRecursively()
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "can not drop the key of the sync", e)
        }
    }
}
