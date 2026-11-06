import SwiftUI

struct PaperPicker: View {
    let selection: PaperPattern?
    var title: LocalizedStringKey = "Paper template"
    let select: (PaperPattern) -> Bool

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(PaperPattern.allCases) { pattern in
                Button {
                    if select(pattern) { dismiss() }
                } label: {
                    HStack(spacing: 16) {
                        PaperPatternView(pattern: pattern, dark: false, preview: true)
                            .frame(width: 54, height: 68)
                            .background(.white)
                            .border(Color.gray.opacity(0.5))
                            .accessibilityHidden(true)
                        Text(pattern.label)
                        Spacer()
                        if selection == pattern {
                            Image(systemName: "checkmark")
                        }
                    }
                }
                .accessibilityAddTraits(selection == pattern ? .isSelected : [])
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

struct PaperPatternView: View {
    let pattern: PaperPattern
    let dark: Bool
    var preview = false

    var body: some View {
        Canvas { context, size in
            context.withCGContext { graphics in
                if preview {
                    let scale = size.width / 612
                    guard scale > 0 else { return }
                    graphics.scaleBy(x: scale, y: scale)
                    pattern.draw(in: graphics, size: CGSize(width: 612, height: size.height / scale),
                                 dark: dark, preview: true)
                } else {
                    pattern.draw(in: graphics, size: size, dark: dark)
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
        }
    }
}
