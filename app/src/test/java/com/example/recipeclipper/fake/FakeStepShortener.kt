package com.example.recipeclipper.fake

import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.local.dao.ShortStepDao
import com.example.recipeclipper.data.local.entity.ShortStepEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Chef mode's model, faked (#100): [written] says what it writes for each step; a step not in
 * there gets null ("can't right now"). [asked] records every step it was asked for.
 */
class FakeStepShortener(
    var support: ChefSupport = ChefSupport.Available(setOf("en", "de")),
    val written: MutableMap<String, String> = mutableMapOf()
) : StepShortener {
    val asked = mutableListOf<String>()

    override suspend fun support(): ChefSupport = support

    override suspend fun shorten(step: String, language: String): String? {
        asked += step
        return written[step]
    }
}

/** [ShortStepDao] in memory, with the table's unique key and IGNORE on insert. */
class FakeShortStepDao : ShortStepDao {
    val rows = MutableStateFlow<List<ShortStepEntity>>(emptyList())

    override fun observe(recipeId: Long, language: String): Flow<List<ShortStepEntity>> =
        rows.map { all -> all.filter { it.recipeId == recipeId && it.language == language } }

    override suspend fun insert(row: ShortStepEntity) = rows.update { all ->
        val taken = all.any { it.recipeId == row.recipeId && it.stepHash == row.stepHash && it.language == row.language }
        if (taken) all else all + row.copy(id = all.size + 1L)
    }

    override suspend fun prune(recipeId: Long, language: String, keep: List<String>) = rows.update { all ->
        all.filterNot { it.recipeId == recipeId && (it.language != language || it.stepHash !in keep) }
    }
}
