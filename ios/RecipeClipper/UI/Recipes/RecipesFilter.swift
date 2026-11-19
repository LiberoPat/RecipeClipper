import SwiftUI

/// The Recipes filter (#345, V1 "Say what it is"): All, In a list and Not in a list as plain text
/// pills with their counts, the chosen one filled in ink. An exclusive choice, so each pill is a
/// button marked selected for VoiceOver. The pills pick recipes, never ingredients, so the reading
/// view's no-chips rule is untouched.
struct RecipeFilterPills: View {
    let selected: RecipeFilter
    let counts: RecipeCounts?
    let onSelect: (RecipeFilter) -> Void

    var body: some View {
        PillFlow(spacing: 6, lineSpacing: 0) {
            ForEach(RecipeFilter.pills, id: \.self) { filter in
                pill(filter)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func pill(_ filter: RecipeFilter) -> some View {
        let on = filter == selected
        let label = Self.name(filter) + (counts.map { " \($0.of(filter))" } ?? "")
        return Button { onSelect(filter) } label: {
            Text(label)
                .textStyle(Typography.labelLarge)
                .foregroundStyle(on ? Palette.background : Palette.onBackground)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background {
                    if on {
                        Capsule().fill(Palette.onBackground)
                    } else {
                        Capsule().strokeBorder(Palette.hairline, lineWidth: 1)
                    }
                }
                // The 44pt touch target is the outer frame; the pill is the shape inside it.
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("recipes.filter.\(filter.rawValue)")
    }

    static func name(_ filter: RecipeFilter) -> String {
        switch filter {
        case .all: Strings.recipesFilterAll
        case .inAList: Strings.recipesFilterInList
        case .notInAList: Strings.recipesFilterNotInList
        case .cooked: Strings.recipesViewCooked
        case .typed: Strings.recipesViewTyped
        case .chat: Strings.recipesViewChat
        }
    }
}

/// The one muted line under the filter that says what the clean-up does to recipes in no list
/// (#345), with the cap `keep` (the old history's 50): the mockup's info glyph and a quiet panel.
struct CullNote: View {
    let keep: Int

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle")
                .foregroundStyle(Palette.muted)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .accessibilityHidden(true)
            Text(Strings.recipesCullNote(keep))
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.hairline))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("recipes.cullNote")
    }
}

/// Pills left to right, wrapping onto a new line when the next one doesn't fit (Android's FlowRow).
private struct PillFlow: Layout {
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
