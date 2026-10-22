import SwiftUI

struct SimpleEmptyState: View {
    let title: String
    let systemImage: String
    let message: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(BMTheme.secondaryText)
            Text(title)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(BMTheme.navy)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .frame(maxWidth: .infinity, minHeight: 280)
    }
}

enum BatterArrival: String, CaseIterable, Identifiable {
    case out = "打者出局"
    case first = "到一垒"
    case second = "到二垒"
    case third = "到三垒"
    case score = "打者得分"

    var id: String { rawValue }

    var destination: RunnerDestination {
        switch self {
        case .out: .out
        case .first: .base(.first)
        case .second: .base(.second)
        case .third: .base(.third)
        case .score: .score
        }
    }

    var icon: String {
        switch self {
        case .out: "xmark.circle.fill"
        case .first: "1.circle.fill"
        case .second: "2.circle.fill"
        case .third: "3.circle.fill"
        case .score: "house.fill"
        }
    }

    var color: Color { self == .out ? BMTheme.red : BMTheme.green }
}

struct ObservationCause: Identifiable {
    let title: String
    let detail: String
    let outcome: PlayOutcome
    let asksForFielder: Bool

    var id: String { "\(title)-\(outcome.rawValue)" }

    static func options(
        for arrival: BatterArrival,
        hasRunners: Bool,
        hasRunnerOnThird: Bool,
        outs: Int
    ) -> [ObservationCause] {
        switch arrival {
        case .out:
            var values = [
                ObservationCause(title: "球被接住", detail: "飞球或平飞球被接杀", outcome: .flyOut, asksForFielder: true),
                ObservationCause(title: "传球或触杀", detail: "滚地球、传杀或触杀出局", outcome: .groundOut, asksForFielder: true)
            ]
            if hasRunners && outs < 2 {
                values.append(ObservationCause(title: "一次出了两人", detail: "这一球形成双杀", outcome: .doublePlay, asksForFielder: true))
                values.append(ObservationCause(title: "触击推进跑者", detail: "打者出局，跑者推进", outcome: .sacrificeBunt, asksForFielder: false))
            }
            if hasRunnerOnThird && outs < 2 {
                values.append(ObservationCause(title: "接杀后跑者得分", detail: "高飞球接杀，跑者回本垒", outcome: .sacrificeFly, asksForFielder: false))
            }
            values.append(ObservationCause(title: "我不确定", detail: "先记出局，赛后再确认", outcome: .pendingOut, asksForFielder: false))
            return values

        case .first, .second, .third:
            let hit: PlayOutcome = switch arrival {
            case .first: .single
            case .second: .double
            case .third: .triple
            default: .single
            }
            return [
                ObservationCause(title: "正常打上垒", detail: "守备没有明显失误", outcome: hit, asksForFielder: false),
                ObservationCause(title: "守备没处理好", detail: "漏接、漏传或传球失误", outcome: .error, asksForFielder: true),
                ObservationCause(title: "杀了其他跑者", detail: "守备选择让打者上垒", outcome: .fieldersChoice, asksForFielder: true),
                ObservationCause(title: "我不确定", detail: "先保存垒况，赛后再确认", outcome: .pending, asksForFielder: false)
            ]

        case .score:
            return [
                ObservationCause(title: "本垒打", detail: "打出围墙或场内跑完全场", outcome: .homeRun, asksForFielder: false),
                ObservationCause(title: "守备连续失误", detail: "因为守备处理问题跑回本垒", outcome: .error, asksForFielder: true),
                ObservationCause(title: "我不确定", detail: "先记录得分，赛后再确认", outcome: .pending, asksForFielder: false)
            ]
        }
    }
}

struct BattedBallObservationSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSelect: (BatterArrival) -> Void
    let onUnclear: () -> Void

    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("打者最后怎么样了？")
                            .font(.system(size: 24, weight: .black))
                            .foregroundStyle(BMTheme.navy)
                        Text("先点你看到的结果，不需要判断安打、失误或专业符号。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                    }

                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(BatterArrival.allCases) { arrival in
                            Button {
                                onSelect(arrival)
                            } label: {
                                VStack(spacing: 9) {
                                    Image(systemName: arrival.icon)
                                        .font(.system(size: 27, weight: .bold))
                                    Text(arrival.rawValue)
                                        .font(.system(size: 17, weight: .bold))
                                }
                                .foregroundStyle(arrival.color)
                                .frame(maxWidth: .infinity, minHeight: 92)
                                .background(arrival == .out ? BMTheme.redSoft : BMTheme.greenSoft)
                                .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("arrival-\(arrival.rawValue)")
                        }
                    }

                    Button {
                        onUnclear()
                    } label: {
                        Label("刚才没看清，直接修正现场", systemImage: "eye.slash.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle(color: BMTheme.orange))
                    .accessibilityIdentifier("batted-ball-unclear")
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("记录击球")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

struct BattedBallCauseSheet: View {
    let arrival: BatterArrival
    let hasRunners: Bool
    let hasRunnerOnThird: Bool
    let outs: Int
    let onBack: () -> Void
    let onSelect: (ObservationCause) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        Image(systemName: arrival.icon)
                            .font(.system(size: 25, weight: .bold))
                            .foregroundStyle(arrival.color)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("已记录：\(arrival.rawValue)")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Text("再选最接近现场的一项")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(arrival == .out ? BMTheme.redSoft : BMTheme.greenSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 14))

                    ForEach(ObservationCause.options(
                        for: arrival,
                        hasRunners: hasRunners,
                        hasRunnerOnThird: hasRunnerOnThird,
                        outs: outs
                    )) { cause in
                        Button {
                            onSelect(cause)
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(cause.title)
                                        .font(.system(size: 17, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                    Text(cause.detail)
                                        .font(.system(size: 12))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            .padding(.horizontal, 15)
                            .frame(minHeight: 68)
                            .background(BMTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                            .overlay {
                                RoundedRectangle(cornerRadius: 14).stroke(BMTheme.line, lineWidth: 1)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("cause-\(cause.title)")
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("发生了什么？")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { onBack() }
                }
            }
        }
    }
}

struct RunnerEventFlowSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore
    @State private var selectedKind: RunnerEventKind?

    var body: some View {
        Group {
            if let selectedKind {
                RunnerResolutionSheet(
                    decisions: store.suggestedRunnerEventDecisions(for: selectedKind),
                    availableDestinations: store.availableDestinations,
                    title: "跑者现在在哪？",
                    message: "系统给出了常见去向；按现场实际位置点一下即可修改。",
                    confirmTitle: "确认跑者变化"
                ) { decisions in
                    store.recordRunnerEvent(selectedKind, decisions: decisions)
                    dismiss()
                }
                .id(selectedKind.id)
            } else {
                runnerEventMenu
            }
        }
    }

    private var runnerEventMenu: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 9) {
                        Image(systemName: "info.circle.fill")
                            .foregroundStyle(BMTheme.orange)
                        Text("这里只处理跑者变化；坏球或好球仍用主页面按钮记录。")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    .padding(12)
                    .background(BMTheme.orangeSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    if store.game.baseRunners.isEmpty {
                        SimpleEmptyState(title: "垒上没有跑者", systemImage: "diamond", message: "有跑者时才能记录跑垒事件。")
                    } else {
                        ForEach(RunnerEventKind.allCases) { kind in
                            Button {
                                selectedKind = kind
                            } label: {
                                HStack {
                                    Text(kind.rawValue)
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                    Spacer()
                                    Text(kind.shortTitle)
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(BMTheme.green)
                                    Image(systemName: "chevron.right")
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                .padding(.horizontal, 15)
                                .frame(minHeight: 58)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 13))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("runner-event-\(kind.shortTitle)")
                        }
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("跑者发生了什么？")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

struct SpecialEventSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore
    let onRequestCorrection: () -> Void
    @State private var resolvingDroppedThirdStrike = false

    var body: some View {
        Group {
            if resolvingDroppedThirdStrike {
                RunnerResolutionSheet(
                    decisions: store.droppedThirdStrikeDecisions(),
                    availableDestinations: store.availableDestinations,
                    title: "三振后球没接住",
                    message: "请确认打者和原有跑者最后到达的位置。",
                    confirmTitle: "确认这次三振"
                ) { decisions in
                    store.recordDroppedThirdStrike(decisions: decisions)
                    dismiss()
                }
            } else {
                specialEventMenu
            }
        }
    }

    private var specialEventMenu: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("按裁判手势或你看到的情况选择，不需要输入专业符号。")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)

                    specialButton("打者被球击中", detail: "自动上一垒并推进被迫跑者", icon: "cross.case.fill", id: "special-hbp") {
                        store.recordHitByPitch()
                        dismiss()
                    }
                    specialButton("裁判示意故意保送", detail: "无需补点四个坏球", icon: "hand.raised.fill", id: "special-ibb") {
                        store.recordIntentionalWalk()
                        dismiss()
                    }
                    specialButton(
                        "三振，但捕手没接住",
                        detail: store.canReachOnDroppedThirdStrike ? "打者仍可能跑上一垒" : "当前一垒有人且不足两出局，打者应直接出局",
                        icon: "exclamationmark.circle.fill",
                        id: "special-dropped-third"
                    ) {
                        resolvingDroppedThirdStrike = true
                    }
                    .disabled(!store.canReachOnDroppedThirdStrike)
                    .opacity(store.canReachOnDroppedThirdStrike ? 1 : 0.45)

                    specialButton("裁判判罚、没看清或漏记", detail: "直接把比分、球数和垒况改成现场状态", icon: "slider.horizontal.3", id: "special-correction") {
                        dismiss()
                        onRequestCorrection()
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("其他情况")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func specialButton(
        _ title: String,
        detail: String,
        icon: String,
        id: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 13) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(BMTheme.green)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(BMTheme.secondaryText)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 68)
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay {
                RoundedRectangle(cornerRadius: 14).stroke(BMTheme.line, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

struct GameStateCorrectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore

    @State private var inning = 1
    @State private var isTop = true
    @State private var balls = 0
    @State private var strikes = 0
    @State private var outs = 0
    @State private var awayScore = 0
    @State private var homeScore = 0
    @State private var batterIndex = 0
    @State private var pitcherID: UUID?
    @State private var firstRunnerID: UUID?
    @State private var secondRunnerID: UUID?
    @State private var thirdRunnerID: UUID?
    @State private var loaded = false

    private var battingPlayers: [Player] { isTop ? store.game.awayTeam.players : store.game.homeTeam.players }
    private var fieldingPlayers: [Player] { isTop ? store.game.homeTeam.players : store.game.awayTeam.players }

    var body: some View {
        NavigationStack {
            Form {
                Section("局面") {
                    Stepper("第 \(inning) 局", value: $inning, in: 1...20)
                    Picker("进攻方", selection: $isTop) {
                        Text("上半局").tag(true)
                        Text("下半局").tag(false)
                    }
                    .pickerStyle(.segmented)
                }

                Section("比分") {
                    Stepper("\(store.game.awayTeam.shortName)  \(awayScore) 分", value: $awayScore, in: 0...99)
                    Stepper("\(store.game.homeTeam.shortName)  \(homeScore) 分", value: $homeScore, in: 0...99)
                }

                Section("球数与出局") {
                    Stepper("坏球  \(balls)", value: $balls, in: 0...3)
                    Stepper("好球  \(strikes)", value: $strikes, in: 0...2)
                    Stepper("出局  \(outs)", value: $outs, in: 0...2)
                }

                Section("当前球员") {
                    Picker("当前打者", selection: $batterIndex) {
                        ForEach(Array(battingPlayers.enumerated()), id: \.element.id) { index, player in
                            Text("#\(player.number) \(player.name)").tag(index)
                        }
                    }
                    Picker("当前投手", selection: $pitcherID) {
                        ForEach(fieldingPlayers) { player in
                            Text("#\(player.number) \(player.name)").tag(Optional(player.id))
                        }
                    }
                }

                Section("垒上跑者") {
                    runnerPicker("一垒", selection: $firstRunnerID)
                    runnerPicker("二垒", selection: $secondRunnerID)
                    runnerPicker("三垒", selection: $thirdRunnerID)
                    if hasDuplicateRunner {
                        Label("同一名球员不能同时站在两个垒位。", systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(BMTheme.orange)
                    }
                }

                Section {
                    Text("修正只改变当前现场状态，并会保留一条“人工修正”记录；如有误仍可撤销。")
                        .font(.system(size: 12))
                        .foregroundStyle(BMTheme.secondaryText)
                }
            }
            .scrollContentBackground(.hidden)
            .bmScreenBackground()
            .navigationTitle("修正现场")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("应用") { applyCorrection() }
                        .fontWeight(.bold)
                        .disabled(hasDuplicateRunner)
                        .accessibilityIdentifier("apply-state-correction")
                }
            }
            .onAppear(perform: loadCurrentState)
            .onChange(of: isTop) { newValue in
                guard loaded else { return }
                batterIndex = 0
                let players = newValue ? store.game.homeTeam.players : store.game.awayTeam.players
                pitcherID = players.first(where: { $0.primaryPosition == .pitcher })?.id ?? players.first?.id
                firstRunnerID = nil
                secondRunnerID = nil
                thirdRunnerID = nil
            }
        }
    }

    private func runnerPicker(_ title: String, selection: Binding<UUID?>) -> some View {
        Picker(title, selection: selection) {
            Text("无人").tag(UUID?.none)
            ForEach(battingPlayers) { player in
                Text("#\(player.number) \(player.name)").tag(Optional(player.id))
            }
        }
    }

    private func loadCurrentState() {
        guard !loaded else { return }
        inning = store.game.inning
        isTop = store.game.isTop
        balls = store.game.balls
        strikes = store.game.strikes
        outs = store.game.outs
        awayScore = store.game.awayScore
        homeScore = store.game.homeScore
        batterIndex = (store.game.isTop ? store.game.awayBatterIndex : store.game.homeBatterIndex) % max(store.game.battingTeam.players.count, 1)
        pitcherID = store.currentPitcher.id
        firstRunnerID = store.game.baseRunners[.first]?.id
        secondRunnerID = store.game.baseRunners[.second]?.id
        thirdRunnerID = store.game.baseRunners[.third]?.id
        loaded = true
    }

    private func applyCorrection() {
        var runners: [Base: Player] = [:]
        if let id = firstRunnerID, let player = battingPlayers.first(where: { $0.id == id }) { runners[.first] = player }
        if let id = secondRunnerID, let player = battingPlayers.first(where: { $0.id == id }) { runners[.second] = player }
        if let id = thirdRunnerID, let player = battingPlayers.first(where: { $0.id == id }) { runners[.third] = player }

        store.correctGameState(
            inning: inning,
            isTop: isTop,
            balls: balls,
            strikes: strikes,
            outs: outs,
            awayScore: awayScore,
            homeScore: homeScore,
            batterIndex: batterIndex,
            pitcherID: pitcherID,
            baseRunners: runners
        )
        dismiss()
    }

    private var hasDuplicateRunner: Bool {
        let selected = [firstRunnerID, secondRunnerID, thirdRunnerID].compactMap { $0 }
        return Set(selected).count != selected.count
    }
}

#Preview("观察式击球") {
    BattedBallObservationSheet(onSelect: { _ in }, onUnclear: {})
}

#Preview("其他情况") {
    SpecialEventSheet(onRequestCorrection: {})
        .environmentObject(MockGameStore())
}
