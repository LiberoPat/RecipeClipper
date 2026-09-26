package com.example.recipeclipper.data.remote

import org.jsoup.nodes.Element

/**
 * A CSS selector from the small subset `shared/tables/site-rules.json` uses (#120): a tag,
 * `.class`, `#id`, `[attr]`, `[attr=v]`, `[attr^=v]` and `[attr*=v]` (the value optionally
 * quoted), compounded (`h3.heading`, `h3[class*=SubHed-]`), in comma lists. No combinators: a
 * card's parts are only ever looked for inside its list. Values are case-sensitive, and an
 * attribute's runs of whitespace count as one space. Anything else is a mistake in the table,
 * so it throws. Matched here rather than by Jsoup's `select`, so the iOS app's `CardSelector`
 * matches exactly the same elements.
 */
internal class CardSelector(text: String) {

    /** `.x` is `class ~= x` (one of its words), `#x` is `id = x`; an empty operator only needs the attribute. */
    private class Condition(val attribute: String, val operator: String, val value: String)

    private class Compound(val tag: String?, val conditions: List<Condition>)

    private val alternatives: List<Compound> = text.split(',').map { compound(it.trim(), text) }

    fun matches(e: Element): Boolean = alternatives.any { c ->
        (c.tag == null || c.tag == e.tagName()) && c.conditions.all { holds(it, e) }
    }

    /** [root] and everything inside it that matches, in document order. */
    fun select(root: Element): List<Element> = root.allElements.filter(::matches)

    private fun holds(c: Condition, e: Element): Boolean {
        if (!e.hasAttr(c.attribute)) return false
        val value = CardIngredients.collapse(e.attr(c.attribute))
        return when (c.operator) {
            "=" -> value == c.value
            "^=" -> value.startsWith(c.value)
            "*=" -> value.contains(c.value)
            "~=" -> c.value in value.split(' ')
            else -> true
        }
    }

    private companion object {
        val tagPattern = Regex("^[a-z][a-z0-9]*")
        val conditionPattern = Regex("""\.([A-Za-z0-9_-]+)|#([A-Za-z0-9_-]+)|\[([a-z0-9_-]+)(?:([*^]?=)(?:"([^"]*)"|'([^']*)'|([^\]"']+)))?]""")

        fun compound(text: String, selector: String): Compound {
            val tag = tagPattern.find(text)?.value
            var at = tag?.length ?: 0
            val conditions = mutableListOf<Condition>()
            while (at < text.length) {
                val m = conditionPattern.matchAt(text, at) ?: break
                val g = m.groupValues
                conditions += when {
                    g[1].isNotEmpty() -> Condition("class", "~=", g[1])
                    g[2].isNotEmpty() -> Condition("id", "=", g[2])
                    else -> Condition(g[3], g[4], g[5] + g[6] + g[7])
                }
                at = m.range.last + 1
            }
            require(at == text.length && (tag != null || conditions.isNotEmpty())) {
                "Unsupported selector \"$selector\" in site-rules.json"
            }
            return Compound(tag, conditions)
        }
    }
}
