package com.example.recipeclipper.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryEdit
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantrySection
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.ui.groceries.typedLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** The edit sheet's fields, for item [id]. Written only on Save. */
data class PantryEditing(
    val id: Long,
    val name: String,
    val quantity: String,
    val alwaysHave: Boolean,
    val expiresDay: Long?,
    val purchasedDay: Long?
)

/** The snackbar, only ever for undo (#146). [id] tells two messages about the same item apart. */
sealed class PantryMessage {
    abstract val id: Long

    /** [name] was deleted: offers Undo. */
    data class Deleted(override val id: Long, val name: String) : PantryMessage()
}

/**
 * [sections] is null until the pantry has loaded; [hasItems] says whether it holds anything at
 * all (a search can find nothing in a full pantry). [onList] holds the items whose name is on
 * the grocery list, unticked: their rows show "On list" (#146).
 */
data class PantryUiState(
    val sections: List<PantrySection>? = null,
    val hasItems: Boolean = false,
    val query: String = "",
    val sort: PantrySort = PantrySort.AISLE,
    val draft: String = "",
    val today: Long = 0,
    val editing: PantryEditing? = null,
    val message: PantryMessage? = null,
    val onList: Set<Long> = emptySet()
)

/**
 * The Pantry tab (#51): add by typing, search, sort by aisle or expiry, toggle in and out of
 * stock. Running out puts the item on the grocery list, silently (#146); its row then says "On
 * list", and tapping that takes it off again. A delete can be undone.
 */
@HiltViewModel
class PantryViewModel @Inject constructor(
    private val pantry: PantryRepository,
    private val groceries: GroceryRepository,
    private val calendar: PlanCalendar
) : ViewModel() {

    private val _uiState = MutableStateFlow(PantryUiState(today = calendar.today()))
    val uiState: StateFlow<PantryUiState> = _uiState.asStateFlow()

    private var items: List<PantryItem> = emptyList()
    private var groceryItems: List<GroceryItem> = emptyList()
    private var deleted: PantryRepository.Snapshot? = null
    private var messages = 0L

    init {
        viewModelScope.launch {
            pantry.observeItems().collect { all ->
                items = all
                _uiState.update { it.copy(hasItems = all.isNotEmpty(), onList = onList()).arranged() }
            }
        }
        viewModelScope.launch {
            groceries.observeItems().collect { list ->
                groceryItems = list
                _uiState.update { it.copy(onList = onList()) }
            }
        }
    }

    private fun PantryUiState.arranged() = copy(sections = PantryList.arrange(items, query, sort))

    /**
     * The unticked grocery lines that are [item] itself: its name as the pantry puts it there
     * (trimmed, case-insensitive, in its language). A recipe's "2 cups flour" isn't, so taking
     * the item off the list never loses a recipe's line.
     */
    private fun linesFor(item: PantryItem): List<GroceryItem> {
        val name = item.name.trim().lowercase(Locale.ROOT)
        return groceryItems.filter { !it.checked && it.language == item.language && it.text.trim().lowercase(Locale.ROOT) == name }
    }

    private fun onList(): Set<Long> = items.filter { linesFor(it).isNotEmpty() }.map { it.id }.toSet()

    fun onQueryChange(query: String) = _uiState.update { it.copy(query = query).arranged() }

    fun onSortChange(sort: PantrySort) = _uiState.update { it.copy(sort = sort).arranged() }

    fun onDraftChange(text: String) = _uiState.update { it.copy(draft = text) }

    /** Adds what was typed, in stock; a name already here is put back in stock instead. */
    fun onAddTyped() {
        val name = _uiState.value.draft.trim()
        if (name.isEmpty()) return
        _uiState.update { it.copy(draft = "") }
        val language = typedLanguage()
        val today = calendar.today()
        viewModelScope.launch {
            val existing = PantryList.sameName(items, name, language)
            if (existing != null) pantry.restock(listOf(existing.id), today)
            else pantry.add(NewPantryItem(name, language, purchasedDay = today))
        }
    }

    /**
     * In → out puts the item on the grocery list, silently, unless it's there already (#146);
     * out → in means just bought, today.
     */
    fun onToggleStock(item: PantryItem) {
        viewModelScope.launch {
            if (item.inStock) {
                pantry.setInStock(listOf(item.id), false)
                if (linesFor(item).isEmpty()) groceries.add(listOf(NewGroceryLine(item.name, item.language)))
            } else {
                pantry.restock(listOf(item.id), calendar.today())
            }
        }
    }

    /** The row's "On list" tag, tapped: the item's own lines leave the grocery list. No snackbar (#146). */
    fun onTakeOffList(item: PantryItem) {
        val ids = linesFor(item).map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch { groceries.delete(ids) }
    }

    // --- The edit sheet

    fun onEdit(item: PantryItem) = _uiState.update {
        it.copy(
            editing = PantryEditing(item.id, item.name, item.quantity.orEmpty(), item.alwaysHave, item.expiresDay, item.purchasedDay)
        )
    }

    fun onEditName(name: String) = _uiState.update { it.copy(editing = it.editing?.copy(name = name)) }

    fun onEditQuantity(quantity: String) = _uiState.update { it.copy(editing = it.editing?.copy(quantity = quantity)) }

    fun onEditAlwaysHave(alwaysHave: Boolean) = _uiState.update { it.copy(editing = it.editing?.copy(alwaysHave = alwaysHave)) }

    fun onEditExpiry(day: Long?) = _uiState.update { it.copy(editing = it.editing?.copy(expiresDay = day)) }

    fun onEditSave() {
        val editing = _uiState.value.editing ?: return
        if (editing.name.isBlank()) return
        _uiState.update { it.copy(editing = null) }
        viewModelScope.launch {
            pantry.edit(editing.id, PantryEdit(editing.name, editing.quantity, editing.alwaysHave, editing.expiresDay))
        }
    }

    fun onEditDismissed() = _uiState.update { it.copy(editing = null) }

    /** Deletes the item being edited. One undo at a time: a second delete settles the first. */
    fun onEditDelete() {
        val editing = _uiState.value.editing ?: return
        _uiState.update { it.copy(editing = null) }
        viewModelScope.launch {
            val gone = pantry.delete(editing.id) ?: return@launch
            deleted = gone
            _uiState.update { it.copy(message = PantryMessage.Deleted(++messages, gone.entities.first().name)) }
        }
    }

    fun onUndoDelete() {
        val gone = deleted ?: return
        deleted = null
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch { pantry.restore(gone) }
    }

    /** The snackbar timed out or was dismissed. */
    fun onMessageDismissed() {
        if (_uiState.value.message is PantryMessage.Deleted) deleted = null
        _uiState.update { it.copy(message = null) }
    }
}
