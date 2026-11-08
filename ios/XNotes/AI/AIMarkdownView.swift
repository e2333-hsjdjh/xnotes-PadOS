import SwiftUI

struct AIMarkdownView: View {
    let source: String
    var body: some View {
        VStack(alignment: .leading, spacing: UITokens.markdownGap) {
            ForEach(AIMarkdown.parse(source)) { AIMarkdownBlockView(block: $0) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }
}

private struct AIMarkdownBlockView: View {
    let block: AIMarkdown.Block
    var ordered = false

    var body: some View {
        content.frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var content: some View {
        switch block.kind {
        case .paragraph, .tableCell:
            Text(block.text)
        case .header(let level):
            Text(block.text).font(headingFont(level)).bold()
        case .thematicBreak: Divider()
        case .orderedList, .unorderedList:
            VStack(alignment: .leading, spacing: UITokens.markdownGap) {
                ForEach(block.children) { child in
                    AIMarkdownBlockView(block: child, ordered: block.kind == .orderedList)
                }
            }
        case .listItem(let ordinal):
            let item = taskItem()
            HStack(alignment: .top, spacing: UITokens.markdownGap) {
                Text(verbatim: item.marker ?? (ordered ? "\(ordinal)." : "•"))
                VStack(alignment: .leading, spacing: UITokens.markdownGap) {
                    ForEach(item.children) { AIMarkdownBlockView(block: $0) }
                }
            }
        case .blockQuote:
            HStack(alignment: .top, spacing: UITokens.markdownGap) {
                Rectangle().fill(UITokens.secondaryText).frame(width: UITokens.overlayOutline)
                children
            }.fixedSize(horizontal: false, vertical: true)
        case .codeBlock(let language):
            VStack(alignment: .leading) {
                if let language, !language.isEmpty { Text(verbatim: language).foregroundStyle(UITokens.secondaryText) }
                ScrollView(.horizontal) {
                    Text(verbatim: block.plainText).font(.system(.body, design: .monospaced)).fixedSize(horizontal: true, vertical: false)
                }
                Button("Copy code", systemImage: "doc.on.doc") { UIPasteboard.general.string = block.plainText }
                    .frame(minHeight: UITokens.minTouchTarget)
            }
            .padding(UITokens.markdownGap)
            .overlay { Rectangle().strokeBorder(UITokens.secondaryText, lineWidth: UITokens.hairline) }
        case .table(let columns):
            ScrollView(.horizontal) {
                if columns.count <= 64 {
                    Grid(alignment: .leading, horizontalSpacing: UITokens.markdownIndent, verticalSpacing: UITokens.markdownGap) {
                        ForEach(block.children) { row in
                            GridRow {
                                ForEach(columns.indices, id: \.self) { column in
                                    Text(cell(row, at: column))
                                        .fontWeight(row.kind == .tableHeaderRow ? .bold : .regular)
                                        .gridColumnAlignment(alignment(columns[column].alignment))
                                }
                            }
                            if row.kind == .tableHeaderRow { Divider().gridCellColumns(max(1, columns.count)) }
                        }
                    }.padding(UITokens.markdownGap)
                } else {
                    Text(verbatim: block.children.map { row in
                        columns.indices.map { String(cell(row, at: $0).characters) }.joined(separator: " | ")
                    }.joined(separator: "\n"))
                }
            }
            .overlay { Rectangle().strokeBorder(UITokens.secondaryText, lineWidth: UITokens.hairline) }
        default: children
        }
    }

    private var children: some View {
        VStack(alignment: .leading, spacing: UITokens.markdownGap) {
            ForEach(block.children) { AIMarkdownBlockView(block: $0) }
        }
    }
    private func taskItem() -> (marker: String?, children: [AIMarkdown.Block]) {
        var children = block.children
        guard !ordered, let first = children.first, first.kind == .paragraph else { return (nil, children) }
        let text = String(first.text.characters)
        let marker: String
        if text.hasPrefix("[ ] ") { marker = "☐" }
        else if text.lowercased().hasPrefix("[x] ") { marker = "☑" }
        else { return (nil, children) }
        let end = first.text.index(first.text.startIndex, offsetByCharacters: 4)
        children[0].text = AttributedString(first.text[end...])
        return (marker, children)
    }
    private func cell(_ row: AIMarkdown.Block, at index: Int) -> AttributedString {
        row.children.first { child in
            if case .tableCell(let column) = child.kind { return column == index }
            return false
        }?.text ?? AttributedString()
    }
    private func headingFont(_ level: Int) -> Font {
        switch level { case 1: .title; case 2: .title2; case 3: .title3; default: .headline }
    }
    private func alignment(_ value: PresentationIntent.TableColumn.Alignment) -> HorizontalAlignment {
        switch value { case .left: .leading; case .center: .center; case .right: .trailing }
    }
}
