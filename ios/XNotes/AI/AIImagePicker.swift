import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct AIImagePickerRequest: Identifiable {
    enum Kind { case photos, files }
    let ticket: AIImageImportTicket
    let kind: Kind
    var id: UUID { ticket.id }
}

struct AIImageFilePicker: UIViewControllerRepresentable {
    let selected: (URL?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(selected: selected) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selected: (URL?) -> Void
        init(selected: @escaping (URL?) -> Void) { self.selected = selected }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { selected(urls.first) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { selected(nil) }
    }
}

struct AIImagePhotoPicker: UIViewControllerRepresentable {
    let selected: (NSItemProvider?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(selected: selected) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let selected: (NSItemProvider?) -> Void
        init(selected: @escaping (NSItemProvider?) -> Void) { self.selected = selected }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) { selected(results.first?.itemProvider) }
    }
}

enum AIPhotoTransfer {
    private final class Transfer: @unchecked Sendable {
        private let lock = NSLock()
        private var progress: Progress?
        private var cancelled = false
        func install(_ progress: Progress) {
            lock.lock(); defer { lock.unlock() }
            self.progress = progress
            if cancelled { progress.cancel() }
        }
        func cancel() {
            lock.lock(); defer { lock.unlock() }
            cancelled = true
            progress?.cancel()
        }
    }

    static func read(_ provider: NSItemProvider) async throws -> Data {
        guard let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) else { throw AIImageError.invalid }
        let transfer = Transfer()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                let progress = provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                    do {
                        if let error { throw error }
                        guard let url else { throw AIImageError.invalid }
                        // The provider's temporary URL is valid only inside this callback.
                        continuation.resume(returning: try AIImagePreparation.read(url))
                    } catch { continuation.resume(throwing: error) }
                }
                transfer.install(progress)
            }
        }, onCancel: { transfer.cancel() })
    }
}
