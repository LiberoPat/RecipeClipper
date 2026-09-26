package com.example.recipeclipper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AutoBackup
import com.example.recipeclipper.data.AutoBackupState
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.backup.AutoBackupPolicy
import com.example.recipeclipper.data.importFile
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.settings.BackupStatus
import com.example.recipeclipper.ui.settings.toStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [loaded] is false until the database has answered once, so the screen doesn't flash its
 * "nothing here yet" hint at someone who has plenty of history.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val urlInput: String = "",
    val urlError: Boolean = false,
    val continueCooking: RecipeSummary? = null,
    val recent: List<RecipeSummary> = emptyList(),
    /** "Restore from a backup file" (#150): offered on an empty library; its outcome shows under it. */
    val restore: BackupStatus = BackupStatus.Idle,
    /** The one-time "Keep a backup copy?" card (#150): a recipe to lose, and no folder yet. */
    val offersBackupFolder: Boolean = false
) {
    val libraryEmpty: Boolean get() = loaded && continueCooking == null

    /** The restore row shows on an empty library, and stays to say how the restore went. */
    val showsRestore: Boolean get() = libraryEmpty || restore != BackupStatus.Idle
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: RecipeRepository,
    // Restore (#150): the same file reading and merge as Settings' Import. Null in tests that
    // don't care, which then offer no restore.
    private val backups: BackupRepository? = null,
    private val files: BackupFiles? = null,
    // The automatic backup copy (#150), for the one-time folder card; null offers none.
    private val autoBackup: AutoBackup? = null
) : ViewModel() {

    private val input = MutableStateFlow("")
    private val inputError = MutableStateFlow(false)
    private val restore = MutableStateFlow<BackupStatus>(BackupStatus.Idle)

    private val backupState: Flow<AutoBackupState?> =
        autoBackup?.state ?: flowOf(null)

    /**
     * The most recent recipe is the "continue cooking" card; the five before it are "recent".
     *
     * There is deliberately no "Saved" section. Home used to carry one, fed by a
     * `observeRecentlySaved` query, but it showed the same recipes as "Recently viewed" —
     * saving something usually means you just opened it — and Lists already answers "what
     * have I kept?" properly. The duplication was visible on screen and also crashed the
     * LazyColumn, since one recipe rendered in two sections under one key.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        combine(input, inputError, ::Pair),
        repository.observeRecent(RECENT_COUNT + 1),
        restore,
        backupState
    ) { (url, error), recent, restoring, backup ->
        HomeUiState(
            loaded = true,
            urlInput = url,
            urlError = error,
            continueCooking = recent.firstOrNull(),
            recent = recent.drop(1),
            restore = if (backups != null) restoring else BackupStatus.Idle,
            offersBackupFolder = backup != null &&
                AutoBackupPolicy.offersFolderPrompt(backup.record, backup.destination, libraryEmpty = recent.isEmpty())
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Whether "Restore from a backup file" can be offered at all. */
    val canRestore: Boolean get() = backups != null && files != null

    fun onUrlChange(value: String) {
        input.value = value
        inputError.value = false
    }

    /** The link to open, or null (and an error shown) if what was typed isn't a link. */
    fun onGo(): String? {
        val url = UrlInput.normalize(input.value)
        inputError.update { url == null }
        if (url != null) input.value = ""
        return url
    }

    /** "Restore from a backup file" (#150): the file the user picked, merged in like Import. */
    fun onRestorePicked(uri: String) {
        val backups = backups ?: return
        val files = files ?: return
        if (restore.value == BackupStatus.Importing) return
        restore.value = BackupStatus.Importing
        viewModelScope.launch { restore.value = backups.importFile(files, uri).toStatus() }
    }

    /** The folder card's "Choose a folder" came back with a folder (null: the picker was cancelled). */
    fun onBackupFolderPicked(uri: String?) {
        if (uri != null) autoBackup?.chooseFolder(uri)
    }

    /** The folder card's "Not now": it doesn't come back; Settings still offers the folder. */
    fun onBackupPromptDismissed() {
        autoBackup?.dismissFolderPrompt()
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
