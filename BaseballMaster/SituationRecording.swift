import SwiftUI

struct BattedBallFielding: Equatable, Codable {
    enum Status: String, Codable { case recorded, pending, noFielding }
    struct Participant: Equatable, Codable {
        var position: FieldPosition
        var playerID: UUID
        var name: String
        var number: String
    }
    var status: Status?
    var route: [FieldPosition] = []
    var errorPosition: FieldPosition?
    var participants: [Participant]? = nil
    var isValid: Bool {
        route.count <= 50 && (status != .recorded || !route.isEmpty)
            && (status != .noFielding || (route.isEmpty && errorPosition == nil))
    }
    mutating func touch(_ position: FieldPosition) {
        guard route.last != position, route.count < 50 else { return }
        route.append(position); status = .recorded
    }
    mutating func capture(in state: GameState) {
        participants = state.fieldingTeam.players.filter { state.fieldingPlayerIDs.contains($0.id) }.map {
            Participant(position: $0.primaryPosition, playerID: $0.id, name: $0.name, number: $0.numberText)
        }
    }
    func name(_ position: FieldPosition) -> String {
        position.fullName + (participants?.first { $0.position == position }?.name ?? "（姓名未记录）")
    }
    var description: String {
        if status == .noFielding { return "越过围墙，无人处理" }
        let numbers = route.map { String($0.rawValue) }.joined(separator: "-")
        let names = route.map(name).joined(separator: "-")
        let suffix = status == .pending ? (route.isEmpty ? "守备待补" : "，后续待补") : route.count == 1 ? "（未传球）" : ""
        return (route.isEmpty ? "" : "防守路线 \(numbers)，\(names)") + suffix
    }
}

enum BattedBallRunnerCause: String, Codable, CaseIterable, Identifiable {
    case hit, relay, error, award, tag, appeal, pending
    var id: String { rawValue }
    var title: String {
        switch self {
        case .hit: "随本次击球"
        case .relay: "趁传进垒"
        case .error: "失误进垒"
        case .award: "裁判判给"
        case .tag: "进垒被触杀"
        case .appeal: "申诉出局"
        case .pending: "待确认"
        }
    }
    var asksForFielding: Bool { [.relay, .error, .tag].contains(self) }
}
struct BattedBallRunnerDetail: Equatable, Codable, Identifiable {
    var playerID: UUID
    var cause: BattedBallRunnerCause = .hit
    var fielding: BattedBallFielding?
    var id: UUID { playerID }
}

enum SituationFieldMode: String, CaseIterable, Identifiable {
    case landing = "落点", defense = "守备／传球", error = "失误责任"
    var id: String { rawValue }
}

struct SituationFieldView: View {
    let game: GameState
    var observation: BattedBallObservation?
    @Binding var fielding: BattedBallFielding
    var mode: SituationFieldMode
    var onPoint: ((CGPoint) -> Void)?
    private var positions: [FieldPosition] { game.rulesSnapshot?.positions ?? FieldPosition.allCases.filter { $0 != .free } }
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                SituationFieldBackground()
                if mode == .landing, let onPoint {
                    Color.clear.contentShape(Rectangle()).gesture(SpatialTapGesture().onEnded { value in
                        onPoint(CGPoint(x: value.location.x / proxy.size.width, y: value.location.y / proxy.size.height))
                    }).accessibilityLabel("在球场点选击球落点").accessibilityIdentifier("contact-field")
                }
                Path { path in
                    for (a, b) in zip(fielding.route, fielding.route.dropFirst()) {
                        let start = scaled(a, proxy.size), end = scaled(b, proxy.size)
                        let dx = end.x - start.x, dy = end.y - start.y, length = hypot(dx, dy)
                        guard length > 1 else { continue }
                        let ux = dx / length, uy = dy / length, inset = min(27, length / 3)
                        let tip = CGPoint(x: end.x - ux * inset, y: end.y - uy * inset)
                        path.move(to: CGPoint(x: start.x + ux * inset, y: start.y + uy * inset)); path.addLine(to: tip)
                        path.move(to: CGPoint(x: tip.x - ux * 8 - uy * 4, y: tip.y - uy * 8 + ux * 4)); path.addLine(to: tip)
                        path.addLine(to: CGPoint(x: tip.x - ux * 8 + uy * 4, y: tip.y - uy * 8 - ux * 4))
                    }
                }.stroke(Color(red: 1, green: 0.89, blue: 0.60), style: StrokeStyle(lineWidth: 2.5, lineCap: .round)).allowsHitTesting(false)
                if mode != .landing {
                    ForEach(positions) { position in
                        let player = game.fieldingTeam.players.first { game.fieldingPlayerIDs.contains($0.id) && $0.primaryPosition == position }
                        let selected = mode == .error ? fielding.errorPosition == position : fielding.route.contains(position)
                        Button {
                            if mode == .error { fielding.errorPosition = position }
                            else { fielding.touch(position) }
                        } label: {
                            VStack(spacing: 1) {
                                Text(player?.name ?? "未记录").font(.system(size: 10, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                                Text("#\(player?.numberText ?? "—")").font(.system(size: 9, weight: .bold))
                            }.foregroundStyle(selected ? Color(red: 0.30, green: 0.23, blue: 0.07) : Color(red: 0.13, green: 0.26, blue: 0.21))
                                .frame(width: 60, height: 44)
                                .background(selected ? Color(red: 1, green: 0.86, blue: 0.49) : Color(red: 1, green: 0.99, blue: 0.94), in: RoundedRectangle(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(.white.opacity(0.7), lineWidth: 1))
                                .shadow(color: .black.opacity(0.16), radius: 3, y: 2)
                                .overlay(alignment: .topTrailing) {
                                    if fielding.errorPosition == position { Text("E").font(.caption2.bold()).foregroundStyle(BMTheme.red) }
                                }
                        }.buttonStyle(.plain).disabled(player == nil).position(scaled(position, proxy.size))
                            .accessibilityIdentifier("situation-fielder-\(position.rawValue)")
                            .accessibilityLabel((mode == .error ? "失误责任人，" : "处理球，") + position.fullName + (player?.name ?? "姓名未记录"))
                    }
                }
                VStack {
                    Spacer()
                    HStack {
                        Text(mode == .landing ? "点选落点" : mode == .error ? "点选失误责任人" : fielding.route.isEmpty ? "点第一位处理球的人" : "继续点传球对象")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(Color(red: 104/255, green: 79/255, blue: 44/255))
                            .padding(6).background(Color(red: 249/255, green: 239/255, blue: 218/255).opacity(0.92), in: RoundedRectangle(cornerRadius: 6))
                        Spacer()
                    }
                }.padding(9).allowsHitTesting(false)
                if let point = observation?.illustration {
                    Circle().fill(Color(red: 1, green: 0.82, blue: 0.29))
                        .overlay(Circle().stroke(.white, lineWidth: 3))
                        .frame(width: 17, height: 17)
                        .background(Circle().fill(Color.yellow.opacity(0.3)).padding(-6))
                        .position(x: point.x * proxy.size.width, y: point.y * proxy.size.height)
                        .allowsHitTesting(false)
                }
            }.clipShape(RoundedRectangle(cornerRadius: 15))
        }.frame(height: 260)
    }
    private func scaled(_ position: FieldPosition, _ size: CGSize) -> CGPoint {
        let point: CGPoint = switch position {
        case .pitcher: CGPoint(x: 0.5, y: 0.70)
        case .catcher: CGPoint(x: 0.5, y: 0.91)
        case .firstBase: CGPoint(x: 0.84, y: 0.65)
        case .secondBase: CGPoint(x: 0.65, y: 0.49)
        case .thirdBase: CGPoint(x: 0.16, y: 0.65)
        case .shortstop: CGPoint(x: 0.35, y: 0.49)
        case .leftField: CGPoint(x: 0.22, y: 0.23)
        case .centerField: CGPoint(x: 0.5, y: 0.12)
        case .rightField: CGPoint(x: 0.78, y: 0.23)
        case .free: CGPoint(x: 0.5, y: 0.31)
        }
        return CGPoint(x: point.x * size.width, y: point.y * size.height)
    }
}

/// Exact 360 × 260 geometry and palette of the approved recording/demoReview V4.
/// Player labels are supplied separately so they can show names and jersey numbers.
private struct SituationFieldBackground: View {
    var body: some View {
        GeometryReader { proxy in
            let transform = CGAffineTransform(scaleX: proxy.size.width / 360, y: proxy.size.height / 260)
            ZStack {
                Color(red: 237/255, green: 227/255, blue: 206/255)
                Path { p in
                    p.move(to: CGPoint(x: -5, y: 72))
                    p.addQuadCurve(to: CGPoint(x: 365, y: 72), control: CGPoint(x: 180, y: -80))
                    p.addLine(to: CGPoint(x: 180, y: 251)); p.closeSubpath()
                }.transform(transform).fill(Color(red: 213/255, green: 183/255, blue: 128/255))
                Path { p in
                    p.move(to: CGPoint(x: 3, y: 77))
                    p.addQuadCurve(to: CGPoint(x: 357, y: 77), control: CGPoint(x: 180, y: -68))
                    p.addLine(to: CGPoint(x: 180, y: 242)); p.closeSubpath()
                }.transform(transform).fill(Color(red: 43/255, green: 117/255, blue: 88/255))
                diamond([(180,130),(247,192),(180,251),(113,192)], transform)
                    .fill(Color(red: 205/255, green: 178/255, blue: 127/255))
                diamond([(180,148),(228,192),(180,236),(132,192)], transform)
                    .fill(Color(red: 57/255, green: 124/255, blue: 96/255))
                Path { p in
                    p.move(to: CGPoint(x: 0, y: 65)); p.addLine(to: CGPoint(x: 180, y: 246)); p.addLine(to: CGPoint(x: 360, y: 65))
                    p.move(to: CGPoint(x: 116, y: 192)); p.addLine(to: CGPoint(x: 180, y: 134)); p.addLine(to: CGPoint(x: 244, y: 192))
                }.transform(transform).stroke(Color(red: 1, green: 245/255, blue: 217/255), lineWidth: 2)
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
    private func diamond(_ vertices: [(CGFloat, CGFloat)], _ transform: CGAffineTransform) -> Path {
        Path { p in
            p.move(to: CGPoint(x: vertices[0].0, y: vertices[0].1))
            for point in vertices.dropFirst() { p.addLine(to: CGPoint(x: point.0, y: point.1)) }
            p.closeSubpath()
        }.applying(transform)
    }
}

/// Native implementation of recording/demoReview V4. One field collects contact and touches.
struct SituationPlayFlowSheet: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    let illegalPitch: Bool
    var initial: SituationExistingPlay? = nil
    var onSave: ((PlayOutcome, DefensivePlay?, [RunnerDecision], Bool?, BattedBallObservation) -> Bool)? = nil
    @State private var initialized = false
    private enum Stage { case entry, catalog, process, processResult, contact, review, timing }
    @State private var stage: Stage = .entry
    @State private var outcome = PlayOutcome.single
    @State private var observation = BattedBallObservation()
    @State private var selectedType: BattedBallType?
    @State private var fielding = BattedBallFielding()
    @State private var fieldMode = SituationFieldMode.landing
    @State private var decisions: [RunnerDecision] = []
    @State private var runnerDetails: [BattedBallRunnerDetail] = []
    @State private var selectedRunner: UUID?
    @State private var extraRunner: UUID?
    @State private var contactReturnToReview = false
    @State private var search = ""
    @State private var issue: String?
    @State private var timing: Bool?

    private var common: [PlayOutcome] { [.single, .double, .flyOut, .groundOut, .error, .doublePlay] }
    private var requiresReview: Bool {
        store.hasRunners || [.error, .fieldersChoice, .runnerTagOut, .infieldFly, .pending, .pendingOut, .other].contains(outcome)
    }
    private var fieldIssue: String? {
        if selectedType == nil { return "请选择球型" }
        if !observation.hasLocation { return "请在球场点选落点，或选择大致区域" }
        if fielding.status == nil { return "请点第一位处理球的守备员，再按传球顺序继续点选" }
        if !fielding.isValid { return "请核对守备路线" }
        if fielding.status == .noFielding && outcome != .homeRun { return "只有越过围墙的本垒打可选择无人处理" }
        if outcome == .error && fielding.errorPosition == nil && fielding.status != .pending { return "请在同一球场点选失误责任人" }
        return nil
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if [.entry, .catalog, .process, .processResult].contains(stage) { context }
                    switch stage {
                    case .entry: entry
                    case .catalog: catalog
                    case .process: typeGrid(isProcess: true)
                    case .processResult: outcomeGrid(processOutcomes)
                    case .contact: contact
                    case .review: review
                    case .timing: timingOptions
                    }
                    if let issue { Text(issue).font(.callout).foregroundStyle(BMTheme.red).accessibilityIdentifier("situation-issue") }
                }.padding(16)
            }.bmScreenBackground()
                .safeAreaInset(edge: .bottom) { footer }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { if stage != .entry { Button("返回") { goBack() } } }
                    ToolbarItem(placement: .confirmationAction) {
                        if stage == .entry { Button("全部击球") { stage = .catalog }.accessibilityIdentifier("all-batted-outcomes") }
                        Button { dismiss() } label: { Image(systemName: "xmark") }.accessibilityLabel("取消本球").accessibilityIdentifier("cancel-situation-play")
                    }
                }
                .gameActionErrorAlert(store)
                .onAppear {
                    guard !initialized else { return }; initialized = true
                    if let initial {
                        outcome = initial.outcome; observation = initial.observation
                        selectedType = initial.observation.type; fielding = initial.observation.fielding ?? BattedBallFielding(status: .pending)
                        decisions = initial.decisions; runnerDetails = initial.observation.runnerDetails ?? initial.decisions.map { BattedBallRunnerDetail(playerID: $0.id) }
                        timing = initial.timing; stage = .review
                    }
                }
                .sheet(item: Binding(get: { extraRunner.map(RunnerRouteSelection.init) }, set: { extraRunner = $0?.id })) { selection in
                    extraRouteSheet(selection.id)
                }
        }
    }
    private var title: String {
        switch stage {
        case .entry: "记录击球"
        case .catalog: "全部击球"
        case .process: "是什么球？"
        case .processResult: "\(selectedType?.title ?? "击球") · 结果"
        case .contact: outcome.rawValue
        case .review: "核对本球"
        case .timing: "第三出局，核对得分"
        }
    }
    private var context: some View {
        Text("第 \(store.game.inning) 局\(store.game.isTop ? "上" : "下") · \(store.game.outs) 出局 · 打者 \(store.currentBatter.compactName)")
            .font(.caption).foregroundStyle(BMTheme.secondaryText)
    }
    private var entry: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("球型、落点、守备 · 在同一个球场记录").font(.subheadline.bold())
            outcomeGrid(common)
            Button { stage = .process } label: { Label("按击球过程记录", systemImage: "arrow.right") }
                .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-by-process")
            Button("结果待确认") { choose(.other) }.buttonStyle(SecondaryButtonStyle())
            Text("点落点，再点守备员；有跑者时统一核对。")
                .font(.caption).foregroundStyle(BMTheme.secondaryText)
        }
    }
    private var catalog: some View {
        VStack(spacing: 14) {
            TextField("搜索：牺牲、野手选择、待确认…", text: $search).textFieldStyle(.roundedBorder)
            ForEach(Array(catalogGroups.enumerated()), id: \.offset) { _, group in
                let values = group.1.filter { search.isEmpty || $0.rawValue.contains(search) }
                if !values.isEmpty { Text(group.0).font(.headline).frame(maxWidth: .infinity, alignment: .leading); outcomeGrid(values) }
            }
            if !search.isEmpty && !PlayOutcome.allCases.contains(where: { $0.rawValue.contains(search) }) { Text("没有匹配结果，请试试失误或接杀。") }
        }
    }
    private var catalogGroups: [(String, [PlayOutcome])] {
        [("安打", [.single, .double, .triple, .homeRun]),
         ("接杀与出局", [.groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly]),
         ("战术与多人出局", [.sacrificeBunt, .sacrificeFly, .doublePlay, .triplePlay]),
         ("其他结果", [.error, .fieldersChoice, .runnerTagOut]),
         ("待确认", [.pendingOut, .pending, .other])]
    }
    private var processOutcomes: [PlayOutcome] {
        switch selectedType {
        case .ground, .hardGround: [.single, .groundOut, .error, .fieldersChoice, .doublePlay, .other]
        case .bunt: [.sacrificeBunt, .single, .groundOut, .error, .fieldersChoice, .other]
        case .line: [.single, .lineOut, .double, .error, .other]
        default: [.single, .flyOut, .double, .sacrificeFly, .foulFlyOut, .error, .other]
        }
    }
    private func outcomeGrid(_ values: [PlayOutcome]) -> some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(values) { result in
                Button(result == .flyOut ? "高飞接杀" : result == .error ? "守备失误" : result.rawValue) { choose(result) }
                    .buttonStyle(SecondaryButtonStyle(color: result.isHit ? BMTheme.green : BMTheme.navy))
                    .disabled(!available(result)).accessibilityIdentifier("quick-outcome-\(result.notation)")
            }
        }
    }
    private func available(_ result: PlayOutcome) -> Bool {
        switch result {
        case .doublePlay: store.game.outs < 2 && store.hasRunners
        case .triplePlay: store.game.outs == 0 && store.game.baseRunners.count >= 2
        case .sacrificeBunt: !store.isSlowPitch && store.game.outs < 2 && store.hasRunners
        case .sacrificeFly: store.game.outs < 2 && store.game.baseRunners[.third] != nil
        case .infieldFly: store.game.outs < 2 && store.game.baseRunners[.first] != nil && store.game.baseRunners[.second] != nil
        default: true
        }
    }
    private func typeGrid(isProcess: Bool = false) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 7), count: 3), spacing: 7) {
            ForEach(BattedBallType.allCases) { type in
                Button(type.title) {
                    selectedType = type; observation.type = type
                    if isProcess { stage = .processResult }
                }.buttonStyle(SecondaryButtonStyle(color: selectedType == type ? BMTheme.orange : BMTheme.green))
                    .disabled(type == .bunt && store.isSlowPitch).accessibilityIdentifier("contact-type-\(type.rawValue)")
            }
        }
    }
    private var contact: some View {
        VStack(alignment: .leading, spacing: 10) {
            typeGrid()
            Picker("球场记录内容", selection: $fieldMode) {
                ForEach(SituationFieldMode.allCases.filter { $0 != .error || outcome == .error }) { Text($0.rawValue).tag($0) }
            }.pickerStyle(.segmented).accessibilityIdentifier("situation-field-mode")
            SituationFieldView(game: store.game, observation: observation, fielding: $fielding, mode: fieldMode) { point in
                observation.selectPoint(point); fieldMode = .defense
            }
            routeStrip
            Text((fieldMode == .landing ? "点选落点" : fieldMode == .error ? "点选失误责任人" : fielding.route.isEmpty ? "点第一位处理球的人" : "继续点传球对象") + " · " + observation.summary)
                .font(.caption).accessibilityIdentifier("contact-summary")
            if fielding.status == .recorded { Text(fielding.route.map { String($0.rawValue) }.joined(separator: " → ") + (fielding.route.count == 1 ? " · 处理后未传球" : ""))
                .font(.caption.bold()).accessibilityIdentifier("situation-route") }
            if fielding.status == .pending { Text(fielding.route.isEmpty ? "守备待补" : "已记部分路线，后续待补").font(.caption).foregroundStyle(BMTheme.orange) }
            if let position = fielding.errorPosition { Text("失误责任：" + position.fullName + (fielder(position)?.name ?? "姓名未记录")).font(.caption).foregroundStyle(BMTheme.red) }
            DisclosureGroup("区域选择／其他处理") {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 7) {
                    ForEach(BattedBallRegion.allCases) { region in
                        Button(region.title) { observation.selectRegion(region); fieldMode = .defense }
                            .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("contact-region-\(region.rawValue)")
                    }
                }
                if outcome == .homeRun {
                    Button("越过围墙，无人处理") { fielding.route = []; fielding.status = .noFielding }
                        .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-no-fielding")
                }
                Button("没看清守备，明确标记待补") { fielding.status = .pending }
                    .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-fielding-pending")
                Button("清空守备路线，重新记录") { fielding.route = []; fielding.status = nil }
                    .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-clear-route")
            }.font(.caption)
        }
    }
    private var routeStrip: some View {
        HStack {
            if fielding.route.isEmpty {
                if [.groundOut, .doublePlay].contains(outcome) {
                    ForEach(outcome == .groundOut ? [[6,3],[4,3],[5,3]] : [[6,4,3],[4,6,3],[5,4,3]], id: \.self) { numbers in
                        Button(numbers.map(String.init).joined(separator: "-")) {
                            fielding.route = numbers.compactMap(FieldPosition.init(rawValue:)); fielding.status = .recorded; fieldMode = .defense
                        }.font(.caption.bold()).accessibilityIdentifier("situation-route-" + numbers.map(String.init).joined(separator: "-"))
                    }
                } else { Text("按实际传接顺序点选守备员").font(.caption) }
            } else {
                ScrollView(.horizontal) {
                    Text(fielding.route.enumerated().map { "\($0.offset + 1). \(fielder($0.element)?.name ?? $0.element.shortName)" }.joined(separator: " → "))
                        .font(.caption.bold()).fixedSize()
                }
            }
            Spacer(minLength: 0)
            Button { _ = fielding.route.popLast(); if fielding.route.isEmpty { fielding.status = nil } } label: { Image(systemName: "arrow.uturn.backward") }
                .frame(width: 44, height: 44).disabled(fielding.route.isEmpty).accessibilityLabel("撤回最后一位守备员").accessibilityIdentifier("situation-undo-route")
        }
    }
    @ViewBuilder private var footer: some View {
        if stage == .contact {
            VStack(spacing: 6) {
                if let fieldIssue { Text(fieldIssue).font(.caption).foregroundStyle(BMTheme.secondaryText) }
                Button(contactReturnToReview ? "完成修改" : requiresReview ? "下一步 · 核对跑者" : "完成本球") { finishContact() }
                    .buttonStyle(PrimaryButtonStyle()).disabled(fieldIssue != nil).accessibilityIdentifier("finish-situation-field")
            }.padding(12).background(BMTheme.surface)
        } else if stage == .review {
            VStack(spacing: 6) {
                Text(previewText).font(.caption.bold())
                Button(initial == nil ? "确认本球" : "保存本球修改") { submit() }.buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier("confirm-situation-play")
            }.padding(12).background(BMTheme.surface)
        }
    }
    private var review: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(initial == nil ? "尚未提交" : "修改刚才这球 · 保存后替换").font(.caption.bold()).foregroundStyle(BMTheme.orange)
            Text("\(outcome.rawValue) · \(observation.type.title)").font(.headline)
            Text(fielding.description).font(.caption).accessibilityIdentifier("situation-defense-description")
            if let position = fielding.errorPosition { Text("失误责任：" + fielding.name(position)).font(.caption).foregroundStyle(BMTheme.red).accessibilityIdentifier("situation-error-responsibility") }
            Button("落点：\(observation.summary) · 修改球场记录") { contactReturnToReview = true; fieldMode = .defense; stage = .contact }
                .font(.caption).accessibilityIdentifier("edit-situation-field")
            ForEach(decisions.indices, id: \.self) { index in runnerCard(index) }
            Text("进垒是建议值，按现场调整；点击姓名展开去向。")
                .font(.caption).foregroundStyle(BMTheme.secondaryText)
        }
    }
    private func runnerCard(_ index: Int) -> some View {
        let decision = decisions[index]
        return VStack(alignment: .leading, spacing: 9) {
            Button { selectedRunner = selectedRunner == decision.id ? nil : decision.id } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(decision.origin.title).font(.caption)
                        Text(decision.player.compactName).font(.headline)
                    }
                    Spacer()
                    Text(decision.destination == .hold ? "留垒" : decision.destination.title).font(.subheadline.bold())
                    Image(systemName: selectedRunner == decision.id ? "chevron.up" : "chevron.down")
                }.frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("situation-runner-\(index)")
            if selectedRunner == decision.id {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 5), count: 3), spacing: 5) {
                    ForEach(store.availableDestinations(for: decision), id: \.self) { destination in
                        Button(destination.title) {
                            decisions[index].destination = destination; timing = nil
                            if !decisions[index].hasActualMovement { runnerDetails[index].cause = .hit; runnerDetails[index].fielding = nil }
                            if destination == .out && runnerDetails[index].cause == .hit { runnerDetails[index].cause = .tag }
                            if destination != .out && [.tag, .appeal].contains(runnerDetails[index].cause) { runnerDetails[index].cause = .hit }
                        }.buttonStyle(SecondaryButtonStyle(color: decision.destination == destination ? BMTheme.orange : BMTheme.green))
                            .accessibilityIdentifier("situation-destination-\(index)-\(destination.title)")
                    }
                }
                Picker("变化原因", selection: $runnerDetails[index].cause) {
                    ForEach(BattedBallRunnerCause.allCases.filter { decision.destination == .out ? [.hit, .tag, .appeal, .pending].contains($0) : ![.tag, .appeal].contains($0) }) { Text($0.title).tag($0) }
                }.accessibilityIdentifier("situation-cause-\(index)")
                if runnerDetails[index].cause.asksForFielding {
                    Button("补充跑垒处理" + (runnerDetails[index].fielding?.route.isEmpty == false ? " · 已记录" : "")) { extraRunner = decision.id }
                        .font(.caption.bold()).accessibilityIdentifier("situation-extra-route-\(index)")
                }
            }
        }.padding(12).background(BMTheme.surface, in: RoundedRectangle(cornerRadius: 13))
    }
    private var previewText: String {
        let outs = decisions.filter { $0.destination == .out }.count
        let batterThirdOut = store.game.outs == 2 && decisions.contains { $0.origin == .batter && $0.destination == .out }
        let runs = timing == false || batterThirdOut ? 0 : decisions.filter { $0.destination == .score }.count
        let bases: [Base] = decisions.compactMap { decision in
            if case .base(let base) = decision.destination { return base }
            if decision.destination == .hold, case .base(let base) = decision.origin { return base }
            return nil
        }
        let baseText = store.game.outs + outs >= 3 || bases.isEmpty ? "垒上无人" : bases.sorted { $0.rawValue < $1.rawValue }.map(\.title).joined(separator: "、") + "有人"
        return "记录后：\(store.game.awayScore + (store.game.isTop ? runs : 0)) : \(store.game.homeScore + (store.game.isTop ? 0 : runs)) · \(min(3, store.game.outs + outs)) 出局 · " + baseText
    }
    private var timingOptions: some View {
        VStack(spacing: 12) {
            Text("本球同时有第三出局和回本垒，请按实际出局性质与先后核对。")
            Button("第三出局为封杀／打者到一垒前出局 · 不计分") { timing = false; stage = .review }.buttonStyle(SecondaryButtonStyle())
            Button("其他触杀，得分在先 · 计分") { timing = true; stage = .review }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-run-before-out")
            Button("第三出局在先 · 不计分") { timing = false; stage = .review }.buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-out-before-run")
        }
    }
    private func extraRouteSheet(_ id: UUID) -> some View {
        let index = runnerDetails.firstIndex { $0.playerID == id }!
        let binding = Binding(get: { runnerDetails[index].fielding ?? BattedBallFielding() }, set: { runnerDetails[index].fielding = $0 })
        return NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(decisions[index].player.compactName + " · " + runnerDetails[index].cause.title).font(.headline)
                    if !fielding.route.isEmpty {
                        Button("沿用本球路线：" + fielding.route.map { String($0.rawValue) }.joined(separator: "-")) { runnerDetails[index].fielding = fielding; extraRunner = nil }
                            .buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("situation-reuse-route")
                    }
                    SituationFieldView(game: store.game, fielding: binding, mode: .defense)
                    Text(binding.wrappedValue.route.map { String($0.rawValue) }.joined(separator: " → ")).font(.headline)
                    Button("撤回最后一人") { var value = binding.wrappedValue; _ = value.route.popLast(); value.status = value.route.isEmpty ? nil : .recorded; binding.wrappedValue = value }
                    if runnerDetails[index].cause == .error {
                        Picker("失误责任人", selection: Binding(get: { binding.wrappedValue.errorPosition }, set: { binding.wrappedValue.errorPosition = $0 })) {
                            Text("待补").tag(nil as FieldPosition?)
                            ForEach(store.availableFieldPositions) { Text($0.fullName + (fielder($0)?.name ?? "")).tag(Optional($0)) }
                        }
                    }
                    Button("没看清处理，标记待补") { binding.wrappedValue.status = .pending; extraRunner = nil }
                    Text("本次跑垒处理独立保存，不改变原本的安打结果。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText)
                }.padding(16)
            }.navigationTitle("补充跑垒处理").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("返回") { extraRunner = nil } }
                    ToolbarItem(placement: .confirmationAction) { Button("完成路线") { extraRunner = nil }.disabled(binding.wrappedValue.route.isEmpty) }
                }
        }
    }
    private func fielder(_ position: FieldPosition) -> Player? {
        store.game.fieldingTeam.players.first { store.game.fieldingPlayerIDs.contains($0.id) && $0.primaryPosition == position }
    }
    private func choose(_ result: PlayOutcome) {
        outcome = result; fielding = BattedBallFielding(); observation = BattedBallObservation(type: selectedType ?? .unknown)
        if selectedType == nil {
            selectedType = result == .groundOut ? .ground : [.flyOut, .sacrificeFly].contains(result) ? .fly : result == .lineOut ? .line : result == .sacrificeBunt ? .bunt : nil
            observation.type = selectedType ?? .unknown
        }
        let suggested = store.suggestedRunnerDecisions(for: result)
        decisions = suggested.sorted { a, b in
            func order(_ item: RunnerDecision) -> Int { if case .base(let base) = item.origin { return base.rawValue }; return 0 }
            return order(a) < order(b)
        }
        runnerDetails = decisions.map { BattedBallRunnerDetail(playerID: $0.player.id) }
        selectedRunner = decisions.count > 3 ? nil : decisions.first(where: { $0.origin != .batter })?.id ?? decisions.first?.id
        timing = nil; fieldMode = .landing; stage = .contact; issue = nil; contactReturnToReview = false
    }
    private func finishContact() {
        guard fieldIssue == nil else { issue = fieldIssue; return }
        fielding.capture(in: store.game)
        if initial != nil || contactReturnToReview || requiresReview { stage = .review; contactReturnToReview = false }
        else { submit() }
    }
    private func submit() {
        guard fieldIssue == nil else { issue = fieldIssue; stage = .contact; return }
        let outs = decisions.filter { $0.destination == .out }.count
        if outcome == .doublePlay && outs != 2 || outcome == .triplePlay && outs != 3 { issue = "请明确本次\(outcome.rawValue)的实际出局者"; stage = .review; return }
        if store.situationNeedsTimingDecision(outcome, decisions: decisions), timing == nil { stage = .timing; return }
        for index in runnerDetails.indices {
            if runnerDetails[index].cause.asksForFielding, decisions[index].destination != .hold,
               runnerDetails[index].fielding?.status == nil {
                issue = "请补充\(decisions[index].player.name)的跑垒处理，或明确标记待补"; stage = .review; selectedRunner = decisions[index].id; return
            }
            runnerDetails[index].fielding?.capture(in: store.game)
        }
        fielding.capture(in: store.game); observation.fielding = fielding; observation.runnerDetails = runnerDetails
        let defense = situationDefensivePlay(outcome: outcome, observation: observation, decisions: decisions)
        if let onSave {
            if onSave(outcome, defense, decisions, timing, observation) { dismiss() }
            else { issue = "修改未保存，请核对比赛是否已变化。" }
            return
        }
        let result = illegalPitch
            ? store.recordIllegalPitch(nil, wasThrown: true, outcome: outcome, defensivePlay: defense, decisions: decisions, timingRunCounts: timing, observation: observation)
            : store.applyPlay(outcome, defensivePlay: defense, decisions: decisions, timingRunCounts: timing, observation: observation)
        if result { dismiss() }
    }
    private func goBack() {
        issue = nil
        switch stage {
        case .entry: break
        case .catalog, .process: stage = .entry
        case .processResult: stage = .process
        case .contact: stage = contactReturnToReview ? .review : .entry
        case .review: contactReturnToReview = false; stage = .entry
        case .timing: stage = .review
        }
    }
}

private struct RunnerRouteSelection: Identifiable { var id: UUID }

/// Official scorekeeping still receives explicit resolved results and actual runner decisions.
func situationDefensivePlay(outcome: PlayOutcome, observation: BattedBallObservation, decisions: [RunnerDecision]) -> DefensivePlay? {
    guard let fielding = observation.fielding, fielding.status != .noFielding else { return nil }
    let route = fielding.route
    var putouts: [FieldPosition] = [], assists: [FieldPosition] = []
    if fielding.status == .recorded {
        let extraOuts = (observation.runnerDetails ?? []).filter { detail in decisions.contains { $0.id == detail.playerID && $0.destination == .out } && detail.fielding?.status == .recorded }
        let mainOuts = max(0, decisions.filter { $0.destination == .out }.count - extraOuts.count)
        if mainOuts > 0, !route.isEmpty {
            if [.flyOut, .lineOut, .foulFlyOut, .sacrificeFly, .infieldFly].contains(outcome) { putouts = [route[0]] }
            else { putouts = Array(route.suffix(mainOuts)); assists = route.count > 1 ? Array(Set(route.dropLast())).sorted { $0.rawValue < $1.rawValue } : [] }
        }
        for detail in extraOuts {
            if let extraRoute = detail.fielding?.route, let last = extraRoute.last {
                putouts.append(last); assists += extraRoute.dropLast()
            }
        }
    }
    return DefensivePlay(id: "SITUATION", title: "本球守备", notation: outcome.isHit || route.isEmpty ? outcome.notation : route.map { String($0.rawValue) }.joined(separator: "-"),
        assistPositions: Array(Set(assists)).sorted { $0.rawValue < $1.rawValue }, putoutPosition: putouts.last,
        errorPosition: fielding.errorPosition, routePositions: route, putoutPositions: putouts)
}

@MainActor
extension GameStore {
    func situationNeedsTimingDecision(_ outcome: PlayOutcome, decisions: [RunnerDecision]) -> Bool {
        let outs = decisions.filter { $0.destination == .out }.count
        let runs = decisions.filter { $0.destination == .score }.count
        let batterOut = decisions.contains { $0.origin == .batter && $0.destination == .out }
        return game.outs + outs >= 3 && runs > 0 && !(game.outs == 2 && batterOut)
    }

    func situationObservationError(_ observation: BattedBallObservation?, outcome: PlayOutcome, decisions: [RunnerDecision]) -> String? {
        guard let observation, let fielding = observation.fielding else { return nil }
        if !observation.hasLocation { return "请记录本球落点或大致区域。" }
        if fielding.status == nil || !fielding.isValid { return "请记录处理球的人，或明确标记守备待补。" }
        if fielding.status == .noFielding && outcome != .homeRun { return "只有越过围墙的本垒打可记录无人处理。" }
        let active = Set(activeFielders.map(\.primaryPosition))
        if !Set(fielding.route).isSubset(of: active) || fielding.errorPosition.map({ !active.contains($0) }) == true { return "守备路线与当前场上人员不一致。" }
        if outcome == .error && fielding.status != .pending && fielding.errorPosition == nil { return "请指定失误责任人。" }
        let outs = decisions.filter { $0.destination == .out }.count
        if outcome == .doublePlay && outs != 2 || outcome == .triplePlay && outs != 3 { return "请核对本球实际出局人数。" }
        if [.groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly, .sacrificeBunt, .sacrificeFly, .pendingOut].contains(outcome),
           !decisions.contains(where: { $0.origin == .batter && $0.destination == .out }) { return "该结果要求打者出局，请核对实际结果。" }
        if let details = observation.runnerDetails {
            guard Set(details.map(\.playerID)).count == details.count, Set(details.map(\.playerID)) == Set(decisions.map(\.id)) else { return "跑者处理与本球人员不一致。" }
            for detail in details {
                guard let decision = decisions.first(where: { $0.id == detail.playerID }) else { return "跑者资料缺失。" }
                if detail.cause.asksForFielding && decision.hasActualMovement {
                    guard let extra = detail.fielding, extra.status != nil, extra.isValid,
                          Set(extra.route).isSubset(of: active), extra.status != .noFielding else { return "请补充实际跑垒处理，或标记待补。" }
                    if detail.cause == .error && extra.errorPosition == nil && extra.status != .pending { return "请核对跑垒处理中的失误责任人。" }
                }
            }
        }
        return nil
    }
}

struct SituationExistingPlay {
    var outcome: PlayOutcome
    var observation: BattedBallObservation
    var decisions: [RunnerDecision]
    var timing: Bool?
}
struct BattedBallEventSelection: Identifiable { var id: UUID }

struct SituationAmendmentSheet: View {
    let original: GameStore
    let draft: HistoryCorrectionDraft?
    let operationID: UUID?
    let initial: SituationExistingPlay?
    @StateObject private var worker: GameStore
    @Environment(\.dismiss) private var dismiss
    init(store: GameStore, eventID: UUID) {
        original = store
        let correction = try? store.makeCorrectionDraft()
        let operation = correction?.journal.operations.first { $0.eventIDs.contains(eventID) }
        let before = correction.flatMap { store.previewCorrection($0).statesBefore[operation?.id ?? UUID()] }
        draft = correction; operationID = operation?.id
        var existing: SituationExistingPlay?
        if let operation {
            switch operation.command {
            case .play(let outcome, _, let decisions, let timing):
                existing = SituationExistingPlay(outcome: outcome, observation: operation.battedBall ?? BattedBallObservation(), decisions: decisions, timing: timing)
            case .illegalPitch(_, _, let outcome, _, let decisions, let timing, _):
                if let outcome { existing = SituationExistingPlay(outcome: outcome, observation: operation.battedBall ?? BattedBallObservation(), decisions: decisions ?? [], timing: timing) }
            default: break
            }
        }
        initial = existing
        if let before, let stored = store.correctionStoredGame { _worker = StateObject(wrappedValue: store.historyWorker(stored, state: before)) }
        else { _worker = StateObject(wrappedValue: GameStore(persistenceURL: nil)) }
    }
    var body: some View {
        if let initial, draft != nil {
            SituationPlayFlowSheet(illegalPitch: false, initial: initial, onSave: save).environmentObject(worker)
        } else {
            NavigationStack {
                Text("这条旧记录需要从“纠正记录”中核对后补充。没有修改原比赛。")
                    .padding().navigationTitle("补充这球")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            }
        }
    }
    private func save(_ outcome: PlayOutcome, _ defense: DefensivePlay?, _ decisions: [RunnerDecision], _ timing: Bool?, _ observation: BattedBallObservation) -> Bool {
        guard var draft, let index = draft.journal.operations.firstIndex(where: { $0.id == operationID }) else { return false }
        if case .illegalPitch(_, let thrown, _, _, _, _, let penalty) = draft.journal.operations[index].command {
            draft.journal.operations[index].command = .illegalPitch(nil, thrown, outcome, defense, decisions, timing, penalty)
        } else { draft.journal.operations[index].command = .play(outcome, defense, decisions, timing) }
        draft.journal.operations[index].battedBall = observation
        draft.journal.operations[index].edited = true; draft.journal.operations[index].correctedAt = Date()
        draft.reason = "补充刚才这球"
        let saved = original.saveCorrection(draft)
        if !saved { worker.actionErrorMessage = original.actionErrorMessage }
        return saved
    }
}

@MainActor
extension GameStore {
    /// A practice correction replays an in-memory document and never creates a formal match.
    var correctionStoredGame: StoredGame? {
        if let activeStoredGame { return activeStoredGame }
        guard supportsTransientCorrection, game.historyJournal != nil else { return nil }
        var value = StoredGame(id: transientCorrectionID, seasonID: MatchYear.current, ourTeamID: nil, opponentTeamID: nil,
            isHome: false, rules: game.rulesSnapshot ?? GameRules(scheduledInnings: 1), lineup: [],
            status: game.isFinal ? .completed : .ongoing, state: game)
        value.revision = ((try? game.historyData())?.hashValue ?? 0) & Int.max
        return value
    }
}

#if DEBUG
struct SituationCompoundFixture: View {
    @StateObject private var store: GameStore
    init() {
        let value = GameStore(persistenceURL: nil)
        var rules = GameRules(); rules.recordingMethod = .situation
        let lineup = value.currentTeam!.players.prefix(9).enumerated().map {
            LineupAssignment(playerID: $0.element.id, battingOrder: $0.offset + 1, position: FieldPosition.allCases[$0.offset])
        }
        value.startNewGame(opponent: value.opponentTeams[0], isHome: false, rules: rules, lineup: lineup)
        _ = value.applyPlay(.double)
        _store = StateObject(wrappedValue: value)
    }
    var body: some View { NavigationStack { ScorekeepingView() }.environmentObject(store) }
}
#endif

extension RunnerDecision {
    var hasActualMovement: Bool {
        if destination == .hold { return false }
        if case .base(let base) = self.origin, destination == .base(base) { return false }
        return true
    }
}
