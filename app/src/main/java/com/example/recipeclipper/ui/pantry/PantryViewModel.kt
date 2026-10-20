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

    /** The selection's Remove took [count] items out of the pantry (#258): offers Undo. */
    data class Removed(override val id: Long, val count: Int) : PantryMessage()

    /** The selection's Mark run out ran [count] items out (#258): offers Undo. */
    data class MarkedRunOut(override val id: Long, val count: Int) : PantryMessage()
}

/**
 * Selecting several items (#258). [ids] are checked. While a drag is under way ([anchor] set),
 * every row from [anchor] to [current], in the screen's order, is checked ([adding], the drag
 * began on an unchecked row) or unchecked, on top of [base], the selection when the drag began:
 * dragging back over rows gives them back their state, as in Google Photos.
 */
data class PantrySelection(
    val ids: Set<Long> = emptySet(),
    val anchor: Long? = null,
    val current: Long? = null,
    val base: Set<Long> = emptySet(),
    val adding: Boolean = true
)

/**
 * One of the three counts under the title (#286): how many of the rows shown are [stock] (in
 * stock, running low with its Low tag, or in the Run out section), and [firstId], the first of
 * them top to bottom, where a tap scrolls; null when there are none.
 */
data class PantryCount(val stock: PantryStock, val count: Int, val firstId: Long?)

/**
 * [sections] is null until the pantry has loaded; [hasItems] says whether it holds anything at
 * all (a search can find nothing in a full pantry), and [hasInStock] whether anything is in
 * stock, which is what "Send list" and "Send as file" send (#149). [onList] holds the items
 * that are on the grocery list, unticked ([PantryList.onListLines]), in any stock: their rows show
 * the basket Groceries tag (#146).
 * [hasRunOut] says whether anything has run out, what "Clear run-out items" needs, and
 * [confirmClearRunOut] is how many items its dialog asks about, while it's open (#194).
 * [counts] are In stock, Running low and Run out, counting what the list shows (a search
 * narrows them too), or empty while the pantry is, which hides them (#286).
 */
data class PantryUiState(
    val sections: List<PantrySection>? = null,
    val counts: List<PantryCount> = emptyList(),
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
    val confirmClearRunOut: Int? = null,
    // Null while not selecting (#258).
    val selection: PantrySelection? = null
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

    private fun PantryUiState.arranged(): PantryUiState {
        val sections = PantryList.arrange(items, query, sort)
        // What the search hides, or what has gone, is no longer selected.
        val rows = sections.flatMap { it.items }
        val shown = rows.map { it.id }.toSet()
        return copy(
            sections = sections,
            counts = if (items.isEmpty()) emptyList() else PantryStock.entries.map { stock ->
                val those = rows.filter { it.stock == stock }
                PantryCount(stock, those.size, those.firstOrNull()?.id)
            },
            selection = selection?.let { it.copy(ids = it.ids intersect shown, base = it.base intersect shown) }
        )
    }

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

    // --- Selecting several items (#258)

    /** The rows' ids, top to bottom, as the screen shows them: what a drag's range runs along. */
    private fun PantryUiState.order(): List<Long> = sections.orEmpty().flatMap { section -> section.items.map { it.id } }

    private fun PantryUiState.updateSelection(change: (PantrySelection) -> PantrySelection): PantryUiState =
        selection?.let { copy(selection = change(it)) } ?: this

    /** The ⋮ menu's "Select items": selecting, with nothing checked yet. */
    fun onStartSelecting() = _uiState.update { it.copy(selection = it.selection ?: PantrySelection()) }

    /** A touch and hold on [id]'s row: starts selecting with it checked, and a drag anchored there. */
    fun onLongPress(id: Long) {
        onStartSelecting()
        onDragStart(id)
    }

    /** The row's Select action (TalkBack, VoiceOver): starts selecting with [id] checked, or checks it. */
    fun onSelect(id: Long) = _uiState.update { state ->
        val selection = state.selection ?: PantrySelection()
        state.copy(selection = selection.copy(ids = selection.ids + id))
    }

    /** A tap on a row, or its checkbox, while selecting. */
    fun onToggle(id: Long) = _uiState.update { state ->
        state.updateSelection { it.copy(ids = if (id in it.ids) it.ids - id else it.ids + id) }
    }

    /** A drag from [id]'s checkbox (or after a touch and hold): it checks, or unchecks if [id] was checked. */
    fun onDragStart(id: Long) = _uiState.update { state ->
        state.updateSelection { it.copy(anchor = id, current = id, base = it.ids, adding = id !in it.ids).ranged(state.order()) }
    }

    /** The drag has reached [id]'s row: everything from the anchor to it takes the drag's state. */
    fun onDragTo(id: Long) = _uiState.update { state ->
        state.updateSelection { if (it.anchor == null || it.current == id) it else it.copy(current = id).ranged(state.order()) }
    }

    fun onDragEnd() = _uiState.update { state ->
        state.updateSelection { it.copy(anchor = null, current = null, base = emptySet()) }
    }

    private fun PantrySelection.ranged(order: List<Long>): PantrySelection {
        val from = order.indexOf(anchor)
        val to = order.indexOf(current)
        if (from < 0 || to < 0) return this
        val range = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        return copy(ids = if (adding) base + range else base - range)
    }

    /** Checks every row shown. */
    fun onSelectAll() = _uiState.update { state -> state.updateSelection { it.copy(ids = state.order().toSet()) } }

    /** A section heading's "Select all": checks that section's rows too. */
    fun onSelectSection(section: PantrySection) = _uiState.update { state ->
        state.updateSelection { selection -> selection.copy(ids = selection.ids + section.items.map { it.id }) }
    }

    /** ✕ or Back. */
    fun onExitSelection() = _uiState.update { it.copy(selection = null) }

    /** Remove: every checked item leaves the pantry in one write, with one Undo for them all. */
    fun onRemoveSelected() {
        val ids = _uiState.value.selection?.ids.orEmpty()
        if (ids.isEmpty()) return
        _uiState.update { it.copy(selection = null) }
        viewModelScope.launch {
            val gone = pantry.delete(ids.toList()) ?: return@launch
            undo = Undo.Pantry(gone)
            _uiState.update { it.copy(message = PantryMessage.Removed(++messages, gone.entities.size)) }
        }
    }

    /**
     * Mark run out: every checked item still in stock runs out, as a swipe would (its name goes
     * on the grocery list unless it's there), with one Undo that puts their stock back. The
     * grocery lines stay, as they do when an item is restocked.
     */
    fun onMarkRunOutSelected() {
        val ids = _uiState.value.selection?.ids.orEmpty()
        if (ids.isEmpty()) return
        _uiState.update { it.copy(selection = null) }
        val changing = items.filter { it.id in ids && it.inStock }
        if (changing.isEmpty()) return
        viewModelScope.launch {
            val before = pantry.snapshot(changing.map { it.id })
            pantry.setStock(changing.map { it.id }, PantryStock.RUN_OUT)
            val missing = changing.filter { linesFor(it).isEmpty() }
            if (missing.isNotEmpty()) groceries.add(missing.map { NewGroceryLine(it.name, it.language) })
            undo = Undo.Pantry(before)
            _uiState.update { it.copy(message = PantryMessage.MarkedRunOut(++messages, changing.size)) }
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
