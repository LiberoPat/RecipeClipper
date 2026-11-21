package com.example.recipeclipper.data.flags

/**
 * Settings links (#412): the Plan tab's menu links Settings' Plan section only while that section is
 * on the index (it holds only the `eveningReminders` reminders, #397), so the link never opens an
 * empty page. iOS: `FeatureFlags.planSettingsLink`.
 */
val FlagValues.planSettingsLink: Boolean get() = isOn(Flag.TAB_SETTINGS_LINKS) && isOn(Flag.EVENING_REMINDERS)

/** The Pantry tab's menu links Settings' Pantry section (its expiry reminders, #52), always there. */
val FlagValues.pantrySettingsLink: Boolean get() = isOn(Flag.TAB_SETTINGS_LINKS)
