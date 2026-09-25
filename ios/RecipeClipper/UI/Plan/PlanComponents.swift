import SwiftUI

/// Day labels (#49). A day is an epoch day, so it is formatted at midnight UTC with a UTC
/// calendar: the local zone could put that instant on the day before.
enum PlanDayFormat {
    private static func format(_ day: Int64, _ template: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: PlanDays.utcDate(day))
    }

    /// "Monday 23", in the locale's order.
    static func title(_ day: Int64) -> String { format(day, "EEEEd") }

    /// "Mon"
    static func shortWeekday(_ day: Int64) -> String { format(day, "EEE") }

    /// "23"
    static func dayOfMonth(_ day: Int64) -> String { format(day, "d") }

    /// "Sep 21 – 27", or across months "Sep 28 – Oct 4".
    static func weekRange(_ start: Int64) -> String {
        let end = start + 6
        let sameMonth = format(start, "M") == format(end, "M")
        return format(start, "MMMd") + " – " + (sameMonth ? format(end, "d") : format(end, "MMMd"))
    }
}

/// The strip of days in a plan sheet: this week and next, a short weekday over its date. The
/// chosen day is ringed in paprika; today's date is paprika text.
struct DayStrip: View {
    let days: [Int64]
    let today: Int64
    let selected: Int64
    let onSelect: (Int64) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(days, id: \.self) { day in
                    let isSelected = day == selected
                    Button { onSelect(day) } label: {
                        VStack(spacing: 2) {
                            Text(PlanDayFormat.shortWeekday(day))
                                .textStyle(Typography.labelMedium)
                                .foregroundStyle(Palette.muted)
                            Text(PlanDayFormat.dayOfMonth(day))
                                .textStyle(Typography.titleMedium)
                                .foregroundStyle(day == today ? Palette.accentText : Palette.onBackground)
                        }
                        .frame(minWidth: 48)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 6)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .strokeBorder(isSelected ? Palette.accentText : Palette.hairline, lineWidth: isSelected ? 2 : 1)
                        )
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(PlanDayFormat.title(day))
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                    .accessibilityIdentifier("planDay-\(day)")
                }
            }
        }
    }
}

/// The meal types as a row of outlined choices, the chosen one ringed in paprika.
struct MealTypeChoices: View {
    let types: [MealType]
    let selected: Int64?
    let onSelect: (Int64) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(types) { type in
                    let isSelected = type.id == selected
                    Button { onSelect(type.id) } label: {
                        Text(type.name)
                            .textStyle(Typography.labelLarge)
                            .foregroundStyle(isSelected ? Palette.accentText : Palette.onBackground)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(
                                Capsule().strokeBorder(isSelected ? Palette.accentText : Palette.hairline, lineWidth: isSelected ? 2 : 1)
                            )
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
        }
    }
}

/// "Serves − 4 +", as on the recipe screen.
struct ServingsPicker: View {
    let servings: Int
    let onChange: (Int) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(Strings.serves).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
            stepButton("−", enabled: servings > 1, label: Strings.decreaseServings) { onChange(servings - 1) }
            Text("\(servings)")
                .textStyle(Typography.titleMedium)
                .foregroundStyle(Palette.onBackground)
                .frame(minWidth: 32)
            stepButton("+", enabled: servings < Servings.max, label: Strings.increaseServings) { onChange(servings + 1) }
        }
    }

    private func stepButton(_ glyph: String, enabled: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(glyph)
                .textStyle(Typography.titleLarge)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Palette.surfaceContainer))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Palette.onBackground)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

/// The body shared by "Add to plan" and the Week's "Move": days, meal type, maybe servings,
/// then one button.
struct PlanSheetContent: View {
    let title: String
    let days: [Int64]
    let today: Int64
    let selectedDay: Int64
    let onDaySelected: (Int64) -> Void
    let mealTypes: [MealType]
    let selectedMealTypeId: Int64?
    let onMealTypeSelected: (Int64) -> Void
    var servings: Int? = nil
    var onServingsChange: (Int) -> Void = { _ in }
    let confirmLabel: String
    let confirmEnabled: Bool
    let onConfirm: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(title).padding(.bottom, 12)
                DayStrip(days: days, today: today, selected: selectedDay, onSelect: onDaySelected)
                Text(Strings.labelMeal)
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 20)
                    .padding(.bottom, 8)
                MealTypeChoices(types: mealTypes, selected: selectedMealTypeId, onSelect: onMealTypeSelected)
                if let servings {
                    ServingsPicker(servings: servings, onChange: onServingsChange).padding(.top, 16)
                }
                Button(confirmLabel, action: onConfirm)
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!confirmEnabled)
                    .padding(.top, 24)
                    .accessibilityIdentifier("plan.confirm")
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
            .readableColumn()
        }
        .presentationBackground(Palette.background)
    }
}
