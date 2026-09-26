package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.AutoBackupPolicy
import com.example.recipeclipper.data.backup.AutoBackupRecord
import com.example.recipeclipper.data.backup.BackupDestination
import com.example.recipeclipper.data.backup.BackupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The folder the automatic copy (#150) goes to: a tree the user picked once through the system
 * picker, reached only through its lasting permission. The one part that needs a `Context`.
 */
interface BackupFolder {
    /** [BackupDestination.NOT_CHOSEN] with no folder, READY while its permission stands, else LOST. */
    fun destination(folderUri: String?): BackupDestination

    /** Keeps a lasting read-and-write permission on a picked folder; its name, or null if it can't. */
    fun adopt(folderUri: String): String?

    /** Gives a folder's permission back (the user chose another). */
    fun release(folderUri: String)

    /** The names of the files in the folder, or null if it can't be read. */
    suspend fun list(folderUri: String): List<String>?

    /** Writes a zip export ([com.example.recipeclipper.data.backup.BackupArchive]) as [name],
     *  replacing a file of that name; the name it ended up with, or null if it couldn't. A
     *  failed write leaves no partial file. */
    suspend fun write(folderUri: String, name: String, json: String, photos: Map<String, String>): String?

    /** Deletes the named file, if it is there. */
    suspend fun delete(folderUri: String, name: String)
}

/** Where [AutoBackupRecord] is kept: its own preferences file, outside Android's cloud backup. */
interface AutoBackupStore {
    val record: StateFlow<AutoBackupRecord>
    fun update(transform: (AutoBackupRecord) -> AutoBackupRecord)
}

/** When the app looks for a copy to write; WorkManager in the app, a recorder in tests. */
interface AutoBackupScheduler {
    /** A daily look, kept across restarts; idempotent. */
    fun startPeriodic()

    /** A look shortly after the app is left, coalescing quick returns. */
    fun afterLeaving()

    /** A look straight away (a folder was just chosen, or the copy turned back on). */
    fun now()
}

/** What Settings and Home show: the record, and whether its destination can be written now. */
data class AutoBackupState(val record: AutoBackupRecord, val destination: BackupDestination)

enum class AutoBackupOutcome { WRITTEN, UP_TO_DATE, OFF, NO_DESTINATION, FAILED }

/**
 * The automatic copy (#150): the latest export, photos included, written as a zip into the
 * folder the user chose, keeping the newest [AutoBackupPolicy.KEEP]. The rules are
 * [AutoBackupPolicy]'s; this runs them against the real export, folder and record. Nothing is
 * ever written anywhere else, and nothing is sent to any server.
 */
@Singleton
class AutoBackup @Inject constructor(
    private val backups: BackupRepository,
    private val folder: BackupFolder,
    private val store: AutoBackupStore,
    private val clock: Clock,
    private val scheduler: AutoBackupScheduler
) {
    // One copy at a time: the worker and "Back up now" may overlap.
    private val mutex = Mutex()

    // Bumped to recheck the folder's permission, which can go away while the app is closed.
    private val recheck = MutableStateFlow(0)

    val state: Flow<AutoBackupState> = combine(store.record, recheck) { record, _ ->
        AutoBackupState(record, folder.destination(record.folderUri))
    }

    val current: AutoBackupState
        get() = store.record.value.let { AutoBackupState(it, folder.destination(it.folderUri)) }

    /** Asks the folder again, e.g. when Settings opens. */
    fun recheckDestination() {
        recheck.value++
    }

    /** The daily look; called once per process. */
    fun start() = scheduler.startPeriodic()

    /** The app was left: a look soon, if a copy is being kept. */
    fun onAppLeft() {
        val record = store.record.value
        if (record.enabled && record.folderUri != null) scheduler.afterLeaving()
    }

    fun setEnabled(enabled: Boolean) {
        store.update { it.copy(enabled = enabled) }
        if (enabled) scheduler.now()
    }

    /** Home's one-time card was dismissed ("Not now"); Settings still offers the folder. */
    fun dismissFolderPrompt() = store.update { it.copy(folderPromptDone = true) }

    /**
     * The user picked a folder: keep its permission, give the old one back, and write a first
     * copy there at once. False if the permission couldn't be kept.
     */
    fun chooseFolder(folderUri: String): Boolean {
        val name = folder.adopt(folderUri) ?: return false
        val old = store.record.value.folderUri
        if (old != null && old != folderUri) folder.release(old)
        store.update {
            it.copy(enabled = true, folderUri = folderUri, folderName = name, folderPromptDone = true, lastFailed = false)
        }
        recheckDestination()
        scheduler.now()
        return true
    }

    /**
     * Writes a copy if [AutoBackupPolicy.isDue] (always, for [force]: "Back up now", which works
     * even with the automatic copy off). Old copies beyond the newest three go afterwards, so a
     * failed write never leaves fewer.
     */
    suspend fun run(force: Boolean = false): AutoBackupOutcome = mutex.withLock {
        val record = store.record.value
        if (!force && !record.enabled) return AutoBackupOutcome.OFF
        val folderUri = record.folderUri
        if (folderUri == null || folder.destination(folderUri) != BackupDestination.READY) {
            recheckDestination()
            return AutoBackupOutcome.NO_DESTINATION
        }
        val existing = folder.list(folderUri) ?: return failed()
        val exported = when (val result = backups.export()) {
            is BackupResult.Success -> result.value
            is BackupResult.Failure -> return failed()
        }
        val now = clock.now()
        val fingerprint = AutoBackupPolicy.fingerprint(exported.json, exported.photos.keys)
        if (!AutoBackupPolicy.isDue(record, fingerprint, existing, now, force)) return AutoBackupOutcome.UP_TO_DATE
        val written = folder.write(folderUri, AutoBackupPolicy.fileName(now), exported.json, exported.photos)
            ?: return failed()
        AutoBackupPolicy.toDelete(existing + written, written).forEach { folder.delete(folderUri, it) }
        store.update {
            it.copy(lastBackupAt = now, lastFingerprint = fingerprint, lastFile = written, lastFailed = false)
        }
        AutoBackupOutcome.WRITTEN
    }

    private fun failed(): AutoBackupOutcome {
        store.update { it.copy(lastFailed = true) }
        recheckDestination()
        return AutoBackupOutcome.FAILED
    }
}
