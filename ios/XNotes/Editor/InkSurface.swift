import PencilKit
import SwiftUI

enum InkTool: String, CaseIterable, Identifiable {
    case pen = "pencil.tip"
    case marker = "highlighter"
    case eraser = "eraser"
    case lasso = "lasso"

    var id: Self { self }

    func pencilTool(color: UIColor, width: CGFloat, eraser: InkEraserMode = .stroke) -> any PKTool {
        switch self {
        case .pen: PKInkingTool(.pen, color: color, width: width)
        case .marker: PKInkingTool(.marker, color: color, width: width)
        case .eraser: eraser.makeTool()
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

enum InkEraserMode: String, CaseIterable {
    case stroke, partial

    var label: LocalizedStringKey {
        switch self {
        case .stroke: "Erase whole stroke"
        case .partial: "Erase part of stroke"
        }
    }

    func makeTool() -> PKEraserTool {
        switch self {
        case .stroke: PKEraserTool(.vector)
        case .partial: PKEraserTool(.bitmap)
        }
    }
}

struct InkSurface: UIViewRepresentable {
    let ink: Data
    let tool: InkTool
    let color: UIColor
    let width: CGFloat
    let eraser: InkEraserMode
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
        context.coordinator.setTool(tool.pencilTool(color: color, width: width, eraser: eraser), on: view)
        if context.coordinator.loaded != ink {
            context.coordinator.load(ink, into: view)
        }
    }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        private(set) var loaded = Data()
        private var displayed = Data()
        private var gesture: UUID?
        private var pendingTool: (any PKTool)?

        func setTool(_ tool: any PKTool, on canvas: PKCanvasView) {
            if gesture != nil {
                pendingTool = tool
            } else {
                canvas.tool = tool
            }
        }
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
            if let pendingTool {
                self.pendingTool = nil
                canvasView.tool = pendingTool
            }
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
