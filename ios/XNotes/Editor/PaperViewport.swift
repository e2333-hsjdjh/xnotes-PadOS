import SwiftUI

enum PaperCoordinates {
    static let name = "editor.paperCoordinates"
}

struct PaperViewport<Paper: View>: View {
    let paperSize: CGSize
    @Binding var zoom: PaperZoom
    @ViewBuilder let paper: () -> Paper

    @GestureState private var magnification: CGFloat = 1

    var body: some View {
        GeometryReader { geometry in
            let base = zoom.scale(for: paperSize.width, availableWidth: geometry.size.width)
            let scale = magnification == 1 || zoom.locked ? base : PaperZoom.clamped(base * magnification)
            ScrollView([.horizontal, .vertical]) {
                paper()
                    .coordinateSpace(name: PaperCoordinates.name)
                    .scaleEffect(scale, anchor: .topLeading)
                    .frame(width: paperSize.width * scale, height: paperSize.height * scale, alignment: .topLeading)
                    .padding(PaperZoom.inset)
            }
            .simultaneousGesture(MagnifyGesture()
                .updating($magnification) { value, state, _ in
                    if !zoom.locked { state = value.magnification }
                }
                .onEnded { value in
                    if value.magnification != 1 { zoom.adjust(value.magnification, from: base) }
                })
        }
        .background(Color(uiColor: .secondarySystemBackground))
        .accessibilityIdentifier("editor.paper")
    }
}
