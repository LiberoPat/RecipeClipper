package com.example.recipeclipper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AutoBackup
import com.example.recipeclipper.data.AutoBackupState
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.CookedPhotoRepository
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.backup.AutoBackupPolicy
import com.example.recipeclipper.data.importFile
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.edit.PastedTextRouter
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.MealWindows
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.recipe.CookController
import com.example.recipeclipper.ui.recipe.CookSession
import com.example.recipeclipper.ui.settings.BackupStatus
import com.example.recipeclipper.ui.settings.toStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject

/**
 * [loaded] is false until the database has answered once, so the screen doesn't flash its
 * "nothing here yet" hint at someone who has plenty of history. [linkInput] is the "Paste a
 * link" dialog's text (#286, the Recipes screen's dialog), or null while it is closed.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val linkInput: String? = null,
    /** Go found nothing to open in the pasted text (#372); cleared by the next keystroke. */
    val linkNoRecipe: Boolean = false,
    /** The "Ask an AI" sheet (#351) is open. */
    val askAiOpen: Boolean = false,
    val continueCooking: RecipeSummary? = null,
    /** Where the cook stopped on [continueCooking] (#286); null when it isn't being cooked. */
    val resume: CookResume? = null,
    /**
     * Today's next meal from Week (#402), shown in the resume card's place while nothing is
     * being cooked ([resume] null); null when nothing is left to cook today.
     */
    val today: TodayCard? = null,
    /** The Recipes and Lists rows' counts (#286); null until known. */
    val recipeCount: Int? = null,
    val listCount: Int? = null,
    /** "Restore from a backup file" (#150): offered on an empty library; its outcome shows under it. */
    val restore: BackupStatus = BackupStatus.Idle,
    /** The one-time "Keep a backup copy?" card (#150): a recipe to lose, and no folder yet. */
    val offersBackupFolder: Boolean = false,
    /** The only recipe is the tour's sample (#151), which leaves the library as good as empty. */
    val onlySample: Boolean = false
) {
    val libraryEmpty: Boolean get() = loaded && (continueCooking == null || onlySample)

    /** The restore row shows on an empty library, and stays to say how the restore went. */
    val showsRestore: Boolean get() = libraryEmpty || restore != BackupStatus.Idle

    /** The dialog's Go is enabled for any text (#372): a link imports, anything else goes where a share would. */
    val canOpenLink: Boolean get() = !linkInput.isNullOrBlank()
}

/**
 * A recipe being cooked (#286): cook mode was left on, or a step timer still runs. [step] is the
 * one stopped on (1-based) of [stepCount]; [timerSecondsLeft] is the running timer that ends
 * first, recomputed from its saved deadline, or null when none runs.
 */
data class CookResume(
    val recipeId: Long,
    val step: Int,
    val stepCount: Int,
    val timerSecondsLeft: Int?
) {
    companion object {
        /** Where [recipe] stands at [now]; null when it isn't being cooked (or has no steps). */
        fun of(recipe: Recipe, now: Long): CookResume? {
            val count = recipe.instructions.size
            if (count == 0) return null
            val cook = recipe.cook
            // A finished run (every step done) is never resumed, whatever its timers (#402).
            if ((0 until count).all { it in cook.doneSteps }) return null
            val timer = cook.timers.filterKeys { it in 0 until count }.values
                .mapNotNull { it.endsAt }
                .filter { it > now }
                .minOrNull()
                ?.let { CookSession.secondsUntil(it, now) }
            if (!cook.active && timer == null) return null
            return CookResume(recipe.id, cook.currentStep.coerceIn(0, count - 1) + 1, count, timer)
        }
    }
}

/**
 * One of today's planned meals (#402): [recipeId] is null for a note, which only shows its
 * [title] (the note's text). [mealType] is the name it is planned under ("Dinner").
 */
data class TodayMeal(
    val entryId: Long,
    val mealType: String,
    val recipeId: Long?,
    val title: String,
    val imageUrl: String?,
    val servings: Int?,
    /** What its no-photo image is drawn from (#403): the recipe's uid, language and ingredient lines; none for a note. */
    val recipeUid: String? = null,
    val language: String? = null,
    val ingredients: List<String> = emptyList()
) {
    /** The designed image for a recipe with no photo (#403); a recipe whose uid is not known is tinted by its id. */
    val noPhoto: NoPhotoSource
        get() = NoPhotoSource(recipeUid ?: "recipe-$recipeId", title, language, ingredients)
}

/**
 * Home's Today card (#402): [lead], the earliest meal today not yet cooked, and the [others]
 * still to cook, in meal type order. A recipe counts as cooked today with a cooking entry dated
 * today (a photo, "Mark as cooked", or a finished run), and then drops off. A meal whose window
 * has passed ([MealWindows]: breakfast 11:00, lunch 15:00) is gone, cooked or not.
 */
data class TodayCard(val lead: TodayMeal, val others: List<TodayMeal>) {
    companion object {
        /**
         * Today's card at [today] and [millisOfDay] (local), from the day's [meals]; null when
         * nothing is left to cook.
         */
        fun of(
            meals: List<PlannedMeal>, types: List<MealType>, today: Long, cooked: Set<Long>, millisOfDay: Long
        ): TodayCard? {
            val order = types.sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .mapIndexed { index, type -> type.id to index }.toMap()
            val names = types.associate { it.id to it.name }
            val keys = types.associate { it.id to it.builtInKey }
            val left = meals.filter {
                it.day == today && (it.recipeId == null || it.recipeId !in cooked) &&
                    MealWindows.isOpen(keys[it.mealTypeId], millisOfDay)
            }
                .sortedBy { order[it.mealTypeId] ?: Int.MAX_VALUE }
                .mapNotNull { meal ->
                    val title = (meal.title ?: meal.note)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    TodayMeal(
                        meal.id, names[meal.mealTypeId].orEmpty(), meal.recipeId, title,
                        meal.imageUrl, meal.servings, meal.recipeUid, meal.language, meal.ingredients
                    )
                }
            return left.firstOrNull()?.let { TodayCard(it, left.drop(1)) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: RecipeRepository,
    // Restore (#150): the same file reading and merge as Settings' Import. Null in tests that
    // don't care, which then offer no restore.
    private val backups: BackupRepository? = null,
    private val files: BackupFiles? = null,
    // The automatic backup copy (#150), for the one-time folder card; null offers none.
    private val autoBackup: AutoBackup? = null,
    // The Lists row's count (#286); null shows none.
    private val lists: ListRepository? = null,
    // The resume card's timer counts down from its saved deadline against this (#286).
    private val clock: Clock = Clock { System.currentTimeMillis() },
    // "Paste a link" takes any text (#372): where it goes is the shared-text route's; null opens links only.
    private val pasted: PastedTextRouter? = null,
    // Today's meals from Week, and which are cooked (#402); null shows no Today card.
    private val mealPlan: MealPlanRepository? = null,
    private val cooked: CookedPhotoRepository? = null
) : ViewModel() {

    private val link = MutableStateFlow<String?>(null)
    private val linkNoRecipe = MutableStateFlow(false)
    private val restore = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    private val askAi = MutableStateFlow(false)

    private val backupState: Flow<AutoBackupState?> =
        autoBackup?.state ?: flowOf(null)

    /** The two most recent (Continue cooking, and whether it stands alone), and whether the only one is the tour's sample (#151). */
    private val recentRecipes: Flow<Pair<List<RecipeSummary>, Boolean>> =
        repository.observeRecent(RECENT_COUNT)
            .map { recent -> recent to (recent.size == 1 && recent[0].id == repository.sampleId()) }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Continue cooking's place (#286), followed as the cook's progress is saved. A running
     * timer is counted down from its deadline, as cook mode does, and nothing is written.
     */
    private val resume: Flow<CookResume?> = recentRecipes
        .map { (recent, _) -> recent.firstOrNull()?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeRecipe(id) }
        .flatMapLatest { recipe ->
            if (recipe == null) flowOf(null) else flow {
                while (true) {
                    val place = CookResume.of(recipe, clock.now())
                    emit(place)
                    if (place?.timerSecondsLeft == null) break
                    delay(TICK_MS)
                }
            }
        }

    /** Today, and the time of day in it. */
    private data class Moment(val day: Long, val millisOfDay: Long)

    /**
     * Today as an epoch day with the local time (#402), again when the day turns over or a meal
     * window ends ([MealWindows]); the clock's, never the screen's.
     */
    private val today: Flow<Moment> = flow {
        while (true) {
            val now = clock.now()
            val zone = TimeZone.getDefault()
            val sinceMidnight = Math.floorMod(now + zone.getOffset(now), PlanDays.MILLIS_PER_DAY)
            emit(Moment(PlanDays.today(now), sinceMidnight))
            // Woken at midnight or when the next window ends, and in a minute at most: a clock or
            // zone changed under it is seen soon too.
            delay(
                minOf(
                    PlanDays.MILLIS_PER_DAY - sinceMidnight,
                    MealWindows.untilNextEnd(sinceMidnight) ?: Long.MAX_VALUE,
                    DAY_CHECK_MS
                )
            )
        }
    }.distinctUntilChanged { a, b -> a.day == b.day && MealWindows.ended(a.millisOfDay) == MealWindows.ended(b.millisOfDay) }

    /** Today's card (#402), followed as the plan, the cooked entries and the day change. */
    private val todayCard: Flow<TodayCard?> =
        if (mealPlan == null) flowOf(null) else today.flatMapLatest { (day, millisOfDay) ->
            combine(
                mealPlan.observeDays(day, day),
                mealPlan.observeMealTypes(),
                cooked?.observeCookedOn(day) ?: flowOf(emptySet())
            ) { meals, types, cookedIds -> TodayCard.of(meals, types, day, cookedIds, millisOfDay) }
        }

    private val counts: Flow<Pair<Int, Int?>> = combine(
        repository.observeHistory("").map { it.size },
        lists?.observeLists()?.map { it.size } ?: flowOf(null),
        ::Pair
    )

    /**
     * The most recent recipe is the "continue cooking" card.
     *
     * There is deliberately no "Saved" section and no "Recently viewed" shelf (#371): both
     * repeated what Recipes shows (Recently viewed is its default sort), and the duplication
     * once crashed the LazyColumn, one recipe in two sections under one key.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        combine(combine(recentRecipes, resume, askAi, ::Triple), todayCard, ::Pair),
        counts,
        combine(link, linkNoRecipe, ::Pair),
        restore,
        backupState
    ) { (recentResumeAsk, todayCard), (recipeCount, listCount), (linkInput, noRecipe), restoring, backup ->
        // The sample alone (#151) still offers the restore row, and no folder card: there is
        // nothing of the user's to restore over or to lose.
        val (recentAndSample, resume, askAiOpen) = recentResumeAsk
        val (recent, onlySample) = recentAndSample
        val latest = recent.firstOrNull()
        HomeUiState(
            loaded = true,
            linkInput = linkInput,
            linkNoRecipe = noRecipe,
            askAiOpen = askAiOpen,
            continueCooking = latest,
            // Only the latest recipe's own place: never the one before it, for a moment.
            resume = resume?.takeIf { it.recipeId == latest?.id },
            today = todayCard,
            recipeCount = recipeCount,
            listCount = listCount,
            restore = if (backups != null) restoring else BackupStatus.Idle,
            offersBackupFolder = backup != null && AutoBackupPolicy.offersFolderPrompt(
                backup.record, backup.destination, libraryEmpty = recent.isEmpty() || onlySample
            ),
            onlySample = onlySample
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Whether "Restore from a backup file" can be offered at all. */
    val canRestore: Boolean get() = backups != null && files != null

    /** The "Paste a link" tile (#286): opens the dialog, empty. */
    fun onPasteLink() {
        link.value = ""
    }

    /** The "Ask an AI" tile (#351): opens its sheet. */
    fun onAskAi() {
        askAi.value = true
    }

    fun onAskAiDismiss() {
        askAi.value = false
    }

    fun onLinkChange(text: String) {
        link.value = text
        linkNoRecipe.value = false
    }

    fun onLinkDismiss() {
        link.value = null
        linkNoRecipe.value = false
    }

    /**
     * Go (#372): a link imports, any other text goes where the same text shared would (the
     * editor, the meal-plan review, Groceries' list sheet). [onTarget] gets where, and the dialog
     * closes; with nothing to open the dialog stays, saying so. Nothing is saved here.
     */
    fun onOpenLink(onTarget: (PasteTarget) -> Unit) {
        val text = link.value?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val target = pasted?.open(text) ?: UrlInput.normalize(text)?.let { PasteTarget.Link(it) }
            if (target == null) {
                linkNoRecipe.value = true
            } else {
                link.value = null
                onTarget(target)
            }
        }
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
        /** Continue cooking, and whether a second recipe exists (for the sample check). */
        const val RECENT_COUNT = 2

        /** How often a running timer's time left is recomputed, as in cook mode. */
        const val TICK_MS = CookController.TICK_MS

        /** How often the day is looked at again, at most (#402). */
        const val DAY_CHECK_MS = 60_000L
    }
}
