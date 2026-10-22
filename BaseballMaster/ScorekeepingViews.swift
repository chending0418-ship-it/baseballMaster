import SwiftUI
import UIKit

struct ScorekeepingView: View {
    @EnvironmentObject private var store: GameStore
    @State private var showPlayFlowSheet = false
    @State private var showRunnerEventSheet = false
    @State private var showSpecialEventSheet = false
    @State private var showCorrectionSheet = false
    @State private var showSubstitutionSheet = false

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 7) {
                compactScoreHeader
                VStack(spacing: 0) {
                    BaseballDiamondView(game: store.game, fixedHeight: nil)

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
                    .frame(maxHeight: .infinity)
                    .overlay(alignment: .top) {
                        TimelineView(.periodic(from: .now, by: 60)) { context in
                            let notices = store.ruleNotices(at: context.date)
                            if !notices.isEmpty {
                                Label(notices.joined(separator: " · "), systemImage: "bell.badge.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BMTheme.orange)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.72)
                                    .padding(.horizontal, 10)
                                    .frame(height: 28)
                                    .background(BMTheme.orangeSoft.opacity(0.96))
                                    .clipShape(Capsule())
                                    .padding(.top, 7)
                                    .accessibilityIdentifier("game-rule-warning")
                            }
                        }
                    }

                if store.game.isFinal {
                    finalGameCard
                } else {
                    pitchControls

                    HStack(spacing: 10) {
                        Button {
                            showRunnerEventSheet = true
                        } label: {
                            Label("跑者 / 出局", systemImage: "figure.run")
                        }
                        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.green))
                        .accessibilityIdentifier("open-runner-events")

                        Button {
                            showSpecialEventSheet = true
                        } label: {
                            Label("特殊 / 判罚", systemImage: "exclamationmark.shield")
                        }
                        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.orange))
                        .accessibilityIdentifier("open-special-events")
                    }

                    HStack(spacing: 7) {
                        undoButton
                        redoButton

                        Button {
                            showCorrectionSheet = true
                        } label: {
                            Label("修正", systemImage: "slider.horizontal.3")
                        }
                        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.orange, compact: true))
                        .accessibilityIdentifier("open-state-correction")

                        Button {
                            showSubstitutionSheet = true
                        } label: {
                            Label("换人", systemImage: "arrow.left.arrow.right")
                        }
                        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.green, compact: true))
                        .accessibilityIdentifier("open-substitutions")
                    }
                }
            }
            .frame(
                width: max(0, proxy.size.width - 24),
                height: max(0, proxy.size.height - 8),
                alignment: .top
            )
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .bmScreenBackground()
        .navigationTitle("现场记分")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                NavigationLink(destination: BaseballRulesView()) {
                    Image(systemName: "questionmark.circle")
                }
                .accessibilityLabel("棒球规则")
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let stored = store.activeStoredGame {
                    NavigationLink(destination: GamePosterView(game: stored)) {
                        Image(systemName: "photo.badge.plus")
                    }
                    .accessibilityLabel("比赛宣传海报")
                    .accessibilityIdentifier("open-game-poster")
                }
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
        .sheet(
            isPresented: Binding(
                get: { store.requiresTiebreakRunnerPlacement },
                set: { _ in }
            )
        ) {
            TiebreakRunnerSheet()
                .environmentObject(store)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.hidden)
                .interactiveDismissDisabled()
        }
        .gameActionErrorAlert(store)
    }

    private var compactScoreHeader: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(store.game.inning)局\(store.game.isTop ? "上" : "下")")
                    .font(.system(size: 12, weight: .black))
                    .foregroundStyle(BMTheme.green)

                HStack(spacing: 5) {
                    Text(store.game.awayTeam.shortName)
                    Text("\(store.game.awayScore)")
                        .font(.system(size: 22, weight: .black, design: .rounded))
                    Text(":")
                        .foregroundStyle(BMTheme.secondaryText)
                    Text("\(store.game.homeScore)")
                        .font(.system(size: 22, weight: .black, design: .rounded))
                    Text(store.game.homeTeam.shortName)
                }
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(BMTheme.navy)
            }
            Spacer(minLength: 2)

            VStack(alignment: .trailing, spacing: 4) {
                gameClockControl
                HStack(spacing: 9) {
                    miniCount(label: "B", value: store.game.balls, maximum: 3, color: BMTheme.green)
                    miniCount(label: "S", value: store.game.strikes, maximum: 2, color: BMTheme.orange)
                    miniCount(label: "O", value: store.game.outs, maximum: 2, color: BMTheme.red)
                }
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 58)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private var gameClockControl: some View {
        if !store.hasStartedGameClock {
            Button {
                store.startGameClock()
                haptic(.medium)
            } label: {
                Label("Play Ball", systemImage: "play.fill")
                    .font(.system(size: 10, weight: .black))
                    .padding(.horizontal, 9)
                    .frame(height: 24)
                    .foregroundStyle(.white)
                    .background(BMTheme.green)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("start-game-clock")
            .accessibilityLabel("Play Ball，开始比赛计时")
        } else {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                HStack(spacing: 4) {
                    Button {
                        store.toggleGameClockDisplayMode()
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: store.gameClockDisplayMode == .remaining ? "hourglass.bottomhalf.filled" : "stopwatch.fill")
                            Text(store.gameClockDisplayMode == .remaining ? "余" : "正")
                            Text(store.gameClockText(at: context.date))
                                .monospacedDigit()
                        }
                        .font(.system(size: 10, weight: .black, design: .rounded))
                        .foregroundStyle(clockColor(at: context.date))
                        .frame(minWidth: 68, minHeight: 22)
                        .background(clockColor(at: context.date).opacity(0.10))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("game-clock-display")
                    .accessibilityLabel("\(store.gameClockDisplayMode.title)\(store.gameClockText(at: context.date))")
                    .accessibilityHint(store.canShowRemainingGameTime ? "轻点切换已进行时间和剩余时间" : "本场未设置时间限制")

                    Button {
                        if store.isGameClockRunning {
                            store.pauseGameClock(at: context.date)
                        } else {
                            store.startGameClock(at: context.date)
                        }
                        haptic(.light)
                    } label: {
                        Image(systemName: store.isGameClockRunning ? "pause.fill" : "play.fill")
                            .font(.system(size: 9, weight: .black))
                            .foregroundStyle(BMTheme.navy)
                            .frame(width: 22, height: 22)
                            .background(BMTheme.background)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("toggle-game-clock-running")
                    .accessibilityLabel(store.isGameClockRunning ? "暂停比赛计时" : "继续比赛计时")
                }
            }
        }
    }

    private func clockColor(at date: Date) -> Color {
        guard store.gameClockDisplayMode == .remaining,
              let remaining = store.remainingGameTime(at: date) else { return BMTheme.green }
        if remaining <= 0 { return BMTheme.red }
        if let warning = store.activeRules?.timeWarningMinutes,
           remaining <= TimeInterval(warning * 60) { return BMTheme.orange }
        return BMTheme.green
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
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(value)\(label == "B" ? "坏球" : label == "S" ? "好球" : "出局")")
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
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label)，\(player.number)号，\(player.name)")
    }

    private var pitchControls: some View {
        VStack(spacing: 7) {
            HStack(spacing: 7) {
                pitchButton(.ball, color: BMTheme.brandGreen)
                pitchButton(.calledStrike, color: BMTheme.brandOrange)
                pitchButton(.swingingStrike, color: BMTheme.brandOrange)
                pitchButton(.foul, color: BMTheme.brandNavy)
            }
            Button {
                haptic(.medium)
                showPlayFlowSheet = true
            } label: {
                HStack {
                    Image(systemName: "baseball.fill")
                    Text("击球进入场内")
                        .font(.system(size: 12, weight: .semibold))
                }
            }
            .buttonStyle(ScoreActionButtonStyle(color: BMTheme.brandGreen, filled: true))
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
        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.navy, compact: true))
        .disabled(!store.canUndo)
        .opacity(store.canUndo ? 1 : 0.45)
        .accessibilityIdentifier("undo-last-play")
    }

    private var redoButton: some View {
        Button {
            store.redo()
            haptic(.light)
        } label: {
            Label("恢复", systemImage: "arrow.uturn.forward")
        }
        .buttonStyle(ScoreActionButtonStyle(color: BMTheme.navy, compact: true))
        .disabled(!store.canRedo)
        .opacity(store.canRedo ? 1 : 0.45)
        .accessibilityIdentifier("redo-last-play")
    }

    private var finalGameCard: some View {
        BMCard {
            VStack(spacing: 12) {
                Label("比赛已经结束", systemImage: "checkmark.seal.fill")
                    .font(.system(size: 19, weight: .black))
                    .foregroundStyle(BMTheme.green)
                HStack(spacing: 10) {
                    undoButton
                    redoButton
                    NavigationLink(destination: BoxScoreView()) {
                        Label("查看结果", systemImage: "tablecells")
                    }
                    .buttonStyle(PrimaryButtonStyle(color: BMTheme.brandGreen))
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
        .buttonStyle(ScoreActionButtonStyle(color: color, filled: true))
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
    case timing(ObservationCause, BatterArrival, DefensivePlay?, [RunnerDecision])
}

struct ScorePlayFlowSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
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
                    hasRunnersOnFirstAndSecond: store.game.baseRunners[.first] != nil
                        && store.game.baseRunners[.second] != nil,
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
                    if store.playNeedsTimingDecision(cause.outcome, decisions: decisions) {
                        stage = .timing(cause, arrival, defensivePlay, decisions)
                    } else {
                        if store.applyPlay(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) {
                            dismiss()
                        }
                    }
                }

            case .timing(let cause, _, let defensivePlay, let decisions):
                ThirdOutTimingSheet(title: cause.outcome.rawValue) { runCounts in
                    if store.applyPlay(
                        cause.outcome,
                        defensivePlay: defensivePlay,
                        decisions: decisions,
                        timingRunCounts: runCounts
                    ) {
                        dismiss()
                    }
                }
            }
        }
        .gameActionErrorAlert(store)
    }

    private func continuePlay(
        cause: ObservationCause,
        arrival: BatterArrival,
        defensivePlay: DefensivePlay?
    ) {
        let needsRunnerConfirmation = store.hasRunners
            || [.fieldersChoice, .runnerTagOut, .doublePlay, .triplePlay, .infieldFly, .sacrificeBunt, .sacrificeFly].contains(cause.outcome)
        if needsRunnerConfirmation {
            stage = .runners(cause, arrival, defensivePlay)
        } else {
            let decisions = store.suggestedRunnerDecisions(
                for: cause.outcome,
                batterDestination: arrival.destination
            )
            if store.applyPlay(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) {
                dismiss()
            }
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
        if [.groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly, .runnerTagOut, .doublePlay, .triplePlay].contains(outcome) {
            return BMTheme.red
        }
        return BMTheme.navy
    }

    private func outcomeBackground(_ outcome: PlayOutcome) -> Color {
        if outcome.isHit { return BMTheme.greenSoft }
        if outcome == .error { return BMTheme.orangeSoft }
        if [.groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly, .runnerTagOut, .doublePlay, .triplePlay].contains(outcome) {
            return BMTheme.redSoft
        }
        return BMTheme.surface
    }
}

struct DefensivePlaySheet: View {
    @Environment(\.dismiss) private var dismiss
    let outcome: PlayOutcome
    let onSelect: (DefensivePlay) -> Void
    @State private var showCustomRoute = false

    private var plays: [DefensivePlay] {
        switch outcome {
        case .groundOut, .fieldersChoice, .runnerTagOut:
            return Array(DefensivePlay.quickPlays.prefix(3)) + [DefensivePlay.quickPlays.last!]
        case .flyOut:
            return Array(DefensivePlay.quickPlays[3...5]) + [DefensivePlay.quickPlays.last!]
        case .lineOut, .foulFlyOut:
            return FieldPosition.allCases.map(DefensivePlay.caught(by:)) + [DefensivePlay.quickPlays.last!]
        case .infieldFly:
            return Array(FieldPosition.allCases.prefix(6)).map(DefensivePlay.caught(by:))
                + [DefensivePlay.quickPlays.last!]
        case .doublePlay:
            return [DefensivePlay.quickPlays[6], DefensivePlay.quickPlays[0], DefensivePlay.quickPlays[1], DefensivePlay.quickPlays.last!]
        case .triplePlay:
            return [DefensivePlay.quickPlays[7], DefensivePlay.quickPlays.last!]
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
                                    if play.id == "OTHER" {
                                        showCustomRoute = true
                                    } else {
                                        onSelect(play)
                                    }
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
        .sheet(isPresented: $showCustomRoute) {
            CustomDefensiveRouteSheet(outcome: outcome) { play in
                showCustomRoute = false
                onSelect(play)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
    }
}

struct CustomDefensiveRouteSheet: View {
    @Environment(\.dismiss) private var dismiss
    let outcome: PlayOutcome
    let onConfirm: (DefensivePlay) -> Void
    @State private var route: [FieldPosition] = []

    private var minimumRouteCount: Int {
        switch outcome {
        case .doublePlay: 2
        case .triplePlay: 3
        default: 1
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("按球实际经过的顺序依次点击守备员。最后完成出局的人记录刺杀，之前参与传球的人记录助杀。")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)

                    HStack {
                        Text(route.isEmpty ? "尚未选择" : route.map { String($0.rawValue) }.joined(separator: " → "))
                            .font(.system(size: 20, weight: .black, design: .rounded))
                            .foregroundStyle(BMTheme.navy)
                        Spacer()
                        if !route.isEmpty {
                            Button("清空") { route.removeAll() }
                                .font(.system(size: 13, weight: .bold))
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                    .background(BMTheme.greenSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 13))

                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 10) {
                        ForEach(FieldPosition.allCases) { position in
                            let selectedIndex = route.firstIndex(of: position)
                            Button {
                                if let selectedIndex {
                                    route.remove(at: selectedIndex)
                                } else {
                                    route.append(position)
                                }
                            } label: {
                                VStack(spacing: 5) {
                                    Text(position.shortName)
                                        .font(.system(size: 16, weight: .bold))
                                    Text(selectedIndex.map { "第 \($0 + 1) 步" } ?? position.fullName)
                                        .font(.system(size: 10, weight: .medium))
                                }
                                .foregroundStyle(selectedIndex == nil ? BMTheme.navy : .white)
                                .frame(maxWidth: .infinity, minHeight: 66)
                                .background(selectedIndex == nil ? BMTheme.surface : BMTheme.green)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 12).stroke(BMTheme.line, lineWidth: selectedIndex == nil ? 1 : 0)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("custom-route-\(position.rawValue)")
                        }
                    }
                }
                .padding(18)
            }
            .safeAreaInset(edge: .bottom) {
                Button("保存守备路线") {
                    onConfirm(.custom(route: route, outcome: outcome))
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(route.count < minimumRouteCount)
                .opacity(route.count < minimumRouteCount ? 0.45 : 1)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(BMTheme.background)
                .accessibilityIdentifier("confirm-custom-defense-route")
            }
            .bmScreenBackground()
            .navigationTitle("自定义守备路线")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
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
    case fielderReplacementTarget
    case fielderReplacementPlayer(Player)
    case doubleSwitch
    case fielder
    case position(Player)
}

struct SubstitutionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
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
                menuButton("换投手", detail: "选择新的投手", icon: "arrow.triangle.2.circlepath", id: "substitution-pitcher") { stage = .pitcher }
                menuButton("代打", detail: "替换当前打者", icon: "figure.baseball", id: "substitution-pinch-hitter") { stage = .pinchHitter }
                menuButton("代跑", detail: "选择垒位和替换球员", icon: "figure.run", id: "substitution-pinch-runner") { stage = .pinchRunnerBase }
                menuButton("守备换人", detail: "替换任意一名场上守备员", icon: "person.crop.circle.badge.plus", id: "substitution-fielder") { stage = .fielderReplacementTarget }
                menuButton("双重换人", detail: "一次确认两组球员、棒次和守位", icon: "arrow.triangle.swap", id: "substitution-double-switch") { stage = .doubleSwitch }
                menuButton("调整守位", detail: "交换球员的防守位置", icon: "square.grid.3x3.fill", id: "substitution-position") { stage = .fielder }
            }

        case .pitcher:
            playerList(
                store.activeFielders.filter { $0.id != store.currentPitcher.id } + store.fieldingBenchPlayers
            ) { player in
                store.changePitcher(to: player)
                dismiss()
            }

        case .pinchHitter:
            playerList(store.battingBenchPlayers) { player in
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
            playerList(store.battingBenchPlayers) { player in
                store.replaceRunner(on: base, with: player)
                dismiss()
            }

        case .fielderReplacementTarget:
            playerList(store.activeFielders) { player in
                stage = .fielderReplacementPlayer(player)
            }

        case .fielderReplacementPlayer(let previous):
            playerList(store.fieldingBenchPlayers) { replacement in
                if store.replaceFielder(previous, with: replacement) { dismiss() }
            }

        case .doubleSwitch:
            DoubleSwitchEditor(onComplete: { dismiss() })
                .environmentObject(store)

        case .fielder:
            playerList(store.activeFielders) { player in
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
        case .fielderReplacementTarget: "选择退场守备员"
        case .fielderReplacementPlayer: "选择替补守备员"
        case .doubleSwitch: "双重换人"
        case .fielder: "选择守备球员"
        case .position(let player): "#\(player.number) 调整守位"
        }
    }

    private func menuButton(
        _ title: String,
        detail: String,
        icon: String,
        id: String,
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
        .accessibilityIdentifier(id)
    }

    private func playerList(_ players: [Player], onSelect: @escaping (Player) -> Void) -> some View {
        VStack(spacing: 9) {
            if players.isEmpty {
                SimpleEmptyState(
                    title: "没有可用替补",
                    systemImage: "person.crop.circle.badge.xmark",
                    message: "场上球员不能重复替换，已经退场的球员默认不能重新上场。"
                )
            } else {
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
}

private struct DoubleSwitchEditor: View {
    @EnvironmentObject private var store: GameStore
    let onComplete: () -> Void

    @State private var firstOutID: UUID?
    @State private var firstInID: UUID?
    @State private var firstPosition: FieldPosition = .pitcher
    @State private var secondOutID: UUID?
    @State private var secondInID: UUID?
    @State private var secondPosition: FieldPosition = .rightField
    @State private var loaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("两名替补分别接管所选退场球员的棒次；启用 DH 的比赛需先处理 DH／投手关系。")
                .font(.system(size: 12))
                .foregroundStyle(BMTheme.secondaryText)

            switchCard(title: "第一组", outID: $firstOutID, inID: $firstInID, position: $firstPosition)
            switchCard(title: "第二组", outID: $secondOutID, inID: $secondInID, position: $secondPosition)

            if let validationMessage {
                Label(validationMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BMTheme.orange)
            }

            Button("确认双重换人") { submit() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(validationMessage != nil)
                .opacity(validationMessage == nil ? 1 : 0.45)
                .accessibilityIdentifier("confirm-double-switch")
        }
        .onAppear(perform: loadDefaults)
    }

    private func switchCard(
        title: String,
        outID: Binding<UUID?>,
        inID: Binding<UUID?>,
        position: Binding<FieldPosition>
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 14, weight: .black))
                .foregroundStyle(BMTheme.green)
            Picker("退场", selection: outID) {
                Text("请选择").tag(UUID?.none)
                ForEach(store.activeFielders) { player in
                    Text("#\(player.number) \(player.name)").tag(Optional(player.id))
                }
            }
            Picker("替补", selection: inID) {
                Text("请选择").tag(UUID?.none)
                ForEach(store.fieldingBenchPlayers) { player in
                    Text("#\(player.number) \(player.name)").tag(Optional(player.id))
                }
            }
            Picker("新守位", selection: position) {
                ForEach(FieldPosition.allCases) { fieldPosition in
                    Text(fieldPosition.fullName).tag(fieldPosition)
                }
            }
        }
        .padding(14)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var validationMessage: String? {
        guard firstOutID != nil, firstInID != nil, secondOutID != nil, secondInID != nil else {
            return "请完整选择两组换人。"
        }
        if firstOutID == secondOutID { return "两组不能选择同一名退场球员。" }
        if firstInID == secondInID { return "两组不能选择同一名替补球员。" }
        if firstPosition == secondPosition { return "两名替补不能占用同一守位。" }
        return nil
    }

    private func loadDefaults() {
        guard !loaded else { return }
        loaded = true
        let fielders = store.activeFielders
        let bench = store.fieldingBenchPlayers
        firstOutID = fielders.first?.id
        secondOutID = fielders.dropFirst().first?.id
        firstInID = bench.first?.id
        secondInID = bench.dropFirst().first?.id
        firstPosition = fielders.first?.primaryPosition ?? .pitcher
        secondPosition = fielders.dropFirst().first?.primaryPosition ?? .catcher
    }

    private func submit() {
        guard let firstOut = store.activeFielders.first(where: { $0.id == firstOutID }),
              let secondOut = store.activeFielders.first(where: { $0.id == secondOutID }),
              let firstIn = store.fieldingBenchPlayers.first(where: { $0.id == firstInID }),
              let secondIn = store.fieldingBenchPlayers.first(where: { $0.id == secondInID }) else { return }
        if store.performDoubleSwitch(
            firstOut: firstOut,
            firstIn: firstIn,
            firstPosition: firstPosition,
            secondOut: secondOut,
            secondIn: secondIn,
            secondPosition: secondPosition
        ) {
            onComplete()
        }
    }
}

private struct ScoreActionButtonStyle: ButtonStyle {
    var color: Color
    var filled = false
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: compact ? 12 : 13, weight: .bold))
            .foregroundStyle(filled ? Color.white : color)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(
                filled
                    ? color.opacity(configuration.isPressed ? 0.78 : 1)
                    : BMTheme.surface.opacity(configuration.isPressed ? 0.62 : 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay {
                if !filled {
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .stroke(color.opacity(0.25), lineWidth: 1)
                }
            }
    }
}

struct TiebreakRunnerSheet: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("第 \(store.game.inning) 局\(store.game.isTop ? "上" : "下")，请按赛事规程放置自动跑者。")
                        .font(.system(size: 14))
                        .foregroundStyle(BMTheme.secondaryText)

                    if let targetBase = store.nextTiebreakRunnerBase {
                        Label("当前放置：\(targetBase.title)（共 \(store.tiebreakRunnerBases.count) 人）", systemImage: "diamond.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                    }

                    if let recommended = store.recommendedTiebreakRunner,
                       store.eligibleTiebreakRunners.contains(where: { $0.id == recommended.id }) {
                        Text("建议 · 上一棒")
                            .font(.system(size: 13, weight: .black))
                            .foregroundStyle(BMTheme.green)
                        Button {
                            store.placeTiebreakRunner(recommended)
                        } label: {
                            HStack {
                                PlayerRow(player: recommended)
                                Spacer()
                                Text("放\(store.nextTiebreakRunnerBase?.title ?? "指定垒位")")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(BMTheme.green)
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 62)
                            .background(BMTheme.greenSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("tiebreak-recommended-runner")
                    }

                    Text("选择其他球员")
                        .font(.system(size: 13, weight: .black))
                        .foregroundStyle(BMTheme.secondaryText)
                    ForEach(store.eligibleTiebreakRunners.filter { $0.id != store.recommendedTiebreakRunner?.id }) { player in
                        Button {
                            store.placeTiebreakRunner(player)
                        } label: {
                            PlayerRow(player: player)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 54)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 13))
                        }
                        .buttonStyle(.plain)
                    }

                    Button("本半局暂不放人 · 待确认") {
                        store.skipTiebreakRunnerForCurrentHalf()
                    }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(BMTheme.orange)
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
                .padding(18)
            }
            .bmScreenBackground()
            .navigationTitle("TB 垒上放人")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

#Preview("现场记分") {
    NavigationStack { ScorekeepingView() }
        .environmentObject(GameStore())
}

#Preview("击球结果") {
    BattedBallObservationSheet(onSelect: { _ in }, onUnclear: {})
}

#Preview("跑者确认") {
    let store = GameStore()
    RunnerResolutionSheet(
        decisions: store.suggestedRunnerDecisions(for: .single),
        availableDestinations: store.availableDestinations,
        onConfirm: { _ in }
    )
}

private struct GameActionErrorAlertModifier: ViewModifier {
    @ObservedObject var store: GameStore

    func body(content: Content) -> some View {
        content.alert(
            "无法保存这次记录",
            isPresented: Binding(
                get: { store.actionErrorMessage != nil },
                set: { if !$0 { store.actionErrorMessage = nil } }
            )
        ) {
            Button("知道了", role: .cancel) { store.actionErrorMessage = nil }
        } message: {
            Text(store.actionErrorMessage ?? "请检查当前局面。")
        }
    }
}

extension View {
    func gameActionErrorAlert(_ store: GameStore) -> some View {
        modifier(GameActionErrorAlertModifier(store: store))
    }
}
