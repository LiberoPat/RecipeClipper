import SwiftUI
import UIKit

/// "Ask an AI" (#351, #353; Android's `AskAiSheet`): the cook's own words, or a ready prompt filled
/// from the pantry, the favourites and what was cooked lately, then Copy prompt and Share (the
/// system share sheet, so any chat app). Nothing is sent from the app; the answer comes back as
/// shared text.
struct AskAiSheet: View {
    let vm: AskAiViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.askAiTitle).padding(.bottom, 4)
                Text(Strings.askAiIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 12)
                PromptField(
                    label: state.kind == .describe ? Strings.askAiField : Strings.askAiFieldExtra,
                    placeholder: nil, text: Binding(get: { vm.uiState.request }, set: vm.onRequestChange),
                    identifier: "askAiField"
                )
                VStack(alignment: .leading, spacing: 0) {
                    kindRow(.describe, state, name: Strings.askAiKindDescribe, hint: Strings.askAiKindDescribeHint, enabled: true)
                    kindRow(
                        .whatCanIMake, state, name: Strings.askAiKindMake,
                        hint: state.canAskWhatCanIMake ? Strings.askAiKindMakeHint : Strings.askAiKindMakeEmpty,
                        enabled: state.canAskWhatCanIMake
                    )
                    kindRow(.planMyWeek, state, name: Strings.askAiKindPlan, hint: Strings.askAiKindPlanHint, enabled: true)
                }
                .padding(.top, 8)
                PromptButtons(
                    enabled: state.canSend, copied: state.copied,
                    prompt: { vm.prompt(words: Strings.askAiWords) }, onCopied: vm.onCopied
                )
                .padding(.top, 16)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .presentationBackground(Palette.background)
        .accessibilityIdentifier("askAi")
    }

    /// One prompt to choose: a radio row, its hint saying what it will carry.
    private func kindRow(_ kind: AskAiKind, _ state: AskAiUiState, name: String, hint: String, enabled: Bool) -> some View {
        Button { vm.onKind(kind) } label: {
            HStack(alignment: .top, spacing: 12) {
                RadioGlyph(selected: state.kind == kind)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).textStyle(Typography.bodyLarge).foregroundStyle(enabled ? Palette.onBackground : Palette.muted)
                    Text(hint).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(state.kind == kind ? [.isButton, .isSelected] : [.isButton])
        .accessibilityIdentifier("askAiKind-\(kind)")
    }
}

/// The recipe screen's "Change this recipe" (#353): a field for the request, then Copy prompt and
/// Share. The prompt carries `recipe` as schema.org JSON.
struct ChangeRecipeSheet: View {
    let vm: AskAiViewModel
    let recipe: Recipe

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.changeRecipeTitle).padding(.bottom, 4)
                Text(Strings.changeRecipeIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 12)
                PromptField(
                    label: Strings.changeRecipeField, placeholder: Strings.changeRecipePlaceholder,
                    text: Binding(get: { vm.uiState.request }, set: vm.onRequestChange), identifier: "changeRecipeField"
                )
                PromptButtons(
                    enabled: state.canSendChange, copied: state.copied,
                    prompt: { vm.changePrompt(recipe: recipe, words: Strings.askAiWords) }, onCopied: vm.onCopied
                )
                .padding(.top, 16)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .presentationBackground(Palette.background)
        .accessibilityIdentifier("changeRecipe")
    }
}

/// A labelled box for the cook's words: grows with its content.
private struct PromptField: View {
    let label: String
    let placeholder: String?
    @Binding var text: String
    let identifier: String
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).textStyle(Typography.labelMedium).foregroundStyle(Palette.muted)
            TextField(
                label, text: $text,
                prompt: Text(placeholder ?? "").foregroundStyle(Palette.muted), axis: .vertical
            )
            .lineLimit(2 ... 4)
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .tint(Palette.primary)
            .textInputAutocapitalization(.sentences)
            .focused($focused)
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(focused ? Palette.primary : Palette.outline, lineWidth: focused ? 2 : 1)
            )
            .accessibilityIdentifier(identifier)
        }
    }
}

/// Copy prompt (filled, then "Copied") and Share, side by side; stacked at the accessibility sizes.
private struct PromptButtons: View {
    let enabled: Bool
    let copied: Bool
    /// The prompt now, or nil when it can't be built.
    let prompt: () -> String?
    let onCopied: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 12))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Button(copied ? Strings.askAiCopied : Strings.askAiCopy) {
                if let text = prompt() {
                    UIPasteboard.general.string = text
                    onCopied()
                }
            }
            .buttonStyle(PrimaryButtonStyle(fillWidth: true))
            .disabled(!enabled)
            .accessibilityIdentifier("askAiCopy")
            // The system share sheet, so any chat app takes it. The text is the prompt as it reads now.
            ShareLink(item: prompt() ?? "") {
                Text(Strings.askAiShare)
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(Palette.accentText)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.outline, lineWidth: 1))
            }
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.4)
            .accessibilityIdentifier("askAiShare")
        }
    }
}
