import PencilKit
import SwiftUI

enum InkTool: String, CaseIterable, Identifiable {
    case pen = "pencil.tip"
    case marker = "highlighter"
    case eraser = "eraser"
    case lasso = "lasso"

    var id: Self { self }

    func pencilTool(color: UIColor, width: CGFloat) -> any PKTool {
        switch self {
        case .pen: PKInkingTool(.pen, color: color, width: width)
        case .marker: PKInkingTool(.marker, color: color, width: width)
        case .eraser: PKEraserTool(.vector)
        case .lasso: PKLassoTool()
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .pen: "Pen"
        case .marker: "Highlighter"
        case .eraser: "Eraser"
        case .lasso: "Lasso"
        }
    }
}

struct InkSurface: UIViewRepresentable {
    let ink: Data
    let tool: InkTool
    let color: UIColor
    let width: CGFloat
    let darkPaper: Bool
    let pencilOnly: Bool
    let changed: (Data, UUID?) -> Bool

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PKCanvasView {
        let view = PKCanvasView()
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isScrollEnabled = false
        view.overrideUserInterfaceStyle = darkPaper ? .dark : .light
        context.coordinator.load(ink, into: view)
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: PKCanvasView, context: Context) {
        context.coordinator.changed = changed
        view.overrideUserInterfaceStyle = darkPaper ? .dark : .light
        view.drawingPolicy = pencilOnly ? .pencilOnly : .anyInput
        view.tool = tool.pencilTool(color: color, width: width)
        if context.coordinator.loaded != ink {
            context.coordinator.load(ink, into: view)
        }
    }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        private(set) var loaded = Data()
        private var displayed = Data()
        private var gesture: UUID?
        var changed: ((Data, UUID?) -> Bool)?

        func load(_ ink: Data, into canvasView: PKCanvasView) {
            let drawing = (try? PKDrawing(data: ink)) ?? PKDrawing()
            loaded = ink
            displayed = drawing.dataRepresentation()
            canvasView.drawing = drawing
        }

        func canvasViewDidBeginUsingTool(_ canvasView: PKCanvasView) {
            gesture = UUID()
        }

        func canvasViewDidEndUsingTool(_ canvasView: PKCanvasView) {
            gesture = nil
        }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            let data = canvasView.drawing.dataRepresentation()
            guard data != displayed, let changed else { return }
            if changed(data, gesture) {
                loaded = data
                displayed = data
            } else {
                load(loaded, into: canvasView)
            }
        }
    }
}
