package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.DecisionCandidates
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.GroceryDecisions
import com.example.recipeclipper.data.model.GroceryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The on-device model's questions about the grocery list (#99, #104, #234), asked in the
 * background on [scope] (the ViewModel's), each once per visit. The list shows today's grouping
 * until an answer lands; then the decisions flow regroups it, and an answer may file items out of
 * Other ([GroceryRepository.fileFromOther]). [latestItems] is the list as it now stands.
 */
class GroceryQuestions(
    private val scope: CoroutineScope,
    private val decisions: DecisionRepository,
    private val repository: GroceryRepository,
    private val latestItems: () -> List<GroceryItem>
) {

    // Grocery questions and items already asked about in this visit, so each is asked once.
    private val askedGrocery = mutableSetOf<DecisionQuestion>()
    private val askedAisles = mutableSetOf<Long>()

    /** Asks what [items] raise that [current] hasn't answered and this visit hasn't asked. */
    fun ask(items: List<GroceryItem>, current: Decisions) {
        askAisles(items)
        askGroceryQuestions(items, current)
    }

    /**
     * Asks the model about close names and trailing text (#99), in the background. The list
     * shows today's grouping until an answer lands; then the decisions flow regroups it, and
     * a fresh answer may file a line out of Other beside its partner ([GroceryDecisions.filing]).
     */
    private fun askGroceryQuestions(items: List<GroceryItem>, current: Decisions) {
        val unchecked = items.filter { !it.checked }
        val open = (
            GroceryDecisions.ingredientNames(unchecked) + GroceryDecisions.trailingTexts(unchecked, current) +
                GroceryDecisions.samePairs(unchecked, current)
            )
            .filter { !current.isAnswered(it) && askedGrocery.add(it) }
        if (open.isEmpty()) return
        scope.launch {
            decisions.decide(open)
            val after = decisions.current()
            val fresh = open.filter { after.isAnswered(it) }.toSet()
            for ((aisle, ids) in GroceryDecisions.filing(latestItems(), fresh, after)) {
                repository.fileFromOther(ids, aisle)
            }
        }
    }

    /**
     * Asks the model the aisle of each item in Other whose name the keyword table doesn't know
     * (#104), in the background. Only an answer that lands now files the items, and only those
     * still in Other: an item in Other whose aisle was already decided was put there by the user.
     */
    private fun askAisles(items: List<GroceryItem>) {
        val fresh = items.filter { it.aisle == Aisle.OTHER && !it.checked && askedAisles.add(it.id) }
        val byQuestion = fresh.mapNotNull { item -> DecisionCandidates.aisle(item.text, item.language)?.let { it to item.id } }
            .groupBy({ it.first }, { it.second })
        if (byQuestion.isEmpty()) return
        scope.launch {
            val before = decisions.current()
            val open = byQuestion.filterKeys { !before.isAnswered(it) }
            if (open.isEmpty()) return@launch
            decisions.decide(open.keys)
            val after = decisions.current()
            for ((question, ids) in open) {
                val aisle = after.aisle(question.input, question.language) ?: continue
                repository.fileFromOther(ids, aisle)
            }
        }
    }
}
