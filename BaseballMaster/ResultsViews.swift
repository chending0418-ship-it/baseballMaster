import SwiftUI
import UIKit

struct BoxScoreView: View {
    @EnvironmentObject private var store: GameStore
    @State private var selectedSection = 0
    @State private var showEndReasonSheet = false
    @State private var showShareSheet = false
    @State private var shareURLs: [URL] = []
    @State private var exportErrorMessage: String?
    private let sections = ["打击", "投手", "守备", "记录"]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ScoreboardCard(game: store.game)
                LineScoreTable(game: store.game)

                Picker("数据分类", selection: $selectedSection) {
                    ForEach(sections.indices, id: \.self) { index in
                        Text(sections[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)

                Group {
                    switch selectedSection {
                    case 0: battingTable
                    case 1: pitchingTable
                    case 2: fieldingTable
                    default: playLog
                    }
                }

                exportActions

                if !store.game.isFinal {
                    Button {
                        showEndReasonSheet = true
                    } label: {
                        Label("结束并保存比赛结果", systemImage: "checkmark.seal.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(color: BMTheme.brandNavy))
                    .accessibilityIdentifier("open-finish-game")
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("比赛结果")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showEndReasonSheet) {
            GameEndReasonSheet()
                .environmentObject(store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showShareSheet) {
            ActivityShareSheet(items: shareURLs)
        }
        .alert("导出失败", isPresented: Binding(
            get: { exportErrorMessage != nil },
            set: { if !$0 { exportErrorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { exportErrorMessage = nil }
        } message: {
            Text(exportErrorMessage ?? "")
        }
    }

    private var battingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach([store.game.awayTeam, store.game.homeTeam]) { team in
                SectionHeader(title: "\(team.shortName)打击", subtitle: "本场正式数据")
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(spacing: 0) {
                        tableHeader(["球员", "PA", "AB", "R", "H", "RBI", "BB", "SO", "AVG"])
                        ForEach(battingPlayers(for: team)) { player in
                            let line = store.battingLine(for: player)
                            tableRow([
                                "#\(player.number) \(player.name)",
                                "\(line.plateAppearances)", "\(line.atBats)", "\(line.runs)", "\(line.hits)",
                                "\(line.runsBattedIn)", "\(line.walks)", "\(line.strikeouts)", statText(line.average)
                            ], emphasized: 0)
                        }
                    }
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
    }

    private var pitchingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach([store.game.awayTeam, store.game.homeTeam]) { team in
                SectionHeader(title: "\(team.shortName)投手", subtitle: "本场数据")
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(spacing: 0) {
                        tableHeader(["投手", "IP", "BF", "H", "R", "ER", "BB", "SO", "P-S", "ERA"])
                        ForEach(pitchersToDisplay(for: team)) { player in
                            let line = store.pitchingLine(for: player)
                            tableRow([
                                "#\(player.number) \(player.name)", line.inningsText, "\(line.battersFaced)", "\(line.hits)",
                                "\(line.runs)", "\(line.earnedRuns)", "\(line.walks)", "\(line.strikeouts)",
                                "\(line.pitches)-\(line.strikes)", String(format: "%.2f", line.era)
                            ], emphasized: 0)
                        }
                    }
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
    }

    private var fieldingTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach([store.game.awayTeam, store.game.homeTeam]) { team in
                SectionHeader(title: "\(team.shortName)守备", subtitle: "刺杀、助杀、失误与双杀")
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(spacing: 0) {
                        tableHeader(["球员", "守位", "PO", "A", "E", "DP"])
                        ForEach(fieldingPlayers(for: team)) { player in
                            let line = store.fieldingLine(for: player)
                            tableRow([
                                "#\(player.number) \(player.name)", player.primaryPosition.shortName,
                                "\(line.putouts)", "\(line.assists)", "\(line.errors)", "\(line.doublePlays)"
                            ], emphasized: 0)
                        }
                    }
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
    }

    private var playLog: some View {
        VStack(alignment: .leading, spacing: 10) {
            let appearances = store.plateAppearanceRecords()
            SectionHeader(title: "中文比赛记录", subtitle: "按打席 · \(appearances.count) 条")

            if !store.pendingReviewEvents.isEmpty {
                BMCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("还有 \(store.pendingReviewEvents.count) 条待确认记录", systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(BMTheme.orange)
                        ForEach(store.pendingReviewEvents) { event in
                            NavigationLink {
                                PendingEventReviewView(event: event)
                                    .environmentObject(store)
                            } label: {
                                HStack {
                                    Text(event.title)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(BMTheme.navy)
                                        .lineLimit(2)
                                    Spacer()
                                    Text("补录")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(BMTheme.green)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                            }
                            .accessibilityIdentifier("result-review-\(event.id.uuidString)")
                        }
                    }
                }
            }

            if appearances.isEmpty {
                BMCard {
                    Text("还没有完成或正在进行的打席记录")
                        .foregroundStyle(BMTheme.secondaryText)
                        .frame(maxWidth: .infinity, minHeight: 80)
                }
            } else {
                ForEach(appearances.reversed()) { appearance in
                    HStack(alignment: .top, spacing: 12) {
                        Text(appearance.inningLabel.replacingOccurrences(of: "第", with: "").replacingOccurrences(of: "局", with: ""))
                            .font(.system(size: 12, weight: .black))
                            .foregroundStyle(appearance.needsReview ? BMTheme.orange : BMTheme.green)
                            .frame(width: 36, height: 30)
                            .background(appearance.needsReview ? BMTheme.orangeSoft : BMTheme.greenSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("第 \(appearance.sequence) 打席 · #\(appearance.batter.number) \(appearance.batter.name)")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Text(appearance.events.map(\.title).joined(separator: "；"))
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(BMTheme.navy)
                                .fixedSize(horizontal: false, vertical: true)
                            if appearance.needsReview {
                                Label("待确认，可在本页补录", systemImage: "exclamationmark.circle.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BMTheme.orange)
                            } else if !appearance.isComplete {
                                Label("当前打席进行中", systemImage: "clock.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            ReportExportButton(title: "导出本打席 · 详细版", identifier: "export-appearance-\(appearance.sequence)", previewTitle: "打席速报") {
                                try playByPlayReport.write(appearanceID: appearance.id)
                            }
                            .font(.subheadline)
                            .padding(.vertical, 6)
                            ReportExportButton(title: "导出本打席 · 文字简版", identifier: "export-appearance-text-\(appearance.sequence)", previewTitle: "单打席文字简版") {
                                try playByPlayReport.write(appearanceID: appearance.id, style: .textOnly)
                            }
                            .font(.subheadline)
                            .padding(.vertical, 6)
                        }
                        Spacer()
                    }
                    .padding(13)
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("plate-appearance-\(appearance.sequence)")
                }
            }

            let gameEvents = store.nonPlateAppearanceGameEvents()
            if !gameEvents.isEmpty {
                SectionHeader(title: "比赛进程", subtitle: "计时、换人、TB 与修正")
                ForEach(gameEvents.reversed()) { event in
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "text.bubble.fill")
                            .foregroundStyle(event.needsReview ? BMTheme.orange : BMTheme.secondaryText)
                        Text(event.title)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(BMTheme.navy)
                        Spacer()
                    }
                    .padding(12)
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private func battingPlayers(for team: Team) -> [Player] {
        let order = team.id == store.game.awayTeam.id ? store.game.awayBattingOrderIDs : store.game.homeBattingOrderIDs
        let ordered = order.compactMap { id in team.players.first(where: { $0.id == id }) }
        let recorded = team.players.filter { store.battingLine(for: $0).plateAppearances > 0 }
        return Array((ordered + recorded).reduce(into: [Player]()) { result, player in
            if !result.contains(where: { $0.id == player.id }) { result.append(player) }
        })
    }

    private func pitchersToDisplay(for team: Team) -> [Player] {
        let recorded = team.players.filter {
            store.pitchingLine(for: $0).pitches > 0 || store.pitchingLine(for: $0).battersFaced > 0
        }
        return recorded.isEmpty ? team.players.filter { $0.primaryPosition == .pitcher } : recorded
    }

    private func fieldingPlayers(for team: Team) -> [Player] {
        let recorded = team.players.filter {
            let line = store.fieldingLine(for: $0)
            return line.putouts + line.assists + line.errors + line.doublePlays > 0
        }
        if !recorded.isEmpty { return recorded }
        let ids = team.id == store.game.awayTeam.id
            ? (store.game.awayFieldingPlayerIDs ?? store.game.awayBattingOrderIDs)
            : (store.game.homeFieldingPlayerIDs ?? store.game.homeBattingOrderIDs)
        return ids.compactMap { id in team.players.first(where: { $0.id == id }) }
    }

    private var exportActions: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "导出与分享", subtitle: "使用 iOS 系统分享，可发送到微信等已安装 App")
            BMCard {
                VStack(spacing: 10) {
                    Button {
                        prepareShare(.completeRecord)
                    } label: {
                        Label("分享完整比赛记录", systemImage: "doc.text.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                    .accessibilityIdentifier("share-complete-game-record")

                    ReportExportButton(title: "预览与分享 Box Score", identifier: "share-box-score", previewTitle: "比赛战报") {
                        try gameExporter.writeBoxScorePDF()
                    }
                    .buttonStyle(SecondaryButtonStyle(color: BMTheme.navy))

                    ReportExportButton(title: "逐打席速报 · 详细版", identifier: "share-play-by-play", previewTitle: "逐打席速报") {
                        try playByPlayReport.write()
                    }
                    .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))

                    ReportExportButton(title: "逐打席速报 · 文字简版", identifier: "share-play-by-play-text", previewTitle: "逐打席文字简版") {
                        try playByPlayReport.write(style: .textOnly)
                    }
                    .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))

                    Button {
                        prepareShare(.all)
                    } label: {
                        Label("全部导出并分享", systemImage: "square.and.arrow.up.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("share-all-game-files")
                }
            }
        }
    }

    private var gameExporter: GameExportService {
        GameExportService(game: store.game, rules: store.activeRules,
                          plateAppearances: store.plateAppearanceRecords(),
                          gameEvents: store.nonPlateAppearanceGameEvents(), playedAt: store.activeGameRecordedAt)
    }

    private var playByPlayReport: PlayByPlayPDFReport {
        PlayByPlayPDFReport(game: store.game, appearances: store.plateAppearanceRecords(), playedAt: store.activeGameRecordedAt)
    }

    private func prepareShare(_ selection: GameExportSelection) {
        do {
            let exporter = gameExporter
            switch selection {
            case .completeRecord:
                shareURLs = [try exporter.writeCompleteRecord()]
            case .boxScore:
                shareURLs = [try exporter.writeBoxScorePDF()]
            case .all:
                shareURLs = [try exporter.writeCompleteRecord(), try exporter.writeBoxScorePDF(), try playByPlayReport.write(),
                             try playByPlayReport.write(style: .textOnly)]
            }
            showShareSheet = true
        } catch {
            exportErrorMessage = error.localizedDescription
        }
    }

    private func tableHeader(_ values: [String]) -> some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                Text(values[index])
                    .font(.system(size: 11, weight: .black))
                    .foregroundStyle(BMTheme.secondaryText)
                    .frame(width: index == 0 ? 112 : 54, alignment: index == 0 ? .leading : .center)
                    .padding(.horizontal, index == 0 ? 10 : 0)
            }
        }
        .frame(height: 38)
        .background(BMTheme.background)
    }

    private func tableRow(_ values: [String], emphasized: Int) -> some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                Text(values[index])
                    .font(.system(size: index == emphasized ? 13 : 12, weight: index == emphasized ? .bold : .medium))
                    .foregroundStyle(index == emphasized ? BMTheme.navy : BMTheme.secondaryText)
                    .frame(width: index == 0 ? 112 : 54, alignment: index == 0 ? .leading : .center)
                    .padding(.horizontal, index == 0 ? 10 : 0)
                    .lineLimit(1)
            }
        }
        .frame(height: 46)
        .overlay(alignment: .bottom) {
            Rectangle().fill(BMTheme.line.opacity(0.65)).frame(height: 1)
        }
    }
}

struct GameEndReasonSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore

    private let reasons: [GameEndReason] = [
        .regulation, .timeLimit, .mercyRule, .forfeit,
        .weather, .suspended, .scorerDecision
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("时间、球数和投手局数限制只负责提醒，必须由记录员根据裁判或赛事规程选择结束原因。")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)
                        .padding(12)
                        .background(BMTheme.orangeSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 12))

                    ForEach(reasons) { reason in
                        Button {
                            store.finishGame(reason: reason)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: reason == .suspended ? "pause.circle.fill" : "checkmark.seal.fill")
                                    .foregroundStyle(reason == .suspended ? BMTheme.orange : BMTheme.green)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(reason.rawValue)
                                        .font(.system(size: 16, weight: .bold))
                                    Text(reason == .suspended ? "保存当前局面，比赛仍保留在正在进行中" : "冻结计时并保存为已结束比赛")
                                        .font(.system(size: 11))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            .foregroundStyle(BMTheme.navy)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 62)
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 13))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("finish-reason-\(reason.rawValue)")
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("结束或中断比赛")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

struct LineScoreTable: View {
    let game: GameState

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(spacing: 0) {
                lineScoreRow(label: "", innings: Array(1...game.homeRunsByInning.count).map(String.init), totals: ["R", "H", "E"], header: true)
                lineScoreRow(label: game.awayTeam.shortName, innings: game.awayRunsByInning.map(String.init), totals: ["\(game.awayScore)", "\(game.awayHits)", "\(game.awayErrors)"])
                lineScoreRow(label: game.homeTeam.shortName, innings: game.homeRunsByInning.map(String.init), totals: ["\(game.homeScore)", "\(game.homeHits)", "\(game.homeErrors)"])
            }
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("逐局比分，\(game.awayTeam.shortName) \(game.awayScore)分，\(game.homeTeam.shortName) \(game.homeScore)分")
    }

    private func lineScoreRow(label: String, innings: [String], totals: [String], header: Bool = false) -> some View {
        HStack(spacing: 0) {
            Text(label)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(BMTheme.navy)
                .frame(width: 62, alignment: .leading)
                .padding(.leading, 12)
            ForEach(innings.indices, id: \.self) { index in
                Text(innings[index])
                    .font(.system(size: 12, weight: header ? .bold : .medium, design: .rounded))
                    .foregroundStyle(header ? BMTheme.secondaryText : BMTheme.navy)
                    .frame(width: 32)
            }
            Rectangle().fill(BMTheme.line).frame(width: 1, height: 24).padding(.horizontal, 5)
            ForEach(totals.indices, id: \.self) { index in
                Text(totals[index])
                    .font(.system(size: 12, weight: .black, design: .rounded))
                    .foregroundStyle(index == 0 && !header ? BMTheme.green : BMTheme.navy)
                    .frame(width: 32)
            }
        }
        .frame(height: header ? 34 : 42)
        .background(header ? BMTheme.background : BMTheme.surface)
        .overlay(alignment: .bottom) {
            if !header { Rectangle().fill(BMTheme.line.opacity(0.5)).frame(height: 1) }
        }
    }
}

enum GameExportSelection {
    case completeRecord
    case boxScore
    case all
}

struct ActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

@MainActor
struct GameExportService {
    let game: GameState
    let rules: GameRules?
    let plateAppearances: [PlateAppearanceRecord]
    let gameEvents: [ScoringEventRecord]
    var playedAt: Date? = nil

    func completeRecordText() -> String {
        var lines: [String] = []
        lines.append("BaseballMaster 完整比赛记录")
        lines.append("\(game.awayTeam.name) vs \(game.homeTeam.name)")
        lines.append("比分：\(game.awayTeam.shortName) \(game.awayScore) - \(game.homeScore) \(game.homeTeam.shortName)")
        lines.append("状态：\(game.isFinal ? (game.endReason?.rawValue ?? "比赛结束") : "记录中")")
        if let rules {
            var ruleParts = ["规定 \(rules.scheduledInnings) 局", "\(rules.fieldersCount) 人守备"]
            if let minutes = rules.timeLimitMinutes { ruleParts.append("限时 \(minutes) 分钟") }
            if let pitches = rules.pitchLimit { ruleParts.append("单投手 \(pitches) 球") }
            if let innings = rules.pitcherInningsLimit { ruleParts.append("单投手 \(innings) 局") }
            if rules.designatedHitterEnabled {
                ruleParts.append(rules.twoWayPlayerEnabled ? "大谷条款" : "指定打击 DH")
            }
            lines.append("规则：" + ruleParts.joined(separator: "，"))
        }
        lines.append("")
        lines.append("【逐局比分】")
        lines.append(lineScoreHeader())
        lines.append(lineScoreRow(team: game.awayTeam, innings: game.awayRunsByInning, hits: game.awayHits, errors: game.awayErrors))
        lines.append(lineScoreRow(team: game.homeTeam, innings: game.homeRunsByInning, hits: game.homeHits, errors: game.homeErrors))

        for team in [game.awayTeam, game.homeTeam] {
            lines.append("")
            lines.append("【\(team.shortName)打击】")
            lines.append("球员\tPA\tAB\tR\tH\t2B\t3B\tHR\tRBI\tBB\tSO\tAVG")
            for player in battingPlayers(for: team) {
                let value = game.batting[player.id, default: BattingLine()]
                lines.append("#\(player.number) \(player.name)\t\(value.plateAppearances)\t\(value.atBats)\t\(value.runs)\t\(value.hits)\t\(value.doubles)\t\(value.triples)\t\(value.homeRuns)\t\(value.runsBattedIn)\t\(value.walks)\t\(value.strikeouts)\t\(statText(value.average))")
            }

            lines.append("")
            lines.append("【\(team.shortName)投手】")
            lines.append("投手\tIP\tBF\tH\tR\tER\tBB\tSO\tP-S\tERA")
            for player in pitchingPlayers(for: team) {
                let value = game.pitching[player.id, default: PitchingLine()]
                lines.append("#\(player.number) \(player.name)\t\(value.inningsText)\t\(value.battersFaced)\t\(value.hits)\t\(value.runs)\t\(value.earnedRuns)\t\(value.walks)\t\(value.strikeouts)\t\(value.pitches)-\(value.strikes)\t\(String(format: "%.2f", value.era))")
            }

            lines.append("")
            lines.append("【\(team.shortName)守备】")
            lines.append("球员\t守位\tPO\tA\tE\tDP")
            for player in fieldingPlayers(for: team) {
                let value = game.fielding[player.id, default: FieldingLine()]
                lines.append("#\(player.number) \(player.name)\t\(player.primaryPosition.shortName)\t\(value.putouts)\t\(value.assists)\t\(value.errors)\t\(value.doublePlays)")
            }
        }

        lines.append("")
        lines.append("【按打席中文比赛记录】")
        if plateAppearances.isEmpty {
            lines.append("暂无打席记录")
        } else {
            for appearance in plateAppearances {
                let marker = appearance.needsReview ? "【待确认】" : appearance.isComplete ? "" : "【进行中】"
                lines.append("\(appearance.chineseRecord)\(marker)")
            }
        }

        lines.append("")
        lines.append("【比赛进程】")
        for event in gameEvents {
            lines.append("第\(event.inning)局\(event.isTop ? "上" : "下")：\(event.title)\(event.needsReview ? "【待确认】" : "")")
        }

        lines.append("")
        lines.append("【结构化事件附录】")
        for (index, event) in (game.scoringEvents ?? []).enumerated() {
            let notation = event.notation.map { " [\($0)]" } ?? ""
            let ballStatus = event.ballStatus.map { " · \($0.rawValue)" } ?? ""
            let movements = event.runnerMovements.isEmpty
                ? ""
                : " · " + event.runnerMovements.map { "\($0.origin)→\($0.destination)" }.joined(separator: "，")
            lines.append("\(index + 1). \(event.category.title)\(notation)\(ballStatus)：\(event.title)\(movements)")
        }
        return lines.joined(separator: "\n") + "\n"
    }

    func writeCompleteRecord() throws -> URL {
        let url = try exportURL(suffix: "完整比赛记录", extension: "txt")
        guard let data = completeRecordText().data(using: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        try data.write(to: url, options: .atomic)
        return url
    }

    func boxScorePDFData() -> Data {
        GameBoxScorePDFReport(game: game, rules: rules, playedAt: playedAt).pdfData()
    }

    func writeBoxScorePDF() throws -> URL {
        let url = try exportURL(suffix: "BoxScore", extension: "pdf")
        try boxScorePDFData().write(to: url, options: .atomic)
        return url
    }

    private func lineScoreHeader() -> String {
        (["球队"] + Array(1...game.homeRunsByInning.count).map(String.init) + ["R", "H", "E"]).joined(separator: "\t")
    }

    private func lineScoreRow(team: Team, innings: [Int], hits: Int, errors: Int) -> String {
        ([team.shortName] + innings.map(String.init) + ["\(innings.reduce(0, +))", "\(hits)", "\(errors)"]).joined(separator: "\t")
    }

    private func battingPlayers(for team: Team) -> [Player] {
        let order = team.id == game.awayTeam.id ? game.awayBattingOrderIDs : game.homeBattingOrderIDs
        let ordered = order.compactMap { id in team.players.first(where: { $0.id == id }) }
        let recorded = team.players.filter { game.batting[$0.id, default: BattingLine()].plateAppearances > 0 }
        return uniquePlayers(ordered + recorded)
    }

    private func pitchingPlayers(for team: Team) -> [Player] {
        let recorded = team.players.filter {
            let line = game.pitching[$0.id, default: PitchingLine()]
            return line.pitches > 0 || line.battersFaced > 0
        }
        return recorded.isEmpty ? team.players.filter { $0.primaryPosition == .pitcher } : recorded
    }

    private func fieldingPlayers(for team: Team) -> [Player] {
        let recorded = team.players.filter {
            let line = game.fielding[$0.id, default: FieldingLine()]
            return line.putouts + line.assists + line.errors + line.doublePlays > 0
        }
        if !recorded.isEmpty { return recorded }
        let ids = team.id == game.awayTeam.id
            ? (game.awayFieldingPlayerIDs ?? game.awayBattingOrderIDs)
            : (game.homeFieldingPlayerIDs ?? game.homeBattingOrderIDs)
        return ids.compactMap { id in team.players.first(where: { $0.id == id }) }
    }

    private func uniquePlayers(_ players: [Player]) -> [Player] {
        players.reduce(into: []) { result, player in
            if !result.contains(where: { $0.id == player.id }) { result.append(player) }
        }
    }

    private func exportURL(suffix: String, extension fileExtension: String) throws -> URL {
        try ReportExportFile.url(name: "\(game.awayTeam.shortName)-vs-\(game.homeTeam.shortName)-\(suffix)", extension: fileExtension)
    }
}

struct PlayerDetailView: View {
    @EnvironmentObject private var store: GameStore
    let player: Player
    let statisticsTeamID: UUID?

    @State private var category: StatisticsCategory
    @State private var preparedSelection = false
    @State private var selectedSeasonID: String
    @State private var selectedGameIDs = Set<UUID>()
    @State private var showGameFilter = false

    init(
        player: Player, initialSeasonID: String = "", statisticsTeamID: UUID? = nil,
        initialCategory: StatisticsCategory = .batting
    ) {
        self.player = player
        self.statisticsTeamID = statisticsTeamID
        _selectedSeasonID = State(initialValue: initialSeasonID)
        _category = State(initialValue: initialCategory)
    }

    private var seasonGames: [PlayerGameRecord] {
        store.gameRecords(for: player, seasonID: selectedSeasonID, teamID: statisticsTeamID)
    }

    private var selectedStatistics: PlayerSeasonStatistics {
        store.playerStatistics(for: player, seasonID: selectedSeasonID,
                               teamID: statisticsTeamID, gameIDs: selectedGameIDs)
    }

    private var selectedGames: [PlayerGameRecord] {
        seasonGames.filter { selectedGameIDs.contains($0.id) }
    }

    private var selectedLine: BattingLine {
        store.battingLine(for: selectedGames)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                VStack(spacing: 10) {
                    Text(player.numbers.first.map(String.init) ?? "—")
                        .font(.system(size: 34, weight: .black, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(width: 78, height: 78)
                        .background(BMTheme.brandNavy)
                        .clipShape(Circle())
                    Text(player.name)
                        .font(.system(size: 25, weight: .black))
                        .foregroundStyle(BMTheme.navy)
                    if !player.englishName.isEmpty && !player.chineseName.isEmpty {
                        Text(player.englishName)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    Text(player.numbersText)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .frame(maxWidth: .infinity)

                BMCard {
                    VStack(spacing: 14) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("统计赛季")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(BMTheme.secondaryText)
                                Picker("统计赛季", selection: $selectedSeasonID) {
                                    ForEach(store.statisticsSeasons) { season in
                                        Text(season.name).tag(season.id)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(BMTheme.navy)
                                .accessibilityIdentifier("player-season-picker")
                            }
                            Spacer()
                            Button {
                                showGameFilter = true
                            } label: {
                                Label("筛选比赛", systemImage: "line.3.horizontal.decrease.circle")
                                    .font(.system(size: 14, weight: .bold))
                            }
                            .foregroundStyle(BMTheme.green)
                            .accessibilityIdentifier("open-game-filter")
                        }

                        Divider()

                        HStack {
                            Text("已选 \(selectedGames.count) / \(seasonGames.count) 场比赛")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(BMTheme.secondaryText)
                            Spacer()
                            if selectedGameIDs.count != seasonGames.count {
                                Button("选择全部") {
                                    selectedGameIDs = Set(seasonGames.map(\.id))
                                }
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(BMTheme.green)
                            }
                        }
                    }
                }

                Picker("球员统计分类", selection: $category) {
                    ForEach(StatisticsCategory.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("player-statistics-category")

                if category == .batting {
                    HStack(spacing: 8) {
                        StatPill(label: "AVG", value: selectedLine.atBats > 0 ? statText(selectedLine.average) : "—", color: BMTheme.green)
                        StatPill(label: "OBP", value: selectedLine.atBats + selectedLine.walks + selectedLine.hitByPitch + selectedLine.sacrifices > 0 ? statText(selectedLine.onBasePercentage) : "—")
                        StatPill(label: "SLG", value: selectedLine.atBats > 0 ? statText(selectedLine.slugging) : "—")
                        StatPill(label: "OPS", value: StatisticsMetric.ops.formattedValue(for: selectedStatistics), color: BMTheme.orange)
                    }

                    BMCard {
                        VStack(alignment: .leading, spacing: 15) {
                            SectionHeader(title: "筛选范围统计", subtitle: "\(selectedGames.count) 场")
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 16) {
                                detailStat("PA", selectedLine.plateAppearances)
                                detailStat("AB", selectedLine.atBats)
                                detailStat("H", selectedLine.hits)
                                detailStat("R", selectedLine.runs)
                                detailStat("2B", selectedLine.doubles)
                                detailStat("3B", selectedLine.triples)
                                detailStat("HR", selectedLine.homeRuns)
                                detailStat("RBI", selectedLine.runsBattedIn)
                                detailStat("BB", selectedLine.walks)
                                detailStat("SO", selectedLine.strikeouts)
                                detailStat("SB", selectedLine.stolenBases)
                                detailStat("TB", selectedLine.totalBases)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "表现总结", subtitle: selectedSeasonName)
                        performanceSummary(title: "近 3 场", records: Array(seasonGames.prefix(3)))
                        performanceSummary(title: "近 10 场", records: Array(seasonGames.prefix(10)))
                        performanceSummary(title: "本赛季", records: seasonGames)
                    }

                } else {
                    StatisticsMetricsGrid(category: category, row: selectedStatistics)
                    Text("投手与守备数据来自所选已结束比赛；旧版个人记录仅含打击数据。")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                }

                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "比赛明细", subtitle: "按时间倒序")
                    if seasonGames.isEmpty {
                        BMCard {
                            Text("这个赛季还没有比赛数据")
                                .font(.system(size: 14))
                                .foregroundStyle(BMTheme.secondaryText)
                                .frame(maxWidth: .infinity, minHeight: 72)
                        }
                    } else {
                        ForEach(seasonGames) { record in
                            gameRecordRow(record)
                        }
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("球员数据")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ReportExportButton(title: "导出 PDF", identifier: "export-player-pdf", previewTitle: "球员个人报告") {
                    try PlayerStatisticsPDFReport(store: store, player: player, seasonID: selectedSeasonID,
                                                  teamID: statisticsTeamID, gameIDs: selectedGameIDs).write()
                }
            }
        }
        .onAppear(perform: prepareInitialSelection)
        .onChange(of: selectedSeasonID) { _ in
            selectedGameIDs = Set(seasonGames.map(\.id))
        }
        .sheet(isPresented: $showGameFilter) {
            GameFilterSheet(games: seasonGames, initialSelection: selectedGameIDs) { selection in
                selectedGameIDs = selection
            }
        }
    }

    private func detailStat(_ label: String, _ value: Int) -> some View {
        VStack(spacing: 4) {
            Text("\(value)")
                .font(.system(size: 19, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.navy)
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
        }
    }

    private var selectedSeasonName: String {
        store.statisticsSeasons.first(where: { $0.id == selectedSeasonID })?.name ?? ""
    }

    private func prepareInitialSelection() {
        guard !preparedSelection else { return }
        preparedSelection = true
        if !store.statisticsSeasons.contains(where: { $0.id == selectedSeasonID }) {
            selectedSeasonID = store.statisticsSeasons.first?.id ?? ""
        }
        selectedGameIDs = Set(seasonGames.map(\.id))
    }

    private func performanceSummary(title: String, records: [PlayerGameRecord]) -> some View {
        let line = store.battingLine(for: records)
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text("\(records.count) 场 · \(line.hits)-\(line.atBats) · \(line.runsBattedIn) 打点")
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            summaryMetric("AVG", line.atBats > 0 ? statText(line.average) : "—", color: BMTheme.green)
            summaryMetric("OPS", line.atBats > 0 ? statText(line.ops) : "—", color: BMTheme.orange)
        }
        .padding(14)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func summaryMetric(_ label: String, _ value: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 17, weight: .black, design: .rounded))
                .foregroundStyle(color)
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .frame(width: 47)
    }

    private func gameRecordSummary(_ record: PlayerGameRecord) -> String {
        let stored = store.games.first { $0.id == record.id }
        switch category {
        case .batting: return "\(record.batting.hits)-\(record.batting.atBats)"
        case .pitching:
            guard let line = stored?.state.pitching[player.id] else { return "无投手记录" }
            return "\(line.inningsText) 局 · \(line.strikeouts) 三振"
        case .fielding:
            guard let line = stored?.state.fielding[player.id] else { return "无守备记录" }
            return "\(line.putouts) 刺杀 · \(line.assists) 助杀 · \(line.errors) 失误"
        }
    }

    private func gameRecordRow(_ record: PlayerGameRecord) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("对 \(record.opponent)")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text("\(gameDateText(record.date)) · \(record.result)")
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text(gameRecordSummary(record))
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(BMTheme.navy)
                Text(category == .batting ? "AVG \(record.batting.atBats > 0 ? statText(record.batting.average) : "—")" : category.rawValue)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(BMTheme.green)
            }
        }
        .padding(14)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct GameFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    let games: [PlayerGameRecord]
    let onApply: (Set<UUID>) -> Void
    @State private var selection: Set<UUID>

    init(
        games: [PlayerGameRecord],
        initialSelection: Set<UUID>,
        onApply: @escaping (Set<UUID>) -> Void
    ) {
        self.games = games
        self.onApply = onApply
        _selection = State(initialValue: initialSelection)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        Button("全选") {
                            selection = Set(games.map(\.id))
                        }
                        .buttonStyle(.bordered)

                        Button("清空") {
                            selection.removeAll()
                        }
                        .buttonStyle(.bordered)
                        .tint(BMTheme.red)

                        Spacer()
                        Text("已选 \(selection.count) 场")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                }

                Section("选择参与统计的比赛") {
                    if games.isEmpty {
                        Text("当前赛季没有比赛数据")
                            .foregroundStyle(BMTheme.secondaryText)
                    } else {
                        ForEach(games) { game in
                            Button {
                                if selection.contains(game.id) {
                                    selection.remove(game.id)
                                } else {
                                    selection.insert(game.id)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: selection.contains(game.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(selection.contains(game.id) ? BMTheme.green : BMTheme.secondaryText)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("对 \(game.opponent)")
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundStyle(BMTheme.navy)
                                        Text("\(gameDateText(game.date)) · \(game.result)")
                                            .font(.system(size: 12))
                                            .foregroundStyle(BMTheme.secondaryText)
                                    }
                                    Spacer()
                                    Text("\(game.batting.hits)-\(game.batting.atBats)")
                                        .font(.system(size: 14, weight: .black, design: .rounded))
                                        .foregroundStyle(BMTheme.navy)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle("筛选比赛")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("应用") {
                        onApply(selection)
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
        }
    }
}

private func gameDateText(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.dateFormat = "M月d日"
    return formatter.string(from: date)
}

#Preview("比赛结果") {
    NavigationStack { BoxScoreView() }
        .environmentObject(GameStore())
}

#Preview("球队统计") {
    NavigationStack { StatsOverviewView() }
        .environmentObject(GameStore())
}

#Preview("球员详情") {
    let store = GameStore()
    NavigationStack { PlayerDetailView(player: store.currentTeam.players[0]) }
        .environmentObject(store)
}
