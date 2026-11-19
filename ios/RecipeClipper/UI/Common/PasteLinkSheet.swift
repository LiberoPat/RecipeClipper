import SwiftUI

/// "Paste a link" (here and on Recipes, #286), which takes a link or, since #372, any recipe text
/// (an AI's answer, a note): Go is on for any text, and a link imports while anything else goes
/// where the same text shared would. A sheet rather than an alert, whose field is one line and
/// would flatten a recipe's lines. `noRecipe`: Go found nothing to open. Android's `PasteLinkDialog`.
struct PasteLinkSheet: View {
    @Binding var text: String
    let canOpen: Bool
    let noRecipe: Bool
    let onOpen: () -> Void
    let onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Strings.pasteDialogTitle).textStyle(Typography.titleLarge).foregroundStyle(Palette.onBackground)
                VStack(alignment: .leading, spacing: 4) {
                    Text(Strings.labelLinkOrRecipe).textStyle(Typography.labelMedium).foregroundStyle(Palette.muted)
                    TextField(Strings.labelLinkOrRecipe, text: $text, axis: .vertical)
                        .lineLimit(3 ... 8)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.onBackground)
                        .tint(Palette.primary)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .padding(14)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .strokeBorder(
                                    noRecipe ? Palette.error : (focused ? Palette.primary : Palette.outline),
                                    lineWidth: focused || noRecipe ? 2 : 1
                                )
                        )
                        .accessibilityIdentifier("pasteField")
                    if noRecipe {
                        Text(Strings.pasteNoRecipe)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.error)
                            .accessibilityIdentifier("pasteNoRecipe")
                    }
                }
                HStack(spacing: 12) {
                    Button(Strings.cancel, action: onCancel)
                        .textStyle(Typography.labelLarge)
                        .foregroundStyle(Palette.accentText)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .accessibilityIdentifier("pasteCancel")
                    Button(Strings.go, action: onOpen)
                        .textStyle(Typography.labelLarge)
                        .foregroundStyle(Palette.accentText)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .disabled(!canOpen)
                        .opacity(canOpen ? 1 : 0.4)
                        .accessibilityIdentifier("pasteGo")
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .presentationBackground(Palette.background)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { focused = true }
        .accessibilityIdentifier("pasteSheet")
    }
}
