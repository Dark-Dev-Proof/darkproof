import SwiftUI
import BlackoutCore

// Replies, reactions and "How it got there" in the chat (spec/FORMAT.md, "Replies and reactions", "Journey notes").
// The rules are in BlackoutCore (`About`, `Reactions`, `Quote`, `Journey`); these are only the views.

/// The quote above a reply's text, or the line that says why there is none.
struct QuoteLine: Equatable {
    /// Whose message is quoted; nil when the phone has nothing to quote.
    let name: String?
    let text: String
    /// The row to scroll to, when the quoted message is in the chat.
    var row: String? = nil
}

/// A quote inside a bubble or above a card: a thin bar, the name, one line of the message.
struct QuoteView: View {
    let quote: QuoteLine
    /// On a white bubble (the user's own): black ink.
    var onWhite = false
    var tap: (() -> Void)? = nil

    var body: some View {
        if let tap, quote.row != nil {
            Button(action: tap) { lines }.buttonStyle(.plain)
                .accessibilityLabel("Reply to \(quote.name ?? "a message"): \(quote.text)")
                .accessibilityHint("Shows that message")
        } else {
            lines.accessibilityElement(children: .combine)
                .accessibilityLabel(quote.name.map { "Reply to \($0): \(quote.text)" } ?? "Reply to: \(quote.text)")
        }
    }

    private var lines: some View {
        let ink = onWhite ? Color.black : Theme.text
        return HStack(spacing: 8) {
            Capsule().fill(ink).frame(width: 2)
            VStack(alignment: .leading, spacing: 1) {
                if let name = quote.name { Text(name).scaledFont(13, .bold).lineLimit(1) }
                Text(quote.text).scaledFont(13).lineLimit(1)
            }
        }
        .foregroundStyle(ink).opacity(0.64)
        .fixedSize(horizontal: false, vertical: true)
        .contentShape(Rectangle())
    }
}

/// The reactions under a message: each with its count, the user's own outlined. Tapping one sets or takes back the
/// user's own (`tap` is nil where the group can't be written to: then they are only shown).
struct ReactionChips: View {
    let chips: [Reactions.Chip]
    var tap: ((String) -> Void)? = nil

    var body: some View {
        FlowRow(spacing: 4) {
            ForEach(chips) { c in
                if let tap {
                    Button { tap(c.text) } label: { chip(c) }
                        .buttonStyle(.plain)
                        .padding(.vertical, 8).contentShape(Rectangle()).padding(.vertical, -8) // easier to hit, same size
                        .accessibilityLabel(label(c))
                        .accessibilityHint(c.mine ? "Takes your reaction back" : "Adds your reaction")
                } else {
                    chip(c).accessibilityElement(children: .ignore).accessibilityLabel(label(c))
                }
            }
        }
    }

    private func label(_ c: Reactions.Chip) -> String {
        "\(c.text), \(c.count) \(c.count == 1 ? "person" : "people")\(c.mine ? ", you too" : "")"
    }

    private func chip(_ c: Reactions.Chip) -> some View {
        HStack(spacing: 4) {
            Text(c.text).lineLimit(1).truncationMode(.tail).frame(maxWidth: 150)
            if c.count > 1 { Text("\(c.count)").fontWeight(.semibold).monospacedDigit() }
        }
        .scaledFont(14).foregroundStyle(Theme.text)
        .padding(.horizontal, 9).padding(.vertical, 4)
        .background(Capsule().fill(c.mine ? Color(white: 0.27) : Color(white: 0.17)))
        .overlay(Capsule().strokeBorder(c.mine ? Theme.text.opacity(0.9) : Color.clear, lineWidth: 1.5))
        .padding(2).background(Capsule().fill(Theme.bg)) // a black ring, so a chip reads apart from the bubble it sits on
    }
}

/// Lays its views out in rows, left to right, starting a new row when the next one doesn't fit.
struct FlowRow: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = place(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in place(subviews, width: bounds.width) {
            for item in row.items { subviews[item.index].place(at: CGPoint(x: bounds.minX + item.x, y: bounds.minY + row.y), proposal: .unspecified) }
        }
    }

    private struct Row { var y: CGFloat, height: CGFloat = 0, width: CGFloat = 0, items: [(index: Int, x: CGFloat)] = [] }

    private func place(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows = [Row(y: 0)]
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            var row = rows[rows.count - 1]
            if !row.items.isEmpty && row.width + spacing + s.width > width {
                row = Row(y: row.y + row.height + spacing)
                rows.append(row)
            }
            let x = row.items.isEmpty ? 0 : row.width + spacing
            row.items.append((i, x)); row.width = x + s.width; row.height = max(row.height, s.height)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// The strip above the composer while a reply is being written.
struct ReplyStrip: View {
    /// "Marco", or "yourself".
    let to: String
    let text: String
    let cancel: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrowshape.turn.up.left.fill").scaledFont(14, .semibold).foregroundStyle(Theme.dim)
            VStack(alignment: .leading, spacing: 1) {
                Text("Replying to \(to)").scaledFont(14, .semibold).foregroundStyle(Theme.text).lineLimit(1)
                if !text.isEmpty { Text(text).scaledFont(13).foregroundStyle(Theme.dim).lineLimit(1) }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Button(action: cancel) {
                Image(systemName: "xmark").scaledFont(13, .bold).foregroundStyle(Theme.dim).frame(width: 40, height: 40).contentShape(Rectangle())
            }
            .accessibilityLabel("Cancel the reply")
        }
        .padding(.leading, 14).padding(.trailing, 2).padding(.vertical, 3)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Which of the user's own messages "How it got there" is open for.
struct JourneyItem: Identifiable { let id: String }

/// "How it got there": what this phone knows about the way one of its own group messages took. It reads the engine
/// while it is open, so a receipt that arrives meanwhile shows up.
struct JourneySheet: View {
    @ObservedObject var engine: Engine
    let id: String

    var body: some View {
        FittedSheet {
            VStack(alignment: .leading, spacing: 16) {
                Text("How it got there").scaledFont(21, .heavy).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                if let m = engine.allMessages.first(where: { $0.id == id }) { journey(m) }
                else { Text("This message is no longer on this phone.").scaledFont(15).foregroundStyle(Theme.dim) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder private func journey(_ m: Message) -> some View {
        let readers = engine.deliveries.readers[m.id] ?? []
        let steps = Journey.steps(written: m.ts, handedOff: engine.handedOff.contains(m.id), left: engine.leftOver[m.id], readers: readers,
                                  now: Int64(Date().timeIntervalSince1970 * 1000))
        let line = Quote.line(of: m)
        if !line.isEmpty {
            Text(line).scaledFont(14).foregroundStyle(Theme.dim).lineLimit(1)
                .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.tile, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in JourneyStepRow(step: s, last: i == steps.count - 1) }
        }
        if Journey.wasCarried(readers) {
            Label(Journey.carried, systemImage: "person.2.fill").scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
        }
        if !readers.isEmpty {
            Text("Reached means their phone got the message. It doesn't mean they have read it.")
                .scaledFont(13).foregroundStyle(Theme.faint).fixedSize(horizontal: false, vertical: true)
        }
        HStack(spacing: 10) {
            Image(systemName: "lock.fill").scaledFont(13, .semibold).foregroundStyle(Theme.dim)
            Text(Journey.sealed).scaledFont(15).foregroundStyle(Theme.dim)
            Spacer(minLength: 0)
            Text("Yes").scaledFont(15, .semibold).foregroundStyle(Theme.text)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(Theme.tile, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// One line of the journey: a round mark, what happened, and when.
private struct JourneyStepRow: View {
    let step: Journey.Step
    let last: Bool

    var body: some View {
        // With a note the line under the title says how it came; without one, when the receipt was written.
        let detail = step.detail ?? (step.kind == .reached ? step.at.map { ChatTime.stamp($0) } : nil)
        let time = step.kind == .reached ? step.later : step.at.map { ChatTime.stamp($0) }
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 3) {
                Image(systemName: symbol).scaledFont(11, .bold)
                    .foregroundStyle(step.kind == .reached ? Color.black : Theme.text)
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(step.kind == .reached ? Theme.safe : Theme.tile))
                if !last { Capsule().fill(Theme.line).frame(width: 2).frame(maxHeight: .infinity) }
            }
            // The time sits beside the title; the line under them has the whole width.
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(step.title).scaledFont(16, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if let time { Text(time).scaledFont(13).foregroundStyle(Theme.faint).monospacedDigit().multilineTextAlignment(.trailing) }
                }
                if let detail { Text(detail).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true) }
            }
            .padding(.top, 2).padding(.bottom, last ? 0 : 16)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        switch step.kind {
        case .saved, .unconfirmed: return "clock"
        case .left: return "arrow.up"
        case .reached: return "checkmark"
        }
    }
}
