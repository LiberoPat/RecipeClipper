package com.example.recipeclipper.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.model.CoverPhoto
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.data.model.RecipeList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [loaded] is false until the database has answered once, matching `HomeUiState`: the four
 * built-in lists always exist, so a momentary empty list would be a lie rather than a state.
 *
 * [coverPhotos] is each list's cover (#286): up to four photo URLs, most recently added first.
 * A list with none is absent and gets a plain tile.
 *
 * [draggingId] is the list the cook is holding (#381); while it is set, [lists] is in the order
 * the drag has made so far and nothing is stored yet.
 */
data class ListsUiState(
    val loaded: Boolean = false,
    val lists: List<RecipeList> = emptyList(),
    val coverPhotos: Map<Long, List<CoverPhoto>> = emptyMap(),
    /** A list with no photo shows its recipes' designed images (#403); an empty one the bookmark tile. */
    val coverPlaceholders: Map<Long, List<NoPhotoSource>> = emptyMap(),
    val creatingList: Boolean = false,
    val newListName: String = "",
    val draggingId: Long? = null
)

/**
 * A drag in flight: the list held, and every list's id in the order the drag has made.
 * [settling] is a drop whose write is done but whose new stored order has not been published yet:
 * the order stays on show so the covers don't snap back and then forward again (#421).
 */
private data class Drag(val id: Long, val order: List<Long>, val settling: Boolean = false)

/** How long a dropped order waits for the database to publish it before the stored one returns. */
private const val SETTLE_MILLIS = 1_000L

/**
 * The Lists screen: every list as a cover with its count, creating a new one, and putting the
 * covers in the cook's own order (#381). Renaming and deleting live on the list's own screen
 * rather than here, so there is one place a list is managed from — the same reason the recipe
 * screen owns deleting a recipe.
 */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: ListRepository
) : ViewModel() {

    private val creatingList = MutableStateFlow(false)
    private val newListName = MutableStateFlow("")
    private val drag = MutableStateFlow<Drag?>(null)

    /**
     * The stored order, collected here (not read back off [uiState], whose `value` is the initial
     * state while nothing collects) so a move always starts from what the database has.
     */
    private val stored = MutableStateFlow<List<RecipeList>?>(null)

    init {
        viewModelScope.launch {
            repository.observeLists().collect { lists ->
                stored.value = lists
                // The write the drop made has come back: the stored order is the one on show.
                val ids = lists.map { it.id }
                drag.update { if (it != null && it.settling && it.order == ids) null else it }
            }
        }
    }

    /** Puts the stored order back on show if the dropped one is never published (the write failed). */
    private var settleJob: Job? = null

    val uiState: StateFlow<ListsUiState> = combine(
        stored.filterNotNull(),
        combine(repository.observeCoverPhotos(), repository.observeCoverPlaceholders()) { photos, placeholders ->
            photos to placeholders
        },
        creatingList,
        newListName,
        drag
    ) { lists, (photos, placeholders), creating, name, dragging ->
        ListsUiState(
            loaded = true,
            lists = dragging?.let { lists.inOrder(it.order) } ?: lists,
            coverPhotos = photos.mapValues { it.value.take(ListDao.COVER_PHOTOS) },
            coverPlaceholders = placeholders.mapValues { it.value.take(ListDao.COVER_PHOTOS) },
            creatingList = creating,
            newListName = name,
            draggingId = dragging?.takeUnless { it.settling }?.id
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    fun onStartCreating() {
        creatingList.value = true
    }

    fun onCancelCreating() {
        creatingList.value = false
        newListName.value = ""
    }

    fun onNewListNameChange(value: String) {
        newListName.value = value
    }

    /** No recipe in hand here, so the new list starts empty. A blank name is ignored. */
    fun onCreateList() {
        val name = newListName.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.createList(name, addRecipeId = null)
            creatingList.value = false
            newListName.value = ""
        }
    }

    // --- Reordering (#381) ---

    /** The cook lifts a cover. Nothing is stored until [onDragEnd]. */
    fun onDragStart(listId: Long) {
        val lists = stored.value ?: return
        if (lists.none { it.id == listId }) return
        settleJob?.cancel()
        drag.value = Drag(listId, lists.map { it.id })
    }

    /** The held cover is over another's place: it takes that place, the ones between shift. */
    fun onDragOver(targetId: Long) {
        val current = drag.value ?: return
        val from = current.order.indexOf(current.id)
        val to = current.order.indexOf(targetId)
        if (from < 0 || to < 0 || from == to) return
        drag.value = current.copy(order = current.order.moved(from, to))
    }

    /** The cook let go: the order the drag made is stored, once. */
    fun onDragEnd() {
        val current = drag.value?.takeUnless { it.settling } ?: return
        viewModelScope.launch {
            if (current.order != stored.value?.map { it.id }) repository.reorderLists(current.order)
            // The drag may have been cancelled or replaced while the write ran.
            if (drag.value !== current) return@launch
            if (stored.value?.map { it.id } == current.order) {
                drag.value = null
            } else {
                // The write is done, its publication is on its way. If it never comes the stored
                // order goes back on show after a moment.
                val settling = current.copy(settling = true)
                drag.value = settling
                settleJob = launch {
                    delay(SETTLE_MILLIS)
                    drag.update { if (it === settling) null else it }
                }
            }
        }
    }

    /** The drag ended with no drop: the covers go back, nothing is stored. */
    fun onDragCancel() {
        settleJob?.cancel()
        drag.value = null
    }

    /** The accessible alternative to dragging: one place earlier, stored at once. */
    fun onMoveEarlier(listId: Long) = move(listId, by = -1)

    /** The accessible alternative to dragging: one place later, stored at once. */
    fun onMoveLater(listId: Long) = move(listId, by = 1)

    private fun move(listId: Long, by: Int) {
        val order = stored.value?.map { it.id } ?: return
        val from = order.indexOf(listId)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        viewModelScope.launch { repository.reorderLists(order.moved(from, to)) }
    }
}

private fun List<Long>.moved(from: Int, to: Int): List<Long> =
    toMutableList().also { it.add(to, it.removeAt(from)) }

/** [this] arranged as [order] says; a list the order does not name (just created) goes last. */
private fun List<RecipeList>.inOrder(order: List<Long>): List<RecipeList> {
    val byId = associateBy { it.id }
    return order.mapNotNull { byId[it] } + filterNot { it.id in order }
}
