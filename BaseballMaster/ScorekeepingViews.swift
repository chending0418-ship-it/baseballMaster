import SwiftUI
import UIKit

struct ScorekeepingView: View {
    @EnvironmentObject private var store: MockGameStore
    @State private var showPlayFlowSheet = false
    @State private var showRunnerEventSheet = false
    @State private var showSpecialEventSheet = false
    @State private var showCorrectionSheet = false
    @State private var showSubstitutionSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                compactScoreHeader
                VStack(spacing: 0) {
                    BaseballDiamondView(game: store.game)

                    Divider()
                        .overlay(BMTheme.line)

                    matchupBar
                }
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous)
                            .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
                    }

                if store.game.isFinal {
                    finalGameCard
                } else {
                    pitchControls

                    HStack(spacing: 10) {
                        Button {
                            showRunnerEventSheet = true
                        } label: {
                            Label("跑者变化", systemImage: "figure.run")
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                        .accessibilityIdentifier("open-runner-events")

                        Button {
                            showSpecialEventSheet = true
                        } label: {
                            Label("其他情况", systemImage: "ellipsis.circle")
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.orange))
                        .accessibilityIdentifier("open-special-events")
                    }

                    HStack(spacing: 10) {
                        undoButton

                        Button {
                            showCorrectionSheet = true
                        } label: {
                            Label("修正", systemImage: "slider.horizontal.3")
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.orange))
                        .accessibilityIdentifier("open-state-correction")

                        Button {
                            showSubstitutionSheet = true
                        } label: {
                            Label("换人", systemImage: "arrow.left.arrow.right")
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                    }
                }
            }
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.bottom, 24)
        }
        .bmScreenBackground()
        .navigationTitle("现场记分")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                NavigationLink(destination: GlossaryView()) {
                    Image(systemName: "questionmark.circle")
                }
                .accessibilityLabel("名词解释")
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(destination: BoxScoreView()) {
                    Text("比赛结果")
                        .font(.system(size: 14, weight: .bold))
                }
                .accessibilityIdentifier("open-box-score")
            }
        }
        .sheet(isPresented: $showPlayFlowSheet) {
            ScorePlayFlowSheet {
                showPlayFlowSheet = false
                presentCorrectionAfterDismissal()
            }
                .environmentObject(store)
                .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showRunnerEventSheet) {
            RunnerEventFlowSheet()
                .environmentObject(store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSpecialEventSheet) {
            SpecialEventSheet {
                showSpecialEventSheet = false
                presentCorrectionAfterDismissal()
            }
                .environmentObject(store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showCorrectionSheet) {
            GameStateCorrectionSheet()
                .environmentObject(store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSubstitutionSheet) {
            SubstitutionSheet()
                .environmentObject(store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
    }

    private var compactScoreHeader: some View {
        BMCard {
            VStack(spacing: 10) {
                HStack {
                    HStack(spacing: 6) {
                        Text("\(store.game.inning)局\(store.game.isTop ? "上" : "下")")
                            .font(.system(size: 13, weight: .black))
                        Circle()
                            .fill(BMTheme.red)
                            .frame(width: 6, height: 6)
                    }
                    .foregroundStyle(BMTheme.green)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(BMTheme.greenSoft)
                    .clipShape(Capsule())

                    Spacer()

                    HStack(spacing: 12) {
                        miniCount(label: "坏 B", value: store.game.balls, maximum: 3, color: BMTheme.green)
                        miniCount(label: "好 S", value: store.game.strikes, maximum: 2, color: BMTheme.orange)
                        miniCount(label: "出 O", value: store.game.outs, maximum: 2, color: BMTheme.red)
                    }
                }

                HStack(spacing: 7) {
                    scoreTeam(store.game.awayTeam, score: store.game.awayScore, isBatting: store.game.isTop, alignment: .leading)

                    Text(":")
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(BMTheme.secondaryText)

                    scoreTeam(store.game.homeTeam, score: store.game.homeScore, isBatting: !store.game.isTop, alignment: .trailing)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(store.game.inning)局\(store.game.isTop ? "上" : "下")，\(store.game.awayTeam.shortName)\(store.game.awayScore)比\(store.game.homeScore)\(store.game.homeTeam.shortName)，\(store.game.balls)坏球，\(store.game.strikes)好球，\(store.game.outs)出局")
    }

    private func scoreTeam(
        _ team: Team,
        score: Int,
        isBatting: Bool,
        alignment: HorizontalAlignment
    ) -> some View {
        HStack(spacing: 7) {
            if alignment == .leading {
                TeamMark(team: team, size: 34)
            }

            VStack(alignment: alignment, spacing: 0) {
                HStack(spacing: 4) {
                    if alignment == .trailing, isBatting {
                        Circle().fill(BMTheme.green).frame(width: 6, height: 6)
                    }
                    Text(team.shortName)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BMTheme.secondaryText)
                    if alignment == .leading, isBatting {
                        Circle().fill(BMTheme.green).frame(width: 6, height: 6)
                    }
                }
                Text("\(score)")
                    .font(.system(size: 28, weight: .black, design: .rounded))
                    .foregroundStyle(BMTheme.navy)
            }

            if alignment == .trailing {
                TeamMark(team: team, size: 34)
            }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private func miniCount(label: String, value: Int, maximum: Int, color: Color) -> some View {
        HStack(spacing: 3) {
            Text(label)
                .font(.system(size: 10, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.secondaryText)
            ForEach(0..<maximum, id: \.self) { index in
                Circle()
                    .fill(index < value ? color : BMTheme.line)
                    .frame(width: 8, height: 8)
            }
        }
    }

    private var matchupBar: some View {
        HStack(spacing: 0) {
            matchupPlayer(label: "投手", player: store.currentPitcher, alignment: .leading)
            Rectangle()
                .fill(BMTheme.line)
                .frame(width: 1, height: 35)
                .padding(.horizontal, 12)
            matchupPlayer(label: "打者", player: store.currentBatter, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .accessibilityIdentifier("current-matchup")
    }

    private func matchupPlayer(label: String, player: Player, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(BMTheme.secondaryText)
            Text("#\(player.number) \(player.name)")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(BMTheme.navy)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private var pitchControls: some View {
        VStack(spacing: 8) {
            HStack(spacing: 7) {
                pitchButton(.ball, color: BMTheme.green)
                pitchButton(.calledStrike, color: BMTheme.orange)
                pitchButton(.swingingStrike, color: BMTheme.orange)
                pitchButton(.foul, color: BMTheme.brandNavy)
            }
            Button {
                haptic(.medium)
                showPlayFlowSheet = true
            } label: {
                HStack {
                    Image(systemName: "baseball.fill")
                    Text("击球")
                    Text("进入场内")
                        .font(.system(size: 12, weight: .semibold))
                        .opacity(0.8)
                }
            }
            .buttonStyle(PrimaryButtonStyle(color: BMTheme.green))
            .accessibilityIdentifier("ball-in-play")
        }
    }

    private var undoButton: some View {
        Button {
            store.undo()
            haptic(.light)
        } label: {
            Label("撤销", systemImage: "arrow.uturn.backward")
        }
        .buttonStyle(SecondaryButtonStyle(color: BMTheme.navy))
        .disabled(!store.canUndo)
        .opacity(store.canUndo ? 1 : 0.45)
        .accessibilityIdentifier("undo-last-play")
    }

    private var finalGameCard: some View {
        BMCard {
            VStack(spacing: 12) {
                Label("比赛已经结束", systemImage: "checkmark.seal.fill")
                    .font(.system(size: 19, weight: .black))
                    .foregroundStyle(BMTheme.green)
                HStack(spacing: 10) {
                    undoButton
                    NavigationLink(destination: BoxScoreView()) {
                        Label("查看结果", systemImage: "tablecells")
                    }
                    .buttonStyle(PrimaryButtonStyle(color: BMTheme.green))
                }
            }
        }
    }

    private func pitchButton(_ action: PitchAction, color: Color) -> some View {
        Button {
            store.recordPitch(action)
            haptic(.light)
        } label: {
            Text(action.rawValue)
        }
        .buttonStyle(PrimaryButtonStyle(color: color))
        .accessibilityIdentifier("pitch-\(action.rawValue)")
    }

    private func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    private func presentCorrectionAfterDismissal() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            showCorrectionSheet = true
        }
    }
}

private enum PlayFlowStage {
    case arrival
    case cause(BatterArrival)
    case defense(ObservationCause, BatterArrival)
    case runners(ObservationCause, BatterArrival, DefensivePlay?)
}

struct ScorePlayFlowSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore
    let onNeedsCorrection: () -> Void
    @State private var stage: PlayFlowStage = .arrival

    init(onNeedsCorrection: @escaping () -> Void = {}) {
        self.onNeedsCorrection = onNeedsCorrection
    }

    var body: some View {
        Group {
            switch stage {
            case .arrival:
                BattedBallObservationSheet { arrival in
                    stage = .cause(arrival)
                } onUnclear: {
                    dismiss()
                    onNeedsCorrection()
                }

            case .cause(let arrival):
                BattedBallCauseSheet(
                    arrival: arrival,
                    hasRunners: store.hasRunners,
                    hasRunnerOnThird: store.game.baseRunners[.third] != nil,
                    outs: store.game.outs,
                    onBack: { stage = .arrival }
                ) { cause in
                    if cause.asksForFielder {
                        stage = .defense(cause, arrival)
                    } else {
                        continuePlay(cause: cause, arrival: arrival, defensivePlay: nil)
                    }
                }

            case .defense(let cause, let arrival):
                DefensivePlaySheet(outcome: cause.outcome) { play in
                    continuePlay(cause: cause, arrival: arrival, defensivePlay: play)
                }

            case .runners(let cause, let arrival, let defensivePlay):
                RunnerResolutionSheet(
                    decisions: store.suggestedRunnerDecisions(
                        for: cause.outcome,
                        batterDestination: arrival.destination
                    ),
                    availableDestinations: store.availableDestinations
                ) { decisions in
                    store.applyPlay(cause.outcome, defensivePlay: defensivePlay, decisions: decisions)
                    dismiss()
                }
            }
        }
    }

    private func continuePlay(
        cause: ObservationCause,
        arrival: BatterArrival,
        defensivePlay: DefensivePlay?
    ) {
        let needsRunnerConfirmation = store.hasRunners
            || [.fieldersChoice, .doublePlay, .sacrificeBunt, .sacrificeFly].contains(cause.outcome)
        if needsRunnerConfirmation {
            stage = .runners(cause, arrival, defensivePlay)
        } else {
            let decisions = store.suggestedRunnerDecisions(
                for: cause.outcome,
                batterDestination: arrival.destination
            )
            store.applyPlay(cause.outcome, defensivePlay: defensivePlay, decisions: decisions)
            dismiss()
        }
    }
}

struct PlayOutcomeSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSelect: (PlayOutcome) -> Void
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 9), count: 4)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("只需选择场上看到的结果")
                        .font(.system(size: 14))
                        .foregroundStyle(BMTheme.secondaryText)

                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(PlayOutcome.allCases) { outcome in
                            Button {
                                onSelect(outcome)
                            } label: {
                                VStack(spacing: 7) {
                                    Text(outcome.rawValue)
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                        .multilineTextAlignment(.center)
                                        .lineLimit(2)
                                    Text(outcome.notation)
                                        .font(.system(size: 11, weight: .black, design: .rounded))
                                        .foregroundStyle(outcomeColor(outcome))
                                }
                                .frame(maxWidth: .infinity, minHeight: 72)
                                .background(outcomeBackground(outcome))
                                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("outcome-\(outcome.rawValue)")
                        }
                    }

                    HStack(spacing: 8) {
                        Image(systemName: "lightbulb.fill")
                            .foregroundStyle(BMTheme.orange)
                        Text("不确定时先选“其他”，赛后可补充。")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    .padding(12)
                    .background(BMTheme.orangeSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("击球结果")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("取消")
                }
            }
        }
    }

    private func outcomeColor(_ outcome: PlayOutcome) -> Color {
        if outcome.isHit { return BMTheme.green }
        if outcome == .error { return BMTheme.orange }
        if [.groundOut, .flyOut, .doublePlay].contains(outcome) { return BMTheme.red }
        return BMTheme.navy
    }

    private func outcomeBackground(_ outcome: PlayOutcome) -> Color {
        if outcome.isHit { return BMTheme.greenSoft }
        if outcome == .error { return BMTheme.orangeSoft }
        if [.groundOut, .flyOut, .doublePlay].contains(outcome) { return BMTheme.redSoft }
        return BMTheme.surface
    }
}

struct DefensivePlaySheet: View {
    @Environment(\.dismiss) private var dismiss
    let outcome: PlayOutcome
    let onSelect: (DefensivePlay) -> Void

    private var plays: [DefensivePlay] {
        switch outcome {
        case .groundOut, .fieldersChoice:
            return Array(DefensivePlay.quickPlays.prefix(3)) + [DefensivePlay.quickPlays.last!]
        case .flyOut:
            return Array(DefensivePlay.quickPlays[3...5]) + [DefensivePlay.quickPlays.last!]
        case .doublePlay:
            return [DefensivePlay.quickPlays[6], DefensivePlay.quickPlays[0], DefensivePlay.quickPlays[1], DefensivePlay.quickPlays.last!]
        default:
            return DefensivePlay.quickPlays
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(outcome == .error ? "谁发生了失误？" : "谁处理了这个球？")
                        .font(.system(size: 21, weight: .black))
                        .foregroundStyle(BMTheme.navy)
                    Text("只选主要责任人，专业记分符号会自动生成。")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)

                    if outcome == .error {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 10) {
                            ForEach(FieldPosition.allCases) { position in
                                Button {
                                    onSelect(.error(at: position))
                                } label: {
                                    VStack(spacing: 6) {
                                        Text(position.shortName)
                                            .font(.system(size: 15, weight: .bold))
                                        Text("E\(position.rawValue)")
                                            .font(.system(size: 11, weight: .black, design: .rounded))
                                            .foregroundStyle(BMTheme.orange)
                                    }
                                    .foregroundStyle(BMTheme.navy)
                                    .frame(maxWidth: .infinity, minHeight: 68)
                                    .background(BMTheme.orangeSoft)
                                    .clipShape(RoundedRectangle(cornerRadius: 13))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                            ForEach(plays) { play in
                                Button {
                                    onSelect(play)
                                } label: {
                                    VStack(spacing: 6) {
                                        Text(play.title)
                                            .font(.system(size: 15, weight: .bold))
                                        Text(play.notation)
                                            .font(.system(size: 12, weight: .black, design: .rounded))
                                            .foregroundStyle(BMTheme.green)
                                    }
                                    .foregroundStyle(BMTheme.navy)
                                    .frame(maxWidth: .infinity, minHeight: 72)
                                    .background(BMTheme.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 13))
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 13).stroke(BMTheme.line, lineWidth: 1)
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("defense-\(play.id)")
                            }
                        }
                    }
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle(outcome.rawValue)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { dismiss() }
                }
            }
        }
    }
}

struct RunnerResolutionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var decisions: [RunnerDecision]
    let availableDestinations: (RunnerDecision) -> [RunnerDestination]
    let title: String
    let message: String
    let confirmTitle: String
    let onConfirm: ([RunnerDecision]) -> Void

    init(
        decisions: [RunnerDecision],
        availableDestinations: @escaping (RunnerDecision) -> [RunnerDestination],
        title: String = "确认跑者",
        message: String = "系统已根据结果给出建议，请确认每名跑者。",
        confirmTitle: String = "确认跑者结果",
        onConfirm: @escaping ([RunnerDecision]) -> Void
    ) {
        _decisions = State(initialValue: decisions)
        self.availableDestinations = availableDestinations
        self.title = title
        self.message = message
        self.confirmTitle = confirmTitle
        self.onConfirm = onConfirm
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 9) {
                        Image(systemName: "sparkles")
                            .foregroundStyle(BMTheme.green)
                        Text(message)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    .padding(12)
                    .background(BMTheme.greenSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    if hasBaseConflict {
                        Label("同一个垒位不能站两名跑者，请修改其中一人。", systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(BMTheme.orange)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(BMTheme.orangeSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    ForEach($decisions) { $decision in
                        BMCard {
                            VStack(alignment: .leading, spacing: 13) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(decision.origin.title)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundStyle(BMTheme.secondaryText)
                                        Text("#\(decision.player.number) \(decision.player.name)")
                                            .font(.system(size: 17, weight: .bold))
                                            .foregroundStyle(BMTheme.navy)
                                    }
                                    Spacer()
                                    Text(decision.destination.title)
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundStyle(destinationColor(decision.destination))
                                }

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(availableDestinations(decision), id: \.self) { destination in
                                            Button {
                                                decision.destination = destination
                                            } label: {
                                                Text(destination.title)
                                                    .font(.system(size: 13, weight: .bold))
                                                    .foregroundStyle(decision.destination == destination ? .white : BMTheme.navy)
                                                    .padding(.horizontal, 13)
                                                    .frame(minHeight: 40)
                                                    .background(decision.destination == destination ? destinationColor(destination) : BMTheme.background)
                                                    .clipShape(Capsule())
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(18)
            }
            .safeAreaInset(edge: .bottom) {
                Button(confirmTitle) {
                    onConfirm(decisions)
                }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(BMTheme.background)
                .accessibilityIdentifier("confirm-runners")
                .disabled(hasBaseConflict)
                .opacity(hasBaseConflict ? 0.45 : 1)
            }
            .bmScreenBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") { dismiss() }
                }
            }
        }
    }

    private func destinationColor(_ destination: RunnerDestination) -> Color {
        switch destination {
        case .score, .base: BMTheme.green
        case .out: BMTheme.red
        case .hold: BMTheme.secondaryText
        }
    }

    private var hasBaseConflict: Bool {
        var occupied = Set<Base>()
        for decision in decisions {
            guard case .base(let base) = decision.destination else { continue }
            if occupied.contains(base) { return true }
            occupied.insert(base)
        }
        return false
    }
}

private enum SubstitutionStage: Equatable {
    case menu
    case pitcher
    case pinchHitter
    case pinchRunnerBase
    case pinchRunnerPlayer(Base)
    case fielder
    case position(Player)
}

struct SubstitutionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore
    @State private var stage: SubstitutionStage = .menu

    var body: some View {
        NavigationStack {
            ScrollView {
                content
                    .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(stage == .menu ? "取消" : "返回") {
                        if stage == .menu { dismiss() } else { stage = .menu }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch stage {
        case .menu:
            VStack(spacing: 10) {
                menuButton("换投手", detail: "选择新的投手", icon: "arrow.triangle.2.circlepath") { stage = .pitcher }
                menuButton("代打", detail: "替换当前打者", icon: "figure.baseball") { stage = .pinchHitter }
                menuButton("代跑", detail: "选择垒位和替换球员", icon: "figure.run") { stage = .pinchRunnerBase }
                menuButton("调整守位", detail: "交换球员的防守位置", icon: "square.grid.3x3.fill") { stage = .fielder }
            }

        case .pitcher:
            playerList(store.game.fieldingTeam.players.filter { $0.id != store.currentPitcher.id }) { player in
                store.changePitcher(to: player)
                dismiss()
            }

        case .pinchHitter:
            playerList(store.game.battingTeam.players.filter { $0.id != store.currentBatter.id }) { player in
                store.replaceCurrentBatter(with: player)
                dismiss()
            }

        case .pinchRunnerBase:
            VStack(spacing: 10) {
                if store.game.baseRunners.isEmpty {
                    SimpleEmptyState(title: "垒上没有跑者", systemImage: "figure.run", message: "有跑者时才能安排代跑。")
                } else {
                    ForEach(Base.allCases, id: \.self) { base in
                        if let runner = store.game.baseRunners[base] {
                            Button {
                                stage = .pinchRunnerPlayer(base)
                            } label: {
                                HStack {
                                    Text(base.title)
                                        .font(.system(size: 16, weight: .bold))
                                    Spacer()
                                    Text("#\(runner.number) \(runner.name)")
                                    Image(systemName: "chevron.right")
                                }
                                .foregroundStyle(BMTheme.navy)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 58)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 13))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

        case .pinchRunnerPlayer(let base):
            playerList(store.game.battingTeam.players.filter { candidate in
                !store.game.baseRunners.values.contains(where: { $0.id == candidate.id })
            }) { player in
                store.replaceRunner(on: base, with: player)
                dismiss()
            }

        case .fielder:
            playerList(store.game.fieldingTeam.players) { player in
                stage = .position(player)
            }

        case .position(let player):
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(FieldPosition.allCases) { position in
                    Button {
                        store.changeFieldingPosition(for: player, to: position)
                        dismiss()
                    } label: {
                        VStack(spacing: 6) {
                            Text(position.shortName)
                                .font(.system(size: 16, weight: .bold))
                            Text(position.fullName)
                                .font(.system(size: 11))
                        }
                        .foregroundStyle(BMTheme.navy)
                        .frame(maxWidth: .infinity, minHeight: 70)
                        .background(position == player.primaryPosition ? BMTheme.greenSoft : BMTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 13))
                        .overlay {
                            RoundedRectangle(cornerRadius: 13).stroke(BMTheme.line, lineWidth: 1)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var navigationTitle: String {
        switch stage {
        case .menu: "换人与守位"
        case .pitcher: "选择新投手"
        case .pinchHitter: "选择代打"
        case .pinchRunnerBase: "选择代跑垒位"
        case .pinchRunnerPlayer: "选择代跑球员"
        case .fielder: "选择守备球员"
        case .position(let player): "#\(player.number) 调整守位"
        }
    }

    private func menuButton(
        _ title: String,
        detail: String,
        icon: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 13) {
                Image(systemName: icon)
                    .foregroundStyle(BMTheme.green)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 16, weight: .bold))
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .foregroundStyle(BMTheme.navy)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(BMTheme.secondaryText)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 13))
        }
        .buttonStyle(.plain)
    }

    private func playerList(_ players: [Player], onSelect: @escaping (Player) -> Void) -> some View {
        VStack(spacing: 9) {
            ForEach(players) { player in
                Button {
                    onSelect(player)
                } label: {
                    PlayerRow(player: player)
                        .padding(.horizontal, 13)
                        .frame(minHeight: 58)
                        .background(BMTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 13))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

#Preview("现场记分") {
    NavigationStack { ScorekeepingView() }
        .environmentObject(MockGameStore())
}

#Preview("击球结果") {
    BattedBallObservationSheet(onSelect: { _ in }, onUnclear: {})
}

#Preview("跑者确认") {
    let store = MockGameStore()
    RunnerResolutionSheet(
        decisions: store.suggestedRunnerDecisions(for: .single),
        availableDestinations: store.availableDestinations,
        onConfirm: { _ in }
    )
}
