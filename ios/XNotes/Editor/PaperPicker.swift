import SwiftUI

struct PaperPicker: View {
    let selection: PaperPattern?
    var parameters: PaperPatternParameters? = nil
    let select: (PaperPattern, PaperPatternParameters?) -> Bool

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(PaperPattern.allCases) { pattern in
                if pattern == .blank {
                    Button {
                        if select(.blank, nil) { dismiss() }
                    } label: {
                        row(pattern)
                    }
                } else {
                    NavigationLink {
                        PaperParametersView(pattern: pattern, parameters: selection == pattern ? parameters : nil) { draft in
                            if select(pattern, draft) { dismiss() }
                        }
                    } label: {
                        row(pattern)
                    }
                }
            }
            .navigationTitle("Paper template")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
    private func row(_ pattern: PaperPattern) -> some View {
        HStack(spacing: 16) {
            PaperPatternView(pattern: pattern, dark: false, preview: true,
                             parameters: selection == pattern ? parameters : nil)
                .frame(width: 54, height: 68)
                .background(.white)
                .border(Color.gray.opacity(0.5))
                .accessibilityHidden(true)
            Text(pattern.label)
            Spacer()
            if selection == pattern { Image(systemName: "checkmark") }
        }
        .accessibilityAddTraits(selection == pattern ? .isSelected : [])
    }

}

struct PaperPatternView: View {
    let pattern: PaperPattern
    let dark: Bool
    var preview = false
    var parameters: PaperPatternParameters? = nil

    var body: some View {
        Canvas { context, size in
            context.withCGContext { graphics in
                if preview {
                    let scale = size.width / 612
                    guard scale > 0 else { return }
                    graphics.scaleBy(x: scale, y: scale)
                    pattern.draw(in: graphics, size: CGSize(width: 612, height: size.height / scale),
                                 dark: dark, preview: true, parameters: parameters)
                } else {
                    pattern.draw(in: graphics, size: size, dark: dark, parameters: parameters)
                }
            }
        }
    }
}

extension PaperPattern {
    var label: LocalizedStringKey {
        switch self {
        case .blank: "Blank paper"
        case .ruled: "Ruled paper"
        case .grid: "Square grid"
        case .dots: "Dot grid"
        case .engineering: "Engineering paper"
        case .musicalStaves: "Musical staves"
        case .guitarTab: "Guitar tablature"
        }
    }
}

private struct PaperParametersView: View {
    let pattern: PaperPattern
    let apply: (PaperPatternParameters) -> Void
    @State private var draft: PaperPatternParameters

    init(pattern: PaperPattern, parameters: PaperPatternParameters?, apply: @escaping (PaperPatternParameters) -> Void) {
        self.pattern = pattern
        self.apply = apply
        _draft = State(initialValue: parameters ?? .defaults(for: pattern))
    }

    var body: some View {
        Form {
            PaperPatternView(pattern: pattern, dark: false, preview: true, parameters: draft)
                .frame(width: 108, height: 144)
                .background(.white)
                .border(Color.gray.opacity(0.5))
                .frame(maxWidth: .infinity)
            millimeters(pattern == .guitarTab ? "String spacing" : pattern == .musicalStaves ? "Staff line gap" : "Template spacing", value: $draft.spacingMM, range: PaperPatternParameters.spacingRange(for: pattern))
            if pattern == .engineering {
                let count = Binding(get: { draft.squaresPerBlock ?? 5 }, set: { draft.squaresPerBlock = $0 })
                Stepper(value: count, in: 2...10) {
                    LabeledContent("Squares per block", value: count.wrappedValue.formatted())
                }
            }
            if pattern == .ruled {
                millimeters("Header height", value: $draft.headerMM, range: 0...60)
                millimeters("Margin width", value: $draft.marginMM, range: 0...60)
            }
            LabeledContent("Template opacity", value: draft.opacity.formatted(.percent.precision(.fractionLength(0))))
            Slider(value: $draft.opacity, in: 0...1)
                .accessibilityLabel(Text("Template opacity"))
            Button("Restore default paper settings") { draft = .defaults(for: pattern) }
        }
        .navigationTitle(Text(pattern.label))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Apply") { apply(draft) }
            }
        }
    }

    private func millimeters(_ label: LocalizedStringKey, value: Binding<Double>, range: ClosedRange<Double>) -> some View {
        VStack {
            LabeledContent {
                Text("\(value.wrappedValue, format: .number.precision(.fractionLength(1))) mm")
            } label: {
                Text(label)
            }
            Slider(value: value, in: range)
                .accessibilityLabel(Text(label))
        }
    }
}
