import PencilKit
import SwiftUI

enum InkTool: String, CaseIterable {
    case pen = "pencil.tip"
    case marker = "highlighter"
    case eraser = "eraser"
    case lasso = "lasso"

    func pencilTool(color: UIColor, width: CGFloat) -> any PKTool {
        switch self {
        case .pen: PKInkingTool(.pen, color: color, width: width)
        case .marker: PKInkingTool(.marker, color: color, width: width * 3)
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
    let pencilOnly: Bool
    let changed: (Data) -> Void
    let history: InkHistory

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PKCanvasView {
        let view = PKCanvasView()
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isScrollEnabled = false
        view.overrideUserInterfaceStyle = .light
        view.drawing = (try? PKDrawing(data: ink)) ?? PKDrawing()
        context.coordinator.loaded = ink
        view.delegate = context.coordinator
        history.canvas = view
        return view
    }

    func updateUIView(_ view: PKCanvasView, context: Context) {
        context.coordinator.changed = changed
        view.drawingPolicy = pencilOnly ? .pencilOnly : .anyInput
        view.tool = tool.pencilTool(color: color, width: width)
        if context.coordinator.loaded != ink {
            context.coordinator.loaded = ink
            view.drawing = (try? PKDrawing(data: ink)) ?? PKDrawing()
        }
    }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        var loaded = Data()
        var changed: ((Data) -> Void)?

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            let data = canvasView.drawing.dataRepresentation()
            guard data != loaded, !canvasView.drawing.strokes.isEmpty || !loaded.isEmpty else { return }
            loaded = data
            changed?(data)
        }
    }
}

final class InkHistory {
    weak var canvas: PKCanvasView?

    func undo() { canvas?.undoManager?.undo() }
    func redo() { canvas?.undoManager?.redo() }
}
