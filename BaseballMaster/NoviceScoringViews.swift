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
        hasRunnersOnFirstAndSecond: Bool,
        outs: Int
    ) -> [ObservationCause] {
        switch arrival {
        case .out:
            var values = [
                ObservationCause(title: "球被接住", detail: "飞球或平飞球被接杀", outcome: .flyOut, asksForFielder: true),
                ObservationCause(title: "平飞球接杀", detail: "强劲平飞球被直接接住", outcome: .lineOut, asksForFielder: true),
                ObservationCause(title: "界外飞球接杀", detail: "界外区飞球被接住", outcome: .foulFlyOut, asksForFielder: true),
                ObservationCause(title: "打者未到一垒前出局", detail: "传一垒、封杀或触杀打者跑者", outcome: .groundOut, asksForFielder: true)
            ]
            if hasRunnersOnFirstAndSecond && outs < 2 {
                values.append(ObservationCause(
                    title: "内野高飞必死",
                    detail: "打者立即出局，跑者仍按现场结果确认",
                    outcome: .infieldFly,
                    asksForFielder: true
                ))
            }
            if hasRunners && outs < 2 {
                values.append(ObservationCause(title: "一次出了两人", detail: "这一球形成双杀", outcome: .doublePlay, asksForFielder: true))
                values.append(ObservationCause(title: "触击推进跑者", detail: "打者出局，跑者推进", outcome: .sacrificeBunt, asksForFielder: false))
            }
            if hasRunners && outs == 0 {
                values.append(ObservationCause(title: "一次出了三人", detail: "这一球形成三杀", outcome: .triplePlay, asksForFielder: true))
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
                ObservationCause(title: "触杀其他跑者", detail: "打者安全，其他跑者被触杀", outcome: .runnerTagOut, asksForFielder: true),
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
    let hasRunnersOnFirstAndSecond: Bool
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
                        hasRunnersOnFirstAndSecond: hasRunnersOnFirstAndSecond,
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
    @EnvironmentObject private var store: GameStore
    @State private var selectedKind: RunnerEventKind?
    @State private var pendingTimingDecisions: [RunnerDecision]?

    var body: some View {
        Group {
            if let selectedKind, let pendingTimingDecisions {
                ThirdOutTimingSheet(title: selectedKind.shortTitle) { runCounts in
                    if store.recordRunnerEvent(
                        selectedKind,
                        decisions: pendingTimingDecisions,
                        timingRunCounts: runCounts
                    ) {
                        dismiss()
                    }
                }
            } else if let selectedKind {
                RunnerResolutionSheet(
                    decisions: store.suggestedRunnerEventDecisions(for: selectedKind),
                    availableDestinations: store.availableDestinations,
                    title: "跑者现在在哪？",
                    message: "系统给出了常见去向；按现场实际位置点一下即可修改。",
                    confirmTitle: "确认跑者变化"
                ) { decisions in
                    if store.runnerEventNeedsTimingDecision(selectedKind, decisions: decisions) {
                        pendingTimingDecisions = decisions
                    } else {
                        if store.recordRunnerEvent(selectedKind, decisions: decisions) {
                            dismiss()
                        }
                    }
                }
                .id(selectedKind.id)
            } else {
                runnerEventMenu
            }
        }
        .gameActionErrorAlert(store)
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
                        ForEach(RunnerEventGroup.allCases) { group in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(group.rawValue)
                                    .font(.system(size: 13, weight: .black))
                                    .foregroundStyle(BMTheme.secondaryText)
                                ForEach(RunnerEventKind.allCases.filter { $0.group == group }) { kind in
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
                                .frame(minHeight: 54)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 13))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("runner-event-\(kind.shortTitle)")
                                }
                            }
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

struct ThirdOutTimingSheet: View {
    let title: String
    let onSelect: (Bool) -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("第三出局与得分谁先发生？")
                    .font(.system(size: 23, weight: .black))
                    .foregroundStyle(BMTheme.navy)
                Text("触杀等时间局面需要确认先后。封杀出局由系统自动判定不计分。")
                    .font(.system(size: 14))
                    .foregroundStyle(BMTheme.secondaryText)

                Button {
                    onSelect(true)
                } label: {
                    Label("得分在先 · 得分有效", systemImage: "checkmark.circle.fill")
                }
                .buttonStyle(PrimaryButtonStyle(color: BMTheme.green))
                .accessibilityIdentifier("third-out-run-counts")

                Button {
                    onSelect(false)
                } label: {
                    Label("出局在先 · 得分无效", systemImage: "xmark.circle.fill")
                }
                .buttonStyle(PrimaryButtonStyle(color: BMTheme.red))
                .accessibilityIdentifier("third-out-run-cancelled")

                Spacer()
            }
            .padding(18)
            .bmScreenBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct SpecialEventSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let onRequestCorrection: () -> Void
    @State private var resolvingDroppedThirdStrike = false
    @State private var choosingViolationCategory = false
    @State private var selectedViolationCategory: ViolationCategory?
    @State private var resolvingViolation: ViolationKind?
    @State private var adjudicatingViolation: ViolationKind?
    @State private var choosingExtraInning = false

    var body: some View {
        Group {
            if let adjudicatingViolation {
                ViolationAdjudicationSheet(violation: adjudicatingViolation)
                    .environmentObject(store)
            } else if let resolvingViolation {
                RunnerResolutionSheet(
                    decisions: store.suggestedViolationDecisions(for: resolvingViolation),
                    availableDestinations: store.availableDestinations,
                    title: resolvingViolation.rawValue,
                    message: "按裁判最终宣判，点选每名跑者最后的位置。",
                    confirmTitle: "确认判罚结果"
                ) { decisions in
                    if store.recordViolation(resolvingViolation, decisions: decisions) {
                        dismiss()
                    }
                }
                .id(resolvingViolation.id)
            } else if resolvingDroppedThirdStrike {
                RunnerResolutionSheet(
                    decisions: store.droppedThirdStrikeDecisions(),
                    availableDestinations: store.availableDestinations,
                    title: "三振后球没接住",
                    message: "请确认打者和原有跑者最后到达的位置。",
                    confirmTitle: "确认这次三振"
                ) { decisions in
                    if store.recordDroppedThirdStrike(decisions: decisions) {
                        dismiss()
                    }
                }
            } else if let selectedViolationCategory {
                violationList(selectedViolationCategory)
            } else if choosingViolationCategory {
                violationCategoryMenu
            } else if choosingExtraInning {
                extraInningMenu
            } else {
                specialEventMenu
            }
        }
        .gameActionErrorAlert(store)
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

                    specialButton(
                        "两好球后触击界外",
                        detail: "按第三好球记录打者出局",
                        icon: "hand.raised.fingers.spread.fill",
                        id: "special-foul-bunt"
                    ) {
                        store.recordFoulBuntStrikeout()
                        dismiss()
                    }
                    .disabled(store.game.strikes != 2)
                    .opacity(store.game.strikes == 2 ? 1 : 0.45)

                    specialButton("裁判判罚与犯规", detail: "投手、打者、捕手、防守或进攻", icon: "exclamationmark.shield.fill", id: "special-violations") {
                        choosingViolationCategory = true
                    }

                    if store.canConfirmExtraInning {
                        specialButton("转换为延长局", detail: "由记录员选择普通延长或 TB", icon: "plus.forwardslash.minus", id: "special-extra-inning") {
                            choosingExtraInning = true
                        }
                    } else if store.canEnableTiebreak {
                        specialButton("从本局启用 TB", detail: "选择赛事规定的放置垒位和人数", icon: "diamond.fill", id: "special-enable-tb") {
                            choosingExtraInning = true
                        }
                    }

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
                        .accessibilityIdentifier("close-special-events")
                }
            }
        }
    }

    private var violationCategoryMenu: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("先点犯规一方，再点裁判宣判的事项。")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)
                    ForEach(ViolationCategory.allCases) { category in
                        specialButton(
                            "\(category.rawValue)犯规",
                            detail: "查看\(category.rawValue)相关判罚",
                            icon: "chevron.right.circle.fill",
                            id: "violation-category-\(category.rawValue)"
                        ) {
                            selectedViolationCategory = category
                        }
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("裁判判罚")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { choosingViolationCategory = false }
                }
            }
        }
    }

    private func violationList(_ category: ViolationCategory) -> some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(ViolationKind.allCases.filter { $0.category == category }) { violation in
                        HStack(spacing: 8) {
                            specialButton(
                                violation.rawValue,
                                detail: "\(violation.detail) · \(violation.ballStatus.rawValue)",
                                icon: "hand.raised.fill",
                                id: "violation-\(violation.rawValue)"
                            ) {
                                if violation.resolution == .resolveRunners
                                    || (violation.resolution == .ballOrAdvance && store.hasRunners) {
                                    resolvingViolation = violation
                                } else {
                                    if store.recordViolation(violation) {
                                        dismiss()
                                    }
                                }
                            }
                            Button {
                                adjudicatingViolation = violation
                            } label: {
                                Image(systemName: "slider.horizontal.3")
                                    .font(.system(size: 17, weight: .bold))
                                    .foregroundStyle(BMTheme.orange)
                                    .frame(width: 48, height: 58)
                                    .background(BMTheme.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 13))
                            }
                            .accessibilityLabel("自定义\(violation.rawValue)宣判")
                            .accessibilityIdentifier("custom-violation-\(violation.rawValue)")
                        }
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("\(category.rawValue)犯规")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { selectedViolationCategory = nil }
                }
            }
        }
    }

    private var extraInningMenu: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("当前为第 \(store.game.inning) 局。请选择本场采用的延长赛方式。")
                    .font(.system(size: 14))
                    .foregroundStyle(BMTheme.secondaryText)
                if store.canConfirmExtraInning {
                    specialButton("普通延长局", detail: "垒上无人开始本局", icon: "arrow.right.circle.fill", id: "extra-normal") {
                        store.confirmExtraInning(useTiebreak: false)
                        dismiss()
                    }
                }
                specialButton("TB · 二垒 1 人", detail: "常见国际赛制", icon: "diamond.fill", id: "extra-tiebreak") {
                    enableTiebreak(on: [.second])
                }
                specialButton("TB · 一、二垒 2 人", detail: "按赛事规程在两个垒位放人", icon: "diamond.lefthalf.filled", id: "extra-tiebreak-two") {
                    enableTiebreak(on: [.first, .second])
                }
                specialButton("TB · 满垒 3 人", detail: "按赛事规程在一、二、三垒放人", icon: "diamond.inset.filled", id: "extra-tiebreak-three") {
                    enableTiebreak(on: [.first, .second, .third])
                }
                Spacer()
            }
            .padding(18)
            .bmScreenBackground()
            .navigationTitle("转换为延长局")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { choosingExtraInning = false }
                }
            }
        }
    }

    private func enableTiebreak(on bases: [Base]) {
        if store.canConfirmExtraInning {
            store.confirmExtraInning(useTiebreak: true, runnerBases: bases)
        } else {
            store.enableTiebreakFromCurrentInning(runnerBases: bases)
        }
        dismiss()
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

struct ViolationAdjudicationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let violation: ViolationKind

    @State private var ballStatus: BallStatus
    @State private var previousPlayDisposition: PreviousPlayDisposition = .notApplicable
    @State private var plateAppearanceDisposition: PlateAppearanceDisposition
    @State private var decisions: [RunnerDecision] = []
    @State private var countsAsAtBat: Bool
    @State private var isFinalRuling: Bool
    @State private var battingCredit: AdjudicationBattingCredit = .none
    @State private var runsBattedIn = 0
    @State private var earnedRuns = 0
    @State private var fieldingErrorPlayerID: UUID?
    @State private var loaded = false

    init(violation: ViolationKind) {
        self.violation = violation
        _ballStatus = State(initialValue: violation.ballStatus)
        let plateDisposition: PlateAppearanceDisposition = switch violation.resolution {
        case .batterOut: .batterOut
        case .batterFirst: .batterFirst
        default: .continueAtBat
        }
        _plateAppearanceDisposition = State(initialValue: plateDisposition)
        _countsAsAtBat = State(initialValue: plateDisposition == .batterOut)
        _isFinalRuling = State(initialValue: violation.resolution != .recordForReview)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("裁判最终宣判") {
                    Picker("球状态", selection: $ballStatus) {
                        ForEach(BallStatus.allCases) { status in
                            Text(status.rawValue).tag(status)
                        }
                    }
                    Picker("上一比赛结果", selection: $previousPlayDisposition) {
                        ForEach(PreviousPlayDisposition.allCases) { disposition in
                            Text(disposition.rawValue).tag(disposition)
                        }
                    }
                    Picker("当前打席", selection: $plateAppearanceDisposition) {
                        ForEach(PlateAppearanceDisposition.allCases) { disposition in
                            Text(disposition.rawValue).tag(disposition)
                        }
                    }
                    if plateAppearanceDisposition != .continueAtBat {
                        Toggle("计入一次打数", isOn: $countsAsAtBat)
                    }
                    Toggle("这是最终宣判", isOn: $isFinalRuling)
                }

                Section("打者与跑者最终位置") {
                    if decisions.isEmpty {
                        Text("当前没有需要移动的跑者，打席继续。")
                            .foregroundStyle(BMTheme.secondaryText)
                    } else {
                        ForEach(decisions.indices, id: \.self) { index in
                            Picker(decisionLabel(decisions[index]), selection: $decisions[index].destination) {
                                ForEach(store.availableDestinations(for: decisions[index]), id: \.self) { destination in
                                    Text(destination.title).tag(destination)
                                }
                            }
                        }
                    }
                    if hasBaseConflict {
                        Label("同一个垒位不能同时站两名跑者。", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(BMTheme.orange)
                    }
                }

                if plateAppearanceDisposition != .continueAtBat {
                    Section("正式统计责任") {
                        Picker("打击结果", selection: $battingCredit) {
                            ForEach(AdjudicationBattingCredit.allCases) { credit in
                                Text(credit.rawValue).tag(credit)
                            }
                        }
                        if battingCredit == .reachedOnError {
                            Picker("失误责任人", selection: $fieldingErrorPlayerID) {
                                Text("请选择").tag(UUID?.none)
                                ForEach(store.activeFielders) { fielder in
                                    Text("#\(fielder.number) \(fielder.name)").tag(Optional(fielder.id))
                                }
                            }
                        }
                        Stepper("打点  \(runsBattedIn)", value: $runsBattedIn, in: 0...scoredRuns)
                        Stepper("投手自责分  \(earnedRuns)", value: $earnedRuns, in: 0...maximumEarnedRuns)
                        Text("TB 自动跑者得分不会进入可选自责分。未最终确认的统计仍可在赛后补录。")
                            .font(.system(size: 12))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                }

                Section {
                    Text("用于妨碍守备、脱离装备触球等复杂判罚；可一次处理打者与全部原有跑者、多出局及垒位奖励，并明确是否取消上一比赛结果。")
                        .font(.system(size: 12))
                        .foregroundStyle(BMTheme.secondaryText)
                }
            }
            .scrollContentBackground(.hidden)
            .bmScreenBackground()
            .navigationTitle(violation.rawValue)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("记录宣判") {
                        let adjudication = ViolationAdjudication(
                            ballStatus: ballStatus,
                            previousPlayDisposition: previousPlayDisposition,
                            plateAppearanceDisposition: plateAppearanceDisposition,
                            runnerDecisions: decisions,
                            countsAsAtBat: countsAsAtBat,
                            isFinalRuling: isFinalRuling,
                            battingCredit: battingCredit,
                            runsBattedIn: runsBattedIn,
                            earnedRuns: earnedRuns,
                            fieldingErrorPlayerID: fieldingErrorPlayerID
                        )
                        if store.recordViolation(violation, adjudication: adjudication) { dismiss() }
                    }
                    .fontWeight(.bold)
                    .disabled(hasBaseConflict)
                    .accessibilityIdentifier("confirm-custom-violation")
                }
            }
            .onAppear {
                guard !loaded else { return }
                loaded = true
                decisions = store.adjudicationDecisions(
                    for: violation,
                    plateDisposition: plateAppearanceDisposition
                )
            }
            .onChange(of: plateAppearanceDisposition) { newValue in
                decisions = store.adjudicationDecisions(for: violation, plateDisposition: newValue)
                countsAsAtBat = newValue == .batterOut
                battingCredit = .none
                runsBattedIn = 0
                earnedRuns = 0
                fieldingErrorPlayerID = nil
            }
            .onChange(of: decisions) { _ in
                runsBattedIn = min(runsBattedIn, scoredRuns)
                earnedRuns = min(earnedRuns, maximumEarnedRuns)
            }
            .onChange(of: battingCredit) { newValue in
                switch newValue {
                case .single, .double, .triple, .homeRun, .reachedOnError, .fieldersChoice:
                    countsAsAtBat = true
                case .sacrifice:
                    countsAsAtBat = false
                case .none:
                    break
                }
                if newValue == .reachedOnError, fieldingErrorPlayerID == nil {
                    fieldingErrorPlayerID = store.activeFielders.first?.id
                }
            }
        }
        .gameActionErrorAlert(store)
    }

    private func decisionLabel(_ decision: RunnerDecision) -> String {
        "#\(decision.player.number) \(decision.player.name) · \(decision.origin.title)"
    }

    private var hasBaseConflict: Bool {
        let bases = decisions.compactMap { decision -> Base? in
            if case .base(let base) = decision.destination { return base }
            return nil
        }
        return Set(bases).count != bases.count
    }

    private var scoredRuns: Int {
        decisions.filter { $0.destination == .score }.count
    }

    private var maximumEarnedRuns: Int {
        let automaticIDs = Set(store.game.automaticRunnerIDs ?? [])
        return decisions.filter {
            $0.destination == .score && !automaticIDs.contains($0.player.id)
        }.count
    }
}

struct GameStateCorrectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore

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

    private var battingPlayers: [Player] {
        let team = isTop ? store.game.awayTeam : store.game.homeTeam
        let order = isTop ? store.game.awayBattingOrderIDs : store.game.homeBattingOrderIDs
        return order.compactMap { id in team.players.first(where: { $0.id == id }) }
    }
    private var fieldingPlayers: [Player] {
        let team = isTop ? store.game.homeTeam : store.game.awayTeam
        let order = isTop ? store.game.homeBattingOrderIDs : store.game.awayBattingOrderIDs
        return order.compactMap { id in team.players.first(where: { $0.id == id }) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("待确认记录") {
                    if store.pendingReviewEvents.isEmpty {
                        Label("当前没有待确认事件", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(BMTheme.green)
                    } else {
                        ForEach(store.pendingReviewEvents) { event in
                            NavigationLink {
                                PendingEventReviewView(event: event)
                                    .environmentObject(store)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(event.title)
                                        .font(.system(size: 14, weight: .bold))
                                        .lineLimit(2)
                                    Text("\(event.inning)局\(event.isTop ? "上" : "下") · \(event.category.title)")
                                        .font(.system(size: 11))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                            }
                            .accessibilityIdentifier("pending-event-\(event.id.uuidString)")
                        }
                    }
                }

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
        .gameActionErrorAlert(store)
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
        batterIndex = (store.game.isTop ? store.game.awayBatterIndex : store.game.homeBatterIndex) % max(battingPlayers.count, 1)
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

        if store.correctGameState(
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
        ) {
            dismiss()
        }
    }

    private var hasDuplicateRunner: Bool {
        let selected = [firstRunnerID, secondRunnerID, thirdRunnerID].compactMap { $0 }
        return Set(selected).count != selected.count
    }
}

struct PendingEventReviewView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let event: ScoringEventRecord

    @State private var title: String
    @State private var category: ScoringEventCategory
    @State private var notation: String
    @State private var primaryPlayerID: UUID?
    @State private var secondaryPlayerID: UUID?
    @State private var ballStatus: BallStatus?
    @State private var resolvedOutcome: PlayOutcome
    @State private var note = ""

    init(event: ScoringEventRecord) {
        self.event = event
        _title = State(initialValue: event.title)
        _category = State(initialValue: event.category)
        _notation = State(initialValue: event.notation ?? "")
        _primaryPlayerID = State(initialValue: event.primaryPlayerID)
        _secondaryPlayerID = State(initialValue: event.secondaryPlayerID)
        _ballStatus = State(initialValue: event.ballStatus)
        let batterWasOut = event.runnerMovements.first(where: { $0.origin == RunnerOrigin.batter.title })?.destination
            == RunnerDestination.out.title
        _resolvedOutcome = State(initialValue: batterWasOut ? .groundOut : .error)
    }

    var body: some View {
        Form {
            Section("确认后的中文记录") {
                TextField("事件说明", text: $title, axis: .vertical)
                    .lineLimit(2...5)
                    .accessibilityIdentifier("review-event-title")
                Picker("事件类型", selection: $category) {
                    ForEach(ScoringEventCategory.allCases) { value in
                        Text(value.title).tag(value)
                    }
                }
                TextField("记分符号（可不填）", text: $notation)
                    .textInputAutocapitalization(.characters)
            }

            Section("责任人与球状态") {
                if requiresOutcomeReview {
                    Picker("正式记分结果", selection: $resolvedOutcome) {
                        ForEach(reviewableOutcomes) { outcome in
                            Text("\(outcome.rawValue)（\(outcome.notation)）").tag(outcome)
                        }
                    }
                    .accessibilityIdentifier("review-event-outcome")
                }
                playerPicker("主要球员／责任人", selection: $primaryPlayerID)
                playerPicker("次要球员／责任人", selection: $secondaryPlayerID)
                Picker("球状态", selection: $ballStatus) {
                    Text("不适用").tag(BallStatus?.none)
                    ForEach(BallStatus.allCases) { status in
                        Text(status.rawValue).tag(Optional(status))
                    }
                }
            }

            Section("复核说明") {
                TextField("说明依据或修改原因（必填）", text: $note, axis: .vertical)
                    .lineLimit(2...5)
                    .accessibilityIdentifier("review-event-note")
            }

            Section("局面链") {
                Text(snapshotSummary(event.beforeSituation, prefix: "修改前"))
                Text(snapshotSummary(event.afterSituation, prefix: "修改后"))
                Text("保存前会从这条记录开始重放并检查全部后续事件；若局面不衔接，将拒绝修改并指出冲突记录。")
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
        }
        .navigationTitle("复核待确认记录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成复核") {
                    if store.reviewPendingEvent(
                        id: event.id,
                        title: title,
                        category: category,
                        notation: notation,
                        primaryPlayerID: primaryPlayerID,
                        secondaryPlayerID: secondaryPlayerID,
                        ballStatus: ballStatus,
                        resolvedOutcome: requiresOutcomeReview ? resolvedOutcome : nil,
                        note: note
                    ) {
                        dismiss()
                    }
                }
                .fontWeight(.bold)
                .accessibilityIdentifier("confirm-pending-event-review")
            }
        }
        .gameActionErrorAlert(store)
    }

    private var reviewableOutcomes: [PlayOutcome] {
        PlayOutcome.allCases.filter { ![.pending, .pendingOut, .other].contains($0) }
    }

    private var requiresOutcomeReview: Bool {
        event.resolvedOutcome.map { [.pending, .pendingOut, .other].contains($0) } ?? false
    }

    private func playerPicker(_ label: String, selection: Binding<UUID?>) -> some View {
        Picker(label, selection: selection) {
            Text("未指定").tag(UUID?.none)
            ForEach(store.eventReviewPlayers) { player in
                Text("#\(player.number) \(player.name)").tag(Optional(player.id))
            }
        }
    }

    private func snapshotSummary(_ snapshot: GameSituationSnapshot?, prefix: String) -> String {
        guard let snapshot else { return "\(prefix)：旧版记录未保存局面快照" }
        let score = "\(snapshot.awayRunsByInning.reduce(0, +)):\(snapshot.homeRunsByInning.reduce(0, +))"
        return "\(prefix)：\(snapshot.inning)局\(snapshot.isTop ? "上" : "下")，\(snapshot.balls)坏 \(snapshot.strikes)好 \(snapshot.outs)出局，比分 \(score)，垒上 \(snapshot.baseRunners.count) 人"
    }
}

#Preview("观察式击球") {
    BattedBallObservationSheet(onSelect: { _ in }, onUnclear: {})
}

#Preview("其他情况") {
    SpecialEventSheet(onRequestCorrection: {})
        .environmentObject(GameStore())
}
