import Foundation

struct ScheduleRole: Codable, Equatable {
    struct Transfer: Codable, Equatable { let id: UUID; let targetName: String }
    let transfer: Transfer?
    let canPrepare: Bool?
    let openingReady: Bool?
    let openingConfirmed: Bool?
    let hasCheckpoint: Bool?
    let epoch: Int
    let role: String
    let scorer: UUID?
    let lineup: UUID?
    let scorerName: String?
    let lineupName: String?
    let active: Bool
    let closed: Bool
    let holdsDevice: Bool
    var description: String {
        guard let scorerName else { return "待指派 · 创建比赛的管理员负责" }
        if let lineupName { return "局面：" + scorerName + "　阵容：" + lineupName }
        return "整场记录：" + scorerName
    }
}

// This is an editing view of the field, not the official game or a history archive.
struct ScheduleField: Codable, Equatable {
    let isHome: Bool
    let rules: GameRules
    let pregame: Bool
    let lastPlay: UUID?
    var state: GameState

    init(_ stored: StoredGame, pregame: Bool) {
        isHome = stored.isHome; rules = stored.rules; self.pregame = pregame
        let g = stored.state
        lastPlay = g.playLog.last?.id
        var s = GameState(homeTeam: g.homeTeam, awayTeam: g.awayTeam, scheduledInnings: g.scheduledInnings,
                          homeBattingOrderIDs: g.homeBattingOrderIDs, awayBattingOrderIDs: g.awayBattingOrderIDs, rules: stored.rules)
        s.homeFieldingPlayerIDs = g.homeFieldingPlayerIDs; s.awayFieldingPlayerIDs = g.awayFieldingPlayerIDs
        s.homeDesignatedHitterID = g.homeDesignatedHitterID; s.awayDesignatedHitterID = g.awayDesignatedHitterID
        s.activeHomePitcherID = g.activeHomePitcherID; s.activeAwayPitcherID = g.activeAwayPitcherID
        s.homeBatterIndex = g.homeBatterIndex; s.awayBatterIndex = g.awayBatterIndex
        s.inning = g.inning; s.isTop = g.isTop; s.outs = g.outs; s.balls = g.balls; s.strikes = g.strikes
        s.baseRunners = g.baseRunners; s.automaticRunnerIDs = g.automaticRunnerIDs
        s.homeRunsByInning = g.homeRunsByInning; s.awayRunsByInning = g.awayRunsByInning
        s.isFinal = g.isFinal
        state = s
    }
}

struct ScheduleProposal: Codable, Equatable, Identifiable {
    var id = UUID()
    let version: Int
    let baseline: HistoryLineup
    let lastPlay: UUID?
    let lineup: HistoryLineup

    func draft(on game: GameState, pregame: Bool) -> LiveLineupDraft? {
        var draft = LiveLineupDraft(game: game, isHomeTeam: lineup.isHome,
                                    allowsTwoWayPlayer: game.rulesSnapshot?.twoWayPlayerEnabled == true, isPregame: pregame)
        guard baseline.isHome == lineup.isHome, baseline.players == draft.players else { return nil }
        let ids = lineup.players.map(\.id)
        let other = lineup.isHome ? game.awayTeam : game.homeTeam
        guard ids.count <= 200, Set(ids).count == ids.count,
              Set(draft.players.map(\.id)).isSubset(of: Set(ids)),
              Set(ids).isDisjoint(with: other.players.map(\.id)) else { return nil }
        if changesParticipation {
            guard baseline.battingIDs == draft.battingOrderIDs, baseline.fieldingIDs == draft.fieldingIDs,
                  baseline.anchorID == draft.batterAnchorID, baseline.pitcherID == draft.pitcherID,
                  baseline.dhID == draft.designatedHitterID, baseline.runners == draft.runners,
                  baseline.automaticIDs == draft.automaticRunnerIDs else { return nil }
            draft.battingOrderIDs = lineup.battingIDs; draft.fieldingIDs = lineup.fieldingIDs
            draft.batterAnchorID = lineup.anchorID; draft.pitcherID = lineup.pitcherID
            draft.designatedHitterID = lineup.dhID; draft.runners = lineup.runners
            draft.automaticRunnerIDs = lineup.automaticIDs
        }
        if !changesParticipation {
            for (base,runner) in draft.runners {
                guard let updated = lineup.players.first(where:{ $0.id == runner.id }) else { continue }
                draft.runners[base] = updated
            }
        }
        draft.players = lineup.players
        draft.changes = lineup.changes
        return draft.validationMessage == nil ? draft : nil
    }

    func carryRosterEdits(into snapshot: GameState) -> GameState {
        var edit = LiveLineupDraft(game:snapshot,isHomeTeam:lineup.isHome,allowsTwoWayPlayer:snapshot.rulesSnapshot?.twoWayPlayerEnabled == true)
        for p in lineup.players {
            if let old = baseline.players.first(where: { $0.id == p.id }) {
                if old.chineseName != p.chineseName || old.englishName != p.englishName || old.numberTexts != p.numberTexts {
                    _ = edit.updateProfile(playerID:p.id,chineseName:p.chineseName,englishName:p.englishName,numberTexts:p.numberTexts)
                }
            } else { _ = edit.addBenchPlayer(p) }
        }
        guard edit.hasChanges else { return snapshot }
        var result = snapshot
        if lineup.isHome { result.homeTeam.players = edit.players } else { result.awayTeam.players = edit.players }
        result.baseRunners = edit.runners
        if result.historyJournal != nil {
            result.historyJournal?.operations.append(HistoryOperation(command:.lineup(HistoryLineup(edit)),before:snapshot))
        }
        return result
    }

    var changesParticipation: Bool {
        baseline.battingIDs != lineup.battingIDs || baseline.fieldingIDs != lineup.fieldingIDs ||
        baseline.anchorID != lineup.anchorID || baseline.pitcherID != lineup.pitcherID ||
        baseline.dhID != lineup.dhID || baseline.runners.mapValues(\.id) != lineup.runners.mapValues(\.id) ||
        baseline.automaticIDs != lineup.automaticIDs ||
        lineup.players.contains { next in baseline.players.contains { $0.id == next.id && $0.primaryPosition != next.primaryPosition } }
    }
}

struct SavedScheduleWriter: Codable {
    let team: UUID
    let game: UUID
    let member: UUID
    let recovery: UUID?
    let accessEpoch: Int?
    let role: ScheduleRole
}

struct ScheduleDraftEnvelope: Codable {
    let team: UUID
    let game: UUID
    let member: UUID
    let recovery: UUID?
    let epoch: Int
    let proposal: ScheduleProposal
}

struct ScheduleScoringStatus: Decodable {
    struct Receipt: Decodable { let id: UUID; let applied: Bool }
    let access: ScheduleRole
    let version: Int
    let frame: ScheduleField?
    let proposal: ScheduleProposal?
    let lastFrameID: UUID?
    let reserved: UUID?
    let receipt: Receipt?
    private enum CodingKeys: CodingKey { case version, frame, proposal, receipt, reserved, lastFrameID }
    init(from decoder: Decoder) throws {
        access = try ScheduleRole(from: decoder)
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(Int.self, forKey: .version)
        frame = try c.decodeIfPresent(ScheduleField.self, forKey: .frame)
        proposal = try c.decodeIfPresent(ScheduleProposal.self, forKey: .proposal)
        lastFrameID = try c.decodeIfPresent(UUID.self, forKey:.lastFrameID)
        reserved = try c.decodeIfPresent(UUID.self, forKey: .reserved)
        receipt = try c.decodeIfPresent(Receipt.self, forKey: .receipt)
    }
}

struct ScheduleFrameSubmission: Codable, Equatable {
    let id: UUID
    let epoch: Int
    let version: Int
    let frame: ScheduleField
    let appliedID: UUID?
    let rejected: Bool
}

struct ScheduleWrite: Encodable {
    var operationID = UUID()
    var epoch: Int
    var version: Int?
    var scorer: UUID?
    var lineup: UUID?
    var frame: ScheduleField?
    var proposal: ScheduleProposal?
    var appliedID: UUID?
    var rejected: Bool?
    var finished: Bool?
    var transferID: UUID?
    var document: [String: MatchJSON]?
}

struct ScheduleMatchKey: Hashable {
    let team: UUID
    let game: UUID
    var path: String { "teams/" + team.uuidString.lowercased() + "/schedules/" + game.uuidString.lowercased() + "/scoring" }
}

extension GameStore {
    func scheduleKey(_ game: StoredGame) -> ScheduleMatchKey? {
        game.schedulePublication.map { ScheduleMatchKey(team: $0.teamID, game: game.id) }
    }

    func scheduleAccess(_ stored: StoredGame) -> ScheduleRole? {
        guard let key = scheduleKey(stored), !teamSharing.state.requiresRecoveryReview,
              let team = teamSharing.teams.first(where: { $0.id == key.team && $0.role != .member }) else { return nil }
        if let permission = schedulePermissions[key] {
            guard permission.member == team.memberID, permission.recovery == teamSharing.state.recoveryEpoch else { return nil }
            return permission.role
        }
        return teamSharing.state.scheduleWriters?.first {
            $0.team == key.team && $0.game == key.game && $0.member == team.memberID &&
            $0.recovery == teamSharing.state.recoveryEpoch && $0.accessEpoch == team.accessEpoch
        }?.role
    }

    func permitsScheduledScore(_ stored: StoredGame) -> Bool {
        guard stored.schedulePublication != nil else { return true }
        guard let role = scheduleAccess(stored) else { return false }
        return role.role == "scorer" && role.holdsDevice && !role.closed && role.transfer == nil
            && (role.lineup == nil || role.openingConfirmed == true || applyingScheduleProposal != nil)
    }

    func scoringStatus(_ key: ScheduleMatchKey) async throws -> ScheduleScoringStatus {
        guard let client = teamSharing.service as? TeamServiceClient,
              !teamSharing.state.requiresRecoveryReview,
              let member = teamSharing.teams.first(where: { $0.id == key.team })?.memberID else {
            schedulePermissions.removeValue(forKey: key)
            throw TeamServiceError.message("请先刷新球队身份。")
        }
        let recovery = teamSharing.state.recoveryEpoch
        do {
            let status: ScheduleScoringStatus = try await client.request("GET",path:key.path,body:Optional<String>.none)
            guard teamSharing.teams.contains(where: { $0.id == key.team && $0.memberID == member }),
                  !teamSharing.state.requiresRecoveryReview, recovery == teamSharing.state.recoveryEpoch else {
                throw TeamServiceError.message("球队权限已变化，请重新进入。")
            }
            if schedulePermissions[key]?.role != status.access || schedulePermissions[key]?.member != member ||
                schedulePermissions[key]?.recovery != recovery {
                schedulePermissions[key] = (member,recovery,status.access)
            }
            try cacheScheduleWriter(key,member:member,role:status.access)
            return status
        } catch TeamServiceError.response(let code, let message) {
            if [401,403,404,426].contains(code) {
                schedulePermissions.removeValue(forKey:key)
                try cacheScheduleWriter(key,member:member,role:nil)
            }
            throw TeamServiceError.response(code,message)
        }
    }

    private func cacheScheduleWriter(_ key: ScheduleMatchKey, member: UUID, role: ScheduleRole?) throws {
        var next = teamSharing.state
        var saved = next.scheduleWriters ?? []
        let old = saved.first { $0.team == key.team && $0.game == key.game }
        let epoch = teamSharing.teams.first { $0.id == key.team }?.accessEpoch
        if let role, role.role == "scorer", role.holdsDevice, !role.closed, role.transfer == nil {
            if old?.role == role && old?.member == member && old?.recovery == next.recoveryEpoch && old?.accessEpoch == epoch { return }
            saved.removeAll { $0.team == key.team && $0.game == key.game }
            saved.append(SavedScheduleWriter(team:key.team,game:key.game,member:member,recovery:next.recoveryEpoch,accessEpoch:epoch,role:role))
        } else {
            guard old != nil else { return }
            saved.removeAll { $0.team == key.team && $0.game == key.game }
        }
        next.scheduleWriters = saved
        try teamSharing.persist(next)
    }

    func scoringWrite(_ key: ScheduleMatchKey, _ action: String, _ body: ScheduleWrite) async throws {
        struct Ack: Decodable { let ok: Bool }
        guard !teamSharing.state.requiresRecoveryReview, let client = teamSharing.service as? TeamServiceClient else {
            throw TeamServiceError.message("请先核对球队服务。")
        }
        let ack: Ack = try await client.request("POST",path:key.path + "/" + action,body:body)
        guard ack.ok else { throw TeamServiceError.message("操作尚待确认。") }
    }

    func prepareScoring(_ key: ScheduleMatchKey, status: ScheduleScoringStatus) async throws {
        guard let local = games.first(where: { $0.id == key.game && $0.schedulePublication?.teamID == key.team && $0.status == .scheduled }) else {
            throw TeamServiceError.message("请比赛发布人在原设备上提供赛前名单。")
        }
        let prepared = try preparedScheduleField(local)
        try await scoringWrite(key,"prepare",ScheduleWrite(epoch:status.access.epoch,version:status.version,frame:prepared))
    }

    func enterScheduledScore(_ key: ScheduleMatchKey) async throws {
        var status = try await scoringStatus(key)
        var adopted = false
        if status.frame == nil {
            if let old = games.first(where:{ $0.id == key.game && $0.schedulePublication?.teamID == key.team && $0.status == .ongoing }) {
                let field = ScheduleField(old,pregame:old.startedAt == nil && old.openingPitchRecorded != true)
                try await scoringWrite(key,"resumeLegacy",ScheduleWrite(epoch:status.access.epoch,frame:field))
                adopted = true
            } else { try await prepareScoring(key,status:status) }
            status = try await scoringStatus(key)
        }
        guard status.access.role == "scorer", let initial = status.frame else {
            throw TeamServiceError.message("只有本场局面记录员可以开赛。")
        }
        let local = games.first { $0.id == key.game }
        if let local, local.schedulePublication?.teamID != key.team {
            throw TeamServiceError.message("本机有另一场相同编号的比赛，请核对。")
        }
        if status.access.active && status.access.hasCheckpoint != true && local?.status != .ongoing && !initial.pregame {
            throw TeamServiceError.message("本机缺少完整逐球历史，请回原记录设备继续。")
        }
        try await scoringWrite(key,"start",ScheduleWrite(epoch:status.access.epoch))
        status = try await scoringStatus(key)
        guard status.access.holdsDevice, !status.access.closed, let field = status.frame else {
            throw TeamServiceError.message("本场岗位已变化。")
        }
        if status.access.hasCheckpoint == true {
            struct Checkpoint: Decodable { let game: StoredGame }
            guard let client = teamSharing.service as? TeamServiceClient else { throw TeamServiceError.message("请核对球队服务。") }
            let download: Checkpoint = try await client.request("GET",path:key.path + "/checkpoint",body:Optional<String>.none)
            var saved = download.game
            guard saved.id == key.game, saved.status == .ongoing, saved.state.homeTeam.id == field.state.homeTeam.id,
                  saved.state.awayTeam.id == field.state.awayTeam.id else { throw TeamServiceError.message("交接档案与本场不符。") }
            if let local, local.status == .ongoing, local.scheduleReceivedEpoch != status.access.epoch {
                let old = local.state.playLog.map(\.id), incoming = saved.state.playLog.map(\.id)
                guard incoming.starts(with:old) else { throw TeamServiceError.message("本机另有未核对的逐球记录，已保留。请先备份并核对后再接收。") }
            }
            saved.schedulePublication = local?.schedulePublication ?? LocalSchedulePublication(teamID:key.team)
            saved.postgameUploadTeamID = key.team
            // Only the named recipient can retrieve this checkpoint. A lost receipt must never rewind later plays.
            if local?.scheduleReceivedEpoch != status.access.epoch {
                saved.scheduleReceivedEpoch = status.access.epoch
                saved.schedulePublication?.scoringVersion = status.version
                saved.schedulePublication?.pendingFrame = nil
                try persistenceStore.upsertGame(saved)
                if let i = games.firstIndex(where:{ $0.id == key.game }) { games[i] = saved } else { games.insert(saved,at:0) }
            }
            try await scoringWrite(key,"received",ScheduleWrite(epoch:status.access.epoch))
            openGame(id:key.game)
            return
        }
        if var local, local.status == .ongoing {
            if adopted || (local.schedulePublication?.scoringVersion == nil && status.access.holdsDevice && ScheduleField(local,pregame:local.startedAt == nil && local.openingPitchRecorded != true) == status.frame) {
                local.schedulePublication?.scoringVersion = status.version
                try saveScheduleFields(local)
            }
            openGame(id:key.game)
            return
        }
        guard field.pregame else { throw TeamServiceError.message("需要先恢复本场完整记录。") }
        let g = field.state
        guard !g.homeBattingOrderIDs.isEmpty, !g.awayBattingOrderIDs.isEmpty,
              !g.homeTeam.players.isEmpty, !g.awayTeam.players.isEmpty else { throw TeamServiceError.message("请先完成双方先发。") }
        let own = field.isHome ? g.homeTeam : g.awayTeam, other = field.isHome ? g.awayTeam : g.homeTeam
        let notice = teamSchedules.first { $0.teamID == key.team && $0.id == key.game }
        var stored = local ?? StoredGame(id:key.game,scheduledAt:notice?.date,startedAt:nil,
            seasonID:MatchYear.id(for:notice?.date ?? Date()),ourTeamID:own.id,opponentTeamID:other.id,
            isHome:field.isHome,rules:field.rules,lineup:[],state:g)
        stored.status = .ongoing; stored.state = g; stored.rules = field.rules
        stored.lineup = g.startingAssignments(for:own.id,rules:field.rules)
        stored.secondaryLineup = g.startingAssignments(for:other.id,rules:field.rules)
        stored.schedulePublication = local?.schedulePublication ?? LocalSchedulePublication(teamID:key.team, revision:notice?.revision ?? 0)
        stored.schedulePublication?.scoringVersion = status.version
        stored.postgameUploadTeamID = key.team
        try persistenceStore.upsertGame(stored)
        if let index = games.firstIndex(where: { $0.id == key.game }) { games[index] = stored } else { games.insert(stored,at:0) }
        openGame(id:key.game)
    }

    func releaseScheduledScore(_ key: ScheduleMatchKey, status: ScheduleScoringStatus) async throws {
        guard let transfer = status.access.transfer, status.access.holdsDevice, status.access.role == "scorer",
              let local = games.first(where: { $0.id == key.game && $0.status == .ongoing }) else { throw TeamServiceError.message("交接状态已变化。") }
        let pregame = local.openingPitchRecorded != true && local.startedAt == nil
        let frame = ScheduleField(local,pregame:pregame)
        try await scoringWrite(key,"release",ScheduleWrite(epoch:status.access.epoch,version:status.version,frame:frame,
            transferID:transfer.id,document:try sharedMatches.documentFields(local)))
    }

    func stageScheduleDraft(_ key: ScheduleMatchKey, epoch: Int, proposal: ScheduleProposal) throws {
        guard let member = teamSharing.teams.first(where: { $0.id == key.team })?.memberID else { throw TeamServiceError.message("请刷新球队身份。") }
        var next = teamSharing.state
        var drafts = next.scheduleDrafts ?? []
        drafts.removeAll { $0.team == key.team && $0.game == key.game }
        drafts.append(ScheduleDraftEnvelope(team:key.team,game:key.game,member:member,recovery:next.recoveryEpoch,epoch:epoch,proposal:proposal))
        next.scheduleDrafts = drafts
        try teamSharing.persist(next)
    }

    func submitScheduleDraft(_ key: ScheduleMatchKey, status: ScheduleScoringStatus) async throws {
        guard let draft = teamSharing.state.scheduleDrafts?.first(where: { $0.game == key.game && $0.team == key.team }),
              draft.epoch == status.access.epoch, draft.recovery == teamSharing.state.recoveryEpoch,
              teamSharing.teams.contains(where: { $0.id == key.team && $0.memberID == draft.member }) else {
            throw TeamServiceError.message("岗位或恢复代次已变化，原草稿需重新核对。")
        }
        try await scoringWrite(key,"propose",ScheduleWrite(operationID:draft.proposal.id,epoch:draft.epoch,proposal:draft.proposal))
        var next = teamSharing.state
        next.scheduleDrafts?.removeAll { $0.proposal.id == draft.proposal.id }
        try teamSharing.persist(next)
    }
}

extension GameState {
    func hasSameFieldAssignments(as prior: GameState) -> Bool {
        homeTeam == prior.homeTeam && awayTeam == prior.awayTeam &&
        homeBattingOrderIDs == prior.homeBattingOrderIDs && awayBattingOrderIDs == prior.awayBattingOrderIDs &&
        homeFieldingPlayerIDs == prior.homeFieldingPlayerIDs && awayFieldingPlayerIDs == prior.awayFieldingPlayerIDs &&
        activeHomePitcherID == prior.activeHomePitcherID && activeAwayPitcherID == prior.activeAwayPitcherID &&
        homeDesignatedHitterID == prior.homeDesignatedHitterID && awayDesignatedHitterID == prior.awayDesignatedHitterID
    }
}

struct ScheduleSyncResult {
    var opening: ScheduleScoringStatus?
    var conflict: ScheduleProposal?
    var message: String?
}

extension GameStore {
    private func sendScheduleFrame(_ key: ScheduleMatchKey, _ pending: ScheduleFrameSubmission) async throws {
        try await scoringWrite(key,"frame",ScheduleWrite(operationID:pending.id,epoch:pending.epoch,version:pending.version,
            frame:pending.frame,appliedID:pending.appliedID,rejected:pending.rejected,finished:pending.frame.state.isFinal))
        guard var game = games.first(where:{ $0.id == key.game && $0.schedulePublication?.teamID == key.team }),
              game.schedulePublication?.pendingFrame?.id == pending.id else { return }
        game.schedulePublication?.pendingFrame = nil
        game.schedulePublication?.scoringVersion = pending.version + 1
        try saveScheduleFields(game)
    }

    func synchronizeScheduledScore(_ key: ScheduleMatchKey, accept: UUID? = nil, reject: UUID? = nil) async throws -> ScheduleSyncResult {
        var result = ScheduleSyncResult()
        guard scheduleSyncInFlight.insert(key).inserted else { return result }
        defer { scheduleSyncInFlight.remove(key) }
        let s = try await scoringStatus(key)
        guard !Task.isCancelled, let stored = activeStoredGame, stored.id == key.game else { return result }
        if let transfer = s.access.transfer {
            result.message = "等待交接给" + transfer.targetName + "。请进入本场记录岗位交接。"; return result
        }
        guard !s.access.closed else {
            if let pending = stored.schedulePublication?.pendingFrame, pending.id == s.lastFrameID {
                var final = stored
                final.schedulePublication?.pendingFrame = nil
                final.schedulePublication?.scoringVersion = s.version
                try saveScheduleFields(final)
            }
            return result
        }
        guard s.access.role == "scorer", s.access.holdsDevice else {
            result.message = "本设备只读，请从本场记录岗位核对指派。"; return result
        }
        let waitingForOpening = s.access.lineup != nil && s.access.openingConfirmed != true
        result.opening = waitingForOpening ? s : nil
        guard var local = stored.schedulePublication else { return result }
        if let pending = local.pendingFrame {
            if s.lastFrameID == pending.id {
                local.scoringVersion = s.version
            } else if s.version == pending.version && s.access.epoch == pending.epoch {
                try await sendScheduleFrame(key,pending)
                return result
            }
            local.pendingFrame = nil
            var confirmed = stored
            confirmed.schedulePublication = local
            try saveScheduleFields(confirmed)
        }
        guard local.scoringVersion == s.version else {
            throw TeamServiceError.message("本机比赛与已确认的记录位置不同。原记录已保留，请核对备份与交接。")
        }
        let field = ScheduleField(stored,pregame:isPregame)
        var handled: UUID?
        var rejected = false
        if let p = s.proposal {
            if stored.lastScheduleProposal == p.id { handled = p.id }
            else if reject == p.id { handled = p.id; rejected = true }
            else if !canRecordAction && !waitingForOpening && !stored.state.isFinal { return result }
            else if let draft = p.draft(on:game,pregame:isPregame),
                    !stored.state.isFinal && (!p.changesParticipation || p.lastPlay == field.lastPlay || accept == p.id) {
                if s.reserved != p.id {
                    try await scoringWrite(key,"reserve",ScheduleWrite(epoch:s.access.epoch,appliedID:p.id))
                    guard !Task.isCancelled, activeGameID == key.game, game == draft.source else { return result }
                }
                guard applyScheduleLineup(p,draft:draft) else { result.message = actionErrorMessage; return result }
                handled = p.id
            } else {
                result.conflict = p
                if accept == p.id { result.message = "场上人员或垒位已变化，请退回重新选择。" }
                return result
            }
        }
        guard let current = activeStoredGame, current.id == key.game else { return result }
        let latest = ScheduleField(current,pregame:isPregame)
        if latest != s.frame || handled != nil || latest.state.isFinal {
            let pending = ScheduleFrameSubmission(id:UUID(),epoch:s.access.epoch,version:s.version,frame:latest,appliedID:handled,rejected:rejected)
            var staged = current
            staged.schedulePublication?.pendingFrame = pending
            try saveScheduleFields(staged)
            try await sendScheduleFrame(key,pending)
        }
        result.message = handled == nil ? nil : (rejected ? "已退回阵容调整" : "阵容调整已生效")
        return result
    }
}
