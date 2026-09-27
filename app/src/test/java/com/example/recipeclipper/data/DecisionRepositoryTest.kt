package com.example.recipeclipper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.local.RecipeDatabase
import com.example.recipeclipper.data.local.entity.AiDecisionEntity
import com.example.recipeclipper.data.model.CountBracket
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.DecisionReply
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.fake.FakeDecisionModel
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The decision cache (#104) against real SQLite: asked twice, judged, cached, asked once. */
@RunWith(AndroidJUnit4::class)
class DecisionRepositoryTest {

    private lateinit var db: RecipeDatabase
    private val model = FakeDecisionModel(answer = "total")
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private lateinit var repository: DefaultDecisionRepository

    private val line = "4 Apfel (ca. 800g)"
    private val question = DecisionQuestion.countBracket(line, "de")

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java).build()
        repository = DefaultDecisionRepository(db.aiDecisionDao(), model, flags, Clock { 5 }) { _, e -> throw e }
        flags.set(Flag.AI_DECISIONS, true)
        // These tests ask count brackets, which also need their own flag since #127.
        flags.set(Flag.AI_COUNT_BRACKETS, true)
    }

    @After
    fun close() = db.close()

    @Test fun `a definite answer is asked twice, cached and never asked again`() = runBlocking {
        repository.decide(listOf(question))
        assertEquals(2, model.asked.size)
        assertEquals(listOf("total", "each", "unsure"), model.asked[0].options)
        assertEquals(listOf("each", "total", "unsure"), model.asked[1].options)
        assertEquals(CountBracket.TOTAL, repository.current().countBracket(line, "de"))
        repository.decide(listOf(question))
        assertEquals(2, model.asked.size)
    }

    @Test fun `a disagreement is cached as unsure, so it isn't asked again and changes nothing`() = runBlocking {
        var n = 0
        model.reply = { DecisionReply(if (n++ == 0) "total" else "each", "high") }
        repository.decide(listOf(question))
        repository.decide(listOf(question))
        assertEquals(2, model.asked.size)
        assertNull(repository.current().countBracket(line, "de"))
        assertEquals("unsure", db.aiDecisionDao().answer("countBracket", question.input, "de"))
    }

    @Test fun `a model that can't answer now caches nothing and is asked next time`() = runBlocking {
        model.reply = { null }
        repository.decide(listOf(question))
        assertNull(db.aiDecisionDao().answer("countBracket", question.input, "de"))
        model.reply = { DecisionReply("total", "high") }
        repository.decide(listOf(question))
        assertEquals(CountBracket.TOTAL, repository.current().countBracket(line, "de"))
    }

    @Test fun `flag off or an unsupported language asks nothing and changes nothing`() = runBlocking {
        repository.decide(listOf(DecisionQuestion.countBracket("1 lata (397 g)", "pt")))
        assertTrue(model.asked.isEmpty())
        repository.decide(listOf(question))
        flags.set(Flag.AI_DECISIONS, false)
        assertEquals(Decisions.NONE, repository.current())
        repository.decide(listOf(DecisionQuestion.countBracket("3 large apples (about 3 cups)", "en")))
        assertEquals(2, model.asked.size)
    }

    @Test fun `with count brackets off, none is asked and a cached one changes nothing`() = runBlocking {
        flags.set(Flag.AI_COUNT_BRACKETS, false)
        repository.decide(listOf(question))
        assertTrue(model.asked.isEmpty())
        db.aiDecisionDao().insert(
            AiDecisionEntity(kind = "countBracket", input = question.input, language = "de", answer = "each", updatedAt = 1)
        )
        assertNull(repository.current().countBracket(line, "de"))
        repository.decide(listOf(DecisionQuestion.aisle("miso paste", "en")))
        assertEquals(2, model.asked.size)
        flags.set(Flag.AI_COUNT_BRACKETS, true)
        assertEquals(CountBracket.EACH, repository.current().countBracket(line, "de"))
    }
}
