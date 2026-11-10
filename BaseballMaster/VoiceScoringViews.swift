import AVFoundation
import SwiftUI

struct VoiceScoringSettings: View {
    @ObservedObject private var access = VoiceScoringAccess.shared
    @State private var code = ""
    @State private var issue: String?

    var body: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 12) {
                if access.active {
                    Label("语音记分已开通", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(BMTheme.green)
                    Text("在现场记分或练习时点「语音输入」，一次记录一个局面。")
                        .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                } else {
                    Text("输入管理员发给你的开启码。开通后，基础版和 Pro 都可在现场记分或练习时选用语音。")
                        .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                    SecureField("语音开启码", text: $code)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("voice-activation-code")
                        .disabled(access.redeeming)
                        .onSubmit(redeem)
                    Button(access.redeeming ? "正在开通…" : "开通语音记分", action: redeem)
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(access.redeeming || code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("redeem-voice-code")
                }
                if let issue { Text(issue).font(.footnote).foregroundStyle(BMTheme.red) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task { await access.refresh() }
    }

    private func redeem() {
        guard !access.redeeming, !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        issue = nil
        Task {
            do {
                try await access.redeem(code)
                if access.active { code = "" }
            } catch { issue = error.localizedDescription }
        }
    }
}

struct VoiceScoringSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var store: GameStore
    @ObservedObject private var access = VoiceScoringAccess.shared
    @StateObject private var recorder = VoiceScoringRecorder()
    let context: VoicePlayContext
    let pro: Bool

    @State private var text = ""
    @State private var reviewResult: VoicePlayReview?
    @State private var showTranscript = false
    @State private var recordingAuthorized = false
    @State private var generateWhenFinalized = false
    @State private var hasRequestedAnalysis = false
    @State private var proDraft: SituationExistingPlay?
    @State private var analysisTask: Task<Void, Never>?
    @State private var issue: String?
    @State private var submitted = false

    init(context: VoicePlayContext, pro: Bool) {
        self.context = context
        self.pro = pro
    }

    #if DEBUG
    init(context: VoicePlayContext, pro: Bool, previewTranscript: String, previewReview: VoicePlayReview) {
        self.init(context: context, pro: pro)
        _text = State(initialValue: previewTranscript)
        _reviewResult = State(initialValue: previewReview)
    }
    #endif

    var body: some View {
        Group {
            if let proDraft {
                SituationPlayFlowSheet(illegalPitch: false, initial: proDraft, onSave: savePro,
                                       onClose: returnToManual, voiceReview: true)
            } else {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("第 \(context.state.inning) 局\(context.state.isTop ? "上" : "下") · \(context.state.outs) 出局 · \(context.state.currentBatter.compactName)")
                                .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                            if let reviewResult { review(reviewResult) }
                            if let issue {
                                Text(issue).font(.footnote).foregroundStyle(BMTheme.red).accessibilityIdentifier("voice-issue")
                            }
                            if analysisTask != nil { analysisProgress }
                            if recorder.phase != .idle || (text.isEmpty && !showTranscript && reviewResult == nil && analysisTask == nil) {
                                recordControl
                            }
                            if recorder.phase == .idle, showTranscript || !text.isEmpty {
                                transcriptControl
                            }
                        }
                        .frame(maxWidth: 560)
                        .padding(16)
                        .frame(maxWidth: .infinity)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .safeAreaInset(edge: .bottom, spacing: 0) { confirmationBar }
                    .bmScreenBackground()
                    .navigationTitle("语音输入")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("返回手动记分", action: returnToManual)
                                .accessibilityIdentifier("voice-return-manual")
                        }
                    }
                }
            }
        }
        .gameActionErrorAlert(store)
        .onChange(of: recorder.transcript) { text = $0 }
        .onChange(of: recorder.finalized) { _ in finishRecordingIfReady() }
        .onChange(of: recorder.phase) { _ in finishRecordingIfReady() }
        .onReceive(recorder.$error) { error in
            guard let error else { return }
            if let token = access.token { access.report(error, for: token) }
            generateWhenFinalized = false
            if !recorder.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                showTranscript = true
            }
            issue = error.localizedDescription
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
            if notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                recorder.interrupt()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { notification in
            if notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                recorder.interrupt()
            }
        }
        .onChange(of: scenePhase) {
            if $0 == .background {
                generateWhenFinalized = false
                recorder.interrupt()
            }
        }
        .onDisappear { recorder.cancel(); cancelAnalysis() }
    }

    @ViewBuilder private var recordControl: some View {
        switch recorder.phase {
        case .idle:
            VStack(alignment: .leading, spacing: 10) {
                Text("录音直传腾讯云做语音识别，连接时我们的服务接收必要比赛局面。选择「结束并生成建议」或「解析局面」后，转写和必要局面（可能含球员姓名、背号）会经本服务发给腾讯云混元 AI。")
                    .font(.footnote)
                    .foregroundStyle(BMTheme.secondaryText)
                    .accessibilityIdentifier("voice-recording-disclosure")
                Button(action: beginRecording) {
                    Label(text.isEmpty ? "同意并开始录音" : "同意并重新录音", systemImage: "mic")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(analysisTask != nil || !access.active)
                .accessibilityIdentifier("start-voice-recording")
            }
        case .connecting, .finishing:
            HStack {
                ProgressView()
                Text(recorder.phase == .connecting ? "正在连接，请稍候再说" : "正在接收最终转写…")
                Spacer()
                Button("停止等待") {
                    generateWhenFinalized = false
                    recorder.cancel()
                    showTranscript = !text.isEmpty
                }
            }
        case .recording:
            VStack(alignment: .leading, spacing: 10) {
                Text("正在录音，现在可以说。")
                    .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                Button { endRecording(generateSuggestion: true) } label: {
                    Label("结束并生成建议", systemImage: "stop.circle")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("stop-voice-recording")
                Button("结束，先核对转写") { endRecording(generateSuggestion: false) }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("stop-voice-review-transcript")
                if !text.isEmpty {
                    Text(text).font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
            }
        }
    }

    private func beginRecording() {
        guard recorder.phase == .idle, analysisTask == nil,
              access.active, let token = access.token, context.matches(store) else {
            issue = "局面或语音权限已变化，请返回记分页。"
            return
        }
        issue = nil
        reviewResult = nil
        showTranscript = false
        hasRequestedAnalysis = false
        generateWhenFinalized = false
        recordingAuthorized = true
        text = ""
        recorder.start(context: context.payload, token: token, service: access.service)
    }

    private func endRecording(generateSuggestion: Bool) {
        generateWhenFinalized = generateSuggestion
        showTranscript = !generateSuggestion
        recorder.stop()
    }

    private func finishRecordingIfReady() {
        guard generateWhenFinalized, recorder.phase == .idle else { return }
        generateWhenFinalized = false
        guard recorder.finalized, scenePhase == .active else {
            showTranscript = !recorder.transcript.isEmpty
            return
        }
        requestAnalysis(recorder.transcript)
    }

    private var analysisProgress: some View {
        HStack {
            ProgressView()
            Text("正在解析本球…")
            Spacer()
            Button("停止等待") {
                cancelAnalysis()
                showTranscript = true
            }
        }
    }

    private var transcriptControl: some View {
        DisclosureGroup("查看或修改转写", isExpanded: $showTranscript) {
            VStack(alignment: .leading, spacing: 12) {
                TextEditor(text: Binding(get: { text }, set: {
                    text = $0
                    if case .ready? = reviewResult { reviewResult = nil }
                }))
                .frame(minHeight: 86)
                .padding(6)
                .background(BMTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .disabled(analysisTask != nil)
                .accessibilityLabel("语音转写")
                .accessibilityIdentifier("voice-transcript")
                if analysisTask == nil {
                    Button(hasRequestedAnalysis ? "重新解析" : "解析局面") { requestAnalysis(text) }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(!recordingAuthorized || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("analyze-voice-play")
                    recordControl
                }
            }
            .padding(.top, 8)
        }
        .accessibilityIdentifier("voice-edit-transcript")
        .tint(BMTheme.navy)
    }

    private func review(_ result: VoicePlayReview) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                switch result {
                case .ready(let proposal, let suggested):
                    Text(suggested ? "建议记为\(proposal.title)" : proposal.title)
                        .font(.title3.bold())
                        .accessibilityIdentifier("voice-review-result")
                    Text("本球尚未记录").font(.caption).foregroundStyle(BMTheme.secondaryText)
                    if case .hit(let play) = proposal {
                        ForEach(Array(play.decisions.enumerated()), id: \.element.id) { index, decision in
                            runnerRow(index: index, decision: decision, play: play, suggested: suggested)
                        }
                        if pro {
                            Text(play.observation.summary)
                                .font(.subheadline)
                                .accessibilityIdentifier("voice-pro-contact-summary")
                        }
                        if let fielding = play.observation.fielding {
                            Text(fielding.description).font(.footnote).foregroundStyle(BMTheme.secondaryText)
                        }
                        if let runnerIssue = runnerIssue(play.decisions) {
                            Text(runnerIssue).font(.footnote).foregroundStyle(BMTheme.red)
                                .accessibilityIdentifier("voice-runner-issue")
                        } else if pro {
                            if let missing = proIssue(play) {
                                Label(missing, systemImage: "location.circle")
                                    .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                                    .accessibilityIdentifier(play.observation.hasLocation ? "voice-pro-missing-field" : "voice-missing-landing")
                            } else if !store.situationNeedsTimingDecision(play.outcome, decisions: play.decisions) || play.timing != nil {
                                Button("修改球场记录") { proDraft = play }
                                    .font(.subheadline.weight(.semibold))
                                    .frame(minHeight: 44)
                                    .accessibilityIdentifier("voice-edit-pro-contact")
                            }
                        }
                    }
                case .needsDetails(let prompts):
                    Text("本球还需核对").font(.headline)
                    ForEach(prompts, id: \.self) { prompt in
                        Text(prompt).font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                    }
                    Text("可以改转写后重新解析，或返回手动记分。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func runnerRow(index: Int, decision: RunnerDecision, play: SituationExistingPlay, suggested: Bool) -> some View {
        if decision.origin != .batter, play.outcome.isHit, play.outcome != .homeRun {
            Menu {
                ForEach(store.availableDestinations(for: decision).filter { $0 != .out }, id: \.self) { destination in
                    Button {
                        var edited = play
                        edited.decisions[index].destination = destination
                        edited.timing = nil
                        reviewResult = .ready(.hit(edited), suggested: suggested)
                    } label: {
                        if destination == decision.destination {
                            Label(destination.title, systemImage: "checkmark")
                        } else {
                            Text(destination.title)
                        }
                    }
                    .accessibilityIdentifier("voice-destination-\(index)-\(destination.title)")
                }
            } label: {
                runnerLabel(decision, editable: true)
            }
            .accessibilityIdentifier("voice-runner-\(index)")
            .accessibilityLabel("\(decision.origin.title)，\(decision.player.compactName)，\(decision.destination.title)，修改去向")
        } else {
            runnerLabel(decision, editable: false)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("voice-runner-\(index)")
        }
    }

    private func runnerLabel(_ decision: RunnerDecision, editable: Bool) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(decision.origin.title).font(.caption).foregroundStyle(BMTheme.secondaryText)
                Text(decision.player.compactName).font(.subheadline).foregroundStyle(BMTheme.navy)
            }
            Spacer(minLength: 4)
            Text(decision.destination.title).font(.subheadline.bold()).foregroundStyle(editable ? BMTheme.green : BMTheme.navy)
            if editable {
                Image(systemName: "chevron.up.chevron.down").font(.caption).foregroundStyle(BMTheme.green)
            }
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    @ViewBuilder private var confirmationBar: some View {
        if case .ready(let proposal, _)? = reviewResult {
            Button(reviewActionTitle(proposal)) { confirm(proposal) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(submitted || !access.active || !context.matches(store) || proposalHasRunnerIssue(proposal))
                .accessibilityIdentifier("confirm-voice-play")
                .frame(maxWidth: 560)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(BMTheme.background)
        }
    }

    private func proposalHasRunnerIssue(_ proposal: VoicePlayProposal) -> Bool {
        if case .hit(let play) = proposal { return runnerIssue(play.decisions) != nil }
        return false
    }

    private func runnerIssue(_ decisions: [RunnerDecision]) -> String? {
        if let error = store.runnerDecisionValidationError(decisions, includesBatter: true) { return error }
        let ranks = decisions.compactMap { decision -> Int? in
            switch decision.destination {
            case .base(let base): return base.rawValue
            case .hold:
                if case .base(let base) = decision.origin { return base.rawValue }
                return nil
            case .score: return 4
            case .out: return nil
            }
        }
        guard zip(ranks, ranks.dropFirst()).allSatisfy({ $0 < $1 || ($0 == 4 && $1 == 4) }) else {
            return "请核对跑者去向，跑者不能占用同一垒或越过前面的跑者。"
        }
        return nil
    }

    private func proIssue(_ play: SituationExistingPlay) -> String? {
        if !play.observation.hasLocation { return "请补录落点或大致区域。" }
        if play.observation.type == .unknown { return "请补录球型。" }
        if play.observation.fielding == nil { return "请补录守备处理。" }
        return store.situationObservationError(play.observation, outcome: play.outcome, decisions: play.decisions)
    }

    private func reviewActionTitle(_ proposal: VoicePlayProposal) -> String {
        if pro, case .hit(let play) = proposal {
            if proIssue(play) != nil {
                return !play.observation.hasLocation ? "补录落点" : "补录本球"
            }
            if store.situationNeedsTimingDecision(play.outcome, decisions: play.decisions), play.timing == nil {
                return "核对得分先后"
            }
        }
        return "确认记入本球"
    }

    private func requestAnalysis(_ input: String) {
        guard analysisTask == nil, recorder.phase == .idle, recordingAuthorized,
              access.active, let token = access.token, context.matches(store) else {
            issue = "局面或语音权限已变化，请返回记分页。"
            return
        }
        let transcript = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transcript.isEmpty else { return }
        issue = nil
        reviewResult = nil
        hasRequestedAnalysis = true
        showTranscript = false
        analysisTask = Task {
            defer { if !Task.isCancelled { analysisTask = nil } }
            do {
                let result = try await access.service.analyze(transcript, context: context.payload, token: token)
                try Task.checkCancellation()
                guard context.matches(store), access.token == token else {
                    issue = "局面或语音权限已变化，请返回记分页。"
                    showTranscript = true
                    return
                }
                let review = VoicePlayReview(analysis: result, context: context, transcript: transcript,
                                            pro: pro, slowPitch: store.isSlowPitch)
                reviewResult = review
                if case .needsDetails = review { showTranscript = true }
            } catch {
                guard !Task.isCancelled else { return }
                access.report(error, for: token)
                issue = error.localizedDescription
                showTranscript = true
            }
        }
    }

    private func cancelAnalysis() {
        analysisTask?.cancel()
        analysisTask = nil
    }

    private func returnToManual() {
        generateWhenFinalized = false
        recorder.cancel()
        cancelAnalysis()
        dismiss()
    }

    private func confirm(_ proposal: VoicePlayProposal) {
        guard !submitted, access.active, context.matches(store) else {
            issue = "局面或语音权限已变化，本球未记录。"; return
        }
        switch proposal {
        case .pitch(let pitch):
            submitted = true
            store.recordPitch(pitch)
            if store.storageErrorMessage == nil && store.actionErrorMessage == nil { dismiss() }
            else { submitted = false; issue = store.storageErrorMessage }
        case .hit(let play):
            if let error = runnerIssue(play.decisions) {
                issue = error
                return
            }
            let defense = situationDefensivePlay(outcome: play.outcome, observation: play.observation, decisions: play.decisions)
            if pro {
                if proIssue(play) != nil || (store.situationNeedsTimingDecision(play.outcome, decisions: play.decisions) && play.timing == nil) {
                    proDraft = play
                } else if savePro(play.outcome, defense, play.decisions, play.timing, play.observation) {
                    dismiss()
                }
            } else {
                submitted = true
                var observation = play.observation
                observation.fielding = nil
                if store.applyPlay(play.outcome, defensivePlay: defense, decisions: play.decisions, observation: observation),
                   store.storageErrorMessage == nil { dismiss() }
                else {
                    submitted = false
                    issue = store.storageErrorMessage ?? store.actionErrorMessage ?? "本球未能保存，请重新核对。"
                }
            }
        }
    }

    private func savePro(_ outcome: PlayOutcome, _ defense: DefensivePlay?, _ decisions: [RunnerDecision],
                         _ timing: Bool?, _ observation: BattedBallObservation) -> Bool {
        guard !submitted, access.active, context.matches(store) else {
            store.actionErrorMessage = "局面或语音权限已变化，本球未记录。"; return false
        }
        if let error = runnerIssue(decisions) {
            store.actionErrorMessage = error
            return false
        }
        submitted = true
        let saved = store.applyPlay(outcome, defensivePlay: defense, decisions: decisions,
                                   timingRunCounts: timing, observation: observation) && store.storageErrorMessage == nil
        if !saved {
            submitted = false
            issue = store.storageErrorMessage ?? store.actionErrorMessage ?? "本球未能保存，请重新核对。"
        }
        return saved
    }
}
