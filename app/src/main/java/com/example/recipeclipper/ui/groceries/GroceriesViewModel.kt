package com.example.recipeclipper.ui.groceries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.model.DecisionCandidates
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.GroceryDecisions
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.GroceryShareText
import com.example.recipeclipper.data.model.IngredientName
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * What the undo snackbar says was removed: one row's [label], or the checked items ([label]
 * null), whether "Done shopping" also changed the pantry ([putAway]), or the whole list ([all], #219).
 */
data class RemovedGroceries(val id: Long, val label: String?, val putAway: Boolean = false, val all: Boolean = false)

/**
 * One thing to put away after shopping (#146): the pantry item it restocks ([trackedId]), or a
 * new one named [name]. [key] tells them apart in [PutAwaySheet.ticked].
 */
data class PutAwayItem(val key: String, val name: String, val language: String, val aisle: Aisle, val trackedId: Long?)

/**
 * The "Done shopping" sheet (#146): the ticked items the pantry can hold, and which of them go
 * in. What the pantry already tracks starts ticked; the rest doesn't.
 */
data class PutAwaySheet(val items: List<PutAwayItem>, val ticked: Set<String>)

/**
 * [sections] is null until the list has loaded. [draft] is the "Add an item" field. [moving]
 * is the row whose aisle is being chosen. [putAway] is the open "Done shopping" sheet.
 * [recipeTitles] names the recipes items came from, for "Send list" (#149). [confirmClearAll] is
 * the number of rows "Clear the whole list" asks about (#219), while its dialog is open.
 */
data class GroceriesUiState(
    val sections: List<GroceryCombiner.Section>? = null,
    val draft: String = "",
    val moving: GroceryCombiner.Row? = null,
    val removed: RemovedGroceries? = null,
    val putAway: PutAwaySheet? = null,
    val recipeTitles: Map<Long, String> = emptyMap(),
    val confirmClearAll: Int? = null
) {
    val hasChecked: Boolean get() = sections.orEmpty().any { s -> s.rows.any { r -> r.items.any { it.checked } } }
    val isEmpty: Boolean get() = sections?.isEmpty() == true
    /** Anything on the list: what "Clear the whole list" needs (#219). */
    val hasItems: Boolean get() = !sections.isNullOrEmpty()
    /** Something is left to buy: what "Send as file" sends (#149). */
    val hasUnchecked: Boolean get() = sections.orEmpty().any { s -> s.rows.any { r -> r.items.any { !it.checked } } }
}

/**
 * The Groceries tab (#50): the list grouped by aisle, with lines naming the same ingredient
 * together (and added up when that's exact, [GroceryCombiner]). Everything is written as it
 * happens. A tick only ticks (#146): "Done shopping" puts what was bought in the pantry and
 * clears the ticked items in one step. A delete or "Done shopping" can be undone from the
 * snackbar, and so can the menu's "Clear ticked items" and "Clear the whole list" (#219).
 *
 * **Ticks stay put** (#219): within a visit, ticking or unticking never re-sorts the list. The
 * order is worked out with each item's tick as it was when the order was last worked out
 * ([orderTicks]), and shown with its tick now. It's worked out afresh when anything but a tick
 * changes (an item added, removed, cleared, or moved to another aisle, here or elsewhere) and
 * when the screen is left ([onLeave]), so ticked rows sink to the bottom of their aisle next time.
 *
 * A typed item has no recipe, so it's read with the phone's language when the app has words
 * for it, else English: the one place the phone's language picks the words, since the person
 * typing it is the only source of it.
 */
@HiltViewModel
class GroceriesViewModel @Inject constructor(
    private val repository: GroceryRepository,
    private val pantry: PantryRepository,
    private val calendar: PlanCalendar,
    // The model's aisles for what the keyword table puts in Other (#104); none without it.
    private val decisions: DecisionRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceriesUiState())
    val uiState: StateFlow<GroceriesUiState> = _uiState.asStateFlow()

    private var undo: Undo? = null
    private var removals = 0L
    private var pantryItems: List<PantryItem> = emptyList()

    /** What the snackbar's Undo puts back: the list's items and, after "Done shopping", the pantry. */
    private class Undo(
        val groceries: GroceryRepository.DeletedItems?,
        val restocked: PantryRepository.Snapshot? = null,
        val added: List<Long> = emptyList()
    )

    // Declared before init: a list that is already there is collected during construction.
    private var latestItems: List<GroceryItem> = emptyList()
    private var latestDecisions: Decisions = Decisions.NONE

    // The visit's order (#219): each item's tick when the order was last worked out, and the list
    // then with its ticks left out; null until worked out, and after the screen is left.
    private var orderTicks: Map<Long, Boolean>? = null
    private var orderShape: List<GroceryItem>? = null

    // Grocery questions and items already asked about in this visit, so each is asked once.
    private val askedGrocery = mutableSetOf<DecisionQuestion>()
    private val askedAisles = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            pantry.observeItems().collect { pantryItems = it }
        }
        viewModelScope.launch {
            repository.observeRecipeTitles().collect { titles -> _uiState.update { it.copy(recipeTitles = titles) } }
        }
        viewModelScope.launch {
            val answers = decisions?.observe() ?: flowOf(Decisions.NONE)
            repository.observeItems().combine(answers) { items, d -> items to d }.collect { (items, d) ->
                latestItems = items
                latestDecisions = d
                show()
                askAisles(items)
                askGroceryQuestions(items, d)
            }
        }
    }

    /** Lays the list out in the visit's order, working it out afresh when more than a tick changed. */
    private fun show() {
        val items = latestItems
        val shape = items.map { if (it.checked) it.copy(checked = false) else it }
        val ticks = orderTicks?.takeIf { shape == orderShape } ?: items.associate { it.id to it.checked }.also {
            orderTicks = it
            orderShape = shape
        }
        val sections = GroceriesOrder.sections(items, latestDecisions, ticks)
        _uiState.update { state ->
            // A row being moved that has since gone closes the aisle picker.
            val moving = state.moving?.takeIf { row -> row.items.all { i -> items.any { it.id == i.id } } }
            state.copy(sections = sections, moving = moving)
        }
    }

    /**
     * The screen was left (not rotated, #219): the next visit tidies the list, ticked rows at the
     * bottom of their aisle. Done now, while nothing is on screen to jump.
     */
    fun onLeave() {
        orderTicks = null
        if (_uiState.value.sections != null) show()
    }

    /**
     * Asks the model about close names and trailing text (#99), in the background. The list
     * shows today's grouping until an answer lands; then the decisions flow regroups it, and
     * a fresh answer may file a line out of Other beside its partner ([GroceryDecisions.filing]).
     */
    private fun askGroceryQuestions(items: List<GroceryItem>, current: Decisions) {
        val repo = decisions ?: return
        val unchecked = items.filter { !it.checked }
        val open = (
            GroceryDecisions.ingredientNames(unchecked) + GroceryDecisions.trailingTexts(unchecked, current) +
                GroceryDecisions.samePairs(unchecked, current)
            )
            .filter { !current.isAnswered(it) && askedGrocery.add(it) }
        if (open.isEmpty()) return
        viewModelScope.launch {
            repo.decide(open)
            val after = repo.current()
            val fresh = open.filter { after.isAnswered(it) }.toSet()
            for ((aisle, ids) in GroceryDecisions.filing(latestItems, fresh, after)) {
                repository.fileFromOther(ids, aisle)
            }
        }
    }

    /**
     * Asks the model the aisle of each item in Other whose name the keyword table doesn't know
     * (#104), in the background. Only an answer that lands now files the items, and only those
     * still in Other: an item in Other whose aisle was already decided was put there by the user.
     */
    private fun askAisles(items: List<GroceryItem>) {
        val repo = decisions ?: return
        val fresh = items.filter { it.aisle == Aisle.OTHER && !it.checked && askedAisles.add(it.id) }
        val byQuestion = fresh.mapNotNull { item -> DecisionCandidates.aisle(item.text, item.language)?.let { it to item.id } }
            .groupBy({ it.first }, { it.second })
        if (byQuestion.isEmpty()) return
        viewModelScope.launch {
            val before = repo.current()
            val open = byQuestion.filterKeys { !before.isAnswered(it) }
            if (open.isEmpty()) return@launch
            repo.decide(open.keys)
            val after = repo.current()
            for ((question, ids) in open) {
                val aisle = after.aisle(question.input, question.language) ?: continue
                repository.fileFromOther(ids, aisle)
            }
        }
    }

    fun onDraftChange(text: String) = _uiState.update { it.copy(draft = text) }

    fun onAddTyped() {
        val text = _uiState.value.draft.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(draft = "") }
        viewModelScope.launch { repository.add(listOf(NewGroceryLine(text, typedLanguage()))) }
    }

    /**
     * Ticks or unticks every line in [row]: a combined row is one thing to pick up. A tick only
     * ticks (#146): the pantry changes only at "Done shopping".
     */
    fun onToggle(row: GroceryCombiner.Row) {
        val checked = !row.items.all { it.checked }
        viewModelScope.launch { repository.setChecked(row.items.map { it.id }, checked) }
    }

    fun onMoveStart(row: GroceryCombiner.Row) = _uiState.update { it.copy(moving = row) }

    fun onMoveTo(aisle: Aisle) {
        val row = _uiState.value.moving ?: return
        _uiState.update { it.copy(moving = null) }
        viewModelScope.launch { repository.setAisle(row.items.map { it.id }, aisle) }
    }

    fun onMoveDismissed() = _uiState.update { it.copy(moving = null) }

    // --- Removing, with undo. One undo at a time: a second removal settles the first.

    fun onDelete(row: GroceryCombiner.Row, label: String) {
        viewModelScope.launch {
            val deleted = repository.delete(row.items.map { it.id }) ?: return@launch
            removed(deleted, label)
        }
    }

    /**
     * "Done shopping" (#146): opens the sheet of ticked items the pantry can hold, one per
     * ingredient, in the list's order; what it tracks starts ticked. A line the app can't name
     * isn't listed but is cleared all the same; with nothing to list, the list clears at once.
     */
    fun onDoneShopping() {
        val items = mutableListOf<PutAwayItem>()
        for (item in _uiState.value.sections.orEmpty().flatMap { s -> s.rows.flatMap { it.items } }) {
            if (!item.checked) continue
            val words = LanguageWords.forTag(item.language) ?: continue
            val name = IngredientName.of(item.text, words) ?: continue
            val tracked = PantryMatch.find(name, words.language, pantryItems)
            // One row per ingredient: "1 onion" and "2 onions" are one new item, named as first met.
            val key = tracked?.let { "pantry-${it.id}" } ?: "new-${words.language}-${IngredientName.key(name, words)}"
            if (items.none { it.key == key }) items += PutAwayItem(key, tracked?.name ?: name, words.language, item.aisle, tracked?.id)
        }
        if (items.isEmpty()) return putAway(emptyList())
        val ticked = items.filter { it.trackedId != null }.map { it.key }.toSet()
        _uiState.update { it.copy(putAway = PutAwaySheet(items, ticked)) }
    }

    fun onPutAwayToggle(key: String) = _uiState.update { state ->
        val sheet = state.putAway ?: return@update state
        state.copy(putAway = sheet.copy(ticked = if (key in sheet.ticked) sheet.ticked - key else sheet.ticked + key))
    }

    fun onPutAwayDismissed() = _uiState.update { it.copy(putAway = null) }

    /** The sheet's one button: the ticked items go in the pantry, and every ticked line leaves the list. */
    fun onPutAwayConfirm() {
        val sheet = _uiState.value.putAway ?: return
        _uiState.update { it.copy(putAway = null) }
        putAway(sheet.items.filter { it.key in sheet.ticked })
    }

    /** Restocks or adds [items], bought today, then clears every ticked line: one undo for it all. */
    private fun putAway(items: List<PutAwayItem>) {
        val today = calendar.today()
        viewModelScope.launch {
            val restock = items.mapNotNull { it.trackedId }
            val restocked = if (restock.isEmpty()) null else pantry.snapshot(restock).also { pantry.restock(restock, today) }
            val added = items.filter { it.trackedId == null }
                .mapNotNull { pantry.add(NewPantryItem(it.name, it.language, it.aisle, purchasedDay = today)) }
            val cleared = repository.clearChecked()
            if (cleared == null && restocked == null && added.isEmpty()) return@launch
            undo = Undo(cleared, restocked, added)
            _uiState.update { it.copy(removed = RemovedGroceries(++removals, null, putAway = items.isNotEmpty())) }
        }
    }

    /**
     * "Clear ticked items" (#219): every ticked line leaves the list at once, with no "Done
     * shopping" sheet, so the pantry is untouched. Undo puts them back.
     */
    fun onClearTicked() {
        viewModelScope.launch {
            val cleared = repository.clearChecked() ?: return@launch
            removed(cleared, null)
        }
    }

    /** "Clear the whole list" (#219) asks first, naming how many rows would go. */
    fun onClearAll() {
        val rows = _uiState.value.sections.orEmpty().sumOf { it.rows.size }
        if (rows > 0) _uiState.update { it.copy(confirmClearAll = rows) }
    }

    fun onClearAllDismissed() = _uiState.update { it.copy(confirmClearAll = null) }

    /** The dialog's Clear: every line, ticked or not, leaves the list; the pantry is untouched. Undo puts them back. */
    fun onClearAllConfirm() {
        if (_uiState.value.confirmClearAll == null) return
        _uiState.update { it.copy(confirmClearAll = null) }
        val ids = latestItems.map { it.id }
        viewModelScope.launch {
            val deleted = repository.delete(ids) ?: return@launch
            removed(deleted, null, all = true)
        }
    }

    private fun removed(deleted: GroceryRepository.DeletedItems, label: String?, all: Boolean = false) {
        undo = Undo(deleted)
        _uiState.update { it.copy(removed = RemovedGroceries(++removals, label, all = all)) }
    }

    /** Puts back what the last removal took: the items and, after "Done shopping", the pantry as it was. */
    fun onUndoRemove() {
        val last = undo ?: return
        undo = null
        _uiState.update { it.copy(removed = null) }
        viewModelScope.launch {
            last.groceries?.let { repository.restore(it) }
            last.restocked?.let { pantry.restore(it) }
            last.added.forEach { pantry.delete(it) }
        }
    }

    /** The snackbar timed out or was dismissed: the removal stands. */
    fun onSnackbarDismissed() {
        undo = null
        _uiState.update { it.copy(removed = null) }
    }

    /**
     * "Send list" (#149): every unticked item as plain text for the share sheet, each naming the
     * recipes it's for; null when there's nothing left to buy.
     */
    fun shareText(title: String, aisleName: (Aisle) -> String): String? {
        val state = _uiState.value
        val sections = state.sections.orEmpty()
        if (sections.none { s -> s.rows.any { r -> r.items.none { it.checked } } }) return null
        return GroceryShareText.format(sections, title, state.recipeTitles, aisleName)
    }
}

/**
 * The visit's order (#219): [GroceryCombiner.sections] laid out as if each item were ticked as in
 * [ticks] (an item not in it, as it is), then shown with every item's tick as it is now. A row
 * ticks all its lines, so its lines share one tick and it stays one row.
 */
internal object GroceriesOrder {
    fun sections(items: List<GroceryItem>, decisions: Decisions, ticks: Map<Long, Boolean>): List<GroceryCombiner.Section> {
        val now = items.associate { it.id to it.checked }
        val asWas = items.map { item -> ticks[item.id]?.let { if (it == item.checked) item else item.copy(checked = it) } ?: item }
        fun GroceryItem.current() = now[id]?.let { if (it == checked) this else copy(checked = it) } ?: this
        return GroceryCombiner.sections(asWas, decisions).map { section ->
            section.copy(
                rows = section.rows.map { row ->
                    when (row) {
                        is GroceryCombiner.Row.Single -> GroceryCombiner.Row.Single(row.item.current())
                        is GroceryCombiner.Row.Combined -> row.copy(items = row.items.map { it.current() })
                        is GroceryCombiner.Row.Together -> row.copy(items = row.items.map { it.current() })
                    }
                }
            )
        }
    }
}

/**
 * The language a typed item (groceries, pantry) is read with: the phone's when the app has words
 * for it, else English. The one place the phone's language picks the words, since the person
 * typing is the only source.
 */
internal fun typedLanguage(): String =
    LanguageWords.forTag(Locale.getDefault().language)?.language ?: LanguageWords.ENGLISH.language
