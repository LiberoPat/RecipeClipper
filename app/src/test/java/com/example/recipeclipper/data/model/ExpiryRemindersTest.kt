package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ExpiryRemindersTest {

    private val today = 20_000L
    private val early = 8 * 60 + 59 // 8:59
    private val late = 9 * 60      // 9:00, today's reminder has gone

    private fun item(
        name: String,
        expires: Long?,
        inStock: Boolean = true,
        alwaysHave: Boolean = false,
        id: Long = name.hashCode().toLong()
    ) = PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave, null, expires)

    @Test fun `an item is announced the morning before and the morning of its date`() {
        val plan = ExpiryReminders.plan(listOf(item("milk", today + 3)), today, early)
        assertEquals(
            listOf(
                ExpiryReminder(today + 2, today = emptyList(), tomorrow = listOf("milk")),
                ExpiryReminder(today + 3, today = listOf("milk"), tomorrow = emptyList())
            ),
            plan
        )
        assertEquals(ExpiryReminderText.TOMORROW, plan[0].text)
        assertEquals(ExpiryReminderText.TODAY, plan[1].text)
    }

    @Test fun `items on the same day share one reminder, A to Z`() {
        val plan = ExpiryReminders.plan(
            listOf(item("yogurt", today + 2), item("Milk", today + 2)), today, early
        )
        assertEquals(listOf(today + 1, today + 2), plan.map { it.day })
        assertEquals(listOf("Milk", "yogurt"), plan[0].tomorrow)
    }

    @Test fun `one morning can say what expires today and tomorrow`() {
        val reminder = ExpiryReminders.forDay(listOf(item("milk", today), item("eggs", today + 1)), today)!!
        assertEquals(listOf("milk"), reminder.today)
        assertEquals(listOf("eggs"), reminder.tomorrow)
        assertEquals(ExpiryReminderText.TODAY_AND_TOMORROW, reminder.text)
    }

    @Test fun `today's reminder is planned only before nine`() {
        val items = listOf(item("milk", today + 1))
        assertEquals(listOf(today, today + 1), ExpiryReminders.plan(items, today, early).map { it.day })
        assertEquals(listOf(today + 1), ExpiryReminders.plan(items, today, late).map { it.day })
    }

    @Test fun `out of stock, staples, undated and expired items are never announced`() {
        val items = listOf(
            item("milk", today + 1, inStock = false),
            item("salt", today + 1, alwaysHave = true),
            item("flour", null),
            item("cream", today - 1)
        )
        assertEquals(emptyList<ExpiryReminder>(), ExpiryReminders.plan(items, today, early))
        assertNull(ExpiryReminders.forDay(items, today))
    }

    @Test fun `the same name twice is listed once`() {
        val reminder = ExpiryReminders.forDay(listOf(item("milk", today, id = 1), item("milk", today, id = 2)), today)!!
        assertEquals(listOf("milk"), reminder.today)
    }

    @Test fun `the plan is capped`() {
        val items = (1..100).map { item("item $it", today + it * 3L) }
        assertEquals(ExpiryReminders.MAX_REMINDERS, ExpiryReminders.plan(items, today, early).size)
    }

    @Test fun `names join with commas and the translated and`() {
        val and = { a: String, b: String -> "$a and $b" }
        assertEquals("", ExpiryReminders.joinNames(emptyList(), and))
        assertEquals("milk", ExpiryReminders.joinNames(listOf("milk"), and))
        assertEquals("milk and eggs", ExpiryReminders.joinNames(listOf("milk", "eggs"), and))
        assertEquals("milk, eggs and yogurt", ExpiryReminders.joinNames(listOf("milk", "eggs", "yogurt"), and))
    }

    @Test fun `a sentence opens with a capital`() {
        assertEquals("Milk expires today.", ExpiryReminders.capitalized("milk expires today.", Locale.ENGLISH))
        assertEquals("Émincé", ExpiryReminders.capitalized("émincé", Locale.FRENCH))
    }
}
