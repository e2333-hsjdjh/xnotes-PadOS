import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct AIImageAttachment: Codable, Identifiable, Equatable, Sendable {
    var id = UUID()
    let png: Data
    var sourceDocumentID: UUID?
    var sourcePageID: UUID?

    func validate(decoding: Bool = false) throws {
        guard !png.isEmpty, png.count <= AIImagePreparation.outputByteLimit,
              let source = CGImageSourceCreateWithData(png as CFData, nil),
              CGImageSourceGetType(source) as String? == UTType.png.identifier,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...AIImagePreparation.longestEdge).contains(width), (1...AIImagePreparation.longestEdge).contains(height),
              !decoding || CGImageSourceCreateImageAtIndex(source, 0, nil) != nil else { throw AIImageError.invalid }
    }
}

enum AIImagePreparation {
    static let longestEdge = 1600
    static let inputByteLimit = 32 * 1024 * 1024
    static let outputByteLimit = 8 * 1024 * 1024

    /// Downsampling decodes only a bounded thumbnail and applies EXIF orientation.
    /// Encoding a fresh PNG omits the source's location and camera metadata.
    static func prepare(_ data: Data) throws -> AIImageAttachment {
        guard data.count <= inputByteLimit else { throw AIImageError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: longestEdge,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw AIImageError.invalid }
        return AIImageAttachment(png: try encode(image))
    }

    static func crop(_ data: Data, region: CGRect) throws -> AIImageAttachment {
        guard [region.minX, region.minY, region.width, region.height].allSatisfy(\.isFinite),
              region.width > 0, region.height > 0, region.minX >= 0, region.minY >= 0,
              region.maxX <= 1, region.maxY <= 1 else { throw AIImageError.invalid }
        let prepared = try prepare(data)
        guard let source = CGImageSourceCreateWithData(prepared.png as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw AIImageError.invalid }
        let pixels = CGRect(x: region.minX * CGFloat(image.width), y: region.minY * CGFloat(image.height),
                            width: region.width * CGFloat(image.width), height: region.height * CGFloat(image.height)).integral
            .intersection(CGRect(x: 0, y: 0, width: image.width, height: image.height))
        guard !pixels.isEmpty, let cropped = image.cropping(to: pixels) else { throw AIImageError.invalid }
        return AIImageAttachment(png: try encode(cropped))
    }

    static func read(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= inputByteLimit else { throw AIImageError.tooLarge }
        let data = try Data(contentsOf: url)
        guard data.count <= inputByteLimit else { throw AIImageError.tooLarge }
        return data
    }

    private static func encode(_ image: CGImage) throws -> Data {
        let bytes = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(bytes, UTType.png.identifier as CFString, 1, nil) else { throw AIImageError.invalid }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw AIImageError.invalid }
        guard bytes.length <= outputByteLimit else { throw AIImageError.tooLarge }
        return bytes as Data
    }
}

enum AIImageError: LocalizedError {
    case invalid, tooLarge, limit, importing, failedImport
    var errorDescription: String? {
        switch self {
        case .invalid: String(localized: "The selected image could not be read. No attachment was added.")
        case .tooLarge: String(localized: "The image is too large. Use a source up to 32 MiB and a prepared PNG up to 8 MiB.")
        case .limit: String(localized: "A question can include at most six images. No images were discarded.")
        case .failedImport: String(localized: "Resolve or discard the failed image import before sending.")
        case .importing: String(localized: "Wait for image imports to finish before sending.")
        }
    }
}

struct AIImageImportTicket: Hashable, Sendable {
    let id = UUID()
    let conversationID: UUID
    let epoch: UUID
}
