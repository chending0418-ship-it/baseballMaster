import SwiftUI
import UIKit

struct RecordingWorkspaceActiveKey: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

struct ScorekeepingView: View {
    @State private var showPostgameUpload = false
    @State private var pendingPostgameGameID: UUID?
    @State private var recordingPresentationActive = false
    @State private var scorekeepingVisible = false
    @State private var correctionAfterDismissal = false

    var showsGameHomeButton = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @EnvironmentObject private var store: GameStore
    @State private var showPlayFlowSheet = false
    @State private var dockedPlayFlow = false
    @State private var workbenchAvailable = false
    @State private var showRunnerEventSheet = false
    @State private var showSpecialEventSheet = false
    @State private var showCorrectionSheet = false
    @State private var showSubstitutionSheet = false
    @State private var confirmOpeningLineups = false
    @State private var illegalPlay = false
    @State private var confirmTimeEnd = false
    @State private var pendingOpeningPitch: PitchAction?
    @State private var pendingOpeningPlay = false
    @State private var showEndReasonSheet = false
    @State private var confirmEndHalf = false
    @State private var showLiveBroadcast = false
    @State private var showRuleNotices = false
    @State private var showHistoryCorrection = false
    @State private var showBatterPerformance = false
    @State private var situationAmendment: BattedBallEventSelection?

    var body: some View {
        GeometryReader { proxy in
            let wide = proxy.size.width >= 740
            let showsField = proxy.size.width >= 390 && proxy.size.height >= 620
            Group {
            if wide || (dockedPlayFlow && showPlayFlowSheet) {
                ScrollViewReader { scroll in
                ScrollView {
                    VStack(spacing: 10) {
                        if wide {
                            HStack(spacing: 12) {
                                compactScoreHeader(height: 75)
                                    .frame(width: min(340, (proxy.size.width - 24) * 0.4))
                                    .disabled(isDockedRecording)
                                WorkbenchLineScore(game: store.game, compact: true)
                            }
                        } else {
                        compactScoreHeader().disabled(isDockedRecording)
                        WorkbenchLineScore(game: store.game)
                        }
                        let columns = wide ? AnyLayout(HStackLayout(alignment: .top, spacing: 16))
                            : AnyLayout(VStackLayout(spacing: 14))
                        columns {
                            VStack(spacing: 14) {
                                // Keep the editor in the same subtree across a fold. On a
                                // narrow display, hide read-only context while the task is active.
                                if wide || !isDockedRecording {
                                fieldCard(showsField: true, fieldHeight: min(320, max(240, proxy.size.height - 170)))
                                    .disabled(isDockedRecording)
                                workbenchMatchup
                                workbenchLineups
                                }
                            }
                            .frame(maxWidth: .infinity)
                            VStack(spacing: 14) {
                                wideRecordingPanel
                                    .id("recording-workbench-panel")
                                workbenchRecentLog
                                workbenchRecentAppearances
                            }
                            .frame(width: wide ? min(460, (min(1280, proxy.size.width - 32) - 16) * 0.46) : nil)
                        }
                    }
                    .frame(maxWidth: 1280)
                    .padding(12)
                    .frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("scorekeeping-wide-layout")
                .onChange(of: wide) { _ in
                    if isDockedRecording { scroll.scrollTo("recording-workbench-panel", anchor: .top) }
                }
                .onChange(of: isDockedRecording) { active in
                    if active { scroll.scrollTo("recording-workbench-panel", anchor: .top) }
                }
                }
            } else if proxy.size.height < 620 || dynamicTypeSize.isAccessibilitySize {
                // A short window must keep every action reachable, including slow-pitch extras.
                ScrollView {
                    compactContent(showsField: false)
                        .frame(minHeight: max(0, proxy.size.height - 8), alignment: .top)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 8)
                }
                .accessibilityIdentifier("scorekeeping-compact-scroll-layout")
            } else {
                compactContent(showsField: showsField)
                    .frame(width: max(0, proxy.size.width - 24),
                           height: max(0, proxy.size.height - 8), alignment: .top)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
            }
            .onAppear { workbenchAvailable = wide }
            .onChange(of: wide) { workbenchAvailable = $0 }
        }
        .bmScreenBackground()
        .alert("操作未保存", isPresented: Binding(get: { store.storageErrorMessage != nil }, set: { if !$0 { store.storageErrorMessage = nil } })) {
            Button("知道了", role: .cancel) { store.storageErrorMessage = nil }
        } message: { Text(store.storageErrorMessage ?? "") }
        .navigationTitle("现场记分")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(showsGameHomeButton || isDockedRecording)
        .toolbar(.hidden, for: .tabBar)
        .confirmationDialog("结束当前半局？", isPresented: $confirmEndHalf, titleVisibility: .visible) {
            Button("确认交换攻守") { store.endCurrentHalf() }
            Button("取消", role: .cancel) {}
        }
        .sheet(isPresented: $showRuleNotices, onDismiss: recordingSheetDidDismiss) {
            NavigationStack {
                List(Array(store.ruleNotices().enumerated()), id: \.offset) { _, notice in Text(notice) }
                    .navigationTitle("比赛规则提醒")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showRuleNotices = false } } }
            }.onAppear { recordingPresentationActive = true }
        }
        .onAppear { scorekeepingVisible = true; schedulePostgameUploadIfReady() }
        .onDisappear {
            if !hasRecordingPresentation && !showPostgameUpload { scorekeepingVisible = false }
        }
        .onChange(of: store.game.isFinal) { final in
            if final { queueSavedPostgamePrompt() }
            else { pendingPostgameGameID = nil }
        }
        .onChange(of: store.activeStoredGame?.status) { if $0 == .completed { queueSavedPostgamePrompt() } }
        .onChange(of: confirmTimeEnd) { if !$0 { schedulePostgameUploadIfReady() } }
        .onChange(of: confirmEndHalf) { if !$0 { schedulePostgameUploadIfReady() } }
        .onChange(of: confirmOpeningLineups) { if !$0 { schedulePostgameUploadIfReady() } }
        .onChange(of: store.storageErrorMessage) { if $0 == nil { schedulePostgameUploadIfReady() } }
        .onChange(of: store.actionErrorMessage) { if $0 == nil { schedulePostgameUploadIfReady() } }
        .sheet(isPresented: $showPostgameUpload) { PostgameUploadPrompt() }
        .sheet(isPresented: $showEndReasonSheet, onDismiss: recordingSheetDidDismiss) {
            GameEndReasonSheet().environmentObject(store).onAppear { recordingPresentationActive = true }
        }
        .sheet(isPresented: $showLiveBroadcast, onDismiss: recordingSheetDidDismiss) {
            if store.liveBroadcasts.isEnabled, let id = store.activeStoredGame?.id {
                LiveBroadcastSheet(store: store, gameID: id).onAppear { recordingPresentationActive = true }
            }
        }
        .sheet(isPresented: $showHistoryCorrection, onDismiss: recordingSheetDidDismiss) {
            HistoryCorrectionView().environmentObject(store).onAppear { recordingPresentationActive = true }
        }
        .sheet(isPresented: $showBatterPerformance, onDismiss: recordingSheetDidDismiss) {
            BatterPerformanceSheet(player: store.currentBatter).environmentObject(store).onAppear { recordingPresentationActive = true }
        }
        .sheet(item: $situationAmendment, onDismiss: recordingSheetDidDismiss) { selected in
            SituationAmendmentSheet(store: store, eventID: selected.id).onAppear { recordingPresentationActive = true }
        }
        .toolbar {
            if showsGameHomeButton {
                ToolbarItem(placement: .topBarLeading) {
                    Button { store.returnToGameHome() } label: {
                        Label("比赛首页", systemImage: "chevron.left")
                    }
                    .accessibilityIdentifier("return-game-home")
                    .disabled(isDockedRecording)
                }
            }
            if let match = store.activeStoredGame, let team = store.teamBroadcasts.teamID(for: match) {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        TeamBroadcastSourcesView(coordinator: store.teamBroadcasts, teamID: team, matchID: match.id)
                    } label: { Image(systemName: "rectangle.on.rectangle") }
                    .accessibilityLabel("转播源")
                    .disabled(isDockedRecording)
                }
            }
            if let stored = store.activeStoredGame, let shared = store.sharedMatches.match(localID: stored.id) {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Text(store.sharedMatches.status(stored.id))
                        NavigationLink {
                            SharedMatchDetailView(coordinator: store.sharedMatches, matchID: shared.id)
                        } label: { Label("比赛同步与交接", systemImage: "person.2") }
                            .accessibilityIdentifier("open-match-sync-detail")
                        Button { showHistoryCorrection = true } label: { Label("纠正记录", systemImage: "clock.arrow.circlepath") }
                            .accessibilityIdentifier("open-history-correction")
                    .disabled(isDockedRecording)
                        NavigationLink(destination: GamePosterView(game: stored)) {
                            Label("比赛宣传海报", systemImage: "photo.badge.plus")
                        }.accessibilityIdentifier("open-game-poster")
                    .disabled(isDockedRecording)
                    } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("比赛同步与更多操作")
                    .accessibilityIdentifier("match-sync-controls")
                    .disabled(isDockedRecording)
                }
            } else {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showHistoryCorrection = true } label: { Image(systemName: "clock.arrow.circlepath") }
                    .accessibilityLabel("纠正记录").accessibilityIdentifier("open-history-correction")
                    .disabled(isDockedRecording)
            }
            ToolbarItem(placement: .topBarTrailing) {
                if store.liveBroadcasts.isEnabled, store.activeGameID.map({ !store.sharedMatches.isShared($0) }) ?? true {
                    Button { showLiveBroadcast = true } label: {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                    }
                    .accessibilityLabel("文字直播与分享")
                    .accessibilityIdentifier("open-live-broadcast")
                    .disabled(isDockedRecording)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let stored = store.activeStoredGame {
                    NavigationLink(destination: GamePosterView(game: stored)) {
                        Image(systemName: "photo.badge.plus")
                    }
                    .accessibilityLabel("比赛宣传海报")
                    .accessibilityIdentifier("open-game-poster")
                    .disabled(isDockedRecording)
                }
            }
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(destination: BoxScoreView()) {
                    Text("比赛结果")
                        .font(.system(size: 14, weight: .bold))
                }
                .accessibilityIdentifier("open-box-score")
                    .disabled(isDockedRecording)
            }
        }
        .sheet(isPresented: Binding(get: { showPlayFlowSheet && !dockedPlayFlow },
                                    set: { showPlayFlowSheet = $0 }), onDismiss: recordingSheetDidDismiss) {
            Group {
                if (store.game.rulesSnapshot ?? store.activeRules)?.effectiveRecordingMethod == .situation {
                    SituationPlayFlowSheet(illegalPitch: illegalPlay)
                } else {
                    ScorePlayFlowSheet(illegalPitch: illegalPlay) {
                        showPlayFlowSheet = false
                        presentCorrectionAfterDismissal()
                    }
                }
            }
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showRunnerEventSheet, onDismiss: recordingSheetDidDismiss) {
            RunnerEventFlowSheet()
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSpecialEventSheet, onDismiss: recordingSheetDidDismiss) {
            SpecialEventSheet {
                showSpecialEventSheet = false
                presentCorrectionAfterDismissal()
            }
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showCorrectionSheet, onDismiss: recordingSheetDidDismiss) {
            GameStateCorrectionSheet()
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSubstitutionSheet, onDismiss: recordingSheetDidDismiss) {
            SubstitutionSheet()
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(
            isPresented: Binding(
                get: { store.requiresTiebreakRunnerPlacement },
                set: { _ in }
            ),
            onDismiss: recordingSheetDidDismiss
        ) {
            TiebreakRunnerSheet()
                .environmentObject(store)
                .onAppear { recordingPresentationActive = true }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.hidden)
                .interactiveDismissDisabled()
        }
        .alert("确认时间赛结束？", isPresented: $confirmTimeEnd) {
            Button("继续记录", role: .cancel) {}
            Button("确认结束") { store.finishGame(reason: .timeLimit) }
        } message: { Text("仅按裁判最终决定结束比赛，保留当前比分与实际记录。") }
        .alert("确认双方阵容并开赛？", isPresented: $confirmOpeningLineups) {
            Button("继续设置阵容") { pendingOpeningPitch = nil; pendingOpeningPlay = false; showSubstitutionSheet = true }
            Button("确认开赛") {
                store.startGameClock()
                if let pitch = pendingOpeningPitch { store.recordPitch(pitch) }
                if pendingOpeningPlay { openPlayFlow() }
                pendingOpeningPitch = nil; pendingOpeningPlay = false
            }
            Button("取消", role: .cancel) { pendingOpeningPitch = nil; pendingOpeningPlay = false }
        } message: {
            Text("\(store.game.awayTeam.shortName) 与 \(store.game.homeTeam.shortName)的先发阵容确认后开始计时。开赛后仍可按现有方式调整阵容。")
        }
        .gameActionErrorAlert(store)
        .preference(key: RecordingWorkspaceActiveKey.self, value: isDockedRecording)
    }

    // Kept separate so the wide host can embed the same recording workflow here.
    private var wideRecordingPanel: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 12) {
                workbenchHeading("现场记录", detail: store.game.isFinal ? "本场已结束" : "当前打席 · 第 \(store.currentBattingOrder) 棒")
                if isDockedRecording {
                    SituationPlayFlowSheet(illegalPitch: illegalPlay, embedded: true, onClose: closeDockedPlayFlow)
                        .environmentObject(store)
                        .onAppear { recordingPresentationActive = true }
                } else {
                    recordingControls
                }
            }
        }
    }

    private var isDockedRecording: Bool { dockedPlayFlow && showPlayFlowSheet }

    private func openPlayFlow() {
        dockedPlayFlow = workbenchAvailable
            && (store.game.rulesSnapshot ?? store.activeRules)?.effectiveRecordingMethod == .situation
        showPlayFlowSheet = true
    }

    private func closeDockedPlayFlow() {
        showPlayFlowSheet = false
        dockedPlayFlow = false
        recordingSheetDidDismiss()
    }

    private var workbenchMatchup: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 12) {
                workbenchHeading("本场投打表现", detail: "随现场记录更新")
                HStack(alignment: .top, spacing: 16) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("当前打者").font(.caption).foregroundStyle(BMTheme.secondaryText)
                        Text("#\(store.currentBatter.numberText) \(store.currentBatter.name)").font(.subheadline.bold())
                        let batting = store.battingLine(for: store.currentBatter)
                        Text("\(batting.atBats) 打数 · \(batting.hits) 安打 · \(batting.runsBattedIn) 打点")
                        Text("\(batting.walks) 四坏 · \(batting.strikeouts) 三振")
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Divider()
                    VStack(alignment: .leading, spacing: 7) {
                        Text(store.isCoachPitch ? "P 位守备" : "当前投手").font(.caption).foregroundStyle(BMTheme.secondaryText)
                        Text("#\(store.currentPitcher.numberText) \(store.currentPitcher.name)").font(.subheadline.bold())
                        if store.isCoachPitch {
                            Text("教练投球，投手数据不适用")
                        } else {
                            let pitching = store.pitchingLine(for: store.currentPitcher)
                            Text("\(pitching.pitches) 球 · \(pitching.inningsText) 局 · \(pitching.strikeouts) 三振")
                            Text("\(pitching.hits) 被安打 · \(pitching.walks) 四坏")
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.caption)
                Divider()
                HStack(spacing: 12) {
                    Text("接下来").font(.caption.bold()).foregroundStyle(BMTheme.secondaryText)
                    ForEach(Array(workbenchOnDeck.enumerated()), id: \.offset) { offset, player in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(offset == 0 ? "待打" : "准备").font(.caption2).foregroundStyle(BMTheme.secondaryText)
                            Text("#\(player.numberText) \(player.name)").font(.caption.bold()).lineLimit(1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .foregroundStyle(BMTheme.navy)
        }.accessibilityIdentifier("workbench-matchup")
    }

    private var workbenchOnDeck: [Player] {
        let lineup = store.game.battingOrderPlayers
        guard lineup.count > 1 else { return [] }
        let current = (store.game.isTop ? store.game.awayBatterIndex : store.game.homeBatterIndex) % lineup.count
        return (1...min(2, lineup.count - 1)).map { lineup[(current + $0) % lineup.count] }
    }

    private var workbenchLineups: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    workbenchHeading("场上阵容", detail: "攻守交换后自动更新")
                    Spacer(minLength: 0)
                    Button("调整") { showSubstitutionSheet = true }
                        .font(.caption.bold()).disabled(!store.canEditCurrentMatch || (dockedPlayFlow && showPlayFlowSheet))
                        .accessibilityIdentifier("workbench-adjust-lineup")
                }
                HStack(alignment: .top, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(store.game.battingTeam.shortName) · 进攻").font(.caption.bold()).foregroundStyle(BMTheme.green)
                        ForEach(Array(store.game.battingOrderPlayers.enumerated()), id: \.offset) { index, player in
                            HStack(spacing: 6) {
                                Text("\(index + 1)").monospacedDigit().foregroundStyle(BMTheme.secondaryText).frame(width: 16)
                                Text("#\(player.numberText) \(player.name)").lineLimit(1)
                                if player.id == store.currentBatter.id { Text("打击").font(.caption2.bold()).foregroundStyle(BMTheme.green) }
                            }.font(.caption).frame(minHeight: 23, alignment: .leading)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(store.game.fieldingTeam.shortName) · 守备").font(.caption.bold()).foregroundStyle(BMTheme.green)
                        ForEach(workbenchFielders) { player in
                            HStack(spacing: 6) {
                                Text(player.id == store.currentPitcher.id ? "P" : player.primaryPosition.shortName)
                                    .foregroundStyle(BMTheme.secondaryText).frame(width: 24, alignment: .leading)
                                Text("#\(player.numberText) \(player.name)").lineLimit(1)
                            }.font(.caption).frame(minHeight: 23, alignment: .leading)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }.foregroundStyle(BMTheme.navy)
        }.accessibilityIdentifier("workbench-lineups")
    }

    private var workbenchFielders: [Player] {
        store.game.fieldingPlayerIDs.compactMap { id in store.game.fieldingTeam.players.first { $0.id == id } }
    }

    private var workbenchRecentLog: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                workbenchHeading("最近实况", detail: "最近 6 条 · 包含投球与场上事件")
                if store.game.playLog.isEmpty {
                    Text("尚无现场记录，记球后显示在这里。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText).padding(.vertical, 12)
                } else {
                    ForEach(Array(store.game.playLog.suffix(6).reversed())) { entry in
                        HStack(alignment: .top, spacing: 10) {
                            Text(entry.inningLabel).font(.caption2.bold()).foregroundStyle(BMTheme.green)
                                .frame(width: 28, alignment: .leading)
                            Text(entry.text).font(.caption).fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if entry.isIncomplete { Image(systemName: "exclamationmark.circle").foregroundStyle(BMTheme.orange) }
                        }
                        if entry.id != store.game.playLog.suffix(6).first?.id { Divider() }
                    }
                }
            }.foregroundStyle(BMTheme.navy)
        }.accessibilityIdentifier("workbench-recent-log")
    }

    private var workbenchRecentAppearances: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    workbenchHeading("最近打席", detail: "最近 3 个已完成打席")
                    Spacer(minLength: 0)
                    NavigationLink("全部", destination: BoxScoreView()).font(.caption.bold())
                        .disabled(isDockedRecording)
                }
                let appearances = Array(store.plateAppearanceRecords().filter(\.isComplete).suffix(3).reversed())
                if appearances.isEmpty {
                    Text("尚无已完成打席。当前打席确认后显示结果。")
                        .font(.caption).foregroundStyle(BMTheme.secondaryText).padding(.vertical, 12)
                } else {
                    ForEach(appearances) { appearance in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(appearance.inningLabel) · #\(appearance.batter.numberText) \(appearance.batter.name)")
                                .font(.caption.bold())
                            Text(appearance.resultText).font(.caption).foregroundStyle(BMTheme.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if appearance.id != appearances.last?.id { Divider() }
                    }
                }
            }.foregroundStyle(BMTheme.navy)
        }.accessibilityIdentifier("workbench-recent-appearances")
    }

    private func workbenchHeading(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline.bold()).foregroundStyle(BMTheme.navy)
            Text(detail).font(.caption2).foregroundStyle(BMTheme.secondaryText)
        }
    }

    private func compactContent(showsField: Bool) -> some View {
        VStack(spacing: 7) {
            compactScoreHeader()
            fieldCard(showsField: showsField)
                .frame(maxHeight: showsField ? .infinity : nil)
            if !showsField { Spacer(minLength: 0) }
            recordingControls
        }
    }

    private func fieldCard(showsField: Bool, fieldHeight: CGFloat? = nil) -> some View {
        VStack(spacing: 0) {
            if showsField {
                BaseballDiamondView(game: store.game, fixedHeight: fieldHeight)
                Divider().overlay(BMTheme.line)
            }
            matchupBar
            if !showsField { compactBaseSummary }
        }
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: BMTheme.cardRadius, style: .continuous)
                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
        }
    }

    private var recordingControls: some View {
        VStack(spacing: 7) {
            if store.isCoachPitch {
                Text("教练投手 · 本打席 \(store.game.plateAppearancePitchCount ?? 0) / \(store.coachPitchLimit) 球")
                    .font(.caption.bold()).accessibilityIdentifier("coach-pitch-count")
            }
            if store.game.isFinal {
                finalGameCard
            } else if let decision = store.game.pendingDecision {
                BMCard {
                    VStack(spacing: 12) {
                        Text(decision.title).font(.headline)
                        Button(decision == .runLimit ? "交换攻守" : "结束比赛") { store.resolveGameDecision(finish: true) }
                            .buttonStyle(PrimaryButtonStyle())
                        Button(decision == .runLimit ? "继续本半局（不再加分）" : "继续比赛") { store.resolveGameDecision(finish: false) }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }.accessibilityIdentifier("pending-game-decision")
            } else {
                if store.canSwapOpeningSides {
                    Button { store.swapOpeningSides() } label: {
                        Label("互换先攻／后攻", systemImage: "arrow.left.arrow.right")
                            .font(.caption.bold()).frame(maxWidth: .infinity, minHeight: 32)
                    }.tint(BMTheme.green).accessibilityIdentifier("swap-opening-sides")
                }
                pitchControls.disabled(!store.canEditCurrentMatch)

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

                if store.isSlowPitch {
                    Menu {
                        Button("未挥棒，裁判判一坏球") { store.recordIllegalPitch(.ball, wasThrown: true) }
                        Button("裁判取消 Illegal，按普通坏球记录") { store.recordIllegalPitch(.ball, wasThrown: true, penaltyApplied: false) }
                        Button("挥空，按好球处理") { store.recordIllegalPitch(.swingingStrike, wasThrown: true) }
                        Button("界外，按本场界外规则处理") { store.recordIllegalPitch(.foul, wasThrown: true) }
                        Button("击球，按最终结果录入") { illegalPlay = true; openPlayFlow() }
                        Button("未实际投出的罚球，记一坏") { store.recordIllegalPitch(.ball, wasThrown: false) }
                        Button("已投出，裁判判 No Pitch") { store.recordIllegalPitch(nil, wasThrown: true) }
                        Button("未投出，裁判判 No Pitch") { store.recordIllegalPitch(nil, wasThrown: false) }
                    } label: { Label("Illegal／非法投球", systemImage: "exclamationmark.circle") }
                    .buttonStyle(ScoreActionButtonStyle(color: BMTheme.orange))
                    .accessibilityIdentifier("record-illegal-pitch")
                    .disabled(store.isPregame)
                    if store.game.strikes >= 2 && store.activeRules?.twoStrikeFoulPolicy == .oneExtraFoul {
                        Text(store.game.extraFoulUsed == true ? "额外界外机会已用，下次界外出局" : "两好后仍允许一次界外")
                            .font(.caption).foregroundStyle(BMTheme.secondaryText)
                    }
                    TimelineView(.periodic(from: Date(), by: 1)) { context in
                        if store.hasStartedGameClock && store.activeRules?.isTimeGame == true && (store.remainingGameTime(at: context.date) ?? 1) <= 0 {
                            Button("时间已到 · 按裁判决定结束比赛") { confirmTimeEnd = true }
                                .buttonStyle(PrimaryButtonStyle())
                        }
                    }
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
                        Label("阵容", systemImage: "person.3.fill")
                    }
                    .buttonStyle(ScoreActionButtonStyle(color: BMTheme.green, compact: true))
                    .accessibilityIdentifier("open-substitutions")
                }
                if (store.game.rulesSnapshot ?? store.activeRules)?.effectiveRecordingMethod == .situation,
                   let latest = store.game.scoringEvents?.last(where: { $0.category == .battedBall }) {
                    HStack(spacing: 8) {
                        Text(latest.title).font(.system(size: 10)).lineLimit(2).foregroundStyle(BMTheme.secondaryText)
                        Spacer(minLength: 0)
                        Button("补充这球") { situationAmendment = BattedBallEventSelection(id: latest.id) }
                            .font(.caption.bold()).fixedSize().disabled(!store.canEditCurrentMatch)
                            .accessibilityIdentifier("amend-situation-play")
                    }.padding(.horizontal, 8)
                }
                HStack(spacing: 10) {
                    Button("结束半局") { confirmEndHalf = true }
                        .disabled(!store.canRecordAction)
                    Spacer(minLength: 0)
                    if store.game.strikes == 2 && store.activeRules?.supportsBunting != false {
                        Button("触击界外出局") { store.recordFoulBuntStrikeout() }
                            .foregroundStyle(BMTheme.orange)
                            .frame(minHeight: 40)
                            .accessibilityIdentifier("foul-bunt-strikeout")
                        Spacer(minLength: 0)
                    }
                    Button("结束比赛") { showEndReasonSheet = true }
                }
                .font(.system(size: 14, weight: .semibold))
                .padding(.horizontal, 14)
                .frame(height: 40)
            }
        }
    }

    private func compactScoreHeader(height: CGFloat = 58) -> some View {
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
                .lineLimit(1)
                .minimumScaleFactor(0.75)
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
        .frame(height: height)
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
                confirmOpeningLineups = true
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

    private func ruleNoticeButton(_ notices: [String], summary: String, iconOnly: Bool) -> some View {
        Button { showRuleNotices = true } label: {
            HStack(spacing: 3) {
                Image(systemName: "bell.badge.fill")
                if !iconOnly { Text(summary) }
            }
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(BMTheme.orange)
            .padding(.horizontal, 4)
            .frame(height: 20)
            .background(BMTheme.orangeSoft, in: Capsule())
            .fixedSize()
            .contentShape(Rectangle().inset(by: -10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(notices.joined(separator: " · "))
        .accessibilityValue(summary)
        .accessibilityIdentifier("game-rule-warning")
        .accessibilityHint("点按查看全部比赛规则提醒")
    }

    private var matchupBar: some View {
        HStack(spacing: 0) {
            matchupPlayer(label: store.isCoachPitch ? "P 位守备（教练投球）" : "投手", player: store.currentPitcher, alignment: .leading,
                          pitchCount: store.isCoachPitch ? nil : store.pitchingLine(for: store.currentPitcher).pitches)
            Rectangle()
                .fill(BMTheme.line)
                .frame(width: 1, height: 35)
                .padding(.horizontal, 12)
            matchupPlayer(label: "第 \(store.currentBattingOrder) 棒", player: store.currentBatter, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
    }

    private func matchupPlayer(label: String, player: Player, alignment: HorizontalAlignment, pitchCount: Int? = nil) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            HStack(spacing: 7) {
                Text(label)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(BMTheme.secondaryText)
                if let pitchCount {
                    Text("已投 \(pitchCount) 球")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(BMTheme.green)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(BMTheme.green.opacity(0.12), in: Capsule())
                        .fixedSize()
                }
            }
            if alignment == .leading {
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    if let summary = store.ruleNoticeSummary(at: context.date) {
                        let notices = store.ruleNotices(at: context.date)
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 4) {
                                matchupName(label: label, player: player, alignment: alignment, pitchCount: pitchCount).fixedSize()
                                ruleNoticeButton(notices, summary: summary, iconOnly: false)
                            }
                            HStack(spacing: 4) {
                                matchupName(label: label, player: player, alignment: alignment, pitchCount: pitchCount)
                                ruleNoticeButton(notices, summary: summary, iconOnly: true)
                            }
                        }
                    } else {
                        matchupName(label: label, player: player, alignment: alignment, pitchCount: pitchCount)
                    }
                }
            } else {
                Button { showBatterPerformance = true } label: {
                    matchupName(label: label, player: player, alignment: alignment, pitchCount: pitchCount)
                }.buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private func matchupName(label: String, player: Player, alignment: HorizontalAlignment, pitchCount: Int?) -> some View {
        Text("#\(player.numberText) \(player.name)")
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(BMTheme.navy)
            .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label)，\(player.numberText)号，\(player.name)" + (pitchCount.map { "，本场已投 \($0) 球" } ?? ""))
        .accessibilityIdentifier(alignment == .leading ? "current-pitcher" : "current-batter")
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
                illegalPlay = false
                if store.isPregame { pendingOpeningPlay = true; confirmOpeningLineups = true }
                else { openPlayFlow() }
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

    private var compactBaseSummary: some View {
        HStack(spacing: 8) {
            ForEach(Base.allCases, id: \.self) { base in
                let runner = store.game.baseRunners[base]
                VStack(spacing: 3) {
                    Text(base.title).font(.caption2)
                    Text(runner.map { "#\($0.numberText) \($0.name)" } ?? "无人")
                        .font(.caption.bold()).lineLimit(1).minimumScaleFactor(0.65)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 8)
                .foregroundStyle(runner == nil ? BMTheme.secondaryText : Color.black)
                .background(runner == nil ? BMTheme.surface : Color(red: 0.98, green: 0.74, blue: 0.16))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityIdentifier("base-\(base.rawValue)-\(runner == nil ? "empty" : "occupied")")
            }
        }.padding(.horizontal, 12).padding(.bottom, 10)
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
                    Button {
                        if store.activeStoredGame == nil { dismiss() }
                        else { store.returnToGameHome() }
                    } label: {
                        Label(store.activeStoredGame == nil ? "返回练习" : "返回首页", systemImage: "house")
                    }
                    .buttonStyle(ScoreActionButtonStyle(color: BMTheme.brandGreen, filled: true, compact: true))
                    .accessibilityIdentifier("return-final-game-home")
                }
            }
        }
    }

    private func pitchButton(_ action: PitchAction, color: Color) -> some View {
        Button {
            if store.isPregame { pendingOpeningPitch = action; confirmOpeningLineups = true }
            else { store.recordPitch(action) }
            haptic(.light)
        } label: {
            Text(action.title)
        }
        .buttonStyle(ScoreActionButtonStyle(color: color, filled: true))
        .accessibilityIdentifier("pitch-\(action.rawValue)")
    }

    private func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    private func presentCorrectionAfterDismissal() {
        correctionAfterDismissal = true
    }

    private func recordingSheetDidDismiss() {
        recordingPresentationActive = false
        if correctionAfterDismissal {
            correctionAfterDismissal = false
            showCorrectionSheet = true
            return
        }
        schedulePostgameUploadIfReady()
    }

    private var hasRecordingPresentation: Bool {
        recordingPresentationActive || showRuleNotices || showEndReasonSheet || showLiveBroadcast
            || showHistoryCorrection || showBatterPerformance || situationAmendment != nil
            || showPlayFlowSheet || showRunnerEventSheet || showSpecialEventSheet || showCorrectionSheet
            || showSubstitutionSheet || store.requiresTiebreakRunnerPlacement
            || confirmTimeEnd || confirmEndHalf || confirmOpeningLineups || correctionAfterDismissal
    }

    private func queueSavedPostgamePrompt() {
        guard scorekeepingVisible, let saved = store.activeStoredGame, saved.status == .completed, saved.state.isFinal,
              saved.sharedGameID == nil, store.storageErrorMessage == nil else { return }
        pendingPostgameGameID = saved.id
        schedulePostgameUploadIfReady()
    }

    private func schedulePostgameUploadIfReady() {
        // Run after SwiftUI consumes the final-state / alert bindings. Sheets call this only after onDismiss.
        DispatchQueue.main.async {
            guard scorekeepingVisible, let id = pendingPostgameGameID, !showPostgameUpload, !hasRecordingPresentation,
                  !store.requiresDataRecovery, store.storageErrorMessage == nil, store.actionErrorMessage == nil,
                  let saved = store.activeStoredGame, saved.id == id, saved.sharedGameID == nil,
                  saved.status == .completed, saved.state.isFinal, store.game.isFinal else { return }
            pendingPostgameGameID = nil
            guard store.claimAutomaticPostgamePrompt(gameID: id) else { return }
            showPostgameUpload = true
        }
    }
}

private enum PlayFlowStage {
    case arrival
    case cause(BatterArrival)
    case defense(ObservationCause, BatterArrival)
    case runners(ObservationCause, BatterArrival, DefensivePlay?)
    case timing(ObservationCause, BatterArrival, DefensivePlay?, [RunnerDecision])
    case terminalOuts(ObservationCause, BatterArrival, DefensivePlay?, Int)
}

struct ScorePlayFlowSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let illegalPitch: Bool
    let onNeedsCorrection: () -> Void
    @State private var stage: PlayFlowStage = .arrival

    init(illegalPitch: Bool = false, onNeedsCorrection: @escaping () -> Void = {}) {
        self.illegalPitch = illegalPitch
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
                    allowsBunting: store.activeRules?.supportsBunting != false,
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
                        if recordOutcome(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) {
                            dismiss()
                        }
                    }
                }

            case .terminalOuts(let cause, _, let defensivePlay, let count):
                HalfEndingOutsSheet(runners: store.suggestedRunnerEventDecisions(for: .forceOut), requiredOuts: count) { ids in
                    if let decisions = store.halfEndingDecisions(for: cause.outcome, runnerOutIDs: ids),
                       recordOutcome(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) {
                        dismiss()
                    }
                }

            case .timing(let cause, _, let defensivePlay, let decisions):
                ThirdOutTimingSheet(title: cause.outcome.rawValue) { runCounts in
                    if recordOutcome(
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
        if arrival == .out, let count = store.halfEndingRunnerOutCount(for: cause.outcome) {
            if count == 0, let decisions = store.halfEndingDecisions(for: cause.outcome) {
                if recordOutcome(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) { dismiss() }
            } else {
                stage = .terminalOuts(cause, arrival, defensivePlay, count)
            }
            return
        }
        let needsRunnerConfirmation = store.hasRunners
            || [.fieldersChoice, .runnerTagOut, .doublePlay, .triplePlay, .infieldFly, .sacrificeBunt, .sacrificeFly].contains(cause.outcome)
        if needsRunnerConfirmation {
            stage = .runners(cause, arrival, defensivePlay)
        } else {
            let decisions = store.suggestedRunnerDecisions(
                for: cause.outcome,
                batterDestination: arrival.destination
            )
            if recordOutcome(cause.outcome, defensivePlay: defensivePlay, decisions: decisions) {
                dismiss()
            }
        }
    }
    private func recordOutcome(_ outcome: PlayOutcome, defensivePlay: DefensivePlay? = nil,
                               decisions: [RunnerDecision]? = nil, timingRunCounts: Bool? = nil) -> Bool {
        if illegalPitch {
            return store.recordIllegalPitch(nil, wasThrown: true, outcome: outcome, defensivePlay: defensivePlay,
                                            decisions: decisions, timingRunCounts: timingRunCounts)
        }
        return store.applyPlay(outcome, defensivePlay: defensivePlay, decisions: decisions, timingRunCounts: timingRunCounts)
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
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    let outcome: PlayOutcome
    var onBack: (() -> Void)? = nil
    let onSelect: (DefensivePlay) -> Void
    @State private var showCustomRoute = false

    private var plays: [DefensivePlay] {
        switch outcome {
        case .groundOut, .fieldersChoice, .runnerTagOut:
            return Array(DefensivePlay.quickPlays.prefix(3)) + [DefensivePlay.quickPlays.last!]
        case .flyOut:
            return Array(DefensivePlay.quickPlays[3...5]) + [DefensivePlay.quickPlays.last!]
        case .lineOut, .foulFlyOut:
            return store.availableFieldPositions.map(DefensivePlay.caught(by:)) + [DefensivePlay.quickPlays.last!]
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
                            ForEach(store.availableFieldPositions) { position in
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
                    Button("返回") { if let onBack { onBack() } else { dismiss() } }
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
    @EnvironmentObject private var store: GameStore
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
                        ForEach(store.availableFieldPositions) { position in
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

                    if decisions.filter({ $0.destination == .score }).count > 1 {
                        BMCard {
                            VStack(alignment: .leading) {
                                Text("按实际回本垒顺序排列；设有上限时只计有效得分").font(.footnote)
                                ForEach(decisions.filter { $0.destination == .score }) { scorer in
                                    HStack {
                                        Text(scorer.player.compactName)
                                        Spacer()
                                        Button("提前") {
                                            let indices = decisions.indices.filter { decisions[$0].destination == .score }
                                            if let current = decisions.firstIndex(where: { $0.id == scorer.id }),
                                               let prior = indices.last(where: { $0 < current }) { decisions.swapAt(current, prior) }
                                        }.buttonStyle(.borderless)
                                    }
                                }
                            }
                        }
                    }
                    ForEach($decisions) { $decision in
                        BMCard {
                            VStack(alignment: .leading, spacing: 13) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(decision.origin.title)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundStyle(BMTheme.secondaryText)
                                        Text("#\(decision.player.numberText) \(decision.player.name)")
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

private struct LineupSide: Identifiable {
    let isHome: Bool
    var id: Bool { isHome }
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
    @State private var lineupSide: LineupSide?
    @State private var savedMessage: String?

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
                    Button(stage == .menu ? "返回比赛" : "返回") {
                        if stage == .menu { dismiss() } else { stage = .menu }
                    }
                }
            }
        }
        .sheet(item: $lineupSide) { side in
            LiveLineupEditor(draft: store.lineupDraft(forHomeTeam: side.isHome),
                             onCancel: { lineupSide = nil },
                             onSaved: { savedMessage = "阵容已保存，可继续调整" })
                .environmentObject(store)
        }
        .pitcherChangeConfirmation(store, onConfirmed: adjustmentSaved)
        .gameActionErrorAlert(store)
    }

    @ViewBuilder
    private var content: some View {
        switch stage {
        case .menu:
            VStack(spacing: 10) {
                if let savedMessage { Label(savedMessage, systemImage: "checkmark.circle.fill")
                    .font(.footnote).foregroundStyle(BMTheme.green) }
                HStack(spacing: 10) {
                    lineupButton(isHome: false)
                    lineupButton(isHome: true)
                }
                Text("双方均可调整棒次、守位、姓名和号码")
                    .font(.caption).foregroundStyle(BMTheme.secondaryText)
                if store.isPregame {
                    Text("保存双方赛前阵容，返回后点 Play Ball 确认开赛。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                } else {
                menuButton(store.isCoachPitch ? "替换 P 位守备" : "换投手", detail: store.isCoachPitch ? "选择新的 P 位守备球员" : "选择新的投手", icon: "arrow.triangle.2.circlepath", id: "substitution-pitcher") { stage = .pitcher }
                menuButton("代打", detail: "替换当前打者", icon: "figure.baseball", id: "substitution-pinch-hitter") { stage = .pinchHitter }
                menuButton("代跑", detail: "选择垒位和替换球员", icon: "figure.run", id: "substitution-pinch-runner") { stage = .pinchRunnerBase }
                menuButton("守备换人", detail: "替换任意一名场上守备员", icon: "person.crop.circle.badge.plus", id: "substitution-fielder") { stage = .fielderReplacementTarget }
                menuButton("双重换人", detail: "一次确认两组球员、棒次和守位", icon: "arrow.triangle.swap", id: "substitution-double-switch") { stage = .doubleSwitch }
                menuButton("调整守位", detail: "交换球员的防守位置", icon: "square.grid.3x3.fill", id: "substitution-position") { stage = .fielder }
                }
            }

        case .pitcher:
            playerList(
                store.availablePitchers
            ) { player in
                if store.requestLineupChange(.pitcher(player)) { adjustmentSaved() }
            }

        case .pinchHitter:
            playerList(store.battingBenchPlayers) { player in
                store.replaceCurrentBatter(with: player)
                adjustmentSaved()
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
                                    Text("#\(runner.numberText) \(runner.name)")
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
                adjustmentSaved()
            }

        case .fielderReplacementTarget:
            playerList(store.activeFielders) { player in
                stage = .fielderReplacementPlayer(player)
            }

        case .fielderReplacementPlayer(let previous):
            playerList(store.fieldingBenchPlayers) { replacement in
                if store.requestLineupChange(.fielder(previous, replacement)) { adjustmentSaved() }
            }

        case .doubleSwitch:
            DoubleSwitchEditor(onComplete: { adjustmentSaved() })
                .environmentObject(store)

        case .fielder:
            playerList(store.activeFielders) { player in
                stage = .position(player)
            }

        case .position(let player):
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(store.availableFieldPositions) { position in
                    Button {
                        if store.requestLineupChange(.position(player, position)) { adjustmentSaved() }
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

    private func adjustmentSaved() {
        guard store.actionErrorMessage == nil else { return }
        savedMessage = "调整已保存，可继续调整或返回比赛"
        stage = .menu
    }

    private var navigationTitle: String {
        switch stage {
        case .menu: "阵容与换人"
        case .pitcher: store.isCoachPitch ? "选择 P 位守备球员" : "选择新投手"
        case .pinchHitter: "选择代打"
        case .pinchRunnerBase: "选择代跑垒位"
        case .pinchRunnerPlayer: "选择代跑球员"
        case .fielderReplacementTarget: "选择退场守备员"
        case .fielderReplacementPlayer: "选择替补守备员"
        case .doubleSwitch: "双重换人"
        case .fielder: "选择守备球员"
        case .position(let player): "#\(player.numberText) 调整守位"
        }
    }

    private func lineupButton(isHome: Bool) -> some View {
        let team = isHome ? store.game.homeTeam : store.game.awayTeam
        let role = store.activeStoredGame?.isObservation == true ? (isHome ? "主队" : "客队")
            : (store.activeStoredGame?.isHome == isHome ? "本队" : "对手")
        return Button { lineupSide = LineupSide(isHome: isHome) } label: {
            VStack(spacing: 4) {
                Text("\(role)阵容").font(.headline)
                Text(team.shortName).font(.caption).lineLimit(1)
            }.frame(maxWidth: .infinity, minHeight: 64)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier(isHome ? "edit-home-lineup" : "edit-away-lineup")
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
                    message: "当前球员均已在场；换下的球员可再次上场。"
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
                    Text("#\(player.numberText) \(player.name)").tag(Optional(player.id))
                }
            }
            Picker("替补", selection: inID) {
                Text("请选择").tag(UUID?.none)
                ForEach(store.fieldingBenchPlayers) { player in
                    Text("#\(player.numberText) \(player.name)").tag(Optional(player.id))
                }
            }
            Picker("新守位", selection: position) {
                ForEach(store.availableFieldPositions) { fieldPosition in
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
        if store.requestLineupChange(.doubleSwitch(firstOut, firstIn, firstPosition,
                                                   secondOut, secondIn, secondPosition)) {
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

    func pitcherChangeConfirmation(_ store: GameStore, isLineupEditor: Bool = false,
                                   onConfirmed: @escaping () -> Void) -> some View {
        modifier(PitcherChangeConfirmationModifier(store: store, isLineupEditor: isLineupEditor,
                                                   onConfirmed: onConfirmed))
    }
}

private struct PitcherChangeConfirmationModifier: ViewModifier {
    @ObservedObject var store: GameStore
    let isLineupEditor: Bool
    let onConfirmed: () -> Void

    private var pending: GameStore.PendingPitcherChange? {
        guard let value = store.pendingPitcherChange,
              value.change.isLineupEditor == isLineupEditor else { return nil }
        return value
    }

    func body(content: Content) -> some View {
        content
            .interactiveDismissDisabled(pending != nil)
            .alert("确认当前投手", isPresented: Binding(get: { pending != nil }, set: { _ in }),
                   presenting: pending) { value in
                Button("确认，是这位投手") {
                    if store.confirmPitcherChange(id: value.id) { onConfirmed() }
                }.accessibilityIdentifier("confirm-current-pitcher")
                Button("选错了，重新选择", role: .cancel) { store.cancelPitcherChange() }
                    .accessibilityIdentifier("cancel-pitcher-change")
            } message: { value in
                Text("\(value.teamName)\n原投手：\(value.previous.lineupSelectionText)\n新投手：\(value.candidate.lineupSelectionText)\n\n请核对场上实际投手。确认后才能继续记录；取消不改变记录。"
                     + (store.activeRules?.pitcherInningsLimit.map { "\n本场限制 \($0) 个登板局。明确确认登板即占用该局额度；换局自动沿用投手，实际投球后才计新局。同一局不重复计算，统计 IP 另按实际出局数累计。" } ?? ""))
            }
            .onDisappear { if pending != nil { store.cancelPitcherChange() } }
    }
}
