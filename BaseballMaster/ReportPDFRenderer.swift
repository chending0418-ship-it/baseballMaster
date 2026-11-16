import UIKit

/// A print-oriented, fixed-color layout independent of the app's appearance.
@MainActor
final class ReportPDFCanvas {
    nonisolated static let pageSize = CGSize(width: 842, height: 595)
    nonisolated static let portraitPageSize = CGSize(width: 595, height: 842)
    enum Style { case standard, scorekeeper }

    private let style: Style
    private let size: CGSize
    private let context: UIGraphicsPDFRendererContext
    private let kind: String
    private let contextLabel: String
    private let generatedAt: Date
    private var pageNumber = 0
    private(set) var y: CGFloat = 0
    private var margin: CGFloat { style == .scorekeeper ? 54 : 36 }
    private var bottom: CGFloat { size.height - (style == .scorekeeper ? 67 : 42) }
    private var continuationTop: CGFloat { style == .scorekeeper ? 105 : 70 }
    private var width: CGFloat { size.width - margin * 2 }
    private var navy: UIColor {
        style == .scorekeeper ? UIColor(red: 11/255, green: 25/255, blue: 51/255, alpha: 1)
            : UIColor(red: 0.06, green: 0.16, blue: 0.25, alpha: 1)
    }
    private var green: UIColor {
        style == .scorekeeper ? UIColor(red: 9/255, green: 42/255, blue: 42/255, alpha: 1)
            : UIColor(red: 0.10, green: 0.43, blue: 0.32, alpha: 1)
    }
    private let metricSurface = UIColor(red: 0.94, green: 0.96, blue: 0.95, alpha: 1)
    private let secondary = UIColor(red: 0.36, green: 0.43, blue: 0.48, alpha: 1)

    private init(context: UIGraphicsPDFRendererContext, kind: String, contextLabel: String, generatedAt: Date, size: CGSize, style: Style) {
        self.style = style
        self.context = context
        self.kind = kind
        self.contextLabel = contextLabel
        self.generatedAt = generatedAt
        self.size = size
    }

    static func render(
        kind: String, title: String, subtitle: String, generatedAt: Date = Date(),
        pageSize: CGSize = ReportPDFCanvas.pageSize, style: Style = .standard,
        content: (ReportPDFCanvas) -> Void
    ) -> Data {
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextTitle as String: title, kCGPDFContextAuthor as String: "BaseballMaster"]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize), format: format)
        return renderer.pdfData { context in
            let canvas = ReportPDFCanvas(context: context, kind: kind, contextLabel: title, generatedAt: generatedAt, size: pageSize, style: style)
            canvas.newPage()
            canvas.paragraph(title, size: style == .scorekeeper ? 29 : 25, bold: true)
            canvas.paragraph(subtitle, size: style == .scorekeeper ? 11 : 10, muted: true)
            canvas.y += 8
            content(canvas)
        }
    }

    func newPage() {
        context.beginPage()
        pageNumber += 1
        UIColor.white.setFill()
        context.fill(CGRect(origin: .zero, size: size))
        if style == .scorekeeper {
            drawScorekeeperPage()
            return
        }
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

    private func drawScorekeeperPage() {
        drawBrandAsset("ReportScorekeeperIdentity", y: 40, height: 22)
        draw(kind, x: size.width / 2, y: 45, width: width / 2, size: 10, bold: true, color: green, alignment: .right)
        green.setFill()
        context.fill(CGRect(x: margin, y: 73, width: width, height: 1))
        UIColor(red: 0.83, green: 0.87, blue: 0.85, alpha: 1).setFill()
        context.fill(CGRect(x: margin, y: size.height - 52, width: width, height: 0.5))
        drawBrandAsset("ReportBaseballMasterSignoff", y: size.height - 44, height: 10)
        draw("第 \(pageNumber) 页", x: size.width - margin - 90, y: size.height - 45,
             width: 90, size: 9, color: secondary, alignment: .right)
        if pageNumber > 1 {
            draw(wrapped(contextLabel, width: width, size: 9, bold: false).first ?? "",
                 x: margin, y: 81, width: width, size: 9, color: secondary)
        }
        y = pageNumber == 1 ? 91 : continuationTop
    }

    private func drawBrandAsset(_ name: String, y: CGFloat, height: CGFloat) {
        guard let image = UIImage(named: name), image.size.height > 0 else { return }
        let width = height * image.size.width / image.size.height
        image.draw(in: CGRect(x: margin, y: y, width: width, height: height))
    }

    private func font(size: CGFloat, bold: Bool) -> UIFont {
        if style == .scorekeeper,
           let preferred = UIFont(name: bold ? "PingFangSC-Semibold" : "PingFangSC-Regular", size: size) {
            return preferred
        }
        return bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
    }

    static func dateText(_ date: Date, includesTime: Bool = false, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = timeZone
        formatter.dateFormat = includesTime ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    func paragraph(_ text: String, size: CGFloat = 10, bold: Bool = false, muted: Bool = false) {
        let lines = wrapped(text, width: width, size: size, bold: bold)
        let leading = ceil(size * (style == .scorekeeper ? 1.65 : 1.5))
        for line in lines {
            ensure(leading)
            draw(line, x: margin, y: y, width: width, size: size, bold: bold, color: muted ? secondary : navy)
            y += leading
        }
        y += 7
    }

    func section(title: String, paragraphs: [String]) {
        guard let first = paragraphs.first else { return }
        let firstHeight = min(2, wrapped(first, width: width, size: 12, bold: false).count) * 20
        ensure(CGFloat(firstHeight) + 37)
        paragraph(title, size: 15, bold: true)
        for text in paragraphs { paragraph(text, size: 12) }
        y += 7
    }

    func highlight(title: String, detail: String) {
        let padding: CGFloat = 14
        let innerWidth = width - 2 * padding
        let heading = wrapped(title, width: innerWidth, size: 15, bold: true)
        let lines = wrapped(detail, width: innerWidth, size: 12, bold: false)
        let height = 2 * padding + CGFloat(heading.count) * 22 + CGFloat(lines.count) * 20 + 3
        guard height <= bottom - continuationTop else {
            section(title: title, paragraphs: [detail])
            return
        }
        ensure(height)
        metricSurface.setFill()
        context.fill(CGRect(x: margin, y: y, width: width, height: height))
        var baseline = y + padding
        for line in heading {
            draw(line, x: margin + padding, y: baseline, width: innerWidth, size: 15, bold: true, color: green)
            baseline += 22
        }
        baseline += 3
        for line in lines {
            draw(line, x: margin + padding, y: baseline, width: innerWidth, size: 12, color: navy)
            baseline += 20
        }
        y += height + 17
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
                metricSurface.setFill()
                context.fill(CGRect(x: x, y: y, width: cardWidth, height: 58))
                let numberFont = style == .scorekeeper ? UIFont(name: "Arial-BoldMT", size: 21) : nil
                let valueWidth = (item.1 as NSString).size(withAttributes: [.font: numberFont ?? font(size: 21, bold: true)]).width
                let valueSize = min(21, 21 * (cardWidth - 20) / max(1, valueWidth))
                draw(item.1, x: x + 10, y: y + 8, width: cardWidth - 20, size: valueSize, bold: true, color: navy,
                     fontOverride: numberFont?.withSize(valueSize))
                draw(item.0, x: x + 10, y: y + 37, width: cardWidth - 20, size: 9, color: secondary)
            }
            y += 68
        }
    }

    func table(title: String, headers: [String], rows: [[String]], weights: [CGFloat]? = nil, compact: Bool = false, leftAlignedColumns: Set<Int> = [0]) {
        let columnWeights = weights ?? [3] + Array(repeating: 1, count: headers.count - 1)
        precondition(columnWeights.count == headers.count && rows.allSatisfy { $0.count == headers.count })
        let sum = columnWeights.reduce(0, +)
        let widths = columnWeights.map { width * $0 / sum }
        let fontSize: CGFloat = style == .scorekeeper ? 11 : 8.5
        let leading: CGFloat = style == .scorekeeper ? 17 : 12
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
                     size: style == .scorekeeper ? 10 : 8, bold: true, color: .white,
                     alignment: leftAlignedColumns.contains(index) ? .left : .center)
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
                                 color: navy, alignment: leftAlignedColumns.contains(index) ? .left : .center)
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
        let font = font(size: size, bold: bold)
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
                      bold: Bool = false, color: UIColor, alignment: NSTextAlignment = .left,
                      fontOverride: UIFont? = nil) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byClipping
        (text as NSString).draw(in: CGRect(x: x, y: y, width: width, height: size * 1.5), withAttributes: [
            .font: fontOverride ?? font(size: size, bold: bold),
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
