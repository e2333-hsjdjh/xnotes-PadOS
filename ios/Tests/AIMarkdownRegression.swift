import Foundation

enum AIMarkdownRegression {
    static let sample = """
    # 学习结论
    **重点**：公式 x^2 + y^2 = r^2 表示圆。

    ## 步骤
    - 确定圆心
    - [x] 确定半径
    1. 代入坐标
    2. 验证结果

    > 注意：这是模型回答中的引用。
    >
    > > 嵌套引用中的 **重点**。

    | 符号 | 含义 |
    | :--- | ---: |
    | x、y | 坐标 |
    | r | 半径 |

    ```swift
    let radius = 5
    print(radius * radius)
    ```
    """
    static func run() {
        let blocks = AIMarkdown.parse(sample)
        precondition(blocks.first?.kind == .header(level: 1))
        precondition(blocks.contains { $0.kind == .codeBlock(languageHint: "swift") && $0.plainText.contains("print(radius * radius)") })
        let nested = AIMarkdown.parse("3. First\n   continuation\n\n   second paragraph\n   - nested **bold**\n4. Next")
        precondition(nested.count == 1 && nested[0].kind == .orderedList)
        let item = nested[0].children[0]
        precondition(item.kind == .listItem(ordinal: 3) && item.children.count == 3)
        precondition(item.children[0].plainText.contains("continuation"))
        precondition(item.children[1].plainText == "second paragraph")
        precondition(item.children[2].kind == .unorderedList)
        precondition(item.children[2].children[0].children[0].text.runs.contains { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true })
        let quote = AIMarkdown.parse("> outer\n>\n> > inner\n> >\n> > ```swift\n> > let x = 1\n> > ```")
        precondition(quote.first?.kind == .blockQuote)
        precondition(quote[0].children.contains { $0.kind == .blockQuote })
        precondition(AIMarkdown.parse("title\n===").first?.kind == .header(level: 1))
        precondition(AIMarkdown.parse("---").first?.kind == .thematicBreak)
        let table = AIMarkdown.parse("| a | b |\n| :--- | ---: |\n| x | y |")[0]
        precondition(table.children.count == 2 && table.children[0].children[0].plainText == "a")
        for index in sample.indices { _ = AIMarkdown.parse(String(sample[..<index])) }
        precondition(AIMarkdown.parse("````swift\nx\n```\ny").first?.plainText.contains("```\ny") == true)
        print("PASS Markdown headings, lists, nested continuation paragraphs, attributed spans, nested quotes, tables and every streaming prefix")
    }
}
