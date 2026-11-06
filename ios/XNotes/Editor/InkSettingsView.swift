import SwiftUI

struct InkSettingsView: View {
    let tool: InkTool
    @Binding var preset: InkColor
    @Binding var custom: String
    @Binding var width: Double

    @Environment(\.dismiss) private var dismiss

    private var limits: ClosedRange<Double> {
        tool == .marker ? 7.5...40 : 1...20
    }

    private var chosenColor: Binding<Color> {
        Binding {
            Color(uiColor: CustomInkColor.decode(custom) ?? preset.color)
        } set: { value in
            if let encoded = CustomInkColor.encode(UIColor(value)) {
                custom = encoded
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Ink color") {
                    Picker("Preset color", selection: Binding<InkColor?>(
                        get: { CustomInkColor.decode(custom) == nil ? preset : nil },
                        set: { option in
                            guard let option else { return }
                            preset = option
                            custom = ""
                        }
                    )) {
                        if CustomInkColor.decode(custom) != nil {
                            Text("Custom").tag(Optional<InkColor>.none)
                        }
                        ForEach(InkColor.allCases, id: \.self) { option in
                            Label {
                                Text(option.label)
                            } icon: {
                                Circle().fill(Color(uiColor: option.color)).frame(width: 14, height: 14)
                            }
                            .tag(Optional(option))
                        }
                    }
                    ColorPicker("Custom ink color", selection: chosenColor, supportsOpacity: false)
                }
                Section("Width") {
                    LabeledContent("Width", value: width.formatted(.number.precision(.fractionLength(0...1))))
                    Slider(value: $width, in: limits)
                        .accessibilityLabel(Text("Width"))
                }
            }
            .navigationTitle(Text(tool.label))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { width = min(max(width, limits.lowerBound), limits.upperBound) }
        }
    }
}
