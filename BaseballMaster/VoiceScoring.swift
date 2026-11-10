import AVFoundation
import CryptoKit
import Security
import SwiftUI

enum VoiceScoringError: LocalizedError {
    case message(String)
    case unauthorized
    case storage
    var errorDescription: String? {
        switch self {
        case .message(let text): text
        case .unauthorized: "语音权限已失效，请在 App 设置中重新开通。"
        case .storage: "暂时无法保存语音权限，请解锁设备后重试。"
        }
    }
}

struct VoiceGrant: Decodable {
    let id: String
    let url: URL
    let maxRecordingSeconds: Int
}

struct VoiceAnalysis: Decodable {
    struct Ball: Decodable {
        let type: String
        let region: String
        let outcome: String
        var intent: String? = nil
    }
    struct Event: Decodable {
        let sequence: Int
        let kind: String
        let playerId: String?
        let fromBase: String?
        let toBase: String?
        let outType: String?
        let pitchResult: String?
        let evidence: String
        var fromFielder: Int? = nil
        var toFielder: Int? = nil
        var cause: String? = nil
    }
    let schemaVersion: Int
    let summary: String
    let ball: Ball
    let events: [Event]
    let observations: [String]
}

struct VoiceService {
    struct Entitlement: Decodable { let active: Bool }
    struct ModelResponse: Decodable { let analysis: VoiceAnalysis }
    private struct Failure: Decodable { let message: String? }
    static let cookie = "voice_assistant_session"
    let baseURL: URL
    let session: URLSession

    init(baseURL: URL = URL(string: "https://baseballmaster.cc/recording/voice-assistant/")!,
         session: URLSession? = nil) {
        self.baseURL = baseURL
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 240
        config.timeoutIntervalForResource = 600
        self.session = session ?? URLSession(configuration: config, delegate: VoiceNoRedirect(), delegateQueue: nil)
    }

    func redeem(code: String, device: UUID, operation: UUID) async throws -> String {
        let (data, response) = try await request("redeem", body: [
            "code": .string(code), "deviceId": .uuid(device), "operationId": .uuid(operation)
        ])
        guard try JSONDecoder().decode(Entitlement.self, from: data).active,
              let header = response.value(forHTTPHeaderField: "Set-Cookie"),
              let cookie = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": header], for: baseURL)
                .first(where: { $0.name == Self.cookie }),
              cookie.value.range(of: "^[A-Za-z0-9_-]{32}$", options: .regularExpression) != nil else {
            throw VoiceScoringError.message("开通响应未完整收到，请使用同一个开启码重试。")
        }
        return cookie.value
    }

    func entitlement(_ token: String) async throws -> Bool {
        let (data, _) = try await request("entitlement", token: token)
        return try JSONDecoder().decode(Entitlement.self, from: data).active
    }

    func grant(_ context: MatchJSON, token: String) async throws -> VoiceGrant {
        let (data, _) = try await request("asr-session", body: ["context": context], token: token)
        return try JSONDecoder().decode(VoiceGrant.self, from: data)
    }

    func end(_ id: String, token: String) async {
        _ = try? await request("asr-session/end", body: ["id": .string(id)], token: token)
    }

    func analyze(_ text: String, context: MatchJSON, token: String) async throws -> VoiceAnalysis {
        let (data, _) = try await request("analyze", body: [
            "text": .string(text), "context": context, "modelId": .string("hy3"), "thinking": .string("disabled")
        ], token: token)
        let result = try JSONDecoder().decode(ModelResponse.self, from: data).analysis
        guard result.schemaVersion == 1 else { throw VoiceScoringError.message("当前 App 无法读取此解析结果。") }
        return result
    }

    private func request(_ path: String, body: [String: MatchJSON]? = nil, token: String? = nil) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: baseURL.appendingPathComponent("api").appendingPathComponent(path))
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let token { request.setValue("\(Self.cookie)=\(token)", forHTTPHeaderField: "Cookie") }
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(MatchJSON.object(body))
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401, token != nil { throw VoiceScoringError.unauthorized }
        guard (200..<300).contains(http.statusCode) else {
            throw VoiceScoringError.message((try? JSONDecoder().decode(Failure.self, from: data))?.message
                ?? "语音服务暂时不可用，请重试或继续手动记分。")
        }
        return (data, http)
    }
}

private final class VoiceNoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class VoiceScoringAccess: ObservableObject {
    static let shared = VoiceScoringAccess()
    let service = VoiceService()
    @Published private(set) var active = false
    @Published private(set) var redeeming = false
    private(set) var token: String?
    private struct Device: Codable {
        var id = UUID()
        var token: String?
        var operation: UUID?
        var codeDigest: Data?
    }
    private let key: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.jasonchen.baseballmaster.voice-scoring",
        kSecAttrAccount as String: "voice-access-v1"
    ]

    private init() {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--voice-access-preview") {
            active = true
            return
        }
        #endif
        if let saved = try? read().token {
            token = saved
            active = true
        }
    }

    func refresh() async {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--voice-access-preview") { return }
        #endif
        guard !redeeming, let saved = try? read().token else { return }
        do {
            let enabled = try await service.entitlement(saved)
            guard !redeeming, (try? read().token) == saved else { return }
            if enabled {
                token = saved
                active = true
            } else {
                clear(saved)
            }
        } catch {
            report(error, for: saved)
        }
    }

    func redeem(_ input: String) async throws {
        let code = input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !redeeming else { return }
        guard !code.isEmpty, code.utf16.count <= 80 else {
            throw VoiceScoringError.message("请核对开启码。")
        }
        redeeming = true
        defer { redeeming = false }
        var device = try read()
        let digest = Data(SHA256.hash(data: Data(code.utf8)))
        if device.codeDigest != digest || device.operation == nil {
            device.codeDigest = digest
            device.operation = UUID()
            try write(device)
        }
        guard let operation = device.operation else { throw VoiceScoringError.storage }
        let grant = try await service.redeem(code: code, device: device.id, operation: operation)
        device.token = grant
        device.codeDigest = nil
        device.operation = nil
        try write(device)
        token = grant
        active = true
    }

    func report(_ error: Error, for credential: String) {
        if case VoiceScoringError.unauthorized = error { clear(credential) }
    }

    private func clear(_ credential: String) {
        guard !redeeming, var device = try? read(), device.token == credential else { return }
        token = nil
        active = false
        device.token = nil
        try? write(device)
    }

    private func read() throws -> Device {
        var result: CFTypeRef?
        var query = key
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return Device() }
        guard status == errSecSuccess, let data = result as? Data,
              let device = try? JSONDecoder().decode(Device.self, from: data) else { throw VoiceScoringError.storage }
        return device
    }

    private func write(_ device: Device) throws {
        let data = try JSONEncoder().encode(device)
        let attributes = [kSecValueData as String: data]
        let status = SecItemUpdate(key as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw VoiceScoringError.storage }
        var insert = key
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw VoiceScoringError.storage }
    }
}

struct VoicePlayContext {
    let gameID: UUID
    let revision: Int
    let state: GameState
    let payload: MatchJSON

    @MainActor static func sessionID(for store: GameStore) -> UUID? {
        store.activeStoredGame?.id ?? store.practiceSessionID
    }

    @MainActor init?(_ store: GameStore) {
        guard let id = Self.sessionID(for: store), store.canRecordAction,
              !store.requiresTiebreakRunnerPlacement, !store.isHistoryReplay,
              store.game.inning <= 99, store.game.outs <= 2,
              (0...3).contains(store.game.balls), (0...2).contains(store.game.strikes) else { return nil }
        gameID = id
        revision = store.activeStoredGame?.revision ?? 0
        state = store.game
        let batter = state.currentBatter
        let active = store.activeFielders.filter { $0.primaryPosition.rawValue <= 9 }
        var fielders: [String: MatchJSON] = [:]
        for player in active { fielders[String(player.primaryPosition.rawValue)] = .uuid(player.id) }
        let runners: [MatchJSON] = Base.allCases.compactMap { base in
            guard let runner = store.game.baseRunners[base] else { return nil }
            return .object(["playerId": .uuid(runner.id), "base": .string(base.voiceName),
                            "responsiblePitcherId": store.game.runnerPitcherIDs?[runner.id].map(MatchJSON.uuid) ?? .null,
                            "earnedStatus": .string("unknown")])
        }
        let rulesProfile = store.isSlowPitch ? "BM-slowPitch" : store.isCoachPitch ? "BM-coachPitch" : "OBR-2026"
        payload = .object([
            "schemaVersion": .number(1), "gameId": .uuid(gameID), "revision": .number(Double(revision)),
            "plateAppearanceId": .string(state.currentPlateAppearanceID?.uuidString.lowercased() ?? "\(gameID.uuidString.lowercased())-\(revision)"),
            "rulesProfile": .string(rulesProfile), "inning": .number(Double(state.inning)),
            "half": .string(state.isTop ? "top" : "bottom"), "outs": .number(Double(state.outs)),
            "balls": .number(Double(state.balls)), "strikes": .number(Double(state.strikes)),
            "scores": .object(["away": .number(Double(state.awayScore)), "home": .number(Double(state.homeScore))]),
            "batter": .object(["id": .uuid(batter.id), "number": .string(batter.numberText), "name": .string(batter.name)]),
            "pitcherId": store.isCoachPitch || active.isEmpty ? .null : .uuid(state.currentPitcher.id),
            "fielders": .object(fielders), "runners": .array(runners), "historyComplete": .bool(false),
            "virtualOuts": .null, "pendingIssues": .array([])
        ])
    }

    @MainActor func matches(_ store: GameStore) -> Bool {
        store.canRecordAction && !store.isHistoryReplay && !store.requiresTiebreakRunnerPlacement
            && gameID == Self.sessionID(for: store) && revision == (store.activeStoredGame?.revision ?? 0) && state == store.game
    }
}

private extension Base {
    var voiceName: String {
        switch self { case .first: "first"; case .second: "second"; case .third: "third" }
    }
}

@MainActor
final class VoiceScoringRecorder: ObservableObject {
    enum Phase: Equatable { case idle, connecting, recording, finishing }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var transcript = ""
    @Published private(set) var finalized = false
    @Published private(set) var error: Error?
    private var generation = UUID()
    private var socket: URLSessionWebSocketTask?
    private let socketSession = URLSession(configuration: .ephemeral, delegate: VoiceNoRedirect(), delegateQueue: nil)
    private let engine = AVAudioEngine()
    private var tapInstalled = false
    private var audioSessionActive = false
    private var stream: AsyncThrowingStream<Data, Error>.Continuation?
    private var receiveTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?
    private var deadline: Task<Void, Never>?
    private var recordingLimit = 90
    private var releaseGrant: (() -> Void)?
    private var parts: [Int: String] = [:]
    private var sentEnd = false

    func start(context: MatchJSON, token: String, service: VoiceService) {
        guard phase == .idle else { return }
        error = nil
        transcript = ""
        finalized = false
        parts = [:]
        sentEnd = false
        phase = .connecting
        let attempt = generation
        receiveTask = Task { [weak self] in
            guard let self else { return }
            do {
                let allowed = await withCheckedContinuation { continuation in
                    AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
                }
                guard generation == attempt else { return }
                guard allowed else { throw VoiceScoringError.message("请在系统设置中允许麦克风，或继续手动记分。") }
                let grant = try await service.grant(context, token: token)
                guard generation == attempt else {
                    await service.end(grant.id, token: token)
                    return
                }
                releaseGrant = { Task { await service.end(grant.id, token: token) } }
                guard grant.url.scheme == "wss", grant.url.host == "asr.cloud.tencent.com",
                      grant.url.port == nil, grant.url.user == nil, grant.url.password == nil, grant.url.fragment == nil,
                      grant.url.path.range(of: #"^/asr/v2/[0-9]+$"#, options: .regularExpression) != nil else {
                    throw VoiceScoringError.message("未取得有效的腾讯语音识别连接。")
                }
                recordingLimit = min(90, max(1, grant.maxRecordingSeconds))
                let connection = socketSession.webSocketTask(with: grant.url)
                socket = connection
                connection.resume()
                scheduleDeadline(seconds: 15) { [weak self] in self?.fail("语音连接未就绪，请重试。") }
                while generation == attempt {
                    let message = try await connection.receive()
                    guard generation == attempt else { return }
                    guard case .string(let text) = message else {
                        throw VoiceScoringError.message("语音服务返回了无法读取的数据。")
                    }
                    try receive(text, attempt: attempt)
                }
            } catch {
                guard generation == attempt else { return }
                if error is VoiceScoringError { cancel(); self.error = error }
                else { fail("语音连接中断，请重试或继续手动记分。") }
            }
        }
    }

    func stop() {
        guard phase == .recording else { return }
        phase = .finishing
        stopAudio()
        stream?.finish()
        scheduleDeadline(seconds: 20) { [weak self] in self?.fail("未收到最终转写，请重新录音。") }
    }

    func cancel() {
        generation = UUID()
        phase = .idle
        receiveTask?.cancel(); receiveTask = nil
        sendTask?.cancel(); sendTask = nil
        deadline?.cancel(); deadline = nil
        stopAudio()
        stream?.finish(); stream = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        releaseGrant?(); releaseGrant = nil
    }

    func interrupt() {
        guard phase != .idle else { return }
        fail("录音已中断，请重新录音或继续手动记分。")
    }

    private func fail(_ message: String) {
        cancel()
        error = VoiceScoringError.message(message)
    }

    private func scheduleDeadline(seconds: Int, action: @escaping @MainActor () -> Void) {
        deadline?.cancel()
        deadline = Task {
            try? await Task.sleep(for: .seconds(seconds))
            if !Task.isCancelled { action() }
        }
    }

    private func receive(_ text: String, attempt: UUID) throws {
        guard let message = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              (message["code"] == nil || message["code"] as? Int == 0) else {
            throw VoiceScoringError.message("腾讯语音识别未完成，请检查网络后重试。")
        }
        let final = (message["final"] as? Int) == 1
        if final && !sentEnd { throw VoiceScoringError.message("识别提前结束，请重新录音。") }
        if phase == .connecting { try startAudio(attempt: attempt) }
        if let result = message["result"] as? [String: Any], let text = result["voice_text_str"] as? String {
            parts[result["index"] as? Int ?? 0] = text
        } else {
            let container = message["sentences"]
            let group = container as? [String: Any]
            let sentences = container as? [[String: Any]] ?? group?["sentence_list"] as? [[String: Any]] ?? group.map { [$0] } ?? []
            for (index, sentence) in sentences.enumerated() {
                let nested = sentence["sentence"] as? [String: Any] ?? sentence
                let rawID = sentence["sentence_id"] ?? nested["sentence_id"]
                let id = (rawID as? Int) ?? (rawID as? String).flatMap(Int.init) ?? index
                if let text = sentence["sentence"] as? String ?? nested["text"] as? String ?? nested["voice_text_str"] as? String {
                    parts[id] = text
                }
            }
        }
        transcript = parts.keys.sorted().compactMap { parts[$0] }.joined()
        if final {
            finalized = !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            cancel()
            if !finalized { error = VoiceScoringError.message("没有听到清楚的口述，请重新录音。") }
        }
    }

    private func startAudio(attempt: UUID) throws {
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.record, mode: .measurement)
        try audio.setActive(true)
        audioSessionActive = true
        let input = engine.inputNode
        let source = input.outputFormat(forBus: 0)
        guard source.sampleRate > 0, source.channelCount > 0,
              let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: source, to: format), let socket else {
            throw VoiceScoringError.message("当前麦克风不可用，请检查输入设备。")
        }

        let frames = AsyncThrowingStream<Data, Error>.makeStream(bufferingPolicy: .bufferingOldest(24))
        stream = frames.continuation
        input.installTap(onBus: 0, bufferSize: 4096, format: source) { buffer, _ in
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16_000 / source.sampleRate) + 16)
            guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
            var provided = false
            var conversionError: NSError?
            let result = converter.convert(to: output, error: &conversionError) { _, status in
                if provided { status.pointee = .noDataNow; return nil }
                provided = true
                status.pointee = .haveData
                return buffer
            }
            if result == .error {
                frames.continuation.finish(throwing: VoiceScoringError.message("音频格式转换失败，请重试。"))
            } else if output.frameLength > 0, let samples = output.int16ChannelData?[0] {
                let frame = Data(bytes: samples, count: Int(output.frameLength) * MemoryLayout<Int16>.size)
                if case .dropped = frames.continuation.yield(frame) {
                    frames.continuation.finish(throwing: VoiceScoringError.message("网络跟不上录音，请重试。"))
                }
            }
        }
        tapInstalled = true
        engine.prepare()
        try engine.start()
        phase = .recording
        scheduleDeadline(seconds: recordingLimit) { [weak self] in self?.stop() }
        sendTask = Task { [weak self] in
            do {
                var pending = Data()
                for try await frame in frames.stream {
                    try Task.checkCancellation()
                    pending.append(frame)
                    while pending.count >= 6400 {
                        try await socket.send(.data(Data(pending.prefix(6400))))
                        pending.removeFirst(6400)
                    }
                }
                guard let self, generation == attempt, phase == .finishing else { return }
                if !pending.isEmpty { try await socket.send(.data(pending)) }
                guard generation == attempt else { return }
                sentEnd = true
                try await socket.send(.string(#"{"type":"end"}"#))
            } catch {
                guard let self, generation == attempt else { return }
                fail((error as? VoiceScoringError)?.localizedDescription ?? "录音未发送完整，请重试。")
            }
        }
    }

    private func stopAudio() {
        if tapInstalled {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if audioSessionActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            audioSessionActive = false
        }
    }
}

enum VoicePlayProposal {
    case pitch(PitchAction)
    case hit(SituationExistingPlay)

    var title: String {
        switch self {
        case .pitch(let pitch): pitch.title
        case .hit(let play): play.outcome.rawValue
        }
    }

    init?(analysis: VoiceAnalysis, context: VoicePlayContext, transcript: String) {
        let events = analysis.events.sorted { $0.sequence < $1.sequence }
        guard !events.isEmpty, Set(events.map(\.sequence)).count == events.count,
              events.allSatisfy({ !$0.evidence.isEmpty && transcript.contains($0.evidence) }) else { return nil }
        if events.count == 1, let event = events.first, event.kind == "pitch",
           analysis.ball.outcome == "unknown", analysis.observations.isEmpty {
            switch event.pitchResult {
            case "ball": self = .pitch(.ball)
            case "called_strike": self = .pitch(.calledStrike)
            case "swinging_strike": self = .pitch(.swingingStrike)
            case "foul": self = .pitch(.foul)
            default: return nil
            }
            return
        }
        // Support straightforward batted balls. Compound outs, errors and uncertain runner actions stay manual.
        let onlyMissingLocation = analysis.observations.allSatisfy(Self.isMissingLocation)
        guard onlyMissingLocation, analysis.ball.intent != "sacrifice",
              events.allSatisfy({ ["field", "throw", "advance", "out", "score"].contains($0.kind)
                  || ($0.kind == "pitch" && $0.pitchResult == "in_play") }),
              events.filter({ $0.kind == "pitch" }).count <= 1 else { return nil }
        let type = BattedBallType(rawValue: analysis.ball.type) ?? .unknown
        let batter = context.state.currentBatter
        let outcome: PlayOutcome
        let batterDestination: RunnerDestination
        switch analysis.ball.outcome {
        case "single": (outcome, batterDestination) = (.single, .base(.first))
        case "double": (outcome, batterDestination) = (.double, .base(.second))
        case "triple": (outcome, batterDestination) = (.triple, .base(.third))
        case "home_run": (outcome, batterDestination) = (.homeRun, .score)
        case "out":
            guard let out = events.first(where: { $0.kind == "out" }), out.playerId.flatMap(UUID.init(uuidString:)) == batter.id else { return nil }
            switch (type, out.outType) {
            case (.ground, "batter_before_first"): outcome = .groundOut
            case (.line, "catch"): outcome = .lineOut
            case (.fly, "catch"), (.popup, "catch"): outcome = .flyOut
            default: return nil
            }
            batterDestination = .out
        default: return nil
        }

        var players: [(Player, RunnerOrigin)] = [(batter, .batter)]
        for base in Base.allCases {
            if let player = context.state.baseRunners[base] { players.append((player, .base(base))) }
        }
        let movements = events.filter { ["advance", "out", "score"].contains($0.kind) }
        guard movements.allSatisfy({ event in
            (event.cause == nil || event.cause == "batted_ball")
                && players.contains { $0.0.id == event.playerId.flatMap(UUID.init(uuidString:)) }
        }) else { return nil }
        var decisions: [RunnerDecision] = []
        for (player, origin) in players {
            let legs = movements.filter { $0.playerId.flatMap(UUID.init(uuidString:)) == player.id }
            guard !legs.isEmpty else { return nil }
            var from: String
            switch origin { case .batter: from = "batter"; case .base(let base): from = base.voiceName }
            var destination: RunnerDestination?
            for leg in legs {
                guard leg.fromBase == from, from != "home", destination != .out else { return nil }
                switch leg.kind {
                case "out":
                    guard origin == .batter, legs.count == 1, outcome.recordedOuts == 1,
                          leg.toBase == (leg.outType == "catch" ? "batter" : "first") else { return nil }
                    destination = .out
                case "advance":
                    guard let base = Base.allCases.first(where: { $0.voiceName == leg.toBase }),
                          let position = ["batter", "first", "second", "third"].firstIndex(of: from),
                          base.rawValue > position else { return nil }
                    from = base.voiceName
                    destination = .base(base)
                case "score":
                    guard leg.toBase == "home", origin != .batter || outcome == .homeRun else { return nil }
                    from = "home"
                    destination = .score
                default: return nil
                }
            }
            guard let destination, origin != .batter || destination == batterDestination,
                  outcome != .homeRun || destination == .score else { return nil }
            decisions.append(RunnerDecision(player: player, origin: origin, destination: destination))
        }
        let ranks = decisions.compactMap { decision -> Int? in
            switch decision.destination {
            case .base(let base): base.rawValue
            case .score: 4
            default: nil
            }
        }
        guard zip(ranks, ranks.dropFirst()).allSatisfy({ $0 < $1 || ($0 == 4 && $1 == 4) }),
              !(context.state.outs == 2 && outcome.recordedOuts == 1 && decisions.contains { $0.destination == .score }) else { return nil }

        var observation = BattedBallObservation(type: type)
        if analysis.observations.isEmpty {
            observation.selectRegion(Self.region(analysis.ball.region, transcript: transcript))
        }
        if outcome.recordedOuts == 1 {
            observation.fielding = Self.defense(for: outcome, events: events, state: context.state)
        }
        self = .hit(SituationExistingPlay(outcome: outcome, observation: observation, decisions: decisions, timing: nil))
    }

    private static func defense(for outcome: PlayOutcome, events: [VoiceAnalysis.Event], state: GameState) -> BattedBallFielding? {
        let active = Set(state.fieldingTeam.players.filter { state.fieldingPlayerIDs.contains($0.id) }.map(\.primaryPosition))
        func position(_ number: Int?) -> FieldPosition? {
            guard let value = number.flatMap(FieldPosition.init(rawValue:)), active.contains(value) else { return nil }
            return value
        }
        guard let out = events.first(where: { $0.kind == "out" }), let putout = position(out.toFielder) else { return nil }
        let handling = events.filter { $0.kind == "field" || $0.kind == "throw" }
        if outcome == .flyOut || outcome == .lineOut {
            guard handling.allSatisfy({ $0.kind == "field" && $0.sequence < out.sequence && position($0.toFielder) == putout }) else { return nil }
            return BattedBallFielding(status: .recorded, route: [putout])
        }
        guard let first = handling.first, first.kind == "field", let initial = position(first.toFielder),
              first.sequence < out.sequence, handling.count <= 50 else { return nil }
        var route = [initial]
        for leg in handling.dropFirst() {
            guard leg.kind == "throw", leg.sequence < out.sequence, position(leg.fromFielder) == route.last,
                  let target = position(leg.toFielder), target != route.last else { return nil }
            route.append(target)
        }
        return route.last == putout ? BattedBallFielding(status: .recorded, route: route) : nil
    }

    private static func isMissingLocation(_ text: String) -> Bool {
        let compact = text.replacingOccurrences(of: "。", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        return ["未提及落点", "未说明落点", "落点未知", "落点不明", "落点未说明", "落点未提及",
                "未提及击球落点", "未说明击球落点", "未描述落点", "落点未描述"].contains(compact)
    }

    private static func region(_ name: String, transcript: String) -> BattedBallRegion? {
        let labels: [(BattedBallRegion, [String])] = [
            (.infieldLeft, ["内野左侧"]), (.infieldMiddle, ["内野中间"]), (.infieldRight, ["内野右侧"]),
            (.left, ["左外野"]), (.center, ["中外野"]), (.right, ["右外野"]),
            (.foulLeft, ["左侧界外"]), (.foulRight, ["右侧界外"])
        ]
        let words = transcript.replacingOccurrences(of: "野手", with: "手")
        return labels.first { _, phrases in phrases.contains { name.contains($0) && words.contains($0) } }?.0
    }
}
