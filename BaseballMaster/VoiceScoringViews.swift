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
    @State private var hasRequestedAnalysis = false
    @State private var proDraft: SituationExistingPlay?
    @State private var analysisTask: Task<Void, Never>?
    @State private var issue: String?
    @State private var submitted = false
    @State private var confirmVoiceDataSharing = false

    var body: some View {
        Group {
            if let proDraft {
                SituationPlayFlowSheet(illegalPitch: false, initial: proDraft, onSave: savePro,
                                       onClose: { dismiss() }, voiceReview: true)
            } else {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("第 \(context.state.inning) 局\(context.state.isTop ? "上" : "下") · \(context.state.outs) 出局 · \(context.state.currentBatter.compactName)")
                                .font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                            Text("只说刚才发生的一个局面。解析前可以核对、修改转写。")
                                .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                            recordControl
                            if !text.isEmpty || reviewResult != nil || analysisTask != nil {
                                transcriptEditor
                            }
                            if let reviewResult { review(reviewResult) }
                            if let issue { Text(issue).font(.footnote).foregroundStyle(BMTheme.red).accessibilityIdentifier("voice-issue") }
                        }
                        .frame(maxWidth: 560)
                        .padding(16)
                        .frame(maxWidth: .infinity)
                    }
                    .bmScreenBackground()
                    .navigationTitle("语音输入")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
                }
            }
        }
        .gameActionErrorAlert(store)
        .onChange(of: recorder.transcript) { text = $0 }
        .onReceive(recorder.$error) { error in
            guard let error else { return }
            if let token = access.token { access.report(error, for: token) }
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
        .onChange(of: scenePhase) { if $0 == .background { recorder.interrupt() } }
        .onDisappear { recorder.cancel(); cancelAnalysis() }
    }

    @ViewBuilder private var recordControl: some View {
        switch recorder.phase {
        case .idle:
            Button {
                confirmVoiceDataSharing = true
            } label: {
                Label(text.isEmpty ? "开始录音" : "重新录音", systemImage: "mic")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(analysisTask != nil || !access.active)
            .accessibilityIdentifier("start-voice-recording")
            .alert("使用语音记分", isPresented: $confirmVoiceDataSharing) {
                Button("同意并开始录音", action: beginRecording)
                Button("取消", role: .cancel) {}
            } message: {
                Text("录音将直接发送给腾讯云语音识别，当前局面会发给我们的服务。点「解析局面」后，转写和当前局面（可能含球员姓名、背号）会发给腾讯云混元 AI 生成记分建议。你也可以取消并手动记分。")
            }
        case .connecting, .finishing:
            HStack {
                ProgressView()
                Text(recorder.phase == .connecting ? "正在连接麦克风…" : "正在接收最终转写…")
                Spacer()
                Button("取消录音") { recorder.cancel(); text = "" }
            }
        case .recording:
            Button { recorder.stop() } label: { Label("结束录音", systemImage: "stop.circle") }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("stop-voice-recording")
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
        hasRequestedAnalysis = false
        text = ""
        recorder.start(context: context.payload, token: token, service: access.service)
    }

    private var transcriptEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("语音转写").font(.headline)
            TextEditor(text: Binding(get: { text }, set: {
                text = $0
                if case .ready? = reviewResult { reviewResult = nil }
            }))
            .frame(minHeight: 86)
            .padding(6)
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .disabled(recorder.phase != .idle || analysisTask != nil)
            .accessibilityLabel("语音转写")
            .accessibilityIdentifier("voice-transcript")

            if analysisTask != nil {
                HStack {
                    ProgressView()
                    Text("正在解析本球…")
                    Spacer()
                    Button("取消解析", action: cancelAnalysis)
                }
            } else {
                Button(hasRequestedAnalysis ? "重新解析" : "解析局面", action: parse)
                    .buttonStyle(SecondaryButtonStyle())
                    .disabled(!recorder.finalized || recorder.phase != .idle || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("analyze-voice-play")
            }
        }
    }

    private func review(_ result: VoicePlayReview) -> some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("核对本球").font(.headline)
                switch result {
                case .ready(let proposal, let suggested):
                    Text(suggested ? "建议记为\(proposal.title)" : proposal.title).font(.subheadline.bold())
                    if case .hit(let play) = proposal {
                        if let fielding = play.observation.fielding {
                            Text(fielding.description).font(.subheadline)
                        }
                        ForEach(play.decisions) { item in
                            Text("\(item.origin.title) · \(item.player.compactName) · \(item.destination.title)").font(.subheadline)
                        }
                        if pro && !play.observation.hasLocation {
                            Label("转写未包含落点，请在球场补录。", systemImage: "location.circle")
                                .foregroundStyle(BMTheme.secondaryText)
                                .accessibilityIdentifier("voice-missing-landing")
                        }
                    }
                    Button(reviewActionTitle(proposal)) { confirm(proposal) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(submitted)
                        .accessibilityIdentifier("confirm-voice-play")
                case .needsDetails(let prompts):
                    ForEach(prompts, id: \.self) { prompt in
                        Text(prompt).font(.subheadline).foregroundStyle(BMTheme.secondaryText)
                    }
                    Text("可修改上方转写后重新解析。")
                        .font(.footnote).foregroundStyle(BMTheme.secondaryText)
                    Button("返回手动记分") { dismiss() }.buttonStyle(SecondaryButtonStyle())
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func reviewActionTitle(_ proposal: VoicePlayProposal) -> String {
        if pro, case .hit(let play) = proposal {
            return play.observation.hasLocation ? "到球场核对" : "到球场补落点"
        }
        return "确认记录"
    }

    private func parse() {
        guard analysisTask == nil, recorder.phase == .idle, recorder.finalized, let token = access.token, context.matches(store) else {
            issue = "局面或语音权限已变化，请返回记分页。"; return
        }
        let transcript = text.trimmingCharacters(in: .whitespacesAndNewlines)
        issue = nil
        reviewResult = nil
        hasRequestedAnalysis = true
        analysisTask = Task {
            do {
                let result = try await access.service.analyze(transcript, context: context.payload, token: token)
                try Task.checkCancellation()
                guard context.matches(store), access.token == token else {
                    issue = "局面或语音权限已变化，请返回记分页。"; analysisTask = nil; return
                }
                reviewResult = VoicePlayReview(analysis: result, context: context, transcript: transcript,
                                               pro: pro, slowPitch: store.isSlowPitch)
            } catch {
                guard !Task.isCancelled else { return }
                access.report(error, for: token)
                issue = error.localizedDescription
            }
            analysisTask = nil
        }
    }

    private func cancelAnalysis() {
        analysisTask?.cancel()
        analysisTask = nil
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
            if pro {
                proDraft = play
            } else {
                submitted = true
                let defense = situationDefensivePlay(outcome: play.outcome, observation: play.observation, decisions: play.decisions)
                var observation = play.observation
                observation.fielding = nil
                if store.applyPlay(play.outcome, defensivePlay: defense, decisions: play.decisions, observation: observation),
                   store.storageErrorMessage == nil { dismiss() }
                else { submitted = false; issue = store.storageErrorMessage }
            }
        }
    }

    private func savePro(_ outcome: PlayOutcome, _ defense: DefensivePlay?, _ decisions: [RunnerDecision],
                         _ timing: Bool?, _ observation: BattedBallObservation) -> Bool {
        guard !submitted, access.active, context.matches(store) else {
            store.actionErrorMessage = "局面或语音权限已变化，本球未记录。"; return false
        }
        submitted = true
        let saved = store.applyPlay(outcome, defensivePlay: defense, decisions: decisions,
                                   timingRunCounts: timing, observation: observation) && store.storageErrorMessage == nil
        if !saved { submitted = false }
        return saved
    }
}
