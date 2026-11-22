import Foundation

extension FeatureFlags {
    /// Settings links (#412): the Plan tab's menu links Settings' Plan section only while that section is
    /// on the index (it holds the `eveningReminders` reminders, #397, and the `todayCardClose` switch,
    /// #448), so the link never opens an empty page. Android: `FlagValues.planSettingsLink`.
    var planSettingsLink: Bool { isOn(.tabSettingsLinks) && (isOn(.eveningReminders) || isOn(.todayCardClose)) }

    /// The Pantry tab's menu links Settings' Pantry section (its expiry reminders, #52), always there.
    var pantrySettingsLink: Bool { isOn(.tabSettingsLinks) }
}
