import Foundation
import CryptoKit

enum StatisticsCategory: String, CaseIterable, Identifiable {
    case batting = "打击"
    case pitching = "投手"
    case fielding = "守备"

    var id: String { rawValue }
    var metrics: [StatisticsMetric] {
        switch self {
        case .batting: [.average, .ops, .hits, .totalBases, .homeRuns, .rbi, .stolenBases, .plateAppearances]
        case .pitching: [.era, .whip, .strikeouts, .innings, .pitches]
        case .fielding: [.fieldingPercentage, .putouts, .assists, .errors, .doublePlays, .catcherCaughtStealing, .passedBalls]
        }
    }
}

enum StatisticsMetric: String, Identifiable {
    case average = "打率 AVG", ops = "攻击指数 OPS", hits = "安打 H"
    case totalBases = "打击总垒数 TB"
    case homeRuns = "本垒打 HR", rbi = "打点 RBI", stolenBases = "盗垒 SB", plateAppearances = "打席 PA"
    case era = "防御率 ERA", whip = "每局被上垒率 WHIP", strikeouts = "三振 SO", innings = "投球局数 IP", pitches = "用球数 P"
    case fieldingPercentage = "守备率 FPCT", putouts = "刺杀 PO", assists = "助杀 A", errors = "失误 E", doublePlays = "参与双杀 DP"

    case catcherCaughtStealing = "捕手阻杀 CCS", passedBalls = "捕逸 PB"

    var id: String { rawValue }
    var ascendingByDefault: Bool { self == .era || self == .whip || self == .errors || self == .passedBalls }

    func value(for row: PlayerSeasonStatistics) -> Double? {
        switch self {
        case .average: row.batting.atBats > 0 ? row.batting.average : nil
        case .ops: row.batting.atBats > 0 ? row.batting.ops : nil
        case .hits: Double(row.batting.hits)
        case .totalBases: Double(row.batting.totalBases)
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
        case .catcherCaughtStealing: row.fielding.catcherCaughtStealing.map(Double.init)
        case .passedBalls: row.fielding.passedBalls.map(Double.init)
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
    var isUnlinked = false
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

    let wins: Int
    let losses: Int
    var ties: Int { games.count - wins - losses }
    let runs: Int
    let runsAllowed: Int
    let pendingCount: Int
    let hasIncompleteGames: Bool
    let batting: BattingLine
    let pitching: PitchingLine
    let fielding: FieldingLine

    init(games: [StoredGame], players: [PlayerSeasonStatistics]) {
        self.games = games
        self.players = players
        var wins = 0, losses = 0, runs = 0, runsAllowed = 0, pending = 0
        var incomplete = false
        for game in games {
            let scored = game.ourScore
            let allowed = game.opponentScore
            if scored > allowed { wins += 1 }
            if scored < allowed { losses += 1 }
            runs += scored
            runsAllowed += allowed
            incomplete = incomplete || game.state.statisticsIncomplete == true
            for event in game.state.scoringEvents ?? [] where event.needsReview { pending += 1 }
        }
        self.wins = wins
        self.losses = losses
        self.runs = runs
        self.runsAllowed = runsAllowed
        pendingCount = pending
        hasIncompleteGames = incomplete
        batting = BattingLine.aggregate(players.lazy.map(\.batting))
        pitching = PitchingLine.aggregate(players.lazy.map(\.pitching))
        fielding = FieldingLine.aggregate(players.lazy.map(\.fielding))
    }

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
    var catcherCaughtStealingText: String { catcherCaughtStealing.map(String.init) ?? "-" }
    var passedBallsText: String { passedBalls.map(String.init) ?? "-" }
    var percentage: Double? { chances > 0 ? Double(putouts + assists) / Double(chances) : nil }

    static func aggregate<S: Sequence>(_ lines: S) -> FieldingLine where S.Element == FieldingLine {
        lines.reduce(into: FieldingLine()) { result, line in
            result.putouts += line.putouts
            result.assists += line.assists
            result.errors += line.errors
            result.doublePlays += line.doublePlays
            if let count = line.catcherCaughtStealing { result.catcherCaughtStealing = (result.catcherCaughtStealing ?? 0) + count }
            if let count = line.passedBalls { result.passedBalls = (result.passedBalls ?? 0) + count }
        }
    }
}

extension GameState {
    /// Catcher totals are derived from confirmed events. The original shared fielding
    /// payload keeps its existing keys, and historical unassigned events stay unassigned.
    var reportFielding: [UUID: FieldingLine] {
        var lines = fielding
        for event in scoringEvents ?? [] {
            if event.notation == RunnerEventKind.caughtStealing.notation {
                for move in event.runnerMovements {
                    guard case .caughtStealing(let catcherID, _, _) = move.defense else { continue }
                    let prior = lines[catcherID]?.catcherCaughtStealing ?? 0
                    lines[catcherID, default: FieldingLine()].catcherCaughtStealing = prior + 1
                }
            } else if event.notation == RunnerEventKind.passedBall.notation {
                let catcherID = event.runnerMovements.lazy.compactMap { move -> UUID? in
                    if case .passedBall(let id) = move.defense { return id }
                    return nil
                }.first
                if let catcherID {
                    let prior = lines[catcherID]?.passedBalls ?? 0
                    lines[catcherID, default: FieldingLine()].passedBalls = prior + 1
                }
            }
        }
        return lines
    }
}

extension StoredGame {
    func statisticsIdentity(for sourceID: UUID) -> UUID {
        guard sharedTeamID != nil else { return sourceID }
        return sharedPlayerLinks?.first(where: { $0.sourceID == sourceID })?.targetID ?? sourceID
    }
    func statisticsSources(for playerID: UUID, catcherFielding: [UUID: FieldingLine]? = nil) -> Set<UUID> {
        Set(statisticsParticipants(catcherFielding: catcherFielding).filter { statisticsIdentity(for: $0) == playerID })
    }
    func statisticsBatting(for playerID: UUID) -> BattingLine { .aggregate(statisticsSources(for: playerID).compactMap { state.batting[$0] }) }
    func statisticsPitching(for playerID: UUID) -> PitchingLine { .aggregate(statisticsSources(for: playerID).compactMap { state.pitching[$0] }) }
    func statisticsFielding(for playerID: UUID) -> FieldingLine {
        let lines = state.reportFielding
        return .aggregate(statisticsSources(for: playerID, catcherFielding: lines).compactMap { lines[$0] })
    }
    func recordedPitching(for playerID: UUID) -> PitchingLine? {
        let lines = statisticsSources(for: playerID).compactMap { state.pitching[$0] }
        return lines.isEmpty ? nil : .aggregate(lines)
    }
    func recordedFielding(for playerID: UUID) -> FieldingLine? {
        let fielding = state.reportFielding
        let lines = statisticsSources(for: playerID, catcherFielding: fielding).compactMap { fielding[$0] }
        return lines.isEmpty ? nil : .aggregate(lines)
    }

    /// Snapshot membership preserves contributions from substituted or deleted players.
    var statisticsParticipantIDs: Set<UUID> { statisticsParticipants() }

    func statisticsParticipants(catcherFielding: [UUID: FieldingLine]? = nil) -> Set<UUID> {
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
        participants.formUnion((catcherFielding ?? state.fielding).keys)
        return participants.intersection(rosterIDs)
    }
}

struct StatisticsSharedSource: Equatable {
    let directory: ObjectIdentifier
    let directoryRevision: UInt
    let matches: ObjectIdentifier
    let matchesRevision: UInt
}

struct StatisticsSeasonsCache {
    let source: StatisticsSharedSource
    let currentYear: String
    let timeZone: TimeZone
    let seasons: [Season]
}

/// Holds only the last overview so switching tabs and changing display controls reuse its aggregates.
struct StatisticsOverviewCache {
    struct Scope: Equatable {
        let team: Team
        let seasonID: String
        let mode: GameMode?
        let sharedSource: StatisticsSharedSource
    }

    let scope: Scope
    let eligibleGames: [StoredGame]
    let eligibleIDs: Set<UUID>
    let selectedIDs: Set<UUID>?
    let summary: TeamSeasonStatistics
}

@MainActor
extension GameStore {
    private var statisticsSharedSource: StatisticsSharedSource {
        StatisticsSharedSource(
            directory: ObjectIdentifier(teamSharing), directoryRevision: teamSharing.stateRevision,
            matches: ObjectIdentifier(sharedMatches), matchesRevision: sharedMatches.stateRevision
        )
    }

    func statisticsOverview(for team: Team, seasonID: String, mode: GameMode?, selectedIDs: Set<UUID>?) -> StatisticsOverviewCache {
        let scope = StatisticsOverviewCache.Scope(team: team, seasonID: seasonID, mode: mode, sharedSource: statisticsSharedSource)
        let cached = statisticsOverviewCache.flatMap { $0.scope == scope ? $0 : nil }
        let eligible = cached?.eligibleGames ?? completedStatisticsGames(teamID: team.id, seasonID: seasonID, mode: mode)
        let eligibleIDs = cached?.eligibleIDs ?? Set(eligible.map(\.id))
        let selected = selectedIDs.map { $0.intersection(eligibleIDs) }
        if let cached, cached.selectedIDs == selected { return cached }

        let completed = selected.map { ids in eligible.filter { ids.contains($0.id) } } ?? eligible
        let overview = StatisticsOverviewCache(
            scope: scope, eligibleGames: eligible, eligibleIDs: eligibleIDs, selectedIDs: selected,
            summary: seasonStatistics(for: team, completed: completed)
        )
        statisticsOverviewCache = overview
        return overview
    }

    /// Shared summaries always use the last acknowledged document, never an editable local draft.
    var officialStatisticsGames: [StoredGame] {
        let sharedIDs = Set(sharedMatches.state.matches.compactMap { $0.payload?.id })
        let local = games.filter { $0.sharedGameID == nil && $0.sharedTeamID == nil && !sharedIDs.contains($0.id) }
        let accessible = Set(teamSharing.teams.map(\.id))
        let shared = sharedMatches.state.matches.filter { accessible.contains($0.teamID) }.compactMap(\.officialGame)
        return local + shared
    }
    var recordedPlayerGameCount: Int {
        let storedIDs = Set(games.map(\.id))
        let legacyCount = playerGameRecords.filter { !storedIDs.contains($0.id) }.count
        return games.filter { $0.status == .completed && !$0.isObservation && $0.ourTeamID != nil }
            .reduce(legacyCount) { $0 + $1.statisticsParticipantIDs.count }
    }

    var statisticsSeasons: [Season] {
        let source = statisticsSharedSource
        let year = MatchYear.current
        let timeZone = TimeZone.current
        if let cached = statisticsSeasonsCache, cached.source == source,
           cached.currentYear == year, cached.timeZone == timeZone { return cached.seasons }
        var years: Set<String> = [year]
        years.formUnion(officialStatisticsGames.lazy.map(\.yearID))
        years.formUnion(playerGameRecords.lazy.map(\.yearID))
        let seasons = years.sorted(by: >).map { Season(id: $0, name: "\($0) 年") }
        statisticsSeasonsCache = StatisticsSeasonsCache(source: source, currentYear: year, timeZone: timeZone, seasons: seasons)
        return seasons
    }

    /// Separate stable scope for local originals made from a shared roster.
    func localStatisticsScope(_ teamID: UUID) -> UUID {
        let bytes = Array(SHA256.hash(data: Data(("BaseballMaster.local-statistics:" + teamID.uuidString).utf8)).prefix(16))
        return UUID(uuid: (bytes[0],bytes[1],bytes[2],bytes[3],bytes[4],bytes[5],bytes[6],bytes[7],bytes[8],bytes[9],bytes[10],bytes[11],bytes[12],bytes[13],bytes[14],bytes[15]))
    }
    func belongsToStatisticsTeam(_ game: StoredGame, teamID: UUID?) -> Bool {
        if let shared = game.sharedTeamID {
            guard teamSharing.teams.contains(where: { $0.id == shared }) else { return false }
            if let teamID { return teamID == shared }
            // Global totals count an accessible local original once, using its latest saved version.
            let source = game.historySourceID ?? sharedMatches.match(id: game.sharedGameID ?? game.id)?.wireLocalID
            return !games.contains { $0.id == source && $0.sharedGameID == nil && $0.status == .completed }
        }
        guard let teamID else { return true }
        if teamSharing.teams.contains(where: { $0.id == game.ourTeamID }) {
            return game.ourTeamID.map(localStatisticsScope) == teamID
        }
        return game.ourTeamID == teamID
    }

    func completedStatisticsGames(teamID: UUID? = nil, seasonID: String, mode: GameMode? = nil, gameIDs: Set<UUID>? = nil) -> [StoredGame] {
        officialStatisticsGames.filter {
            $0.status == .completed && !$0.isObservation && $0.ourTeamID != nil
                && belongsToStatisticsTeam($0, teamID: teamID) && $0.yearID == seasonID
                && (mode == nil || $0.rules.gameMode == mode)
                && (gameIDs == nil || gameIDs!.contains($0.id))
        }.sorted {
            let lhsDate = $0.effectivePlayedAt
            let rhsDate = $1.effectivePlayedAt
            return lhsDate == rhsDate ? $0.id.uuidString < $1.id.uuidString : lhsDate > rhsDate
        }
    }

    func seasonStatistics(for team: Team, seasonID: String, mode: GameMode? = nil, gameIDs: Set<UUID>? = nil) -> TeamSeasonStatistics {
        let completed = completedStatisticsGames(teamID: team.id, seasonID: seasonID, mode: mode, gameIDs: gameIDs)
        return seasonStatistics(for: team, completed: completed)
    }

    private func seasonStatistics(for team: Team, completed: [StoredGame]) -> TeamSeasonStatistics {
        var players = Dictionary(uniqueKeysWithValues: team.players.map {
            ($0.id, PlayerSeasonStatistics(player: $0))
        })
        let sharedProfile = teamSharing.state.profiles?.first { $0.id == team.id }
        let knownPlayers = sharedProfile?.players.map(\.localPlayer) ?? team.players
        for stored in completed {
            let fielding = stored.state.reportFielding
            let participantIDs = stored.statisticsParticipants(catcherFielding: fielding)
            var countedIdentities = Set<UUID>()
            for player in stored.ourTeam.players where participantIDs.contains(player.id) {
                let identity = stored.statisticsIdentity(for: player.id)
                let known = knownPlayers.first { $0.id == identity }
                var historical = Player(id: identity, chineseName: player.chineseName, englishName: player.englishName, numbers: player.numbers, primaryPosition: player.primaryPosition)
                historical.jerseyNumbers = player.jerseyNumbers
                var row = players[identity] ?? PlayerSeasonStatistics(player: known ?? historical, isCurrentRoster: false,
                    isUnlinked: stored.sharedTeamID != nil && known == nil)
                if countedIdentities.insert(identity).inserted { row.gamesPlayed += 1 }
                row.batting.add(stored.state.batting[player.id] ?? BattingLine())
                if stored.rules.gameMode != .coachPitch { row.pitching = .aggregate([row.pitching, stored.state.pitching[player.id] ?? PitchingLine()]) }
                row.fielding = .aggregate([row.fielding, fielding[player.id] ?? FieldingLine()])
                players[identity] = row
            }
        }
        return TeamSeasonStatistics(games: completed, players: Array(players.values))
    }

    func playerStatistics(
        for player: Player, seasonID: String, teamID: UUID? = nil, gameIDs: Set<UUID>
    ) -> PlayerSeasonStatistics {
        let completed = completedStatisticsGames(teamID: teamID, seasonID: seasonID)
            .filter { gameIDs.contains($0.id) && !$0.statisticsSources(for: player.id).isEmpty }
        let records = gameRecords(for: player, seasonID: seasonID, teamID: teamID)
            .filter { gameIDs.contains($0.id) }
        return PlayerSeasonStatistics(
            player: player, gamesPlayed: records.count,
            batting: .aggregate(records.map(\.batting)),
            pitching: .aggregate(completed.filter { $0.rules.gameMode != .coachPitch }.map { $0.statisticsPitching(for: player.id) }),
            fielding: .aggregate(completed.map { $0.statisticsFielding(for: player.id) })
        )
    }
}
