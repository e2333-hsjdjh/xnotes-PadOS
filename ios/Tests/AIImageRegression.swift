import ImageIO
import PDFKit
import UIKit
import UniformTypeIdentifiers

private actor ImageRequestCapture {
    var body: Data?
    func put(_ body: Data?) { self.body = body }
}

enum AIImageRegression {
    @MainActor static func run() async throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "image-tests-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let large = UIGraphicsImageRenderer(size: CGSize(width: 3200, height: 1600), format: format).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 3200, height: 1600))
            UIColor.black.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1600, height: 1600))
        }
        let image = try AIImagePreparation.prepare(large.pngData()!)
        try image.validate(decoding: true)
        let size = UIImage(data: image.png)!.size
        precondition(size == CGSize(width: 1600, height: 800))
        let cropped = try AIImagePreparation.crop(image.png, region: CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5))
        precondition(UIImage(data: cropped.png)!.size == CGSize(width: 800, height: 400))
        do { _ = try AIImagePreparation.crop(image.png, region: CGRect(x: -1, y: 0, width: 1, height: 1)); fatalError("Accepted invalid crop") } catch AIImageError.invalid {}
        let input = NSMutableData()
        let destination = CGImageDestinationCreateWithData(input, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, large.cgImage!, [kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 12.0, kCGImagePropertyGPSLongitude: 13.0]] as CFDictionary)
        precondition(CGImageDestinationFinalize(destination))
        let oriented = try AIImagePreparation.prepare(input as Data)
        precondition(UIImage(data: oriented.png)!.size == CGSize(width: 800, height: 1600))
        let source = CGImageSourceCreateWithData(oriented.png as CFData, nil)!
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil)! as NSDictionary
        precondition(properties[kCGImagePropertyGPSDictionary] == nil)
        do { _ = try AIImagePreparation.prepare(Data("invalid image".utf8)); fatalError("Accepted invalid image") } catch AIImageError.invalid {}
        let provider = AIProvider(name: "Image provider", endpoint: "https://image-provider.example/v1", key: "fake-image-key", models: ["vision"])
        var config = AIConfiguration(providers: [provider]); config.reconcileDefault()
        let capture = ImageRequestCapture()
        let history = AIHistoryStore(directory: root.appending(path: "History"))
        let sessions = AISessions(history: history, transport: { request, event in
            await capture.put(try request.urlRequest().httpBody)
            try await event(.text("Image answer"))
            return .completed
        })
        let documentA = UUID(), documentB = UUID()
        let id = sessions.newConversation(sourceDocumentID: documentA)
        var fromB = image; fromB.sourceDocumentID = documentB; fromB.sourcePageID = UUID()
        try sessions.addImage(id, image: fromB)
        precondition(sessions.conversation(id)?.relatedDocumentIDs == [documentB])
        try sessions.send(id, configuration: config) // Image-only questions are valid.
        try await waitUntil { sessions.conversation(id)?.isRunning == false }
        let body = try JSONSerialization.jsonObject(with: await capture.body!) as! [String: Any]
        let messages = body["messages"] as! [[String: Any]]
        let parts = messages.last!["content"] as! [[String: Any]]
        let url = (parts.last!["image_url"] as! [String: String])["url"]!
        precondition(url == "data:image/png;base64," + image.png.base64EncodedString())
        let reopened = AISessions(history: history)
        precondition(reopened.conversation(id)?.turns[0].images?.first?.png == image.png)
        precondition(reopened.conversation(id)?.sourceDocumentID == documentA)
        precondition(reopened.conversation(id)?.draftImages == nil)
        let associated = sessions.conversation(id)!
        precondition(associated.appearsInHistory(query: "Image answer", documentID: documentA, trash: false))
        precondition(associated.appearsInHistory(query: "", documentID: documentB, trash: false))
        precondition(!associated.appearsInHistory(query: "", documentID: UUID(), trash: false))
        precondition(!associated.appearsInHistory(query: "absent words", documentID: nil, trash: false))
        precondition(!associated.appearsInHistory(query: "", documentID: documentA, trash: true))
        let oldPicker = sessions.beginImageSelection(id)!
        sessions.updateDraft(id, text: "Another question")
        try sessions.send(id, configuration: config)
        sessions.importImage(oldPicker) { fatalError("Accepted a picker result from the submitted draft") }
        try await waitUntil { sessions.conversation(id)?.isRunning == false }
        let secondBody = try JSONSerialization.jsonObject(with: await capture.body!) as! [String: Any]
        let secondMessages = secondBody["messages"] as! [[String: Any]]
        precondition((secondMessages.first { $0["role"] as? String == "user" }?["content"] as? [[String: Any]])?.count == 2)
        for _ in 0..<6 { var copy = image; copy.id = UUID(); try sessions.addImage(id, image: copy) }
        do { var extra = image; extra.id = UUID(); try sessions.addImage(id, image: extra); fatalError("Discarded an image at the limit") } catch AIImageError.limit {}
        precondition(sessions.conversation(id)?.draftImages?.count == 6)
        let loaded = AISessions(history: history)
        precondition(loaded.conversation(id)?.draftImages?.count == 6)
        for item in loaded.conversation(id)!.draftImages! { try loaded.removeImage(id, imageID: item.id) }
        let cancelledPicker = loaded.beginImageSelection(id)!
        loaded.cancelImageSelection(cancelledPicker)
        loaded.importImage(cancelledPicker) { fatalError("Accepted a cancelled file picker result") }
        let ticket = loaded.beginImageSelection(id)!
        loaded.importImage(ticket) {
            try await Task.sleep(for: .milliseconds(100))
            return image.png
        }
        do { try loaded.send(id, configuration: config); fatalError("Sent during import") } catch AIImageError.importing {}
        let other = loaded.newConversation()
        try await waitUntil { loaded.imageImportCounts[id, default: 0] == 0 }
        precondition(loaded.conversation(id)?.draftImages?.count == 1 && loaded.conversation(other)?.draftImages == nil)
        let discarded = loaded.newConversation()
        let stale = loaded.beginImageSelection(discarded)!
        loaded.releaseEmpty(discarded)
        loaded.importImage(stale) { fatalError("Read a file after its empty conversation was discarded") }
        let cancelled = loaded.newConversation()
        let running = loaded.beginImageSelection(cancelled)!
        loaded.importImage(running) { try? await Task.sleep(for: .milliseconds(100)); return image.png }
        loaded.releaseEmpty(cancelled)
        try await Task.sleep(for: .milliseconds(150))
        precondition(loaded.conversation(cancelled) == nil)
        let failure = loaded.beginImageSelection(id)!
        loaded.importImage(failure) { Data("not an image".utf8) }
        try await waitUntil { loaded.imageImportCounts[id, default: 0] == 0 }
        precondition(loaded.imageImportErrors[id] != nil && loaded.imageImportErrors[other] == nil)
        do { try loaded.send(id, configuration: config); fatalError("Silently sent despite a failed image import") } catch AIImageError.failedImport {}
        loaded.discardImageImportError(id)
        // Use the same production renderer for notes, original PDFs and canvas captures.
        var page = NotePage(); page.text = "Rendered note"
        let note = try NotebookPageDrawing.render(page, original: nil, longestEdge: 800)
        let detail = try NotebookPageDrawing.render(page, original: nil, longestEdge: 800, region: CGRect(x: 0, y: 0, width: 0.1, height: 0.1))
        precondition(max(detail.size.width, detail.size.height) == 800)
        let canvas = try NotebookPageDrawing.render(page, original: nil, longestEdge: 800, canvas: true)
        let pdfBytes = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { ctx in
            ctx.beginPage(); ("PDF source" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.foregroundColor: UIColor.black])
        }
        let pdfFile = root.appending(path: "original.pdf")
        try pdfBytes.write(to: pdfFile)
        let pdf = PDFDocument(data: pdfBytes)!
        let pdfImage = try NotebookPageDrawing.render(NotePage(), original: pdf.page(at: 0), longestEdge: 800)
        precondition(note.pngData() != canvas.pngData() && pdfImage.pngData() != note.pngData())
        let preserved = try Data(contentsOf: pdfFile)
        precondition(preserved == pdfBytes)
        print("PASS AI images orientation+metadata, bounded PNG, crop, image-only sends, persisted context, six-image limit, session-bound imports, stale picker rejection, failure gating, note+PDF+canvas renders and original bytes")
    }

    @MainActor private static func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<200 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        fatalError("Timed out waiting for the specific image import or conversation task")
    }
}
