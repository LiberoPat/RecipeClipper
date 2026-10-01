package com.example.recipeclipper.ui.sharefile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.ShareFileRepository
import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupJson
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.PantryDestination
import com.example.recipeclipper.data.backup.ShareChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shared file opened with the app (#149, phase 2), waiting for the sheet: MainActivity puts its
 * URI here and [ReceiveFileViewModel] takes it. In memory only, like [com.example.recipeclipper.ui.groceries.ReceivedListInbox].
 */
@Singleton
class ReceivedFileInbox @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(uri: String) {
        _pending.value = uri
    }

    /** The waiting URI, once: taking it empties the inbox. */
    fun take(): String? = _pending.getAndUpdate { null }
}

/** One row of the sheet: its key ("r:", "g:" or "p:" and the file id), its text and a quieter line. */
data class ReceivedRow(val key: String, val text: String, val detail: String? = null)

/** Where the added things went, so the app can show them. */
enum class ReceivedWhere { RECIPES, GROCERIES, PANTRY }

/**
 * The "Add from a shared file" sheet. [open] while it shows; [error] when the file couldn't be
 * read or saved. Every row starts ticked. [added] is set once the ticked ones are in, and the sheet
 * closes on it, unless [skippedFree] says the free library (#107) left some recipes out: then it
 * stays open to say so until Done.
 */
data class ReceiveFileUiState(
    val open: Boolean = false,
    val error: BackupError? = null,
    val recipes: List<ReceivedRow> = emptyList(),
    val groceries: List<ReceivedRow> = emptyList(),
    val pantry: List<ReceivedRow> = emptyList(),
    val unticked: Set<String> = emptySet(),
    val pantryTo: PantryDestination = PantryDestination.PANTRY,
    val adding: Boolean = false,
    val added: ReceivedWhere? = null,
    /** How many recipes the free library left out, and its size. */
    val skippedFree: Pair<Int, Int>? = null
) {
    val isEmpty: Boolean get() = recipes.isEmpty() && groceries.isEmpty() && pantry.isEmpty()
    val tickedCount: Int get() = (recipes + groceries + pantry).count { it.key !in unticked }
}

/**
 * Backs the sheet a shared file opens (#149, phase 2): what's inside, each part ticked, and one
 * Add. Recipes come in as an import would (merged by cleaned link, never replacing one here);
 * grocery items go on the grocery list, each naming its recipe; pantry items go to the Pantry or
 * onto the grocery list, the receiver's choice.
 */
@HiltViewModel
class ReceiveFileViewModel @Inject constructor(
    private val files: BackupFiles,
    private val share: ShareFileRepository,
    inbox: ReceivedFileInbox
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiveFileUiState())
    val uiState: StateFlow<ReceiveFileUiState> = _uiState.asStateFlow()

    private var file: Backup? = null

    init {
        viewModelScope.launch {
            inbox.pending.filterNotNull().collect { inbox.take()?.let(::open) }
        }
    }

    /** Reads the file at [uri] and opens the sheet on it (or on why it can't be read). */
    fun open(uri: String) {
        file = null
        viewModelScope.launch {
            val decoded = when (val read = files.read(uri)) {
                is BackupResult.Failure -> read
                is BackupResult.Success -> BackupJson.decode(read.value.json)
            }
            _uiState.value = when (decoded) {
                is BackupResult.Failure -> ReceiveFileUiState(open = true, error = decoded.error)
                is BackupResult.Success -> rows(decoded.value).also { file = decoded.value }
            }
        }
    }

    fun onToggle(key: String) = _uiState.update {
        it.copy(unticked = if (key in it.unticked) it.unticked - key else it.unticked + key)
    }

    fun onPantryTo(destination: PantryDestination) = _uiState.update { it.copy(pantryTo = destination) }

    fun onAdd() {
        val state = _uiState.value
        val file = file ?: return
        if (state.adding || state.added != null || state.tickedCount == 0) return
        fun ticked(rows: List<ReceivedRow>) = rows.filter { it.key !in state.unticked }.mapTo(HashSet()) { it.key.drop(2) }
        val choice = ShareChoice(ticked(state.recipes), ticked(state.groceries), ticked(state.pantry), state.pantryTo)
        val where = when {
            choice.groceryIds.isNotEmpty() -> ReceivedWhere.GROCERIES
            choice.pantryIds.isNotEmpty() && choice.pantryTo == PantryDestination.GROCERIES -> ReceivedWhere.GROCERIES
            choice.pantryIds.isNotEmpty() -> ReceivedWhere.PANTRY
            else -> ReceivedWhere.RECIPES
        }
        _uiState.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            when (val result = share.receive(file, choice)) {
                is BackupResult.Failure -> _uiState.update { it.copy(adding = false, error = result.error) }
                is BackupResult.Success -> {
                    val summary = result.value
                    val limit = summary.freeLimit
                    _uiState.update {
                        it.copy(
                            adding = false,
                            added = where,
                            skippedFree = if (limit != null && summary.recipesSkipped > 0) summary.recipesSkipped to limit else null
                        )
                    }
                }
            }
        }
    }

    /** Closed, by the user or once the things were added and shown. */
    fun onDismiss() {
        file = null
        _uiState.value = ReceiveFileUiState()
    }

    private fun rows(file: Backup): ReceiveFileUiState {
        val titles = file.recipes.associate { it.id to it.title }
        return ReceiveFileUiState(
            open = true,
            recipes = file.recipes.map { ReceivedRow("r:${it.id}", it.title) },
            groceries = file.groceries.map {
                ReceivedRow("g:${it.id}", it.text, it.recipeId?.let(titles::get))
            },
            pantry = file.pantry.map {
                ReceivedRow("p:${it.id}", it.name, it.quantity)
            }
        )
    }
}
