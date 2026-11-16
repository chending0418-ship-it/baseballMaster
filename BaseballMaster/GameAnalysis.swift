import Foundation
import SwiftUI

struct GameAnalysisInput: Encodable {
    enum Scope: String, Encodable {
        case single, season, filtered

        var title: String {
            switch self {
            case .single: "比赛分析"
            case .season: "全年比赛分析"
            case .filtered: "筛选比赛分析"
            }
        }

        var mvpTitle: String {
            switch self {
            case .single: "本场 MVP"
            case .season: "年度 MVP"
            case .filtered: "所选比赛 MVP"
            }
        }
    }

    struct PlayerLine: Encodable {
        let id: String
        let side: Int
        let games: Int
        let batting: [Int]
        let pitching: [Int]?
        let fielding: [Int]

        init(id: String, side: Int, games: Int, batting: BattingLine, pitching: PitchingLine?, fielding: FieldingLine) {
            self.id = id; self.side = side; self.games = games
            self.batting = [batting.plateAppearances, batting.atBats, batting.hits, batting.totalBases, batting.runs,
                            batting.runsBattedIn, batting.walks, batting.hitByPitch, batting.strikeouts,
                            batting.stolenBases, batting.caughtStealing]
            self.pitching = pitching.map {
                [$0.outsRecorded, $0.battersFaced, $0.hits, $0.runs, $0.earnedRuns, $0.walks, $0.strikeouts, $0.pitches, $0.strikes]
            }
            self.fielding = [fielding.putouts, fielding.assists, fielding.errors, fielding.doublePlays]
        }
    }

    struct Situation: Encodable {
        let inning: Int
        let top: Bool
        let kind: String
        let batter: String?
        let fielder: String?
        let before: [Int]?
        let after: [Int]?
        let beforeScore: [Int]?
        let afterScore: [Int]?
        let pending: Bool

        private static let notations = Set([
            "1B", "2B", "3B", "HR", "GO", "FO", "LO", "FF", "IF", "E", "FC", "TAG", "SH", "SF", "DP", "TP",
            "BB", "IBB", "HBP", "K", "K-BUNT", "SB", "CS", "WP", "PB", "E-ADV", "BALK", "HALF-END", "END"
        ])

        init(event: ScoringEventRecord, people: [UUID: String]) {
            inning = event.inning; top = event.isTop; pending = event.needsReview
            let notation = event.resolvedOutcome?.notation ?? event.notation
            kind = notation.flatMap { Self.notations.contains($0) ? $0 : nil } ?? event.category.rawValue
            batter = event.plateAppearanceBatterID.flatMap { people[$0] }
            let secondary = event.secondaryPlayerID.flatMap { people[$0] }
            fielder = kind == "E-ADV" ? secondary : nil
            before = event.beforeSituation.map(Self.state)
            after = event.afterSituation.map(Self.state)
            beforeScore = event.beforeSituation.map(Self.score)
            afterScore = event.afterSituation.map(Self.score)
        }

        private static func state(_ state: GameSituationSnapshot) -> [Int] {
            let bases = state.baseRunners.reduce(0) { $0 | (1 << ($1.base.rawValue - 1)) }
            return [state.inning, state.isTop ? 1 : 0, state.outs, bases, state.balls, state.strikes]
        }

        private static func score(_ state: GameSituationSnapshot) -> [Int] {
            [state.awayRunsByInning.reduce(0, +), state.homeRunsByInning.reduce(0, +)]
        }

        var importance: Int {
            guard let beforeScore, let afterScore else { return kind == "HR" ? 7 : 0 }
            let beforeLead = beforeScore[0] - beforeScore[1]
            let afterLead = afterScore[0] - afterScore[1]
            let runs = (afterScore[0] - beforeScore[0]) + (afterScore[1] - beforeScore[1])
            return (beforeLead.signum() == afterLead.signum() ? 0 : 12) + max(0, runs) * 5
                + (kind == "HR" ? 7 : 0) + (kind == "DP" || kind == "TP" ? 5 : 0)
        }
    }

    struct Game: Encodable {
        let mode: GameMode
        let ownHome: Bool?
        let away: [Int]
        let home: [Int]
        let totals: [Int]
        let incomplete: Bool
        let events: [Situation]

        init(_ game: StoredGame, people: [UUID: String], eventLimit: Int) {
            let state = game.state
            mode = game.rules.gameMode
            ownHome = game.isObservation || game.ourTeamID == nil ? nil : game.isHome
            let count = max(state.inning, 1)
            away = Array(state.awayRunsByInning.prefix(count))
            home = Array(state.homeRunsByInning.prefix(count))
            totals = [state.awayScore, state.awayHits, state.awayErrors, state.homeScore, state.homeHits, state.homeErrors]
            let records = state.scoringEvents ?? []
            incomplete = records.isEmpty || state.statisticsIncomplete == true || records.contains(where: \.needsReview)
            let notable = records.enumerated().filter {
                let event = $0.element
                return !event.isPregameLineupAdjustment && event.category != .clock && event.category != .pitch
            }.map { (index: $0.offset, event: Situation(event: $0.element, people: people)) }
            events = notable.sorted {
                $0.event.importance == $1.event.importance ? $0.index < $1.index : $0.event.importance > $1.event.importance
            }.prefix(eventLimit).sorted { $0.index < $1.index }.map(\.event)
        }
    }

    let scope: Scope
    let games: [Game]
    let players: [PlayerLine]
}

struct GameAnalysisSource: Identifiable {
    let id = UUID()
    let input: GameAnalysisInput
    let subtitle: String
    let names: [String: String]

    init(single game: StoredGame) {
        let state = game.state
        var lineups: [(Player, Int)] = []
        for (side, team) in [state.awayTeam, state.homeTeam].enumerated() {
            let home = side == 1
            let order = home ? state.homeBattingOrderIDs : state.awayBattingOrderIDs
            let exited = home ? state.homeExitedPlayerIDs : state.awayExitedPlayerIDs
            let fielders = home ? state.homeFieldingPlayerIDs : state.awayFieldingPlayerIDs
            let starting = (game.isObservation ? !home : home == game.isHome) ? game.lineup : (game.secondaryLineup ?? [])
            var participants = Set(starting.map(\.playerID) + order + (exited ?? []) + (fielders ?? []))
            participants.formUnion(state.batting.keys)
            participants.formUnion(state.pitching.keys)
            participants.formUnion(state.fielding.keys)
            lineups += team.players.filter { participants.contains($0.id) }.map { ($0, side) }
        }

        var aliases: [UUID: String] = [:], labels: [String: String] = [:]
        var players: [GameAnalysisInput.PlayerLine] = []
        for (player, side) in lineups where aliases[player.id] == nil {
            let alias = "P\(players.count + 1)"
            aliases[player.id] = alias
            labels[alias] = (side == 0 ? "客队 " : "主队 ") + player.compactName
            players.append(.init(id: alias, side: side, games: 1,
                                 batting: state.batting[player.id] ?? BattingLine(),
                                 pitching: game.rules.gameMode == .coachPitch ? nil : state.pitching[player.id],
                                 fielding: state.fielding[player.id] ?? FieldingLine()))
        }
        input = GameAnalysisInput(scope: .single, games: [.init(game, people: aliases, eventLimit: 18)], players: players)
        subtitle = "\(state.awayTeam.shortName) \(state.awayScore)–\(state.homeScore) \(state.homeTeam.shortName)"
        names = labels
    }

    init(summary: TeamSeasonStatistics, teamName: String, year: String, filtered: Bool) {
        let rows = summary.players.filter { $0.gamesPlayed > 0 }.sorted { $0.player.id.uuidString < $1.player.id.uuidString }
        var aliases: [UUID: String] = [:], labels: [String: String] = [:]
        let players = rows.enumerated().map { index, row -> GameAnalysisInput.PlayerLine in
            let alias = "P\(index + 1)"
            aliases[row.player.id] = alias
            labels[alias] = row.player.compactName
            let pitching = row.pitching == PitchingLine() ? nil : row.pitching
            return .init(id: alias, side: 2, games: row.gamesPlayed, batting: row.batting, pitching: pitching, fielding: row.fielding)
        }
        let games = summary.games.reversed().map { game -> GameAnalysisInput.Game in
            var people: [UUID: String] = [:]
            for id in game.statisticsParticipantIDs { people[id] = aliases[game.statisticsIdentity(for: id)] }
            return .init(game, people: people, eventLimit: 3)
        }
        input = GameAnalysisInput(scope: filtered ? .filtered : .season, games: games, players: players)
        subtitle = "\(teamName) · \(year) 年 · \(games.count) 场"
        names = labels
    }

    func display(_ text: String) -> String {
        let aliases = /P[1-9][0-9]{0,2}(?![0-9])/
        var output = text
        for match in text.matches(of: aliases).reversed() {
            output.replaceSubrange(match.range, with: names[String(match.output)] ?? "球员")
        }
        return output
    }
}

struct GameAnalysisReport: Decodable {
    struct Evaluation: Decodable, Identifiable {
        let id: String
        let evaluation: String
    }
    struct MVP: Decodable {
        let id: String
        let reason: String
    }

    let overview: String
    let situations: [String]
    let players: [Evaluation]
    let mvp: MVP?
}

private enum GameAnalysisFailure: LocalizedError {
    case provider, timeout

    var errorDescription: String? {
        switch self {
        case .provider: "这次分析未生成，请稍后重试。"
        case .timeout: "生成时间较长，请稍后重试。"
        }
    }
}

extension TeamServiceClient {
    func generateGameAnalysis(_ input: GameAnalysisInput, id: UUID) async throws -> GameAnalysisReport {
        struct Start: Encodable { let requestID: UUID; let input: GameAnalysisInput }
        struct Status: Decodable {
            enum State: String, Decodable { case pending, ready, failed }
            let state: State
            let report: GameAnalysisReport?
        }

        _ = try await enroll(deviceName: "iPhone")
        var status: Status = try await request("POST", path: "game-analysis", body: Start(requestID: id, input: input))
        let deadline = Date().addingTimeInterval(120)
        while status.state == .pending, Date() < deadline {
            try await Task.sleep(nanoseconds: 2_000_000_000)
            status = try await request("GET", path: "game-analysis/\(id.uuidString.lowercased())", body: Optional<String>.none)
        }
        guard let report = status.report, status.state == .ready else {
            throw status.state == .failed ? GameAnalysisFailure.provider : GameAnalysisFailure.timeout
        }
        return report
    }
}

struct GameAnalysisView: View {
    @Environment(\.dismiss) private var dismiss
    let source: GameAnalysisSource
    @State private var report: GameAnalysisReport?
    @State private var generation: Task<Void, Never>?
    @State private var error: String?
    @State private var requestID = UUID()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(source.subtitle).font(.headline)
                    if let report {
                        reportContent(report)
                    } else if generation != nil {
                        ProgressView("正在分析比赛…")
                    } else {
                        Text("生成时会把比赛统计、关键局面和球员代号发送给 V-API。姓名在本机显示。")
                            .foregroundStyle(BMTheme.secondaryText)
                        if let error { Text(error).foregroundStyle(BMTheme.orange) }
                        Button(error == nil ? "生成文字分析" : "重新生成") { generate() }
                            .buttonStyle(PrimaryButtonStyle())
                            .accessibilityIdentifier("generate-game-analysis")
                    }
                }
                .frame(maxWidth: 680, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(BMTheme.horizontalPadding)
            }
            .bmScreenBackground()
            .navigationTitle(source.input.scope.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
                if let report {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: plainText(report)) { Label("分享文字", systemImage: "square.and.arrow.up") }
                    }
                }
            }
        }
        .onDisappear { generation?.cancel() }
    }

    @ViewBuilder private func reportContent(_ report: GameAnalysisReport) -> some View {
        Text("AI 生成 · 请结合比赛记录核对").font(.footnote).foregroundStyle(BMTheme.secondaryText)
        SectionHeader(title: "比赛综述")
        Text(source.display(report.overview)).textSelection(.enabled)
        if !report.situations.isEmpty {
            SectionHeader(title: source.input.scope == .single ? "关键局面" : "比赛走势")
            ForEach(report.situations.indices, id: \.self) { index in
                Text(source.display(report.situations[index])).textSelection(.enabled)
            }
        }
        SectionHeader(title: source.input.scope.mvpTitle)
        if let mvp = report.mvp, let name = source.names[mvp.id] {
            VStack(alignment: .leading, spacing: 5) {
                Text(name).font(.headline)
                Text(source.display(mvp.reason)).textSelection(.enabled)
            }
        } else {
            Text("现有记录不足以评选。").foregroundStyle(BMTheme.secondaryText)
        }
        if !report.players.isEmpty {
            SectionHeader(title: "球员评价")
            ForEach(report.players) { row in
                if let name = source.names[row.id] {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(name).font(.headline)
                        Text(source.display(row.evaluation)).textSelection(.enabled)
                    }
                }
            }
        }
    }

    private func plainText(_ report: GameAnalysisReport) -> String {
        var lines = [source.input.scope.title, source.subtitle, "AI 生成 · 请结合比赛记录核对", "", source.display(report.overview)]
        lines += report.situations.map { source.display($0) }
        if let mvp = report.mvp, let name = source.names[mvp.id] {
            lines += ["", "MVP · \(name)", source.display(mvp.reason)]
        }
        for row in report.players {
            if let name = source.names[row.id] { lines += ["", name, source.display(row.evaluation)] }
        }
        return lines.joined(separator: "\n")
    }

    private func generate() {
        guard generation == nil else { return }
        error = nil
        generation = Task { @MainActor in
            defer { generation = nil }
            do {
                let client = try analysisClient()
                let result = try await client.generateGameAnalysis(source.input, id: requestID)
                if !Task.isCancelled { report = result }
            } catch {
                if !Task.isCancelled {
                    if let failure = error as? GameAnalysisFailure, case .provider = failure { requestID = UUID() }
                    self.error = error.localizedDescription
                }
            }
        }
    }

    private func analysisClient() throws -> TeamServiceClient {
        #if DEBUG
        if let value = ProcessInfo.processInfo.environment["BM_GAME_ANALYSIS_LOCAL_TEST"], let id = UUID(uuidString: value) {
            return try TeamServiceClient(baseURL: URL(string: "http://127.0.0.1:18843/api/v3")!,
                                         vault: TeamDeviceKeychain(account: "game-analysis-ui-" + id.uuidString))
        }
        #endif
        let configuration = TeamSharingStore.connectionConfiguration()
        return try TeamServiceClient(baseURL: configuration.baseURL, vault: configuration.vault)
    }
}
