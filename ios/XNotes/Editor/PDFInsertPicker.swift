import PDFKit
import SwiftUI

struct PDFInsertPicker: View {
    let document: PDFDocument
    let insert: (IndexSet) -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var selected: IndexSet

    init(document: PDFDocument, insert: @escaping (IndexSet) -> Bool) {
        self.document = document
        self.insert = insert
        _selected = State(initialValue: IndexSet(integersIn: 0..<document.pageCount))
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(0..<document.pageCount, id: \.self) { index in
                    Button {
                        if selected.contains(index) { selected.remove(index) }
                        else { selected.insert(index) }
                    } label: {
                        HStack {
                            PDFInsertPreview(document: document, index: index)
                            Text("Page \(index + 1)")
                            Spacer()
                            Image(systemName: selected.contains(index) ? "checkmark.circle.fill" : "circle")
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .accessibilityAddTraits(selected.contains(index) ? .isSelected : [])
                }
            }
            .navigationTitle("Insert PDF pages")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button(selected.count == document.pageCount ? "Clear page selection" : "Select all pages") {
                        selected = selected.count == document.pageCount ? [] : IndexSet(integersIn: 0..<document.pageCount)
                    }
                    Button("Insert \(selected.count) pages") {
                        if insert(selected) { dismiss() }
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }
}

private struct PDFInsertPreview: View {
    let document: PDFDocument
    let index: Int
    @State private var image: UIImage?

    var body: some View {
        RoundedRectangle(cornerRadius: 4)
            .fill(.quaternary)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFit() }
            }
            .frame(width: 52, height: 66)
            .accessibilityHidden(true)
            .task {
                image = document.page(at: index)?.thumbnail(of: CGSize(width: 104, height: 132), for: .cropBox)
            }
    }
}
