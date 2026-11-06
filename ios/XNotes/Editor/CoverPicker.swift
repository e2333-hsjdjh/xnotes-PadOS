import SwiftUI

struct CoverPicker: View {
    let selection: NotebookCover?
    let select: (PaperPattern, NotebookCover?) -> Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(NotebookCover.allCases) { cover in
                    Button {
                        if select(.blank, cover) { dismiss() }
                    } label: {
                        HStack(spacing: 12) {
                            NotebookCoverArtwork(cover: cover).frame(width: 54, height: 72)
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
                            if select(pattern, nil) { dismiss() }
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
