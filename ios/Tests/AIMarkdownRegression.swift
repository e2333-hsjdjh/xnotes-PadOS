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
        let blocks = AIMarkdown.parse(sample).map(\.kind)
        precondition(blocks.contains(.heading(1, "学习结论")))
        precondition(blocks.contains(.item(indent: 0, marker: "☑", text: "确定半径")))
        precondition(blocks.contains(.code(language: "swift", text: "let radius = 5\nprint(radius * radius)")))
        precondition(blocks.contains(.table(rows: [["符号", "含义"], ["x、y", "坐标"], ["r", "半径"]], alignment: [.leading, .trailing])))
        precondition(AIMarkdown.cells("| `a|b` | c\\|d |") == ["`a|b`", "c|d"])
        precondition(AIMarkdown.parse("标题\n===").first?.kind == .heading(1, "标题"))
        precondition(AIMarkdown.parse("---").first?.kind == .rule)
        precondition(AIMarkdown.parse("~~~~swift\nx\n~~~\ny").first?.kind == .code(language: "swift", text: "x\n~~~\ny"))
        precondition(AIMarkdown.parse("    let x = 1").first?.kind == .code(language: "", text: "let x = 1"))
        precondition(AIMarkdown.parse("| a | b |\n| --- | --- |\n| x |").first?.kind == .table(rows: [["a", "b"], ["x", ""]], alignment: [.leading, .leading]))
        precondition(AIMarkdown.parse("3. a\n1. b").last?.kind == .item(indent: 0, marker: "4.", text: "b"))
        for index in sample.indices { _ = AIMarkdown.parse(String(sample[..<index])) }
        precondition(AIMarkdown.parse("<script>alert(1)</script>").first?.kind == .paragraph("<script>alert(1)</script>"))
        print("PASS Markdown headings, lists, tasks, quotes, fences, indented code, aligned tables, escaped pipes and every streaming prefix")
    }
}
