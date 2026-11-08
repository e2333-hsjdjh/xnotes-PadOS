import SwiftUI

struct AIImageStrip: View {
    let images: [AIImageAttachment]
    var remove: ((UUID) -> Void)?
    @State private var preview: AIImageAttachment?

    var body: some View {
        ScrollView(.horizontal) {
            HStack {
                ForEach(images) { attachment in
                    VStack {
                        Button {
                            preview = attachment
                        } label: {
                            AIImageThumbnail(attachment: attachment)
                        }
                        .accessibilityLabel(Text("Preview attached image"))
                        if let remove {
                            Button("Remove image", systemImage: "xmark") { remove(attachment.id) }
                                .frame(minHeight: UITokens.minTouchTarget)
                        }
                    }
                }
            }
        }
        .sheet(item: $preview) { attachment in
            NavigationStack {
                if let bitmap = UIImage(data: attachment.png) {
                    Image(uiImage: bitmap).resizable().scaledToFit().padding()
                        .navigationTitle("Attached image")
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { preview = nil } } }
                }
            }
        }
    }
}

private struct AIImageThumbnail: View {
    let attachment: AIImageAttachment
    @State private var bitmap: UIImage?

    var body: some View {
        Group {
            if let bitmap { Image(uiImage: bitmap).resizable().scaledToFit() }
            else { ProgressView() }
        }
        .frame(width: UITokens.attachmentPreview, height: UITokens.attachmentPreview)
        .overlay { Rectangle().strokeBorder(UITokens.secondaryText, lineWidth: UITokens.hairline) }
        .onAppear { if bitmap == nil { bitmap = UIImage(data: attachment.png) } }
    }
}
