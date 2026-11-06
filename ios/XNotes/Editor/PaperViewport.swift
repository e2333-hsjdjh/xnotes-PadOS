import SwiftUI

struct PaperZoom: Equatable {
    static let coordinateSpace = "editor.paperCoordinates"
    static let inset: CGFloat = 16
    static let manualLimits: ClosedRange<CGFloat> = 0.1...4
    var mode: Mode = .fitWidth
    var locked = false

    enum Mode: Equatable {
        case fitWidth
        case manual(CGFloat)
    }

    func scale(for paperWidth: CGFloat, availableWidth: CGFloat) -> CGFloat {
        switch mode {
        case .fitWidth:
            guard paperWidth > 0, availableWidth > 2 * Self.inset else { return 1 }
            return min(1, (availableWidth - 2 * Self.inset) / paperWidth)
        case .manual(let factor):
            return factor
        }
    }

    mutating func adjust(_ factor: CGFloat, from scale: CGFloat) {
        guard !locked, factor.isFinite, factor > 0 else { return }
        mode = .manual(Self.clamped(scale * factor))
    }

    static func clamped(_ scale: CGFloat) -> CGFloat {
        min(manualLimits.upperBound, max(manualLimits.lowerBound, scale))
    }
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
                    .coordinateSpace(name: PaperZoom.coordinateSpace)
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
