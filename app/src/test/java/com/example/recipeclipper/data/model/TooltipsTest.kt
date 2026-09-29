package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.flags.Flag
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The tooltips' catalogue and "which tooltip now" rule (#190), pure (iOS: TooltipsTests). */
class TooltipsTest {

    private val allOn: (Flag) -> Boolean = { true }
    private val everything = Tooltip.entries.toSet()

    private fun visit(screen: TooltipScreen) = Tooltips.visit(null, "t1", screen)

    @Test
    fun `the catalogue is shared tooltips json, in its order, with its screens and flags`() {
        val stream = Tooltip::class.java.getResourceAsStream("/tooltips.json") ?: error("tooltips.json is missing")
        val json = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONArray("tooltips")
        val shared = (0 until json.length()).map { i ->
            val entry = json.getJSONObject(i)
            Triple(entry.getString("id"), entry.getString("screen"), entry.optString("flag").ifEmpty { null })
        }
        assertEquals(shared, Tooltip.entries.map { Triple(it.id, it.screen.name.lowercase(), it.flag?.key) })
    }

    @Test
    fun `each is stored under its own key`() {
        assertEquals("tooltip_home_link", Tooltip.HOME_LINK.key)
        assertEquals(Tooltip.entries.size, Tooltip.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `the first unseen tooltip on screen shows, once the screen is ready`() {
        val home = visit(TooltipScreen.HOME)
        assertEquals(Tooltip.HOME_LINK, Tooltips.current(home, emptySet(), allOn, everything, ready = true))
        assertNull("not in the first second, nor under a sheet", Tooltips.current(home, emptySet(), allOn, everything, ready = false))
        assertEquals(
            Tooltip.HOME_NEW_RECIPE,
            Tooltips.current(home, setOf(Tooltip.HOME_LINK), allOn, everything, ready = true)
        )
        assertNull(Tooltips.current(home, Tooltips.forScreen(TooltipScreen.HOME).toSet(), allOn, everything, ready = true))
    }

    @Test
    fun `an anchor that isn't on screen is passed over for the next`() {
        val recipe = visit(TooltipScreen.RECIPE)
        val visible = setOf(Tooltip.RECIPE_BOOKMARK, Tooltip.RECIPE_START_COOKING)
        assertEquals(Tooltip.RECIPE_BOOKMARK, Tooltips.current(recipe, emptySet(), allOn, visible, ready = true))
        assertNull("none on screen: none this visit, so far", Tooltips.current(recipe, emptySet(), allOn, emptySet(), ready = true))
    }

    @Test
    fun `only the visit's screen shows one`() {
        val cook = visit(TooltipScreen.COOK)
        assertEquals(
            Tooltip.COOK_DONE_NEXT,
            Tooltips.current(cook, emptySet(), allOn, setOf(Tooltip.HOME_LINK, Tooltip.COOK_DONE_NEXT), ready = true)
        )
        assertNull(Tooltips.current(null, emptySet(), allOn, everything, ready = true))
    }

    @Test
    fun `a flagged tooltip shows only with its flag on`() {
        val recipe = visit(TooltipScreen.RECIPE)
        val seen = Tooltips.forScreen(TooltipScreen.RECIPE).toSet() - Tooltip.RECIPE_MADE_THIS
        assertNull(Tooltips.current(recipe, seen, { it != Flag.COOKED_PHOTOS }, everything, ready = true))
        assertEquals(Tooltip.RECIPE_MADE_THIS, Tooltips.current(recipe, seen, allOn, everything, ready = true))
        assertNull(Tooltips.current(visit(TooltipScreen.WEEK), emptySet(), { false }, everything, ready = true))
    }

    @Test
    fun `a visit keeps the tooltip it showed, and shows no other`() {
        val shown = Tooltips.shown(visit(TooltipScreen.RECIPE), Tooltip.RECIPE_UNITS)
        assertEquals(Tooltip.RECIPE_UNITS, Tooltips.current(shown, emptySet(), allOn, everything, ready = true))
        assertNull(
            "scrolled away, it hides; nothing takes its place",
            Tooltips.current(shown, emptySet(), allOn, setOf(Tooltip.RECIPE_START_COOKING), ready = true)
        )
        assertSame(shown, Tooltips.shown(shown, Tooltip.RECIPE_BOOKMARK))
    }

    @Test
    fun `dismissed, the screen's next tooltip waits for a later visit, never chained`() {
        val closed = Tooltips.closed(Tooltips.shown(visit(TooltipScreen.HOME), Tooltip.HOME_LINK))
        assertNull(Tooltips.current(closed, setOf(Tooltip.HOME_LINK), allOn, everything, ready = true))

        val left = Tooltips.leave(closed, "t1")
        val later = Tooltips.visit(left, "t2", TooltipScreen.HOME)
        assertEquals(Tooltip.HOME_NEW_RECIPE, Tooltips.current(later, setOf(Tooltip.HOME_LINK), allOn, everything, ready = true))
    }

    @Test
    fun `the same appearance is the same visit, as after a rotation`() {
        val closed = Tooltips.closed(visit(TooltipScreen.HOME))
        assertSame(closed, Tooltips.visit(closed, "t1", TooltipScreen.HOME))
        assertEquals(TooltipVisit("t2", TooltipScreen.HOME), Tooltips.visit(closed, "t2", TooltipScreen.HOME))
    }

    @Test
    fun `leaving another screen doesn't end this one's visit`() {
        val home = visit(TooltipScreen.HOME)
        assertSame(home, Tooltips.leave(home, "other"))
        assertNull(Tooltips.leave(home, "t1"))
    }
}
