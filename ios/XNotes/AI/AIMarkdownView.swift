import SwiftUI

struct AIMarkdownView: View {
    let source: String
    var body: some View {
        VStack(alignment: .leading, spacing: UITokens.markdownGap) {
            ForEach(AIMarkdown.parse(source)) { block in render(block.kind) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    @ViewBuilder private func render(_ kind: AIMarkdown.Kind) -> some View {
        switch kind {
        case .paragraph(let text): Text(inline(text))
        case .heading(let level, let text): Text(inline(text)).font(headingFont(level)).bold()
        case .rule: Divider()
        case .quote(let text):
            HStack(alignment: .top) {
                Rectangle().fill(UITokens.secondaryText).frame(width: UITokens.overlayOutline)
                Text(inline(text)).frame(maxWidth: .infinity, alignment: .leading)
            }.fixedSize(horizontal: false, vertical: true)
        case .item(let indent, let marker, let text):
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: marker)
                Text(inline(text)).frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.leading, CGFloat(indent) * UITokens.markdownIndent)
        case .code(let language, let text):
            VStack(alignment: .leading) {
                if !language.isEmpty { Text(verbatim: language).foregroundStyle(UITokens.secondaryText) }
                ScrollView(.horizontal) { Text(verbatim: text).font(.system(.body, design: .monospaced)).fixedSize(horizontal: true, vertical: false) }
                Button("Copy code", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }
                    .frame(minHeight: UITokens.minTouchTarget)
            }
            .padding(UITokens.markdownGap)
            .overlay { Rectangle().strokeBorder(UITokens.secondaryText, lineWidth: UITokens.hairline) }
        case .table(let rows, let alignment):
            ScrollView(.horizontal) {
                Grid(alignment: .leading, horizontalSpacing: UITokens.markdownIndent, verticalSpacing: UITokens.markdownGap) {
                    ForEach(rows.indices, id: \.self) { row in
                        GridRow {
                            ForEach(rows[row].indices, id: \.self) { column in
                                Text(inline(rows[row][column]))
                                    .fontWeight(row == 0 ? .bold : .regular)
                                    .gridColumnAlignment(gridAlignment(alignment[column]))
                            }
                        }
                        if row == 0 { Divider().gridCellColumns(alignment.count) }
                    }
                }.padding(UITokens.markdownGap)
            }
            .overlay { Rectangle().strokeBorder(UITokens.secondaryText, lineWidth: UITokens.hairline) }
        }
    }
    private func inline(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)
    }
    private func headingFont(_ level: Int) -> Font {
        switch level { case 1: .title; case 2: .title2; case 3: .title3; default: .headline }
    }
    private func gridAlignment(_ value: AIMarkdown.Alignment) -> HorizontalAlignment {
        switch value { case .leading: .leading; case .center: .center; case .trailing: .trailing }
    }
}
