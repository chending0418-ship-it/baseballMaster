import UIKit

/// A print-oriented, fixed-color layout independent of the app's appearance.
@MainActor
final class ReportPDFCanvas {
    nonisolated static let pageSize = CGSize(width: 842, height: 595)
    nonisolated static let portraitPageSize = CGSize(width: 595, height: 842)
    private let size: CGSize
    private let context: UIGraphicsPDFRendererContext
    private let kind: String
    private let contextLabel: String
    private let generatedAt: Date
    private var pageNumber = 0
    private(set) var y: CGFloat = 0
    private let margin: CGFloat = 36
    private var bottom: CGFloat { size.height - 42 }
    private var width: CGFloat { size.width - margin * 2 }
    private let navy = UIColor(red: 0.06, green: 0.16, blue: 0.25, alpha: 1)
    private let green = UIColor(red: 0.10, green: 0.43, blue: 0.32, alpha: 1)
    private let secondary = UIColor(red: 0.36, green: 0.43, blue: 0.48, alpha: 1)

    private init(context: UIGraphicsPDFRendererContext, kind: String, contextLabel: String, generatedAt: Date, size: CGSize) {
        self.context = context
        self.kind = kind
        self.contextLabel = contextLabel
        self.generatedAt = generatedAt
        self.size = size
    }

    static func render(
        kind: String, title: String, subtitle: String, generatedAt: Date = Date(),
        pageSize: CGSize = ReportPDFCanvas.pageSize,
        content: (ReportPDFCanvas) -> Void
    ) -> Data {
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextTitle as String: title, kCGPDFContextAuthor as String: "BaseballMaster"]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize), format: format)
        return renderer.pdfData { context in
            let canvas = ReportPDFCanvas(context: context, kind: kind, contextLabel: title, generatedAt: generatedAt, size: pageSize)
            canvas.newPage()
            canvas.paragraph(title, size: 25, bold: true)
            canvas.paragraph(subtitle, size: 10, muted: true)
            canvas.y += 8
            content(canvas)
        }
    }

    func newPage() {
        context.beginPage()
        pageNumber += 1
        UIColor.white.setFill()
        context.fill(CGRect(origin: .zero, size: size))
        draw("BASEBALLMASTER", x: margin, y: 20, width: 250, size: 11, bold: true, color: navy)
        draw(kind, x: size.width / 2, y: 20, width: size.width / 2 - margin, size: 10, bold: true, color: green, alignment: .right)
        green.setFill()
        context.fill(CGRect(x: margin, y: 42, width: width, height: 2))
        if pageNumber > 1 {
            // An abbreviated running header never consumes the table's page area.
            draw(String(contextLabel.prefix(72)), x: margin, y: 49, width: width, size: 8, color: secondary)
        }
        let footer = "BaseballMaster  |  本地记录导出  |  \(Self.dateText(generatedAt, includesTime: true))"
        draw(footer, x: margin, y: size.height - 24, width: width - 100, size: 8, color: secondary)
        draw("第 \(pageNumber) 页", x: size.width - margin - 90, y: size.height - 24, width: 90, size: 8, color: secondary, alignment: .right)
        y = pageNumber == 1 ? 62 : 70
    }

    static func dateText(_ date: Date, includesTime: Bool = false) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = includesTime ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    func paragraph(_ text: String, size: CGFloat = 10, bold: Bool = false, muted: Bool = false) {
        let lines = wrapped(text, width: width, size: size, bold: bold)
        let leading = ceil(size * 1.5)
        for line in lines {
            ensure(leading)
            draw(line, x: margin, y: y, width: width, size: size, bold: bold, color: muted ? secondary : navy)
            y += leading
        }
        y += 7
    }

    /// Keeps a normal text record together and repeats its heading when an
    /// unusually long description spans pages.
    func textRecord(title: String, body: String) {
        let lines = wrapped(body, width: width, size: 11, bold: false)
        let headingHeight = CGFloat(wrapped(title, width: width, size: 11, bold: true).count) * 17 + 7
        let totalHeight = headingHeight + CGFloat(lines.count) * 17 + 14
        ensure(totalHeight <= (bottom - 70) / 2 ? totalHeight : headingHeight + 34)
        paragraph(title, size: 11, bold: true)
        for line in lines {
            if y + 17 > bottom {
                newPage()
                paragraph(title + " (续)", size: 11, bold: true)
            }
            draw(line, x: margin, y: y, width: width, size: 11, color: navy)
            y += 17
        }
        y += 14
    }

    func metrics(_ values: [(String, String)]) {
        let gap: CGFloat = 10
        let columns = min(6, max(1, values.count))
        let cardWidth = (width - CGFloat(columns - 1) * gap) / CGFloat(columns)
        for start in stride(from: 0, to: values.count, by: columns) {
            ensure(68)
            for (index, item) in values[start..<min(start + columns, values.count)].enumerated() {
                let x = margin + CGFloat(index) * (cardWidth + gap)
                UIColor(red: 0.94, green: 0.96, blue: 0.95, alpha: 1).setFill()
                context.fill(CGRect(x: x, y: y, width: cardWidth, height: 58))
                let valueWidth = (item.1 as NSString).size(withAttributes: [.font: UIFont.boldSystemFont(ofSize: 21)]).width
                let valueSize = min(21, 21 * (cardWidth - 20) / max(1, valueWidth))
                draw(item.1, x: x + 10, y: y + 8, width: cardWidth - 20, size: valueSize, bold: true, color: navy)
                draw(item.0, x: x + 10, y: y + 37, width: cardWidth - 20, size: 9, color: secondary)
            }
            y += 68
        }
    }

    func table(title: String, headers: [String], rows: [[String]], weights: [CGFloat]? = nil, compact: Bool = false) {
        let columnWeights = weights ?? [3] + Array(repeating: 1, count: headers.count - 1)
        precondition(columnWeights.count == headers.count && rows.allSatisfy { $0.count == headers.count })
        let sum = columnWeights.reduce(0, +)
        let widths = columnWeights.map { width * $0 / sum }
        let fontSize: CGFloat = 8.5
        let leading: CGFloat = 12
        let padding: CGFloat = compact ? 3 : 5
        let headerHeight: CGFloat = compact ? 22 : 24

        func drawHeader(continued: Bool) {
            let heading = title + (continued ? " (续)" : "")
            let headingHeight = CGFloat(wrapped(heading, width: width, size: 12, bold: true).count) * 18 + 7
            ensure(min(bottom - 70, headingHeight + headerHeight + leading + padding * 2))
            paragraph(heading, size: 12, bold: true)
            ensure(headerHeight + leading + padding * 2)
            navy.setFill()
            context.fill(CGRect(x: margin, y: y, width: width, height: headerHeight))
            var x = margin
            for index in headers.indices {
                draw(headers[index], x: x + 5, y: y + 6, width: widths[index] - 10,
                     size: 8, bold: true, color: .white, alignment: index == 0 ? .left : .center)
                x += widths[index]
            }
            y += headerHeight
        }

        drawHeader(continued: false)
        if rows.isEmpty {
            y += 6
            paragraph("暂无符合当前范围的记录。", size: 9, muted: true)
            return
        }
        for (rowIndex, row) in rows.enumerated() {
            let total = row.first == "合计"
            let lines = row.indices.map { wrapped(row[$0], width: widths[$0] - 10, size: fontSize, bold: total) }
            let lineCount = lines.map(\.count).max() ?? 1
            let fullHeight = CGFloat(lineCount) * leading + padding * 2
            var keepTogetherHeight = fullHeight
            if rows.indices.contains(rowIndex + 1), rows[rowIndex + 1].first == "合计" {
                let nextRow = rows[rowIndex + 1]
                let nextLines = nextRow.indices.map { wrapped(nextRow[$0], width: widths[$0] - 10, size: fontSize, bold: true).count }.max() ?? 1
                keepTogetherHeight += CGFloat(nextLines) * leading + padding * 2
            }
            // Keep ordinary rows intact; exceptionally long cells can continue
            // across pages. A total stays with the last player instead of alone.
            if y + keepTogetherHeight > bottom && keepTogetherHeight < bottom - 120 {
                newPage()
                drawHeader(continued: true)
            }
            var offset = 0
            while offset < lineCount {
                if y + leading + padding * 2 > bottom {
                    newPage()
                    drawHeader(continued: true)
                }
                let count = min(lineCount - offset, max(1, Int((bottom - y - padding * 2) / leading)))
                let height = CGFloat(count) * leading + padding * 2
                let shade = total ? UIColor(red: 0.88, green: 0.94, blue: 0.91, alpha: 1)
                    : (rowIndex.isMultiple(of: 2) ? UIColor(white: 0.965, alpha: 1) : .white)
                shade.setFill()
                context.fill(CGRect(x: margin, y: y, width: width, height: height))
                var x = margin
                for index in row.indices {
                    let end = min(offset + count, lines[index].count)
                    if offset < end {
                        for lineIndex in offset..<end {
                            draw(lines[index][lineIndex], x: x + 5,
                                 y: y + padding + CGFloat(lineIndex - offset) * leading,
                                 width: widths[index] - 10, size: fontSize, bold: total,
                                 color: navy, alignment: index == 0 ? .left : .center)
                        }
                    }
                    x += widths[index]
                }
                y += height
                offset += count
            }
        }
        y += compact ? 10 : 13
    }

    func situationPair(before: GameSituationSnapshot?, after: GameSituationSnapshot?, game: GameState) {
        ensure(138)
        let cardWidth = (width - 14) / 2
        for (index, item) in [("打席前", before), ("打席后 / 后续局面", after)].enumerated() {
            let x = margin + CGFloat(index) * (cardWidth + 14)
            UIColor(red: 0.94, green: 0.96, blue: 0.95, alpha: 1).setFill()
            context.fill(CGRect(x: x, y: y, width: cardWidth, height: 125))
            draw(item.0, x: x + 12, y: y + 8, width: cardWidth - 24, size: 10, bold: true, color: green)
            guard let snapshot = item.1 else {
                draw("此局面未记录", x: x + 12, y: y + 48, width: cardWidth - 24, size: 12, color: secondary)
                continue
            }
            let center = CGPoint(x: x + 66, y: y + 77)
            let diamond = UIBezierPath()
            diamond.move(to: CGPoint(x: center.x, y: center.y + 30))
            diamond.addLine(to: CGPoint(x: center.x + 32, y: center.y))
            diamond.addLine(to: CGPoint(x: center.x, y: center.y - 30))
            diamond.addLine(to: CGPoint(x: center.x - 32, y: center.y))
            diamond.close()
            green.setStroke(); diamond.lineWidth = 1.2; diamond.stroke()
            for (base, point) in [(Base.first, CGPoint(x: center.x + 32, y: center.y)), (.second, CGPoint(x: center.x, y: center.y - 30)), (.third, CGPoint(x: center.x - 32, y: center.y))] {
                let occupied = snapshot.baseRunners.contains { $0.base == base }
                (occupied ? green : UIColor.white).setFill()
                let marker = UIBezierPath(ovalIn: CGRect(x: point.x - 6, y: point.y - 6, width: 12, height: 12))
                marker.fill(); green.setStroke(); marker.stroke()
                draw("\(base.rawValue)", x: point.x - 6, y: point.y - 5, width: 12, size: 7, bold: true, color: occupied ? .white : green, alignment: .center)
            }
            let textX = x + 120
            let textWidth = cardWidth - 132
            let score = "\(snapshot.inning)局\(snapshot.isTop ? "上" : "下")  ·  比分 \(snapshot.awayRunsByInning.reduce(0,+)) : \(snapshot.homeRunsByInning.reduce(0,+))"
            draw(score, x: textX, y: y + 31, width: textWidth, size: 10, bold: true, color: navy)
            draw("B \(snapshot.balls)   S \(snapshot.strikes)   O \(snapshot.outs)", x: textX, y: y + 49, width: textWidth, size: 11, bold: true, color: navy)
            for (baseIndex, base) in Base.allCases.enumerated() {
                let runner = snapshot.baseRunners.first { $0.base == base }
                let name = runner.flatMap { runner in (game.homeTeam.players + game.awayTeam.players).first { $0.id == runner.playerID } }?.name
                let label = "\(base.title)：\(name ?? (runner == nil ? "空" : "历史球员"))"
                let lines = wrapped(label, width: textWidth, size: 8, bold: false)
                draw(lines.first ?? label, x: textX, y: y + 69 + CGFloat(baseIndex) * 15, width: textWidth, size: 8, color: navy)
            }
        }
        y += 138
    }

    private func ensure(_ height: CGFloat) {
        if y + height > bottom { newPage() }
    }

    private func wrapped(_ text: String, width: CGFloat, size: CGFloat, bold: Bool) -> [String] {
        let font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
        var result: [String] = []
        for paragraph in text.components(separatedBy: .newlines) {
            var line = ""
            for character in paragraph {
                let next = line + String(character)
                if !line.isEmpty && (next as NSString).size(withAttributes: [.font: font]).width > width {
                    result.append(line)
                    line = String(character)
                } else { line = next }
            }
            result.append(line)
        }
        return result.isEmpty ? [""] : result
    }

    private func draw(_ text: String, x: CGFloat, y: CGFloat, width: CGFloat, size: CGFloat,
                      bold: Bool = false, color: UIColor, alignment: NSTextAlignment = .left) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byClipping
        (text as NSString).draw(in: CGRect(x: x, y: y, width: width, height: size * 1.5), withAttributes: [
            .font: bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size),
            .foregroundColor: color, .paragraphStyle: paragraph
        ])
    }
}

enum ReportExportFile {
    static func url(name: String, extension fileExtension: String) throws -> URL {
        // Independent folders prevent sharing a later export from overwriting
        // a file that an earlier activity or preview is still reading.
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BaseballMasterExports", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let forbidden = CharacterSet(charactersIn: "/\\:").union(.controlCharacters)
        let sanitized = name.components(separatedBy: forbidden).joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // Filesystem limits count UTF-8 bytes, so character limits alone fail
        // for long Chinese names and emoji.
        var base = ""
        for character in sanitized {
            let next = base + String(character)
            guard next.utf8.count <= 180 else { break }
            base = next
        }
        if base.isEmpty { base = "BaseballMaster" }
        return directory.appendingPathComponent(base).appendingPathExtension(fileExtension)
    }

    static func write(_ data: Data, name: String) throws -> URL {
        let url = try url(name: name, extension: "pdf")
        try data.write(to: url, options: .atomic)
        return url
    }
}
