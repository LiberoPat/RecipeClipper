package com.example.recipeclipper.ui.groceries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.model.IngredientName
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantryMatch
import com.example.recipeclipper.data.model.ReceivedList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text shared into the app with no recipe link but with lines (#149), waiting for the Groceries
 * tab to offer it: MainActivity puts it here and opens the tab; [ReceiveListViewModel] takes it.
 * In memory only: a list shared just before the app is killed is shared again.
 */
@Singleton
class ReceivedListInbox @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(text: String) {
        _pending.value = text
    }

    /** The waiting text, once: taking it empties the inbox. */
    fun take(): String? = _pending.getAndUpdate { null }
}

/** Where the sheet put the lines. */
enum class ReceiveTarget { GROCERIES, PANTRY }

/**
 * The "Add this list" sheet (#149). [lines] is null while it's closed, and empty when what was
 * pasted held no list. Every line starts ticked; [added] is set once the ticked ones are written,
 * and the sheet closes on it.
 */
data class ReceiveListUiState(
    val lines: List<String>? = null,
    val unticked: Set<Int> = emptySet(),
    val added: ReceiveTarget? = null
) {
    val tickedCount: Int get() = lines.orEmpty().indices.count { it !in unticked }
}

/**
 * Backs the "Add this list" sheet: a list shared in as text, or pasted, offered line by line with
 * one button for Groceries and one for the Pantry (#149). Lines are added as written, read like
 * a typed item: the phone's language, unless their words clearly say another. On the grocery
 * list they combine as any lines do ([com.example.recipeclipper.data.model.GroceryCombiner]).
 * In the pantry each is its ingredient's name (the whole line when the app can't name it), and
 * one it already tracks is put back in stock rather than added twice, as ticking a grocery line
 * off does.
 */
@HiltViewModel
class ReceiveListViewModel @Inject constructor(
    private val groceries: GroceryRepository,
    private val pantry: PantryRepository,
    private val calendar: PlanCalendar,
    private val inbox: ReceivedListInbox
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiveListUiState())
    val uiState: StateFlow<ReceiveListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            inbox.pending.filterNotNull().collect { inbox.take()?.let(::open) }
        }
    }

    /** Opens the sheet on [text]'s lines (a share, or the clipboard; null: nothing there). */
    fun open(text: String?) {
        _uiState.value = ReceiveListUiState(lines = ReceivedList.lines(text.orEmpty()))
    }

    fun onToggle(index: Int) = _uiState.update {
        it.copy(unticked = if (index in it.unticked) it.unticked - index else it.unticked + index)
    }

    fun onAddToGroceries() {
        val lines = ticked() ?: return
        val language = language(lines)
        _uiState.update { it.copy(added = ReceiveTarget.GROCERIES) }
        viewModelScope.launch { groceries.add(lines.map { NewGroceryLine(it, language) }) }
    }

    fun onAddToPantry() {
        val lines = ticked() ?: return
        val language = language(lines)
        _uiState.update { it.copy(added = ReceiveTarget.PANTRY) }
        viewModelScope.launch {
            val words = LanguageWords.forTag(language)
            val items = pantry.items()
            val today = calendar.today()
            val names = lines.map { line -> words?.let { IngredientName.of(line, it) } ?: line.trim() }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val restock = mutableListOf<Long>()
            for (name in names) {
                val tracked = if (words != null) PantryMatch.find(name, words.language, items)
                else PantryList.sameName(items, name, language)
                when {
                    tracked == null -> pantry.add(NewPantryItem(name, language, purchasedDay = today))
                    !tracked.inStock && !tracked.alwaysHave -> restock += tracked.id
                }
            }
            if (restock.isNotEmpty()) pantry.restock(restock.distinct(), today)
        }
    }

    /** Closed, by the user or once the lines are added. */
    fun onDismiss() {
        _uiState.value = ReceiveListUiState()
    }

    private fun ticked(): List<String>? {
        val state = _uiState.value
        if (state.added != null) return null
        return state.lines.orEmpty().filterIndexed { index, _ -> index !in state.unticked }.ifEmpty { null }
    }

    // As a typed item's, unless the lines' own words clearly say another language the app has.
    private fun language(lines: List<String>): String {
        val typed = typedLanguage()
        val resolved = LanguageWords.resolve(typed, null) { lines.joinToString("\n") }
        return LanguageWords.forTag(resolved)?.language ?: typed
    }
}
