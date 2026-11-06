import SwiftUI

struct CoverPicker: View {
    let selection: NotebookCover?
    let parameters: NotebookCoverParameters?
    let select: (PaperPattern, NotebookCover?, NotebookCoverParameters?) -> Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(NotebookCover.allCases) { cover in
                    NavigationLink {
                        CoverParametersView(cover: cover, parameters: selection == cover ? parameters : nil) { value in
                            if select(.blank, cover, value) {
                                dismiss()
                            }
                        }
                    } label: {
                        HStack(spacing: 12) {
                            NotebookCoverArtwork(cover: cover, parameters: selection == cover ? parameters : nil).frame(width: 54, height: 72)
                            Text(cover.label)
                            Spacer()
                            if selection == cover { Image(systemName: "checkmark") }
                        }
                    }
                    .accessibilityAddTraits(selection == cover ? .isSelected : [])
                }
                Section("Paper template") {
                    ForEach(PaperPattern.allCases) { pattern in
                        Button(pattern.label) {
                            if select(pattern, nil, nil) { dismiss() }
                        }
                        .frame(minHeight: 48)
                    }
                }
            }
            .navigationTitle("Cover page")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}

private struct CoverParametersView: View {
    let cover: NotebookCover
    let apply: (NotebookCoverParameters) -> Void
    @State private var draft: NotebookCoverParameters

    init(cover: NotebookCover, parameters: NotebookCoverParameters?, apply: @escaping (NotebookCoverParameters) -> Void) {
        self.cover = cover
        self.apply = apply
        _draft = State(initialValue: parameters ?? NotebookCoverParameters())
    }

    var body: some View {
        Form {
            NotebookCoverArtwork(cover: cover, parameters: draft)
                .frame(width: 108, height: 144)
                .frame(maxWidth: .infinity)
            Section("Cover label") {
                LabeledContent("Label size", value: draft.labelScale.formatted(.percent.precision(.fractionLength(0))))
                Slider(value: $draft.labelScale, in: 0...1.4, step: 0.05)
                    .accessibilityLabel(Text("Label size"))
                ColorPicker("Label color", selection: color($draft.labelColor, default: cover.colors.label), supportsOpacity: false)
                ColorPicker("Label border", selection: color($draft.ruleColor, default: cover.colors.rule), supportsOpacity: false)
                if cover == .cloth {
                    ColorPicker("Cloth color", selection: color($draft.clothColor, default: cover.colors.paper), supportsOpacity: false)
                }
            }
            Button("Restore default cover settings") { draft = NotebookCoverParameters() }
        }
        .navigationTitle(Text(cover.label))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Apply") { apply(draft) }
            }
        }
    }

    private func color(_ value: Binding<UInt32?>, default fallback: UInt32) -> Binding<Color> {
        Binding {
            Color(cgColor: NotebookCover.color(value.wrappedValue ?? fallback))
        } set: { color in
            guard let hex = CustomInkColor.encode(UIColor(color)), let rgb = UInt32(hex, radix: 16) else { return }
            value.wrappedValue = rgb
        }
    }
}
