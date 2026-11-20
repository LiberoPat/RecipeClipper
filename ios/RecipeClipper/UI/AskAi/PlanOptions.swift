import SwiftUI

/// What "Plan my week" asks, once it is the chosen prompt (#392, #367; Android's `PlanOptions`): the
/// meals as chips (the cook's own types, in their order, Dinner at first, the last choice
/// remembered), the day the plan starts on (the same strip as Add to plan), where the recipes come
/// from, and which context goes in. Under the `planMyWeekPlus` flag; the ViewModel decides, this
/// only shows it.
struct PlanOptions: View {
    let vm: AskAiViewModel
    let state: AskAiUiState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading(Strings.askAiPlanMeals)
            ChipFlow(spacing: 8, lineSpacing: 8) {
                ForEach(state.mealTypes) { type in
                    chip(type.name, selected: state.chosenMeals.contains(type.key), id: "askAiMeal-\(type.key)") {
                        vm.onMeal(type.key)
                    }
                }
            }
            if state.chosenMeals.isEmpty {
                note(Strings.askAiPlanMealsNone, error: true, id: "askAiMealsNone")
            }

            heading(Strings.askAiPlanStart)
            DayStrip(days: state.days, today: state.today, selected: state.startDay, onSelect: vm.onStartDay)

            heading(Strings.askAiPlanSource)
            VStack(alignment: .leading, spacing: 0) {
                sourceRow(.new, Strings.askAiPlanSourceNew)
                sourceRow(.mine, Strings.askAiPlanSourceMine)
                sourceRow(.mix, Strings.askAiPlanSourceMix)
            }
            if state.source == .mix { mixStepper }
            if state.namesRecipes {
                heading(Strings.askAiPlanFrom)
                ChipFlow(spacing: 8, lineSpacing: 8) {
                    chip(Strings.askAiPlanAllRecipes, selected: state.listId == nil, id: "askAiFromAll") { vm.onList(nil) }
                    ForEach(state.planLists, id: \.id) { list in
                        chip(list.name, selected: state.listId == list.id, id: "askAiFromList-\(list.id)") { vm.onList(list.id) }
                    }
                }
                if state.recipeCount > 0 {
                    note(Strings.askAiPlanSent(state.recipesSent, of: state.recipeCount), id: "askAiPlanSent")
                } else {
                    note(Strings.askAiPlanNoRecipes, error: true, id: "askAiPlanNoRecipes")
                }
            }
            if state.source != .mine {
                VStack(spacing: 0) {
                    toggle(Strings.askAiPlanUseFavourites, isOn: state.useFavourites, id: "askAiUseFavourites", vm.onUseFavourites)
                    toggle(Strings.askAiPlanSkipCooked, isOn: state.skipCooked, id: "askAiSkipCooked", vm.onSkipCooked)
                }
                .padding(.top, 4)
            }
            if state.longAnswer { note(Strings.askAiPlanLong, id: "askAiPlanLong") }
        }
        .padding(.top, 8)
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.titleSmall)
            .foregroundStyle(Palette.onBackground)
            .accessibilityAddTraits(.isHeader)
            .padding(.top, 16)
            .padding(.bottom, 8)
    }

    private func note(_ text: String, error: Bool = false, id: String) -> some View {
        Text(text)
            .textStyle(Typography.bodySmall)
            .foregroundStyle(error ? Palette.error : Palette.muted)
            .padding(.top, 8)
            .accessibilityIdentifier(id)
    }

    /// A multi-select chip: outlined, ringed in paprika when chosen, read as selected.
    private func chip(_ label: String, selected: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .textStyle(Typography.labelLarge)
                .foregroundStyle(selected ? Palette.accentText : Palette.onBackground)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Capsule().strokeBorder(selected ? Palette.accentText : Palette.hairline, lineWidth: selected ? 2 : 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
        .accessibilityIdentifier(id)
    }

    private func sourceRow(_ source: AskAiPrompt.PlanSource, _ name: String) -> some View {
        Button { vm.onSource(source) } label: {
            HStack(spacing: 12) {
                RadioGlyph(selected: state.source == source)
                Text(name).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(state.source == source ? [.isButton, .isSelected] : [.isButton])
        .accessibilityIdentifier("askAiSource-\(source.rawValue)")
    }

    /// "Up to 3 of mine, the rest new" with − and +, between 1 and the week's meals.
    private var mixStepper: some View {
        HStack(spacing: 8) {
            stepButton("−", enabled: state.mix > 1, label: Strings.askAiPlanMixLess, id: "askAiMixLess") { vm.onMix(state.mix - 1) }
            Text(Strings.askAiPlanMixCount(state.mix))
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
                .multilineTextAlignment(.center)
                .frame(minWidth: 120)
                .accessibilityIdentifier("askAiMixCount")
            stepButton("+", enabled: state.mix < state.planSlots, label: Strings.askAiPlanMixMore, id: "askAiMixMore") { vm.onMix(state.mix + 1) }
        }
        .padding(.leading, 36)
        .padding(.top, 4)
    }

    private func stepButton(_ glyph: String, enabled: Bool, label: String, id: String, action: @escaping () -> Void) -> some View {
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
        .accessibilityIdentifier(id)
    }

    /// An independent choice: a switch, never a bare tick.
    private func toggle(_ title: String, isOn: Bool, id: String, _ set: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { isOn }, set: set)) {
            Text(title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
        }
        .tint(Palette.primary)
        .padding(.vertical, 6)
        .accessibilityIdentifier(id)
    }
}

/// Chips left to right, wrapping onto a new line when the next one doesn't fit (Android's FlowRow).
private struct ChipFlow: Layout {
    var spacing: CGFloat
    var lineSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = arrange(proposal.width ?? .infinity, subviews)
        let height = lines.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(lines.count - 1, 0))
        return CGSize(width: lines.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in arrange(bounds.width, subviews) {
            var x = bounds.minX
            for (index, size) in line.items {
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + lineSpacing
        }
    }

    private struct Line {
        var items: [(Int, CGSize)] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ maxWidth: CGFloat, _ subviews: Subviews) -> [Line] {
        var lines: [Line] = []
        var line = Line()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: maxWidth, height: nil))
            if !line.items.isEmpty && line.width + spacing + size.width > maxWidth {
                lines.append(line)
                line = Line()
            }
            line.width += (line.items.isEmpty ? 0 : spacing) + size.width
            line.height = max(line.height, size.height)
            line.items.append((index, size))
        }
        if !line.items.isEmpty { lines.append(line) }
        return lines
    }
}
