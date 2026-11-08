import PDFKit
import SwiftUI

struct AIScreenshotRequest: Identifiable {
    let id = UUID()
    let image: PageImage
    let documentID: UUID
    let pageID: UUID
    let snapshot: NotePage
    let original: PDFPage?
    let originalDocument: PDFDocument?
    let canvas: Bool
    let dark: Bool

    init(image: PageImage, documentID: UUID, snapshot: NotePage, original: PDFPage?, canvas: Bool, dark: Bool) {
        self.image = image
        self.documentID = documentID
        self.pageID = snapshot.id
        self.snapshot = snapshot
        self.original = original
        self.originalDocument = original?.document
        self.canvas = canvas
        self.dark = dark
    }
}

struct AIScreenshotPreview: View {
    let request: AIScreenshotRequest
    @Bindable var sessions: AISessions
    let attached: (UUID) -> Void
    @State private var target: UUID?
    @State private var error: String?

    var body: some View {
        PageImageCropView(image: request.image, title: "Screenshot for AI", confirmation: "Add to AI", allowFullImage: true, footer: { destinationControls }) { region in
            var created: UUID?
            do {
                let bitmap = try withExtendedLifetime(request.originalDocument) {
                    try NotebookPageDrawing.render(request.snapshot, original: request.original,
                        longestEdge: CGFloat(AIImagePreparation.longestEdge), canvas: request.canvas, dark: request.dark, region: region)
                }
                guard let png = bitmap.pngData() else { throw AIImageError.invalid }
                var image = try AIImagePreparation.prepare(png)
                image.sourceDocumentID = request.documentID
                image.sourcePageID = request.pageID
                let destination: UUID
                if let target { destination = target }
                else {
                    destination = sessions.newConversation(sourceDocumentID: request.documentID)
                    created = destination
                }
                try sessions.addImage(destination, image: image)
                attached(destination)
                return true
            } catch {
                sessions.releaseEmpty(created)
                self.error = error.localizedDescription
                return false
            }
        }
        .alert("Image attachment error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: { Text(error ?? "") }
        .tint(.primary)
    }
    private var destinationControls: some View {
        VStack {
            Picker("Add to conversation", selection: $target) {
                Text("New conversation").tag(Optional<UUID>.none)
                ForEach(sessions.conversations.filter { !$0.turns.isEmpty && $0.trashedAt == nil }) { conversation in
                    Text(conversation.title.isEmpty ? String(localized: "New conversation") : conversation.title).tag(Optional(conversation.id))
                }
            }
            Text("The screenshot stays local until you send the conversation.")
                .foregroundStyle(UITokens.secondaryText)
        }
        .padding()
        .background(Color(uiColor: .systemBackground))
    }
}
