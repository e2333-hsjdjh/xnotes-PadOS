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
        case .cornell: "Cornell notes"
        case .calligraphy: "Calligraphy guide"
        case .isometricGrid: "Isometric grid"
        case .isometricDots: "Isometric dots"
        case .polar: "Polar grid"
        case .semilog: "Semi-log paper"
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
            millimeters(spacingLabel, value: $draft.spacingMM, range: PaperPatternParameters.spacingRange(for: pattern))
            if pattern == .engineering {
                let count = Binding(get: { draft.squaresPerBlock ?? 5 }, set: { draft.squaresPerBlock = $0 })
                Stepper(value: count, in: 2...10) {
                    LabeledContent("Squares per block", value: count.wrappedValue.formatted())
                }
            }
            if pattern == .polar {
                let count = Binding(get: { draft.spokes ?? 24 }, set: { draft.spokes = $0 })
                Stepper(value: count, in: 4...72) {
                    LabeledContent("Spokes", value: count.wrappedValue.formatted())
                }
            }
            if pattern == .semilog {
                let count = Binding(get: { draft.decades ?? 3 }, set: { draft.decades = $0 })
                Stepper(value: count, in: 1...6) {
                    LabeledContent("Logarithmic decades", value: count.wrappedValue.formatted())
                }
            }
            if pattern == .cornell {
                let cue = Binding(get: { draft.cueFraction ?? 0.3 }, set: { draft.cueFraction = $0 })
                LabeledContent("Cue column width", value: cue.wrappedValue.formatted(.percent.precision(.fractionLength(0))))
                Slider(value: cue, in: 0.15...0.45, step: 0.05).accessibilityLabel(Text("Cue column width"))
                let summary = Binding(get: { draft.summaryMM ?? 50 }, set: { draft.summaryMM = $0 })
                millimeters("Summary height", value: summary, range: 25...90)
            }
            if pattern == .calligraphy {
                let height = Binding(get: { draft.xHeight ?? 5 }, set: { draft.xHeight = $0 })
                let angle = Binding(get: { draft.slant ?? 7 }, set: { draft.slant = $0 })
                LabeledContent("X-height in nib widths", value: height.wrappedValue.formatted())
                Slider(value: height, in: 3...8, step: 0.5).accessibilityLabel(Text("X-height in nib widths"))
                LabeledContent("Slant angle", value: "\(Int(angle.wrappedValue))°")
                Slider(value: angle, in: 0...20, step: 1).accessibilityLabel(Text("Slant angle"))
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

    private var spacingLabel: LocalizedStringKey {
        switch pattern {
        case .guitarTab: "String spacing"
        case .musicalStaves: "Staff line gap"
        case .calligraphy: "Nib width"
        case .isometricGrid: "Triangle side"
        case .isometricDots: "Dot spacing"
        case .polar: "Ring spacing"
        case .semilog: "Linear spacing"
        default: "Template spacing"
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
