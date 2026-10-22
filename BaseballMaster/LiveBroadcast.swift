import Combine
import CryptoKit
import Foundation
import Security

enum AppFeatureAvailability {
    // 2.1 uses the deployed HTTPS service. This is a build setting, not remote configuration.
    static let liveBroadcast = true
}

/// Public match projection. Only current participants are included, never the local roster library or credentials.
struct LiveSnapshot: Codable, Equatable {
    struct Person: Codable, Equatable {
        var id: String; var name: String; var number: String
        init(_ p: Player) { id = p.id.uuidString; name = String(p.name.prefix(200)); number = String(p.numberText.prefix(20)) }
    }
    struct LineupPlayer: Codable, Equatable { var player: Person; var order: Int?; var position: String }
    struct Upcoming: Codable, Equatable { var player: Person; var order: Int }
    struct Side: Codable, Equatable {
        var name: String; var runs: Int; var hits: Int; var errors: Int; var innings: [Int]
        var shortName: String?; var playedInnings: [Bool]?; var lineup: [LineupPlayer]?; var pitcherID: String?
    }
    struct Rules: Codable, Equatable { var scheduledInnings: Int; var halfInningRunLimit: Int?; var timeLimitMinutes: Int? }
    struct Clock: Codable, Equatable { var startedAt: Double?; var runningSince: Double?; var elapsedSeconds: Double }
    struct BatterStats: Codable, Equatable { var atBats: Int; var hits: Int; var isComplete: Bool }
    struct Situation: Codable, Equatable {
        var balls: Int; var strikes: Int; var outs: Int; var bases: [Int]; var halfEnded: Bool
        init(_ s: GameSituationSnapshot, inning: Int, isTop: Bool, endingHalf: Bool = false) {
            balls = s.balls; strikes = s.strikes; outs = s.outs
            halfEnded = endingHalf || s.outs >= 3 || s.inning != inning || s.isTop != isTop
            bases = halfEnded ? [] : s.baseRunners.map { $0.base.rawValue }.sorted()
        }
    }
    struct Runner: Codable, Equatable { var base: Int; var player: Person }
    struct Detail: Codable, Equatable { var id: String; var text: String; var situation: Situation? }
    struct Entry: Codable, Equatable {
        var id: String; var appearanceID: String?; var inning: Int; var isTop: Bool
        var kind: String; var label: String; var summary: String; var player: Person?; var status: String; var details: [Detail]
        var order: Int?; var situation: Situation?
    }
    var schema = 1
    var gameID: String; var revision: Int; var mode: String; var isFinal: Bool; var endedAt: Double?
    var inning: Int; var isTop: Bool; var balls: Int; var strikes: Int; var outs: Int
    var home: Side; var away: Side; var batter: Person?; var pitcher: Person?; var pitchCount: Int?
    var batterOrder: Int?
    var appearancePitchCount: Int; var pitchLimit: Int?; var bases: [Runner]; var currentAppearanceID: String?
    var notice: String; var entries: [Entry]
    // Additive v1 fields; absent in 2.1 snapshots and decoded as unknown, never fabricated by the viewer.
    var hasStarted: Bool?; var rules: Rules?; var clock: Clock?; var batterStats: BatterStats?; var nextBatters: [Upcoming]?

    init(_ stored: StoredGame) {
        let g = stored.state
        gameID = stored.id.uuidString; revision = stored.revision ?? 0
        let coach = stored.rules.gameMode == .coachPitch
        mode = coach ? "coachPitch" : "standard"; isFinal = g.isFinal
        endedAt = g.isFinal ? (g.endedAt ?? stored.updatedAt).timeIntervalSince1970 * 1000 : nil
        inning = g.inning; isTop = g.isTop; balls = g.balls; strikes = g.strikes; outs = g.outs
        let events = g.scoringEvents ?? []
        let playCategories: Set<ScoringEventCategory> = [.pitch, .battedBall, .runner, .out, .violation, .tiebreak]
        let started = stored.startedAt != nil || g.clockRunningSince != nil || (g.clockElapsedSeconds ?? 0) > 0
            || stored.openingPitchRecorded == true || g.inning > 1 || !g.isTop || events.contains { playCategories.contains($0.category) }
            || g.balls > 0 || g.strikes > 0 || g.outs > 0 || !g.baseRunners.isEmpty
            || g.homeScore > 0 || g.awayScore > 0 || g.homeHits > 0 || g.awayHits > 0
            || g.batting.values.contains { $0.plateAppearances > 0 } || g.pitching.values.contains { $0.pitches > 0 }
        hasStarted = started
        rules = Rules(scheduledInnings: g.scheduledInnings, halfInningRunLimit: stored.rules.halfInningRunLimit, timeLimitMinutes: stored.rules.timeLimitMinutes)
        clock = Clock(startedAt: stored.startedAt.map { $0.timeIntervalSince1970 * 1000 },
                      runningSince: g.clockRunningSince.map { $0.timeIntervalSince1970 * 1000 }, elapsedSeconds: max(0, g.clockElapsedSeconds ?? 0))
        func side(_ team: Team, isHome: Bool, runs: Int, hits: Int, errors: Int, innings: [Int]) -> Side {
            let order = isHome ? g.homeBattingOrderIDs : g.awayBattingOrderIDs
            let fielders = (isHome ? g.homeFieldingPlayerIDs : g.awayFieldingPlayerIDs) ?? order
            let dh = isHome ? g.homeDesignatedHitterID : g.awayDesignatedHitterID
            let ids = order + fielders.filter { !order.contains($0) }
            let lineup = ids.compactMap { id -> LineupPlayer? in
                guard let p = team.players.first(where: { $0.id == id }) else { return nil }
                return LineupPlayer(player: Person(p), order: order.firstIndex(of: id).map { $0 + 1 },
                                    position: dh == id ? "DH" : fielders.contains(id) ? p.primaryPosition.fullName : "打击")
            }
            let currentHalf = (g.inning - 1) * 2 + (g.isTop ? 0 : 1)
            let played = innings.indices.map { index in
                let half = index * 2 + (isHome ? 1 : 0)
                return started && (half < currentHalf || (half == currentHalf && (!g.isFinal || events.contains {
                    $0.inning == index + 1 && $0.isTop != isHome && playCategories.contains($0.category)
                } || (index == 0 && !isHome && stored.startedAt != nil))) || innings[index] > 0)
            }
            return Side(name: String(team.name.prefix(200)), runs: runs, hits: hits, errors: errors, innings: innings,
                        shortName: String(team.shortName.prefix(200)), playedInnings: played, lineup: lineup,
                        pitcherID: (isHome ? g.activeHomePitcherID : g.activeAwayPitcherID)?.uuidString)
        }
        home = side(g.homeTeam, isHome: true, runs: g.homeScore, hits: g.homeHits, errors: g.homeErrors, innings: g.homeRunsByInning)
        away = side(g.awayTeam, isHome: false, runs: g.awayScore, hits: g.awayHits, errors: g.awayErrors, innings: g.awayRunsByInning)
        batter = g.battingOrderPlayers.isEmpty ? nil : Person(g.currentBatter)
        batterOrder = g.isFinal || batter == nil ? nil : g.currentBattingOrder
        pitcher = g.fieldingTeam.players.isEmpty ? nil : Person(g.currentPitcher)
        pitchCount = coach || g.fieldingTeam.players.isEmpty ? nil : (g.pitching[g.currentPitcher.id]?.pitches ?? 0)
        let line = batter.flatMap { p in g.batting.first { $0.key.uuidString == p.id }?.value } ?? BattingLine()
        batterStats = g.isFinal || batter == nil ? nil : BatterStats(atBats: line.atBats, hits: line.hits, isComplete: g.statisticsIncomplete != true)
        let battingLineup = g.battingOrderPlayers
        let batterIndex = g.isTop ? g.awayBatterIndex : g.homeBatterIndex
        nextBatters = g.isFinal || battingLineup.isEmpty ? [] : (1...2).map { offset in
            let index = (batterIndex + offset) % battingLineup.count
            return Upcoming(player: Person(battingLineup[index]), order: index + 1)
        }
        appearancePitchCount = g.plateAppearancePitchCount ?? 0
        pitchLimit = coach ? (stored.rules.coachPitchLimit ?? 6) : nil
        bases = g.baseRunners.map { Runner(base: $0.key.rawValue, player: Person($0.value)) }.sorted { $0.base < $1.base }
        currentAppearanceID = g.currentPlateAppearanceID?.uuidString
        notice = g.isFinal ? (g.endReason?.rawValue ?? "比赛结束") : (g.pendingDecision?.title ?? "")
        if g.statisticsIncomplete == true { notice += (notice.isEmpty ? "" : " · ") + "过程或责任待确认，统计可能不完整" }
        let players = g.homeTeam.players + g.awayTeam.players
        let appearances = g.plateAppearances ?? []
        // A third-out action records the play before its separate HALF-END event.
        // Use that boundary only when both events belong to the same saved action;
        // a later manual half-end must not change an earlier hit's recorded bases.
        let eventsByID = Dictionary(uniqueKeysWithValues: events.map { ($0.id, $0) })
        var endingHalfIDs = Set(events.filter { $0.notation == "HALF-END" }.map(\.id))
        var boundarySituations: [UUID: GameSituationSnapshot] = [:]
        for operation in g.historyJournal?.operations ?? [] {
            let actionEvents = operation.eventIDs.compactMap { eventsByID[$0] }
            for index in actionEvents.indices where index > 0 && actionEvents[index].notation == "HALF-END" {
                let previous = actionEvents[index - 1], boundary = actionEvents[index]
                if previous.inning == boundary.inning && previous.isTop == boundary.isTop {
                    endingHalfIDs.insert(previous.id)
                    boundarySituations[previous.id] = boundary.afterSituation
                }
            }
        }
        func eventSituation(_ e: ScoringEventRecord) -> Situation? {
            (boundarySituations[e.id] ?? e.afterSituation).map {
                Situation($0, inning: e.inning, isTop: e.isTop, endingHalf: endingHalfIDs.contains(e.id))
            }
        }
        var sequence: [(position: Double, entry: Entry)] = []
        func person(_ id: UUID?) -> Person? { id.flatMap { id in players.first { $0.id == id }.map(Person.init) } }
        func detail(_ e: ScoringEventRecord) -> Detail {
            Detail(id: e.id.uuidString, text: String((e.title + (e.reviewedAt != nil ? "（已复核修订）" : "")).prefix(1000)),
                   situation: eventSituation(e))
        }
        for (offset, appearance) in appearances.enumerated() {
            let indexed = events.enumerated().filter { $0.element.plateAppearanceID == appearance.id }
            let owned = indexed.map(\.element)
            let p = person(appearance.batterIDs.last)
            let last = owned.last
            let state = owned.contains(where: \.needsReview) ? "review" : appearance.interrupted ? "interrupted" : appearance.completed ? "completed" : "current"
            let summary: String
            if appearance.interrupted { summary = "\(p?.name ?? "打者")：打席未完成，因换边或终场中断" }
            else { summary = last?.title ?? "\(p?.name ?? "打者")准备打击" }
            let historicalOrder = last?.beforeSituation.flatMap { situation -> Int? in
                let ids = appearance.isTop ? situation.awayBattingOrderIDs : situation.homeBattingOrderIDs
                return appearance.batterIDs.last.flatMap { ids?.firstIndex(of: $0).map { $0 + 1 } }
            }
            let isCurrent = appearance.id == g.currentPlateAppearanceID && !g.isFinal
            let closure = appearance.interrupted ? events.enumerated().first { index, event in
                index > (indexed.last?.offset ?? -1) && event.inning == appearance.inning && event.isTop == appearance.isTop
                    && ["HALF-END", "END"].contains(event.notation ?? "")
            }?.element : nil
            let situation = isCurrent ? Situation(g.situationSnapshot, inning: appearance.inning, isTop: appearance.isTop)
                : (closure ?? last).flatMap(eventSituation)
            let entry = Entry(id: appearance.id.uuidString, appearanceID: appearance.id.uuidString, inning: appearance.inning, isTop: appearance.isTop,
                              kind: "appearance", label: String((last?.notation ?? (appearance.completed ? "打席结束" : "打席")).prefix(100)),
                              summary: String(summary.prefix(1000)), player: p, status: state, details: owned.map(detail),
                              order: isCurrent ? g.currentBattingOrder : historicalOrder,
                              situation: situation)
            // Keep independent game/clock/substitution events in their original timeline positions.
            sequence.append((Double(indexed.first?.offset ?? (events.count + offset)), entry))
        }
        let known = Set(appearances.map(\.id))
        for (index, event) in events.enumerated() where event.plateAppearanceID.map({ !known.contains($0) }) ?? true {
            sequence.append((Double(index), Entry(id: event.id.uuidString, appearanceID: event.plateAppearanceID?.uuidString,
                inning: event.inning, isTop: event.isTop, kind: "event", label: event.category.title,
                summary: String(event.title.prefix(1000)), player: person(event.primaryPlayerID),
                status: event.needsReview ? "review" : "completed", details: [detail(event)], order: nil,
                situation: eventSituation(event))))
        }
        // Older games without structured events still expose their recorded text, never invented results.
        if events.isEmpty && !g.playLog.isEmpty {
            for (index, log) in g.playLog.enumerated() {
                sequence.append((Double(index), Entry(id: log.id.uuidString, appearanceID: nil, inning: log.inning, isTop: log.isTop,
                    kind: "event", label: "比赛记录", summary: String(log.text.prefix(1000)), player: nil, status: "completed", details: [], order: nil, situation: nil)))
            }
        }
        entries = sequence.sorted { $0.position < $1.position }.map(\.entry)
    }

    /// Swift normally omits nil optionals. The wire format uses explicit nulls for a strict, language-independent schema.
    func data() throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let raw = try encoder.encode(self)
        var object = try JSONSerialization.jsonObject(with: raw) as! [String: Any]
        for key in ["endedAt", "batter", "batterOrder", "pitcher", "pitchCount", "pitchLimit", "currentAppearanceID", "batterStats"] where object[key] == nil { object[key] = NSNull() }
        if var rows = object["entries"] as? [[String: Any]] {
            for i in rows.indices { for key in ["appearanceID", "player"] where rows[i][key] == nil { rows[i][key] = NSNull() } }
            object["entries"] = rows
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    /// Reproduce the released 2.1 projection to distinguish an additive upgrade from local history conflicts.
    func legacyData() throws -> Data {
        var object = try JSONSerialization.jsonObject(with: data()) as! [String: Any]
        for key in ["hasStarted", "rules", "clock", "batterStats", "nextBatters"] { object.removeValue(forKey: key) }
        for key in ["home", "away"] {
            var side = object[key] as! [String: Any]
            for field in ["shortName", "playedInnings", "lineup", "pitcherID"] { side.removeValue(forKey: field) }
            object[key] = side
        }
        object["entries"] = (object["entries"] as! [[String: Any]]).map { entry in
            var row = entry; row.removeValue(forKey: "order"); row.removeValue(forKey: "situation")
            row["details"] = (row["details"] as! [[String: Any]]).map { detail in var d = detail; d.removeValue(forKey: "situation"); return d }
            return row
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

struct LiveBinding: Codable, Equatable, CustomStringConvertible {
    var description: String { "LiveBinding(gameID: \(gameID), code: \(code ?? "pending"), closing: \(closing), blocked: \(blocked))" }
    var gameID: UUID; var requestID = UUID(); var createdAt = Date(); var token: String; var code: String?
    var ackRevision: Int?; var ackDigest: String?; var lastSync: Date?; var expiresAt: Date?
    var closing = false; var blocked = false
}
protocol LiveCredentialStorage {
    func read() throws -> [LiveBinding]
    func write(_ bindings: [LiveBinding]) throws
}
struct LiveKeychain: LiveCredentialStorage {
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.jasonchen.baseballmaster.live", kSecAttrAccount as String: "publishers-v1"] }
    func read() throws -> [LiveBinding] {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?; let result = SecItemCopyMatching(q as CFDictionary, &item)
        if result == errSecItemNotFound { return [] }
        guard result == errSecSuccess, let data = item as? Data else { throw LiveError.message("无法读取直播发布凭证，请解锁设备后重试。") }
        return try JSONDecoder().decode([LiveBinding].self, from: data)
    }
    func write(_ bindings: [LiveBinding]) throws {
        let data = try JSONEncoder().encode(bindings)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw LiveError.message("直播凭证未能安全保存，尚未开启直播。") }
        } else if status != errSecSuccess { throw LiveError.message("直播凭证保存失败，请重试。") }
    }
}
enum LiveError: LocalizedError {
    case message(String), server(Int, String)
    var errorDescription: String? { switch self { case .message(let s), .server(_, let s): s } }
}
struct LiveResponse: Decodable {
    var code: String; var revision: Int; var serverTime: Double; var lastSeen: Double; var expiresAt: Double
}
protocol LiveTransport {
    func send(method: String, code: String?, token: String, body: Data?) async throws -> LiveResponse?
}
struct LiveHTTPClient: LiveTransport {
    static let baseURL = URL(string: "https://baseballmaster.cc/livestreaming/novideo")!
    var baseURL = Self.baseURL
    var session = URLSession.shared
    func send(method: String, code: String?, token: String, body: Data?) async throws -> LiveResponse? {
        var url = baseURL.appendingPathComponent("api/sessions")
        if let code { url.appendPathComponent(code) }
        var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body; request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw LiveError.message("直播服务器无响应") }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw LiveError.server(http.statusCode, message ?? "直播同步失败（\(http.statusCode)）")
        }
        return method == "DELETE" ? nil : try JSONDecoder().decode(LiveResponse.self, from: data)
    }
}

@MainActor
final class LiveBroadcastManager: ObservableObject {
    @Published private(set) var bindings: [LiveBinding] = []
    @Published private(set) var messages: [UUID: String] = [:]
    @Published private(set) var credentialError: String?
    private weak var store: GameStore?
    private let vault: LiveCredentialStorage
    private let transport: LiveTransport
    let isEnabled: Bool
    private let viewBaseURL: URL
    private var worker: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var requested = false
    private var retryAt: [UUID: Date] = [:]
    private var failures: [UUID: Int] = [:]
    private var generation = 0

    init(store: GameStore, vault: LiveCredentialStorage = LiveKeychain(), transport: LiveTransport = LiveHTTPClient(), enabled: Bool = AppFeatureAvailability.liveBroadcast, viewBaseURL: URL = LiveHTTPClient.baseURL) {
        self.store = store; self.vault = vault; self.transport = transport; self.isEnabled = enabled; self.viewBaseURL = viewBaseURL
        guard enabled else { credentialError = "当前版本暂不提供文字直播。"; return }
        do { bindings = try vault.read() } catch { credentialError = error.localizedDescription }
    }
    func binding(for id: UUID) -> LiveBinding? { bindings.first { $0.gameID == id } }
    func url(for id: UUID) -> URL? {
        guard isEnabled, let b = binding(for: id), !b.closing, let code = b.code, (b.expiresAt ?? .distantFuture) > Date() else { return nil }
        return viewBaseURL.appendingPathComponent(code)
    }
    private func save(_ next: [LiveBinding]) throws { try vault.write(next); bindings = next }
    private func update(_ b: LiveBinding) throws { var next = bindings; next.removeAll { $0.gameID == b.gameID }; next.append(b); try save(next) }
    func start(_ gameID: UUID) {
        guard isEnabled, credentialError == nil, binding(for: gameID) == nil,
              let stored = store?.games.first(where: { $0.id == gameID }), stored.status == .ongoing,
              store?.requiresDataRecovery == false else { return }
        do {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw LiveError.message("无法生成发布凭证") }
            try update(LiveBinding(gameID: gameID, token: bytes.map { String(format: "%02x", $0) }.joined()))
            messages[gameID] = "正在开启直播…"; requestSync()
        } catch { messages[gameID] = error.localizedDescription }
    }
    func close(_ id: UUID) {
        guard isEnabled, var b = binding(for: id) else { return }
        b.closing = true; b.blocked = false
        do { try update(b); retryAt[id] = nil; requestSync() } catch { messages[id] = error.localizedDescription }
    }
    /// Restoring local history must never silently overwrite a currently published match.
    func detachAfterRestore() {
        guard isEnabled else { return }
        generation += 1
        retryAt.removeAll(); failures.removeAll()
        var next = bindings
        for i in next.indices { next[i].closing = true; next[i].blocked = false }
        do { try save(next); requestSync() }
        catch { bindings = next; credentialError = "备份已恢复；直播凭证更新失败，请重新启动 App 检查直播状态。" }
    }
    func setForeground(_ active: Bool) {
        guard isEnabled else { return }
        ticker?.cancel(); ticker = nil
        if active {
            requestSync()
            ticker = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                    if !Task.isCancelled { self?.requestSync() }
                }
            }
        } else { requestSync() }
    }
    func retry(_ id: UUID) { guard isEnabled else { return }; retryAt[id] = nil; requestSync() }
    func requestSync() {
        guard isEnabled, credentialError == nil else { return }
        requested = true
        guard worker == nil else { return }
        worker = Task { [weak self] in
            guard let self else { return }
            while self.requested && !Task.isCancelled {
                self.requested = false
                await self.synchronize()
            }
            self.worker = nil
        }
    }
    func waitForPendingSync() async { await worker?.value }

    /// Also exposed internally for deterministic transport/vault tests.
    func synchronize() async {
        guard isEnabled, credentialError == nil, store?.requiresDataRecovery == false else { return }
        for initial in bindings {
            guard var b = binding(for: initial.gameID), !b.blocked,
                  retryAt[b.gameID].map({ $0 <= Date() }) ?? true else { continue }
            let currentGeneration = generation
            do {
                if let expiry = b.expiresAt, expiry <= Date() {
                    try save(bindings.filter { $0.gameID != b.gameID }); messages[b.gameID] = "直播已过期，旧链接不再可用。"; continue
                }
                var stored = store?.games.first { $0.id == b.gameID }
                if stored == nil { b.closing = true; try update(b) }
                if b.closing {
                    let body = b.code == nil ? try JSONSerialization.data(withJSONObject: ["requestID": b.requestID.uuidString]) : nil
                    _ = try await transport.send(method: "DELETE", code: b.code, token: b.token, body: body)
                    try save(bindings.filter { $0.gameID != b.gameID }); messages[b.gameID] = "直播已关闭，云端资料已删除。"; continue
                }
                guard let saved = stored else { try save(bindings.filter { $0.gameID != b.gameID }); continue }
                let projection = LiveSnapshot(saved)
                if b.ackRevision == (saved.revision ?? 0), b.ackDigest != SHA256.hash(data: try projection.data()).map({ String(format: "%02x", $0) }).joined(),
                   b.ackDigest == SHA256.hash(data: try projection.legacyData()).map({ String(format: "%02x", $0) }).joined() {
                    // Persist a higher publication revision before uploading new fields to an existing 2.1 link.
                    stored = try store?.advanceLiveProjectionRevision(for: saved.id, matching: saved.revision ?? 0)
                }
                guard let stored else { continue }
                let data = try LiveSnapshot(stored).data()
                let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                if b.code == nil {
                    // Retry the same creation identity even when a previous response was lost.
                    let snapshotObject = try JSONSerialization.jsonObject(with: data)
                    let body = try JSONSerialization.data(withJSONObject: ["requestID": b.requestID.uuidString, "createdAt": b.createdAt.timeIntervalSince1970 * 1000, "snapshot": snapshotObject], options: [.sortedKeys])
                    let result = try await transport.send(method: "POST", code: nil, token: b.token, body: body)
                    guard let result else { throw LiveError.message("开播响应不完整") }
                    b.code = result.code
                    // A close/restore can arrive while the request is in flight. Preserve its intent.
                    b.closing = binding(for: b.gameID)?.closing == true || generation != currentGeneration
                    try update(b)
                    requested = true
                    if b.closing { continue }
                }
                if let ack = b.ackRevision, (stored.revision ?? 0) < ack || ((stored.revision ?? 0) == ack && b.ackDigest != digest) {
                    b.blocked = true; try update(b); messages[b.gameID] = "本地资料与已发布版本不一致，请关闭直播后重新开启。"; continue
                }
                let unchanged = b.ackRevision == (stored.revision ?? 0) && b.ackDigest == digest
                let result = try await transport.send(method: unchanged ? "POST" : "PUT", code: b.code, token: b.token, body: unchanged ? nil : data)
                guard let result else { throw LiveError.message("同步响应不完整") }
                b.ackRevision = stored.revision ?? 0; b.ackDigest = digest
                b.lastSync = Date(); b.expiresAt = Date().addingTimeInterval((result.expiresAt - result.serverTime) / 1000)
                b.closing = binding(for: b.gameID)?.closing == true || generation != currentGeneration
                try update(b); failures[b.gameID] = nil; retryAt[b.gameID] = nil
                messages[b.gameID] = stored.state.isFinal ? "终场已同步 · 一小时后删除" : "已同步 · 网页每 10 秒刷新"
            } catch {
                if !b.closing && (binding(for: b.gameID)?.closing == true || generation != currentGeneration) {
                    b.closing = true; b.blocked = false
                    do { try update(b); retryAt[b.gameID] = nil; requested = true }
                    catch { credentialError = error.localizedDescription }
                    continue
                }
                if case LiveError.server(let status, _) = error, [404, 410].contains(status) {
                    do { try save(bindings.filter { $0.gameID != b.gameID }) } catch { credentialError = error.localizedDescription }
                    messages[b.gameID] = "直播已关闭或过期，旧链接不再可用。"
                } else {
                    let count = (failures[b.gameID] ?? 0) + 1; failures[b.gameID] = count
                    retryAt[b.gameID] = Date().addingTimeInterval(min(60, pow(2, Double(min(count, 6)))))
                    messages[b.gameID] = "待同步：\(error.localizedDescription)"
                    if case LiveError.server(let status, _) = error, [400, 401, 403, 409, 413, 415].contains(status) {
                        b.blocked = true
                        do { try update(b) } catch { credentialError = error.localizedDescription }
                        messages[b.gameID] = "\(error.localizedDescription)。请关闭直播后重新开启。"
                    }
                }
            }
        }
    }
}

#if DEBUG
// Controlled network failures for the isolated simulator integration only.
// Healthy requests still use the production HTTPS client; excluded from Release builds.
@MainActor
final class LiveIntegrationTransport: LiveTransport {
    static let shared = LiveIntegrationTransport()
    var offline = false
    func send(method: String, code: String?, token: String, body: Data?) async throws -> LiveResponse? {
        if offline { throw URLError(.notConnectedToInternet) }
        return try await LiveHTTPClient().send(method: method, code: code, token: token, body: body)
    }
}

/// Isolated in-memory credentials for opt-in local UI integration; never reads production Keychain items.
final class LivePreviewCredentials: LiveCredentialStorage {
    private var values: [LiveBinding] = []
    func read() throws -> [LiveBinding] { values }
    func write(_ bindings: [LiveBinding]) throws { values = bindings }
}
#endif
