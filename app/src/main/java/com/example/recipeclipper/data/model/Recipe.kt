package com.example.recipeclipper.data.model

/** Where a recipe was parsed from. Kept so a saved recipe can be re-fetched the right way. */
enum class SourceType { BLOG, REDDIT }

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
    val lastViewedAt: Long = 0
)

/** What a list row (history, home) needs, without loading every ingredient and step. */
data class RecipeSummary(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    val totalTime: String?,
    val lastViewedAt: Long,
    /** In at least one list. Derived from list membership, never stored. */
    val isSaved: Boolean
)

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
     * [imageUrl] so the screen can show the photo. Never retried automatically.
     */
    data class NoTranscription(val title: String, val imageUrl: String?) : ParseError()

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
