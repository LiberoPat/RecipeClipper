package com.example.recipeclipper.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.recipeclipper.data.AutoBackupStore
import com.example.recipeclipper.data.backup.AutoBackupRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The automatic copy's record (#150) in its own SharedPreferences file, `auto_backup`, which is
 * deliberately not on the backup include list: a folder's permission belongs to this phone, so
 * a restored phone asks for its folder again rather than showing one it can't reach.
 */
@Singleton
class SharedPrefsAutoBackupStore @Inject constructor(
    @ApplicationContext context: Context
) : AutoBackupStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.read())

    override val record: StateFlow<AutoBackupRecord> = state.asStateFlow()

    @Synchronized
    override fun update(transform: (AutoBackupRecord) -> AutoBackupRecord) {
        state.update(transform)
        val value = state.value
        prefs.edit {
            putBoolean(ENABLED, value.enabled)
            putOrRemove(FOLDER_URI, value.folderUri)
            putOrRemove(FOLDER_NAME, value.folderName)
            if (value.lastBackupAt != null) putLong(LAST_BACKUP_AT, value.lastBackupAt) else remove(LAST_BACKUP_AT)
            putOrRemove(LAST_FINGERPRINT, value.lastFingerprint)
            putOrRemove(LAST_FILE, value.lastFile)
            putBoolean(LAST_FAILED, value.lastFailed)
            putBoolean(FOLDER_PROMPT_DONE, value.folderPromptDone)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value != null) putString(key, value) else remove(key)
    }

    private fun SharedPreferences.read() = AutoBackupRecord(
        enabled = getBoolean(ENABLED, true),
        folderUri = getString(FOLDER_URI, null),
        folderName = getString(FOLDER_NAME, null),
        lastBackupAt = if (contains(LAST_BACKUP_AT)) getLong(LAST_BACKUP_AT, 0) else null,
        lastFingerprint = getString(LAST_FINGERPRINT, null),
        lastFile = getString(LAST_FILE, null),
        lastFailed = getBoolean(LAST_FAILED, false),
        folderPromptDone = getBoolean(FOLDER_PROMPT_DONE, false)
    )

    private companion object {
        const val FILE = "auto_backup"
        const val ENABLED = "enabled"
        const val FOLDER_URI = "folder_uri"
        const val FOLDER_NAME = "folder_name"
        const val LAST_BACKUP_AT = "last_backup_at"
        const val LAST_FINGERPRINT = "last_fingerprint"
        const val LAST_FILE = "last_file"
        const val LAST_FAILED = "last_failed"
        const val FOLDER_PROMPT_DONE = "folder_prompt_done"
    }
}
