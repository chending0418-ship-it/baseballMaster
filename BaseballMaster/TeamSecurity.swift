import Foundation

struct PairedTeamDevice: Codable, Identifiable {
    let id: UUID; let label: String; let memberID: UUID; let nickname: String; let role: SharedTeamRole
    let revision: Int; let isCurrent: Int
}
struct DevicePairing: Codable, Identifiable {
    let id: UUID; let status: String; let expiresAt: Double; let phrase: String
    let targetLabel: String?; let teamID: UUID?; let teamName: String?; let memberID: UUID?
    let nickname: String?; let role: SharedTeamRole?; let memberRevision: Int?
    var isPending: Bool { ["pending", "offered"].contains(status) && expiresAt > Date().timeIntervalSince1970 * 1000 }
}
struct TeamSecurityReceipt: Codable, Identifiable { let id: UUID; let kind: String; let teamID: UUID?; let pairingID: UUID? }
struct SavedDevicePairing: Codable { let token: String; var request: DevicePairing }
struct TeamRecoveryStatus: Codable { let teamID: UUID; let teamName: String; let version: Int; var hasCode: Bool? = nil }
struct SavedRecoveryCard: Codable, Identifiable {
    let teamID: UUID; let teamName: String; let token: String; let version: Int
    var confirmedSaved = false
    var id: UUID { teamID }
    var code: String { "BMREC1.\(teamID.uuidString.lowercased()).\(token)" }
    var text: String { "BaseballMaster 球队管理恢复卡\n球队：\(teamName)\n版本：\(version)\n\n\(code)\n\n请离线妥善保存，勿发到球队群。持此卡可恢复管理权，原设备、邀请与观看授权将失效，比赛保留。更换恢复码后旧卡失效。" }
    static func parse(_ input: String) -> (teamID: UUID, token: String)? {
        let pattern = "BMREC1\\.([A-Fa-f0-9-]{36})\\.([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let matches = regex.matches(in: input, range: NSRange(input.startIndex..., in: input))
        guard matches.count == 1, let teamRange = Range(matches[0].range(at: 1), in: input),
              let tokenRange = Range(matches[0].range(at: 2), in: input), let id = UUID(uuidString: String(input[teamRange])),
              let token = TeamInvitationLink.token(from: String(input[tokenRange])) else { return nil }
        return (id, token)
    }
}
enum DevicePairingLink {
    static func token(from input: String) -> String? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: value), url.scheme != nil else { return TeamInvitationLink.token(from: value) }
        let web = url.scheme == "https" && url.host == "baseballmaster.cc" && ["/pair", "/pair/"].contains(url.path)
        let app = url.scheme == "baseballmaster" && url.host == "pair" && url.path.isEmpty
        guard (web || app), url.query == nil, url.user == nil, url.password == nil, url.port == nil, let fragment = url.fragment else { return nil }
        return TeamInvitationLink.token(from: fragment)
    }
    static func url(_ token: String) -> URL { URL(string: "https://baseballmaster.cc/pair#\(token)")! }
}
struct TeamSecurityCommand: Codable {
    let path: String; let method: String; let fields: [String: MatchJSON]; let kind: String
    var teamID: UUID? = nil
    var newToken: String? = nil
    var teamName: String? = nil
    var title: String {
        switch kind {
        case "pair-create": return "建立配对请求"
        case "pair-approve": return "授权新设备"
        case "pair-accept": return "完成设备配对"
        case "pair-cancel": return "取消配对"
        case "device-remove": return "移除设备"
        case "recovery-rotate": return "更换管理恢复码"
        case "team-dissolve": return "解散球队"
        case "team-restore": return "恢复已解散球队"
        default: return "恢复球队管理权"
        }
    }
}
protocol TeamSecurityService {
    func securityRequest(path: String, method: String, body: MatchJSON?) async throws -> MatchJSON
}
extension TeamServiceClient: TeamSecurityService {
    func securityRequest(path: String, method: String, body: MatchJSON?) async throws -> MatchJSON {
        try await request(method, path: path, body: body)
    }
}
extension SharedTeamCommand {
    var teamID: UUID? {
        switch self {
        case .create, .join: return nil
        case .fixedInvite(let id, _), .invite(let id, _, _), .revoke(let id, _), .member(let id, _, _, _), .leave(let id): return id
        case .security(let command): return command.teamID
        case .roster(let change): return change.teamID
        }
    }
}
extension TeamDirectoryState {
    func applying(_ command: TeamSecurityCommand, result: MatchJSON) throws -> TeamDirectoryState {
        var next = self
        let data = try JSONEncoder().encode(result)
        switch command.kind {
        case "pair-create":
            let request = try JSONDecoder().decode(DevicePairing.self, from: data)
            if let token = command.newToken { next.pairing = SavedDevicePairing(token: token, request: request) }
        case "pair-accept":
            let request = try JSONDecoder().decode(DevicePairing.self, from: data)
            if next.pairing?.request.id == request.id { next.pairing?.request = request }
        case "pair-approve": next.outgoingPairing = try JSONDecoder().decode(DevicePairing.self, from: data)
        case "pair-cancel":
            let id = command.path.split(separator: "/").dropFirst().first.flatMap { UUID(uuidString: String($0)) }
            if next.pairing?.request.id == id { next.pairing = nil }
            if next.outgoingPairing?.id == id { next.outgoingPairing = nil }
        case "recovery-rotate", "recovery-redeem":
            let status = try JSONDecoder().decode(TeamRecoveryStatus.self, from: data)
            if let token = command.newToken {
                next.recoveryCards = (next.recoveryCards ?? []).filter { $0.teamID != status.teamID }
                next.recoveryCards?.append(SavedRecoveryCard(teamID: status.teamID, teamName: status.teamName, token: token, version: status.version))
            }
            if command.kind == "recovery-redeem" {
                next.lastInvitation = nil; next.pairing = nil; next.outgoingPairing = nil
                next.teamAccessEpochs?.removeValue(forKey: status.teamID.uuidString)
            }
        case "device-remove": next.lastInvitation = nil
        case "team-dissolve":
            if let teamID = command.teamID {
                next.teams.removeAll { $0.id == teamID }; next.profiles?.removeAll { $0.id == teamID }
                next.bindings.removeAll { $0.cloudTeamID == teamID }
                next.recoveryCards?.removeAll { $0.teamID == teamID }
                if next.lastInvitation?.teamID == teamID { next.lastInvitation = nil }
            }
        default: break
        }
        let pair = try? JSONDecoder().decode(DevicePairing.self, from: data)
        next.lastSecurityReceipt = TeamSecurityReceipt(id: UUID(), kind: command.kind, teamID: command.teamID, pairingID: pair?.id)
        return next
    }
}
extension TeamSharingStore {
    func securityRead<T: Decodable>(_ path: String, method: String = "GET", fields: [String: MatchJSON]? = nil) async throws -> T {
        guard let api = service as? TeamSecurityService, !state.requiresRecoveryReview else { throw TeamServiceError.message("请先联网核对球队授权。") }
        let response = try await api.securityRequest(path: path, method: method, body: fields.map(MatchJSON.object))
        let value = try JSONDecoder().decode(T.self, from: JSONEncoder().encode(response))
        if state.pending == nil { errorMessage = nil }
        return value
    }
    func teamDevices(_ teamID: UUID) async throws -> [PairedTeamDevice] {
        struct Result: Decodable { let devices: [PairedTeamDevice] }
        let result: Result = try await securityRead("teams/\(teamID.uuidString.lowercased())/devices")
        return result.devices
    }
    func recoveryStatus(_ teamID: UUID) async throws -> TeamRecoveryStatus {
        let result: TeamRecoveryStatus = try await securityRead("teams/\(teamID.uuidString.lowercased())/recovery")
        // A read started before rotation can arrive after its new card was saved.
        // Keep the newer secret; a delayed read must never erase the recovery path.
        if let card = state.recoveryCards?.first(where: { $0.teamID == teamID }), card.version > result.version {
            throw TeamServiceError.message("恢复码已更新，请刷新后查看新卡。")
        }
        if state.recoveryCards?.contains(where: { $0.teamID == teamID && $0.version != result.version }) == true {
            var next = state; next.recoveryCards?.removeAll { $0.teamID == teamID }; try persist(next)
        }
        return result
    }
    func confirmCardSaved(_ teamID: UUID) {
        var next = state
        if let i = next.recoveryCards?.firstIndex(where: { $0.teamID == teamID }) { next.recoveryCards?[i].confirmedSaved = true }
        do { try persist(next) } catch { errorMessage = "保存状态未能记录，请重试。" }
    }
    func refreshPairing() async {
        guard !isBusy, let current = state.pairing else { return }
        do {
            let result: DevicePairing = try await securityRead("pairings/\(current.request.id.uuidString.lowercased())")
            guard state.pairing?.request.id == result.id, !isBusy else { return }
            var next = state; next.pairing?.request = result; try persist(next)
        } catch { errorMessage = error.localizedDescription }
    }
    func refreshOutgoingPairing() async {
        guard let pair = state.outgoingPairing, !isBusy else { return }
        do {
            let result: DevicePairing = try await securityRead("pairings/\(pair.id.uuidString.lowercased())")
            var next = state
            if next.outgoingPairing?.id == pair.id { next.outgoingPairing = result.isPending ? result : nil; try persist(next) }
        } catch { errorMessage = error.localizedDescription }
    }
    func createPairing(label: String = "我的新 iPhone") async {
        do {
            let token = try TeamDeviceKeychain.randomSecret()
            _ = await submit(.security(TeamSecurityCommand(path: "pairings", method: "POST", fields: ["token": .string(token), "label": .string(label)], kind: "pair-create", newToken: token)))
        } catch { errorMessage = "无法保存配对请求，请重试。" }
    }
    func previewPairing(teamID: UUID, input: String) async throws -> DevicePairing {
        guard let token = DevicePairingLink.token(from: input) else { throw TeamServiceError.message("请使用新设备提供的完整配对请求。") }
        return try await securityRead("teams/\(teamID.uuidString.lowercased())/pairings/preview", method: "POST", fields: ["token": .string(token)])
    }
    func approvePairing(_ pairing: DevicePairing, token: String) async -> Bool {
        guard let team = pairing.teamID, let revision = pairing.memberRevision else { return false }
        return await submit(.security(TeamSecurityCommand(path: "teams/\(team.uuidString.lowercased())/pairings/\(pairing.id.uuidString.lowercased())/approve", method: "POST",
            fields: ["token": .string(token), "phrase": .string(pairing.phrase), "memberRevision": .number(Double(revision))], kind: "pair-approve", teamID: team)))
    }
    func acceptPairing() async -> Bool {
        guard let pairing = state.pairing?.request, let revision = pairing.memberRevision else { return false }
        return await submit(.security(TeamSecurityCommand(path: "pairings/\(pairing.id.uuidString.lowercased())/accept", method: "POST",
            fields: ["phrase": .string(pairing.phrase), "memberRevision": .number(Double(revision))], kind: "pair-accept", teamID: pairing.teamID)))
    }
    func cancelPairing(_ id: UUID, teamID: UUID? = nil) async -> Bool {
        await submit(.security(TeamSecurityCommand(path: "pairings/\(id.uuidString.lowercased())/cancel", method: "POST", fields: [:], kind: "pair-cancel", teamID: teamID)))
    }
    func removeDevice(_ device: PairedTeamDevice, teamID: UUID) async -> Bool {
        await submit(.security(TeamSecurityCommand(path: "teams/\(teamID.uuidString.lowercased())/devices/\(device.id.uuidString.lowercased())", method: "DELETE",
            fields: ["memberID": .uuid(device.memberID), "baseRevision": .number(Double(device.revision))], kind: "device-remove", teamID: teamID)))
    }
    func rotateRecovery(_ status: TeamRecoveryStatus) async -> Bool {
        do {
            let token = try TeamDeviceKeychain.randomSecret()
            return await submit(.security(TeamSecurityCommand(path: "teams/\(status.teamID.uuidString.lowercased())/recovery/rotate", method: "POST",
                fields: ["newToken": .string(token), "baseVersion": .number(Double(status.version)), "confirmReset": .bool(true)], kind: "recovery-rotate", teamID: status.teamID, newToken: token)))
        } catch { errorMessage = "无法保存新的恢复码，请重试。"; return false }
    }
    func previewRecovery(_ input: String) async throws -> TeamRecoveryStatus {
        guard let card = SavedRecoveryCard.parse(input) else { throw TeamServiceError.message("请粘贴完整恢复卡或以 BMREC1 开头的恢复码。") }
        return try await securityRead("recovery/preview", method: "POST", fields: ["teamID": .uuid(card.teamID), "token": .string(card.token)])
    }
    func recoverManagement(input: String, preview: TeamRecoveryStatus, nickname: String) async -> Bool {
        guard let card = SavedRecoveryCard.parse(input), card.teamID == preview.teamID else { return false }
        do {
            let token = try TeamDeviceKeychain.randomSecret()
            return await submit(.security(TeamSecurityCommand(path: "recovery/redeem", method: "POST", fields: ["teamID": .uuid(card.teamID), "token": .string(card.token),
                "newToken": .string(token), "nickname": .string(nickname), "baseVersion": .number(Double(preview.version)), "confirmReset": .bool(true)], kind: "recovery-redeem", teamID: card.teamID, newToken: token)))
        } catch { errorMessage = "无法保存恢复请求，请重试。"; return false }
    }
}
