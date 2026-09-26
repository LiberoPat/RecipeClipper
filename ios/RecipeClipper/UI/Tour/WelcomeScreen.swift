import SwiftUI

/// The first-run welcome (#151): one card at a time, Skip at the top, Back and Next at the foot,
/// and on the last card "Try it with a sample recipe" or "Start". Shown full screen over the
/// app. The card scrolls, so the largest Dynamic Type sizes still reach every word. Android's
/// `WelcomeScreen`.
struct WelcomeScreen: View {
    let vm: WelcomeViewModel
    let onExit: (WelcomeExit) -> Void

    var body: some View {
        let state = vm.uiState
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                if !state.isLast {
                    Button(Strings.welcomeSkip, action: vm.onDone)
                        .buttonStyle(TextActionStyle(color: Palette.muted))
                        .accessibilityIdentifier("welcome.skip")
                }
            }
            .frame(minHeight: 48)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(title(state.card))
                        .textStyle(Typography.headlineMedium)
                        .foregroundStyle(Palette.onBackground)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.bottom, 6)
                    ForEach(lines(state.card, chefMode: state.chefMode), id: \.self) { line in
                        Text(line)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 24)
                .padding(.bottom, 16)
            }

            if state.isLast {
                VStack(spacing: 8) {
                    Button(Strings.welcomeTrySample) { vm.onTrySample() }
                        .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                        .disabled(state.opening)
                        .accessibilityIdentifier("welcome.trySample")
                    Button(action: vm.onDone) {
                        Text(Strings.welcomeStart).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(OutlinedActionStyle())
                    .disabled(state.opening)
                    .accessibilityIdentifier("welcome.start")
                }
                .padding(.bottom, 8)
            }

            HStack {
                HStack {
                    if state.page > 0 {
                        Button(Strings.welcomeBack, action: vm.onPrevious)
                            .buttonStyle(TextActionStyle(color: Palette.muted))
                            .accessibilityIdentifier("welcome.back")
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity)
                PageDots(page: state.page, count: state.cards.count)
                HStack {
                    Spacer(minLength: 0)
                    if !state.isLast {
                        Button(Strings.welcomeNext, action: vm.onNext)
                            .buttonStyle(PrimaryButtonStyle())
                            .accessibilityIdentifier("welcome.next")
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .frame(minHeight: 56)
            .padding(.bottom, 8)
        }
        .padding(.horizontal, 24)
        .readableColumn()
        .screenBackground()
        .onChange(of: state.exit) { _, exit in
            if let exit { onExit(exit) }
        }
    }

    private func title(_ card: WelcomeCard) -> String {
        switch card {
        case .app: Strings.welcomeAppTitle
        case .clip: Strings.welcomeClipTitle
        case .daily: Strings.welcomeDailyTitle
        case .weekly: Strings.welcomeWeeklyTitle
        }
    }

    private func lines(_ card: WelcomeCard, chefMode: Bool) -> [String] {
        switch card {
        case .app: [Strings.welcomeAppBody, Strings.welcomeAppOffline]
        case .clip: [Strings.welcomeClipShare, Strings.welcomeClipPaste, Strings.welcomeClipType]
        case .daily:
            [Strings.welcomeDailyServings, Strings.welcomeDailyLists, Strings.welcomeDailyCook]
                + (chefMode ? [Strings.welcomeDailyChef] : [])
        case .weekly:
            [Strings.welcomeWeeklyPlan, Strings.welcomeWeeklyNeed, Strings.welcomeWeeklyGroceries, Strings.welcomeWeeklyPantry]
        }
    }
}

/// Where the cards stand: read as "Card 2 of 4", drawn as dots.
private struct PageDots: View {
    let page: Int
    let count: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { index in
                Circle()
                    .fill(index == page ? Palette.accentText : Palette.outline)
                    .frame(width: 8, height: 8)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Strings.welcomePage(page + 1, of: count))
        .accessibilityIdentifier("welcome.page")
    }
}
