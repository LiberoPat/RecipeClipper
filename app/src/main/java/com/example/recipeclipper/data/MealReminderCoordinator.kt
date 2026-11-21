package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.AheadSteps
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.MealReminder
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.data.model.MealReminders
import com.example.recipeclipper.data.model.PlanDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** One alarm to arm (#397): [kind]'s reminder fires on [day] (an epoch day) at [minute] local time. */
data class MealReminderAlarm(val kind: MealReminderKind, val day: Long, val minute: Int)

/**
 * Arms the evening and Tonight reminders' alarms (#397). A seam like [ExpiryReminderScheduler]:
 * the real one is `reminders.AndroidMealReminderScheduler`, tests pass a fake.
 */
interface MealReminderScheduler {
    /** Replaces whatever was armed with [alarms], at most one per kind; a kind not in it is cancelled. */
    fun replaceAll(alarms: List<MealReminderAlarm>)

    /** Arms [alarm] alone, replacing its kind's, and leaves the other kind as it is. */
    fun arm(alarm: MealReminderAlarm)
}

/**
 * Keeps the meal reminders' alarms in step with the settings and the flag, and builds a reminder
 * from the database when its alarm fires (#397). The alarm is armed whether or not anything is
 * planned: **the receiver decides** at fire time, from the plan as it is then, so a meal moved or
 * removed since, a setting switched off or a flag turned off never notifies. Only the next
 * firing of each kind is ever pending; the receiver arms the one after, and [start], the boot
 * and the clock changing re-arm the next from scratch.
 */
@Singleton
class MealReminderCoordinator @Inject constructor(
    private val mealPlan: MealPlanRepository,
    private val recipes: RecipeRepository,
    private val preferences: AppPreferences,
    private val flags: FeatureFlags,
    private val scheduler: MealReminderScheduler,
    private val clock: Clock,
    // Pro (#413): without it, as before, every reminder the settings ask for runs.
    private val pro: ProAccess = ProAccess.Ungated
) {
    private var zone: () -> TimeZone = { TimeZone.getDefault() }

    /** For tests: the zone "today" and the reminders' times are measured in. */
    internal fun useZone(zone: TimeZone) {
        this.zone = { zone }
    }

    /** The reminders' settings as the alarms follow them: nothing else about the preferences matters. */
    private data class Wanted(val evening: Boolean, val eveningMinute: Int, val tonight: Boolean, val tonightMinute: Int, val flagOn: Boolean)

    /**
     * Without Pro (#413) only a reminder the cook switched on runs: the evening one's default isn't
     * theirs, a Tonight one that is on always is ([ProReminders]).
     */
    private fun wanted(settings: AppSettings, flagOn: Boolean, pro: ProState) = Wanted(
        ProReminders.runs(settings.eveningReminder, settings.eveningReminderChosen, pro), settings.eveningReminderMinute,
        ProReminders.runs(settings.tonightReminder, chosen = true, pro), settings.tonightReminderMinute, flagOn
    )

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                preferences.settings,
                flags.values.map { it.isOn(Flag.EVENING_REMINDERS) }.distinctUntilChanged(),
                pro.states,
                ::wanted
            ).distinctUntilChanged().collect { scheduler.replaceAll(alarms(it, notBefore = emptyMap())) }
        }
    }

    /** Arms the next firing of each wanted kind from the settings now (a boot, the clock changing). */
    fun refresh() {
        scheduler.replaceAll(alarms(wantedNow(), notBefore = emptyMap()))
    }

    /**
     * Arms the next firing of [kind] alone, after its alarm for [firedDay] has gone off: a day
     * later at the earliest, even if an alarm that came a moment early leaves the clock before
     * its time. The other kind's pending alarm is not touched, whatever its minute.
     */
    fun rearm(kind: MealReminderKind, firedDay: Long) {
        alarms(wantedNow(), notBefore = mapOf(kind to firedDay + 1)).firstOrNull { it.kind == kind }?.let(scheduler::arm)
    }

    private fun wantedNow() = wanted(preferences.current, flags.isOn(Flag.EVENING_REMINDERS), pro.current)

    private fun alarms(wanted: Wanted, notBefore: Map<MealReminderKind, Long>): List<MealReminderAlarm> {
        if (!wanted.flagOn) return emptyList()
        val now = clock.now()
        val offset = zone().getOffset(now)
        val today = PlanDays.epochDay(now, offset)
        val minuteOfDay = (Math.floorMod(now + offset, PlanDays.MILLIS_PER_DAY) / 60_000).toInt()
        fun alarm(kind: MealReminderKind, minute: Int) = MealReminderAlarm(
            kind, maxOf(MealReminders.firstDay(today, minuteOfDay, minute), notBefore[kind] ?: Long.MIN_VALUE), minute
        )
        return listOfNotNull(
            if (wanted.evening) alarm(MealReminderKind.EVENING, wanted.eveningMinute) else null,
            if (wanted.tonight) alarm(MealReminderKind.TONIGHT, wanted.tonightMinute) else null
        )
    }

    /** Today, as an epoch day, on the phone's calendar. */
    fun today(): Long = PlanDays.today(clock.now(), zone())

    /**
     * The reminder of [kind] that fires on [day], as the database has it now, for the alarm that
     * has just gone off; null when there is nothing to say: the flag or the setting is off, the
     * alarm is for another day (it came a day late), or nothing is planned for the day it names.
     */
    suspend fun due(kind: MealReminderKind, day: Long): MealReminder? {
        if (!flags.isOn(Flag.EVENING_REMINDERS)) return null
        val wanted = wantedNow()
        if (!(if (kind == MealReminderKind.EVENING) wanted.evening else wanted.tonight)) return null
        if (day != today()) return null
        val mealDay = MealReminders.mealDay(kind, day)
        val meals = mealPlan.observeDays(mealDay, mealDay).first()
        val types = mealPlan.observeMealTypes().first()
        val ahead = if (kind == MealReminderKind.EVENING) {
            val ids = MealReminders.recipeIds(MealReminders.meals(kind, day, meals, types))
            recipes.byIds(ids).associate { it.id to AheadSteps.find(it.instructions, LanguageWords.forRecipe(it)) }
        } else emptyMap()
        return MealReminders.build(kind, day, meals, types) { ahead[it] }
    }
}
