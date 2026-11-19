package com.example.recipeclipper.ui.edit

import com.example.recipeclipper.TextIntent
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.ui.groceries.ReceivedListInbox
import com.example.recipeclipper.ui.home.UrlInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a pasted or shared text leads (#372). The screen turns it into a route. */
sealed interface PasteTarget {
    /** A link: today's import. */
    data class Link(val url: String) : PasteTarget

    /** The editor on the text, left in [SharedTextInbox] under [key]. */
    data class Review(val key: String) : PasteTarget

    /** The meal-plan review (#352), left in [SharedTextInbox] under [key]. */
    data class Plan(val key: String) : PasteTarget

    /** A bulleted list (#149): offered on Groceries' "Add this list" sheet. */
    data object Groceries : PasteTarget
}

/**
 * Where text goes: shared in, or (#372) pasted into "Paste a link" or "Paste the answer". One
 * decision for both, [TextIntent.target] (#306 text, #350 JSON, #352 a week, #354 versions), so a
 * paste lands exactly where the same text shared would. The text is read once, off the main
 * thread, and left in [SharedTextInbox] / [ReceivedListInbox]. Nothing is saved here: the
 * editor's and the review's Save are the only writes.
 */
@Singleton
class PastedTextRouter @Inject constructor(
    private val flags: FeatureFlags,
    private val sharedTexts: SharedTextInbox,
    private val receivedLists: ReceivedListInbox
) {

    /** Where the text is read: off the main thread (it may be long); a test reads it inline. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.Default

    /** [text] beside pictures ([withPictures], #334) or alone; null when it leads nowhere. */
    suspend fun route(text: String, withPictures: Boolean = false): PasteTarget? {
        val shareTextOn = flags.current.isOn(Flag.SHARE_TEXT)
        val mealPlanOn = flags.current.isOn(Flag.MEAL_PLAN_IMPORT)
        val target = withContext(dispatcher) {
            TextIntent.target(text, shareTextOn, withPictures, mealPlanOn)
        }
        return when (target) {
            is TextIntent.Target.Review -> PasteTarget.Review(sharedTexts.offer(target.text, target.reading))
            is TextIntent.Target.Plan -> PasteTarget.Plan(sharedTexts.offerPlan(target.text, target.plan))
            is TextIntent.Target.Open -> SharedText.link(text)?.let { PasteTarget.Link(it) }
            is TextIntent.Target.OfferList -> {
                receivedLists.offer(target.text)
                PasteTarget.Groceries
            }
            null -> null
        }
    }

    /**
     * What "Paste a link" does with [text] (#372): a lone link, with or without its scheme,
     * imports as it always has; anything else goes where the same text shared would.
     */
    suspend fun open(text: String): PasteTarget? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (LINK_ALONE.matches(trimmed)) UrlInput.normalize(trimmed)?.let { return PasteTarget.Link(it) }
        return route(text)
    }

    private companion object {
        /** One token: a link, or a host and path with the scheme left off ("seriouseats.com/x"), never JSON or "1.5". */
        val LINK_ALONE = Regex(
            """(?i)https?://\S+|[a-z0-9][a-z0-9-]*(\.[a-z0-9-]+)*\.[a-z]{2,}(:\d+)?([/?#]\S*)?"""
        )
    }
}
