import Foundation
import Combine
import CryptoKit

/// JSON preserves the complete, versioned match without converting it to a display-only snapshot.
indirect enum MatchJSON: Codable {
    case object([String: MatchJSON]), array([MatchJSON]), string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode([String: MatchJSON].self) { self = .object(v) }
        else { self = .array(try c.decode([MatchJSON].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    static func encode<T: Encodable>(_ value: T) throws -> MatchJSON { try JSONDecoder().decode(MatchJSON.self, from: JSONEncoder().encode(value)) }
    static func uuid(_ value: UUID) -> MatchJSON { .string(value.uuidString.lowercased()) }
}

enum HistoricalAccessMode: String, Codable, CaseIterable {
    case closed, `public`, restricted
    var title: String { switch self { case .closed: return "不开放"; case .public: return "永久开放"; case .restricted: return "仅指定人员" } }
}
struct MatchHandoff: Codable {
    let id: UUID; let sourceDeviceID: UUID; let targetDeviceID: UUID; let revision: Int; let epoch: Int; let digest: String
}
struct SharedMatch: Codable, Identifiable {
    let id: UUID; let teamID: UUID; let serverRevision: Int; let writerEpoch: Int
    let writerDeviceID: UUID; let responsibleMemberID: UUID; let updatedAt: Double; let deleted: Bool; let digest: String
    let accessMode: HistoricalAccessMode; let accessVersion: Int; let viewCode: String; let liveEnabled: Bool
    let liveCode: String?; let liveExpiresAt: Double?
    let canWrite: Bool; let writerActive: Bool; let canManageAccess: Bool; let handoff: MatchHandoff?
    var payload: StoredGame?; var title: String?; var status: String?
    var playerLinks: [MatchPlayerLink]? = nil
    var historySourceID: UUID? = nil
    var wireLocalID: UUID? = nil
    var isolated: SharedMatch {
        var copy = self
        if let payload { copy.wireLocalID = wireLocalID ?? payload.id; copy.payload?.id = id }
        return copy
    }
    var viewURL: URL { URL(string: "https://baseballmaster.cc/watch/#\(viewCode)")! }
    var liveURL: URL? { liveCode.flatMap { URL(string: "https://baseballmaster.cc/watch/#live/\($0)") } }
    var matchTitle: String { payload.map { "\($0.state.awayTeam.name) vs \($0.state.homeTeam.name)" } ?? title ?? "共享比赛" }
    var officialGame: StoredGame? {
        guard !deleted, var game = payload else { return nil }
        game.sharedTeamID = teamID; game.sharedGameID = id
        game.sharedPlayerLinks = playerLinks; game.historySourceID = historySourceID
        return game
    }
}
struct SharedMatchPage: Decodable { let games: [SharedMatch]; let cursor: Int; let hasMore: Bool; let recoveryEpoch: UUID }
struct MatchDevice: Codable, Identifiable { let deviceID: UUID; let memberID: UUID; let nickname: String; let label: String; var id: UUID { deviceID } }
struct MatchViewer: Codable, Identifiable { let id: UUID; let nickname: String; let status: String; let memberID: UUID? }
struct MatchViewerMember: Codable, Identifiable { let id: UUID; let nickname: String; let granted: Int }
struct MatchViewers: Decodable { let viewers: [MatchViewer]; let members: [MatchViewerMember] }
protocol MatchService {
    func enroll(deviceName: String) async throws -> UUID
    func teams() async throws -> SharedTeamList
    func matchPage(teamID: UUID, cursor: Int) async throws -> SharedMatchPage
    func match(teamID: UUID, gameID: UUID) async throws -> SharedMatch
    func matchCommand(path: String, method: String, body: MatchJSON) async throws -> MatchJSON
    func matchDevices(teamID: UUID, gameID: UUID) async throws -> [MatchDevice]
    func matchViewers(teamID: UUID, gameID: UUID) async throws -> MatchViewers
}
extension TeamServiceClient: MatchService {
    func matchPage(teamID: UUID, cursor: Int) async throws -> SharedMatchPage {
        try await request("GET", path: "teams/\(teamID.uuidString.lowercased())/games?cursor=\(cursor)", body: Optional<String>.none)
    }
    func match(teamID: UUID, gameID: UUID) async throws -> SharedMatch {
        try await request("GET", path: Self.matchPath(teamID,gameID), body: Optional<String>.none)
    }
    nonisolated static func matchPath(_ teamID: UUID, _ gameID: UUID) -> String { "teams/\(teamID.uuidString.lowercased())/games/\(gameID.uuidString.lowercased())" }
    func matchCommand(path: String, method: String, body: MatchJSON) async throws -> MatchJSON { try await request(method, path: path, body: method == "GET" ? Optional<MatchJSON>.none : body) }
    func matchDevices(teamID: UUID, gameID: UUID) async throws -> [MatchDevice] {
        struct Response: Decodable { let devices: [MatchDevice] }
        let response: Response = try await request("GET", path: Self.matchPath(teamID,gameID) + "/participants", body: Optional<String>.none)
        return response.devices
    }
    func matchViewers(teamID: UUID, gameID: UUID) async throws -> MatchViewers {
        try await request("GET", path: Self.matchPath(teamID,gameID) + "/viewers", body: Optional<String>.none)
    }
}
struct PendingMatchOperation: Codable {
    let id: UUID; var localID: UUID; let teamID: UUID; let gameID: UUID?
    let path: String; let method: String; let body: MatchJSON; let kind: String
    let sentLocalRevision: Int?; let token: String?
}
struct MatchConflict: Codable, Identifiable {
    let id: UUID; var localID: UUID; let savedAt: Date; var game: StoredGame; let reason: String
}
struct SharedMatchLocalState: Codable {
    var matches: [SharedMatch] = []
    var pending: [PendingMatchOperation] = []
    var conflicts: [MatchConflict] = []
    var acknowledgedRevisions: [String: Int] = [:]
    var cursors: [String: Int] = [:]
    var deviceID: UUID?
    var recoveryEpoch: UUID?
    var requiresRecoveryReview = false
    var teamAccessEpochs: [String: Int]? = nil
    var lastInvitationURL: String?
    var resolvedConflicts: [MatchConflict]? = nil
    var originalLocalGames: [StoredGame]? = nil
    var historyImport: HistoricalImportBatch? = nil
    var submittedConflictIDs: [UUID]? = nil
    var isolatedCopiesVersion: Int? = nil
    var legacyRestoreSourceIDs: [UUID]? = nil
    var publishedSources: [String: UUID]? = nil
}
protocol SharedMatchStorage { func load() throws -> SharedMatchLocalState; func save(_ state: SharedMatchLocalState) throws }
struct SharedMatchFile: SharedMatchStorage {
    let url: URL; let vault: TeamCredentialStorage
    private func key() throws -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(try vault.encryptionSecret().utf8)),
            salt: Data("BaseballMaster.matches.v1".utf8), info: Data("local-state".utf8), outputByteCount: 32)
    }
    func load() throws -> SharedMatchLocalState {
        guard FileManager.default.fileExists(atPath: url.path) else { return SharedMatchLocalState() }
        return try JSONDecoder().decode(SharedMatchLocalState.self, from: AES.GCM.open(AES.GCM.SealedBox(combined: Data(contentsOf: url)), using: key()))
    }
    func save(_ state: SharedMatchLocalState) throws {
        var directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true; try directory.setResourceValues(values)
        let data = try AES.GCM.seal(JSONEncoder().encode(state), using: key()).combined!
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

@MainActor final class SharedMatchCoordinator: ObservableObject {
    @Published private(set) var state = SharedMatchLocalState()
    @Published private(set) var busy = false
    @Published var message: String?
    @Published private(set) var importing = false
    private var cancelImportRequested = false
    private weak var store: GameStore?
    let service: MatchService
    private let storage: SharedMatchStorage
    private var isolationReady = false
    private var loaded = false
    private var worker: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var requested = false
    init(store: GameStore, service: MatchService, storage: SharedMatchStorage) {
        self.store = store; self.service = service; self.storage = storage
        do { state = try storage.load(); loaded = true; try migrateIndependentCopies() }
        catch { message = "暂时无法读取这台设备保存的共享比赛。资料仍会保留，请解锁设备后重试。" }
    }
    static func deviceStore(store: GameStore) -> SharedMatchCoordinator {
        let configuration = TeamSharingStore.connectionConfiguration()
        return SharedMatchCoordinator(store: store, service: try! TeamServiceClient(baseURL: configuration.baseURL, vault: configuration.vault),
            storage: SharedMatchFile(url: configuration.url.deletingLastPathComponent().appendingPathComponent(configuration.url.lastPathComponent + ".matches"), vault: configuration.vault))
    }
    private func persist(_ next: SharedMatchLocalState) throws { try storage.save(next); state = next; store?.objectWillChange.send() }
    private func migrateIndependentCopies() throws {
        guard state.isolatedCopiesVersion != 1 else { isolationReady = true; return }
        var next = state
        var replacements: [UUID: UUID] = [:]
        for match in state.matches { if let id = match.payload?.id, id != match.id { replacements[id] = match.id } }
        for game in store?.games ?? [] { if let sharedID = game.sharedGameID, game.id != sharedID { replacements[game.id] = sharedID } }
        // Save the local database first, atomically. Retrying after a crash is harmless.
        let originals = (state.originalLocalGames ?? []).filter { state.legacyRestoreSourceIDs?.contains($0.id) ?? true }
        try store?.separateLegacySharedCopies(originals: originals)
        next.matches = next.matches.map(\.isolated)
        for i in next.pending.indices { if let id = replacements[next.pending[i].localID] { next.pending[i].localID = id } }
        for i in next.conflicts.indices { if let id = replacements[next.conflicts[i].localID] { next.conflicts[i].localID = id; next.conflicts[i].game.id = id } }
        for (old, new) in replacements { if let revision = next.acknowledgedRevisions.removeValue(forKey: old.uuidString) { next.acknowledgedRevisions[new.uuidString] = revision } }
        for original in state.originalLocalGames ?? [] {
            for match in state.matches where match.wireLocalID == original.id || match.payload?.id == original.id {
                if next.publishedSources == nil { next.publishedSources = [:] }
                next.publishedSources?["\(match.teamID):\(original.id)"] = match.id
            }
        }
        next.isolatedCopiesVersion = 1; next.legacyRestoreSourceIDs = nil
        try persist(next)
        isolationReady = true
    }
    func publishedMatch(sourceID: UUID, teamID: UUID) -> SharedMatch? {
        state.publishedSources?["\(teamID):\(sourceID)"].flatMap { match(id: $0) }
            ?? state.matches.first { $0.teamID == teamID && ($0.historySourceID == sourceID || $0.wireLocalID == sourceID) }
    }
    private func fetchMatch(teamID: UUID, gameID: UUID) async throws -> SharedMatch {
        try await service.match(teamID: teamID, gameID: gameID).isolated
    }
    func match(localID: UUID) -> SharedMatch? { state.matches.first { $0.payload?.id == localID } }
    func match(id: UUID) -> SharedMatch? { state.matches.first { $0.id == id } }
    func isShared(_ localID: UUID) -> Bool { match(localID: localID) != nil || state.pending.contains { $0.localID == localID } || store?.games.first(where: { $0.id == localID })?.sharedGameID != nil }
    func canEdit(_ localID: UUID) -> Bool {
        guard isShared(localID) else { return true }
        guard isolationReady else { return false }
        guard loaded, !state.requiresRecoveryReview, store?.teamSharing.state.requiresRecoveryReview != true, let match = match(localID: localID), match.canWrite,
              !state.conflicts.contains(where: { $0.localID == localID }),
              !state.pending.contains(where: { $0.localID == localID && $0.kind != "update" }) else { return false }
        return match.writerDeviceID == state.deviceID
    }
    func status(_ localID: UUID) -> String {
        if !isShared(localID) { return "仅本机" }
        if state.requiresRecoveryReview { return "服务恢复后待核对" }
        if state.conflicts.contains(where: { $0.localID == localID }) { return "记录有不同，待核对 · 未同步内容已保留" }
        if state.pending.contains(where: { $0.localID == localID }) { return busy ? "同步中" : "待同步" }
        guard let match = match(localID: localID) else { return "权限待核对" }
        if match.handoff != nil { return "交接中 · 暂停记分" }
        if let game = store?.games.first(where: { $0.id == localID }), canEdit(localID), state.acknowledgedRevisions[localID.uuidString] != (game.revision ?? 0) { return "待同步" }
        return "已同步至 " + Date(timeIntervalSince1970: match.updatedAt / 1000).formatted(date: .omitted, time: .shortened) + (match.canWrite ? " · 本机记分" : " · 只读")
    }
    func setForeground(_ active: Bool) {
        ticker?.cancel(); ticker = nil
        guard active, !state.matches.isEmpty || !state.pending.isEmpty else { return }
        requestSync()
        ticker = Task { [weak self] in
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 10_000_000_000); if !Task.isCancelled { self?.requestSync() } }
        }
    }
    func waitForSync() async { await worker?.value }
    func requestSync() {
        guard loaded, !state.requiresRecoveryReview, !state.matches.isEmpty || !state.pending.isEmpty else { return }
        requested = true
        guard worker == nil else { return }
        worker = Task { [weak self] in
            guard let self else { return }
            while self.requested { self.requested = false; await self.synchronize() }
            self.worker = nil
        }
    }
    func saved(_ game: StoredGame) {
        guard isShared(game.id), canEdit(game.id) else { return }
        do { try stageUpdate(game) } catch { message = "比赛已保存到本机，待同步队列未能保存。请检查存储空间后重试。" }
        requestSync()
    }
    private func documentFields(_ game: StoredGame) throws -> [String: MatchJSON] {
        var clean = game
        if let wireID = match(localID: game.id)?.wireLocalID { clean.id = wireID }
        clean.sharedTeamID = nil; clean.sharedGameID = nil
        clean.sharedPlayerLinks = nil; clean.historySourceID = nil
        clean.publicationState = nil; clean.pendingPublicationRevisions = nil
        clean.minimumWriterFormat = 2
        return ["formatVersion": .number(2), "payload": try .encode(clean), "projection": try JSONDecoder().decode(MatchJSON.self, from: LiveSnapshot(clean).data())]
    }
    private func operation(localID: UUID, teamID: UUID, gameID: UUID?, path: String, method: String, kind: String,
                           fields: [String: MatchJSON], revision: Int? = nil, token: String? = nil) -> PendingMatchOperation {
        let id = UUID(); var body = fields; body["operationID"] = .uuid(id)
        return PendingMatchOperation(id: id, localID: localID, teamID: teamID, gameID: gameID, path: path, method: method,
            body: .object(body), kind: kind, sentLocalRevision: revision, token: token)
    }
    private func stageUpdate(_ game: StoredGame) throws {
        guard let match = match(localID: game.id), canEdit(game.id), !state.pending.contains(where: { $0.localID == game.id }),
              state.acknowledgedRevisions[game.id.uuidString] != (game.revision ?? 0) else { return }
        var fields = try documentFields(game); fields["baseRevision"] = .number(Double(match.serverRevision)); fields["writerEpoch"] = .number(Double(match.writerEpoch))
        var next = state
        next.pending.append(operation(localID: game.id, teamID: match.teamID, gameID: match.id, path: TeamServiceClient.matchPath(match.teamID,match.id),
            method: "PUT", kind: "update", fields: fields, revision: game.revision ?? 0))
        try persist(next)
    }
    func publish(_ game: StoredGame, teamID: UUID) async {
        guard !busy else { message = "正在同步其他比赛，本机记录已保留，请稍后从比赛设置或结束页面重试共享。"; return }
        guard loaded, !state.requiresRecoveryReview else { message = "共享资料需要先读取或核对，本机记录已保留，完成核对后可重试共享。"; return }
        guard !isShared(game.id) else { message = "这场比赛已在共享球队中，无需再次共享。"; return }
        do {
            try migrateIndependentCopies()
            if publishedMatch(sourceID: game.id, teamID: teamID) != nil { message = "这场比赛已经共享。要向成员更新记录，请从共享球队中打开这场比赛。"; return }
            if state.pending.contains(where: { $0.teamID == teamID && $0.kind == "create" }) { message = "请先同步待共享比赛。"; return }
            var next = state
            next.originalLocalGames = (next.originalLocalGames ?? []) + [game]
            next.pending.append(operation(localID: UUID(), teamID: teamID, gameID: nil, path: "teams/\(teamID.uuidString.lowercased())/games",
                method: "POST", kind: "create", fields: try documentFields(game), revision: game.revision ?? 0))
            try persist(next); await synchronize()
        } catch { report(error) }
    }
    func synchronize(teamID: UUID? = nil) async {
        guard loaded, !busy, !state.requiresRecoveryReview else { return }
        busy = true; defer { busy = false }; message = nil
        do {
            try migrateIndependentCopies()
            let device = try await service.enroll(deviceName: "iPhone")
            let teams = try await service.teams()
            if let epoch = state.recoveryEpoch, epoch != teams.recoveryEpoch {
                var next = state; next.requiresRecoveryReview = true; try persist(next)
                throw TeamServiceError.message("球队服务已恢复资料，请先核对；本机未同步记录已保留。")
            }
            var next = state; next.deviceID = device; next.recoveryEpoch = teams.recoveryEpoch
            for team in teams.teams {
                if let old = next.teamAccessEpochs?[team.id.uuidString], old != (team.accessEpoch ?? 1) {
                    for match in next.matches where match.teamID == team.id { preserveDraft(match.payload?.id, reason: "球队管理权恢复后需要重新核对", into: &next) }
                    for pending in next.pending where pending.teamID == team.id { preserveDraft(pending.localID, reason: "球队管理权恢复前的未同步记录", into: &next) }
                    next.pending.removeAll { $0.teamID == team.id }; next.cursors.removeValue(forKey: team.id.uuidString)
                    next.lastInvitationURL = nil
                }
            }
            next.teamAccessEpochs = Dictionary(uniqueKeysWithValues: teams.teams.map { ($0.id.uuidString, $0.accessEpoch ?? 1) })
            let accessible = Set(teams.teams.map(\.id))
            for old in next.matches where !accessible.contains(old.teamID) {
                preserveDraft(old.payload?.id, reason: "球队访问权限已失效", into: &next)
                next.pending.removeAll { $0.gameID == old.id }
                if let localID = old.payload?.id {
                    if let original = next.originalLocalGames?.first(where: { $0.id == localID }) { try store?.restorePreSharingCopy(original, replacing: old.id) }
                    else { try store?.removeSharedCopy(localID) }
                }
            }
            next.matches.removeAll { !accessible.contains($0.teamID) }
            next.cursors = next.cursors.filter { key, _ in UUID(uuidString: key).map(accessible.contains) == true }
            if let link = next.lastInvitationURL, !next.matches.contains(where: { link.hasPrefix($0.viewURL.absoluteString) && $0.canManageAccess }) { next.lastInvitationURL = nil }
            try persist(next)
            // Reinstall a complete durable document after a prior database write failure or process termination.
            for remote in state.matches where !state.pending.contains(where: { $0.gameID == remote.id }) {
                if remote.deleted || remote.payload.map({ !canEdit($0.id) }) == true { try install(remote) }
            }
            for game in store?.games ?? [] { try stageUpdate(game) }
            for pending in state.pending {
                guard accessible.contains(pending.teamID) else { continue }
                do {
                    let result = try await service.matchCommand(path: pending.path, method: pending.method, body: pending.body)
                    let gameID: UUID
                    if let known = pending.gameID { gameID = known }
                    else { gameID = try JSONDecoder().decode(SharedMatch.self, from: JSONEncoder().encode(result)).id }
                    let remote = try await fetchMatch(teamID: pending.teamID, gameID: gameID)
                    var completed = state
                    if pending.kind == "create", let sourceID = remote.wireLocalID {
                        if completed.publishedSources == nil { completed.publishedSources = [:] }
                        completed.publishedSources?["\(pending.teamID):\(sourceID)"] = remote.id
                        completed.acknowledgedRevisions[remote.id.uuidString] = remote.payload?.revision ?? 0
                    }
                    if let revision = pending.sentLocalRevision { completed.acknowledgedRevisions[pending.localID.uuidString] = revision }
                    completed.pending.removeAll { $0.id == pending.id }
                    if pending.kind == "drafts", case .object(let fields) = pending.body,
                       case .string(let rawID) = fields["conflictID"], let conflictID = UUID(uuidString: rawID) {
                        completed.submittedConflictIDs = Array(Set((completed.submittedConflictIDs ?? []) + [conflictID]))
                    }
                    if let token = pending.token { completed.lastInvitationURL = remote.viewURL.absoluteString + "/" + token }
                    try accept(remote, into: &completed, preserveNewerLocal: pending.sentLocalRevision != nil)
                    try persist(completed)
                    try install(remote, preserveNewerLocal: pending.sentLocalRevision != nil)
                } catch let e as TeamServiceError {
                    if case .response(let status, _) = e, [400,401,403,404,409,410,413,426].contains(status) {
                        var rejected = state
                        preserveDraft(pending.localID, reason: e.localizedDescription, into: &rejected)
                        rejected.pending.removeAll { $0.id == pending.id }; try persist(rejected)
                    }
                    throw e
                }
            }
            let targets = teamID.map { [$0] } ?? teams.teams.map(\.id)
            for team in targets where accessible.contains(team) { try await pull(team) }
            for match in state.matches where match.writerDeviceID == device && match.writerActive && match.liveEnabled && match.payload?.state.isFinal == false {
                let result = try await service.matchCommand(path: TeamServiceClient.matchPath(match.teamID, match.id) + "/live-heartbeat", method: "POST", body: .object([:]))
                var refreshed = try JSONDecoder().decode(SharedMatch.self, from: JSONEncoder().encode(result))
                refreshed.payload = match.payload; refreshed.wireLocalID = match.wireLocalID
                var next = state; try accept(refreshed, into: &next, preserveNewerLocal: true); try persist(next)
            }
            // A second edit made during an in-flight upload becomes a new operation based on its acknowledged revision.
            for game in store?.games ?? [] { try stageUpdate(game) }
            if !state.pending.isEmpty { requested = true }
        } catch { report(error) }
    }
    private func preserveDraft(_ localID: UUID?, reason: String, into next: inout SharedMatchLocalState) {
        guard let localID, let game = store?.games.first(where: { $0.id == localID }),
              !next.conflicts.contains(where: { $0.localID == localID }),
              next.matches.first(where: { $0.payload?.id == localID })?.writerDeviceID == next.deviceID || next.pending.contains(where: { $0.localID == localID && ["create", "update"].contains($0.kind) }),
              next.pending.contains(where: { $0.localID == localID }) || next.acknowledgedRevisions[localID.uuidString] != (game.revision ?? 0) else { return }
        next.conflicts.append(MatchConflict(id: UUID(), localID: localID, savedAt: Date(), game: game, reason: reason))
    }
    private func accept(_ remote: SharedMatch, into next: inout SharedMatchLocalState, preserveNewerLocal: Bool = false) throws {
        let remote = remote.isolated
        let old = next.matches.first(where: { $0.id == remote.id })
        if let old, remote.serverRevision < old.serverRevision || remote.writerEpoch < old.writerEpoch {
            throw TeamServiceError.message("收到较旧的比赛版本，已保留当前记录，请刷新。")
        }
        if remote.deleted, let localID = next.matches.first(where: { $0.id == remote.id })?.payload?.id {
            preserveDraft(localID, reason: "比赛已从共享球队删除", into: &next)
            next.pending.removeAll { $0.localID == localID && $0.kind == "update" }
        }
        if let localID = remote.payload?.id, !remote.canWrite {
            preserveDraft(localID, reason: "记分权已交给其他设备", into: &next)
        }
        if let payload = remote.payload {
            let hasUnsent = next.pending.contains { $0.localID == payload.id && $0.kind == "update" }
            let dirty = old != nil && store?.games.first(where: { $0.id == payload.id }).map {
                next.acknowledgedRevisions[payload.id.uuidString] != ($0.revision ?? 0)
            } == true
            if dirty && !preserveNewerLocal, let old,
               remote.serverRevision != old.serverRevision || remote.writerEpoch != old.writerEpoch {
                preserveDraft(payload.id, reason: "球队比赛已有新版本，本机未同步修改已保留", into: &next)
            }
            if !preserveNewerLocal && !hasUnsent && !dirty { next.acknowledgedRevisions[payload.id.uuidString] = payload.revision ?? 0 }
        }
        next.matches.removeAll { $0.id == remote.id }; next.matches.append(remote)
    }
    private func install(_ remote: SharedMatch, preserveNewerLocal: Bool = false) throws {
        let remote = remote.isolated
        if remote.deleted {
            for game in store?.games.filter({ $0.sharedGameID == remote.id }) ?? [] { try store?.removeSharedCopy(game.id) }
            return
        }
        guard var payload = remote.payload else { return }
        let localRevision = store?.games.first(where: { $0.id == payload.id })?.revision ?? 0
        let dirty = state.acknowledgedRevisions[payload.id.uuidString] != localRevision
        if remote.canWrite && !state.conflicts.contains(where: { $0.localID == payload.id })
            && (dirty && (preserveNewerLocal || localRevision > (payload.revision ?? 0)) || state.pending.contains(where: { $0.localID == payload.id && $0.kind == "update" })), let local = store?.games.first(where: { $0.id == payload.id }) {
            payload = local
        }
        payload.sharedTeamID = remote.teamID; payload.sharedGameID = remote.id
        payload.sharedPlayerLinks = remote.playerLinks; payload.historySourceID = remote.historySourceID
        if remote.historySourceID != nil, let existing = store?.games.first(where: { $0.id == payload.id }), existing.sharedGameID == nil {
            throw TeamServiceError.message("无法将这场共享比赛与本机比赛区分开，已停止导入。原有比赛仍会保留，请联系支持。")
        }
        try store?.installSharedCopy(payload)
    }
    private func pull(_ teamID: UUID) async throws {
        var more = true
        while more {
            let cursor = state.cursors[teamID.uuidString] ?? 0
            let page: SharedMatchPage
            do { page = try await service.matchPage(teamID: teamID, cursor: cursor) }
            catch TeamServiceError.response(let status, _) where status == 410 && cursor > 0 {
                var next = state
                for match in next.matches where match.teamID == teamID {
                    preserveDraft(match.payload?.id, reason: "重新核对完整球队资料前保留的本机修改", into: &next)
                }
                next.cursors[teamID.uuidString] = 0
                try persist(next)
                continue
            }
            guard page.recoveryEpoch == state.recoveryEpoch else { throw TeamServiceError.message("服务版本已变化，请重新核对。") }
            var next = state
            for summary in page.games {
                let remote = try await fetchMatch(teamID: teamID, gameID: summary.id)
                if remote.deleted, let old = next.matches.first(where: { $0.id == remote.id }), let localID = old.payload?.id {
                    preserveDraft(localID, reason: "比赛已从共享球队删除", into: &next)
                    next.pending.removeAll { $0.localID == localID }
                }
                try accept(remote, into: &next)
            }
            next.cursors[teamID.uuidString] = page.cursor
            // The page is complete and durable before any downloaded match becomes visible.
            try persist(next)
            for summary in page.games { if let remote = match(id: summary.id) { try install(remote) } }
            more = page.hasMore
        }
    }
    func refresh(_ match: SharedMatch) async {
        guard !busy else { return }
        busy = true; defer { busy = false }; message = nil
        do {
            let remote = try await fetchMatch(teamID: match.teamID, gameID: match.id)
            var next = state; try accept(remote, into: &next); try persist(next); try install(remote)
        } catch { report(error) }
    }
    func command(_ match: SharedMatch, resource: String, fields: [String: MatchJSON] = [:], token: String? = nil) async {
        guard loaded, !busy, !state.requiresRecoveryReview else { return }
        let localID = match.id
        await synchronize(teamID: match.teamID)
        guard message == nil, !state.pending.contains(where: { $0.localID == localID }), let current = self.match(id: match.id) else { return }
        do {
            if ["access", "viewers"].contains(resource), current.accessVersion != match.accessVersion {
                throw TeamServiceError.message("观看设置已有更新，请重新核对后操作。")
            }
            if resource == "handoff/force", current.serverRevision != match.serverRevision || current.writerEpoch != match.writerEpoch {
                throw TeamServiceError.message("比赛已有更新，请重新核对最后同步版本后接管。")
            }
            if resource == "player-links" || resource.hasPrefix("drafts/"), current.serverRevision != match.serverRevision || current.writerEpoch != match.writerEpoch {
                throw TeamServiceError.message("比赛已有更新，你的选择已保留，请刷新核对后再保存。")
            }
            var body = fields
            body["baseRevision"] = .number(Double(current.serverRevision)); body["writerEpoch"] = .number(Double(current.writerEpoch))
            body["baseAccessVersion"] = .number(Double(current.accessVersion))
            if let h = current.handoff { body["handoffID"] = .uuid(h.id); body["digest"] = .string(current.digest) }
            var next = state
            next.pending.append(operation(localID: localID, teamID: current.teamID, gameID: current.id,
                path: TeamServiceClient.matchPath(current.teamID,current.id) + "/" + resource, method: "POST", kind: resource, fields: body, token: token))
            try persist(next); await synchronize(teamID: current.teamID)
        } catch { report(error) }
    }
    func useOfficialVersion(_ conflict: MatchConflict) throws {
        guard let remote = match(localID: conflict.localID), let payload = remote.payload else { return }
        // Keep the preserved draft; resolution never republishes it automatically.
        var next = state; next.conflicts.removeAll { $0.id == conflict.id }
        next.resolvedConflicts = (next.resolvedConflicts ?? []) + [conflict]
        next.acknowledgedRevisions[payload.id.uuidString] = payload.revision ?? 0
        try store?.installSharedCopy({ var p = payload; p.sharedTeamID = remote.teamID; p.sharedGameID = remote.id; p.sharedPlayerLinks = remote.playerLinks; p.historySourceID = remote.historySourceID; return p }())
        try persist(next)
    }
    func submitConflict(_ conflict: MatchConflict, match: SharedMatch) async {
        do {
            var fields = try documentFields(conflict.game)
            fields["conflictID"] = .uuid(conflict.id); fields["reason"] = .string(conflict.reason)
            await command(match, resource: "drafts", fields: fields)
        } catch { report(error) }
    }
    func detachAfterRestore() {
        var next = state
        for match in next.matches { preserveDraft(match.payload?.id, reason: "已恢复本机备份，请核对球队已保存的记录", into: &next) }
        next.pending = []; next.cursors = [:]
        next.historyImport = nil // Restoring a backup never resumes an earlier import.
        next.requiresRecoveryReview = !next.matches.isEmpty || store?.games.contains(where: { $0.sharedGameID != nil }) == true
        let legacyIDs = (store?.games ?? []).filter { $0.sharedGameID != nil && $0.sharedGameID != $0.id }.map(\.id)
        if !legacyIDs.isEmpty { next.isolatedCopiesVersion = nil; next.legacyRestoreSourceIDs = legacyIDs; isolationReady = false }
        do {
            try persist(next)
            if !legacyIDs.isEmpty {
                try migrateIndependentCopies()
                var reviewed = state
                for match in reviewed.matches { preserveDraft(match.payload?.id, reason: "这份记录来自旧备份，请与球队已保存的记录核对", into: &reviewed) }
                try persist(reviewed)
            }
        } catch { loaded = false; report(error) }
    }
    /// Explicit recovery never replays pre-recovery mutations, even ones once acknowledged.
    /// Read and validate the whole accessible set before changing the durable local directory.
    func reconcileAfterRecovery() async -> Bool {
        guard loaded, !busy else { return false }
        busy = true; defer { busy = false }; message = nil
        do {
            let device = try await service.enroll(deviceName: "iPhone")
            let teams = try await service.teams()
            var remotes: [SharedMatch] = [], cursors: [String: Int] = [:]
            for team in teams.teams {
                var cursor = 0, more = true
                while more {
                    let page = try await service.matchPage(teamID: team.id, cursor: cursor)
                    guard page.recoveryEpoch == teams.recoveryEpoch else { throw TeamServiceError.message("服务资料仍在变化，请稍后重新核对。") }
                    for summary in page.games {
                        let remote = try await fetchMatch(teamID: team.id, gameID: summary.id)
                        remotes.removeAll { $0.id == remote.id }; remotes.append(remote)
                    }
                    cursor = page.cursor; more = page.hasMore
                }
                cursors[team.id.uuidString] = cursor
            }
            let finalTeams = try await service.teams()
            guard teams.recoveryEpoch == finalTeams.recoveryEpoch, teams.teams == finalTeams.teams else {
                throw TeamServiceError.message("球队授权在核对期间发生变化，请重新核对。")
            }
            var next = state
            for game in store?.games ?? [] {
                let original = state.matches.first { $0.payload?.id == game.id }
                let authored = original?.writerDeviceID == state.deviceID
                    || state.pending.contains { $0.localID == game.id && ["create", "update"].contains($0.kind) }
                if authored && !next.conflicts.contains(where: { $0.localID == game.id }) {
                    next.conflicts.append(MatchConflict(id: UUID(), localID: game.id, savedAt: Date(), game: game, reason: "恢复前本机记录，请与恢复后的球队比赛逐条核对"))
                }
            }
            next.matches = remotes; next.pending = []; next.historyImport = nil; next.lastInvitationURL = nil
            next.deviceID = device; next.recoveryEpoch = teams.recoveryEpoch; next.cursors = cursors
            next.teamAccessEpochs = Dictionary(uniqueKeysWithValues: teams.teams.map { ($0.id.uuidString, $0.accessEpoch ?? 1) })
            next.acknowledgedRevisions = Dictionary(uniqueKeysWithValues: remotes.compactMap { $0.payload.map { ($0.id.uuidString, $0.revision ?? 0) } })
            next.requiresRecoveryReview = true
            let previous = state.matches
            try persist(next)
            for old in previous where !remotes.contains(where: { $0.id == old.id }) {
                if let localID = old.payload?.id {
                    if let original = next.originalLocalGames?.first(where: { $0.id == localID }) { try store?.restorePreSharingCopy(original, replacing: old.id) }
                    else { try store?.removeSharedCopy(localID) }
                }
            }
            for remote in remotes { try install(remote) }
            return true
        } catch { report(error); return false }
    }
    func finishRecoveryReview() throws {
        var next = state; next.requiresRecoveryReview = false; try persist(next)
    }
    func report(_ error: Error) { message = (error as? TeamServiceError)?.localizedDescription ?? "共享资料未能保存，原记录已保留，请重试。" }
}

struct MatchPlayerLink: Codable, Equatable {
    let sourceID: UUID
    /// The official roster's stable source UUID, not a name or jersey number.
    let targetID: UUID
}
struct HistoricalImportCandidate: Codable, Identifiable {
    let id: UUID; let revision: Int; let deleted: Bool; let title: String; let date: String
    let awayScore: Int; let homeScore: Int
}
struct HistoricalImportPreview: Codable {
    let status: String; let token: String; let candidates: [HistoricalImportCandidate]
    var explanation: String {
        switch status {
        case "new": return "可以导入"
        case "duplicate": return "这场已在队内，跳过重复记录"
        case "conflict": return "这场比赛已导入，但两边内容不同。请打开球队中的比赛核对，不会自动覆盖。"
        case "deleted": return "这场比赛已从共享球队删除，重复导入不会恢复它"
        default: return "有同日同对手比赛，请核对是否为另一场"
        }
    }
}
struct HistoricalImportRow: Codable, Identifiable {
    var id: UUID { original.id }
    let original: StoredGame
    let copyID: UUID
    var preview: HistoricalImportPreview?
    var decision = "review"
    var status = "waiting"
    var pending: MatchJSON?
    var importedID: UUID?
}
struct HistoricalImportBatch: Codable, Identifiable {
    let id: UUID; let teamID: UUID; let sourceTeamID: UUID; let createdAt: Date
    var links: [MatchPlayerLink]
    var rows: [HistoricalImportRow]
    var backupURL: URL?
    var finished: Bool { rows.allSatisfy { $0.status != "waiting" } }
    var ready: Bool { rows.filter { $0.status == "waiting" }.allSatisfy { $0.pending != nil || ($0.decision == "skip" || ($0.preview != nil && $0.decision != "review")) } }
}

extension SharedMatchCoordinator {
    func historicalImportProfile(teamID: UUID) async throws -> SharedTeamProfile {
        _ = try await service.enroll(deviceName: "iPhone")
        let json = try await service.matchCommand(path: "teams/\(teamID.uuidString.lowercased())", method: "GET", body: .null)
        let detail = try JSONDecoder().decode(SharedTeamDetail.self, from: JSONEncoder().encode(json))
        guard detail.role != .member else { throw TeamServiceError.message("管理员或记录员可以导入本机历史比赛。") }
        return detail.team
    }
    private func importFields(_ row: HistoricalImportRow, batch: HistoricalImportBatch) throws -> [String: MatchJSON] {
        var fields = try documentFields(row.original)
        guard case .object(var payload) = fields["payload"], case .object(var projection) = fields["projection"] else { throw TeamServiceError.message("比赛资料无法读取。") }
        payload["id"] = .uuid(row.copyID); projection["gameID"] = .uuid(row.copyID)
        fields["payload"] = .object(payload); fields["projection"] = .object(projection)
        fields["sourceID"] = .uuid(row.id); fields["sourceTeamID"] = .uuid(batch.sourceTeamID)
        let side = [row.original.state.homeTeam, row.original.state.awayTeam].first { $0.id == batch.sourceTeamID }
        let ids = Set(side?.players.map(\.id) ?? [])
        fields["playerLinks"] = try .encode(batch.links.filter { ids.contains($0.sourceID) })
        return fields
    }
    func prepareHistoricalImport(_ games: [StoredGame], teamID: UUID, sourceTeamID: UUID, links: [MatchPlayerLink]) async {
        guard loaded, !busy, !importing, !state.requiresRecoveryReview else { return }
        guard state.historyImport == nil || state.historyImport?.finished == true else { message = "请先完成或结束上一批导入。"; return }
        guard !games.isEmpty, games.count <= 50, Set(games.map(\.id)).count == games.count,
              games.allSatisfy({ $0.status == .completed && $0.state.isFinal && $0.sharedGameID == nil && !isShared($0.id) }) else {
            message = "每批请选择 1 至 50 场已结束的本机比赛。"; return
        }
        busy = true; defer { busy = false }
        do {
            let profile = try await historicalImportProfile(teamID: teamID)
            guard Set(links.map(\.sourceID)).count == links.count,
                  links.allSatisfy({ link in profile.players.contains { $0.sourceID == link.targetID } }) else { throw TeamServiceError.message("请核对球员对应关系。") }
            var next = state
            next.historyImport = HistoricalImportBatch(id: UUID(), teamID: teamID, sourceTeamID: sourceTeamID, createdAt: Date(), links: links,
                rows: games.map { HistoricalImportRow(original: $0, copyID: UUID()) })
            try persist(next); busy = false
            await previewHistoricalImport()
        } catch { report(error) }
    }
    func previewHistoricalImport() async {
        guard loaded, !busy, !importing, let batch = state.historyImport, !state.requiresRecoveryReview else { return }
        busy = true; defer { busy = false }; message = nil
        do {
            _ = try await historicalImportProfile(teamID: batch.teamID)
            for row in batch.rows where row.status == "waiting" && row.pending == nil {
                let result = try await service.matchCommand(path: "teams/\(batch.teamID.uuidString.lowercased())/history-imports/preview", method: "POST", body: .object(try importFields(row, batch: batch)))
                let preview = try JSONDecoder().decode(HistoricalImportPreview.self, from: JSONEncoder().encode(result))
                var next = state
                guard next.historyImport?.id == batch.id, let i = next.historyImport?.rows.firstIndex(where: { $0.id == row.id }) else { return }
                next.historyImport?.rows[i].preview = preview
                next.historyImport?.rows[i].decision = preview.status == "new" ? "include" : ["duplicate", "deleted"].contains(preview.status) ? "skip" : "review"
                try persist(next)
            }
        } catch { report(error) }
    }
    func chooseHistoricalImport(_ rowID: UUID, decision: String) {
        guard !busy, !importing, ["include", "independent", "skip"].contains(decision), let batch = state.historyImport,
              let i = batch.rows.firstIndex(where: { $0.id == rowID }), batch.rows[i].pending == nil, batch.rows[i].status == "waiting" else { return }
        let preview = batch.rows[i].preview?.status
        guard decision == "skip" || (decision == "include" && preview == "new") || (decision == "independent" && preview == "similar") else { return }
        var next = state; next.historyImport?.rows[i].decision = decision
        do { try persist(next) } catch { report(error) }
    }
    func pauseHistoricalImport() { cancelImportRequested = true }
    func endHistoricalImport() {
        guard !busy, !importing else { return }
        guard state.historyImport?.rows.contains(where: { $0.pending != nil }) != true else { message = "上一场的上传结果尚待确认，请联网续传核对后再结束。"; return }
        var next = state; next.historyImport = nil
        do { try persist(next); message = nil } catch { report(error) }
    }
    func resumeHistoricalImport() async {
        guard loaded, !busy, !importing, let batch = state.historyImport, batch.ready, !state.requiresRecoveryReview else { return }
        importing = true; busy = true; cancelImportRequested = false; message = nil
        defer { importing = false; busy = false }
        do {
            let device = try await service.enroll(deviceName: "iPhone"), teams = try await service.teams()
            guard state.historyImport?.id == batch.id else { return }
            guard teams.teams.contains(where: { $0.id == batch.teamID && $0.role != .member }) else { throw TeamServiceError.message("只有当前球队管理员或记录员可以继续导入。") }
            if let epoch = state.recoveryEpoch, epoch != teams.recoveryEpoch { throw TeamServiceError.message("服务恢复后需要重新核对，请先刷新球队。") }
            var enrolled = state; enrolled.deviceID = device; enrolled.recoveryEpoch = teams.recoveryEpoch; try persist(enrolled)
            guard state.historyImport?.id == batch.id else { return }
            for row in batch.rows where row.status == "imported" {
                if let remote = row.importedID.flatMap({ match(id: $0) }) { try install(remote) }
            }
            if batch.backupURL == nil && batch.rows.contains(where: { $0.status == "waiting" && $0.decision != "skip" }) {
                guard let store else { return }
                let exported = try store.exportBackup()
                let directory = (store.backupDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0])
                    .appendingPathComponent("HistoryImports/\(batch.id.uuidString)", isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
                let backup = directory.appendingPathComponent("导入前完整备份.bmbackup")
                if FileManager.default.fileExists(atPath: backup.path) { _ = try LocalBackup.read(backup) }
                else { try FileManager.default.copyItem(at: exported, to: backup); _ = try LocalBackup.read(backup) }
                var saved = state; saved.historyImport?.backupURL = backup; try persist(saved)
            }
            if let backup = state.historyImport?.backupURL { _ = try LocalBackup.read(backup) }
            for rowID in batch.rows.map(\.id) {
                if cancelImportRequested { message = "已暂停，完成的比赛保留，可稍后继续。"; break }
                guard let current = state.historyImport, current.id == batch.id else { return }
                guard let i = current.rows.firstIndex(where: { $0.id == rowID }), current.rows[i].status == "waiting" else { continue }
                let row = current.rows[i]
                if row.decision == "skip", row.pending == nil {
                    var next = state; next.historyImport?.rows[i].status = "skipped"; try persist(next); continue
                }
                if row.pending == nil {
                    guard let preview = row.preview else { throw TeamServiceError.message("请先重新预览核对。") }
                    var body = try importFields(row, batch: current)
                    body["operationID"] = .uuid(UUID()); body["previewToken"] = .string(preview.token); body["independent"] = .bool(row.decision == "independent")
                    var next = state; next.historyImport?.rows[i].pending = .object(body); try persist(next)
                }
                do {
                    let pending = state.historyImport!.rows[i].pending!
                    let result = try await service.matchCommand(path: "teams/\(current.teamID.uuidString.lowercased())/history-imports/commit", method: "POST", body: pending)
                    let receipt = try JSONDecoder().decode(SharedMatch.self, from: JSONEncoder().encode(result))
                    let remote = try await fetchMatch(teamID: current.teamID, gameID: receipt.id)
                    guard state.historyImport?.id == current.id else { return }
                    var next = state; next.historyImport?.rows[i].status = "imported"; next.historyImport?.rows[i].pending = nil; next.historyImport?.rows[i].importedID = remote.id
                    try accept(remote, into: &next); try persist(next); try install(remote)
                } catch let error as TeamServiceError {
                    guard state.historyImport?.id == current.id else { return }
                    if case .response(let status, _) = error, [400,401,403,404,409,413,426].contains(status) {
                        var next = state; next.historyImport?.rows[i].pending = nil; next.historyImport?.rows[i].preview = nil; next.historyImport?.rows[i].decision = "review"; try persist(next)
                    }
                    throw error
                }
            }
            if state.historyImport?.finished == true { message = "本批导入已完成，球队成员可以查看。队外人员暂时不能通过历史回看网页观看，可另行设置。" }
        } catch { report(error) }
    }
}

struct PlayerLinkChange: Decodable {
    let revision: Int
    let occurredAt: Double
    let nickname: String
    let deviceName: String
    let before: [MatchPlayerLink]
    let after: [MatchPlayerLink]
}
struct PlayerLinkHistory: Decodable { let changes: [PlayerLinkChange] }
