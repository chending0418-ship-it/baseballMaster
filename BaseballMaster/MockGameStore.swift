import Combine
import Foundation

@MainActor
final class MockGameStore: ObservableObject {
    @Published var teams: [Team]
    @Published var currentTeam: Team
    @Published var game: DemoGameState
    @Published var selectedTab = 0

    private var undoStack: [DemoGameState] = []

    init() {
        let waves = Self.makeWaves()
        let falcons = Self.makeFalcons()
        self.teams = [waves, falcons]
        self.currentTeam = waves
        self.game = DemoGameState(homeTeam: falcons, awayTeam: waves, scheduledInnings: 6)
        seedDemonstrationState()
    }

    var canUndo: Bool { !undoStack.isEmpty }
    var hasRunners: Bool { !game.baseRunners.isEmpty }
    var currentBatter: Player { game.currentBatter }
    var currentPitcher: Player { game.currentPitcher }

    func startNewGame(opponent: Team, isHome: Bool, innings: Int, lineup: [Player]) {
        var ourTeam = currentTeam
        ourTeam.players = lineup
        let home = isHome ? ourTeam : opponent
        let away = isHome ? opponent : ourTeam
        game = DemoGameState(homeTeam: home, awayTeam: away, scheduledInnings: innings)
        undoStack.removeAll()
        addLog("比赛开始：\(away.shortName) 对 \(home.shortName)")
    }

    func addPlayer(name: String, number: Int, position: FieldPosition) {
        let player = Player(name: name, number: number, primaryPosition: position)
        currentTeam.players.append(player)
        if let index = teams.firstIndex(where: { $0.id == currentTeam.id }) {
            teams[index] = currentTeam
        }
    }

    func recordPitch(_ action: PitchAction) {
        pushUndo()
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updatePitcher(pitcher.id) { line in
            line.pitches += 1
            if action != .ball { line.strikes += 1 }
        }

        switch action {
        case .ball:
            game.balls += 1
            addLog("\(batter.name)：坏球（\(game.balls)坏 \(game.strikes)好）")
            if game.balls >= 4 { applyWalkWithoutUndo() }
        case .calledStrike:
            game.strikes += 1
            addLog("\(batter.name)：看振（\(game.balls)坏 \(game.strikes)好）")
            if game.strikes >= 3 { applyStrikeoutWithoutUndo(swinging: false) }
        case .swingingStrike:
            game.strikes += 1
            addLog("\(batter.name)：挥空（\(game.balls)坏 \(game.strikes)好）")
            if game.strikes >= 3 { applyStrikeoutWithoutUndo(swinging: true) }
        case .foul:
            if game.strikes < 2 { game.strikes += 1 }
            addLog("\(batter.name)：界外球（\(game.balls)坏 \(game.strikes)好）")
        }
    }

    func suggestedRunnerDecisions(
        for outcome: PlayOutcome,
        batterDestination overrideDestination: RunnerDestination? = nil
    ) -> [RunnerDecision] {
        var decisions: [RunnerDecision] = []
        let existing = Base.allCases.reversed().compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: suggestedDestination(from: base, outcome: outcome))
        }
        decisions.append(contentsOf: existing)

        let suggestedBatterDestination: RunnerDestination
        switch outcome {
        case .single, .error, .fieldersChoice, .pending: suggestedBatterDestination = .base(.first)
        case .double: suggestedBatterDestination = .base(.second)
        case .triple: suggestedBatterDestination = .base(.third)
        case .homeRun: suggestedBatterDestination = .score
        case .groundOut, .flyOut, .sacrificeBunt, .sacrificeFly, .doublePlay, .pendingOut: suggestedBatterDestination = .out
        case .other: suggestedBatterDestination = .hold
        }
        let batterDestination = overrideDestination ?? suggestedBatterDestination
        decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: batterDestination))

        if outcome == .fieldersChoice, let firstRunnerIndex = decisions.firstIndex(where: {
            if case .base = $0.origin { return true }
            return false
        }) {
            decisions[firstRunnerIndex].destination = .out
        }

        if outcome == .doublePlay {
            if let runnerIndex = decisions.firstIndex(where: {
                if case .base = $0.origin { return true }
                return false
            }) {
                decisions[runnerIndex].destination = .out
            }
        }
        return decisions
    }

    func suggestedRunnerEventDecisions(for kind: RunnerEventKind) -> [RunnerDecision] {
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: .hold)
        }

        switch kind {
        case .wildPitch, .passedBall, .uncertainLooseBall, .balk:
            for index in decisions.indices {
                guard case .base(let base) = decisions[index].origin else { continue }
                decisions[index].destination = nextDestination(after: base)
            }
        case .stolenBase:
            if let index = decisions.indices.last,
               case .base(let base) = decisions[index].origin {
                decisions[index].destination = nextDestination(after: base)
            }
        case .caughtStealing, .pickoff:
            if let index = decisions.indices.first {
                decisions[index].destination = .out
            }
        }
        return decisions
    }

    func droppedThirdStrikeDecisions() -> [RunnerDecision] {
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: .hold)
        }
        decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: .base(.first)))
        return decisions
    }

    var canReachOnDroppedThirdStrike: Bool {
        game.outs == 2 || game.baseRunners[.first] == nil
    }

    func availableDestinations(for decision: RunnerDecision) -> [RunnerDestination] {
        switch decision.origin {
        case .batter:
            return [.base(.first), .base(.second), .base(.third), .score, .out]
        case .base(.first):
            return [.hold, .base(.second), .base(.third), .score, .out]
        case .base(.second):
            return [.hold, .base(.third), .score, .out]
        case .base(.third):
            return [.hold, .score, .out]
        }
    }

    func applyPlay(_ outcome: PlayOutcome, defensivePlay: DefensivePlay? = nil, decisions: [RunnerDecision]? = nil) {
        pushUndo()
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        let finalDecisions = decisions ?? suggestedRunnerDecisions(for: outcome)
        var battingLine = game.batting[batter.id, default: BattingLine()]
        var pitchingLine = game.pitching[pitcher.id, default: PitchingLine()]
        battingLine.plateAppearances += 1
        pitchingLine.battersFaced += 1
        pitchingLine.pitches += 1
        pitchingLine.strikes += 1

        switch outcome {
        case .single, .double, .triple, .homeRun:
            battingLine.atBats += 1
            battingLine.hits += 1
            pitchingLine.hits += 1
            if outcome == .double { battingLine.doubles += 1 }
            if outcome == .triple { battingLine.triples += 1 }
            if outcome == .homeRun { battingLine.homeRuns += 1 }
            incrementTeamHit()
        case .error, .fieldersChoice, .groundOut, .flyOut, .doublePlay, .pendingOut, .pending:
            battingLine.atBats += 1
        case .sacrificeBunt, .sacrificeFly:
            battingLine.sacrifices += 1
        case .other:
            break
        }

        var resolution = resolveRunners(finalDecisions)
        let batterIsOut = finalDecisions.contains { decision in
            if case .batter = decision.origin, decision.destination == .out { return true }
            return false
        }
        let reachesThirdOut = game.outs + resolution.outs >= 3
        let thirdOutCancelsRuns = reachesThirdOut && (
            (game.outs == 2 && batterIsOut)
                || [.groundOut, .fieldersChoice, .doublePlay, .pendingOut].contains(outcome)
        )
        if thirdOutCancelsRuns {
            resolution = (runs: 0, outs: resolution.outs, scorers: [])
        }
        battingLine.runsBattedIn += [.error, .pendingOut, .pending, .other].contains(outcome) ? 0 : resolution.runs
        pitchingLine.runs += resolution.runs
        pitchingLine.earnedRuns += [.error, .pendingOut, .pending].contains(outcome) ? 0 : resolution.runs
        pitchingLine.outsRecorded += resolution.outs
        game.batting[batter.id] = battingLine
        game.pitching[pitcher.id] = pitchingLine

        for scorer in resolution.scorers {
            updateBatter(scorer.id) { $0.runs += 1 }
        }
        addRuns(resolution.runs)
        applyDefensiveCredits(defensivePlay, outcome: outcome)

        let defenseText = defensivePlay.map { "，\($0.title)（\($0.notation)）" } ?? ""
        let runnerText = runnerSummary(finalDecisions)
        addLog(
            "\(batter.name)：\(outcome.rawValue)\(defenseText)\(runnerText.isEmpty ? "" : "；\(runnerText)")",
            incomplete: outcome == .other || outcome == .pendingOut || outcome == .pending
        )
        if evaluateWalkOff() { return }
        advanceBatter()
        if resolution.outs > 0 { registerOuts(resolution.outs) }
    }

    func recordSubstitution(_ text: String) {
        pushUndo()
        addLog(text)
    }

    func recordHitByPitch() {
        pushUndo()
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.hitByPitch += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.hitByPitch += 1
            $0.pitches += 1
        }

        let resolution = resolveRunners(forcedAdvanceDecisions(for: batter))
        addRunsAndPitcherResponsibility(resolution, pitcherID: pitcher.id, earned: true)
        addLog("\(batter.name) 被球击中，上一垒（HBP）")
        if evaluateWalkOff() { return }
        advanceBatter()
    }

    func recordIntentionalWalk() {
        pushUndo()
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.walks += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.walks += 1
        }

        let resolution = resolveRunners(forcedAdvanceDecisions(for: batter))
        addRunsAndPitcherResponsibility(resolution, pitcherID: pitcher.id, earned: true)
        addLog("\(batter.name) 被故意保送上一垒（IBB）")
        if evaluateWalkOff() { return }
        advanceBatter()
    }

    func recordDroppedThirdStrike(decisions: [RunnerDecision]) {
        guard canReachOnDroppedThirdStrike else { return }
        pushUndo()
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.atBats += 1
            $0.strikeouts += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.strikeouts += 1
            $0.pitches += 1
            $0.strikes += 1
        }

        let resolution = resolveRunners(decisions)
        addRunsAndPitcherResponsibility(resolution, pitcherID: pitcher.id, earned: true)
        addLog("\(batter.name) 三振，但捕手未接住；\(runnerSummary(decisions))", incomplete: true)
        if evaluateWalkOff() { return }
        advanceBatter()
        if resolution.outs > 0 { registerOuts(resolution.outs) }
    }

    func recordRunnerEvent(_ kind: RunnerEventKind, decisions: [RunnerDecision]) {
        guard !decisions.isEmpty else { return }
        pushUndo()
        let pitcher = game.currentPitcher
        let resolution = resolveRunners(decisions)

        switch kind {
        case .stolenBase:
            for decision in decisions where decision.destination != .hold && decision.destination != .out {
                updateBatter(decision.player.id) { $0.stolenBases += 1 }
            }
        case .caughtStealing:
            for decision in decisions where decision.destination == .out {
                updateBatter(decision.player.id) { $0.caughtStealing += 1 }
            }
        case .wildPitch:
            updatePitcher(pitcher.id) { $0.wildPitches += 1 }
        case .passedBall, .uncertainLooseBall, .pickoff, .balk:
            break
        }

        let isEarned = kind != .passedBall && kind != .uncertainLooseBall
        addRunsAndPitcherResponsibility(resolution, pitcherID: pitcher.id, earned: isEarned)
        let summary = runnerSummary(decisions)
        addLog("\(kind.rawValue)\(summary.isEmpty ? "" : "；\(summary)")", incomplete: kind == .uncertainLooseBall)
        if evaluateWalkOff() { return }
        if resolution.outs > 0 { registerOuts(resolution.outs) }
    }

    func correctGameState(
        inning: Int,
        isTop: Bool,
        balls: Int,
        strikes: Int,
        outs: Int,
        awayScore: Int,
        homeScore: Int,
        batterIndex: Int,
        pitcherID: UUID?,
        baseRunners: [Base: Player]
    ) {
        pushUndo()
        game.inning = max(1, inning)
        game.isTop = isTop
        ensureInningCapacity()
        setScore(max(0, awayScore), forHomeTeam: false)
        setScore(max(0, homeScore), forHomeTeam: true)
        game.balls = min(max(0, balls), 3)
        game.strikes = min(max(0, strikes), 2)
        game.outs = min(max(0, outs), 2)
        game.baseRunners = baseRunners

        let lineupCount = max(1, game.battingTeam.players.count)
        if isTop {
            game.awayBatterIndex = min(max(0, batterIndex), lineupCount - 1)
            game.activeHomePitcherID = pitcherID
        } else {
            game.homeBatterIndex = min(max(0, batterIndex), lineupCount - 1)
            game.activeAwayPitcherID = pitcherID
        }
        addLog("现场状态已人工修正", incomplete: true)
    }

    func changePitcher(to player: Player) {
        guard game.fieldingTeam.players.contains(where: { $0.id == player.id }) else { return }
        pushUndo()
        var team = game.fieldingTeam
        if let newIndex = team.players.firstIndex(where: { $0.id == player.id }),
           let oldIndex = team.players.firstIndex(where: { $0.id == game.currentPitcher.id }),
           team.players[newIndex].primaryPosition != .pitcher {
            let replacementPosition = team.players[newIndex].primaryPosition
            team.players[newIndex].primaryPosition = .pitcher
            team.players[oldIndex].primaryPosition = replacementPosition
        }
        if game.isTop {
            game.homeTeam = team
            game.activeHomePitcherID = player.id
        } else {
            game.awayTeam = team
            game.activeAwayPitcherID = player.id
        }
        addLog("换投手：#\(player.number) \(player.name) 登板")
    }

    func replaceRunner(on base: Base, with player: Player) {
        guard game.battingTeam.players.contains(where: { $0.id == player.id }) else { return }
        pushUndo()
        let previous = game.baseRunners[base]
        game.baseRunners[base] = player
        addLog("代跑：#\(player.number) \(player.name) 替换\(previous.map { " #\($0.number) \($0.name)" } ?? "跑者")")
    }

    func replaceCurrentBatter(with player: Player) {
        var team = game.battingTeam
        let currentIndex = game.isTop ? game.awayBatterIndex : game.homeBatterIndex
        let lineupIndex = currentIndex % max(team.players.count, 1)
        guard let replacementIndex = team.players.firstIndex(where: { $0.id == player.id }),
              replacementIndex != lineupIndex else { return }

        pushUndo()
        let previous = team.players[lineupIndex]
        team.players.swapAt(lineupIndex, replacementIndex)
        if game.isTop {
            game.awayTeam = team
        } else {
            game.homeTeam = team
        }
        addLog("代打：#\(player.number) \(player.name) 替换 #\(previous.number) \(previous.name)")
    }

    func changeFieldingPosition(for player: Player, to position: FieldPosition) {
        var team = game.fieldingTeam
        guard let playerIndex = team.players.firstIndex(where: { $0.id == player.id }) else { return }
        pushUndo()

        let oldPosition = team.players[playerIndex].primaryPosition
        if let otherIndex = team.players.firstIndex(where: { $0.primaryPosition == position && $0.id != player.id }) {
            team.players[otherIndex].primaryPosition = oldPosition
        }
        team.players[playerIndex].primaryPosition = position

        if game.isTop {
            game.homeTeam = team
            if position == .pitcher { game.activeHomePitcherID = player.id }
        } else {
            game.awayTeam = team
            if position == .pitcher { game.activeAwayPitcherID = player.id }
        }
        addLog("守位调整：#\(player.number) \(player.name) 改守\(position.fullName)")
    }

    func undo() {
        guard let previous = undoStack.popLast() else { return }
        game = previous
    }

    func finishGame() {
        pushUndo()
        game.isFinal = true
        addLog("比赛记录已结束")
    }

    func battingLine(for player: Player) -> BattingLine {
        game.batting[player.id, default: BattingLine()]
    }

    func pitchingLine(for player: Player) -> PitchingLine {
        game.pitching[player.id, default: PitchingLine()]
    }

    func fieldingLine(for player: Player) -> FieldingLine {
        game.fielding[player.id, default: FieldingLine()]
    }

    func seasonBattingLine(for player: Player) -> BattingLine {
        var line = battingLine(for: player)
        let seed = max(1, player.number % 5)
        line.plateAppearances += 14 + seed
        line.atBats += 12 + seed
        line.hits += 4 + seed
        line.doubles += seed % 2
        line.runs += 3 + seed
        line.runsBattedIn += 2 + seed
        line.walks += 2
        line.strikeouts += 2 + seed
        return line
    }

    private func applyWalkWithoutUndo() {
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        var line = game.batting[batter.id, default: BattingLine()]
        line.plateAppearances += 1
        line.walks += 1
        game.batting[batter.id] = line
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.walks += 1
        }

        var decisions = forcedAdvanceDecisions(for: batter)
        let resolution = resolveRunners(decisions)
        addRuns(resolution.runs)
        for scorer in resolution.scorers { updateBatter(scorer.id) { $0.runs += 1 } }
        updatePitcher(pitcher.id) {
            $0.runs += resolution.runs
            $0.earnedRuns += resolution.runs
        }
        addLog("\(batter.name) 四坏球保送上一垒\(resolution.runs > 0 ? "，挤回 \(resolution.runs) 分" : "")")
        if evaluateWalkOff() { return }
        advanceBatter()
        decisions.removeAll()
    }

    private func applyStrikeoutWithoutUndo(swinging: Bool) {
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.atBats += 1
            $0.strikeouts += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.strikeouts += 1
            $0.outsRecorded += 1
        }
        addLog("\(batter.name) \(swinging ? "挥棒" : "看着")三振出局（K）")
        advanceBatter()
        registerOuts(1)
    }

    private func forcedAdvanceDecisions(for batter: Player) -> [RunnerDecision] {
        var decisions: [RunnerDecision] = []
        if let runner = game.baseRunners[.third], game.baseRunners[.second] != nil, game.baseRunners[.first] != nil {
            decisions.append(RunnerDecision(player: runner, origin: .base(.third), destination: .score))
        } else if let runner = game.baseRunners[.third] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.third), destination: .hold))
        }
        if let runner = game.baseRunners[.second] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.second), destination: game.baseRunners[.first] == nil ? .hold : .base(.third)))
        }
        if let runner = game.baseRunners[.first] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.first), destination: .base(.second)))
        }
        decisions.append(RunnerDecision(player: batter, origin: .batter, destination: .base(.first)))
        return decisions
    }

    private func suggestedDestination(from base: Base, outcome: PlayOutcome) -> RunnerDestination {
        switch outcome {
        case .homeRun: return .score
        case .triple:
            return base == .first ? .score : .score
        case .double:
            return base == .first ? .base(.third) : .score
        case .single, .error, .pending:
            switch base {
            case .first: return .base(.second)
            case .second: return .base(.third)
            case .third: return .score
            }
        case .sacrificeFly:
            return base == .third ? .score : .hold
        case .sacrificeBunt:
            switch base {
            case .first: return .base(.second)
            case .second: return .base(.third)
            case .third: return .score
            }
        case .fieldersChoice, .doublePlay, .groundOut, .flyOut, .pendingOut, .other:
            return .hold
        }
    }

    private func nextDestination(after base: Base) -> RunnerDestination {
        switch base {
        case .first: .base(.second)
        case .second: .base(.third)
        case .third: .score
        }
    }

    private func resolveRunners(_ decisions: [RunnerDecision]) -> (runs: Int, outs: Int, scorers: [Player]) {
        var newBases: [Base: Player] = [:]
        var scorers: [Player] = []
        var outs = 0

        for decision in decisions {
            switch decision.destination {
            case .hold:
                if case .base(let base) = decision.origin { newBases[base] = decision.player }
            case .base(let base):
                newBases[base] = decision.player
            case .score:
                scorers.append(decision.player)
            case .out:
                outs += 1
            }
        }
        game.baseRunners = newBases
        return (scorers.count, outs, scorers)
    }

    private func runnerSummary(_ decisions: [RunnerDecision]) -> String {
        decisions
            .filter { decision in
                if case .hold = decision.destination { return false }
                return true
            }
            .map { "\($0.player.name)\($0.destination.title)" }
            .joined(separator: "，")
    }

    private func applyDefensiveCredits(_ play: DefensivePlay?, outcome: PlayOutcome) {
        guard let play else { return }
        let defense = game.fieldingTeam.players
        for position in play.assistPositions {
            if let player = defense.first(where: { $0.primaryPosition == position }) {
                updateFielder(player.id) { $0.assists += 1 }
            }
        }
        if let position = play.putoutPosition,
           let player = defense.first(where: { $0.primaryPosition == position }) {
            updateFielder(player.id) {
                $0.putouts += outcome == .doublePlay ? 2 : 1
                if outcome == .doublePlay { $0.doublePlays += 1 }
            }
        }
        if let position = play.errorPosition,
           let player = defense.first(where: { $0.primaryPosition == position }) {
            updateFielder(player.id) { $0.errors += 1 }
            if game.isTop { game.homeErrors += 1 } else { game.awayErrors += 1 }
        }
    }

    private func registerOuts(_ newOuts: Int) {
        game.outs += newOuts
        guard game.outs >= 3 else { return }
        game.outs = 0
        game.balls = 0
        game.strikes = 0
        game.baseRunners.removeAll()
        if game.isTop {
            if game.inning >= game.scheduledInnings && game.homeScore > game.awayScore {
                game.isFinal = true
                addLog("三出局，主队领先，无需进行下半局，比赛结束")
                return
            }
            addLog("三出局，攻守交换")
            game.isTop = false
        } else {
            if game.inning >= game.scheduledInnings && game.homeScore != game.awayScore {
                game.isFinal = true
                addLog("三出局，规定局数完成，比赛结束")
                return
            }
            addLog("三出局，攻守交换")
            game.isTop = true
            game.inning += 1
            ensureInningCapacity()
        }
    }

    @discardableResult
    private func evaluateWalkOff() -> Bool {
        guard !game.isTop,
              game.inning >= game.scheduledInnings,
              game.homeScore > game.awayScore else { return false }
        game.isFinal = true
        game.balls = 0
        game.strikes = 0
        addLog("主队取得领先，比赛结束")
        return true
    }

    private func advanceBatter() {
        if game.isTop {
            game.awayBatterIndex = (game.awayBatterIndex + 1) % game.awayTeam.players.count
        } else {
            game.homeBatterIndex = (game.homeBatterIndex + 1) % game.homeTeam.players.count
        }
        game.balls = 0
        game.strikes = 0
    }

    private func addRuns(_ runs: Int) {
        guard runs > 0 else { return }
        ensureInningCapacity()
        if game.isTop {
            game.awayRunsByInning[game.inning - 1] += runs
        } else {
            game.homeRunsByInning[game.inning - 1] += runs
        }
    }

    private func addRunsAndPitcherResponsibility(
        _ resolution: (runs: Int, outs: Int, scorers: [Player]),
        pitcherID: UUID,
        earned: Bool
    ) {
        addRuns(resolution.runs)
        for scorer in resolution.scorers {
            updateBatter(scorer.id) { $0.runs += 1 }
        }
        updatePitcher(pitcherID) {
            $0.runs += resolution.runs
            if earned { $0.earnedRuns += resolution.runs }
        }
    }

    private func setScore(_ target: Int, forHomeTeam: Bool) {
        var innings = forHomeTeam ? game.homeRunsByInning : game.awayRunsByInning
        while innings.count < game.inning { innings.append(0) }
        var difference = target - innings.reduce(0, +)
        if difference >= 0 {
            innings[game.inning - 1] += difference
        } else {
            for index in innings.indices.reversed() where difference < 0 {
                let removable = min(innings[index], -difference)
                innings[index] -= removable
                difference += removable
            }
        }
        if forHomeTeam {
            game.homeRunsByInning = innings
        } else {
            game.awayRunsByInning = innings
        }
    }

    private func incrementTeamHit() {
        if game.isTop { game.awayHits += 1 } else { game.homeHits += 1 }
    }

    private func ensureInningCapacity() {
        while game.homeRunsByInning.count < game.inning {
            game.homeRunsByInning.append(0)
            game.awayRunsByInning.append(0)
        }
    }

    private func updateBatter(_ id: UUID, _ change: (inout BattingLine) -> Void) {
        var line = game.batting[id, default: BattingLine()]
        change(&line)
        game.batting[id] = line
    }

    private func updatePitcher(_ id: UUID, _ change: (inout PitchingLine) -> Void) {
        var line = game.pitching[id, default: PitchingLine()]
        change(&line)
        game.pitching[id] = line
    }

    private func updateFielder(_ id: UUID, _ change: (inout FieldingLine) -> Void) {
        var line = game.fielding[id, default: FieldingLine()]
        change(&line)
        game.fielding[id] = line
    }

    private func pushUndo() {
        undoStack.append(game)
        if undoStack.count > 30 { undoStack.removeFirst() }
    }

    private func addLog(_ text: String, incomplete: Bool = false) {
        game.playLog.append(
            PlayLogEntry(inning: game.inning, isTop: game.isTop, text: text, isIncomplete: incomplete)
        )
    }

    private func seedDemonstrationState() {
        game.awayRunsByInning[0] = 1
        game.homeRunsByInning[0] = 0
        game.inning = 2
        game.isTop = true
        game.awayHits = 2
        game.homeHits = 1
        game.awayBatterIndex = 3
        if game.awayTeam.players.count > 2 {
            game.baseRunners[.first] = game.awayTeam.players[2]
        }
        let first = game.awayTeam.players[0]
        var firstLine = BattingLine()
        firstLine.plateAppearances = 1
        firstLine.atBats = 1
        firstLine.hits = 1
        firstLine.runs = 1
        game.batting[first.id] = firstLine
        game.playLog = [
            PlayLogEntry(inning: 1, isTop: true, text: "陈昊：一垒安打（1B），到一垒"),
            PlayLogEntry(inning: 1, isTop: true, text: "周子墨：二垒安打（2B），陈昊得分"),
            PlayLogEntry(inning: 1, isTop: true, text: "林宇轩：挥棒三振出局（K）"),
            PlayLogEntry(inning: 1, isTop: true, text: "三出局，攻守交换"),
            PlayLogEntry(inning: 1, isTop: false, text: "赵一鸣：中外野接杀（F8）")
        ]
    }

    private static func makeWaves() -> Team {
        Team(name: "青岛海浪", shortName: "海浪", city: "青岛", players: [
            Player(name: "陈昊", number: 12, primaryPosition: .shortstop),
            Player(name: "周子墨", number: 7, primaryPosition: .centerField),
            Player(name: "林宇轩", number: 18, primaryPosition: .catcher),
            Player(name: "王星野", number: 23, primaryPosition: .pitcher),
            Player(name: "许嘉树", number: 5, primaryPosition: .thirdBase),
            Player(name: "韩一川", number: 9, primaryPosition: .leftField),
            Player(name: "顾晨", number: 3, primaryPosition: .firstBase),
            Player(name: "沈亦航", number: 16, primaryPosition: .secondBase),
            Player(name: "唐乐天", number: 21, primaryPosition: .rightField),
            Player(name: "陆景然", number: 2, primaryPosition: .catcher),
            Player(name: "江沐阳", number: 10, primaryPosition: .centerField),
            Player(name: "宋知远", number: 30, primaryPosition: .pitcher)
        ])
    }

    private static func makeFalcons() -> Team {
        let surnames = ["赵一鸣", "高博文", "邵子谦", "何俊熙", "梁天佑", "彭奕辰", "罗凯", "杜明泽", "郑文轩"]
        let positions = FieldPosition.allCases
        return Team(
            name: "北京飞鹰",
            shortName: "飞鹰",
            city: "北京",
            players: surnames.enumerated().map { index, name in
                Player(name: name, number: index + 1, primaryPosition: positions[index])
            }
        )
    }
}
