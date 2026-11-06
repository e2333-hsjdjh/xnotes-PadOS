import SwiftUI

enum PaperCoordinates {
    static let name = "editor.paperCoordinates"
}

struct PaperScrollTarget: Equatable {
    let id = UUID()
    let point: CGPoint

    func position(at scale: CGFloat) -> CGPoint {
        CGPoint(x: point.x * scale, y: point.y * scale)
    }
}

struct PaperViewport<Paper: View>: View {
    let paperSize: CGSize
    let scrollTarget: PaperScrollTarget?
    @Binding var zoom: PaperZoom
    @ViewBuilder let paper: () -> Paper

    @GestureState private var magnification: CGFloat = 1

    var body: some View {
        GeometryReader { geometry in
            let base = zoom.scale(for: paperSize.width, availableWidth: geometry.size.width)
            let scale = magnification == 1 || zoom.locked ? base : PaperZoom.clamped(base * magnification)
            ScrollViewReader { reader in
                ScrollView([.horizontal, .vertical]) {
                    PaperTargetLayout(size: CGSize(width: paperSize.width * scale, height: paperSize.height * scale),
                                      target: scrollTarget?.position(at: scale)) {
                        paper()
                            .coordinateSpace(name: PaperCoordinates.name)
                            .scaleEffect(scale, anchor: .topLeading)
                            .frame(width: paperSize.width * scale, height: paperSize.height * scale, alignment: .topLeading)
                        if let scrollTarget {
                            Color.clear
                                .frame(width: 1, height: 1)
                                .id(scrollTarget.id)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                    .padding(PaperZoom.inset)
                }
                .onChange(of: scrollTarget) { _, target in
                    if let target { reader.scrollTo(target.id, anchor: .center) }
                }
                .simultaneousGesture(MagnifyGesture()
                    .updating($magnification) { value, state, _ in
                        if !zoom.locked { state = value.magnification }
                    }
                    .onEnded { value in
                        if value.magnification != 1 { zoom.adjust(value.magnification, from: base) }
                    })
            }
        }
        .background(Color(uiColor: .secondarySystemBackground))
        .accessibilityIdentifier("editor.paper")
    }
}

private struct PaperTargetLayout: Layout {
    let size: CGSize
    let target: CGPoint?

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(size))
        if subviews.count == 2, let target {
            subviews[1].place(at: CGPoint(x: bounds.minX + target.x, y: bounds.minY + target.y),
                              anchor: .center, proposal: ProposedViewSize(width: 1, height: 1))
        }
    }
}
