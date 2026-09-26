import SwiftUI

/// The share extension's card: a compact confirmation at the bottom of the screen, over the
/// app that shared. Capture stays frictionless: nothing to confirm, and once saved the card
/// dismisses itself after a moment (Done dismisses it at once).
struct ShareImportView: View {
    let vm: ShareImportViewModel
    /// Saved, or the user is done with the card: the request completes.
    let onDone: () -> Void
    /// Cancel or Close: the import (if still running) stops and writes nothing.
    let onCancel: () -> Void

    /// How long the saved card stays up before dismissing itself.
    static let savedDismissDelay: Duration = .milliseconds(2500)

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            card
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.2).ignoresSafeArea())
        .task(id: vm.uiState) {
            guard case .saved = vm.uiState else { return }
            do {
                try await Task.sleep(for: Self.savedDismissDelay)
                onDone()
            } catch {
                // The state changed or the card went away first.
            }
        }
        // A list (#149) once added: written first, then the card dismisses itself like "Saved".
        .task(id: vm.receiveList?.uiState.added) {
            guard let list = vm.receiveList, list.uiState.added != nil else { return }
            await list.currentWrite?.value
            do {
                try await Task.sleep(for: Self.savedDismissDelay)
                onDone()
            } catch {
                // The card went away first.
            }
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(20)
        .frame(maxWidth: 520, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.background))
        .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(Palette.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.15), radius: 16, y: 4)
        .tint(Palette.accentText)
    }

    @ViewBuilder
    private var content: some View {
        switch vm.uiState {
        case .loading:
            HStack(spacing: 12) {
                ProgressView()
                Text(Strings.shareGettingRecipe)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.onBackground)
                Spacer(minLength: 0)
            }
            buttons { Button(Strings.cancel, action: onCancel).buttonStyle(TextActionStyle()) }

        case .saved(let title):
            Text(Strings.shareSaved.uppercased())
                .textStyle(Typography.labelMedium)
                .foregroundStyle(Palette.accentText)
            Text(title)
                .textStyle(Typography.titleLarge)
                .foregroundStyle(Palette.onBackground)
                .lineLimit(3)
            Text(Strings.shareOpenToCook)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.muted)
            buttons { Button(Strings.done, action: onDone).buttonStyle(PrimaryButtonStyle()) }

        case .failed(let error):
            message(Strings.message(for: error))
            buttons {
                Button(Strings.close, action: onCancel).buttonStyle(TextActionStyle())
                Button(Strings.tryAgain, action: vm.onRetry).buttonStyle(PrimaryButtonStyle())
            }

        case .notKept(let title):
            Text(title)
                .textStyle(Typography.titleLarge)
                .foregroundStyle(Palette.onBackground)
                .lineLimit(3)
            message(Strings.recipeNotKept)
            Text(Strings.shareUnlockHint)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.muted)
            buttons { Button(Strings.close, action: onCancel).buttonStyle(PrimaryButtonStyle()) }

        case .noLink:
            message(Strings.shareNoLink)
            buttons { Button(Strings.close, action: onCancel).buttonStyle(TextActionStyle()) }

        case .list:
            if let list = vm.receiveList {
                listContent(list)
            }
        }
    }

    /// A list sent from another phone (#149): its lines to tick, then where they went.
    @ViewBuilder
    private func listContent(_ list: ReceiveListViewModel) -> some View {
        if let added = list.uiState.added {
            Text((added == .pantry ? Strings.receiveListAddedPantry : Strings.whatINeedAdded).uppercased())
                .textStyle(Typography.labelMedium)
                .foregroundStyle(Palette.accentText)
            buttons {
                Button(Strings.done) {
                    // Never end the extension before the lines are written.
                    Task {
                        await list.currentWrite?.value
                        onDone()
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        } else {
            // Short lists sit in the card; a long one scrolls inside it.
            ViewThatFits(in: .vertical) {
                ReceiveListView(vm: list)
                ScrollView { ReceiveListView(vm: list) }
            }
            .frame(maxHeight: 480)
            buttons { Button(Strings.cancel, action: onCancel).buttonStyle(TextActionStyle()) }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Trailing buttons in a row, stacked when an accessibility text size won't fit them.
    private func buttons<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                content()
            }
            VStack(alignment: .trailing, spacing: 8) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}
