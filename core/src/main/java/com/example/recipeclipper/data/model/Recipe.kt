package com.example.recipeclipper.data.model

/** Where a recipe was parsed from. Kept so a saved recipe can be re-fetched the right way. */
enum class SourceType { BLOG, REDDIT }

/**
 * Whose words a recipe's content is (#29, #37), stored by name in `contentOrigin`.
 * Only [PARSED] and [EXTRACTED] are the source's: every other value is the user's version,
 * which a re-share never refreshes. "Update from source" is the one way back to the source's.
 */
enum class ContentOrigin {
    /** As parsed from its link. Refreshed on every re-share. */
    PARSED,
    /** Parsed, then changed by the user. */
    EDITED,
    /** Picked from the page by hand (#37). Stays CLIPPED when edited. */
    CLIPPED,
    /** Typed in by hand; its link is a synthetic [ManualRecipe] key, never fetched. */
    MANUAL,
    /** Read from a chat's schema.org Recipe JSON (#355): the AI's words, to be checked. Handled
     *  like [MANUAL] everywhere (never fetched, a `manual:` link, never refreshed, stays when
     *  edited), but its own view and label. An older app reads the unknown name as EDITED. */
    CHAT,
    /** Picked from the page's text by the on-device model (#103), every line checked to be on
     *  the page. The source's, like [PARSED]: refreshed on every re-share. */
    EXTRACTED;

    /** The user's version: a re-share opens it as it is, without fetching. */
    val isUsersVersion: Boolean get() = !isSources(name)

    /** The origin after the user saves an edit: the source's becomes EDITED; the rest keep theirs. */
    fun afterEdit(): ContentOrigin = if (isUsersVersion) this else EDITED

    companion object {
        /** Stored by name; an unknown name (a newer app's) reads as the user's version, EDITED,
         *  so it is never overwritten by a re-share. */
        fun fromName(name: String?): ContentOrigin =
            if (name == null) PARSED else entries.firstOrNull { it.name == name } ?: EDITED

        /** A stored `contentOrigin` that is the source's, so a re-share refreshes it. */
        fun isSources(name: String?): Boolean = name == PARSED.name || name == EXTRACTED.name
    }
}

/**
 * A recipe typed in by hand has no link, but `sourceUrl` is the unique upsert key, so it gets
 * a synthetic one: `manual:<uuid>`. It is never fetched or cleaned, and has no host, so no
 * source credit, Open original or Report is shown for it.
 */
object ManualRecipe {
    const val SCHEME = "manual:"

    fun newSourceUrl(uuid: String): String = SCHEME + uuid

    fun isManual(sourceUrl: String): Boolean = sourceUrl.startsWith(SCHEME)
}

/**
 * A recipe as the rest of the app sees it, distinct from the Room entity: the repository
 * maps between them. A freshly parsed recipe has no [id] yet (0); one that came out of the
 * database always does.
 */
data class Recipe(
    val name: String,
    val image: String?,
    val ingredients: List<String>,
    val instructions: List<String>,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val yield: String?,
    val sourceUrl: String,
    val sourceType: SourceType = SourceType.BLOG,
    val id: Long = 0,
    /** Indexes into [ingredients] the user has ticked off. Persisted so cooking can resume. */
    val checkedIngredients: Set<Int> = emptySet(),
    val lastViewedAt: Long = 0,
    /** The user's own free-text note, or null. Never parsed, so re-sharing keeps it. */
    val notes: String? = null,
    /**
     * The recipe's language tag as the parser chose it ("en", "de-de"; see [LanguageWords]),
     * which picks the words its lines are read with. Null for a recipe stored before #14,
     * which is detected from its words when shown.
     */
    val language: String? = null,
    /** Where the cook stands on this recipe: cook mode, steps done, step timers. */
    val cook: CookProgress = CookProgress(),
    /** The servings the user chose, or null for the recipe's own yield. */
    val servingsTarget: Int? = null,
    /** Whose words the content is; see [ContentOrigin]. */
    val origin: ContentOrigin = ContentOrigin.PARSED,
    /** When the user last saved an edit, or null if never. */
    val editedAt: Long? = null,
    /** The recipe's own picture, stored on the phone (#386): a file name in the photo store, or null.
     *  Not part of [image] (a web link, which the editor shows and saves). */
    val photoFile: String? = null,
    /** The stored row's stable uid (#403: its tint), or empty for a recipe not stored yet. */
    val uid: String = "",
    /**
     * The source's tips (#432): schema.org `HowToTip` entries in `recipeInstructions`, kept apart
     * from [instructions], each as written. Shown below the method, unnumbered, and never in cook
     * mode: not a step, not counted in its progress. Empty for most recipes.
     */
    val tips: List<String> = emptyList()
) {
    /** The recipe as it read before #432 (the `recipeTips` flag off): its tips as its last steps. */
    fun withTipsAsSteps(): Recipe = if (tips.isEmpty()) this else copy(instructions = instructions + tips, tips = emptyList())

    /** What the screens show as the recipe's picture (#386): its stored photo (with its link behind it, for a missing file), else its link. */
    val displayImage: String?
        get() = photoFile?.let { StoredPhoto.model(it, fallback = image) } ?: image

    /** What its no-photo image is drawn from (#403); a recipe not stored yet is tinted by its link. */
    val noPhoto: NoPhotoSource
        get() = NoPhotoSource(uid.ifEmpty { sourceUrl }, name, language, ingredients)

    /** "Update from source" applies: the user's version of a recipe that has a real link. */
    val canUpdateFromSource: Boolean
        get() = origin.isUsersVersion && origin != ContentOrigin.MANUAL && !ManualRecipe.isManual(sourceUrl)
}

/**
 * A recipe named by another (#354): the original a version is based on, or one of a recipe's
 * versions. Just what a row of "Based on" or "Versions" shows and opens.
 */
data class RecipeLink(val id: Long, val title: String)

/** What a list row (history, home) needs, without loading every ingredient and step. */
data class RecipeSummary(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    val totalTime: String?,
    val lastViewedAt: Long,
    /** In at least one list. Derived from list membership, never stored. */
    val isSaved: Boolean,
    /** Picked from the page by hand (#37, origin CLIPPED): the row says "Clipped by you". */
    val isClipped: Boolean = false,
    /** The latest day (epoch day) of the user's own photos of it (#116); null for none. */
    val lastCookedDay: Long? = null,
    /** Its link, for the site credited under the title (#286); `manual:` for a typed one. */
    val sourceUrl: String = "",
    /** Read from a chat's JSON (#355, origin CHAT): the row says "From a chat". */
    val isFromChat: Boolean = false,
    /** The prep and cook times as stored, and the language they were written in: what "30 min or less" reads (#378). */
    val prepTime: String? = null,
    val cookTime: String? = null,
    val language: String? = null,
    /** Its stable uid, for its image when it has no photo (#403). */
    val uid: String = "",
    /** Its ingredient lines, only where an image shows them (Home's card, #403); else empty. */
    val ingredients: List<String> = emptyList()
) {
    /** What its no-photo image is drawn from (#403). */
    val noPhoto: NoPhotoSource
        get() = NoPhotoSource(uid.ifEmpty { "recipe-$id" }, title, language, ingredients)

    /** The site, as the reading view credits it; null for a typed or scanned recipe. */
    val sourceDomain: String?
        get() = if (ManualRecipe.isManual(sourceUrl)) null else SourceDomain.of(sourceUrl)
}

/**
 * How the Recipes screen orders its rows (#102). Remembered in `AppPreferences` by name, so
 * it survives leaving the screen; an unknown stored name reads as [RECENTLY_VIEWED].
 */
enum class RecipeSort {
    RECENTLY_VIEWED, NAME, DATE_ADDED,

    /** Recipes with the user's own photos first, most recently cooked first (#116). */
    RECENTLY_COOKED;

    companion object {
        fun fromStoredName(name: String?): RecipeSort = entries.firstOrNull { it.name == name } ?: RECENTLY_VIEWED
    }
}

/**
 * How the Recipes screen lays its recipes out (#380): [ROWS] (the tidy list) or a photo [GRID].
 * Remembered in `AppPreferences` as `recipes_layout`, by name; an unknown stored name reads as [ROWS].
 */
enum class RecipesLayout {
    ROWS, GRID;

    companion object {
        fun fromStoredName(name: String?): RecipesLayout = entries.firstOrNull { it.name == name } ?: ROWS
    }
}

/**
 * Which recipes the Recipes screen lists (#345): all, those in at least one list ("saved",
 * derived from the cross-ref table), or those in none (V1's filter pills, and V4's first view);
 * and V4's other automatic views: [COOKED] (the user's own photos or "Mark as cooked", #116,
 * #173), [TYPED] (typed, scanned or read in by hand, `contentOrigin` MANUAL, never the tour's
 * sample) and [CHAT] (read from a chat's JSON, `contentOrigin` CHAT, #355). Not remembered: it opens on [ALL].
 */
enum class RecipeFilter {
    ALL, IN_A_LIST, NOT_IN_A_LIST, COOKED, TYPED, CHAT;

    companion object {
        /** The three pills with `oneCookbook` off (V1). */
        val pills: List<RecipeFilter> = listOf(ALL, IN_A_LIST, NOT_IN_A_LIST)

        /** The strip's automatic views with `oneCookbook` on (V4), in the order the strip shows them. */
        val views: List<RecipeFilter> = listOf(NOT_IN_A_LIST, COOKED, TYPED, CHAT)

        fun fromRouteName(name: String?): RecipeFilter? = views.firstOrNull { it.name == name }
    }
}

/**
 * How many recipes there are, how many of them are in at least one list, and how many are
 * cooked, typed in or from a chat (#345, #355): the Recipes filter's and the views strip's counts.
 */
data class RecipeCounts(
    val all: Int,
    val inList: Int,
    val cooked: Int = 0,
    val typed: Int = 0,
    val chat: Int = 0
) {
    val notInList: Int get() = all - inList

    fun of(filter: RecipeFilter): Int = when (filter) {
        RecipeFilter.ALL -> all
        RecipeFilter.IN_A_LIST -> inList
        RecipeFilter.NOT_IN_A_LIST -> notInList
        RecipeFilter.COOKED -> cooked
        RecipeFilter.TYPED -> typed
        RecipeFilter.CHAT -> chat
    }
}

/**
 * Why a [ParseResult] or a load failed, as a cause rather than a sentence: a parser or a
 * repository has no business choosing presentation copy. The UI resolves one of these to
 * text via `stringResource`. [FetchFailed.detail] is the exception, not to be confused for
 * copy — it's shown in parentheses as diagnostic detail, same as before.
 */
sealed class ParseError {
    /**
     * The page loaded but carried no recipe data. Never retried automatically; "Try again"
     * is still offered, because a captive portal's login page lands here too.
     */
    object NoRecipeFound : ParseError()

    /**
     * A Reddit post with no recipe as text: neither the post body nor any comment splits into
     * ingredients and steps. A legitimate outcome (often a photo of a dish, or of a recipe
     * card nobody has transcribed yet), not a failure. Carries the post's [title] and
     * [imageUrl] so the screen can show the photo, and [imageUrls], every picture of the post
     * in order (each of a gallery's; else the one photo), for "Read the photo" (#198). Never
     * retried automatically.
     */
    data class NoTranscription(
        val title: String,
        val imageUrl: String?,
        val imageUrls: List<String> = listOfNotNull(imageUrl)
    ) : ParseError()

    /**
     * The site answered, but refused: 403, 404, 429 or any 5xx. Usually a bot block, and not
     * a stable one: the same site can refuse one minute and answer the next, and some sites
     * send 404 as a disguise for a block. So this reads as "try again", not "unsupported".
     */
    data class Blocked(val httpStatus: Int) : ParseError()

    /**
     * No connection at all: the device reports no usable network. Not retried (it would only
     * fail the same way); the recipe screen reloads by itself once the connection returns.
     */
    object Offline : ParseError()

    /**
     * Any other network failure (DNS, TLS, a refused connection, a timeout) or any HTTP status
     * that isn't a block. [timedOut] marks a timeout: those are not retried automatically, so a
     * dead Wi-Fi fails in one timeout rather than two.
     */
    data class FetchFailed(val detail: String?, val timedOut: Boolean = false) : ParseError()
    /**
     * Cloudflare's bot check (#220) still wanted a person when the off-screen browser's time ran
     * out ("Verify you are human"). The import screen opens the page visibly instead, for the
     * cook to pass the check once. Never retried automatically, never reloaded on reconnect.
     */
    object HumanCheck : ParseError()
    /**
     * An AI's answer shared in (#350, #352) stopped in the middle of its recipe JSON: a schema.org
     * Recipe began and never closed, and no whole recipe came before it. The editor would only
     * hold a fragment, so the screen says so and offers a message to ask the AI for the rest.
     */
    object CutOff : ParseError()
    object SaveFailed : ParseError()
    object NotSaved : ParseError()
    object NothingToShow : ParseError()

    /**
     * Worth the repository's single automatic retry: a block, or a network failure that wasn't
     * a timeout. Never [Offline] (fails straight away), never [NoRecipeFound]. Every error
     * still offers "Try again" on screen — including [NoRecipeFound], since a captive portal's
     * login page parses as a page with no recipe.
     */
    val shouldAutoRetry: Boolean
        get() = this is Blocked || (this is FetchFailed && !timedOut)

    /**
     * Worth one load in an off-screen browser once the direct fetch (and its retry) has ended
     * here: a block, which a real browser engine often gets past, or a page with no recipe
     * data, which may be built by JavaScript. Never [Offline] or a [FetchFailed].
     */
    val triesRenderedPage: Boolean
        get() = this is Blocked || this == NoRecipeFound

    /** The recipe screen reloads once when the connection comes back while showing these. */
    val reloadsOnReconnect: Boolean
        get() = this == Offline || this is FetchFailed

    companion object {
        /** The statuses that mean "the site refused", as opposed to some other HTTP failure. */
        fun isBlockStatus(status: Int): Boolean =
            status == 403 || status == 404 || status == 429 || status in 500..599

        /** The cause for a non-2xx response. */
        fun forHttpStatus(status: Int, detail: String? = "HTTP $status"): ParseError =
            if (isBlockStatus(status)) Blocked(status) else FetchFailed(detail)
    }
}

sealed class ParseResult {
    /**
     * [kept] false (#107): the library is full and every recipe in it is protected, so
     * [recipe] is shown but was not saved (its id is 0).
     */
    data class Success(val recipe: Recipe, val kept: Boolean = true) : ParseResult()
    data class Error(val error: ParseError) : ParseResult()
}
