import Foundation

/// Value-only block parsing, shared by streamed and saved answers.
enum AIMarkdown {
    struct Block: Equatable, Identifiable {
        let id: Int // Stable source line, independent of an unfinished final block.
        let kind: Kind
    }
    enum Alignment: Equatable { case leading, center, trailing }
    enum Kind: Equatable {
        case paragraph(String), heading(Int, String), quote(String), rule
        case item(indent: Int, marker: String, text: String)
        case code(language: String, text: String)
        case table(rows: [[String]], alignment: [Alignment])
    }

    static func parse(_ source: String) -> [Block] {
        let lines = source.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        var result: [Block] = []
        var index = 0
        var numbers: [Int: Int] = [:]
        while index < lines.count {
            let start = index
            let raw = lines[index]
            let text = raw.trimmingCharacters(in: .whitespaces)
            if text.isEmpty { index += 1; continue }
            let listPrefix = raw.range(of: "^ *([-+*] |[0-9]{1,9}[.)] )", options: .regularExpression)
            if listPrefix == nil { numbers.removeAll() }
            if let opening = fence(text) {
                index += 1
                var contents: [String] = []
                while index < lines.count {
                    let candidate = lines[index].trimmingCharacters(in: .whitespaces)
                    if let closing = fence(candidate), closing.character == opening.character,
                       closing.count >= opening.count, closing.info.isEmpty { break }
                    contents.append(lines[index]); index += 1
                }
                if index < lines.count { index += 1 }
                result.append(Block(id: start, kind: .code(language: opening.info, text: contents.joined(separator: "\n"))))
                continue
            }
            if index + 1 < lines.count, let header = cells(raw), let delimiter = cells(lines[index + 1]),
               !header.isEmpty, header.count <= 64, header.count == delimiter.count, delimiter.allSatisfy({ $0.range(of: "^:?-{3,}:?$", options: .regularExpression) != nil }) {
                let alignment: [Alignment] = delimiter.map { $0.hasSuffix(":") ? ($0.hasPrefix(":") ? .center : .trailing) : .leading }
                var rows = [header]
                index += 2
                while index < lines.count, let row = cells(lines[index]), !lines[index].trimmingCharacters(in: .whitespaces).isEmpty {
                    rows.append(Array((row + Array(repeating: "", count: header.count)).prefix(header.count)))
                    index += 1
                }
                result.append(Block(id: start, kind: .table(rows: rows, alignment: alignment)))
                continue
            }
            if let heading = heading(text) {
                result.append(Block(id: start, kind: .heading(heading.level, heading.text))); index += 1; continue
            }
            if index + 1 < lines.count, let level = setext(lines[index + 1]) {
                result.append(Block(id: start, kind: .heading(level, text))); index += 2; continue
            }
            if isRule(text) { result.append(Block(id: start, kind: .rule)); index += 1; continue }
            if text.hasPrefix(">") {
                var quote: [String] = []
                while index < lines.count {
                    var line = lines[index].trimmingCharacters(in: .whitespaces)
                    guard line.hasPrefix(">") else { break }
                    while line.hasPrefix(">") { line.removeFirst(); line = line.trimmingCharacters(in: .whitespaces) }
                    quote.append(line); index += 1
                }
                result.append(Block(id: start, kind: .quote(quote.joined(separator: "\n")))); continue
            }
            if let prefix = listPrefix {
                let lead = String(raw[prefix])
                let indent = lead.prefix(while: { $0 == " " }).count / 2
                var body = String(raw[prefix.upperBound...])
                var marker = lead.trimmingCharacters(in: .whitespaces)
                numbers = numbers.filter { $0.key <= indent }
                if let startNumber = Int(marker.dropLast()) {
                    let number = numbers[indent].map { $0 + 1 } ?? startNumber
                    numbers[indent] = number
                    marker = "\(number)."
                } else {
                    numbers[indent] = nil
                    marker = "•"
                }
                if marker == "•", body.hasPrefix("[ ] ") || body.lowercased().hasPrefix("[x] ") {
                    marker = body.lowercased().hasPrefix("[x]") ? "☑" : "☐"
                    body = String(body.dropFirst(4))
                }
                result.append(Block(id: start, kind: .item(indent: indent, marker: marker, text: body))); index += 1; continue
            }
            if raw.hasPrefix("    ") {
                var contents: [String] = []
                while index < lines.count && lines[index].hasPrefix("    ") { contents.append(String(lines[index].dropFirst(4))); index += 1 }
                result.append(Block(id: start, kind: .code(language: "", text: contents.joined(separator: "\n")))); continue
            }
            // Per-line paragraphs preserve line breaks while streaming; inline styles use Foundation.
            result.append(Block(id: start, kind: .paragraph(raw))); index += 1
        }
        return result
    }

    private static func fence(_ line: String) -> (character: Character, count: Int, info: String)? {
        guard let character = line.first, character == "`" || character == "~" else { return nil }
        let count = line.prefix(while: { $0 == character }).count
        guard count >= 3 else { return nil }
        let info = line.dropFirst(count).trimmingCharacters(in: .whitespaces)
        if character == "`" && info.contains("`") { return nil }
        return (character, count, info)
    }
    private static func heading(_ line: String) -> (level: Int, text: String)? {
        let count = line.prefix(while: { $0 == "#" }).count
        guard (1...6).contains(count), line.dropFirst(count).first == " " else { return nil }
        let text = line.dropFirst(count).trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: " +#+ *$", with: "", options: .regularExpression)
        return (count, text)
    }
    private static func setext(_ line: String) -> Int? {
        let value = line.trimmingCharacters(in: .whitespaces)
        if !value.isEmpty && value.allSatisfy({ $0 == "=" }) { return 1 }
        if !value.isEmpty && value.allSatisfy({ $0 == "-" }) { return 2 }
        return nil
    }
    private static func isRule(_ line: String) -> Bool {
        let value = line.filter { !$0.isWhitespace }
        return value.count >= 3 && ["-", "*", "_"].contains(String(value.first!)) && value.allSatisfy { $0 == value.first }
    }

    /// Pipes inside code spans or escaped pipes are cell content, not separators.
    static func cells(_ line: String) -> [String]? {
        var result: [String] = [], current = "", escaped = false, codeTicks = 0
        let characters = Array(line.trimmingCharacters(in: .whitespaces))
        var index = 0, separators = 0
        while index < characters.count {
            let character = characters[index]
            if escaped { current += character == "|" ? "|" : "\\" + String(character); escaped = false; index += 1; continue }
            if character == "\\" { escaped = true; index += 1; continue }
            if character == "`" {
                var count = 1
                while index + count < characters.count && characters[index + count] == "`" { count += 1 }
                if codeTicks == 0 { codeTicks = count } else if codeTicks == count { codeTicks = 0 }
                current += String(repeating: "`", count: count); index += count; continue
            }
            if character == "|" && codeTicks == 0 { result.append(current); current = ""; separators += 1 }
            else { current.append(character) }
            index += 1
        }
        if escaped { current.append("\\") }
        result.append(current)
        guard separators > 0 else { return nil }
        if characters.first == "|" { result.removeFirst() }
        if characters.last == "|", result.last == "" { result.removeLast() }
        return result.map { $0.trimmingCharacters(in: .whitespaces) }
    }
}
