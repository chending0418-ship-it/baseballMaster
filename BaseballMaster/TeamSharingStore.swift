import Foundation
import Combine
import CryptoKit

enum TeamInvitationLink {
    static func token(from input: String) -> String? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let token: String
        if let url = URL(string: value), url.scheme != nil {
            let isWeb = url.scheme == "https" && url.host == "baseballmaster.cc" && ["/join", "/join/"].contains(url.path)
            let isApp = url.scheme == "baseballmaster" && url.host == "join" && url.path.isEmpty
            guard (isWeb || isApp), url.user == nil, url.password == nil, url.port == nil, url.query == nil,
                  let fragment = url.fragment else { return nil }
            token = fragment
        } else { token = value }
        guard token.count == 43, token.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil,
              let data = Data(base64Encoded: token.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + "="),
              data.count == 32, data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") == token else { return nil }
        return token
    }
    static func url(token: String) -> URL { URL(string: "https://baseballmaster.cc/join#\(token)")! }
}

enum SharedTeamCommand: Codable {
    case create(name: String, nickname: String, localTeamID: UUID?, profile: SharedRosterSeed?, recoveryToken: String? = nil)
    case security(TeamSecurityCommand)
    case roster(SharedRosterChange)
    case join(token: String, nickname: String)
    case invite(teamID: UUID, role: SharedTeamRole, token: String)
    case fixedInvite(teamID: UUID, rotate: Bool)
    case revoke(teamID: UUID, invitationID: UUID)
    case member(teamID: UUID, member: SharedTeamMember, role: SharedTeamRole, remove: Bool)
    case leave(teamID: UUID)

    var title: String {
        switch self {
        case .create: return "创建共享球队"
        case .security(let command): return command.title
        case .roster: return "更新球队名单"
        case .join: return "加入球队"
        case .invite: return "创建邀请"
        case .fixedInvite: return "更新球队邀请链接"
        case .revoke: return "撤销邀请"
        case .member: return "更新成员"
        case .leave: return "退出球队"
        }
    }
}
struct PendingTeamCommand: Codable { let id: UUID; let command: SharedTeamCommand }
struct SharedTeamBinding: Codable { let localTeamID: UUID; let cloudTeamID: UUID }
struct SavedTeamInvitation: Codable {
    let teamID: UUID; let invitation: SharedTeamInvitation; let token: String
}
struct TeamDirectoryState: Codable {
    var teams: [SharedTeamSummary] = []
    var bindings: [SharedTeamBinding] = []
    var pending: PendingTeamCommand?
    var fixedInvitations: [SavedTeamInvitation]? = nil
    var lastInvitation: SavedTeamInvitation?
    var recoveryEpoch: UUID?
    var lastRefreshed: Date?
    var requiresRecoveryReview = false
    var recoveryCards: [SavedRecoveryCard]? = nil
    var pairing: SavedDevicePairing? = nil
    var outgoingPairing: DevicePairing? = nil
    var teamAccessEpochs: [String: Int]? = nil
    var lastCreatedTeamID: UUID? = nil
    var lastSecurityReceipt: TeamSecurityReceipt? = nil
    var profiles: [SharedTeamProfile]? = nil
    var rosterDraft: SharedRosterChange? = nil
}
protocol TeamDirectoryStorage {
    func load() throws -> TeamDirectoryState
    func save(_ state: TeamDirectoryState) throws
}

/// Device-bound, encrypted, excluded from OS backup and from the app's roster backup.
/// Pending invitation secrets must be durable before the request, but must never enter exports.
struct TeamDirectoryFile: TeamDirectoryStorage {
    let url: URL
    let vault: TeamCredentialStorage
    private func key() throws -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(try vault.encryptionSecret().utf8)),
            salt: Data("BaseballMaster.team-directory.v1".utf8), info: Data("local-state".utf8), outputByteCount: 32)
    }
    func load() throws -> TeamDirectoryState {
        guard FileManager.default.fileExists(atPath: url.path) else { return TeamDirectoryState() }
        let sealed = try AES.GCM.SealedBox(combined: Data(contentsOf: url))
        return try JSONDecoder().decode(TeamDirectoryState.self, from: AES.GCM.open(sealed, using: key()))
    }
    func save(_ state: TeamDirectoryState) throws {
        var directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let bytes = try JSONEncoder().encode(state)
        let encrypted = try AES.GCM.seal(bytes, using: key()).combined!
        try encrypted.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

@MainActor
final class TeamSharingStore: ObservableObject {
    @Published private(set) var state = TeamDirectoryState()
    @Published private(set) var isBusy = false
    @Published var errorMessage: String?
    @Published private(set) var credentialExpired = false
    @Published var incomingInvitation: String?
    @Published var incomingPairing: String?
    let service: TeamDirectoryService
    private let storage: TeamDirectoryStorage
    @Published private(set) var foregroundGeneration = 0
    private var foregroundActive = false
    private var visitedPages: Set<String> = []
    var detailCache: [UUID: (SharedTeamDetail, [SharedTeamMember], [SharedTeamInvitation])] = [:]
    func setForeground(_ active: Bool) {
        if active && !foregroundActive { foregroundGeneration += 1; visitedPages.removeAll(); onChange?() }
        foregroundActive = active
    }
    func needsRefresh(_ page: String) -> Bool { visitedPages.insert(page).inserted }
    func connectOnce() async { if needsRefresh("directory") { await connect() } }
    private var loaded = false
    var onChange: (() -> Void)?
    var sharedRosterTeams: [Team] {
        (state.profiles ?? []).filter { profile in teams.contains { $0.id == profile.id } }.map(\.localTeam)
    }
    var teams: [SharedTeamSummary] { state.teams }
    var canMutate: Bool { loaded && !isBusy && state.pending == nil && !state.requiresRecoveryReview }
    var pendingTitle: String? { state.pending?.command.title }

    init(service: TeamDirectoryService, storage: TeamDirectoryStorage) { self.service = service; self.storage = storage }

    static func connectionConfiguration() -> (baseURL: URL, vault: TeamDeviceKeychain, url: URL) {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var vault = TeamDeviceKeychain()
        var baseURL = TeamServiceClient.productionURL
        var filename = "directory-v1"
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--v30-sharing-preview") {
            let run = ProcessInfo.processInfo.arguments.first { $0.hasPrefix("--team-test-id=") }?
                .dropFirst("--team-test-id=".count).description ?? "preview"
            let safeRun = UUID(uuidString: run)?.uuidString ?? "preview"
            vault = TeamDeviceKeychain(account: "ui-" + safeRun)
            filename = "test-" + safeRun
            baseURL = URL(string: "http://127.0.0.1:18788/api/v3")!
        }
        #endif
        // Both URLs above are compile-time controlled, never supplied by invitation links.
        return (baseURL, vault, root.appendingPathComponent("TeamSharing/\(filename).sealed"))
    }
    static func deviceStore() -> TeamSharingStore {
        let c = connectionConfiguration()
        return TeamSharingStore(service: try! TeamServiceClient(baseURL: c.baseURL, vault: c.vault),
            storage: TeamDirectoryFile(url: c.url, vault: c.vault))
    }

    func persist(_ next: TeamDirectoryState) throws { try storage.save(next); state = next; onChange?() }
    func loadCachedDirectory() { do { try loadIfNeeded() } catch { report(error) } }
    private func loadIfNeeded() throws {
        guard !loaded else { return }
        do { state = try storage.load(); loaded = true; onChange?() }
        catch { throw TeamServiceError.message("本机共享记录暂时无法读取，请解锁设备后重试。原资料已保留。") }
    }
    private func refreshList() async throws {
        let remote = try await service.teams()
        var next = state
        if let previous = next.recoveryEpoch, previous != remote.recoveryEpoch {
            next.teams = []; next.lastInvitation = nil; next.fixedInvitations = nil; next.requiresRecoveryReview = true
            try persist(next)
            throw TeamServiceError.message("球队服务已恢复资料，需要管理员核对后再继续共享。待处理记录已保留。")
        }
        for team in remote.teams {
            if let old = next.teamAccessEpochs?[team.id.uuidString], old != (team.accessEpoch ?? 1) {
                if next.pending?.command.teamID == team.id {
                    if case .security(let command) = next.pending?.command, command.kind == "recovery-redeem" { }
                    else { next.pending = nil }
                }
                next.fixedInvitations?.removeAll { $0.teamID == team.id }
                next.recoveryCards?.removeAll { $0.teamID == team.id }
                if next.lastInvitation?.teamID == team.id { next.lastInvitation = nil }
            }
        }
        next.teamAccessEpochs = Dictionary(uniqueKeysWithValues: remote.teams.map { ($0.id.uuidString, $0.accessEpoch ?? 1) })
        next.recoveryCards?.removeAll { card in !remote.teams.contains { $0.id == card.teamID && $0.role == .admin && ($0.recoveryVersion == nil || $0.recoveryVersion == card.version) } }
        if let pair = next.outgoingPairing, !remote.teams.contains(where: { $0.id == pair.teamID && $0.memberID == pair.memberID }) { next.outgoingPairing = nil }
        next.profiles?.removeAll { profile in !remote.teams.contains { $0.id == profile.id } }
        if let draft = next.rosterDraft, !remote.teams.contains(where: { $0.id == draft.teamID && (draft.kind == "player" ? $0.role.canEditPlayers : $0.role == .admin) }) { next.rosterDraft = nil }
        next.fixedInvitations?.removeAll { saved in !remote.teams.contains { $0.id == saved.teamID && $0.role == .admin } }
        next.teams = remote.teams; next.recoveryEpoch = remote.recoveryEpoch; next.lastRefreshed = Date()
        let accessible = Set(remote.teams.map(\.id))
        next.bindings.removeAll { !accessible.contains($0.cloudTeamID) }
        if let invitation = next.lastInvitation,
           !remote.teams.contains(where: { $0.id == invitation.teamID && $0.role == .admin }) { next.lastInvitation = nil }
        try persist(next)
    }
    func connect() async {
        guard !isBusy else { return }
        isBusy = true; errorMessage = nil; defer { isBusy = false }
        do {
            try loadIfNeeded()
            guard !state.requiresRecoveryReview else { throw TeamServiceError.message("请联系管理员核对恢复后的球队资料。") }
            _ = try await service.enroll(deviceName: "iPhone")
            try await refreshList()
            for team in state.teams where !(state.profiles ?? []).contains(where: { $0.id == team.id && $0.revision == team.revision }) {
                let detail = try await service.detail(teamID: team.id)
                try cacheProfile(detail.team)
            }
        } catch { report(error) }
    }
    func reconcileAfterRecovery() async -> Bool {
        guard !isBusy else { return false }
        isBusy = true; defer { isBusy = false }; errorMessage = nil
        do {
            try loadIfNeeded()
            _ = try await service.enroll(deviceName: "iPhone")
            let remote = try await service.teams()
            var profiles: [SharedTeamProfile] = []
            for team in remote.teams { profiles.append(try await service.detail(teamID: team.id).team) }
            let final = try await service.teams()
            guard final.recoveryEpoch == remote.recoveryEpoch, final.teams == remote.teams else { throw TeamServiceError.message("球队资料仍在变化，请重新核对。") }
            if let api = service as? TeamSecurityService {
                _ = try await api.securityRequest(path: "devices/recovery-review", method: "POST", body: .object(["recoveryEpoch": .uuid(remote.recoveryEpoch)]))
            }
            var next = state
            if case .roster(let draft) = next.pending?.command { next.rosterDraft = draft }
            next.pending = nil; next.lastInvitation = nil; next.fixedInvitations = nil; next.pairing = nil; next.outgoingPairing = nil
            next.teams = remote.teams; next.profiles = profiles; next.recoveryEpoch = remote.recoveryEpoch
            next.teamAccessEpochs = Dictionary(uniqueKeysWithValues: remote.teams.map { ($0.id.uuidString,$0.accessEpoch ?? 1) })
            next.recoveryCards = nil; next.lastSecurityReceipt = nil
            next.bindings.removeAll { binding in !remote.teams.contains { $0.id == binding.cloudTeamID } }
            next.requiresRecoveryReview = false; next.lastRefreshed = Date(); try persist(next)
            return true
        } catch { report(error); return false }
    }

    /// Exactly one durable operation may be outstanding. Input cannot change while its outcome is unknown.
    @discardableResult func submit(_ command: SharedTeamCommand) async -> Bool {
        guard canMutate else { return false }
        if case .roster(let change) = command, let draft = state.rosterDraft,
           draft.teamID != change.teamID || draft.kind != change.kind || draft.player?.id != change.player?.id {
            errorMessage = "你还有一份名单修改尚未保存，请先核对并保存，再修改其他资料。"; return false
        }
        if case .create(_, _, let localID?, _, _) = command,
           state.bindings.contains(where: { $0.localTeamID == localID }) {
            errorMessage = "这支本机球队已经关联共享球队，请打开已有球队。"; return false
        }
        do {
            var prepared = command
            if case let .create(name, nickname, id, profile, token) = command {
                prepared = .create(name: name, nickname: nickname, localTeamID: id, profile: profile, recoveryToken: try token ?? TeamDeviceKeychain.randomSecret())
            }
            var next = state; next.pending = PendingTeamCommand(id: UUID(), command: prepared); try persist(next)
        }
        catch { errorMessage = "操作尚未发送：本机未能保存，请检查存储空间后重试。"; return false }
        return await retryPending()
    }
    @discardableResult func retryPending() async -> Bool {
        guard !isBusy, let pending = state.pending, !state.requiresRecoveryReview else { return false }
        isBusy = true; errorMessage = nil; defer { isBusy = false }
        do {
            _ = try await service.enroll(deviceName: "iPhone")
            // Re-check the recovery generation before resending a persisted operation.
            try await refreshList()
            guard state.pending?.id == pending.id else { throw TeamServiceError.message("球队管理授权已恢复，原操作已停止，请重新核对。") }
            var next = state
            switch pending.command {
            case let .create(name, nickname, localID, profile, recoveryToken):
                let result = try await service.createTeam(name: name, nickname: nickname, operationID: pending.id, profile: profile, recoveryToken: recoveryToken)
                next.lastCreatedTeamID = result.teamID
                if let token = recoveryToken {
                    next.recoveryCards = (next.recoveryCards ?? []).filter { $0.teamID != result.teamID }
                    next.recoveryCards?.append(SavedRecoveryCard(teamID: result.teamID, teamName: name, token: token, version: result.recoveryVersion ?? 1))
                }
                if let localID { next.bindings.append(SharedTeamBinding(localTeamID: localID, cloudTeamID: result.teamID)) }
            case let .roster(change):
                guard let api = service as? TeamRosterService else { throw TeamServiceError.message("名单服务暂不可用。") }
                let detail = try await api.updateRoster(change, operationID: pending.id)
                next = state
                if !(next.profiles ?? []).contains(where: { $0.id == detail.team.id && $0.revision > detail.team.revision }) {
                    next.profiles = (next.profiles ?? []).filter { $0.id != detail.team.id } + [detail.team]
                }
                next.rosterDraft = nil
            case let .security(command):
                guard let api = service as? TeamSecurityService else { throw TeamServiceError.message("设备服务暂不可用。") }
                var fields = command.fields; fields["operationID"] = .uuid(pending.id)
                let result = try await api.securityRequest(path: command.path, method: command.method, body: .object(fields))
                next = try next.applying(command, result: result)
            case let .join(token, nickname):
                _ = try await service.join(token: token, nickname: nickname, operationID: pending.id)
            case let .invite(teamID, role, token):
                let result = try await service.invite(teamID: teamID, role: role, token: token, operationID: pending.id)
                next.lastInvitation = SavedTeamInvitation(teamID: teamID, invitation: result, token: token)
            case let .fixedInvite(teamID, rotate):
                guard let api = service as? TeamSecurityService else { throw TeamServiceError.message("邀请服务暂不可用。") }
                let json = try await api.securityRequest(path: "teams/\(teamID.uuidString.lowercased())/team-invitation", method: "POST",
                    body: .object(["operationID": .uuid(pending.id), "rotate": .bool(rotate)]))
                let saved = try JSONDecoder().decode(SavedTeamInvitation.self, from: JSONEncoder().encode(json))
                next.fixedInvitations = (next.fixedInvitations ?? []).filter { $0.teamID != teamID } + [saved]
            case let .revoke(teamID, invitationID):
                try await service.revoke(teamID: teamID, invitationID: invitationID, operationID: pending.id)
                if next.lastInvitation?.invitation.id == invitationID { next.lastInvitation = nil }
            case let .member(teamID, member, role, remove):
                try await service.changeMember(teamID: teamID, member: member, role: role, remove: remove, operationID: pending.id)
                // Do not leave stale management UI enabled while a subsequent refresh is offline.
                next.teams.removeAll { $0.id == teamID }
                next.lastInvitation = nil
            case let .leave(teamID):
                try await service.leave(teamID: teamID, operationID: pending.id)
                next.teams.removeAll { $0.id == teamID }; next.bindings.removeAll { $0.cloudTeamID == teamID }
                if next.lastInvitation?.teamID == teamID { next.lastInvitation = nil }
            }
            next.pending = nil
            try persist(next)
            // A refresh failure must not falsely say the already acknowledged mutation failed.
            do { try await refreshList() } catch { report(error) }
            return true
        } catch {
            if let problem = error as? TeamServiceError, !problem.isRetryable {
                var next = state; next.pending = nil
                if case .roster(let change) = pending.command, !problem.endsAccess { next.rosterDraft = change }
                if problem.endsAccess { next.teams = []; next.lastInvitation = nil }
                do { try persist(next) } catch { errorMessage = "结果未能保存，待处理操作已保留，请重试。"; return false }
            }
            report(error); return false
        }
    }
    private func report(_ error: Error) {
        if case TeamServiceError.response(401, _) = error { credentialExpired = true }
        if let problem = error as? TeamServiceError, problem.endsAccess {
            var next = state; next.teams = []; next.lastInvitation = nil
            // Hide stale authorization immediately, even when disk persistence also fails.
            state = next; onChange?()
            try? storage.save(next)
        }
        if error is URLError { errorMessage = state.pending == nil ? "网络暂不可用，正在显示上次同步的资料。" : "网络暂不可用，修改已保存在本机，联网后请重试。"; return }
        errorMessage = (error as? TeamServiceError)?.localizedDescription ?? "本机共享记录未能保存，请检查存储空间后重试。"
    }
    func reconnectExpiredDevice() async -> Bool {
        guard credentialExpired, !isBusy, let client = service as? TeamServiceClient else { return false }
        isBusy = true; defer { isBusy = false }
        do {
            var next = state; next.pending = nil; next.lastInvitation = nil; next.fixedInvitations = nil; next.pairing = nil; next.outgoingPairing = nil
            next.recoveryCards = nil; next.lastSecurityReceipt = nil; next.requiresRecoveryReview = true; try persist(next)
            try await client.reconnectWithNewCredential()
            credentialExpired = false; errorMessage = nil; return true
        } catch { report(error); return false }
    }
    func previewInvitation(_ input: String) async -> SharedInvitationPreview? {
        guard let token = TeamInvitationLink.token(from: input), !isBusy else {
            errorMessage = "邀请内容不正确，请使用球队管理员发来的完整邀请链接。"; return nil
        }
        await connect()
        guard loaded, !state.requiresRecoveryReview, errorMessage == nil else { return nil }
        do { return try await service.preview(token: token) } catch { report(error); return nil }
    }
    private func cacheProfile(_ profile: SharedTeamProfile) throws {
        if let existing = state.profiles?.first(where: { $0.id == profile.id }), existing.revision > profile.revision { return }
        var next = state; next.profiles = (next.profiles ?? []).filter { $0.id != profile.id } + [profile]
        try persist(next)
    }
    func cachedDetail(teamID: UUID) -> SharedTeamDetail? {
        guard let team = teams.first(where: { $0.id == teamID }), let profile = state.profiles?.first(where: { $0.id == teamID }) else { return nil }
        return SharedTeamDetail(team: profile, role: team.role, memberID: team.memberID)
    }
    func details(teamID: UUID) async -> (SharedTeamDetail, [SharedTeamMember], [SharedTeamInvitation])? {
        guard loaded, !state.requiresRecoveryReview else { return nil }
        do {
            let detail = try await service.detail(teamID: teamID)
            try cacheProfile(detail.team)
            // The detail response is authoritative for this member too. Keep navigation and
            // cached role gates in step with an administrator's change on another device.
            var current = state
            let previous = current.teams.first { $0.id == teamID }
            current.teams.removeAll { $0.id == teamID }
            current.teams.append(SharedTeamSummary(id: teamID, name: detail.team.name, revision: detail.team.revision,
                role: detail.role, memberID: detail.memberID, accessEpoch: previous?.accessEpoch,
                recoveryVersion: detail.role == .admin ? previous?.recoveryVersion : nil))
            if detail.role != .admin {
                current.recoveryCards?.removeAll { $0.teamID == teamID }
                if current.lastInvitation?.teamID == teamID { current.lastInvitation = nil }
            }
            try persist(current)
            let members = detail.role == .admin ? try await service.members(teamID: teamID) : []
            let invitations = detail.role == .admin ? try await service.invitations(teamID: teamID) : []
            if let saved = state.lastInvitation, saved.teamID == teamID, !invitations.contains(where: { $0.id == saved.invitation.id }) {
                var next = state; next.lastInvitation = nil; try persist(next)
            }
            detailCache[teamID] = (detail, members, invitations)
            return (detail, members, invitations)
        } catch {
            if let problem = error as? TeamServiceError, problem.endsAccess {
                var next = state; next.teams.removeAll { $0.id == teamID }
                if next.lastInvitation?.teamID == teamID { next.lastInvitation = nil }
                try? persist(next)
            }
            report(error); return nil
        }
    }
}
