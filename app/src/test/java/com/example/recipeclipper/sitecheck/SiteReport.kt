package com.example.recipeclipper.sitecheck

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The pure half of the weekly site check (#32): reading the URL list, and turning outcomes
 * into the Markdown table and the JSON kept as a trend. Nothing here touches the network, so
 * [SiteReportTest] runs with the normal unit tests; the fetching is in [LiveSiteCheck].
 *
 * Only outcomes are recorded: cause, counts and which fields were present. Never the page,
 * and never the recipe's text (the repo is public; the pages are someone else's).
 */
internal object SiteReport {

    /**
     * One URL's result. [firstCause] is set when the first attempt failed and was retried.
     * [rules] names each of the site's rules in `site-rules.json` and whether it matches this
     * page (#120); null when the site has none or the page didn't load. The report judges a rule
     * over all of its site's pages.
     */
    data class Outcome(
        val url: String,
        val result: ParseResult,
        val firstCause: ParseError? = null,
        val millis: Long = 0,
        val rules: Map<String, Boolean>? = null,
    )

    /** What the table says of a site rule that no longer matches; the workflow warns on it. */
    const val STOPPED = "Stopped matching"

    /** One URL per line; blank lines and anything after `#` are ignored. */
    fun parseUrlList(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

    fun describe(error: ParseError): String = when (error) {
        is ParseError.Blocked -> "Blocked(${error.httpStatus})"
        is ParseError.FetchFailed ->
            if (error.timedOut) "FetchFailed(timed out)" else "FetchFailed(${error.detail ?: "no detail"})"
        ParseError.NoRecipeFound -> "NoRecipeFound"
        ParseError.Offline -> "Offline"
        ParseError.SaveFailed -> "SaveFailed"
        ParseError.NotSaved -> "NotSaved"
        ParseError.NothingToShow -> "NothingToShow"
    }

    fun site(url: String): String =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    fun markdown(outcomes: List<Outcome>, runAt: String): String = buildString {
        val parsed = outcomes.count { it.result is ParseResult.Success }
        appendLine("## Recipe site check, $runAt")
        appendLine()
        appendLine("$parsed of ${outcomes.size} parsed. A failure after the app's one retry is " +
            "shown with its cause; blocking flips run to run, so read this as a trend.")
        appendLine()
        appendLine("| Site | Result | Cause | Ingredients | Steps | Yield | Total time | Photo | Time |")
        appendLine("|---|---|---|---:|---:|---|---|---|---:|")
        for (o in outcomes) {
            val site = "[${cell(site(o.url))}](${o.url.replace(")", "%29")})"
            val retried = o.firstCause?.let { " (retried after ${describe(it)})" } ?: ""
            val seconds = String.format(java.util.Locale.ROOT, "%.1f s", o.millis / 1000.0)
            when (val r = o.result) {
                is ParseResult.Success -> {
                    val recipe = r.recipe
                    appendLine("| $site | Parsed${cell(retried)} | | ${recipe.ingredients.size} | " +
                        "${recipe.instructions.size} | ${yesNo(recipe.yield)} | " +
                        "${yesNo(recipe.totalTime)} | ${yesNo(recipe.image)} | $seconds |")
                }
                is ParseResult.Error ->
                    appendLine("| $site | Failed${cell(retried)} | ${cell(describe(r.error))} | | | | | | $seconds |")
            }
        }
        // The site rules (#120), for the pages that loaded, judged per site: a selector that no
        // longer finds the site's markup shows here, not as a failure. A rule need not match every
        // page (not every Epicurious recipe has an editor's note, or groups to head); one that
        // matches none of its site's pages has stopped matching.
        val bySite = outcomes.filter { !it.rules.isNullOrEmpty() }.groupBy { site(it.url) }
        if (bySite.isEmpty()) return@buildString
        appendLine()
        appendLine("### Site rules")
        appendLine()
        appendLine("Each rule in shared/tables/site-rules.json for a site above, over that site's " +
            "pages. One marked $STOPPED matched none of them: check its selectors or phrases " +
            "against the pages.")
        appendLine()
        appendLine("| Site | Rule | Result | Pages matched |")
        appendLine("|---|---|---|---|")
        for ((site, pages) in bySite) {
            for (rule in pages.flatMap { it.rules!!.keys }.distinct()) {
                val checked = pages.mapNotNull { it.rules!![rule] }
                val matched = checked.count { it }
                val result = if (matched > 0) "Matched" else "**$STOPPED**"
                appendLine("| ${cell(site)} | ${cell(rule)} | $result | $matched of ${checked.size} |")
            }
        }
    }

    fun json(outcomes: List<Outcome>, runAt: String): String {
        val results = JSONArray()
        for (o in outcomes) {
            val entry = JSONObject()
                .put("url", o.url)
                .put("site", site(o.url))
                .put("millis", o.millis)
            o.firstCause?.let { entry.put("firstCause", describe(it)) }
            o.rules?.let { entry.put("siteRules", JSONObject(it)) }
            when (val r = o.result) {
                is ParseResult.Success -> entry
                    .put("outcome", "parsed")
                    .put("ingredients", r.recipe.ingredients.size)
                    .put("steps", r.recipe.instructions.size)
                    .put("hasYield", r.recipe.yield != null)
                    .put("hasTotalTime", r.recipe.totalTime != null)
                    .put("hasPhoto", r.recipe.image != null)
                is ParseResult.Error -> entry
                    .put("outcome", "failed")
                    .put("cause", describe(r.error))
            }
            results.put(entry)
        }
        return JSONObject().put("runAt", runAt).put("results", results).toString(2)
    }

    private fun yesNo(value: String?) = if (value.isNullOrBlank()) "no" else "yes"

    /** Safe inside a table cell: no pipes, no line breaks. */
    private fun cell(text: String) = text.replace("|", "\\|").replace(Regex("\\s+"), " ")
}
