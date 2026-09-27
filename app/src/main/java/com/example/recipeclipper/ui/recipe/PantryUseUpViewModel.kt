package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryEdit
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantryUseUp
import com.example.recipeclipper.data.model.UseUpChange
import com.example.recipeclipper.data.model.UseUpChoice
import com.example.recipeclipper.data.model.UseUpRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The end-of-cooking sheet (#147): [rows] as [PantryUseUp] worked them out; [ticked] the
 * worked-out rows to apply, by item id (all of them, to start); [choices] the asked rows'
 * answers, keep until the cook picks another.
 */
data class UseUpSheet(
    val rows: List<UseUpRow>,
    val ticked: Set<Long>,
    val choices: Map<Long, UseUpChoice> = emptyMap()
) {
    fun choice(id: Long): UseUpChoice = choices[id] ?: UseUpChoice.KEEP
}

/** [sheet] is open; [updated] is the Undo snackbar's id, after a confirm that changed something. */
data class UseUpUiState(val sheet: UseUpSheet? = null, val updated: Long? = null)

/**
 * Using up the pantry at the end of cooking (#147): the recipe screen hands over the ticked
 * lines as shown when cook mode is finished; the sheet lists what they did to the pantry, and one
 * confirm applies it, with one Undo. Nothing changes on a tick, and nothing without the confirm.
 */
@HiltViewModel
class PantryUseUpViewModel @Inject constructor(
    private val pantry: PantryRepository,
    private val groceries: GroceryRepository,
    // The model's definite "same ingredient" answers already given (#104); none without it.
    private val decisions: DecisionRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UseUpUiState())
    val uiState: StateFlow<UseUpUiState> = _uiState.asStateFlow()

    /** What Undo puts back: the pantry rows as they were, and the grocery lines added. */
    private class Undo(val pantry: PantryRepository.Snapshot, val added: List<Long>)

    private var undo: Undo? = null
    private var updates = 0L

    /** Cooking finished with [lines] ticked: the sheet opens when any of them uses the pantry. */
    fun onCookFinished(language: String?, lines: List<String>) {
        viewModelScope.launch {
            val rows = PantryUseUp.rows(lines, language, pantry.items(), decisions?.current() ?: Decisions.NONE)
            if (rows.isEmpty()) return@launch
            val ticked = rows.filter { it.change is UseUpChange.Subtract }.map { it.item.id }.toSet()
            _uiState.update { it.copy(sheet = UseUpSheet(rows, ticked)) }
        }
    }

    fun onToggle(id: Long) = _uiState.update { state ->
        val sheet = state.sheet ?: return@update state
        state.copy(sheet = sheet.copy(ticked = if (id in sheet.ticked) sheet.ticked - id else sheet.ticked + id))
    }

    fun onChoice(id: Long, choice: UseUpChoice) = _uiState.update { state ->
        val sheet = state.sheet ?: return@update state
        state.copy(sheet = sheet.copy(choices = sheet.choices + (id to choice)))
    }

    /** Dismissed: nothing changes. */
    fun onDismissed() = _uiState.update { it.copy(sheet = null) }

    /**
     * The sheet's one button. A ticked worked-out row gets its new quantity; used up, it goes out
     * of stock with no quantity (none is left to know). Running low and out put the item's name
     * on the grocery list, unless it's there already (#146's "On list").
     */
    fun onConfirm() {
        val sheet = _uiState.value.sheet ?: return
        _uiState.update { it.copy(sheet = null) }
        val quantities = mutableListOf<Pair<PantryItem, String?>>()
        val out = mutableListOf<PantryItem>()
        val onList = mutableListOf<PantryItem>()
        for (row in sheet.rows) {
            val item = row.item
            when (val change = row.change) {
                is UseUpChange.Subtract -> if (item.id in sheet.ticked) {
                    quantities += item to change.after
                    if (change.after == null) {
                        out += item
                        onList += item
                    }
                }
                UseUpChange.Ask -> when (sheet.choice(item.id)) {
                    UseUpChoice.KEEP -> Unit
                    UseUpChoice.LOW -> onList += item
                    UseUpChoice.OUT -> {
                        out += item
                        onList += item
                    }
                }
            }
        }
        val touched = (quantities.map { it.first } + onList).map { it.id }.distinct()
        if (touched.isEmpty()) return
        viewModelScope.launch {
            // Once confirmed it all lands, even if the screen is left meanwhile.
            withContext(NonCancellable) {
                val before = pantry.snapshot(touched)
                for ((item, quantity) in quantities) {
                    pantry.edit(item.id, PantryEdit(item.name, quantity, item.alwaysHave, item.expiresDay))
                }
                if (out.isNotEmpty()) pantry.setInStock(out.map { it.id }, false)
                val list = groceries.observeItems().first()
                val lines = onList.filter { PantryList.ownLines(it, list).isEmpty() }.map { NewGroceryLine(it.name, it.language) }
                val added = if (lines.isEmpty()) {
                    emptyList()
                } else {
                    groceries.add(lines)
                    val existing = list.map { it.id }.toSet()
                    groceries.observeItems().first().map { it.id }.filter { it !in existing }
                }
                undo = Undo(before, added)
                _uiState.update { it.copy(updated = ++updates) }
            }
        }
    }

    /** Puts the pantry back as it was and takes the added lines off the grocery list. */
    fun onUndo() {
        val last = undo ?: return
        undo = null
        _uiState.update { it.copy(updated = null) }
        viewModelScope.launch {
            pantry.restore(last.pantry)
            if (last.added.isNotEmpty()) groceries.delete(last.added)
        }
    }

    /** The snackbar timed out or was dismissed: the changes stand. */
    fun onUpdatedDismissed() {
        undo = null
        _uiState.update { it.copy(updated = null) }
    }
}
