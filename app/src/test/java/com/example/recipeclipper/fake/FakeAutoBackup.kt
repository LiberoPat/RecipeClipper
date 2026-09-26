package com.example.recipeclipper.fake

import com.example.recipeclipper.data.AutoBackup
import com.example.recipeclipper.data.AutoBackupScheduler
import com.example.recipeclipper.data.AutoBackupStore
import com.example.recipeclipper.data.BackupFolder
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.backup.AutoBackupRecord
import com.example.recipeclipper.data.backup.BackupDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** An in-memory folder: [files] by name to what was written; permissions by [granted] URI. */
class FakeBackupFolder : BackupFolder {
    val files = linkedMapOf<String, String>()
    val granted = mutableSetOf<String>()
    val released = mutableListOf<String>()

    /** False makes every write fail, as a full or offline provider would. */
    var writable = true

    /** A picked folder the app can't keep (adopt fails). */
    var refuses = false

    override fun destination(folderUri: String?): BackupDestination = when {
        folderUri == null -> BackupDestination.NOT_CHOSEN
        folderUri in granted -> BackupDestination.READY
        else -> BackupDestination.LOST
    }

    override fun adopt(folderUri: String): String? {
        if (refuses) return null
        granted += folderUri
        return "Drive"
    }

    override fun release(folderUri: String) {
        granted -= folderUri
        released += folderUri
    }

    override suspend fun list(folderUri: String): List<String>? =
        if (folderUri in granted) files.keys.toList() else null

    override suspend fun write(folderUri: String, name: String, json: String, photos: Map<String, String>): String? {
        if (!writable || folderUri !in granted) return null
        files[name] = json
        return name
    }

    override suspend fun delete(folderUri: String, name: String) {
        files.remove(name)
    }
}

class FakeAutoBackupStore(initial: AutoBackupRecord = AutoBackupRecord()) : AutoBackupStore {
    private val state = MutableStateFlow(initial)
    override val record: StateFlow<AutoBackupRecord> = state
    override fun update(transform: (AutoBackupRecord) -> AutoBackupRecord) = state.update(transform)
}

class FakeAutoBackupScheduler : AutoBackupScheduler {
    var periodic = 0
    var afterLeaving = 0
    var now = 0
    override fun startPeriodic() { periodic++ }
    override fun afterLeaving() { afterLeaving++ }
    override fun now() { now++ }
}

/** An [AutoBackup] over the fakes above, with a settable clock. */
class AutoBackupFixture(record: AutoBackupRecord = AutoBackupRecord()) {
    val repository = FakeBackupRepository()
    val folder = FakeBackupFolder()
    val store = FakeAutoBackupStore(record)
    val scheduler = FakeAutoBackupScheduler()
    var time = 1_000_000_000_000L
    val clock = Clock { time }
    val autoBackup = AutoBackup(repository, folder, store, clock, scheduler)

    /** A folder chosen and granted, as after the picker. */
    fun withFolder(uri: String = FOLDER): AutoBackupFixture {
        folder.granted += uri
        store.update { it.copy(folderUri = uri, folderName = "Drive") }
        return this
    }

    companion object {
        const val FOLDER = "content://drive/tree/recipes"
    }
}
