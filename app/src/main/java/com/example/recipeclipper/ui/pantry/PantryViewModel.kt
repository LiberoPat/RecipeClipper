package com.example.recipeclipper.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryEdit
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantrySection
import com.example.recipeclipper.data.model.PantryShareText
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.ui.groceries.typedLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The edit sheet's fields, for item [id]. Written only on Save, except [stock]: the sheet's
 * stock control applies at once, as the row's menu and swipes do (#194).
 */
data class PantryEditing(
    val id: Long,
    val name: String,
    val quantity: String,
    val alwaysHave: Boolean,
    val expiresDay: Long?,
    val purchasedDay: Long?,
    val stock: PantryStock
)

/** The snackbar, only ever for undo (#146). [id] tells two messages about the same item apart. */
sealed class PantryMessage {
    abstract val id: Long

    /** [name] was deleted: offers Undo. */
    data class Deleted(override val id: Long, val name: String) : PantryMessage()

    /** "Clear run-out items" removed every item that had run out (#194): offers Undo. */
    data class RunOutCleared(override val id: Long) : PantryMessage()

    /** The basket tag took [count] lines off the grocery list (owner, 2026-09-29): offers Undo. */
    data class TakenOffList(override val id: Long, val count: Int) : PantryMessage()
}

/**
 * [sections] is null until the pantry has loaded; [hasItems] says whether it holds anything at
 * all (a search can find nothing in a full pantry), and [hasInStock] whether anything is in
 * stock, which is what "Send list" and "Send as file" send (#149). [onList] holds the items
 * that are on the grocery list, unticked ([PantryList.onListLines]), in any stock: their rows show
 * the basket Groceries tag (#146).
 * [hasRunOut] says whether anything has run out, what "Clear run-out items" needs, and
 * [confirmClearRunOut] is how many items its dialog asks about, while it's open (#194).
 */
data class PantryUiState(
    val sections: List<PantrySection>? = null,
    val hasItems: Boolean = false,
    val hasInStock: Boolean = false,
    val query: String = "",
    val sort: PantrySort = PantrySort.AISLE,
    val draft: String = "",
    val today: Long = 0,
    val editing: PantryEditing? = null,
    val message: PantryMessage? = null,
    val onList: Set<Long> = emptySet(),
    val hasRunOut: Boolean = false,
    val confirmClearRunOut: Int? = null
)

/**
 * The Pantry tab (#51): add by typing, search, sort by aisle or expiry, and mark each item in
 * stock, running low or run out (#194). Running low or out puts the item on the grocery list,
 * silently (#146). An item on the grocery list, its own line or any line naming it, shows the basket Groceries
 * tag, and tapping that takes those lines off. A delete, "Clear run-out items" (#194, which leaves the
 * grocery list alone) and the tag's removal can each be undone, one at a time.
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
    private var undo: Undo? = null
    private var messages = 0L

    init {
        viewModelScope.launch {
            pantry.observeItems().collect { all ->
                items = all
                _uiState.update {
                    it.copy(
                        hasItems = all.isNotEmpty(),
                        hasInStock = all.any { item -> item.inStock },
                        hasRunOut = all.any { item -> !item.inStock },
                        onList = onList()
                    ).arranged()
                }
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

    /** What the snackbar's Undo puts back: pantry items, or grocery lines. */
    private sealed class Undo {
        data class Pantry(val snapshot: PantryRepository.Snapshot) : Undo()
        data class Groceries(val lines: GroceryRepository.DeletedItems) : Undo()
    }

    /** The unticked grocery lines that are [item] itself ([PantryList.ownLines]): what running out checks for. */
    private fun linesFor(item: PantryItem): List<GroceryItem> = PantryList.ownLines(item, groceryItems)

    private fun onList(): Set<Long> =
        items.filter { PantryList.onListLines(it, groceryItems).isNotEmpty() }.map { it.id }.toSet()

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
     * A row's swipe or menu, or the sheet's control (#194). Running low and run out put the item on the grocery
     * list, silently, unless it's there already (#146); back in stock means just bought, today.
     */
    fun onSetStock(item: PantryItem, stock: PantryStock) {
        if (stock == item.stock) return
        viewModelScope.launch {
            if (stock == PantryStock.IN_STOCK) {
                pantry.restock(listOf(item.id), calendar.today())
            } else {
                pantry.setStock(listOf(item.id), stock)
                if (linesFor(item).isEmpty()) groceries.add(listOf(NewGroceryLine(item.name, item.language)))
            }
        }
    }

    /**
     * The row's basket tag, tapped: every line that puts the item on the grocery list
     * ([PantryList.onListLines], a recipe's included) leaves it, with Undo (owner, 2026-09-29).
     */
    fun onTakeOffList(item: PantryItem) {
        val ids = PantryList.onListLines(item, groceryItems).map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val gone = groceries.delete(ids) ?: return@launch
            undo = Undo.Groceries(gone)
            _uiState.update { it.copy(message = PantryMessage.TakenOffList(++messages, gone.entities.size)) }
        }
    }

    // --- The edit sheet

    fun onEdit(item: PantryItem) = _uiState.update {
        it.copy(
            editing = PantryEditing(item.id, item.name, item.quantity.orEmpty(), item.alwaysHave, item.expiresDay, item.purchasedDay, item.stock)
        )
    }

    /** The sheet's stock control: applied at once, through the same path as the row's menu and swipes. */
    fun onEditStock(stock: PantryStock) {
        val editing = _uiState.value.editing ?: return
        val item = items.firstOrNull { it.id == editing.id } ?: return
        _uiState.update { it.copy(editing = it.editing?.copy(stock = stock)) }
        onSetStock(item, stock)
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
            undo = Undo.Pantry(gone)
            _uiState.update { it.copy(message = PantryMessage.Deleted(++messages, gone.entities.first().name)) }
        }
    }

    /** "Clear run-out items" (#194) asks first, naming how many items would go, whatever the search. */
    fun onClearRunOut() {
        val count = items.count { !it.inStock }
        if (count > 0) _uiState.update { it.copy(confirmClearRunOut = count) }
    }

    fun onClearRunOutDismissed() = _uiState.update { it.copy(confirmClearRunOut = null) }

    /**
     * The dialog's Clear: every item that has run out leaves the pantry, in one write; their
     * grocery lines stay. Undo ([onUndoDelete]) puts them back exactly as they were.
     */
    fun onClearRunOutConfirm() {
        if (_uiState.value.confirmClearRunOut == null) return
        _uiState.update { it.copy(confirmClearRunOut = null) }
        viewModelScope.launch {
            val gone = pantry.deleteRunOut() ?: return@launch
            undo = Undo.Pantry(gone)
            _uiState.update { it.copy(message = PantryMessage.RunOutCleared(++messages)) }
        }
    }

    /** Undoes the last removal: a delete, "Clear run-out items", or the tag's lines. */
    fun onUndoDelete() {
        val last = undo ?: return
        undo = null
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch {
            when (last) {
                is Undo.Pantry -> pantry.restore(last.snapshot)
                is Undo.Groceries -> groceries.restore(last.lines)
            }
        }
    }

    /** The snackbar timed out or was dismissed. */
    fun onMessageDismissed() {
        if (_uiState.value.message != null) undo = null
        _uiState.update { it.copy(message = null) }
    }

    /**
     * "Send list" (#149): every in-stock item (running low included) as plain text for the share sheet, arranged as the
     * screen's sort arranges them, whatever the search; null when nothing is in stock.
     */
    fun shareText(title: String, aisleName: (Aisle) -> String): String? {
        val inStock = items.filter { it.inStock }
        if (inStock.isEmpty()) return null
        return PantryShareText.format(PantryList.arrange(inStock, "", _uiState.value.sort), title, aisleName)
    }
}
