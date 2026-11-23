import SwiftUI

/// The plan's quiet note about a perishable meal planned later than its group keeps (#396; Android's
/// `FreshNudgeNote`): "Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?", with the one
/// swap that fixes it. It says only what the table says (a range, never a date) and moves nothing
/// until the swap is tapped.
struct FreshNudgeNote: View {
    let nudge: FreshNudge
    let onSwap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Strings.freshNudge(nudge.name, nudge.keeps))
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
            Button(action: onSwap) { Text(Strings.freshNudgeSwap(PlanDayFormat.title(nudge.swapDay))) }
                .buttonStyle(TextActionStyle())
                .padding(.leading, -12)
                .accessibilityIdentifier("freshSwap")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
