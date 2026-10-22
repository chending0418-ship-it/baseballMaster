import Foundation

@MainActor
enum ReportStatisticsTables {
    static let battingHeaders = ["球员", "G", "PA", "AB", "R", "H", "2B", "3B", "HR", "RBI", "BB", "HBP", "SO", "SB", "AVG", "OBP", "SLG", "OPS"]
    static let pitchingHeaders = ["投手", "IP", "BF", "H", "R", "ER", "BB", "HBP", "SO", "WP", "P", "S", "ERA", "WHIP"]
    static let fieldingHeaders = ["球员", "PO", "A", "E", "DP", "TC", "FPCT"]

    static func battingRow(_ name: String, games: Int, line b: BattingLine) -> [String] {
        let obpDenominator = b.atBats + b.walks + b.hitByPitch + b.sacrifices
        return [name, "\(games)", "\(b.plateAppearances)", "\(b.atBats)", "\(b.runs)", "\(b.hits)",
                "\(b.doubles)", "\(b.triples)", "\(b.homeRuns)", "\(b.runsBattedIn)", "\(b.walks)",
                "\(b.hitByPitch)", "\(b.strikeouts)", "\(b.stolenBases)",
                rate(b.average, available: b.atBats > 0), rate(b.onBasePercentage, available: obpDenominator > 0),
                rate(b.slugging, available: b.atBats > 0), rate(b.ops, available: b.atBats > 0)]
    }

    static func pitchingRow(_ name: String, line p: PitchingLine) -> [String] {
        [name, p.inningsText, "\(p.battersFaced)", "\(p.hits)", "\(p.runs)", "\(p.earnedRuns)",
         "\(p.walks)", "\(p.hitByPitch)", "\(p.strikeouts)", "\(p.wildPitches)", "\(p.pitches)", "\(p.strikes)",
         p.outsRecorded > 0 ? String(format: "%.2f", p.era) : "-",
         p.outsRecorded > 0 ? String(format: "%.2f", p.whip) : "-"]
    }

    static func fieldingRow(_ name: String, line f: FieldingLine) -> [String] {
        [name, "\(f.putouts)", "\(f.assists)", "\(f.errors)", "\(f.doublePlays)", "\(f.chances)", f.percentage.map(statText) ?? "-"]
    }

    static func rate(_ value: Double, available: Bool) -> String { available ? statText(value) : "-" }

    static func playerName(_ row: PlayerSeasonStatistics) -> String {
        row.player.compactName + (row.isCurrentRoster ? "" : "（历史）")
    }

    static func batting(_ canvas: ReportPDFCanvas, title: String, players: [PlayerSeasonStatistics], totalGames: Int, includeTotal: Bool = true, compact: Bool = false) {
        var rows = players.map { battingRow(playerName($0), games: $0.gamesPlayed, line: $0.batting) }
        if includeTotal && !players.isEmpty {
            rows.append(battingRow("合计", games: totalGames, line: .aggregate(players.map(\.batting))))
        }
        canvas.table(title: title + " · BATTING", headers: battingHeaders, rows: rows,
                     weights: [4] + Array(repeating: 1, count: battingHeaders.count - 1), compact: compact)
        let extras = players.filter { $0.batting.caughtStealing + $0.batting.sacrifices > 0 }.map {
            "\($0.player.compactName)：CS \($0.batting.caughtStealing)，SAC \($0.batting.sacrifices)"
        }
        if !extras.isEmpty { canvas.paragraph("其他进攻记录：" + extras.joined(separator: "；"), size: 9, muted: true) }
    }

    static func pitching(_ canvas: ReportPDFCanvas, title: String, players: [PlayerSeasonStatistics], includeTotal: Bool = true, compact: Bool = false) {
        let pitchers = players.filter { $0.hasRecord(in: .pitching) }
        var rows = pitchers.map { pitchingRow(playerName($0), line: $0.pitching) }
        if includeTotal && !pitchers.isEmpty { rows.append(pitchingRow("合计", line: .aggregate(pitchers.map(\.pitching)))) }
        canvas.table(title: title + " · PITCHING", headers: pitchingHeaders, rows: rows,
                     weights: [4] + Array(repeating: 1, count: pitchingHeaders.count - 1), compact: compact)
    }

    static func fielding(_ canvas: ReportPDFCanvas, title: String, players: [PlayerSeasonStatistics], includeTotal: Bool = true, compact: Bool = false) {
        let fielders = players.filter { $0.hasRecord(in: .fielding) }
        var rows = fielders.map { fieldingRow(playerName($0), line: $0.fielding) }
        if includeTotal && !fielders.isEmpty { rows.append(fieldingRow("合计", line: .aggregate(fielders.map(\.fielding)))) }
        canvas.table(title: title + " · FIELDING", headers: fieldingHeaders, rows: rows,
                     weights: [4, 1, 1, 1, 1, 1, 1.5], compact: compact)
    }

    static func legend(_ canvas: ReportPDFCanvas) {
        canvas.paragraph("统计口径与缩写", size: 11, bold: true)
        canvas.paragraph("比率按累计原始数据计算；ERA 按 7 局计算。IP 小数位为出局数，0.1 表示 1 人出局。\"-\" 表示未进行、无记录或无可用分母。DP 为球员参与双杀次数，合计不等同于球队双杀次数。", size: 8, muted: true)
        canvas.paragraph("G 场次 · PA 打席 · AB 打数 · R 得分/失分 · H 安打 · 2B/3B/HR 二垒/三垒/本垒打 · RBI 打点 · BB 保送 · HBP 触身球 · SO 三振 · SB/CS 盗垒/盗垒失败 · SAC 牺牲打 · AVG/OBP/SLG/OPS 打率/上垒率/长打率/攻击指数 · BF 面对打者 · ER 自责分 · WP 暴投 · P/S 用球/好球 · PO/A/E 刺杀/助杀/失误 · TC 守备机会 · FPCT 守备率", size: 8, muted: true)
    }
}

@MainActor
struct TeamSeasonPDFReport {
    let team: Team
    let seasonName: String
    let summary: TeamSeasonStatistics

    init(store: GameStore, team: Team, seasonID: String) {
        self.team = team
        seasonName = store.statisticsSeasons.first { $0.id == seasonID }?.name ?? seasonID
        summary = store.seasonStatistics(for: team, seasonID: seasonID)
    }

    func pdfData(generatedAt: Date = Date()) -> Data {
        ReportPDFCanvas.render(kind: "SEASON REPORT / 球队赛季报告", title: team.name,
                               subtitle: "\(seasonName) · 完整球队赛季数据 · \(summary.games.count) 场已结束比赛", generatedAt: generatedAt) { canvas in
            canvas.metrics([("比赛", "\(summary.games.count)"), ("胜 / 负 / 平", "\(summary.wins) / \(summary.losses) / \(summary.ties)"),
                            ("得分", "\(summary.runs)"), ("失分", "\(summary.runsAllowed)"),
                            ("打率 AVG", ReportStatisticsTables.rate(summary.batting.average, available: summary.batting.atBats > 0)),
                            ("防御率 ERA", summary.pitching.outsRecorded > 0 ? String(format: "%.2f", summary.pitching.era) : "-")])
            canvas.paragraph("范围：本球队、本赛季全部已结束比赛与全部球员；不受页面搜索和排序影响。观赛、练习、未来安排及进行中比赛不计入。历史参赛球员仍保留贡献；旧版独立个人记录不计入球队汇总。", size: 9, muted: true)
            if summary.pendingCount > 0 {
                canvas.paragraph("数据待复核：当前范围内有 \(summary.pendingCount) 条待确认记录，数据可能在复核后变化。", size: 10, bold: true)
            }
            let gameRows = summary.games.map { game in
                [ReportPDFCanvas.dateText(game.startedAt ?? game.scheduledAt ?? game.createdAt), game.opponent.name,
                 game.isHome ? "主场" : "客场", game.ourScore == game.opponentScore ? "平" : (game.ourScore > game.opponentScore ? "胜" : "负"),
                 "\(game.ourScore) : \(game.opponentScore)", "\(game.isHome ? game.state.homeHits : game.state.awayHits)",
                 "\(game.isHome ? game.state.homeErrors : game.state.awayErrors)"]
            }
            canvas.table(title: "比赛结果 · GAME LOG", headers: ["日期", "对手", "主客", "结果", "比分（本队:对手）", "H", "E"],
                         rows: gameRows, weights: [1.8, 4, 1, 1, 2.8, 1, 1])
            canvas.newPage()
            let players = summary.filteredPlayers(category: .batting, metric: .plateAppearances, ascending: false, recordsOnly: false)
            ReportStatisticsTables.batting(canvas, title: "赛季打击", players: players, totalGames: summary.games.count)
            canvas.newPage()
            ReportStatisticsTables.pitching(canvas, title: "赛季投手", players: players)
            ReportStatisticsTables.fielding(canvas, title: "赛季守备", players: players)
            ReportStatisticsTables.legend(canvas)
        }
    }

    func write() throws -> URL {
        try ReportExportFile.write(pdfData(), name: "\(team.shortName)-\(seasonName)-球队赛季报告")
    }
}

@MainActor
struct PlayerStatisticsPDFReport {
    let player: Player
    let seasonName: String
    let teamName: String
    let seasonGameCount: Int
    let records: [PlayerGameRecord]
    let games: [StoredGame]
    let summary: PlayerSeasonStatistics

    init(store: GameStore, player: Player, seasonID: String, teamID: UUID?, gameIDs: Set<UUID>) {
        self.player = player
        seasonName = store.statisticsSeasons.first { $0.id == seasonID }?.name ?? seasonID
        teamName = store.teams.first { teamID == nil ? $0.players.contains(where: { $0.id == player.id }) : $0.id == teamID }?.name ?? "历史球员"
        let seasonRecords = store.gameRecords(for: player, seasonID: seasonID, teamID: teamID)
        seasonGameCount = seasonRecords.count
        records = seasonRecords.filter { gameIDs.contains($0.id) }
        games = store.completedStatisticsGames(teamID: teamID, seasonID: seasonID)
            .filter { gameIDs.contains($0.id) && $0.statisticsParticipantIDs.contains(player.id) }
        summary = store.playerStatistics(for: player, seasonID: seasonID, teamID: teamID, gameIDs: gameIDs)
    }

    func pdfData(generatedAt: Date = Date()) -> Data {
        ReportPDFCanvas.render(kind: "PLAYER REPORT / 球员个人报告", title: player.name,
                               subtitle: "\(player.englishName) · \(player.numbersText) · \(teamName) · \(seasonName)\n范围：已选 \(records.count) / \(seasonGameCount) 场比赛", generatedAt: generatedAt) { canvas in
            canvas.metrics([("已选比赛", "\(records.count)"), ("打率 AVG", ReportStatisticsTables.rate(summary.batting.average, available: summary.batting.atBats > 0)),
                            ("安打 H", "\(summary.batting.hits)"), ("打点 RBI", "\(summary.batting.runsBattedIn)"),
                            ("投球局数 IP", summary.pitching.inningsText), ("刺杀 + 助杀", "\(summary.fielding.putouts + summary.fielding.assists)")])
            let legacyCount = records.count - games.count
            canvas.paragraph("仅汇总当前选择的比赛，包含打击、投手和守备三个分类。\(legacyCount > 0 ? "其中 \(legacyCount) 场为旧版个人打击记录，未记录的投手与守备数据以 - 表示。" : "")", size: 9, muted: true)
            let pending = games.reduce(0) { $0 + ($1.state.scoringEvents ?? []).filter(\.needsReview).count }
            if pending > 0 { canvas.paragraph("所选比赛有 \(pending) 条待确认记录，统计可能在复核后变化。", size: 10, bold: true) }
            let rows = records.isEmpty ? [] : [summary]
            ReportStatisticsTables.batting(canvas, title: "所选范围打击", players: rows, totalGames: records.count, includeTotal: false)
            ReportStatisticsTables.pitching(canvas, title: "所选范围投手", players: rows, includeTotal: false)
            ReportStatisticsTables.fielding(canvas, title: "所选范围守备", players: rows, includeTotal: false)
            canvas.newPage()
            canvas.table(title: "逐场打击 · BATTING GAME LOG", headers: ["日期 / 对手 / 结果", "PA", "AB", "R", "H", "HR", "RBI", "BB", "SO", "AVG"],
                         rows: records.map { record in
                let b = record.batting
                return [recordLabel(record), "\(b.plateAppearances)", "\(b.atBats)", "\(b.runs)", "\(b.hits)", "\(b.homeRuns)",
                        "\(b.runsBattedIn)", "\(b.walks)", "\(b.strikeouts)", ReportStatisticsTables.rate(b.average, available: b.atBats > 0)]
            }, weights: [5] + Array(repeating: 1, count: 9))
            canvas.table(title: "逐场投手 · PITCHING GAME LOG", headers: ["日期 / 对手 / 结果", "IP", "H", "R", "ER", "BB", "SO", "P", "ERA"],
                         rows: records.map { record in
                guard let p = games.first(where: { $0.id == record.id })?.state.pitching[player.id] else {
                    return [recordLabel(record)] + Array(repeating: "-", count: 8)
                }
                return [recordLabel(record), p.inningsText, "\(p.hits)", "\(p.runs)", "\(p.earnedRuns)", "\(p.walks)", "\(p.strikeouts)",
                        "\(p.pitches)", p.outsRecorded > 0 ? String(format: "%.2f", p.era) : "-"]
            }, weights: [5] + Array(repeating: 1, count: 8))
            canvas.table(title: "逐场守备 · FIELDING GAME LOG", headers: ["日期 / 对手 / 结果", "PO", "A", "E", "DP", "FPCT"],
                         rows: records.map { record in
                guard let f = games.first(where: { $0.id == record.id })?.state.fielding[player.id] else {
                    return [recordLabel(record)] + Array(repeating: "-", count: 5)
                }
                return [recordLabel(record), "\(f.putouts)", "\(f.assists)", "\(f.errors)", "\(f.doublePlays)", f.percentage.map(statText) ?? "-"]
            }, weights: [5] + Array(repeating: 1, count: 5))
            ReportStatisticsTables.legend(canvas)
        }
    }

    private func recordLabel(_ record: PlayerGameRecord) -> String {
        "\(ReportPDFCanvas.dateText(record.date))\n\(record.opponent)\n\(record.result)"
    }

    func write() throws -> URL {
        try ReportExportFile.write(pdfData(), name: "\(player.name)-\(seasonName)-个人统计报告")
    }
}

@MainActor
struct GameBoxScorePDFReport {
    let game: GameState
    let rules: GameRules?
    let playedAt: Date?

    func pdfData(generatedAt: Date = Date()) -> Data {
        ReportPDFCanvas.render(kind: "BOX SCORE / 单场比赛战报", title: "\(game.awayTeam.name) vs \(game.homeTeam.name)",
                               subtitle: "比赛时间：\(playedAt.map { ReportPDFCanvas.dateText($0, includesTime: true) } ?? "未记录") · \(game.isFinal ? "已结束" : "进行中 / 非最终数据") · 规定 \(rules?.scheduledInnings ?? game.scheduledInnings) 局", generatedAt: generatedAt) { canvas in
            canvas.metrics([("客队 · \(String(game.awayTeam.shortName.prefix(14)))", "\(game.awayScore)"),
                            ("主队 · \(String(game.homeTeam.shortName.prefix(14)))", "\(game.homeScore)"),
                            ("安打 H（客 / 主）", "\(game.awayHits) / \(game.homeHits)"),
                            ("失误 E（客 / 主）", "\(game.awayErrors) / \(game.homeErrors)")])
            canvas.paragraph(game.isFinal ? "结束原因：\(game.endReason?.rawValue ?? "比赛结束")" : "比赛尚未结束，本报告为导出时的比赛快照。", size: 10, bold: true)
            let pending = (game.scoringEvents ?? []).filter(\.needsReview).count
            if pending > 0 { canvas.paragraph("数据待复核：\(pending) 条记录尚待确认。", size: 10, bold: true) }
            let inningCount = max(1, game.inning, game.homeRunsByInning.count, game.awayRunsByInning.count)
            for start in stride(from: 1, through: inningCount, by: 12) {
                let innings = Array(start...min(start + 11, inningCount))
                let rows = [false, true].map { home -> [String] in
                    let team = home ? game.homeTeam : game.awayTeam
                    let values = home ? game.homeRunsByInning : game.awayRunsByInning
                    let runs = innings.map { inning -> String in
                        let value = values.indices.contains(inning - 1) ? values[inning - 1] : 0
                        if value == 0 && (inning > game.inning || (home && inning == game.inning && game.isTop)) { return "-" }
                        return "\(value)"
                    }
                    return [team.name] + runs + ["\(home ? game.homeScore : game.awayScore)", "\(home ? game.homeHits : game.awayHits)", "\(home ? game.homeErrors : game.awayErrors)"]
                }
                canvas.table(title: "逐局比分 · LINE SCORE（\(start)-\(innings.last!) 局）",
                             headers: ["球队"] + innings.map(String.init) + ["R", "H", "E"], rows: rows,
                             weights: [4] + Array(repeating: 1, count: innings.count + 3))
            }
            let scoring = (game.scoringEvents ?? []).filter { event in
                guard let before = event.beforeSituation, let after = event.afterSituation else { return false }
                return after.awayRunsByInning.reduce(0, +) > before.awayRunsByInning.reduce(0, +)
                    || after.homeRunsByInning.reduce(0, +) > before.homeRunsByInning.reduce(0, +)
            }
            canvas.table(title: "得分过程 · SCORING SUMMARY", headers: ["局次", "比赛记录", "比分（客:主）"], rows: scoring.map {
                ["\($0.inning) 局\($0.isTop ? "上" : "下")", $0.title + ($0.needsReview ? "（待确认）" : ""),
                 "\($0.afterSituation!.awayRunsByInning.reduce(0, +)) : \($0.afterSituation!.homeRunsByInning.reduce(0, +))"]
            }, weights: [1.2, 7, 1.8])

            ReportStatisticsTables.legend(canvas)
            for team in [game.awayTeam, game.homeTeam] {
                canvas.newPage()
                let title = team.name + (team.id == game.awayTeam.id ? " · 客队" : " · 主队")
                let players = participants(for: team)
                ReportStatisticsTables.batting(canvas, title: title, players: players, totalGames: 1, compact: true)
                ReportStatisticsTables.pitching(canvas, title: title, players: players, compact: true)
                ReportStatisticsTables.fielding(canvas, title: title, players: players, compact: true)
            }
        }
    }

    private func participants(for team: Team) -> [PlayerSeasonStatistics] {
        let home = team.id == game.homeTeam.id
        let order = home ? game.homeBattingOrderIDs : game.awayBattingOrderIDs
        let exited = home ? game.homeExitedPlayerIDs : game.awayExitedPlayerIDs
        let defense = home ? game.homeFieldingPlayerIDs : game.awayFieldingPlayerIDs
        var ids = order
        let recorded = Set(game.batting.keys).union(game.pitching.keys).union(game.fielding.keys)
            .union(exited ?? []).union(defense ?? [])
        ids += team.players.filter { !ids.contains($0.id) && recorded.contains($0.id) }.map(\.id)
        return ids.compactMap { id in
            guard let player = team.players.first(where: { $0.id == id }) else { return nil }
            return PlayerSeasonStatistics(player: player, gamesPlayed: 1, batting: game.batting[id] ?? BattingLine(),
                                          pitching: game.pitching[id] ?? PitchingLine(), fielding: game.fielding[id] ?? FieldingLine())
        }
    }
}
