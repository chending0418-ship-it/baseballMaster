import SwiftUI

/// Missing on old games: keep their original scoring flow.
enum RecordingMethod: String, Codable, CaseIterable, Identifiable {
    case traditional, situation
    var id: String { rawValue }
    var title: String { self == .traditional ? "基础版" : "Pro 版" }
    var detail: String {
        self == .traditional ? "先选打者最终位置，再判断原因。" : "球型、落点和守备在同一个球场记录，再统一核对跑者。"
    }
}

enum BattedBallType: String, Codable, CaseIterable, Identifiable {
    case ground, hardGround, line, fly, popup, bunt, unknown
    var id: String { rawValue }
    var title: String {
        switch self {
        case .ground: "地滚"
        case .hardGround: "强力地滚"
        case .line: "平飞"
        case .fly: "高飞"
        case .popup: "内野小飞"
        case .bunt: "触击"
        case .unknown: "没有看清"
        }
    }
    var symbol: String {
        switch self {
        case .ground: "地"
        case .hardGround: "强"
        case .line: "平"
        case .fly: "高"
        case .popup: "小"
        case .bunt: "触"
        case .unknown: "?"
        }
    }
}

enum BattedBallRegion: String, Codable, CaseIterable, Identifiable {
    case infieldLeft, infieldMiddle, infieldRight, left, center, right, foulLeft, foulRight
    var id: String { rawValue }
    var title: String {
        switch self {
        case .infieldLeft: "内野左侧"
        case .infieldMiddle: "内野中间"
        case .infieldRight: "内野右侧"
        case .left: "左外野方向"
        case .center: "中外野方向"
        case .right: "右外野方向"
        case .foulLeft: "左侧界外"
        case .foulRight: "右侧界外"
        }
    }
    /// Only an illustration of an area, never an observed point.
    var illustration: CGPoint {
        switch self {
        case .infieldLeft: CGPoint(x: 0.36, y: 0.60)
        case .infieldMiddle: CGPoint(x: 0.50, y: 0.51)
        case .infieldRight: CGPoint(x: 0.64, y: 0.60)
        case .left: CGPoint(x: 0.23, y: 0.31)
        case .center: CGPoint(x: 0.50, y: 0.20)
        case .right: CGPoint(x: 0.77, y: 0.31)
        case .foulLeft: CGPoint(x: 0.12, y: 0.70)
        case .foulRight: CGPoint(x: 0.88, y: 0.70)
        }
    }
}

struct BattedBallObservation: Equatable, Codable {
    enum Precision: String, Codable { case unknown, region, point }
    var type: BattedBallType = .unknown
    var precision: Precision = .unknown
    var region: BattedBallRegion?
    var x: Double?
    var y: Double?
    var fielding: BattedBallFielding? = nil
    var runnerDetails: [BattedBallRunnerDetail]? = nil
    var isValid: Bool {
        guard fielding?.isValid != false, runnerDetails?.allSatisfy({ $0.fielding?.isValid != false }) != false else { return false }
        return switch precision {
        case .unknown: region == nil && x == nil && y == nil
        case .region: region != nil && x == nil && y == nil
        case .point: region == nil && x.map { $0.isFinite && (0...1).contains($0) } == true
            && y.map { $0.isFinite && (0...1).contains($0) } == true
        }
    }
    var hasLocation: Bool { isValid && precision != .unknown }
    var illustration: CGPoint? {
        guard isValid else { return nil }
        if precision == .region { return region?.illustration }
        if let x, let y { return CGPoint(x: x, y: y) }
        return nil
    }
    var summary: String {
        type.title + " · " + (precision == .point ? "点选去向" : region?.title ?? "位置未知")
    }
    mutating func selectRegion(_ value: BattedBallRegion?) {
        region = value; x = nil; y = nil; precision = value == nil ? .unknown : .region
    }
    mutating func selectPoint(_ point: CGPoint) {
        x = min(1, max(0, point.x)); y = min(1, max(0, point.y)); region = nil; precision = .point
    }
}

extension GameState {
    func liveText(for event: ScoringEventRecord) -> String {
        guard event.defensiveRoute == nil, event.reviewedAt == nil,
              let operation = historyJournal?.operations.first(where: { $0.eventIDs.contains(event.id) }) else { return event.title }
        let defense: DefensivePlay?
        switch operation.command {
        case .play(_, let play, _, _): defense = play
        case .illegalPitch(_, _, _, let play, _, _, _): defense = play
        default: defense = nil
        }
        guard let defense else { return event.title }
        let positions = defense.routePositions.isEmpty ? defense.errorPosition.map { [$0] } ?? [] : defense.routePositions
        let ids = event.isTop ? operation.context.homeFielders : operation.context.awayFielders
        let team = event.isTop ? homeTeam : awayTeam
        let names = positions.map { position in
            let player = team.players.first { ids.contains($0.id) && operation.context.positions[$0.id] == position }
            return position.fullName + (player?.name ?? "（姓名未记录）")
        }.joined(separator: "-")
        guard !names.isEmpty else { return event.title }
        return event.title + "；防守路线 \(defense.notation)，" + names
    }

    func defensiveRouteDescription(_ play: DefensivePlay) -> String {
        let route = play.routePositions.isEmpty ? play.errorPosition.map { [$0] } ?? [] : play.routePositions
        let names = route.map { position in
            let player = fieldingTeam.players.first { fieldingPlayerIDs.contains($0.id) && $0.primaryPosition == position }
            return position.fullName + (player?.name ?? "（姓名未记录）")
        }.joined(separator: "-")
        return "防守路线 \(play.notation)" + (names.isEmpty ? "（参与人待补充）" : "，\(names)")
    }
}

struct RecordingMethodPicker: View {
    @Binding var method: RecordingMethod
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("记录方式").font(.headline)
            Picker("记录方式", selection: $method) {
                ForEach(RecordingMethod.allCases) { Text($0.title).tag($0) }
            }.pickerStyle(.segmented).accessibilityIdentifier("recording-method-picker")
            Text(method.detail).font(.caption).foregroundStyle(BMTheme.secondaryText)
        }.padding(.vertical, 8)
    }
}

/// Compact field for performance plots and legacy observation editing. No position labels.
struct BattedBallField: View {
    var observation: BattedBallObservation?
    var marks: [BatterContact] = []
    var onPoint: ((CGPoint) -> Void)?
    var onMark: ((BatterContact) -> Void)?
    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                RoundedRectangle(cornerRadius: 16).fill(BMTheme.greenSoft)
                Path { p in
                    let home = point(CGPoint(x: 0.5, y: 0.90), size)
                    p.move(to: home)
                    p.addLine(to: point(CGPoint(x: 0.08, y: 0.38), size))
                    p.addQuadCurve(to: point(CGPoint(x: 0.92, y: 0.38), size), control: point(CGPoint(x: 0.5, y: -0.13), size))
                    p.closeSubpath()
                }.fill(BMTheme.green.opacity(0.18))
                Path { p in
                    p.move(to: point(CGPoint(x: 0.5, y: 0.90), size))
                    p.addLine(to: point(CGPoint(x: 0.25, y: 0.63), size))
                    p.addLine(to: point(CGPoint(x: 0.5, y: 0.36), size))
                    p.addLine(to: point(CGPoint(x: 0.75, y: 0.63), size))
                    p.closeSubpath()
                }.stroke(BMTheme.orange.opacity(0.65), lineWidth: 2)
                Path { p in
                    p.move(to: point(CGPoint(x: 0.02, y: 0.30), size))
                    p.addLine(to: point(CGPoint(x: 0.5, y: 0.90), size))
                    p.addLine(to: point(CGPoint(x: 0.98, y: 0.30), size))
                }.stroke(BMTheme.green, lineWidth: 1.5)
                // This background alone handles input, so reading a mark never moves a draft point.
                if let onPoint {
                    Color.clear.contentShape(Rectangle()).gesture(SpatialTapGesture().onEnded { value in
                        onPoint(CGPoint(x: value.location.x / size.width, y: value.location.y / size.height))
                    }).accessibilityLabel("击球去向球场，点选大致位置；也可使用下方区域按钮")
                        .accessibilityIdentifier("contact-field")
                }
                ForEach(marks) { mark in
                    if let location = mark.observation.illustration {
                        trajectory(location, size: size, color: mark.color)
                        Button { onMark?(mark) } label: {
                            marker(mark.observation, color: mark.color)
                                .frame(width: 40, height: 40)
                        }.position(point(location, size)).accessibilityLabel(mark.detail)
                    }
                }
                if let observation, let location = observation.illustration {
                    trajectory(location, size: size, color: BMTheme.orange)
                    marker(observation, color: BMTheme.orange).position(point(location, size)).allowsHitTesting(false)
                }
            }
        }.frame(height: 265)
    }
    private func point(_ value: CGPoint, _ size: CGSize) -> CGPoint { CGPoint(x: value.x * size.width, y: value.y * size.height) }
    private func trajectory(_ value: CGPoint, size: CGSize, color: Color) -> some View {
        Path { p in p.move(to: point(CGPoint(x: 0.5, y: 0.90), size)); p.addLine(to: point(value, size)) }
            .stroke(color.opacity(0.35), style: StrokeStyle(lineWidth: 1.3, dash: [4, 3])).allowsHitTesting(false)
    }
    private func marker(_ observation: BattedBallObservation, color: Color) -> some View {
        Text(observation.type.symbol).font(.caption2.bold()).foregroundStyle(color)
            .frame(width: observation.precision == .region ? 36 : 23, height: observation.precision == .region ? 36 : 23)
            .background(color.opacity(observation.precision == .region ? 0.12 : 0.22), in: Circle())
            .overlay(Circle().stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: observation.precision == .region ? [3, 2] : [])))
    }
}

struct BattedBallObservationEditor: View {
    @Binding var observation: BattedBallObservation
    var allowsBunting = true
    var game: GameState? = nil
    var outcome: PlayOutcome? = nil
    @State private var fieldMode = SituationFieldMode.defense
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("球型").font(.headline)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(BattedBallType.allCases) { type in
                    Button(type.title) { observation.type = type }
                        .buttonStyle(SecondaryButtonStyle(color: observation.type == type ? BMTheme.orange : BMTheme.green))
                        .disabled(type == .bunt && !allowsBunting).accessibilityIdentifier("contact-type-\(type.rawValue)")
                }
            }
            Text("击球去向").font(.headline)
            if let game, observation.fielding != nil {
                Picker("球场记录内容", selection: $fieldMode) {
                    ForEach(SituationFieldMode.allCases.filter { $0 != .error || outcome == .error }) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented)
                SituationFieldView(game: game, observation: observation, fielding: fieldingBinding(game), mode: fieldMode) { point in observation.selectPoint(point); fieldMode = .defense }
                Text(observation.fielding?.description ?? "").font(.caption)
                Button("撤回最后一位守备员") { _ = observation.fielding?.route.popLast(); if observation.fielding?.route.isEmpty == true { observation.fielding?.status = nil } }
                Button("守备／后续待补") { observation.fielding?.status = .pending }
                if outcome == .homeRun { Button("越过围墙，无人处理") { observation.fielding?.route = []; observation.fielding?.errorPosition = nil; observation.fielding?.status = .noFielding } }
            } else {
                BattedBallField(observation: observation, onPoint: { observation.selectPoint($0) })
            }
            Text(observation.summary).font(.subheadline.bold()).accessibilityIdentifier("contact-summary")
            Text("点选大致去向，或只选择区域。连线不代表真实轨迹；区域虚线圈不代表实测落点。")
                .font(.caption).foregroundStyle(BMTheme.secondaryText)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(BattedBallRegion.allCases) { region in
                    Button(region.title) { observation.selectRegion(region) }
                        .buttonStyle(SecondaryButtonStyle(color: observation.region == region ? BMTheme.orange : BMTheme.green))
                        .accessibilityIdentifier("contact-region-\(region.rawValue)")
                }
                if observation.fielding == nil {
                    Button("位置未知") { observation.selectRegion(nil) }
                        .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("contact-location-unknown")
                }
            }
        }
    }
    private func fieldingBinding(_ game: GameState) -> Binding<BattedBallFielding> {
        Binding(get: { observation.fielding ?? BattedBallFielding() }, set: { value in var copy = value; copy.capture(in: game); observation.fielding = copy })
    }

}

struct BatterContact: Identifiable {
    var id: String
    var observation: BattedBallObservation
    var outcome: PlayOutcome?
    var detail: String
    var color: Color { outcome?.isHit == true ? BMTheme.green : (outcome?.recordedOuts ?? 0) > 0 ? BMTheme.red : BMTheme.orange }
}
struct BatterPerformance {
    var line = BattingLine()
    var contacts: [BatterContact] = []
    var missingLocations = 0
    var gameCount = 0
    var completedAppearances = 0
}

@MainActor
extension GameStore {
    func attachBattedBall(_ observation: BattedBallObservation?, at index: Int) {
        guard let observation, let event = game.scoringEvents?[index] else { return }
        game.scoringEvents?[index].battedBall = observation
        let runners = (observation.runnerDetails ?? []).filter { $0.cause != .hit }.map { detail in
            let player = (game.homeTeam.players + game.awayTeam.players).first { $0.id == detail.playerID }
            return (player?.name ?? "跑者") + "：" + detail.cause.title + (detail.fielding.map { "，" + $0.description } ?? "")
        }.joined(separator: "；")
        let text = event.title + "；击球观察：" + observation.summary + (runners.isEmpty ? "" : "；" + runners)
        if observation.fielding?.status == .pending || (observation.runnerDetails ?? []).contains(where: { $0.cause == .pending || $0.fielding?.status == .pending }) {
            game.scoringEvents?[index].needsReview = true
            if let id = event.logEntryID, let log = game.playLog.firstIndex(where: { $0.id == id }) { game.playLog[log].isIncomplete = true }
        }
        game.scoringEvents?[index].title = text
        if let id = event.logEntryID, let log = game.playLog.firstIndex(where: { $0.id == id }) { game.playLog[log].text = text }
    }

    func batterPerformance(playerID: UUID, annual: Bool) -> BatterPerformance {
        let current = activeStoredGame
        let mode = activeRules?.gameMode ?? game.rulesSnapshot?.gameMode ?? .standard
        let year = current?.yearID ?? MatchYear.current
        let currentTeam = [game.homeTeam, game.awayTeam].first { $0.players.contains(where: { $0.id == playerID }) }?.id
        let linkedID = current?.statisticsIdentity(for: playerID) ?? playerID
        var sources: [StoredGame] = []
        if annual {
            // Use only accessible official documents, one latest revision per original game.
            let eligible = officialStatisticsGames.filter { $0.yearID == year && $0.rules.gameMode == mode && $0.status != .scheduled }
            func sourceKey(_ stored: StoredGame) -> UUID {
                if let original = stored.historySourceID { return original }
                if let cloud = stored.sharedGameID, let remote = sharedMatches.state.matches.first(where: { $0.id == cloud }) {
                    return remote.historySourceID ?? remote.wireLocalID ?? stored.id
                }
                return stored.id
            }
            let grouped = Dictionary(grouping: eligible, by: sourceKey)
            sources = grouped.values.compactMap { values in values.max { a, b in
                if (a.sharedGameID != nil) != (b.sharedGameID != nil) { return a.sharedGameID == nil }
                return a.updatedAt < b.updatedAt
            } }
            if let current, current.sharedGameID == nil,
               !sources.contains(where: { $0.sharedGameID != nil && sourceKey($0) == current.id }) {
                sources.removeAll { $0.id == current.id }
                var live = current; live.state = game; sources.append(live)
            }
        } else if var current { current.state = game; sources = [current] }
        else {
            sources = [StoredGame(seasonID: year, ourTeamID: nil, opponentTeamID: nil, isHome: false,
                                  rules: game.rulesSnapshot ?? GameRules(), lineup: [], status: game.isFinal ? .completed : .ongoing, state: game)]
        }
        var result = BatterPerformance()
        for stored in sources {
            let state = stored.state
            // Stable identities only; the opponent must also belong to the same team snapshot.
            let participants = state.homeTeam.players + state.awayTeam.players
            let sameTeam = [state.homeTeam, state.awayTeam].first { $0.id == currentTeam }
            let ids = Set(participants.filter { p in
                if stored.sharedTeamID != nil, current?.sharedTeamID == stored.sharedTeamID, stored.statisticsIdentity(for: p.id) == linkedID { return true }
                return sameTeam?.players.contains(where: { $0.id == p.id && p.id == playerID }) == true
            }.map(\.id))
            guard !ids.isEmpty else { continue }
            result.gameCount += 1
            let lines = ids.compactMap { state.batting[$0] }
            result.line = .aggregate([result.line] + lines)
            result.completedAppearances += lines.reduce(0) { $0 + $1.plateAppearances }
            // A batted-ball event is a committed plate appearance. Pitches and runner events never fabricate contacts.
            for event in state.scoringEvents ?? [] where event.category == .battedBall && (event.primaryPlayerID ?? event.plateAppearanceBatterID).map(ids.contains) == true {
                if let observation = event.battedBall, observation.hasLocation {
                    let date = stored.displayDate.formatted(date: .numeric, time: .omitted)
                    result.contacts.append(BatterContact(id: stored.id.uuidString + event.id.uuidString, observation: observation,
                        outcome: event.resolvedOutcome, detail: "\(date) · 第\(event.inning)局\(event.isTop ? "上" : "下") · \(observation.summary) · \(event.resolvedOutcome?.rawValue ?? event.title)"))
                } else { result.missingLocations += 1 }
            }
        }
        return result
    }
}

struct BatterPerformanceSheet: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    let player: Player
    @State private var annual = false
    @State private var selected: BatterContact?
    var body: some View {
        let performance = store.batterPerformance(playerID: player.id, annual: annual)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(player.compactName).font(.title2.bold())
                    Picker("统计范围", selection: $annual) {
                        Text("本场").tag(false)
                        Text("\(store.activeStoredGame?.yearID ?? MatchYear.current) 年").tag(true)
                    }.pickerStyle(.segmented).accessibilityIdentifier("batter-performance-scope")
                    HStack {
                        metric("打席", "\(performance.line.plateAppearances)")
                        metric("安打／打数", "\(performance.line.hits) / \(performance.line.atBats)")
                        metric("打击率", performance.line.atBats == 0 ? "—" : String(format: "%.3f", performance.line.average))
                    }
                    Text("\(store.activeRules?.gameMode.title ?? "普通棒球") · \(performance.gameCount) 场 · 仅统计已完成打席")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText)
                    Text("击球图").font(.headline)
                    BattedBallField(marks: performance.contacts, onMark: { selected = $0 })
                    HStack {
                        Label("安打", systemImage: "circle.fill").foregroundStyle(BMTheme.green)
                        Label("出局", systemImage: "circle.fill").foregroundStyle(BMTheme.red)
                        Label("其他", systemImage: "circle.fill").foregroundStyle(BMTheme.orange)
                    }.font(.caption)
                    Text("有去向 \(performance.contacts.count) 球 · 缺少去向 \(performance.missingLocations) 球")
                        .font(.subheadline).accessibilityIdentifier("batter-contact-coverage")
                    Text("实线圈表示点选位置，虚线圈只表示区域。旧记录缺少资料时保持未知，保送与三振不画线。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText)
                    if annual {
                        Text("年度仅包含本设备实际记录且当前有权查看的比赛；比赛模式分别统计，不代表球员完整年度成绩。")
                            .font(.caption).foregroundStyle(BMTheme.secondaryText)
                    }
                    if let selected { Text(selected.detail).font(.callout).padding().background(BMTheme.surface).accessibilityIdentifier("selected-contact-detail") }
                    if performance.contacts.isEmpty { Text("暂无有位置的击球记录").foregroundStyle(BMTheme.secondaryText) }
                    else {
                        DisclosureGroup("逐球查看（含重叠位置）") {
                            ForEach(performance.contacts) { contact in
                                Button(contact.detail) { selected = contact }.font(.caption).padding(.vertical, 6)
                            }
                        }
                    }
                }.padding(18)
            }.bmScreenBackground().navigationTitle("打击表现").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(spacing: 5) { Text(value).font(.title3.bold()); Text(title).font(.caption) }.frame(maxWidth: .infinity)
    }
}

#if DEBUG
/// Repeatable UI evidence uses built-in sample rosters and an in-memory game only.
struct BattedBallPreviewFixture: View {
    @StateObject private var store: GameStore
    init() {
        let value = GameStore(persistenceURL: nil)
        value.startNewGame(opponent: value.opponentTeams[0], isHome: false, innings: 6,
                           lineup: Array(value.currentTeam!.players.prefix(9)))
        let battingCount = value.game.battingOrderIDs.count
        for index in 0..<(battingCount * 3) {
            var observation = BattedBallObservation(type: [.line, .fly, .hardGround][index / battingCount])
            if index / battingCount == 0 { observation.selectPoint(CGPoint(x: 0.27, y: 0.29)) }
            else if index / battingCount == 1 { observation.selectRegion(.right) }
            // Third pass deliberately has no location: statistics must still include it.
            _ = value.applyPlay(.homeRun, observation: observation)
        }
        _store = StateObject(wrappedValue: value)
    }
    var body: some View {
        NavigationStack { ScorekeepingView() }.environmentObject(store)
    }
}
#endif
