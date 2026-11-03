import Foundation

struct BulkRosterRow: Identifiable, Equatable {
    let lineNumber: Int
    let chineseName: String
    let englishName: String
    let numberText: String
    var id: Int { lineNumber }
}

struct BulkRosterIssue: Identifiable, Equatable {
    let lineNumber: Int
    let message: String
    var id: String { "\(lineNumber):\(message)" }
}

struct BulkRosterParseResult {
    var rows: [BulkRosterRow] = []
    var issues: [BulkRosterIssue] = []
    var warnings: [BulkRosterIssue] = []
    var canImport: Bool { !rows.isEmpty && issues.isEmpty }
}

enum BulkRosterParser {
    static let maximumRows = 200

    /// A simple three-column line format, not quoted CSV. Empty fields are significant.
    static func parse(_ text: String) -> BulkRosterParseResult {
        var result = BulkRosterParseResult()
        var firstContent = true
        var dataLineCount = 0
        var seen: [[String]: Int] = [:]
        var seenLabels: [[String]: Int] = [:]
        var normalized = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        if normalized.first == "\u{FEFF}" { normalized.removeFirst() }
        for (index, rawLine) in normalized.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let lineNumber = index + 1
            let line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            let columns = line.replacingOccurrences(of: "，", with: ",")
                .components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            if firstContent {
                firstContent = false
                if columns == ["姓名", "英文名", "背号"] { continue }
            }
            dataLineCount += 1
            guard dataLineCount <= maximumRows else {
                result.issues.append(.init(lineNumber: lineNumber, message: "每批最多导入 \(maximumRows) 名队员，请分批输入。"))
                break
            }
            guard columns.count == 3 else {
                result.issues.append(.init(lineNumber: lineNumber, message: "需要三列：姓名,英文名,背号；留空的项目也要保留逗号。"))
                continue
            }
            guard !columns[0].isEmpty || !columns[1].isEmpty else {
                result.issues.append(.init(lineNumber: lineNumber, message: "姓名和英文名至少填写一项。"))
                continue
            }
            guard columns[2].isEmpty || Player.validNumberTexts([columns[2]]) else {
                result.issues.append(.init(lineNumber: lineNumber, message: "背号请填写 0–99 或 00，也可留空；其他前导零不适用。"))
                continue
            }
            if let previous = seen[columns] {
                result.issues.append(.init(lineNumber: lineNumber, message: "与第 \(previous) 行完全重复，请删除重复行后再导入。"))
                continue
            }
            seen[columns] = lineNumber
            let label = [columns[0].isEmpty ? columns[1] : columns[0], columns[2]]
            if let previous = seenLabels[label] {
                result.warnings.append(.init(lineNumber: lineNumber, message: "与第 \(previous) 行姓名和背号相同，将分别新增为不同队员，请核对。"))
            } else { seenLabels[label] = lineNumber }
            result.rows.append(.init(lineNumber: lineNumber, chineseName: columns[0], englishName: columns[1], numberText: columns[2]))
        }
        if result.rows.isEmpty && result.issues.isEmpty {
            result.issues.append(.init(lineNumber: 1, message: "请先输入至少一名队员。"))
        }
        return result
    }
}
