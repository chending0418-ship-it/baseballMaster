import Foundation
import Security
import Compression

/// Device credentials are not part of any Codable roster, game or backup.
protocol TeamCredentialStorage {
    func loadOrCreate() throws -> String
    func encryptionSecret() throws -> String
}
extension TeamCredentialStorage { func encryptionSecret() throws -> String { try loadOrCreate() } }

struct PendingCredentialRotation: Codable { let id: UUID; let token: String; let createdAt: Date }

enum TeamServiceError: LocalizedError {
    case message(String)
    case response(Int, String)
    var isRetryable: Bool { if case .response(let status, _) = self { return status >= 500 || status == 429 }; return true }
    var endsAccess: Bool { if case .response(let status, _) = self { return status == 401 || status == 403 || status == 404 }; return false }
    var errorDescription: String? {
        switch self { case .message(let text), .response(_, let text): return text }
    }
}

struct TeamDeviceKeychain: TeamCredentialStorage {
    var account: String = "device-v1"
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.jasonchen.baseballmaster.collaboration",
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    func loadOrCreate() throws -> String {
        try loadOrCreate(seed: nil)
    }
    private func loadOrCreate(seed: String?) throws -> String {
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data, let value = String(data: data, encoding: .utf8), value.count == 43 {
            return value
        }
        guard status == errSecItemNotFound else {
            throw TeamServiceError.message("无法读取设备授权，请解锁设备后重试。")
        }
        let value = try seed ?? Self.randomSecret()
        var create = query
        create[kSecValueData as String] = Data(value.utf8)
        create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(create as CFDictionary, nil)
        if added == errSecDuplicateItem { return try loadOrCreate() }
        guard added == errSecSuccess else { throw TeamServiceError.message("设备授权未能安全保存，请重试。") }
        return value
    }
    func encryptionSecret() throws -> String {
        // Freeze the old key before the first credential rotation. Existing ciphertext remains readable.
        try TeamDeviceKeychain(account: account + ".storage-key").loadOrCreate(seed: loadOrCreate())
    }
    private func readItem(_ suffix: String) throws -> Data? {
        var read = TeamDeviceKeychain(account: account + suffix).query
        read[kSecReturnData as String] = true; read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?; let status = SecItemCopyMatching(read as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw TeamServiceError.message("设备安全资料暂不可用，请解锁后重试。") }
        return data
    }
    private func writeItem(_ suffix: String, data: Data) throws {
        let target = TeamDeviceKeychain(account: account + suffix).query
        let changed = SecItemUpdate(target as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if changed == errSecSuccess { return }
        guard changed == errSecItemNotFound else { throw TeamServiceError.message("设备安全资料未能保存，请重试。") }
        var create = target; create[kSecValueData as String] = data
        create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(create as CFDictionary, nil)
        if added == errSecDuplicateItem { try writeItem(suffix, data: data); return }
        guard added == errSecSuccess else { throw TeamServiceError.message("设备安全资料未能保存，请重试。") }
    }
    func pendingRotation() throws -> PendingCredentialRotation? {
        try readItem(".rotation-pending").map { try JSONDecoder().decode(PendingCredentialRotation.self, from: $0) }
    }
    func beginRotation() throws -> PendingCredentialRotation {
        _ = try encryptionSecret()
        if let pending = try pendingRotation() { return pending }
        let value = PendingCredentialRotation(id: UUID(), token: try Self.randomSecret(), createdAt: Date())
        var create = TeamDeviceKeychain(account: account + ".rotation-pending").query
        create[kSecValueData as String] = try JSONEncoder().encode(value); create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(create as CFDictionary, nil)
        if status == errSecDuplicateItem, let pending = try pendingRotation() { return pending }
        guard status == errSecSuccess else { throw TeamServiceError.message("新设备授权尚未保存，更新未发送。") }
        return value
    }
    func finishRotation(_ value: PendingCredentialRotation) throws {
        _ = try encryptionSecret()
        try writeItem("", data: Data(value.token.utf8))
        try writeItem(".rotation-date", data: JSONEncoder().encode(Date()))
        try clearRotation(value.id)
    }
    func clearRotation(_ id: UUID) throws {
        guard try pendingRotation()?.id == id else { return }
        let status = SecItemDelete(TeamDeviceKeychain(account: account + ".rotation-pending").query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw TeamServiceError.message("设备授权更新结果未能保存，请重试。") }
    }
    func rotationDue() throws -> Bool {
        guard let data = try readItem(".rotation-date") else {
            try writeItem(".rotation-date", data: JSONEncoder().encode(Date())); return false
        }
        return Date().timeIntervalSince(try JSONDecoder().decode(Date.self, from: data)) >= 90 * 86400
    }
    func replaceLostCredential() throws {
        _ = try encryptionSecret()
        try writeItem("", data: Data(Self.randomSecret().utf8))
        if let pending = try pendingRotation() { try clearRotation(pending.id) }
        try writeItem(".rotation-date", data: JSONEncoder().encode(Date()))
    }

    static func randomSecret() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw TeamServiceError.message("暂时无法建立设备授权，请重试。")
        }
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

enum SharedTeamRole: String, Codable, CaseIterable {
    case admin, scorer, member
    var title: String { switch self { case .admin: return "管理员"; case .scorer: return "记录员"; case .member: return "成员" } }
    var canEditPlayers: Bool { self == .admin || self == .scorer }
    var explanation: String {
        switch self {
        case .admin: return "管理球队和成员，查看全队资料。"
        case .scorer: return "新增、修改球员，查看全队资料，记录负责的比赛；不管理成员。"
        case .member: return "查看队内资料和比赛记录。"
        }
    }
}
struct SharedTeamSummary: Codable, Identifiable, Equatable {
    let id: UUID
    let name: String
    let revision: Int
    let role: SharedTeamRole
    let memberID: UUID
    var accessEpoch: Int? = nil
    var recoveryVersion: Int? = nil
}
struct SharedTeamList: Decodable { let teams: [SharedTeamSummary]; let recoveryEpoch: UUID }
struct SharedTeamMembership: Codable { let teamID: UUID; let memberID: UUID; let role: SharedTeamRole; var recoveryVersion: Int? = nil }
struct SharedTeamInvitation: Codable, Identifiable {
    let invitationID: UUID; let expiresAt: Double; let role: SharedTeamRole
    var id: UUID { invitationID }
}
struct SharedRosterSeed: Codable, Equatable {
    struct Entry: Codable, Equatable {
        let sourceID: UUID; let chineseName: String; let englishName: String; let numbers: [String]
    }
    let shortName: String; let city: String; let players: [Entry]
    init(team: Team) {
        shortName = team.shortName; city = team.city
        players = team.players.map { Entry(sourceID: $0.id, chineseName: $0.chineseName, englishName: $0.englishName, numbers: $0.numberTexts) }
    }
}
struct SharedRosterPlayer: Codable, Identifiable, Equatable {
    let id: UUID; let sourceID: UUID; let chineseName: String; let englishName: String; let numbers: [String]
    var archived: Bool? = nil
    var isArchived: Bool { archived == true }
    var localPlayer: Player {
        var player = Player(id: sourceID, chineseName: chineseName, englishName: englishName, numbers: numbers.compactMap(Int.init))
        player.jerseyNumbers = numbers; return player
    }
    var name: String { !chineseName.isEmpty ? chineseName : (!englishName.isEmpty ? englishName : "未填写姓名") }
    var numberText: String { numbers.isEmpty ? "暂无背号" : numbers.map { "#\($0)" }.joined(separator: " / ") }
}
struct SharedTeamProfile: Codable, Identifiable {
    let id: UUID; let name: String; let revision: Int; let shortName: String; let city: String; let players: [SharedRosterPlayer]
}
struct SharedTeamDetail: Codable { let team: SharedTeamProfile; let role: SharedTeamRole; let memberID: UUID }
extension SharedTeamProfile {
    var localTeam: Team { Team(id: id, name: name, shortName: shortName, city: city, players: players.filter { !$0.isArchived }.map(\.localPlayer)) }
}
struct SharedRosterChange: Codable {
    let teamID: UUID
    var baseRevision: Int
    let kind: String
    var name: String? = nil
    var shortName: String? = nil
    var city: String? = nil
    var player: SharedRosterPlayer? = nil
}
protocol TeamRosterService {
    func updateRoster(_ change: SharedRosterChange, operationID: UUID) async throws -> SharedTeamDetail
}
extension TeamServiceClient: TeamRosterService {
    func updateRoster(_ change: SharedRosterChange, operationID: UUID) async throws -> SharedTeamDetail {
        var fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(change)) as! [String: Any]
        fields["operationID"] = operationID.uuidString
        let body = try JSONDecoder().decode(MatchJSON.self, from: JSONSerialization.data(withJSONObject: fields))
        return try await request("PATCH", path: "teams/\(change.teamID.uuidString.lowercased())/roster", body: body)
    }
}
struct SharedTeamMember: Codable, Identifiable { let id: UUID; let nickname: String; let role: SharedTeamRole; let revision: Int }
struct SharedInvitationPreview: Decodable { let teamID: UUID; let teamName: String; let role: SharedTeamRole; let expiresAt: Double }

protocol TeamDirectoryService {
    func enroll(deviceName: String) async throws -> UUID
    func teams() async throws -> SharedTeamList
    func createTeam(name: String, nickname: String, operationID: UUID, profile: SharedRosterSeed?, recoveryToken: String?) async throws -> SharedTeamMembership
    func detail(teamID: UUID) async throws -> SharedTeamDetail
    func preview(token: String) async throws -> SharedInvitationPreview
    func invite(teamID: UUID, role: SharedTeamRole, token: String, operationID: UUID) async throws -> SharedTeamInvitation
    func join(token: String, nickname: String, operationID: UUID) async throws -> SharedTeamMembership
    func invitations(teamID: UUID) async throws -> [SharedTeamInvitation]
    func revoke(teamID: UUID, invitationID: UUID, operationID: UUID) async throws
    func members(teamID: UUID) async throws -> [SharedTeamMember]
    func changeMember(teamID: UUID, member: SharedTeamMember, role: SharedTeamRole, remove: Bool, operationID: UUID) async throws
    func leave(teamID: UUID, operationID: UUID) async throws
}

private final class TeamRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

private struct TeamServiceProblem: Decodable {
    struct Detail: Decodable { let code: String }
    let error: Detail
}

/// Initial identity/team API. No automatic uploads; the product UI will opt in to sharing.
actor TeamServiceClient: TeamDirectoryService {
    static let productionURL = URL(string: "https://baseballmaster.cc/api/v3")!
    private let baseURL: URL
    private let vault: TeamCredentialStorage
    private let session: URLSession

    init(baseURL: URL = TeamServiceClient.productionURL, vault: TeamCredentialStorage = TeamDeviceKeychain()) throws {
        var allowed = baseURL.scheme == "https"
        #if DEBUG
        allowed = allowed || (baseURL.scheme == "http" && ["127.0.0.1", "localhost", "::1"].contains(baseURL.host ?? ""))
        #endif
        guard allowed, baseURL.user == nil, baseURL.password == nil, baseURL.query == nil, baseURL.fragment == nil else {
            throw TeamServiceError.message("球队服务地址无效。")
        }
        self.baseURL = baseURL; self.vault = vault
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil; configuration.httpCookieStorage = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 15
        session = URLSession(configuration: configuration, delegate: TeamRedirectPolicy(), delegateQueue: nil)
    }

    func enroll(deviceName: String) async throws -> UUID {
        if let keychain = vault as? TeamDeviceKeychain, try keychain.pendingRotation() != nil {
            _ = try await rotateCredentialIfNeeded()
        }
        struct Body: Encodable { let label: String }
        struct Result: Decodable { let deviceID: UUID; let `protocol`: Int }
        let result: Result = try await request("POST", path: "devices", body: Body(label: deviceName))
        guard result.protocol == 1 else { throw TeamServiceError.message("请更新 App 后重试。") }
        _ = try await rotateCredentialIfNeeded()
        return result.deviceID
    }
    @discardableResult func rotateCredentialIfNeeded(force: Bool = false) async throws -> Bool {
        guard let keychain = vault as? TeamDeviceKeychain else { return false }
        guard try force || keychain.pendingRotation() != nil || keychain.rotationDue() else { return false }
        let pending = try keychain.beginRotation()
        struct Commit: Encodable { let rotationID: UUID }
        struct Result: Decodable { let deviceID: UUID; let rotated: Bool }
        do {
            let result: Result
            do { result = try await request("POST", path: "devices/credential-commit", body: Commit(rotationID: pending.id), credential: pending.token) }
            catch TeamServiceError.response(let status, _) where status == 404 {
                struct Prepare: Encodable { let operationID: UUID; let newToken: String }
                struct Prepared: Decodable { let rotationID: UUID }
                let _: Prepared = try await request("POST", path: "devices/credential-prepare", body: Prepare(operationID: pending.id, newToken: pending.token))
                result = try await request("POST", path: "devices/credential-commit", body: Commit(rotationID: pending.id), credential: pending.token)
            }
            guard result.rotated else { throw TeamServiceError.message("设备授权更新尚未确认。") }
            try keychain.finishRotation(pending); return true
        } catch TeamServiceError.response(let status, _) where status == 409 || status == 426 {
            return false // Service recovery review or an older compatible server can defer maintenance.
        } catch TeamServiceError.response(let status, _) where status == 410 {
            try keychain.clearRotation(pending.id); return false
        }
    }

    func teams() async throws -> SharedTeamList {
        try await request("GET", path: "teams", body: Optional<String>.none)
    }
    func reconnectWithNewCredential() async throws {
        guard let keychain = vault as? TeamDeviceKeychain else { throw TeamServiceError.message("此设备暂不支持重新接入。") }
        try keychain.replaceLostCredential()
        _ = try await enroll(deviceName: "iPhone")
    }

    // Callers persist the operation ID with user input before sending so retries keep the same identity.
    func createTeam(name: String, nickname: String, operationID: UUID, profile: SharedRosterSeed? = nil, recoveryToken: String? = nil) async throws -> SharedTeamMembership {
        struct Body: Encodable { let name: String; let nickname: String; let operationID: UUID; let profile: SharedRosterSeed?; let recoveryToken: String? }
        return try await request("POST", path: "teams", body: Body(name: name, nickname: nickname, operationID: operationID, profile: profile, recoveryToken: recoveryToken))
    }

    func invite(teamID: UUID, role: SharedTeamRole, token: String, operationID: UUID) async throws -> SharedTeamInvitation {
        struct Body: Encodable { let role: SharedTeamRole; let token: String; let operationID: UUID }
        return try await request("POST", path: "teams/\(teamID.uuidString.lowercased())/invitations", body: Body(role: role, token: token, operationID: operationID))
    }

    func join(token: String, nickname: String, operationID: UUID) async throws -> SharedTeamMembership {
        struct Body: Encodable { let token: String; let nickname: String; let operationID: UUID }
        return try await request("POST", path: "invitations/redeem", body: Body(token: token, nickname: nickname, operationID: operationID))
    }

    func detail(teamID: UUID) async throws -> SharedTeamDetail {
        try await request("GET", path: "teams/\(teamID.uuidString.lowercased())", body: Optional<String>.none)
    }
    func preview(token: String) async throws -> SharedInvitationPreview {
        struct Body: Encodable { let token: String }
        return try await request("POST", path: "invitations/preview", body: Body(token: token))
    }
    func members(teamID: UUID) async throws -> [SharedTeamMember] {
        struct Result: Decodable { let members: [SharedTeamMember] }
        let result: Result = try await request("GET", path: "teams/\(teamID.uuidString.lowercased())/members", body: Optional<String>.none)
        return result.members
    }
    func invitations(teamID: UUID) async throws -> [SharedTeamInvitation] {
        struct Result: Decodable { let invitations: [SharedTeamInvitation] }
        let result: Result = try await request("GET", path: "teams/\(teamID.uuidString.lowercased())/invitations", body: Optional<String>.none)
        return result.invitations
    }
    func revoke(teamID: UUID, invitationID: UUID, operationID: UUID) async throws {
        struct Body: Encodable { let operationID: UUID }
        struct Result: Decodable { let revoked: Bool }
        let _: Result = try await request("DELETE", path: "teams/\(teamID.uuidString.lowercased())/invitations/\(invitationID.uuidString.lowercased())", body: Body(operationID: operationID))
    }
    func changeMember(teamID: UUID, member: SharedTeamMember, role: SharedTeamRole, remove: Bool, operationID: UUID) async throws {
        struct Body: Encodable { let operationID: UUID; let baseRevision: Int; let role: SharedTeamRole; let active: Bool? }
        struct Result: Decodable { let memberID: UUID }
        let _: Result = try await request("PATCH", path: "teams/\(teamID.uuidString.lowercased())/members/\(member.id.uuidString.lowercased())",
            body: Body(operationID: operationID, baseRevision: member.revision, role: role, active: remove ? false : nil))
    }
    func leave(teamID: UUID, operationID: UUID) async throws {
        struct Body: Encodable { let operationID: UUID }
        struct Result: Decodable { let left: Bool }
        let _: Result = try await request("POST", path: "teams/\(teamID.uuidString.lowercased())/leave", body: Body(operationID: operationID))
    }

    /// Compression emits raw DEFLATE. Add the RFC 1950 envelope for HTTP deflate.
    nonisolated static func deflate(_ data: Data) -> Data? {
        var destination = [UInt8](repeating: 0, count: data.count + data.count / 8 + 256)
        let size = data.withUnsafeBytes { source in
            compression_encode_buffer(&destination, destination.count, source.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
        }
        guard size > 0 else { return nil }
        var result = Data([0x78, 0x9c]); result.append(contentsOf: destination.prefix(size))
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in data { a = (a + UInt32(byte)) % 65521; b = (b + a) % 65521 }
        let checksum = (b << 16) | a
        result.append(contentsOf: [UInt8(truncatingIfNeeded: checksum >> 24), UInt8(truncatingIfNeeded: checksum >> 16), UInt8(truncatingIfNeeded: checksum >> 8), UInt8(truncatingIfNeeded: checksum)])
        return result
    }

    nonisolated static func connectionFailureMessage(_ error: Error, path: String) -> String {
        // Use a fixed operation label, never an URL, team ID, token or NSError.userInfo.
        let operation = path.contains("/games") ? "比赛同步" : "球队资料连接"
        let problem = error as NSError
        guard problem.domain == NSURLErrorDomain else { return "\(operation)未完成，请稍后重试。" }
        let reason: String
        switch URLError.Code(rawValue: problem.code) {
        case .timedOut: reason = "超时"
        case .cancelled: reason = "已取消"
        case .cannotFindHost, .dnsLookupFailed: reason = "无法解析服务地址"
        case .cannotConnectToHost: reason = "无法连接服务器"
        case .networkConnectionLost: reason = "连接中断"
        case .notConnectedToInternet: reason = "网络不可用"
        case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid: reason = "安全连接验证失败"
        default: reason = "未完成"
        }
        return "\(operation)\(reason)（\(problem.code)），请稍后重试。"
    }

    func request<Body: Encodable, Response: Decodable>(_ method: String, path: String, body: Body?, credential: String? = nil) async throws -> Response {
        // This write to Keychain must succeed before any credential is sent to the service.
        let token = try credential ?? vault.loadOrCreate()
        let pieces = path.split(separator: "?", maxSplits: 1).map(String.init)
        var components = URLComponents(url: baseURL.appendingPathComponent(pieces[0]), resolvingAgainstBaseURL: false)!
        if pieces.count == 2 { components.percentEncodedQuery = pieces[1] }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("1", forHTTPHeaderField: "X-BM-Protocol")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body {
            let data = try JSONEncoder().encode(body)
            if data.count > 1024, let compressed = Self.deflate(data), compressed.count < data.count {
                request.httpBody = compressed; request.setValue("deflate", forHTTPHeaderField: "Content-Encoding")
            } else { request.httpBody = data }
        }
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) }
        catch { throw TeamServiceError.message(Self.connectionFailureMessage(error, path: path)) }
        guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode
            let code = (try? JSONDecoder().decode(TeamServiceProblem.self, from: data))?.error.code
            let message: String
            switch status {
            case 400, 413:
                if code == "team_capacity" { message = "球队比赛档案或存储空间已达上限。本机记录已保留，请导出并联系支持。" }
                else if code == "confirmation_required" { message = "请核对完整球队名称与确认内容，原资料未修改。" }
                else { message = (path.contains("/games") || path.contains("/history-imports")) ? "比赛资料未通过校验或超过单场大小限制。本机记录已保留，请核对后重试。" : "请核对球队名称、成员称呼和名单；最多支持 200 名球员。" }
            case 401: message = "设备授权已失效，请联系球队管理员重新授权。"
            case 403: message = "当前角色不能执行此操作。"
            case 404: message = path.hasPrefix("recovery/") ? "恢复资料无效或已更换，请核对保存的恢复卡。" : "球队暂不可用，或当前设备无权访问。"
            case 409:
                if code == "admin_limit" { message = "每支球队最多三名管理员。请先调整现有管理员的角色，再设置新的管理员。" }
                else if code == "import_changed" { message = "球队的比赛或名单已更新，请重新预览核对后继续。" }
                else if code == "import_duplicate" { message = "这场比赛已经导入过，不会重复导入、覆盖或恢复。请重新预览核对。" }
                else if code == "duplicate_review" { message = "请核对同日同对手的比赛，确认是否为另一场。" }
                else if code == "roster_conflict" { message = "名单已在其他设备更新。你的修改已保留，请核对最新资料后重新保存。" }
                else if code == "capacity" { message = path.contains("/roster") ? "名单容量已达上限，请先停用已离队球员。" : "球队、成员或待使用邀请已达上限，请联系管理员整理后重试。" }
                else if code == "draft_capacity" { message = "这场比赛待核对的记录太多，请先由管理员处理。本机记录仍会保留。" }
                else if code == "draft_resolved" { message = "这份待核对记录已经处理，请刷新查看结果。" }
                else if code == "correction_required" { message = "请先接手记分，对照待核对记录更正比赛并同步，再完成核对。" }
                else if code == "recovery_review" || code == "recovery_changed" { message = "请从球队共享入口核对恢复后的资料，旧操作不会自动重做。" }
                else if code == "last_admin_device" { message = "这是最后一台有效管理设备，请先配对新设备或指定其他管理员。" }
                else if code == "last_admin" { message = "请先指定另一名管理员，再退出或调整自己的角色。" }
                else if code == "already_joined" { message = "这台设备已加入该球队，请返回球队列表刷新。" }
                else { message = "资料已有变化，请刷新核对后重试。" }
            case 410:
                if code == "cursor_expired" { message = "同步记录已整理，正在重新核对完整球队资料。" }
                else if code == "restore_expired" { message = "已超过 30 天恢复期限，原本机资料仍保留。" }
                else if code == "operation_expired" { message = "这次旧操作已归档，请刷新核对结果，不会自动重新执行。" }
                else { message = path.contains("pairings") ? "配对已失效或成员资料已变化，请在新设备重新申请。" : "邀请已失效，请联系邀请人重新发送。" }
            case 426: message = "请更新 App 后重试。"
            case 429: message = "操作过于频繁，请稍后重试。"
            default: message = "球队服务暂时不可用，请稍后重试。"
            }
            throw TeamServiceError.response(status ?? 503, message)
        }
        do { return try JSONDecoder().decode(Response.self, from: data) }
        catch { throw TeamServiceError.message("球队资料暂时无法读取，请更新 App 或稍后重试。") }
    }
}
