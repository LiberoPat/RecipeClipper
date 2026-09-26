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

/** What the undo snackbar says was removed: one row's [label], or the checked items ([label] null). */
data class RemovedGroceries(val id: Long, val label: String?)

/**
 * What ticking a line off did to the pantry (#51), for the snackbar. [id] tells two apart.
 */
sealed class PantryOffer {
    abstract val id: Long
    abstract val name: String

    /** A tracked item was out and is back in stock: on by default, with Undo. */
    data class Restocked(override val id: Long, override val name: String) : PantryOffer()

    /** Not in the pantry yet: "Add to pantry" is offered, not done. */
    data class Offer(override val id: Long, override val name: String, val item: NewPantryItem) : PantryOffer()
}

/**
 * [sections] is null until the list has loaded. [draft] is the "Add an item" field. [moving]
 * is the row whose aisle is being chosen.
 */
data class GroceriesUiState(
    val sections: List<GroceryCombiner.Section>? = null,
    val draft: String = "",
    val moving: GroceryCombiner.Row? = null,
    val removed: RemovedGroceries? = null,
    val pantryOffer: PantryOffer? = null
) {
    val hasChecked: Boolean get() = sections.orEmpty().any { s -> s.rows.any { r -> r.items.any { it.checked } } }
    val isEmpty: Boolean get() = sections?.isEmpty() == true
}

/**
 * The Groceries tab (#50): the list grouped by aisle, with lines naming the same ingredient
 * together (and added up when that's exact, [GroceryCombiner]). Everything is written as it
 * happens; a delete or "Clear checked" can be undone from the snackbar.
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

    private var removedItems: GroceryRepository.DeletedItems? = null
    private var removals = 0L
    private var pantryItems: List<PantryItem> = emptyList()
    private var restocked: PantryRepository.Snapshot? = null
    private var offers = 0L

    init {
        viewModelScope.launch {
            pantry.observeItems().collect { pantryItems = it }
        }
        viewModelScope.launch {
            val answers = decisions?.observe() ?: flowOf(Decisions.NONE)
            repository.observeItems().combine(answers) { items, d -> items to d }.collect { (items, d) ->
                latestItems = items
                val sections = GroceryCombiner.sections(items, d)
                _uiState.update { state ->
                    // A row being moved that has since gone closes the aisle picker.
                    val moving = state.moving?.takeIf { row -> row.items.all { i -> items.any { it.id == i.id } } }
                    state.copy(sections = sections, moving = moving)
                }
                askAisles(items)
                askGroceryQuestions(items, d)
            }
        }
    }

    private var latestItems: List<GroceryItem> = emptyList()

    // Grocery questions already asked in this visit, so each is asked once.
    private val askedGrocery = mutableSetOf<DecisionQuestion>()

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

    // Items already asked about in this visit, so each is asked once.
    private val askedAisles = mutableSetOf<Long>()

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
     * Ticks or unticks every line in [row]: a combined row is one thing to pick up. Ticking one
     * off feeds the pantry (#51): an item the pantry tracks that was out is back in stock at once
     * (undoable); one it doesn't track is offered, never added unasked. A line the app can't
     * name is left alone.
     */
    fun onToggle(row: GroceryCombiner.Row) {
        val checked = !row.items.all { it.checked }
        viewModelScope.launch {
            repository.setChecked(row.items.map { it.id }, checked)
            if (checked) toPantry(row.items.first())
        }
    }

    private suspend fun toPantry(item: GroceryItem) {
        val words = LanguageWords.forTag(item.language) ?: return
        val name = IngredientName.of(item.text, words) ?: return
        val tracked = PantryMatch.find(name, words.language, pantryItems)
        val offer = when {
            tracked == null -> PantryOffer.Offer(
                ++offers, name, NewPantryItem(name, words.language, item.aisle, purchasedDay = calendar.today())
            )
            tracked.inStock || tracked.alwaysHave -> return
            else -> {
                restocked = pantry.snapshot(listOf(tracked.id))
                pantry.restock(listOf(tracked.id), calendar.today())
                PantryOffer.Restocked(++offers, tracked.name)
            }
        }
        _uiState.update { it.copy(pantryOffer = offer) }
    }

    fun onAddToPantry() {
        val offer = _uiState.value.pantryOffer as? PantryOffer.Offer ?: return
        _uiState.update { it.copy(pantryOffer = null) }
        viewModelScope.launch { pantry.add(offer.item) }
    }

    fun onUndoRestock() {
        val snapshot = restocked ?: return
        restocked = null
        _uiState.update { it.copy(pantryOffer = null) }
        viewModelScope.launch { pantry.restore(snapshot) }
    }

    /** The pantry snackbar timed out or was dismissed: what was done stands, what was offered isn't. */
    fun onPantryOfferDismissed() {
        restocked = null
        _uiState.update { it.copy(pantryOffer = null) }
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

    fun onClearChecked() {
        viewModelScope.launch {
            val deleted = repository.clearChecked() ?: return@launch
            removed(deleted, null)
        }
    }

    private fun removed(deleted: GroceryRepository.DeletedItems, label: String?) {
        removedItems = deleted
        _uiState.update { it.copy(removed = RemovedGroceries(++removals, label)) }
    }

    fun onUndoRemove() {
        val deleted = removedItems ?: return
        removedItems = null
        _uiState.update { it.copy(removed = null) }
        viewModelScope.launch { repository.restore(deleted) }
    }

    /** The snackbar timed out or was dismissed: the removal stands. */
    fun onSnackbarDismissed() {
        removedItems = null
        _uiState.update { it.copy(removed = null) }
    }

    /** The list as plain text for the share sheet; null when there's nothing left to buy. */
    fun shareText(title: String, aisleName: (Aisle) -> String): String? {
        val sections = _uiState.value.sections.orEmpty()
        if (sections.none { s -> s.rows.any { r -> r.items.none { it.checked } } }) return null
        return GroceryShareText.format(sections, title, aisleName)
    }
}

/**
 * The language a typed item (groceries, pantry) is read with: the phone's when the app has words
 * for it, else English. The one place the phone's language picks the words, since the person
 * typing is the only source.
 */
internal fun typedLanguage(): String =
    LanguageWords.forTag(Locale.getDefault().language)?.language ?: LanguageWords.ENGLISH.language
