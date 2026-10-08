import Foundation
import CryptoKit

/// Commands are recorded at the outer transaction boundary. Preview executes the
/// same GameStore operations in an isolated, non-persisting store.
enum HistoryCommand: Equatable, Codable {
    case swapOpeningSides
    case pitch(PitchAction)
    case illegalPitch(PitchAction?, Bool, PlayOutcome?, DefensivePlay?, [RunnerDecision]?, Bool?, Bool? = nil)
    case play(PlayOutcome, DefensivePlay?, [RunnerDecision], Bool?)
    case runner(RunnerEventKind, [RunnerDecision], Bool?)
    case hitByPitch, intentionalWalk, foulBunt
    case droppedThirdStrike([RunnerDecision])
    case pitcher(UUID)
    case batter(UUID)
    case pinchRunner(Base, UUID)
    case fielder(UUID, UUID)
    case position(UUID, FieldPosition)
    case lineup(HistoryLineup)
    case violation(ViolationKind, [RunnerDecision])
    case adjudication(ViolationKind, ViolationAdjudication)
    case situation(HistorySituation)
    case endHalf
    case decision(Bool)
    case extraInning(Bool, [Base])
    case enableTB([Base])
    case placeTB(UUID, Base?)
    case skipTB
    case finish(GameEndReason, Date)
    case reopen(Bool)
    case clock(String, Date)
    case note(String)
    case additionalErrors([HistoryErrorCredit])
    case review(HistoryEventReview)
    case doubleSwitch(UUID, UUID, FieldPosition, UUID, UUID, FieldPosition)
    case unavailable(String, Data, Data)

    var title: String {
        switch self {
        case .swapOpeningSides: "互换先攻／后攻"
        case .pitch(let p): p.rawValue
        case .illegalPitch: "Illegal／非法投球"
        case .play(let p, _, _, _): p.rawValue
        case .runner(let p, _, _): p.rawValue
        case .hitByPitch: "触身球"
        case .intentionalWalk: "故意保送"
        case .foulBunt: "触击界外三振"
        case .droppedThirdStrike: "三振未接住"
        case .pitcher: "补记换投"
        case .batter: "补记代打"
        case .pinchRunner: "补记代跑"
        case .fielder: "补记守备换人"
        case .position, .lineup: "补记守位／阵容"
        case .violation(let v, _), .adjudication(let v, _): v.rawValue
        case .situation: "局面修正 · 过程待确认"
        case .endHalf: "结束半局"
        case .decision: "半局／终场确认"
        case .extraInning: "延长局"
        case .enableTB, .placeTB, .skipTB: "TB 安排"
        case .finish: "结束比赛"
        case .reopen: "恢复比赛"
        case .clock: "比赛计时"
        case .note(let text): text
        case .additionalErrors: "补记附加失误"
        case .review: "复核待确认记录"
        case .doubleSwitch: "双重换人"
        case .unavailable(let text, _, _): text
        }
    }

    /// Kept outside undoable state: removing a recorded pitch cannot reopen the pregame choice.
    var recordsOpeningPitch: Bool {
        switch self {
        case .illegalPitch(_, let thrown, _, _, _, _, _): return thrown
        case .pitch, .play, .hitByPitch, .foulBunt, .droppedThirdStrike: return true
        case .runner(let kind, _, _): return [.wildPitch, .passedBall, .uncertainLooseBall].contains(kind)
        case .violation(let kind, _), .adjudication(let kind, _):
            return [.quickPitch, .illegalPitch, .batterOutOfBox, .batterInterference, .illegalBat, .catcherInterference].contains(kind)
        default: return false
        }
    }

    var checksBatter: Bool {
        switch self {
        case .illegalPitch, .pitch, .play, .hitByPitch, .intentionalWalk, .foulBunt, .droppedThirdStrike, .batter: true
        default: false
        }
    }
}

struct HistoryEventReview: Equatable, Codable {
    var id: UUID; var title: String; var category: ScoringEventCategory; var notation: String?
    var primaryPlayerID: UUID?; var secondaryPlayerID: UUID?; var ballStatus: BallStatus?
    var resolvedOutcome: PlayOutcome?; var note: String
}

struct HistoryErrorCredit: Equatable, Codable, Identifiable {
    var id = UUID()
    var playerID: UUID
    var count: Int = 1
    var note: String = "漏记守备失误"
}

struct HistoryLineup: Equatable, Codable {
    var isHome: Bool
    var players: [Player]
    var battingIDs: [UUID]
    var fieldingIDs: [UUID]
    var dhID: UUID?
    var pitcherID: UUID?
    var anchorID: UUID?
    var runners: [Base: Player]
    var automaticIDs: [UUID]?
    var changes: [String]

    init(_ draft: LiveLineupDraft) {
        isHome = draft.isHomeTeam; players = draft.players
        battingIDs = draft.battingOrderIDs; fieldingIDs = draft.fieldingIDs
        dhID = draft.designatedHitterID; pitcherID = draft.pitcherID
        anchorID = draft.batterAnchorID; runners = draft.runners
        automaticIDs = draft.automaticRunnerIDs; changes = draft.changes
    }
}

struct HistorySituation: Equatable, Codable {
    var inning: Int; var isTop: Bool; var balls: Int; var strikes: Int; var outs: Int
    var awayScore: Int; var homeScore: Int; var batterIndex: Int
    var pitcherID: UUID?; var runners: [Base: Player]
    init(_ game: GameState) {
        inning = game.inning; isTop = game.isTop; balls = game.balls; strikes = game.strikes; outs = game.outs
        awayScore = game.awayScore; homeScore = game.homeScore
        batterIndex = game.isTop ? game.awayBatterIndex : game.homeBatterIndex
        pitcherID = game.isTop ? game.activeHomePitcherID : game.activeAwayPitcherID
        runners = game.baseRunners
    }
}

struct HistoryContext: Equatable, Codable {
    var inning: Int; var isTop: Bool; var batterIndex: Int
    var homeOrder: [UUID]; var awayOrder: [UUID]
    var homeFielders: [UUID]; var awayFielders: [UUID]
    var positions: [UUID: FieldPosition]
    var runners: [Base: UUID]
    init(_ game: GameState) {
        inning = game.inning; isTop = game.isTop
        batterIndex = game.isTop ? game.awayBatterIndex : game.homeBatterIndex
        homeOrder = game.homeBattingOrderIDs; awayOrder = game.awayBattingOrderIDs
        homeFielders = game.homeFieldingPlayerIDs ?? homeOrder
        awayFielders = game.awayFieldingPlayerIDs ?? awayOrder
        positions = Dictionary(uniqueKeysWithValues: (game.homeTeam.players + game.awayTeam.players).map { ($0.id, $0.primaryPosition) })
        runners = game.baseRunners.mapValues(\.id)
    }
}

struct HistoryStatisticsRuling: Equatable, Codable {
    var runsBattedIn: Int
    var earnedRuns: [UUID: Int]
    var unearnedRunnerIDs: [UUID]
}

struct HistoryOperation: Equatable, Codable, Identifiable {
    var id = UUID()
    var command: HistoryCommand
    var context: HistoryContext
    var timestamp: Date
    var title: String
    var eventIDs: [UUID] = []
    var logIDs: [UUID] = []
    var nextAppearanceID: UUID?
    var inserted: Bool = false
    var edited: Bool = false
    var acceptsNewContext: Bool = false
    var errors: [HistoryErrorCredit] = []
    var statisticsRuling: HistoryStatisticsRuling?
    var correctedAt: Date?

    init(command: HistoryCommand, before: GameState, timestamp: Date = Date(), inserted: Bool = false) {
        self.command = command; context = HistoryContext(before); self.timestamp = timestamp
        title = command.title; self.inserted = inserted
    }
    var location: String { "第 \(context.inning) 局\(context.isTop ? "上" : "下")" }
}

struct HistoryJournal: Equatable, Codable {
    var version = 1
    var checkpoint: Data
    var operations: [HistoryOperation]
    var legacyPrefix: Bool = false
}

struct GameCorrectionRevision: Equatable, Codable, Identifiable {
    var id = UUID()
    var date: Date
    var reason: String
    var previousJournal: HistoryJournal
    var appliedOperationIDs: [UUID]
    var summary: [String]
}

struct HistoryCorrectionDraft: Equatable {
    var gameID: UUID
    var baseRevision: Int
    var journal: HistoryJournal
    var reason = "补记比赛记录"
    var createdAt = Date()
    var addedPlayers: [Player] = []
    var addedPlayersHome: [UUID: Bool] = [:]
    var compatibilityNote: String?
}

struct HistoryCorrectionPreview {
    var game: GameState
    var journal: HistoryJournal
    var statesBefore: [UUID: GameState]
    var conflictID: UUID?
    var conflict: String?
    var changes: [String]
    var canSave: Bool { conflict == nil }
}

enum HistoryCorrectionError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let s) = self { return s }; return nil }
}

extension GameState {
    func historyData() throws -> Data {
        var copy = self
        copy.historyJournal = nil; copy.correctionRevisions = nil
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(copy)
    }

    /// Clock, presentation IDs and logs are intentionally excluded. This is used
    /// to prove a legacy reconstruction, never to silently force a changed play.
    func historyScoreEquivalent(to other: GameState) -> Bool {
        inning == other.inning && isTop == other.isTop && balls == other.balls && strikes == other.strikes
        && outs == other.outs && homeRunsByInning == other.homeRunsByInning && awayRunsByInning == other.awayRunsByInning
        && homeBatterIndex == other.homeBatterIndex && awayBatterIndex == other.awayBatterIndex
        && baseRunners.mapValues(\.id) == other.baseRunners.mapValues(\.id)
        && homeBattingOrderIDs == other.homeBattingOrderIDs && awayBattingOrderIDs == other.awayBattingOrderIDs
        && activeHomePitcherID == other.activeHomePitcherID && activeAwayPitcherID == other.activeAwayPitcherID
        && batting == other.batting && pitching == other.pitching && fielding == other.fielding
        && homeHits == other.homeHits && awayHits == other.awayHits && homeErrors == other.homeErrors && awayErrors == other.awayErrors
        && isFinal == other.isFinal
    }
}

@MainActor
extension GameStore {
    func recordHistoryCommand(_ command: HistoryCommand) {
        if historyPendingCommand == nil { historyPendingCommand = command }
    }

    func captureHistoryOperation(from original: GameState) {
        guard !isHistoryReplay, !historySuppressCapture else { return }
        let originals = original.scoringEvents ?? []
        let updated = game.scoringEvents ?? []
        let oldCount = zip(originals, updated).prefix { $0.id == $1.id }.count
        let events = Array(updated.dropFirst(oldCount))
        guard !events.isEmpty || historyPendingCommand != nil else { return }
        do {
            var journal = try original.historyJournal ?? HistoryJournal(checkpoint: original.historyData(), operations: [], legacyPrefix: !(original.scoringEvents ?? []).isEmpty || !original.batting.isEmpty)
            let command = try historyPendingCommand ?? .unavailable(events.last?.title ?? "历史操作", original.historyData(), game.historyData())
            var operation = HistoryOperation(command: command, before: original, timestamp: events.first?.timestamp ?? Date())
            operation.title = events.last?.title ?? command.title
            operation.eventIDs = events.map(\.id); operation.logIDs = events.compactMap(\.logEntryID)
            operation.nextAppearanceID = game.currentPlateAppearanceID
            journal.operations.append(operation)
            game.historyJournal = journal
        } catch {
            // A failed capture must fail the containing transaction, not save an
            // apparently replayable game with a missing operation.
            actionErrorMessage = "无法保存纠错所需记录：\(error.localizedDescription)"
        }
    }

    func makeCorrectionDraft() throws -> HistoryCorrectionDraft {
        guard let stored = activeStoredGame else { throw HistoryCorrectionError.invalid("请先打开一场已保存比赛。") }
        if let journal = game.historyJournal {
            return HistoryCorrectionDraft(gameID: stored.id, baseRevision: stored.revision ?? 0, journal: journal)
        }
        do {
            let journal = try legacyHistoryJournal(for: stored)
            return HistoryCorrectionDraft(gameID: stored.id, baseRevision: stored.revision ?? 0, journal: journal)
        } catch {
            return HistoryCorrectionDraft(gameID: stored.id, baseRevision: stored.revision ?? 0,
                journal: HistoryJournal(checkpoint: try game.historyData(), operations: [], legacyPrefix: true), compatibilityNote: error.localizedDescription)
        }
    }

    func historyWorker(_ stored: StoredGame, state: GameState) -> GameStore {
        let worker = GameStore(persistenceURL: nil)
        worker.isHistoryReplay = true
        worker.activeGameID = nil
        worker.game = state
        worker.games = [stored]
        worker.activeGameID = stored.id
        worker.nextEventBeforeSituation = state.situationSnapshot
        return worker
    }

    func previewCorrection(_ draft: HistoryCorrectionDraft) -> HistoryCorrectionPreview {
        var output = HistoryCorrectionPreview(game: game, journal: draft.journal, statesBefore: [:], changes: [])
        guard let stored = activeStoredGame, stored.id == draft.gameID, (stored.revision ?? 0) == draft.baseRevision else {
            output.conflict = "比赛已变化，请重新打开纠错，避免覆盖新的记分。"; return output
        }
        do {
            var initial = try JSONDecoder().decode(GameState.self, from: draft.journal.checkpoint)
            for player in draft.addedPlayers {
                guard !(initial.homeTeam.players + initial.awayTeam.players).contains(where: { $0.id == player.id }) else { continue }
                if draft.addedPlayersHome[player.id] == true { initial.homeTeam.players.append(player) }
                else { initial.awayTeam.players.append(player) }
            }
            if let original = game.historyJournal {
                let newIDs = Set(draft.journal.operations.map(\.id))
                for removed in original.operations where !newIDs.contains(removed.id) { output.changes.append("删除误录：\(removed.location) · \(removed.title)") }
                let oldIDs = Set(original.operations.map(\.id))
                if original.operations.map(\.id).filter(newIDs.contains) != draft.journal.operations.map(\.id).filter(oldIDs.contains) { output.changes.append("已调整事件先后顺序；以下局面按新顺序逐条校验") }
            }
            let worker = historyWorker(stored, state: initial)
            var aliases: [UUID: UUID] = [:]
            for index in draft.journal.operations.indices {
                var op = draft.journal.operations[index]
                if (op.edited || op.inserted) && op.correctedAt == nil { op.correctedAt = draft.createdAt }
                output.statesBefore[op.id] = worker.game
                do {
                    if !op.acceptsNewContext && !op.inserted {
                        guard op.context.inning == worker.game.inning, op.context.isTop == worker.game.isTop else {
                            throw HistoryCorrectionError.invalid("原记录属于\(op.location)，补录后的局次不同。请核对并明确调整该条归属，或修改前面的补录。")
                        }
                        if op.command.checksBatter && op.context.batterIndex != (worker.game.isTop ? worker.game.awayBatterIndex : worker.game.homeBatterIndex) {
                            throw HistoryCorrectionError.invalid("棒次发生变化。请核对实际打者后重新关联，不能自动跳过一个打席。")
                        }
                    }
                    let before = worker.game
                    let originalEvents = before.scoringEvents ?? []
                    worker.historyReplayDate = op.timestamp
                    worker.actionErrorMessage = nil
                    try worker.executeHistoryCommand(op.command, context: op.context, aliases: &aliases)
                    if let error = worker.actionErrorMessage { throw HistoryCorrectionError.invalid(error) }
                    let eventOffset = zip(originalEvents, worker.game.scoringEvents ?? []).prefix { $0.id == $1.id }.count
                    let logOffset = zip(before.playLog, worker.game.playLog).prefix { $0.id == $1.id }.count
                    try worker.applyHistoryErrors(op.errors, context: op.context, aliases: aliases, fieldingState: before)
                    try worker.applyHistoryStatistics(op, before: before)
                    worker.preserveHistoryIdentities(op, eventOffset: eventOffset, logOffset: logOffset, previousAppearance: before.currentPlateAppearanceID)
                    if eventOffset < (worker.game.scoringEvents?.count ?? 0), let title = worker.game.scoringEvents?.last?.title { output.journal.operations[index].title = title }
                    output.journal.operations[index].correctedAt = op.correctedAt
                    output.journal.operations[index].context = HistoryContext(before)
                    output.journal.operations[index].eventIDs = Array((worker.game.scoringEvents ?? []).dropFirst(eventOffset)).map(\.id)
                    output.journal.operations[index].logIDs = Array(worker.game.playLog.dropFirst(logOffset)).map(\.id)
                    output.journal.operations[index].nextAppearanceID = worker.game.currentPlateAppearanceID
                    output.journal.operations[index].acceptsNewContext = false
                    let originalOperation = game.historyJournal?.operations.first { $0.id == op.id }
                    let directlyChanged = originalOperation.map { $0.command != op.command || $0.errors != op.errors || $0.statisticsRuling != op.statisticsRuling || op.acceptsNewContext } ?? (op.edited || op.inserted)
                    if directlyChanged { output.changes.append("\(op.location) · \(op.command.title)\(op.inserted ? "（补录）" : "（更正）")") }
                } catch {
                    output.game = worker.game
                    output.game.pitchingOutsAccountingVersion = game.pitchingOutsAccountingVersion
                    output.game.pitchingOutsReviewRequired = game.pitchingOutsReviewRequired
                    output.game.pitchingOutsBeforeRepair = game.pitchingOutsBeforeRepair
                    output.conflictID = op.id
                    output.conflict = "\(op.location) · \(op.title)：\(error.localizedDescription)"
                    return output
                }
            }
            output.game = worker.game
            output.game.pitchingOutsAccountingVersion = game.pitchingOutsAccountingVersion
            output.game.pitchingOutsReviewRequired = game.pitchingOutsReviewRequired
            output.game.pitchingOutsBeforeRepair = game.pitchingOutsBeforeRepair
            output.game.statisticsIncomplete = initial.statisticsIncomplete == true || (output.game.scoringEvents ?? []).contains { $0.needsReview && ($0.category == .correction || $0.reviewNote?.contains("责任待确认") == true) } ? true : nil
            output.game.clockRunningSince = game.clockRunningSince
            output.game.clockElapsedSeconds = game.clockElapsedSeconds
            output.game.clockDisplayMode = game.clockDisplayMode
            if game.isFinal {
                guard output.game.isFinal else { throw HistoryCorrectionError.invalid("原比赛已结束，但更正后的结束决定不成立。请修正结束记录，不能自动恢复比赛。") }
                output.game.endedAt = game.endedAt
            }
            if output.game.awayScore != game.awayScore || output.game.homeScore != game.homeScore {
                output.changes.append("比分 \(game.awayScore):\(game.homeScore) → \(output.game.awayScore):\(output.game.homeScore)")
            }
            output.changes += historyStatisticsChanges(from: game, to: output.game)
            if game.situationSnapshot != output.game.situationSnapshot {
                output.changes.append("最终局面：第 \(output.game.inning) 局\(output.game.isTop ? "上" : "下")，B \(output.game.balls) / S \(output.game.strikes) / O \(output.game.outs)，下一打者 \(output.game.currentBatter.compactName)")
            }
            for (oldTeam, newTeam) in [(game.homeTeam, output.game.homeTeam), (game.awayTeam, output.game.awayTeam)] {
                let changed = newTeam.players.filter { p in oldTeam.players.first(where: { $0.id == p.id })?.primaryPosition != p.primaryPosition }
                if !changed.isEmpty { output.changes.append("\(newTeam.shortName)当前守位：" + changed.map { "\($0.compactName) \($0.primaryPosition.fullName)" }.joined(separator: "；")) }
            }
            if output.changes.isEmpty { output.changes = ["局面与统计保持一致；可继续补录或修改。"] }
            output.journal.checkpoint = try initial.historyData()
        } catch { output.conflict = error.localizedDescription }
        return output
    }

    @discardableResult
    func saveCorrection(_ draft: HistoryCorrectionDraft) -> Bool {
        let cleanReason = draft.reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanReason.isEmpty else { actionErrorMessage = "请填写更正原因。"; return false }
        let preview = previewCorrection(draft)
        guard preview.canSave else { actionErrorMessage = preview.conflict; return false }
        guard let previous = try? makeCorrectionDraft() else { return false }
        let sameCheckpoint = (try? JSONDecoder().decode(GameState.self, from: previous.journal.checkpoint)) == (try? JSONDecoder().decode(GameState.self, from: preview.journal.checkpoint))
        guard previous.journal.operations != preview.journal.operations || !sameCheckpoint else { actionErrorMessage = "尚未修改记录。"; return false }
        beginAtomicAction()
        historySuppressCapture = true
        actionErrorMessage = nil
        pushUndo()
        let oldRevisions = game.correctionRevisions ?? []
        let revision = GameCorrectionRevision(date: Date(), reason: cleanReason, previousJournal: previous.journal,
            appliedOperationIDs: preview.journal.operations.map(\.id), summary: preview.changes)
        game = preview.game
        game.historyJournal = preview.journal
        game.correctionRevisions = oldRevisions + [revision]
        nextEventBeforeSituation = game.situationSnapshot
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    func draftRevertingCorrection(_ revision: GameCorrectionRevision) throws -> HistoryCorrectionDraft {
        guard game.correctionRevisions?.last?.id == revision.id else { throw HistoryCorrectionError.invalid("请按最近一次更正逐次撤回，避免覆盖后续更正。") }
        var draft = try makeCorrectionDraft()
        let originalIDs = Set(revision.appliedOperationIDs)
        let later = draft.journal.operations.filter { !originalIDs.contains($0.id) }
        draft.journal = revision.previousJournal
        draft.journal.operations.append(contentsOf: later)
        draft.reason = "撤回更正：" + revision.reason
        return draft
    }

    private func stableHistoryID(_ seed: UUID, _ component: String) -> UUID {
        let bytes = Array(SHA256.hash(data: Data((seed.uuidString + component).utf8)).prefix(16))
        return UUID(uuid: (bytes[0],bytes[1],bytes[2],bytes[3],bytes[4],bytes[5],bytes[6],bytes[7],bytes[8],bytes[9],bytes[10],bytes[11],bytes[12],bytes[13],bytes[14],bytes[15]))
    }

    private func preserveHistoryIdentities(_ op: HistoryOperation, eventOffset: Int, logOffset: Int, previousAppearance: UUID?) {
        var logs = game.playLog
        var events = game.scoringEvents ?? []
        var eventMap: [UUID: UUID] = [:], logMap: [UUID: UUID] = [:], appearanceMap: [UUID: UUID] = [:]
        for index in logOffset..<logs.count {
            let local = index - logOffset
            let newID = op.logIDs.indices.contains(local) ? op.logIDs[local] : stableHistoryID(op.id, "log\(local)")
            logMap[logs[index].id] = newID; logs[index].id = newID
            logs[index].timestamp = op.timestamp
        }
        for index in eventOffset..<events.count {
            let local = index - eventOffset
            let newID = op.eventIDs.indices.contains(local) ? op.eventIDs[local] : stableHistoryID(op.id, "event\(local)")
            eventMap[events[index].id] = newID; events[index].id = newID
            events[index].timestamp = op.timestamp
            if logs.indices.contains(logOffset + local) { events[index].logEntryID = logs[logOffset + local].id }
            if op.edited || op.inserted || !op.errors.isEmpty { events[index].reviewedAt = op.correctedAt ?? op.timestamp; events[index].reviewNote = events[index].reviewNote ?? "历史纠错" }
        }
        game.playLog = logs; game.scoringEvents = events
        if let current = game.currentPlateAppearanceID, current != previousAppearance {
            let preferred = op.nextAppearanceID ?? stableHistoryID(op.id, "nextAppearance")
            if !(game.plateAppearances ?? []).contains(where: { $0.id == preferred && $0.id != current }) {
                if let index = game.plateAppearances?.firstIndex(where: { $0.id == current }) { game.plateAppearances?[index].id = preferred }
                game.currentPlateAppearanceID = preferred
                appearanceMap[current] = preferred
                for index in game.scoringEvents?.indices ?? 0..<0 where game.scoringEvents?[index].plateAppearanceID == current { game.scoringEvents?[index].plateAppearanceID = preferred }
                for index in game.plateAppearances?.indices ?? 0..<0 where game.plateAppearances?[index].previousSegmentID == current { game.plateAppearances?[index].previousSegmentID = preferred }
            }
        }
        remapHistoryUndoIdentities(events: eventMap, logs: logMap, appearances: appearanceMap)
    }

    private func historyStatisticsChanges(from old: GameState, to new: GameState) -> [String] {
        var result: [String] = []
        for player in new.homeTeam.players + new.awayTeam.players {
            let a = old.batting[player.id, default: BattingLine()], b = new.batting[player.id, default: BattingLine()]
            let p = old.pitching[player.id, default: PitchingLine()], q = new.pitching[player.id, default: PitchingLine()]
            let f = old.fielding[player.id, default: FieldingLine()], g = new.fielding[player.id, default: FieldingLine()]
            var parts: [String] = []
            if a != b { parts.append("打击 PA \(a.plateAppearances)→\(b.plateAppearances)，H \(a.hits)→\(b.hits)，R \(a.runs)→\(b.runs)，RBI \(a.runsBattedIn)→\(b.runsBattedIn)") }
            if p != q { parts.append("投球 \(p.pitches)→\(q.pitches)，失分 \(p.runs)→\(q.runs)，自责分 \(p.earnedRuns)→\(q.earnedRuns)") }
            if f != g { parts.append("守备 PO \(f.putouts)→\(g.putouts)，A \(f.assists)→\(g.assists)，E \(f.errors)→\(g.errors)") }
            if !parts.isEmpty { result.append("\(player.compactName)：" + parts.joined(separator: "；")) }
        }
        return result
    }
}

@MainActor
extension GameStore {
    func executeHistoryCommand(_ command: HistoryCommand, context: HistoryContext, aliases: inout [UUID: UUID]) throws {
        func resolve(_ id: UUID) -> UUID { aliases[id] ?? id }
        func player(_ id: UUID) throws -> Player {
            guard let value = (game.homeTeam.players + game.awayTeam.players).first(where: { $0.id == id }) else {
                throw HistoryCorrectionError.invalid("找不到该球员，请先补全本场球员资料。")
            }
            return value
        }
        func originalParticipant(_ id: UUID) -> UUID {
            let mapped = resolve(id)
            if game.homeBattingOrderIDs.contains(mapped) || game.awayBattingOrderIDs.contains(mapped)
                || (game.homeFieldingPlayerIDs ?? []).contains(mapped) || (game.awayFieldingPlayerIDs ?? []).contains(mapped) { return mapped }
            if let index = context.homeOrder.firstIndex(of: id), game.homeBattingOrderIDs.indices.contains(index) { return game.homeBattingOrderIDs[index] }
            if let index = context.awayOrder.firstIndex(of: id), game.awayBattingOrderIDs.indices.contains(index) { return game.awayBattingOrderIDs[index] }
            return mapped
        }
        func decisions(_ inputs: [RunnerDecision], at state: GameState? = nil) throws -> [RunnerDecision] {
            let actual = state ?? game
            return try inputs.map { input in
                switch input.origin {
                case .batter:
                    return RunnerDecision(player: actual.currentBatter, origin: .batter, destination: input.destination)
                case .base(let base):
                    guard let runner = actual.baseRunners[base], runner.id == resolve(input.player.id) else {
                        throw HistoryCorrectionError.invalid("\(input.player.compactName) 原在\(base.title)，与补录后的跑者不一致，请修改该条跑垒决定。")
                    }
                    return RunnerDecision(player: runner, origin: .base(base), destination: input.destination)
                }
            }
        }
        func registerReplacement(_ previous: UUID, _ incoming: UUID) {
            for key in Array(aliases.keys) where aliases[key] == previous { aliases[key] = incoming }
            aliases[previous] = incoming
            // Explicitly returning a player to the field must not create an A/B cycle.
            aliases[incoming] = incoming
        }
        let before = game
        var result = true
        switch command {
        case .swapOpeningSides: result = swapOpeningSides()
        case .pitch(let action):
            guard canRecordAction else { throw HistoryCorrectionError.invalid("当前需要先确认换边或结束决定。") }
            recordPitch(action)
        case .illegalPitch(let action, let thrown, let outcome, let defense, let moves, let timing, let penalty):
            result = recordIllegalPitch(action, wasThrown: thrown, outcome: outcome, defensivePlay: defense,
                                       decisions: try moves.map { try decisions($0) }, timingRunCounts: timing, penaltyApplied: penalty)
        case .play(let outcome, let defense, let moves, let timing):
            result = applyPlay(outcome, defensivePlay: defense, decisions: try decisions(moves), timingRunCounts: timing)
        case .runner(let kind, let moves, let timing):
            result = recordRunnerEvent(kind, decisions: try decisions(moves), timingRunCounts: timing)
        case .hitByPitch: recordHitByPitch()
        case .intentionalWalk: recordIntentionalWalk()
        case .foulBunt: recordFoulBuntStrikeout()
        case .droppedThirdStrike(let moves): result = recordDroppedThirdStrike(decisions: try decisions(moves))
        case .pitcher(let id):
            let incoming = try player(id), old = game.currentPitcher.id
            guard availablePitchers.contains(where: { $0.id == id }), old != id else { throw HistoryCorrectionError.invalid("换上的投手在此时点不可用或已经登板。") }
            changePitcher(to: incoming)
            if !game.fieldingPlayerIDs.contains(old) { registerReplacement(old, id) }
        case .batter(let id):
            guard battingBenchPlayers.contains(where: { $0.id == id }) else { throw HistoryCorrectionError.invalid("代打球员在此时点已在阵容中，请核对。") }
            let old = game.currentBatter.id
            replaceCurrentBatter(with: try player(id)); registerReplacement(old, id)
        case .pinchRunner(let base, let id):
            guard let outgoing = game.baseRunners[base], battingBenchPlayers.contains(where: { $0.id == id }) else {
                throw HistoryCorrectionError.invalid("该垒没有可替换跑者，或代跑球员不可用。")
            }
            replaceRunner(on: base, with: try player(id)); registerReplacement(outgoing.id, id)
        case .fielder(let outgoingID, let incomingID):
            let outgoing = originalParticipant(outgoingID)
            result = replaceFielder(try player(outgoing), with: try player(incomingID))
            if result { registerReplacement(outgoing, incomingID) }
        case .position(let id, let position):
            let actual = originalParticipant(id)
            guard activeFielders.contains(where: { $0.id == actual }) else { throw HistoryCorrectionError.invalid("此时点该球员未参与防守。") }
            changeFieldingPosition(for: try player(actual), to: position)
        case .lineup(let value):
            var draft = lineupDraft(forHomeTeam: value.isHome)
            let sourceOrder = value.isHome ? context.homeOrder : context.awayOrder
            let actualOrder = draft.battingOrderIDs
            func map(_ id: UUID) -> UUID {
                if let slot = sourceOrder.firstIndex(of: id), actualOrder.indices.contains(slot) { return actualOrder[slot] }
                return id // An explicit incoming/re-entering player must retain their own identity.
            }
            for old in value.players {
                let actualID = map(old.id)
                if let index = draft.players.firstIndex(where: { $0.id == actualID }) {
                    if old.primaryPosition != context.positions[old.id] { draft.players[index].primaryPosition = old.primaryPosition }
                    if actualID == old.id {
                        draft.players[index].chineseName = old.chineseName; draft.players[index].englishName = old.englishName
                        draft.players[index].numbers = old.numbers; draft.players[index].jerseyNumbers = old.jerseyNumbers
                    }
                }
            }
            draft.battingOrderIDs = value.battingIDs.map(map)
            draft.fieldingIDs = value.fieldingIDs.map(map)
            draft.pitcherID = value.pitcherID.map(map); draft.designatedHitterID = value.dhID.map(map)
            draft.batterAnchorID = value.anchorID.map(map)
            draft.runners = try value.runners.mapValues { try player(resolve($0.id)) }
            draft.automaticRunnerIDs = value.automaticIDs?.map(resolve)
            draft.changes = value.changes
            result = saveLineup(draft)
        case .violation(let violation, let moves):
            result = recordViolation(violation, decisions: try decisions(moves))
        case .adjudication(let violation, var ruling):
            ruling.runnerDecisions = try decisions(ruling.runnerDecisions, at: ruling.previousPlayDisposition == .cancel ? historyPreviousActionState : nil)
            result = recordViolation(violation, adjudication: ruling)
        case .situation(let state):
            let runners = try state.runners.mapValues { try player(resolve($0.id)) }
            result = correctGameState(inning: state.inning, isTop: state.isTop, balls: state.balls, strikes: state.strikes,
                outs: state.outs, awayScore: state.awayScore, homeScore: state.homeScore,
                batterIndex: state.batterIndex, pitcherID: state.pitcherID, baseRunners: runners)
            if result {
                game.statisticsIncomplete = true
                if let last = game.scoringEvents?.indices.last { game.scoringEvents?[last].needsReview = true; game.scoringEvents?[last].title = "局面已补正，缺失过程与统计待确认" }
                if let last = game.playLog.indices.last { game.playLog[last].isIncomplete = true; game.playLog[last].text = "局面已补正，缺失过程与统计待确认" }
            }
        case .endHalf: endCurrentHalf()
        case .decision(let finish):
            guard game.pendingDecision != nil else { throw HistoryCorrectionError.invalid("原换边／结束确认已不成立，请删除或调整这条确认。") }
            resolveGameDecision(finish: finish)
        case .extraInning(let tb, let bases): confirmExtraInning(useTiebreak: tb, runnerBases: bases)
        case .enableTB(let bases): enableTiebreakFromCurrentInning(runnerBases: bases)
        case .placeTB(let id, let base): placeTiebreakRunner(try player(resolve(id)), on: base)
        case .skipTB: skipTiebreakRunnerForCurrentHalf()
        case .finish(let reason, let date):
            if reason == .walkOff && !(game.isFinal && game.endReason == .walkOff) {
                guard !game.isTop, game.inning >= game.scheduledInnings, game.homeScore > game.awayScore else { throw HistoryCorrectionError.invalid("原再见分结束依据已不成立，请核对结束决定。") }
            }
            if reason == .regulation && game.pendingDecision != .regulationHalf && game.inning < game.scheduledInnings { throw HistoryCorrectionError.invalid("原规定局数结束依据已不成立，请核对结束决定。") }
            finishGame(reason: reason, at: date)
        case .reopen(let confirmed): result = reopenGame(confirmedLegacySituation: confirmed)
        case .clock(let kind, let date):
            if kind == "start" { startGameClock(at: date) }
            else if kind == "pause" { pauseGameClock(at: date) }
            else { toggleGameClockDisplayMode() }
        case .note(let text): recordSubstitution(text)
        case .additionalErrors(let credits): try applyHistoryErrors(credits, context: context, aliases: aliases, fieldingState: before)
        case .review(let r):
            result = reviewPendingEvent(id: r.id, title: r.title, category: r.category, notation: r.notation,
                primaryPlayerID: r.primaryPlayerID.map(resolve), secondaryPlayerID: r.secondaryPlayerID.map(resolve), ballStatus: r.ballStatus, resolvedOutcome: r.resolvedOutcome, note: r.note)
        case .doubleSwitch(let out1, let in1, let pos1, let out2, let in2, let pos2):
            let a = originalParticipant(out1), b = originalParticipant(out2)
            result = performDoubleSwitch(firstOut: try player(a), firstIn: try player(in1), firstPosition: pos1, secondOut: try player(b), secondIn: try player(in2), secondPosition: pos2)
            if result { registerReplacement(a, in1); registerReplacement(b, in2) }
        case .unavailable(_, let beforeData, let afterData):
            let expected = try JSONDecoder().decode(GameState.self, from: beforeData)
            let after = try JSONDecoder().decode(GameState.self, from: afterData)
            guard game.historyScoreEquivalent(to: expected), game.homeTeam == expected.homeTeam, game.awayTeam == expected.awayTeam else {
                throw HistoryCorrectionError.invalid("此旧操作缺少可重算输入，前面的更正影响了它。请用明确的补录替换此条；不能直接套用旧统计。")
            }
            guard expected.scoringEvents?.count == game.scoringEvents?.count, expected.playLog.count == game.playLog.count else {
                throw HistoryCorrectionError.invalid("此旧操作不能跨越新增记录，请用明确的操作替换它。")
            }
            var restored = after
            restored.scoringEvents = (game.scoringEvents ?? []) + Array((after.scoringEvents ?? []).dropFirst(expected.scoringEvents?.count ?? 0))
            restored.playLog = game.playLog + Array(after.playLog.dropFirst(expected.playLog.count))
            game = restored
        }
        guard result else { throw HistoryCorrectionError.invalid(actionErrorMessage ?? "该操作与当时局面冲突，请核对。") }
        if case .clock = command { return }
        if case .unavailable = command { return }
        guard game != before else { throw HistoryCorrectionError.invalid("操作未生效，请核对球员、守位及比赛阶段。") }
    }

    func applyHistoryStatistics(_ op: HistoryOperation, before: GameState) throws {
        let offset = zip(before.scoringEvents ?? [], game.scoringEvents ?? []).prefix { $0.id == $1.id }.count
        let isErrorPlay: Bool
        if case .play(let outcome, _, _, _) = op.command { isErrorPlay = outcome == .error }
        else if case .additionalErrors = op.command { isErrorPlay = true }
        else { isErrorPlay = false }
        if let ruling = op.statisticsRuling {
            if case .play(let outcome, _, _, _) = op.command, [.pending, .pendingOut, .other].contains(outcome) {
                throw HistoryCorrectionError.invalid("请先确认实际击球结果，再确认打点与自责分责任。")
            }
            let runs = max(0, game.homeScore + game.awayScore - before.homeScore - before.awayScore)
            guard (0...runs).contains(ruling.runsBattedIn) else { throw HistoryCorrectionError.invalid("打点不能超过本事件有效得分。") }
            let batter = before.currentBatter.id
            let oldRBI = before.batting[batter, default: BattingLine()].runsBattedIn
            game.batting[batter, default: BattingLine()].runsBattedIn = oldRBI + ruling.runsBattedIn
            for (id, earned) in ruling.earnedRuns {
                let allowed = game.pitching[id, default: PitchingLine()].runs - before.pitching[id, default: PitchingLine()].runs
                guard before.fieldingTeam.players.contains(where: { $0.id == id }), (0...max(0, allowed)).contains(earned) else {
                    throw HistoryCorrectionError.invalid("自责分不能超过该投手在本事件的失分。")
                }
                game.pitching[id, default: PitchingLine()].earnedRuns = before.pitching[id, default: PitchingLine()].earnedRuns + earned
            }
            let allowedIDs = Set(before.battingTeam.players.map(\.id))
            guard Set(ruling.unearnedRunnerIDs).isSubset(of: allowedIDs) else { throw HistoryCorrectionError.invalid("非自责跑者不属于当时进攻方。") }
            game.unearnedRunnerIDs = Array(Set(game.unearnedRunnerIDs ?? []).union(ruling.unearnedRunnerIDs)).sorted { $0.uuidString < $1.uuidString }
            for i in offset..<(game.scoringEvents?.count ?? 0) {
                game.scoringEvents?[i].needsReview = false
                game.scoringEvents?[i].reviewNote = "历史纠错；打点与投手责任已人工复核"
                if let logID = game.scoringEvents?[i].logEntryID, let j = game.playLog.firstIndex(where: { $0.id == logID }) { game.playLog[j].isIncomplete = false }
            }
        } else if (isErrorPlay || !op.errors.isEmpty), op.edited || op.inserted {
            for i in offset..<(game.scoringEvents?.count ?? 0) {
                game.scoringEvents?[i].needsReview = true
                game.scoringEvents?[i].reviewNote = "历史纠错；打点与自责分责任待确认"
                if let id = game.scoringEvents?[i].logEntryID, let j = game.playLog.firstIndex(where: { $0.id == id }) { game.playLog[j].isIncomplete = true }
            }
        }
    }

    func applyHistoryErrors(_ credits: [HistoryErrorCredit], context: HistoryContext, aliases: [UUID: UUID], fieldingState: GameState? = nil) throws {
        guard !credits.isEmpty else { return }
        let state = fieldingState ?? game
        let fielders = Set(state.fieldingPlayerIDs)
        for credit in credits {
            guard (1...9).contains(credit.count), fielders.contains(credit.playerID),
                  let player = state.fieldingTeam.players.first(where: { $0.id == credit.playerID }) else {
                throw HistoryCorrectionError.invalid("失误责任人必须是该事件时实际在场的守备球员，次数为 1–9。")
            }
            game.fielding[player.id, default: FieldingLine()].errors += credit.count
            if state.isTop { game.homeErrors += credit.count } else { game.awayErrors += credit.count }
            let text = "补记失误：\(player.compactName)（\(player.primaryPosition.fullName)）\(credit.count) 次；\(credit.note)"
            let log = PlayLogEntry(inning: state.inning, isTop: state.isTop, text: text, timestamp: historyReplayDate ?? Date())
            game.playLog.append(log)
            var event = ScoringEventRecord(logEntryID: log.id, inning: state.inning, isTop: state.isTop,
                timestamp: log.timestamp, category: .correction, title: text, notation: "E\(player.primaryPosition.rawValue)",
                primaryPlayerID: player.id, beforeSituation: state.situationSnapshot, afterSituation: game.situationSnapshot)
            event.plateAppearanceID = state.currentPlateAppearanceID
            game.scoringEvents = (game.scoringEvents ?? []) + [event]
        }
    }

    /// Legacy imports are enabled only after a complete no-edit replay agrees
    /// with the saved score and every raw statistic. No text-only reconstruction.
    func legacyHistoryJournal(for stored: StoredGame) throws -> HistoryJournal {
        let original = stored.state
        guard let events = original.scoringEvents, !events.isEmpty else {
            return HistoryJournal(checkpoint: try original.historyData(), operations: [], legacyPrefix: !original.playLog.isEmpty)
        }
        var initial = GameState(homeTeam: original.homeTeam, awayTeam: original.awayTeam,
            scheduledInnings: original.scheduledInnings)
        let homeAssignments = stored.isObservation ? stored.secondaryLineup ?? [] : (stored.isHome ? stored.lineup : stored.secondaryLineup ?? [])
        let awayAssignments = stored.isObservation ? stored.lineup : (!stored.isHome ? stored.lineup : stored.secondaryLineup ?? [])
        func apply(_ assignments: [LineupAssignment], home: Bool) {
            let sorted = assignments.sorted { $0.battingOrder < $1.battingOrder }
            let fallback = home ? original.homeBattingOrderIDs : original.awayBattingOrderIDs
            let ids = sorted.isEmpty ? fallback : sorted.map(\.playerID)
            if home { initial.homeBattingOrderIDs = ids; initial.homeFieldingPlayerIDs = ids }
            else { initial.awayBattingOrderIDs = ids; initial.awayFieldingPlayerIDs = ids }
            for item in sorted {
                if home, let i = initial.homeTeam.players.firstIndex(where: { $0.id == item.playerID }) { if let position = item.position { initial.homeTeam.players[i].primaryPosition = position } }
                if !home, let i = initial.awayTeam.players.firstIndex(where: { $0.id == item.playerID }) { if let position = item.position { initial.awayTeam.players[i].primaryPosition = position } }
            }
        }
        apply(homeAssignments, home: true); apply(awayAssignments, home: false)
        initial.activeHomePitcherID = initial.homeTeam.players.first { initial.homeFieldingPlayerIDs!.contains($0.id) && $0.primaryPosition == .pitcher }?.id
        initial.activeAwayPitcherID = initial.awayTeam.players.first { initial.awayFieldingPlayerIDs!.contains($0.id) && $0.primaryPosition == .pitcher }?.id
        initial.scoringEvents = []; initial.playLog = []
        let worker = historyWorker(stored, state: initial)
        worker.ensureCurrentAppearance()
        if let firstID = events.compactMap(\.plateAppearanceID).first,
           let i = worker.game.plateAppearances?.indices.first {
            worker.game.plateAppearances?[i].id = firstID; worker.game.currentPlateAppearanceID = firstID
        }
        var journal = HistoryJournal(checkpoint: try worker.game.historyData(), operations: [])
        var aliases: [UUID: UUID] = [:]
        var index = 0
        while index < events.count {
            let event = events[index]
            // Automatic results follow the real pitch and must not be replayed twice.
            if ["BB", "K", "ꓘ"].contains(event.notation ?? ""), index > 0, events[index - 1].category == .pitch {
                index += 1; continue
            }
            let command: HistoryCommand
            if event.category == .pitch, let pitch = ["B": PitchAction.ball, "C": .calledStrike, "S": .swingingStrike, "F": .foul][event.notation ?? ""] {
                command = .pitch(pitch)
            } else if event.category == .battedBall, let outcome = event.resolvedOutcome {
                let moves = try legacyRunnerDecisions(event, game: worker.game)
                let notation = event.notation ?? ""
                let defense = DefensivePlay.quickPlays.first { $0.notation == notation }
                    ?? (notation.hasPrefix("E") ? Int(notation.dropFirst()).flatMap(FieldPosition.init(rawValue:)).map(DefensivePlay.error(at:)) : nil)
                command = .play(outcome, defense, moves, event.effectiveScorerIDs.map { !$0.isEmpty })
            } else if let kind = RunnerEventKind.allCases.first(where: { $0.notation == event.notation }), [.runner, .out].contains(event.category) {
                command = .runner(kind, try legacyRunnerDecisions(event, game: worker.game), event.effectiveScorerIDs.map { !$0.isEmpty })
            } else if event.notation == "HBP" { command = .hitByPitch }
            else if event.notation == "IBB" { command = .intentionalWalk }
            else if event.notation == "END", original.isFinal { command = .finish(original.endReason ?? .scorerDecision, original.endedAt ?? event.timestamp) }
            else if event.category == .clock { command = .clock(event.notation == "PAUSE" ? "pause" : "start", event.timestamp) }
            else if event.category == .game, index == 0 { index += 1; continue }
            else {
                throw HistoryCorrectionError.invalid("旧记录“\(event.title)”缺少完整的操作／阵容资料，不能可靠重算。可继续现场记分，新操作会保存完整纠错资料；原比赛与统计保持不变。")
            }
            var operation = HistoryOperation(command: command, before: worker.game, timestamp: event.timestamp)
            let offset = worker.game.scoringEvents?.count ?? 0, logOffset = worker.game.playLog.count
            worker.historyReplayDate = event.timestamp
            try worker.executeHistoryCommand(command, context: operation.context, aliases: &aliases)
            if let issue = worker.actionErrorMessage { throw HistoryCorrectionError.invalid(issue) }
            let generatedCount = (worker.game.scoringEvents?.count ?? 0) - offset
            // Match only original semantic events, excluding original clock rows.
            let originals = Array(events[index...].prefix(generatedCount))
            operation.eventIDs = originals.map(\.id); operation.logIDs = originals.compactMap(\.logEntryID)
            operation.title = event.title
            worker.preserveHistoryIdentities(operation, eventOffset: offset, logOffset: logOffset, previousAppearance: nil)
            operation.nextAppearanceID = worker.game.currentPlateAppearanceID
            journal.operations.append(operation)
            index += max(1, generatedCount)
        }
        guard worker.game.historyScoreEquivalent(to: original) else {
            throw HistoryCorrectionError.invalid("旧比赛的完整重算与原统计不一致，暂不能自动更正这段历史。原始数据已保留，不会猜测或清零。")
        }
        return journal
    }

    private func legacyRunnerDecisions(_ event: ScoringEventRecord, game: GameState) throws -> [RunnerDecision] {
        try event.runnerMovements.map { movement in
            guard let player = game.battingTeam.players.first(where: { $0.id == movement.playerID }) else {
                throw HistoryCorrectionError.invalid("旧记录缺少跑者身份。")
            }
            let origin: RunnerOrigin
            if movement.origin == "打者" { origin = .batter }
            else if let base = Base.allCases.first(where: { "\($0.title)跑者" == movement.origin }) { origin = .base(base) }
            else { throw HistoryCorrectionError.invalid("旧记录的起始垒位无法确认。") }
            let destination: RunnerDestination
            if movement.destination == "停留" { destination = .hold }
            else if movement.destination == "得分" || movement.destination == "回本垒（不计分）" { destination = .score }
            else if movement.destination == "出局" { destination = .out }
            else if let base = Base.allCases.first(where: { "到\($0.title)" == movement.destination }) { destination = .base(base) }
            else { throw HistoryCorrectionError.invalid("旧记录的跑者去向无法确认。") }
            return RunnerDecision(player: player, origin: origin, destination: destination)
        }
    }
}

@MainActor
extension GameStore {
    /// Reconcile only complete command histories whose checkpoint predates all
    /// play. Partial/legacy notes are never used to guess additional outs.
    func reconcileLegacyPitcherOuts() throws {
        guard games.contains(where: { $0.status != .scheduled && ($0.state.pitchingOutsAccountingVersion ?? 0) == 0 }) else { return }
        try withSuspendedCurrentPersistence {
        let originalGames = games, originalGame = game, originalActiveID = activeGameID
        var changed = false
        defer {
            activeGameID = originalActiveID
            game = originalActiveID.flatMap { id in games.first { $0.id == id }?.state } ?? originalGame
        }
        for index in games.indices where games[index].status != .scheduled && (games[index].state.pitchingOutsAccountingVersion ?? 0) == 0 {
            let original = games[index].state
            if games[index].rules.gameMode == .coachPitch {
                games[index].state.pitchingOutsAccountingVersion = 2; changed = true; continue
            }
            @MainActor func markForReview() {
                games[index].state.pitchingOutsAccountingVersion = 1
                games[index].state.pitchingOutsReviewRequired = true
                changed = true
            }
            guard let journal = original.historyJournal, !journal.legacyPrefix,
                  !journal.operations.contains(where: { if case .unavailable = $0.command { return true }; return false }),
                  let checkpoint = try? JSONDecoder().decode(GameState.self, from: journal.checkpoint),
                  checkpoint.inning == 1, checkpoint.isTop, checkpoint.outs == 0,
                  checkpoint.pitching.values.allSatisfy({ $0.outsRecorded == 0 && $0.pitches == 0 }),
                  checkpoint.batting.values.allSatisfy({ $0.plateAppearances == 0 }) else { markForReview(); continue }
            activeGameID = games[index].id; game = original
            guard let draft = try? makeCorrectionDraft() else { markForReview(); continue }
            let preview = previewCorrection(draft)
            let computed = preview.game
            guard preview.canSave, computed.inning == original.inning, computed.isTop == original.isTop,
                  computed.outs == original.outs, computed.balls == original.balls, computed.strikes == original.strikes,
                  computed.homeRunsByInning == original.homeRunsByInning,
                  computed.awayRunsByInning == original.awayRunsByInning else { markForReview(); continue }
            let ids = Set(original.pitching.keys).union(computed.pitching.keys)
            guard ids.allSatisfy({ id in
                var old = original.pitching[id, default: PitchingLine()], new = computed.pitching[id, default: PitchingLine()]
                old.outsRecorded = 0; new.outsRecorded = 0
                return old == new
            }) else { markForReview(); continue }
            let oldOuts = original.pitching.mapValues(\.outsRecorded)
            for id in ids {
                games[index].state.pitching[id, default: PitchingLine()].outsRecorded = computed.pitching[id]?.outsRecorded ?? 0
            }
            games[index].state.pitchingOutsAccountingVersion = 2
            games[index].state.pitchingOutsReviewRequired = false
            if games[index].state.pitching.mapValues(\.outsRecorded) != oldOuts {
                games[index].state.pitchingOutsBeforeRepair = oldOuts
                games[index].revision = (games[index].revision ?? 0) + 1
                if games[index].publicationState == "publishing" { games[index].pendingPublicationRevisions = [games[index].revision!] }
            }
            changed = true
        }
        if changed {
            do { try persistenceStore.replaceAll(with: makeRosterSnapshot()) }
            catch { games = originalGames; throw error }
        }
        }
    }
}
