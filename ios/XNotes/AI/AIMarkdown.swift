import Foundation

/// Foundation's complete block intents preserve nested CommonMark structure and inline attributes.
enum AIMarkdown {
    struct Block: Identifiable {
        let id: Int
        let kind: PresentationIntent.Kind
        var text: AttributedString
        var children: [Block]
        var plainText: String { String(text.characters) + children.map(\.plainText).joined(separator: "\n") }
    }
    private final class Builder {
        let id: Int
        let kind: PresentationIntent.Kind
        var text = AttributedString()
        var children: [Builder] = []
        init(_ intent: PresentationIntent.IntentType) { id = intent.identity; kind = intent.kind }
        func value() -> Block { Block(id: id, kind: kind, text: text, children: children.map { $0.value() }) }
    }

    static func parse(_ source: String) -> [Block] {
        guard let parsed = try? AttributedString(markdown: source, options: .init(interpretedSyntax: .full, failurePolicy: .returnPartiallyParsedIfPossible)) else {
            return [Block(id: 0, kind: .paragraph, text: AttributedString(source), children: [])]
        }
        var roots: [Builder] = []
        var nodes: [Int: Builder] = [:]
        for run in parsed.runs {
            guard let intent = run.presentationIntent else { continue }
            var parent: Builder?
            for component in intent.components.reversed() {
                let node: Builder
                if let existing = nodes[component.identity] { node = existing }
                else {
                    node = Builder(component)
                    nodes[node.id] = node
                    if let parent { parent.children.append(node) } else { roots.append(node) }
                }
                parent = node
            }
            var text = AttributedString(parsed[run.range])
            text.presentationIntent = nil
            parent?.text.append(text)
        }
        if roots.isEmpty && !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return [Block(id: 0, kind: .paragraph, text: AttributedString(source), children: [])]
        }
        return roots.map { $0.value() }
    }
}
