package com.example.recipeclipper.data.model

/** Where a recipe was parsed from. Kept so a saved recipe can be re-fetched the right way. */
enum class SourceType { BLOG, REDDIT }

/**
 * Whose words a recipe's content is (#29, #37), stored by name in `contentOrigin`.
 * Only [PARSED] is the source's: every other value is the user's version, which a re-share
 * never refreshes. "Update from source" is the one way back to [PARSED].
 */
enum class ContentOrigin {
    /** As parsed from its link. Refreshed on every re-share. */
    PARSED,
    /** Parsed, then changed by the user. */
    EDITED,
    /** Picked from the page by hand (#37). Stays CLIPPED when edited. */
    CLIPPED,
    /** Typed in by hand; its link is a synthetic [ManualRecipe] key, never fetched. */
    MANUAL;

    /** The user's version: a re-share opens it as it is, without fetching. */
    val isUsersVersion: Boolean get() = this != PARSED

    /** The origin after the user saves an edit: a parsed recipe becomes EDITED; the rest keep theirs. */
    fun afterEdit(): ContentOrigin = if (this == PARSED) EDITED else this

    companion object {
        /** Stored by name; an unknown name (a newer app's) reads as the user's version, EDITED,
         *  so it is never overwritten by a re-share. */
        fun fromName(name: String?): ContentOrigin =
            if (name == null) PARSED else entries.firstOrNull { it.name == name } ?: EDITED
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
    val editedAt: Long? = null
) {
    /** "Update from source" applies: the user's version of a recipe that has a real link. */
    val canUpdateFromSource: Boolean
        get() = origin.isUsersVersion && origin != ContentOrigin.MANUAL && !ManualRecipe.isManual(sourceUrl)
}

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
    val isClipped: Boolean = false
)

/**
 * How the Recipes screen orders its rows (#102). Remembered in `AppPreferences` by name, so
 * it survives leaving the screen; an unknown stored name reads as [RECENTLY_VIEWED].
 */
enum class RecipeSort {
    RECENTLY_VIEWED, NAME, DATE_ADDED;

    companion object {
        fun fromStoredName(name: String?): RecipeSort = entries.firstOrNull { it.name == name } ?: RECENTLY_VIEWED
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
    data class Success(val recipe: Recipe) : ParseResult()
    data class Error(val error: ParseError) : ParseResult()
}
