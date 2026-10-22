import Foundation

enum StatisticsCategory: String, CaseIterable, Identifiable {
    case batting = "打击"
    case pitching = "投手"
    case fielding = "守备"

    var id: String { rawValue }
    var metrics: [StatisticsMetric] {
        switch self {
        case .batting: [.average, .ops, .hits, .homeRuns, .rbi, .stolenBases, .plateAppearances]
        case .pitching: [.era, .whip, .strikeouts, .innings, .pitches]
        case .fielding: [.fieldingPercentage, .putouts, .assists, .errors, .doublePlays]
        }
    }
}

enum StatisticsMetric: String, Identifiable {
    case average = "打率 AVG", ops = "攻击指数 OPS", hits = "安打 H"
    case homeRuns = "本垒打 HR", rbi = "打点 RBI", stolenBases = "盗垒 SB", plateAppearances = "打席 PA"
    case era = "防御率 ERA", whip = "每局被上垒率 WHIP", strikeouts = "三振 SO", innings = "投球局数 IP", pitches = "用球数 P"
    case fieldingPercentage = "守备率 FPCT", putouts = "刺杀 PO", assists = "助杀 A", errors = "失误 E", doublePlays = "参与双杀 DP"

    var id: String { rawValue }
    var ascendingByDefault: Bool { self == .era || self == .whip || self == .errors }

    func value(for row: PlayerSeasonStatistics) -> Double? {
        switch self {
        case .average: row.batting.atBats > 0 ? row.batting.average : nil
        case .ops: row.batting.atBats > 0 ? row.batting.ops : nil
        case .hits: Double(row.batting.hits)
        case .homeRuns: Double(row.batting.homeRuns)
        case .rbi: Double(row.batting.runsBattedIn)
        case .stolenBases: Double(row.batting.stolenBases)
        case .plateAppearances: Double(row.batting.plateAppearances)
        case .era: row.pitching.outsRecorded > 0 ? row.pitching.era : nil
        case .whip: row.pitching.outsRecorded > 0 ? row.pitching.whip : nil
        case .strikeouts: Double(row.pitching.strikeouts)
        case .innings: Double(row.pitching.outsRecorded)
        case .pitches: Double(row.pitching.pitches)
        case .fieldingPercentage: row.fielding.percentage
        case .putouts: Double(row.fielding.putouts)
        case .assists: Double(row.fielding.assists)
        case .errors: Double(row.fielding.errors)
        case .doublePlays: Double(row.fielding.doublePlays)
        }
    }

    func formattedValue(for row: PlayerSeasonStatistics) -> String {
        guard let value = value(for: row) else { return "—" }
        switch self {
        case .average, .ops, .fieldingPercentage: return statText(value)
        case .era, .whip: return String(format: "%.2f", value)
        case .innings: return row.pitching.inningsText
        default: return String(Int(value))
        }
    }
}

struct PlayerSeasonStatistics: Identifiable {
    let player: Player
    var isCurrentRoster = true
    var gamesPlayed = 0
    var batting = BattingLine()
    var pitching = PitchingLine()
    var fielding = FieldingLine()

    var id: UUID { player.id }

    func hasRecord(in category: StatisticsCategory) -> Bool {
        switch category {
        case .batting: batting != BattingLine()
        case .pitching: pitching != PitchingLine()
        case .fielding: fielding != FieldingLine()
        }
    }
}

struct TeamSeasonStatistics {
    let games: [StoredGame]
    let players: [PlayerSeasonStatistics]

    var wins: Int { games.filter { $0.ourScore > $0.opponentScore }.count }
    var losses: Int { games.filter { $0.ourScore < $0.opponentScore }.count }
    var ties: Int { games.count - wins - losses }
    var runs: Int { games.reduce(0) { $0 + $1.ourScore } }
    var runsAllowed: Int { games.reduce(0) { $0 + $1.opponentScore } }
    var pendingCount: Int {
        games.reduce(0) { $0 + ($1.state.scoringEvents ?? []).filter(\.needsReview).count }
    }
    var batting: BattingLine { BattingLine.aggregate(players.map(\.batting)) }
    var pitching: PitchingLine { PitchingLine.aggregate(players.map(\.pitching)) }
    var fielding: FieldingLine { FieldingLine.aggregate(players.map(\.fielding)) }

    func filteredPlayers(
        category: StatisticsCategory,
        metric: StatisticsMetric,
        ascending: Bool,
        query: String = "",
        recordsOnly: Bool = true
    ) -> [PlayerSeasonStatistics] {
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return players.filter { row in
            let matches = search.isEmpty || "\(row.player.chineseName) \(row.player.englishName) \(row.player.numbersText)"
                .localizedStandardContains(search)
            return matches && (!recordsOnly || row.hasRecord(in: category))
        }.sorted { lhs, rhs in
            let left = metric.value(for: lhs)
            let right = metric.value(for: rhs)
            // Undefined rates always sort last, including in ascending order.
            if let left, let right, left != right { return ascending ? left < right : left > right }
            if (left == nil) != (right == nil) { return left != nil }
            if lhs.player.number != rhs.player.number { return lhs.player.number < rhs.player.number }
            if lhs.player.name != rhs.player.name {
                return lhs.player.name.localizedStandardCompare(rhs.player.name) == .orderedAscending
            }
            return lhs.id.uuidString < rhs.id.uuidString
        }
    }
}

extension PitchingLine {
    static func aggregate<S: Sequence>(_ lines: S) -> PitchingLine where S.Element == PitchingLine {
        lines.reduce(into: PitchingLine()) { result, line in
            result.outsRecorded += line.outsRecorded
            result.battersFaced += line.battersFaced
            result.hits += line.hits
            result.runs += line.runs
            result.earnedRuns += line.earnedRuns
            result.walks += line.walks
            result.hitByPitch += line.hitByPitch
            result.strikeouts += line.strikeouts
            result.wildPitches += line.wildPitches
            result.pitches += line.pitches
            result.strikes += line.strikes
        }
    }
}

extension FieldingLine {
    var chances: Int { putouts + assists + errors }
    var percentage: Double? { chances > 0 ? Double(putouts + assists) / Double(chances) : nil }

    static func aggregate<S: Sequence>(_ lines: S) -> FieldingLine where S.Element == FieldingLine {
        lines.reduce(into: FieldingLine()) { result, line in
            result.putouts += line.putouts
            result.assists += line.assists
            result.errors += line.errors
            result.doublePlays += line.doublePlays
        }
    }
}

extension StoredGame {
    /// Snapshot membership preserves contributions from substituted or deleted players.
    var statisticsParticipantIDs: Set<UUID> {
        let rosterIDs = Set(ourTeam.players.map(\.id))
        let order = isHome ? state.homeBattingOrderIDs : state.awayBattingOrderIDs
        let exited = isHome ? state.homeExitedPlayerIDs : state.awayExitedPlayerIDs
        let defense = isHome ? state.homeFieldingPlayerIDs : state.awayFieldingPlayerIDs
        var participants = Set(lineup.map(\.playerID))
        participants.formUnion(order)
        participants.formUnion(exited ?? [])
        participants.formUnion(defense ?? [])
        participants.formUnion(state.batting.keys)
        participants.formUnion(state.pitching.keys)
        participants.formUnion(state.fielding.keys)
        return participants.intersection(rosterIDs)
    }
}

@MainActor
extension GameStore {
    var recordedPlayerGameCount: Int {
        let storedIDs = Set(games.map(\.id))
        let legacyCount = playerGameRecords.filter { !storedIDs.contains($0.id) }.count
        return games.filter { $0.status == .completed && !$0.isObservation && $0.ourTeamID != nil }
            .reduce(legacyCount) { $0 + $1.statisticsParticipantIDs.count }
    }

    var statisticsSeasons: [Season] {
        var result = seasons
        let knownIDs = Set(result.map(\.id))
        let recordedIDs = Set(games.map(\.seasonID) + playerGameRecords.map(\.seasonID))
        result += recordedIDs.subtracting(knownIDs).sorted().map { Season(id: $0, name: $0) }
        return result
    }

    func completedStatisticsGames(teamID: UUID? = nil, seasonID: String) -> [StoredGame] {
        games.filter {
            $0.status == .completed && !$0.isObservation && $0.ourTeamID != nil
                && (teamID == nil || $0.ourTeamID == teamID) && $0.seasonID == seasonID
        }.sorted {
            let lhsDate = $0.startedAt ?? $0.scheduledAt ?? $0.createdAt
            let rhsDate = $1.startedAt ?? $1.scheduledAt ?? $1.createdAt
            return lhsDate == rhsDate ? $0.id.uuidString < $1.id.uuidString : lhsDate > rhsDate
        }
    }

    func seasonStatistics(for team: Team, seasonID: String) -> TeamSeasonStatistics {
        let completed = completedStatisticsGames(teamID: team.id, seasonID: seasonID)
        var players = Dictionary(uniqueKeysWithValues: team.players.map {
            ($0.id, PlayerSeasonStatistics(player: $0))
        })
        for stored in completed {
            let participantIDs = stored.statisticsParticipantIDs
            for player in stored.ourTeam.players where participantIDs.contains(player.id) {
                var row = players[player.id] ?? PlayerSeasonStatistics(player: player, isCurrentRoster: false)
                row.gamesPlayed += 1
                row.batting.add(stored.state.batting[player.id] ?? BattingLine())
                row.pitching = .aggregate([row.pitching, stored.state.pitching[player.id] ?? PitchingLine()])
                row.fielding = .aggregate([row.fielding, stored.state.fielding[player.id] ?? FieldingLine()])
                players[player.id] = row
            }
        }
        return TeamSeasonStatistics(games: completed, players: Array(players.values))
    }

    func playerStatistics(
        for player: Player, seasonID: String, teamID: UUID? = nil, gameIDs: Set<UUID>
    ) -> PlayerSeasonStatistics {
        let completed = completedStatisticsGames(teamID: teamID, seasonID: seasonID)
            .filter { gameIDs.contains($0.id) && $0.statisticsParticipantIDs.contains(player.id) }
        let records = gameRecords(for: player, seasonID: seasonID, teamID: teamID)
            .filter { gameIDs.contains($0.id) }
        return PlayerSeasonStatistics(
            player: player, gamesPlayed: records.count,
            batting: .aggregate(records.map(\.batting)),
            pitching: .aggregate(completed.compactMap { $0.state.pitching[player.id] }),
            fielding: .aggregate(completed.compactMap { $0.state.fielding[player.id] })
        )
    }
}
