package com.example.recipeclipper.data.model

/**
 * One answer from the on-device model reading a page (#103), as the extractor saw it (#273).
 * Pure; the iOS app's `PageReply` is the same.
 */
sealed interface PageReply {
    /** A reply that read as a pick (possibly an empty one: the page holds no recipe). */
    data class Picked(val selection: PageSelection) : PageReply

    /**
     * The model ran away: a reply that doesn't parse (cut off at the reply cap), or one
     * [RunawayReply] stopped. Worth one more try ([PageReplyRetry]).
     */
    data object Unreadable : PageReply

    /**
     * The model couldn't answer at all: busy, over its quota, blocked in the background, a
     * guardrail, a page over its context. Never retried: it isn't the reply's fault.
     */
    data object Failed : PageReply
}

/**
 * One retry for an unreadable reply (#273): the small models sometimes pad a field (often a
 * time) or repeat one line until the reply cap, and the page then shows no recipe. The retry
 * changes the prompt, so a deterministic model doesn't loop the same way again: [REMINDER]
 * after the instructions, and a lower reply cap ([RETRY_REPLY_TOKENS]), so a second runaway costs
 * half as much. Never more than one retry, and never after [PageReply.Failed]. A retry only ever
 * runs for a page that would otherwise show no recipe, so its lower cap can't cost one that
 * shows today. Pure; the iOS app's `PageReplyRetry` is the same.
 */
object PageReplyRetry {

    /** The reply cap for the retry, in tokens (Android's first try has 4,096, #128). */
    const val RETRY_REPLY_TOKENS = 2_048

    /** Appended to the instructions for the retry. */
    const val REMINDER = "Keep the yield and each time short, as the page writes them (like \"30 minutes\" " +
        "or \"4 servings\"), or null: never explain them. Copy each line once, then stop."

    /**
     * The recipe [ask] picks: asked once (`retry` false), and once more (`retry` true) only if
     * that reply was [PageReply.Unreadable]. Null when it found none or couldn't answer.
     */
    inline fun pick(ask: (retry: Boolean) -> PageReply): PageSelection? =
        when (val first = ask(false)) {
            is PageReply.Picked -> first.selection
            PageReply.Failed -> null
            PageReply.Unreadable -> (ask(true) as? PageReply.Picked)?.selection
        }
}

/**
 * Spots a reply running away (#273) while it is still being written, so the extractor can stop
 * it early instead of waiting for the reply cap: a field far longer than any page writes (a
 * name over [MAX_NAME] characters, a yield or time over [MAX_SHORT], a line over [MAX_LINE]), or
 * the same ingredient or step [MAX_REPEATS] times running. The limits are generous on purpose:
 * a stop throws the reply away. Pure; the iOS app's `RunawayReply` is the same, pinned by the
 * differential corpus's `Runaway` rows.
 */
object RunawayReply {

    const val MAX_NAME = 200
    const val MAX_SHORT = 100
    const val MAX_LINE = 2_000
    const val MAX_REPEATS = 4

    /** True when [pick] (perhaps a reply still being written) has run away. */
    fun isRunaway(pick: PageSelection): Boolean =
        (pick.name?.length ?: 0) > MAX_NAME ||
            listOf(pick.yield, pick.prepTime, pick.cookTime, pick.totalTime).any { (it?.length ?: 0) > MAX_SHORT } ||
            repeats(pick.ingredients) || repeats(pick.steps)

    /** True when the JSON reply [text] (perhaps cut off, or still being written) has run away. */
    fun isRunaway(text: String): Boolean = isRunaway(scan(text))

    private fun repeats(lines: List<String>): Boolean {
        var run = 0
        for (i in lines.indices) {
            if (lines[i].length > MAX_LINE) return true
            run = if (i > 0 && lines[i] == lines[i - 1]) run + 1 else 1
            if (run >= MAX_REPEATS) return true
        }
        return false
    }

    /**
     * The fields of a JSON reply as far as it goes, leniently: the top-level object's string
     * values for `name`, `yield` and the times, and the strings in its `ingredients` and `steps`
     * arrays, the last one perhaps unfinished. Anything else (a code fence, other keys, nested
     * objects, numbers) is skipped. For spotting a runaway only, never for reading a pick: that
     * stays strict (`PageSelectionJson`).
     */
    fun scan(text: String): PageSelection {
        val fields = HashMap<String, String>()
        val lists = HashMap<String, MutableList<String>>()
        // The open containers: true for an object, false for an array.
        val stack = ArrayList<Boolean>()
        var expectKey = false
        var key: String? = null
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { stack.add(true); expectKey = true }
                '[' -> stack.add(false)
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); expectKey = false }
                ',' -> expectKey = stack.lastOrNull() == true
                ':' -> expectKey = false
                '"' -> {
                    val value = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            i++
                            when (val c = text[i]) {
                                'n' -> value.append('\n')
                                't' -> value.append('\t')
                                'r' -> value.append('\r')
                                'b', 'f' -> {}
                                'u' -> {
                                    val hex = text.substring(i + 1, minOf(i + 5, text.length))
                                    hex.takeIf { it.length == 4 }?.toIntOrNull(16)?.let { value.append(it.toChar()) }
                                    i += hex.length
                                }
                                else -> value.append(c)
                            }
                        } else if (text[i] != '\\') {
                            value.append(text[i])
                        }
                        i++
                    }
                    val s = value.toString()
                    when {
                        stack.size == 1 && stack[0] && expectKey -> key = s
                        stack.size == 1 && stack[0] && key in SHORT_FIELDS -> fields[key!!] = s
                        stack.size == 2 && stack[0] && !stack[1] && key in LIST_FIELDS ->
                            lists.getOrPut(key!!) { ArrayList() }.add(s)
                    }
                }
            }
            i++
        }
        return PageSelection(
            name = fields["name"],
            ingredients = lists["ingredients"].orEmpty(),
            steps = lists["steps"].orEmpty(),
            yield = fields["yield"],
            prepTime = fields["prepTime"],
            cookTime = fields["cookTime"],
            totalTime = fields["totalTime"]
        )
    }

    private val SHORT_FIELDS = setOf("name", "yield", "prepTime", "cookTime", "totalTime")
    private val LIST_FIELDS = setOf("ingredients", "steps")
}
