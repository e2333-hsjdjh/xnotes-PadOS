import SwiftUI

struct PaperSizePicker: View {
    let apply: (NotebookPaperSize) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var preset: PaperSizePreset
    @State private var landscape: Bool
    @State private var width: String
    @State private var height: String

    init(size: NotebookPaperSize, apply: @escaping (NotebookPaperSize) -> Bool) {
        self.apply = apply
        let landscape = size.width > size.height
        let portrait = landscape ? size.landscape : size
        _preset = State(initialValue: PaperSizePreset.allCases.first { $0.portrait == portrait } ?? .custom)
        _landscape = State(initialValue: landscape)
        let format = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(0...3)).grouping(.never)
        _width = State(initialValue: (size.width / NotebookPaperSize.pointsPerMillimeter).formatted(format))
        _height = State(initialValue: (size.height / NotebookPaperSize.pointsPerMillimeter).formatted(format))
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Paper size", selection: $preset) {
                    ForEach(PaperSizePreset.allCases) { item in
                        Text(label(item)).tag(item)
                    }
                }
                if preset == .custom {
                    TextField("Width (mm)", text: $width).keyboardType(.decimalPad)
                    TextField("Height (mm)", text: $height).keyboardType(.decimalPad)
                    Text("Enter a width and height between 20 and 1,000 mm.")
                        .font(.caption)
                } else {
                    Picker("Orientation", selection: $landscape) {
                        Text("Portrait").tag(false)
                        Text("Landscape").tag(true)
                    }
                    .pickerStyle(.segmented)
                }
                Text("Content keeps its position. A smaller page can hide content beyond its edges.")
                    .font(.caption)
            }
            .navigationTitle("Paper size")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        if let size, apply(size) { dismiss() }
                    }
                    .disabled(size == nil)
                }
            }
        }
    }

    private var size: NotebookPaperSize? {
        if let portrait = preset.portrait { return landscape ? portrait.landscape : portrait }
        guard let w = try? Double(width, format: .number),
              let h = try? Double(height, format: .number) else { return nil }
        let candidate = NotebookPaperSize(widthMM: w, heightMM: h)
        return candidate.isValid ? candidate : nil
    }

    private func label(_ item: PaperSizePreset) -> LocalizedStringKey {
        switch item {
        case .a4: "A4"
        case .a5: "A5"
        case .letter: "Letter"
        case .legal: "Legal"
        case .custom: "Custom"
        }
    }
}
