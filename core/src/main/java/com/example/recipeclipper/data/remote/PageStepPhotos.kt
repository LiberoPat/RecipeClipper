package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.StepPhotos
import com.example.recipeclipper.data.model.WebImageUrl
import org.jsoup.nodes.Element

/**
 * Step photos a page shows beside its steps but leaves out of its JSON-LD (#438): Giallo
 * Zafferano writes bare photo numbers into every step ("con il sale grosso 1 ;", "Mescolate di
 * tanto in tanto 2 .") and shows the photos they point at only in its HTML, each step a
 * `div.gz-content-recipe-step` holding `<picture data-step="1">` photos and the numbers as
 * `<span class="num-step">1</span>`. Read only when the page proves every number: the same count
 * of steps as JSON-LD's, each step's letters and digits found in its JSON-LD step, each step's
 * numbers exactly its photos' numbers, in order, and those numbers 1, 2, 3... through the recipe,
 * so the badges [StepPhotos.numbered] gives them (their place among the recipe's step photos) are
 * the numbers in the text. Anything else reads as no photos, and the text stays as written.
 *
 * What a page's photo card is comes from `shared/tables/site-rules.json` ([SiteRules]), as
 * selectors. Pure. The iOS app's `PageStepPhotos` is the same.
 */
internal object PageStepPhotos {

    /**
     * One kind of step-photo layout: [step] holds one step; [photo] is one of its photos (an
     * `img`, or an element holding one) and [number] the photo's attribute giving its number;
     * [marker] is the step text's element holding a number.
     */
    class Card(val step: CardSelector, val photo: CardSelector, val number: String, val marker: CardSelector)

    /** Each of [steps]' photos from [page], lined up with them, or null unless [card] proves them all. */
    fun read(page: Element, steps: List<String>, card: Card): List<List<String>>? {
        val containers = card.step.select(page).let { all ->
            all.filter { e -> all.none { it !== e && e.parents().contains(it) } }
        }
        if (steps.isEmpty() || containers.size != steps.size) return null
        val photos = mutableListOf<List<String>>()
        var expected = 1
        for ((i, container) in containers.withIndex()) {
            val key = CardHeadings.letters(steps[i])
            if (key.isEmpty() || !CardHeadings.letters(CardIngredients.text(container)).contains(key)) return null
            val inside = container.allElements.drop(1)
            val shown = inside.filter(card.photo::matches)
            val numbers = shown.map { number(it.attr(card.number).trim()) ?: return null }
            val marks = inside.filter(card.marker::matches).map { number(CardIngredients.text(it)) ?: return null }
            if (numbers != marks || shown.size > StepPhotos.MAX_PER_STEP) return null
            if (numbers != List(numbers.size) { expected + it }) return null
            expected += numbers.size
            photos += shown.map { e ->
                val img = if (e.normalName() == "img") e else e.selectFirst("img") ?: return null
                WebImageUrl.of(img.absUrl("src")) ?: return null
            }
        }
        if (expected == 1 || photos.flatten().distinct().size != expected - 1) return null
        return photos
    }

    private val digits = Regex("[0-9]{1,3}")

    /** A photo's number: one to three ASCII digits, nothing else. */
    private fun number(s: String): Int? = if (digits.matches(s)) s.toInt() else null
}
