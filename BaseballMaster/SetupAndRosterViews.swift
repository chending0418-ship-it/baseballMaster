import SwiftUI
import UniformTypeIdentifiers

private struct TeamEditorContext: Identifiable {
    let id = UUID()
    let team: Team?
}

private struct PlayerEditorContext: Identifiable {
    let id = UUID()
    let teamID: UUID
    let player: Player?
}

struct TeamRosterView: View {
    @EnvironmentObject private var store: GameStore
    @State private var editorContext: TeamEditorContext?
    @State private var teamPendingDeletion: Team?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: "球队管理", subtitle: "\(store.teams.count) 支球队")

                LazyVStack(spacing: 12) {
                    ForEach(store.teams) { team in
                        HStack(spacing: 0) {
                            NavigationLink(destination: TeamDetailView(teamID: team.id)) {
                                HStack(spacing: 13) {
                                    TeamMark(team: team, size: 52)
                                    VStack(alignment: .leading, spacing: 5) {
                                        HStack(spacing: 7) {
                                            Text(team.name)
                                                .font(.system(size: 18, weight: .bold))
                                                .foregroundStyle(BMTheme.navy)
                                            if team.id == store.currentTeam.id {
                                                Text("当前")
                                                    .font(.system(size: 10, weight: .black))
                                                    .foregroundStyle(BMTheme.green)
                                                    .padding(.horizontal, 7)
                                                    .padding(.vertical, 3)
                                                    .background(BMTheme.greenSoft)
                                                    .clipShape(Capsule())
                                            }
                                        }
                                        Text("\(team.city) · \(team.players.count) 名球员")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(BMTheme.secondaryText)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                .padding(.vertical, 14)
                                .padding(.leading, 14)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("team-card-\(team.shortName)")

                            Menu {
                                if team.id != store.currentTeam.id {
                                    Button {
                                        store.setCurrentTeam(id: team.id)
                                    } label: {
                                        Label("设为当前球队", systemImage: "checkmark.circle")
                                    }
                                }
                                Button {
                                    editorContext = TeamEditorContext(team: team)
                                } label: {
                                    Label("编辑球队", systemImage: "pencil")
                                }
                                Button(role: .destructive) {
                                    teamPendingDeletion = team
                                } label: {
                                    Label("删除球队", systemImage: "trash")
                                }
                                .disabled(store.teams.count <= 1)
                            } label: {
                                Image(systemName: "ellipsis.circle")
                                    .font(.system(size: 20))
                                    .foregroundStyle(BMTheme.secondaryText)
                                    .frame(width: 50, height: 60)
                            }
                            .accessibilityLabel("管理\(team.name)")
                        }
                        .background(BMTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
                        }
                    }
                }

            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("球队")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    editorContext = TeamEditorContext(team: nil)
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("添加球队")
                .accessibilityIdentifier("add-team")
            }
        }
        .sheet(item: $editorContext) { context in
            TeamEditorSheet(team: context.team)
                .environmentObject(store)
        }
        .confirmationDialog(
            "删除球队？",
            isPresented: Binding(
                get: { teamPendingDeletion != nil },
                set: { if !$0 { teamPendingDeletion = nil } }
            ),
            presenting: teamPendingDeletion
        ) { team in
            Button("删除“\(team.name)”", role: .destructive) {
                _ = store.deleteTeam(id: team.id)
                teamPendingDeletion = nil
            }
            Button("取消", role: .cancel) {
                teamPendingDeletion = nil
            }
        } message: { team in
            Text("球队及名单中的 \(team.players.count) 名球员会被删除，此操作无法撤销。")
        }
    }
}

struct TeamDetailView: View {
    @EnvironmentObject private var store: GameStore
    let teamID: UUID

    @State private var playerEditorContext: PlayerEditorContext?
    @State private var teamEditorContext: TeamEditorContext?
    @State private var playerPendingDeletion: Player?

    var body: some View {
        Group {
            if let team = store.team(withID: teamID) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        BMCard {
                            HStack(spacing: 14) {
                                TeamMark(team: team, size: 60)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(team.name)
                                        .font(.system(size: 23, weight: .black))
                                        .foregroundStyle(BMTheme.navy)
                                    Text("\(team.city) · 简称 \(team.shortName)")
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                Spacer()
                                VStack(spacing: 2) {
                                    Text("\(team.players.count)")
                                        .font(.system(size: 28, weight: .black, design: .rounded))
                                        .foregroundStyle(BMTheme.green)
                                    Text("球员")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                            }
                        }

                        HStack {
                            SectionHeader(title: "球队名单")
                            Button {
                                playerEditorContext = PlayerEditorContext(teamID: teamID, player: nil)
                            } label: {
                                Label("添加", systemImage: "person.badge.plus")
                                    .font(.system(size: 14, weight: .bold))
                            }
                            .foregroundStyle(BMTheme.green)
                            .accessibilityIdentifier("add-player")
                        }

                        if team.players.isEmpty {
                            BMCard {
                                VStack(spacing: 10) {
                                    Image(systemName: "person.3")
                                        .font(.system(size: 28))
                                        .foregroundStyle(BMTheme.secondaryText)
                                    Text("还没有球员")
                                        .font(.system(size: 17, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                    Button("添加第一名球员") {
                                        playerEditorContext = PlayerEditorContext(teamID: teamID, player: nil)
                                    }
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(BMTheme.green)
                                }
                                .frame(maxWidth: .infinity, minHeight: 120)
                            }
                        } else {
                            LazyVStack(spacing: 10) {
                                ForEach(team.players) { player in
                                    HStack(spacing: 0) {
                                        NavigationLink(destination: PlayerDetailView(player: player)) {
                                            PlayerRow(player: player)
                                                .padding(.vertical, 13)
                                                .padding(.leading, 14)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityIdentifier("player-card-\(player.name)")

                                        Menu {
                                            Button {
                                                playerEditorContext = PlayerEditorContext(teamID: teamID, player: player)
                                            } label: {
                                                Label("编辑球员", systemImage: "pencil")
                                            }
                                            Button(role: .destructive) {
                                                playerPendingDeletion = player
                                            } label: {
                                                Label("删除球员", systemImage: "trash")
                                            }
                                        } label: {
                                            Image(systemName: "ellipsis.circle")
                                                .font(.system(size: 20))
                                                .foregroundStyle(BMTheme.secondaryText)
                                                .frame(width: 50, height: 58)
                                        }
                                        .accessibilityLabel("管理\(player.name)")
                                    }
                                    .background(BMTheme.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                                }
                            }
                        }
                    }
                    .padding(BMTheme.horizontalPadding)
                }
                .bmScreenBackground()
                .navigationTitle(team.shortName)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            teamEditorContext = TeamEditorContext(team: team)
                        } label: {
                            Image(systemName: "pencil")
                        }
                        .accessibilityLabel("编辑球队")
                    }
                }
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "person.3")
                        .font(.system(size: 34))
                        .foregroundStyle(BMTheme.secondaryText)
                    Text("球队不存在")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text("该球队可能已经被删除。")
                        .font(.system(size: 14))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .bmScreenBackground()
            }
        }
        .sheet(item: $playerEditorContext) { context in
            PlayerEditorSheet(teamID: context.teamID, player: context.player)
                .environmentObject(store)
        }
        .sheet(item: $teamEditorContext) { context in
            TeamEditorSheet(team: context.team)
                .environmentObject(store)
        }
        .confirmationDialog(
            "删除球员？",
            isPresented: Binding(
                get: { playerPendingDeletion != nil },
                set: { if !$0 { playerPendingDeletion = nil } }
            ),
            presenting: playerPendingDeletion
        ) { player in
            Button("删除“\(player.name)”", role: .destructive) {
                store.deletePlayer(from: teamID, playerID: player.id)
                playerPendingDeletion = nil
            }
            Button("取消", role: .cancel) {
                playerPendingDeletion = nil
            }
        } message: { player in
            Text("球员资料及其比赛统计会被删除，此操作无法撤销。")
        }
    }
}

struct TeamEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let team: Team?
    let isOpponent: Bool

    @State private var name: String
    @State private var shortName: String
    @State private var city: String

    init(team: Team?, isOpponent: Bool = false) {
        self.team = team
        self.isOpponent = isOpponent
        _name = State(initialValue: team?.name ?? "")
        _shortName = State(initialValue: team?.shortName ?? "")
        _city = State(initialValue: team?.city ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基本资料") {
                    TextField("球队全称", text: $name)
                    TextField("球队简称", text: $shortName)
                    TextField("所在城市", text: $city)
                }
                Section {
                    Text("球员数量由球队名单自动统计，不需要手动填写。")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                }
            }
            .navigationTitle(team == nil ? (isOpponent ? "添加对手" : "添加球队") : "编辑球队")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        if let team {
                            if isOpponent {
                                store.updateOpponentTeam(id: team.id, name: name, shortName: shortName, city: city)
                            } else {
                                store.updateTeam(id: team.id, name: name, shortName: shortName, city: city)
                            }
                        } else {
                            if isOpponent {
                                store.addOpponentTeam(name: name, shortName: shortName, city: city)
                            } else {
                                store.addTeam(name: name, shortName: shortName, city: city)
                            }
                        }
                        dismiss()
                    }
                    .disabled(!isValid)
                }
            }
        }
    }

    private var isValid: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !shortName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !city.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private struct JerseyNumberInput: Identifiable {
    let id = UUID()
    var text: String
}

struct PlayerEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: GameStore
    let teamID: UUID
    let player: Player?
    let isOpponent: Bool

    @State private var chineseName: String
    @State private var englishName: String
    @State private var numberInputs: [JerseyNumberInput]

    init(teamID: UUID, player: Player?, isOpponent: Bool = false) {
        self.teamID = teamID
        self.player = player
        self.isOpponent = isOpponent
        _chineseName = State(initialValue: player?.chineseName ?? "")
        _englishName = State(initialValue: player?.englishName ?? "")
        let inputs: [JerseyNumberInput]
        if let player, !player.numbers.isEmpty {
            inputs = player.numbers.map { JerseyNumberInput(text: String($0)) }
        } else {
            inputs = [JerseyNumberInput(text: "")]
        }
        _numberInputs = State(initialValue: inputs)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("姓名") {
                    TextField("中文名", text: $chineseName)
                    TextField("英文名", text: $englishName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                }

                Section("背号") {
                    ForEach($numberInputs) { $input in
                        HStack {
                            TextField("背号", text: $input.text)
                                .keyboardType(.numberPad)
                            if numberInputs.count > 1 {
                                Button(role: .destructive) {
                                    numberInputs.removeAll { $0.id == input.id }
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("删除这个背号")
                            }
                        }
                    }

                    Button {
                        numberInputs.append(JerseyNumberInput(text: ""))
                    } label: {
                        Label("添加另一个背号", systemImage: "plus.circle")
                    }
                }

                if !numbersAreValid {
                    Section {
                        Label("背号需为 0–99 的数字，且不能重复。", systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(BMTheme.orange)
                    }
                }

                Section {
                    Text("球员基本资料不记录常用守位；防守位置以每场比赛的实际阵容为准。")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                }
            }
            .navigationTitle(player == nil ? "添加球员" : "编辑球员")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        save()
                        dismiss()
                    }
                    .fontWeight(.bold)
                    .disabled(!isValid)
                }
            }
        }
    }

    private var parsedNumbers: [Int]? {
        let trimmed = numberInputs.map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard !trimmed.isEmpty, trimmed.allSatisfy({ !$0.isEmpty }) else { return nil }
        let numbers = trimmed.compactMap(Int.init)
        guard numbers.count == trimmed.count,
              numbers.allSatisfy({ (0...99).contains($0) }),
              Set(numbers).count == numbers.count else { return nil }
        return numbers
    }

    private var numbersAreValid: Bool { parsedNumbers != nil }

    private var isValid: Bool {
        !chineseName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !englishName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && numbersAreValid
    }

    private func save() {
        guard let numbers = parsedNumbers else { return }
        if let player {
            if isOpponent {
                store.updateOpponentPlayer(
                    in: teamID,
                    playerID: player.id,
                    chineseName: chineseName,
                    englishName: englishName,
                    numbers: numbers
                )
            } else {
                store.updatePlayer(
                    in: teamID,
                    playerID: player.id,
                    chineseName: chineseName,
                    englishName: englishName,
                    numbers: numbers
                )
            }
        } else {
            if isOpponent {
                store.addOpponentPlayer(
                    to: teamID,
                    chineseName: chineseName,
                    englishName: englishName,
                    numbers: numbers
                )
            } else {
                store.addPlayer(
                    to: teamID,
                    chineseName: chineseName,
                    englishName: englishName,
                    numbers: numbers
                )
            }
        }
    }
}

private struct OpponentTeamEditorContext: Identifiable {
    let id = UUID()
    let team: Team?
}

private enum NewGameMode: String, CaseIterable, Identifiable {
    case team = "本队比赛"
    case spectator = "观赛记录"

    var id: String { rawValue }
}

struct NewGameSetupView: View {
    @EnvironmentObject private var store: GameStore
    @State private var mode = NewGameMode.team
    @State private var selectedTeamID: UUID?
    @State private var selectedOpponentID: UUID?
    @State private var spectatorAwayTeamID: UUID?
    @State private var spectatorHomeTeamID: UUID?
    @State private var isHome = false
    @State private var innings = 6
    @State private var fieldersCount = 9
    @State private var hasTimeLimit = false
    @State private var timeLimitMinutes = 90
    @State private var timeWarningMinutes = 10
    @State private var hasPitchLimit = false
    @State private var pitchLimit = 80
    @State private var pitchWarningRemaining = 10
    @State private var hasPitcherInningsLimit = false
    @State private var pitcherInningsLimit = 3
    @State private var usesDesignatedHitter = false
    @State private var allowsTwoWayPlayer = false
    @State private var scheduleForLater = false
    @State private var configureScheduleNow = false
    @State private var scheduledAt = Date().addingTimeInterval(86_400)
    @State private var showLineup = false
    @State private var showScheduleConfirmation = false
    @State private var showScheduledCreated = false

    init(scheduleForLater: Bool = false, configureScheduleNow: Bool = false) {
        _selectedTeamID = State(initialValue: nil)
        _selectedOpponentID = State(initialValue: nil)
        _spectatorAwayTeamID = State(initialValue: nil)
        _spectatorHomeTeamID = State(initialValue: nil)
        _scheduleForLater = State(initialValue: scheduleForLater)
        _configureScheduleNow = State(initialValue: configureScheduleNow)
    }

    private var selectedTeam: Team? {
        store.teams.first(where: { $0.id == selectedTeamID })
    }

    private var selectedOpponent: Team? {
        store.opponentTeams.first(where: { $0.id == selectedOpponentID })
    }

    private var spectatorAwayTeam: Team? {
        store.opponentTeams.first(where: { $0.id == spectatorAwayTeamID })
    }

    private var spectatorHomeTeam: Team? {
        store.opponentTeams.first(where: { $0.id == spectatorHomeTeamID })
    }

    private var rules: GameRules {
        GameRules(
            scheduledInnings: innings,
            fieldersCount: fieldersCount,
            timeLimitMinutes: hasTimeLimit ? timeLimitMinutes : nil,
            timeWarningMinutes: hasTimeLimit ? timeWarningMinutes : nil,
            pitchLimit: hasPitchLimit ? pitchLimit : nil,
            pitchWarningRemaining: hasPitchLimit ? pitchWarningRemaining : nil,
            pitcherInningsLimit: hasPitcherInningsLimit ? pitcherInningsLimit : nil,
            usesDesignatedHitter: usesDesignatedHitter,
            allowsTwoWayPlayer: usesDesignatedHitter && allowsTwoWayPlayer
        )
    }

    private var validationMessages: [String] {
        var messages: [String] = []
        if mode == .spectator {
            guard let away = spectatorAwayTeam, let home = spectatorHomeTeam else {
                messages.append("请选择观赛的客队和主队")
                if store.opponentTeams.count < 2 { messages.append("观赛记录至少需要创建 2 支对手球队") }
                return messages
            }
            if away.id == home.id { messages.append("观赛的客队和主队不能相同") }
            if scheduleForLater && !configureScheduleNow { return messages }
            let requiredPlayers = fieldersCount + (usesDesignatedHitter && !allowsTwoWayPlayer ? 1 : 0)
            if away.players.count < requiredPlayers {
                messages.append("客队至少需要 \(requiredPlayers) 名球员，当前有 \(away.players.count) 名")
            }
            if home.players.count < requiredPlayers {
                messages.append("主队至少需要 \(requiredPlayers) 名球员，当前有 \(home.players.count) 名")
            }
            return messages
        }
        guard let selectedTeam else {
            messages.append("请选择本队")
            if selectedOpponent == nil { messages.append("请选择对手球队") }
            return messages
        }
        guard let selectedOpponent else {
            messages.append("请选择对手球队")
            return messages
        }
        if scheduleForLater && !configureScheduleNow { return messages }
        let requiredPlayers = fieldersCount + (usesDesignatedHitter && !allowsTwoWayPlayer ? 1 : 0)
        if selectedTeam.players.count < requiredPlayers {
            messages.append("本队至少需要 \(requiredPlayers) 名球员，当前有 \(selectedTeam.players.count) 名")
        }
        if selectedOpponent.players.count < requiredPlayers {
            messages.append("对手至少需要 \(requiredPlayers) 名球员，当前有 \(selectedOpponent.players.count) 名")
        }
        return messages
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                SectionHeader(
                    title: "参赛球队",
                    subtitle: scheduleForLater ? "安排时只需基本信息" : "第 1 步，共 2 步"
                )
                Picker("记录类型", selection: $mode) {
                    ForEach(NewGameMode.allCases) { value in Text(value.rawValue).tag(value) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("game-mode-picker")

                BMCard {
                    VStack(spacing: 0) {
                        if mode == .team {
                            settingPickerRow(title: "本队", icon: "person.3.fill") {
                                Picker("本队", selection: $selectedTeamID) {
                                    ForEach(store.teams) { team in
                                        Text("\(team.shortName) · \(team.players.count) 人").tag(Optional(team.id))
                                    }
                                }
                                .pickerStyle(.menu)
                            }

                            Divider().padding(.leading, 42)

                            settingPickerRow(title: "对手", icon: "shield.fill") {
                                if store.opponentTeams.isEmpty {
                                    Text("尚未创建")
                                        .foregroundStyle(BMTheme.orange)
                                } else {
                                    Picker("对手", selection: $selectedOpponentID) {
                                        ForEach(store.opponentTeams) { team in
                                            Text("\(team.shortName) · \(team.players.count) 人").tag(Optional(team.id))
                                        }
                                    }
                                    .pickerStyle(.menu)
                                }
                            }
                        } else {
                            settingPickerRow(title: "客队 · 先攻", icon: "airplane.departure") {
                                Picker("观赛客队", selection: $spectatorAwayTeamID) {
                                    ForEach(store.opponentTeams) { team in
                                        Text("\(team.shortName) · \(team.players.count) 人").tag(Optional(team.id))
                                    }
                                }
                                .pickerStyle(.menu)
                            }

                            Divider().padding(.leading, 42)

                            settingPickerRow(title: "主队 · 后攻", icon: "house.fill") {
                                Picker("观赛主队", selection: $spectatorHomeTeamID) {
                                    ForEach(store.opponentTeams) { team in
                                        Text("\(team.shortName) · \(team.players.count) 人").tag(Optional(team.id))
                                    }
                                }
                                .pickerStyle(.menu)
                            }
                        }

                        Divider().padding(.leading, 42)

                        NavigationLink(destination: OpponentTeamsView()) {
                            Label("管理对手球队和名单", systemImage: "person.3.sequence.fill")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(BMTheme.green)
                                .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
                        }
                        .accessibilityIdentifier("manage-opponents")
                    }
                }

                if mode == .team {
                    SectionHeader(title: "主客关系")
                    BMCard {
                        settingPickerRow(title: "本队身份", icon: "house.fill") {
                            Picker("本队身份", selection: $isHome) {
                                Text("客队 · 先攻").tag(false)
                                Text("主队 · 后攻").tag(true)
                            }
                            .pickerStyle(.menu)
                        }
                    }
                }

                SectionHeader(title: "比赛时间")
                BMCard {
                    VStack(spacing: 0) {
                        Toggle("安排未来比赛", isOn: $scheduleForLater)
                            .font(.system(size: 15, weight: .semibold))
                            .tint(BMTheme.green)
                            .frame(minHeight: 54)
                        if scheduleForLater {
                            Divider()
                            DatePicker(
                                "开赛时间",
                                selection: $scheduledAt,
                                in: Date()...,
                                displayedComponents: [.date, .hourAndMinute]
                            )
                            .font(.system(size: 15, weight: .semibold))
                            .frame(minHeight: 58)
                            .accessibilityIdentifier("scheduled-game-date")
                        }
                    }
                }

                if scheduleForLater {
                    BMCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Label(
                                "可以只保存双方球队和时间；名单、规则、棒次与守位也可以现在设置，或留到开赛前补充。",
                                systemImage: "calendar.badge.checkmark"
                            )
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(BMTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                            Divider()
                            Toggle("现在完成赛前设置", isOn: $configureScheduleNow)
                                .font(.system(size: 15, weight: .semibold))
                                .tint(BMTheme.green)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("configure-schedule-now")
                        }
                    }
                }

                if !scheduleForLater || configureScheduleNow {
                    SectionHeader(title: "比赛规则")
                    BMCard {
                        VStack(spacing: 0) {
                            settingPickerRow(title: "规定局数", icon: "number.circle.fill") {
                                Picker("规定局数", selection: $innings) {
                                    ForEach(1...9, id: \.self) { Text("\($0) 局").tag($0) }
                                }
                                .pickerStyle(.menu)
                            }

                            Divider().padding(.leading, 42)

                            settingPickerRow(title: "守备人数", icon: "person.3.fill") {
                                Picker("守备人数", selection: $fieldersCount) {
                                    ForEach(6...9, id: \.self) { Text("\($0) 人").tag($0) }
                                }
                                .pickerStyle(.menu)
                            }

                            Divider().padding(.leading, 42)

                            Toggle("启用指定打击 DH", isOn: $usesDesignatedHitter)
                                .font(.system(size: 15, weight: .semibold))
                                .tint(BMTheme.green)
                                .frame(minHeight: 54)
                            if usesDesignatedHitter {
                                Divider().padding(.leading, 42)
                                Toggle("允许投手兼任 DH（大谷条款）", isOn: $allowsTwoWayPlayer)
                                    .font(.system(size: 14, weight: .semibold))
                                    .tint(BMTheme.green)
                                    .frame(minHeight: 54)
                            }
                        }
                    }

                    SectionHeader(title: "时间与投球提醒", subtitle: "只提醒，不自动结束比赛")
                    BMCard {
                        VStack(spacing: 0) {
                            Toggle("比赛时间限制", isOn: $hasTimeLimit)
                                .font(.system(size: 15, weight: .semibold))
                                .tint(BMTheme.green)
                                .frame(minHeight: 54)
                            if hasTimeLimit {
                                Divider()
                                settingPickerRow(title: "比赛时长", icon: "timer") {
                                    Picker("比赛时长", selection: $timeLimitMinutes) {
                                        ForEach([60, 75, 90, 120], id: \.self) { Text("\($0) 分钟").tag($0) }
                                    }
                                    .pickerStyle(.menu)
                                }
                                Divider().padding(.leading, 42)
                                settingPickerRow(title: "提前提醒", icon: "bell.fill") {
                                    Picker("提前提醒", selection: $timeWarningMinutes) {
                                        ForEach([5, 10, 15, 20], id: \.self) { Text("剩余 \($0) 分").tag($0) }
                                    }
                                    .pickerStyle(.menu)
                                }
                            }

                            Divider()

                            Toggle("单投手球数限制", isOn: $hasPitchLimit)
                                .font(.system(size: 15, weight: .semibold))
                                .tint(BMTheme.green)
                                .frame(minHeight: 54)
                            if hasPitchLimit {
                                Divider()
                                settingPickerRow(title: "投球上限", icon: "baseball.fill") {
                                    Picker("投球上限", selection: $pitchLimit) {
                                        ForEach(Array(stride(from: 40, through: 120, by: 5)), id: \.self) { Text("\($0) 球").tag($0) }
                                    }
                                    .pickerStyle(.menu)
                                }
                                Divider().padding(.leading, 42)
                                settingPickerRow(title: "提前提醒", icon: "bell.fill") {
                                    Picker("投球提醒", selection: $pitchWarningRemaining) {
                                        ForEach([5, 10, 15, 20], id: \.self) { Text("剩余 \($0) 球").tag($0) }
                                    }
                                    .pickerStyle(.menu)
                                }
                            }

                            Divider()

                            Toggle("单投手局数限制", isOn: $hasPitcherInningsLimit)
                                .font(.system(size: 15, weight: .semibold))
                                .tint(BMTheme.green)
                                .frame(minHeight: 54)
                            if hasPitcherInningsLimit {
                                Divider()
                                settingPickerRow(title: "投球局数上限", icon: "number.circle.fill") {
                                    Picker("投球局数上限", selection: $pitcherInningsLimit) {
                                        ForEach(1...7, id: \.self) { Text("\($0) 局").tag($0) }
                                    }
                                    .pickerStyle(.menu)
                                }
                            }
                        }
                    }
                }

                if !validationMessages.isEmpty {
                    BMCard {
                        VStack(alignment: .leading, spacing: 7) {
                            ForEach(validationMessages, id: \.self) { message in
                                Label(message, systemImage: "exclamationmark.triangle.fill")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(BMTheme.orange)
                            }
                        }
                    }
                }

                Button {
                    if scheduleForLater && !configureScheduleNow {
                        showScheduleConfirmation = true
                    } else {
                        if mode == .team {
                            guard let selectedTeamID else { return }
                            store.setCurrentTeam(id: selectedTeamID)
                        }
                        showLineup = true
                    }
                } label: {
                    HStack {
                        Text(scheduleForLater && !configureScheduleNow ? "保存比赛安排" : "下一步：设置阵容")
                        Spacer()
                        Image(systemName: scheduleForLater && !configureScheduleNow ? "calendar.badge.plus" : "arrow.right")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!validationMessages.isEmpty)
                .opacity(validationMessages.isEmpty ? 1 : 0.45)
                .accessibilityIdentifier("continue-to-lineup")
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("新比赛")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if selectedTeamID == nil || !store.teams.contains(where: { $0.id == selectedTeamID }) {
                selectedTeamID = store.currentTeam.id
            }
            if selectedOpponentID == nil || !store.opponentTeams.contains(where: { $0.id == selectedOpponentID }) {
                selectedOpponentID = store.opponentTeams.first?.id
            }
            if spectatorAwayTeamID == nil || !store.opponentTeams.contains(where: { $0.id == spectatorAwayTeamID }) {
                spectatorAwayTeamID = store.opponentTeams.first?.id
            }
            if spectatorHomeTeamID == nil || !store.opponentTeams.contains(where: { $0.id == spectatorHomeTeamID }) {
                spectatorHomeTeamID = store.opponentTeams.dropFirst().first?.id
            }
        }
        .navigationDestination(isPresented: $showLineup) {
            if mode == .spectator, let away = spectatorAwayTeam, let home = spectatorHomeTeam {
                ObservedGameLineupView(
                    awayTeam: away,
                    homeTeam: home,
                    rules: rules,
                    scheduledAt: scheduleForLater ? scheduledAt : Date(),
                    startImmediately: !scheduleForLater
                )
            } else if let opponent = selectedOpponent {
                LineupSelectionView(
                    opponent: opponent,
                    isHome: isHome,
                    rules: rules,
                    scheduledAt: scheduleForLater ? scheduledAt : Date(),
                    startImmediately: !scheduleForLater
                )
            }
        }
        .alert("保存比赛安排？", isPresented: $showScheduleConfirmation) {
            Button("取消", role: .cancel) {}
            Button("保存安排") { saveSchedule() }
        } message: {
            Text("只保存双方球队、主客关系和开赛时间。名单、比赛规则、棒次与守位会在开赛前设置。")
        }
        .alert("比赛安排已保存", isPresented: $showScheduledCreated) {
            Button("返回比赛首页") { store.returnToGameHome() }
        } message: {
            Text("可从比赛首页的“即将进行”打开，并在开赛前补充名单和完成设置。")
        }
    }

    private func saveSchedule() {
        if mode == .spectator {
            guard let away = spectatorAwayTeam, let home = spectatorHomeTeam else { return }
            _ = store.scheduleObservedGame(awayTeam: away, homeTeam: home, scheduledAt: scheduledAt)
        } else {
            guard let ourTeam = selectedTeam, let opponent = selectedOpponent else { return }
            _ = store.scheduleGame(
                ourTeam: ourTeam,
                opponent: opponent,
                isHome: isHome,
                scheduledAt: scheduledAt
            )
        }
        showScheduledCreated = true
    }

    private func settingPickerRow<Content: View>(title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(BMTheme.green)
                .frame(width: 30)
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(BMTheme.navy)
            Spacer()
            content()
        }
        .frame(minHeight: 58)
    }
}

struct OpponentTeamsView: View {
    @EnvironmentObject private var store: GameStore
    @State private var editorContext: OpponentTeamEditorContext?
    @State private var teamPendingDeletion: Team?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "对手球队", subtitle: "与本队资料分开保存")
                if store.opponentTeams.isEmpty {
                    BMCard {
                        VStack(spacing: 10) {
                            Image(systemName: "shield.slash")
                                .font(.system(size: 28))
                                .foregroundStyle(BMTheme.secondaryText)
                            Text("还没有对手球队")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Button("添加第一个对手") {
                                editorContext = OpponentTeamEditorContext(team: nil)
                            }
                            .fontWeight(.bold)
                            .foregroundStyle(BMTheme.green)
                        }
                        .frame(maxWidth: .infinity, minHeight: 130)
                    }
                } else {
                    ForEach(store.opponentTeams) { team in
                        HStack(spacing: 0) {
                            NavigationLink(destination: OpponentTeamDetailView(teamID: team.id)) {
                                HStack(spacing: 12) {
                                    TeamMark(team: team, size: 46)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(team.name)
                                            .font(.system(size: 17, weight: .bold))
                                            .foregroundStyle(BMTheme.navy)
                                        Text("\(team.city) · \(team.players.count) 名球员")
                                            .font(.system(size: 12))
                                            .foregroundStyle(BMTheme.secondaryText)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                .padding(.leading, 14)
                                .padding(.vertical, 13)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("opponent-card-\(team.shortName)")

                            Menu {
                                Button("编辑球队", systemImage: "pencil") {
                                    editorContext = OpponentTeamEditorContext(team: team)
                                }
                                Button("删除球队", systemImage: "trash", role: .destructive) {
                                    teamPendingDeletion = team
                                }
                            } label: {
                                Image(systemName: "ellipsis.circle")
                                    .font(.system(size: 20))
                                    .foregroundStyle(BMTheme.secondaryText)
                                    .frame(width: 50, height: 58)
                            }
                        }
                        .background(BMTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 15))
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("对手管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editorContext = OpponentTeamEditorContext(team: nil) } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("添加对手球队")
                .accessibilityIdentifier("add-opponent-team")
            }
        }
        .sheet(item: $editorContext) { context in
            TeamEditorSheet(team: context.team, isOpponent: true)
                .environmentObject(store)
        }
        .confirmationDialog(
            "删除对手球队？",
            isPresented: Binding(
                get: { teamPendingDeletion != nil },
                set: { if !$0 { teamPendingDeletion = nil } }
            ),
            presenting: teamPendingDeletion
        ) { team in
            Button("删除“\(team.name)”", role: .destructive) {
                _ = store.deleteOpponentTeam(id: team.id)
                teamPendingDeletion = nil
            }
            Button("取消", role: .cancel) { teamPendingDeletion = nil }
        } message: { team in
            Text("该球队与 \(team.players.count) 名球员会从可选对手中删除；已完成的比赛记录不受影响。")
        }
    }
}

struct OpponentTeamDetailView: View {
    @EnvironmentObject private var store: GameStore
    let teamID: UUID
    @State private var playerEditorContext: PlayerEditorContext?
    @State private var playerPendingDeletion: Player?

    var body: some View {
        Group {
            if let team = store.opponentTeam(withID: teamID) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        BMCard {
                            HStack {
                                TeamMark(team: team, size: 52)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(team.name)
                                        .font(.system(size: 20, weight: .black))
                                        .foregroundStyle(BMTheme.navy)
                                    Text("\(team.city) · \(team.players.count) 名球员")
                                        .font(.system(size: 13))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                                Spacer()
                            }
                        }

                        HStack {
                            SectionHeader(title: "对手名单")
                            Button {
                                playerEditorContext = PlayerEditorContext(teamID: teamID, player: nil)
                            } label: {
                                Label("添加", systemImage: "person.badge.plus")
                                    .font(.system(size: 14, weight: .bold))
                            }
                            .foregroundStyle(BMTheme.green)
                            .accessibilityIdentifier("add-opponent-player")
                        }

                        if team.players.count < 9 {
                            Button {
                                fillPlaceholderPlayers(team)
                            } label: {
                                Label("补足到 9 名占位球员", systemImage: "wand.and.stars")
                            }
                            .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                            .accessibilityIdentifier("fill-opponent-roster")
                        }

                        if team.players.isEmpty {
                            BMCard {
                                Text("添加真实姓名，或先用占位球员快速开始。")
                                    .font(.system(size: 14))
                                    .foregroundStyle(BMTheme.secondaryText)
                                    .frame(maxWidth: .infinity, minHeight: 80)
                            }
                        } else {
                            ForEach(team.players) { player in
                                HStack {
                                    PlayerRow(player: player)
                                    Menu {
                                        Button("编辑球员", systemImage: "pencil") {
                                            playerEditorContext = PlayerEditorContext(teamID: teamID, player: player)
                                        }
                                        Button("删除球员", systemImage: "trash", role: .destructive) {
                                            playerPendingDeletion = player
                                        }
                                    } label: {
                                        Image(systemName: "ellipsis.circle")
                                            .font(.system(size: 20))
                                            .foregroundStyle(BMTheme.secondaryText)
                                    }
                                }
                                .padding(14)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 15))
                            }
                        }
                    }
                    .padding(BMTheme.horizontalPadding)
                }
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "shield.slash")
                        .font(.system(size: 32))
                        .foregroundStyle(BMTheme.secondaryText)
                    Text("对手球队不存在")
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text("该球队可能已经被删除。")
                        .font(.system(size: 14))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .bmScreenBackground()
        .navigationTitle("对手名单")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $playerEditorContext) { context in
            PlayerEditorSheet(teamID: context.teamID, player: context.player, isOpponent: true)
                .environmentObject(store)
        }
        .confirmationDialog(
            "删除球员？",
            isPresented: Binding(
                get: { playerPendingDeletion != nil },
                set: { if !$0 { playerPendingDeletion = nil } }
            ),
            presenting: playerPendingDeletion
        ) { player in
            Button("删除“\(player.name)”", role: .destructive) {
                store.deleteOpponentPlayer(from: teamID, playerID: player.id)
                playerPendingDeletion = nil
            }
            Button("取消", role: .cancel) { playerPendingDeletion = nil }
        }
    }

    private func fillPlaceholderPlayers(_ team: Team) {
        let usedNumbers = Set(team.players.flatMap(\.numbers))
        var number = 1
        for index in team.players.count..<9 {
            while usedNumbers.contains(number) { number += 1 }
            _ = store.addOpponentPlayer(
                to: team.id,
                chineseName: "对手球员 \(index + 1)",
                englishName: "Opponent \(index + 1)",
                numbers: [number]
            )
            number += 1
        }
    }
}

struct ScheduledGamePreparationView: View {
    @EnvironmentObject private var store: GameStore
    let gameID: UUID

    @State private var rules = GameRules()
    @State private var didLoadRules = false
    @State private var showLineup = false

    private var stored: StoredGame? {
        store.games.first(where: { $0.id == gameID && $0.status == .scheduled })
    }

    private var localTeam: Team? {
        guard let id = stored?.ourTeamID else { return nil }
        return store.team(withID: id)
    }

    private var localOpponent: Team? {
        guard let id = stored?.opponentTeamID else { return nil }
        return store.opponentTeam(withID: id)
    }

    private var observedAwayTeam: Team? {
        guard let id = stored?.state.awayTeam.id else { return nil }
        return store.opponentTeam(withID: id)
    }

    private var observedHomeTeam: Team? {
        guard let id = stored?.state.homeTeam.id else { return nil }
        return store.opponentTeam(withID: id)
    }

    private var validationMessages: [String] {
        guard let stored else { return ["比赛安排不存在"] }
        let requiredPlayers = rules.fieldersCount + (rules.designatedHitterEnabled && !rules.twoWayPlayerEnabled ? 1 : 0)
        if stored.isObservation {
            guard let away = observedAwayTeam, let home = observedHomeTeam else {
                return ["观赛球队已被删除，请重新创建比赛安排"]
            }
            return [
                away.players.count < requiredPlayers
                    ? "客队至少需要 \(requiredPlayers) 名球员，当前有 \(away.players.count) 名"
                    : nil,
                home.players.count < requiredPlayers
                    ? "主队至少需要 \(requiredPlayers) 名球员，当前有 \(home.players.count) 名"
                    : nil
            ].compactMap { $0 }
        }
        guard let team = localTeam, let opponent = localOpponent else {
            return ["参赛球队已被删除，请重新创建比赛安排"]
        }
        return [
            team.players.count < requiredPlayers
                ? "本队至少需要 \(requiredPlayers) 名球员，当前有 \(team.players.count) 名"
                : nil,
            opponent.players.count < requiredPlayers
                ? "对手至少需要 \(requiredPlayers) 名球员，当前有 \(opponent.players.count) 名"
                : nil
        ].compactMap { $0 }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                BMCard {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("赛前准备", systemImage: "checklist")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(BMTheme.green)
                        Text("可补充双方名单、确认比赛规则，再设置本场棒次与守位。已经提前设置过的内容会保留，可继续修改。")
                            .font(.system(size: 13))
                            .foregroundStyle(BMTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                SectionHeader(title: "球队名单", subtitle: "不足时可先进入补充")
                if let stored, stored.isObservation {
                    rosterLink(team: observedAwayTeam, role: "客队名单", isOpponent: true)
                    rosterLink(team: observedHomeTeam, role: "主队名单", isOpponent: true)
                } else {
                    rosterLink(team: localTeam, role: "本队名单", isOpponent: false)
                    rosterLink(team: localOpponent, role: "对手名单", isOpponent: true)
                }

                GameRulesEditor(rules: $rules)

                if !validationMessages.isEmpty {
                    BMCard {
                        VStack(alignment: .leading, spacing: 7) {
                            ForEach(validationMessages, id: \.self) { message in
                                Label(message, systemImage: "exclamationmark.triangle.fill")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(BMTheme.orange)
                            }
                        }
                    }
                }

                Button {
                    showLineup = true
                } label: {
                    HStack {
                        Text("下一步：确认阵容")
                        Spacer()
                        Image(systemName: "arrow.right")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!validationMessages.isEmpty)
                .opacity(validationMessages.isEmpty ? 1 : 0.45)
                .accessibilityIdentifier("continue-scheduled-lineup")
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("赛前设置")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !didLoadRules, let stored else { return }
            rules = stored.rules
            didLoadRules = true
        }
        .navigationDestination(isPresented: $showLineup) {
            if let stored, stored.isObservation,
               let away = observedAwayTeam,
               let home = observedHomeTeam {
                ObservedGameLineupView(
                    awayTeam: away,
                    homeTeam: home,
                    rules: rules,
                    startImmediately: true,
                    scheduledGameID: gameID,
                    initialAwayLineup: stored.lineup,
                    initialHomeLineup: stored.secondaryLineup ?? []
                )
            } else if let stored,
                      let team = localTeam,
                      let opponent = localOpponent {
                LineupSelectionView(
                    opponent: opponent,
                    isHome: stored.isHome,
                    rules: rules,
                    startImmediately: true,
                    lineupTeamID: team.id,
                    scheduledGameID: gameID,
                    initialLineup: stored.lineup
                )
            }
        }
    }

    @ViewBuilder
    private func rosterLink(team: Team?, role: String, isOpponent: Bool) -> some View {
        if let team {
            NavigationLink {
                if isOpponent {
                    OpponentTeamDetailView(teamID: team.id)
                } else {
                    TeamDetailView(teamID: team.id)
                }
            } label: {
                HStack(spacing: 12) {
                    TeamMark(team: team, size: 42)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(role)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(BMTheme.secondaryText)
                        Text("\(team.name) · \(team.players.count) 人")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                    }
                    Spacer()
                    Image(systemName: "person.badge.plus")
                        .foregroundStyle(BMTheme.green)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .padding(13)
                .background(BMTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 15))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("scheduled-roster-\(role)")
        }
    }
}

private struct GameRulesEditor: View {
    @Binding var rules: GameRules

    private var hasTimeLimit: Binding<Bool> {
        Binding(
            get: { rules.timeLimitMinutes != nil },
            set: { enabled in
                rules.timeLimitMinutes = enabled ? (rules.timeLimitMinutes ?? 90) : nil
                rules.timeWarningMinutes = enabled ? (rules.timeWarningMinutes ?? 10) : nil
            }
        )
    }

    private var hasPitchLimit: Binding<Bool> {
        Binding(
            get: { rules.pitchLimit != nil },
            set: { enabled in
                rules.pitchLimit = enabled ? (rules.pitchLimit ?? 80) : nil
                rules.pitchWarningRemaining = enabled ? (rules.pitchWarningRemaining ?? 10) : nil
            }
        )
    }

    private var hasPitcherInningsLimit: Binding<Bool> {
        Binding(
            get: { rules.pitcherInningsLimit != nil },
            set: { enabled in rules.pitcherInningsLimit = enabled ? (rules.pitcherInningsLimit ?? 3) : nil }
        )
    }

    private var usesDesignatedHitter: Binding<Bool> {
        Binding(
            get: { rules.designatedHitterEnabled },
            set: { enabled in
                rules.usesDesignatedHitter = enabled
                if !enabled { rules.allowsTwoWayPlayer = false }
            }
        )
    }

    private var allowsTwoWayPlayer: Binding<Bool> {
        Binding(
            get: { rules.twoWayPlayerEnabled },
            set: { rules.allowsTwoWayPlayer = $0 }
        )
    }

    var body: some View {
        SectionHeader(title: "比赛规则")
        BMCard {
            VStack(spacing: 0) {
                settingPickerRow(title: "规定局数", icon: "number.circle.fill") {
                    Picker("规定局数", selection: $rules.scheduledInnings) {
                        ForEach(1...9, id: \.self) { Text("\($0) 局").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                Divider().padding(.leading, 42)
                settingPickerRow(title: "守备人数", icon: "person.3.fill") {
                    Picker("守备人数", selection: $rules.fieldersCount) {
                        ForEach(6...9, id: \.self) { Text("\($0) 人").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                Divider().padding(.leading, 42)
                Toggle("启用指定打击 DH", isOn: usesDesignatedHitter)
                    .font(.system(size: 15, weight: .semibold))
                    .tint(BMTheme.green)
                    .frame(minHeight: 54)
                if rules.designatedHitterEnabled {
                    Divider().padding(.leading, 42)
                    Toggle("允许投手兼任 DH（大谷条款）", isOn: allowsTwoWayPlayer)
                        .font(.system(size: 14, weight: .semibold))
                        .tint(BMTheme.green)
                        .frame(minHeight: 54)
                }
            }
        }

        SectionHeader(title: "时间与投球提醒", subtitle: "只提醒，不自动结束比赛")
        BMCard {
            VStack(spacing: 0) {
                Toggle("比赛时间限制", isOn: hasTimeLimit)
                    .font(.system(size: 15, weight: .semibold))
                    .tint(BMTheme.green)
                    .frame(minHeight: 54)
                if rules.timeLimitMinutes != nil {
                    Divider()
                    settingPickerRow(title: "比赛时长", icon: "timer") {
                        Picker(
                            "比赛时长",
                            selection: Binding(
                                get: { rules.timeLimitMinutes ?? 90 },
                                set: { rules.timeLimitMinutes = $0 }
                            )
                        ) {
                            ForEach([60, 75, 90, 120], id: \.self) { Text("\($0) 分钟").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                    Divider().padding(.leading, 42)
                    settingPickerRow(title: "提前提醒", icon: "bell.fill") {
                        Picker(
                            "提前提醒",
                            selection: Binding(
                                get: { rules.timeWarningMinutes ?? 10 },
                                set: { rules.timeWarningMinutes = $0 }
                            )
                        ) {
                            ForEach([5, 10, 15, 20], id: \.self) { Text("剩余 \($0) 分").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                }

                Divider()
                Toggle("单投手球数限制", isOn: hasPitchLimit)
                    .font(.system(size: 15, weight: .semibold))
                    .tint(BMTheme.green)
                    .frame(minHeight: 54)
                if rules.pitchLimit != nil {
                    Divider()
                    settingPickerRow(title: "投球上限", icon: "baseball.fill") {
                        Picker(
                            "投球上限",
                            selection: Binding(
                                get: { rules.pitchLimit ?? 80 },
                                set: { rules.pitchLimit = $0 }
                            )
                        ) {
                            ForEach(Array(stride(from: 40, through: 120, by: 5)), id: \.self) { Text("\($0) 球").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                    Divider().padding(.leading, 42)
                    settingPickerRow(title: "提前提醒", icon: "bell.fill") {
                        Picker(
                            "投球提醒",
                            selection: Binding(
                                get: { rules.pitchWarningRemaining ?? 10 },
                                set: { rules.pitchWarningRemaining = $0 }
                            )
                        ) {
                            ForEach([5, 10, 15, 20], id: \.self) { Text("剩余 \($0) 球").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                }

                Divider()
                Toggle("单投手局数限制", isOn: hasPitcherInningsLimit)
                    .font(.system(size: 15, weight: .semibold))
                    .tint(BMTheme.green)
                    .frame(minHeight: 54)
                if rules.pitcherInningsLimit != nil {
                    Divider()
                    settingPickerRow(title: "投球局数上限", icon: "number.circle.fill") {
                        Picker(
                            "投球局数上限",
                            selection: Binding(
                                get: { rules.pitcherInningsLimit ?? 3 },
                                set: { rules.pitcherInningsLimit = $0 }
                            )
                        ) {
                            ForEach(1...7, id: \.self) { Text("\($0) 局").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                }
            }
        }
    }

    private func settingPickerRow<Content: View>(
        title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(BMTheme.green)
                .frame(width: 30)
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(BMTheme.navy)
            Spacer()
            content()
        }
        .frame(minHeight: 58)
    }
}

struct LineupSelectionView: View {
    @EnvironmentObject private var store: GameStore
    let opponent: Team
    let isHome: Bool
    let rules: GameRules
    let scheduledAt: Date
    let startImmediately: Bool
    let lineupTeamID: UUID?
    let scheduledGameID: UUID?

    @State private var assignments: [LineupAssignment]
    @State private var showConfirmation = false
    @State private var showGame = false
    @State private var showScheduledCreated = false
    @State private var historyMessage: String?
    @State private var draggingPlayerID: UUID?

    init(
        opponent: Team,
        isHome: Bool,
        rules: GameRules,
        scheduledAt: Date = Date(),
        startImmediately: Bool = true,
        lineupTeamID: UUID? = nil,
        scheduledGameID: UUID? = nil,
        initialLineup: [LineupAssignment] = []
    ) {
        self.opponent = opponent
        self.isHome = isHome
        self.rules = rules
        self.scheduledAt = scheduledAt
        self.startImmediately = startImmediately
        self.lineupTeamID = lineupTeamID
        self.scheduledGameID = scheduledGameID
        _assignments = State(initialValue: initialLineup)
    }

    init(opponent: Team, isHome: Bool, innings: Int) {
        self.init(
            opponent: opponent,
            isHome: isHome,
            rules: GameRules(scheduledInnings: innings),
            scheduledAt: Date(),
            startImmediately: true
        )
    }

    private var lineupTeam: Team {
        lineupTeamID.flatMap(store.team(withID:)) ?? store.currentTeam
    }

    private var benchPlayers: [Player] {
        lineupTeam.players.filter { player in
            !assignments.contains(where: { $0.playerID == player.id })
        }
    }

    private var designatedHitter: Player? {
        if rules.twoWayPlayerEnabled,
           let pitcherAssignment = assignments.first(where: { $0.position == .pitcher }) {
            return lineupTeam.players.first(where: { $0.id == pitcherAssignment.playerID })
        }
        return rules.designatedHitterEnabled ? benchPlayers.first : nil
    }

    private var validationMessages: [String] {
        var messages: [String] = []
        if assignments.count != rules.fieldersCount {
            messages.append("需要选择 \(rules.fieldersCount) 名先发，当前为 \(assignments.count) 名")
        }
        if Set(assignments.map(\.playerID)).count != assignments.count {
            messages.append("同一名球员不能重复进入打序")
        }
        if Set(assignments.map(\.position)).count != assignments.count {
            messages.append("同一个守备位置不能安排两名先发")
        }
        if rules.fieldersCount >= 2 && !assignments.contains(where: { $0.position == .pitcher }) {
            messages.append("先发阵容必须包含投手")
        }
        if rules.fieldersCount >= 2 && !assignments.contains(where: { $0.position == .catcher }) {
            messages.append("先发阵容必须包含捕手")
        }
        if rules.designatedHitterEnabled && designatedHitter == nil {
            messages.append("DH 赛制需要指定打者；非大谷条款时名单需比守备人数多 1 人")
        }
        return messages
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("打序与本场守位")
                                .font(.system(size: 21, weight: .black))
                                .foregroundStyle(BMTheme.navy)
                            Text("长按球员行拖动棒次，点击守位可更改本场位置")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                        Spacer()
                        Text("\(assignments.count)/\(rules.fieldersCount)")
                            .font(.system(size: 17, weight: .black, design: .rounded))
                            .foregroundStyle(assignments.count == rules.fieldersCount ? BMTheme.green : BMTheme.orange)
                    }

                    HStack(spacing: 10) {
                        Button("沿用上一场") { reusePreviousLineup() }
                            .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                            .accessibilityIdentifier("reuse-previous-lineup")
                        Button("恢复默认") { assignments = defaultAssignments() }
                            .buttonStyle(SecondaryButtonStyle(color: BMTheme.orange))
                    }

                    SectionHeader(title: "先发与打序", subtitle: "守位不可重复")
                    LazyVStack(spacing: 9) {
                        ForEach(Array(assignments.enumerated()), id: \.element.playerID) { index, assignment in
                            if let player = lineupTeam.players.first(where: { $0.id == assignment.playerID }) {
                                HStack(spacing: 10) {
                                    Image(systemName: "line.3.horizontal")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(BMTheme.secondaryText)
                                        .accessibilityLabel("拖动\(player.name)调整棒次")
                                        .accessibilityIdentifier("lineup-drag-\(player.name)")
                                        .onDrag {
                                            draggingPlayerID = assignment.playerID
                                            return NSItemProvider(object: assignment.playerID.uuidString as NSString)
                                        }
                                    Text("\(index + 1)")
                                        .font(.system(size: 15, weight: .black, design: .rounded))
                                        .foregroundStyle(.white)
                                        .frame(width: 30, height: 30)
                                        .background(BMTheme.green)
                                        .clipShape(Circle())
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(player.name)
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundStyle(BMTheme.navy)
                                        Text(player.numbersText)
                                            .font(.system(size: 11))
                                            .foregroundStyle(BMTheme.secondaryText)
                                    }
                                    Spacer()
                                    Menu {
                                        ForEach(FieldPosition.allCases) { position in
                                            Button(position.fullName) {
                                                assignments[index].position = position
                                            }
                                        }
                                    } label: {
                                        Text(assignment.position.shortName)
                                            .font(.system(size: 13, weight: .black))
                                            .foregroundStyle(BMTheme.green)
                                            .padding(.horizontal, 9)
                                            .padding(.vertical, 7)
                                            .background(BMTheme.greenSoft)
                                            .clipShape(Capsule())
                                    }
                                    Button { move(index, offset: -1) } label: {
                                        Image(systemName: "chevron.up")
                                    }
                                    .disabled(index == 0)
                                    Button { move(index, offset: 1) } label: {
                                        Image(systemName: "chevron.down")
                                    }
                                    .disabled(index == assignments.count - 1)
                                    Button(role: .destructive) { remove(at: index) } label: {
                                        Image(systemName: "xmark.circle.fill")
                                    }
                                }
                                .buttonStyle(.borderless)
                                .padding(12)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                                .opacity(draggingPlayerID == assignment.playerID ? 0.55 : 1)
                                .onDrop(
                                    of: [UTType.text],
                                    delegate: LineupDropDelegate(
                                        targetID: assignment.playerID,
                                        assignments: $assignments,
                                        draggingPlayerID: $draggingPlayerID
                                    )
                                )
                            }
                        }
                    }

                    SectionHeader(title: "替补球员", subtitle: "\(benchPlayers.count) 人")
                    if benchPlayers.isEmpty {
                        BMCard {
                            Text("所有球员都已进入先发阵容")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                                .frame(maxWidth: .infinity, minHeight: 55)
                        }
                    } else {
                        LazyVStack(spacing: 9) {
                            ForEach(benchPlayers) { player in
                                Button {
                                    addStarter(player)
                                } label: {
                                    HStack {
                                        PlayerRow(player: player)
                                        Image(systemName: assignments.count < rules.fieldersCount ? "plus.circle.fill" : "person.crop.circle.badge.checkmark")
                                            .font(.system(size: 21))
                                            .foregroundStyle(assignments.count < rules.fieldersCount ? BMTheme.green : BMTheme.secondaryText)
                                    }
                                    .padding(13)
                                    .background(BMTheme.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                }
                                .buttonStyle(.plain)
                                .disabled(assignments.count >= rules.fieldersCount)
                            }
                        }
                    }

                    if rules.designatedHitterEnabled, let designatedHitter {
                        BMCard {
                            HStack(spacing: 12) {
                                Image(systemName: "figure.baseball")
                                    .foregroundStyle(BMTheme.green)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(rules.twoWayPlayerEnabled ? "投手兼任 DH（大谷条款）" : "指定打击 DH")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(BMTheme.navy)
                                    Text("#\(designatedHitter.number) \(designatedHitter.name) · 实际进入打序，投手保留守备身份")
                                        .font(.system(size: 12))
                                        .foregroundStyle(BMTheme.secondaryText)
                                }
                            }
                        }
                    }

                    if !validationMessages.isEmpty {
                        BMCard {
                            VStack(alignment: .leading, spacing: 7) {
                                ForEach(validationMessages, id: \.self) { message in
                                    Label(message, systemImage: "exclamationmark.triangle.fill")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(BMTheme.orange)
                                }
                            }
                        }
                    }
                }
                .padding(BMTheme.horizontalPadding)
            }

            Button(startImmediately ? "检查阵容并开始比赛" : "检查阵容并创建比赛") {
                showConfirmation = true
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!validationMessages.isEmpty)
            .opacity(validationMessages.isEmpty ? 1 : 0.45)
            .accessibilityIdentifier("confirm-lineup")
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 12)
            .background(BMTheme.background)
        }
        .bmScreenBackground()
        .navigationTitle("选择先发")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            let availableIDs = Set(lineupTeam.players.map(\.id))
            assignments.removeAll { !availableIDs.contains($0.playerID) }
            normalizeBattingOrder()
            if assignments.isEmpty { assignments = defaultAssignments() }
        }
        .alert(startImmediately ? "确认开始比赛？" : "确认创建未来比赛？", isPresented: $showConfirmation) {
            Button("取消", role: .cancel) {}
            Button(startImmediately ? "开始比赛" : "创建比赛") {
                if let scheduledGameID {
                    showGame = store.startScheduledGame(
                        id: scheduledGameID,
                        rules: rules,
                        lineup: assignments
                    )
                } else {
                    _ = store.startNewGame(
                        opponent: opponent,
                        isHome: isHome,
                        rules: rules,
                        lineup: assignments,
                        scheduledAt: scheduledAt,
                        startImmediately: startImmediately
                    )
                    if startImmediately {
                        showGame = true
                    } else {
                        showScheduledCreated = true
                    }
                }
            }
        } message: {
            Text(confirmationSummary)
        }
        .alert("无法沿用", isPresented: Binding(
            get: { historyMessage != nil },
            set: { if !$0 { historyMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { historyMessage = nil }
        } message: {
            Text(historyMessage ?? "")
        }
        .alert("比赛已创建", isPresented: $showScheduledCreated) {
            Button("返回比赛首页") { store.returnToGameHome() }
        } message: {
            Text("比赛将在 \(scheduledAt.formatted(date: .abbreviated, time: .shortened)) 显示于“即将进行”，届时可手动开始记录。")
        }
        .navigationDestination(isPresented: $showGame) {
            ScorekeepingView()
                .navigationBarBackButtonHidden(true)
        }
    }

    private var confirmationSummary: String {
        var parts = [
            "\(lineupTeam.shortName) 对 \(opponent.shortName)",
            isHome ? "本队为主队、后攻" : "本队为客队、先攻",
            "\(rules.scheduledInnings) 局，\(rules.fieldersCount) 人守备"
        ]
        if !startImmediately {
            parts.append("开赛：\(scheduledAt.formatted(date: .abbreviated, time: .shortened))")
        }
        if let minutes = rules.timeLimitMinutes {
            parts.append("\(minutes) 分钟限制，剩余 \(rules.timeWarningMinutes ?? 0) 分钟提醒")
        }
        if let pitches = rules.pitchLimit {
            parts.append("单投手 \(pitches) 球限制，剩余 \(rules.pitchWarningRemaining ?? 0) 球提醒")
        }
        if let innings = rules.pitcherInningsLimit {
            parts.append("单投手最多 \(innings) 局")
        }
        if rules.designatedHitterEnabled {
            parts.append(rules.twoWayPlayerEnabled ? "启用大谷条款" : "启用指定打击 DH")
        }
        return parts.joined(separator: "\n")
    }

    private func defaultAssignments() -> [LineupAssignment] {
        Array(lineupTeam.players.prefix(rules.fieldersCount)).enumerated().map { index, player in
            LineupAssignment(
                playerID: player.id,
                battingOrder: index + 1,
                position: FieldPosition.allCases[index]
            )
        }
    }

    private func reusePreviousLineup() {
        guard let previous = store.previousLineup(for: lineupTeam.id, excluding: scheduledGameID) else {
            historyMessage = "这支球队还没有可沿用的历史阵容。"
            return
        }
        let availableIDs = Set(lineupTeam.players.map(\.id))
        let retained = previous
            .filter { availableIDs.contains($0.playerID) }
            .prefix(rules.fieldersCount)
        guard retained.count == rules.fieldersCount else {
            historyMessage = "上一场的部分球员已不在当前名单中，请重新选择。"
            return
        }
        assignments = retained.enumerated().map { index, assignment in
            LineupAssignment(playerID: assignment.playerID, battingOrder: index + 1, position: assignment.position)
        }
    }

    private func addStarter(_ player: Player) {
        guard assignments.count < rules.fieldersCount else { return }
        let usedPositions = Set(assignments.map(\.position))
        let position = FieldPosition.allCases.first(where: { !usedPositions.contains($0) }) ?? .rightField
        assignments.append(LineupAssignment(playerID: player.id, battingOrder: assignments.count + 1, position: position))
    }

    private func remove(at index: Int) {
        assignments.remove(at: index)
        normalizeBattingOrder()
    }

    private func move(_ index: Int, offset: Int) {
        let destination = index + offset
        guard assignments.indices.contains(index), assignments.indices.contains(destination) else { return }
        assignments.swapAt(index, destination)
        normalizeBattingOrder()
    }

    private func normalizeBattingOrder() {
        for index in assignments.indices { assignments[index].battingOrder = index + 1 }
    }
}

struct ObservedGameLineupView: View {
    @EnvironmentObject private var store: GameStore
    let awayTeam: Team
    let homeTeam: Team
    let rules: GameRules
    let scheduledAt: Date
    let startImmediately: Bool
    let scheduledGameID: UUID?

    @State private var showConfirmation = false
    @State private var showGame = false
    @State private var showScheduledCreated = false
    @State private var awayAssignments: [LineupAssignment]
    @State private var homeAssignments: [LineupAssignment]

    init(
        awayTeam: Team,
        homeTeam: Team,
        rules: GameRules,
        scheduledAt: Date = Date(),
        startImmediately: Bool = true,
        scheduledGameID: UUID? = nil,
        initialAwayLineup: [LineupAssignment] = [],
        initialHomeLineup: [LineupAssignment] = []
    ) {
        self.awayTeam = awayTeam
        self.homeTeam = homeTeam
        self.rules = rules
        self.scheduledAt = scheduledAt
        self.startImmediately = startImmediately
        self.scheduledGameID = scheduledGameID
        func preparedLineup(_ initial: [LineupAssignment], for team: Team) -> [LineupAssignment] {
            let playerIDs = Set(team.players.map(\.id))
            var seen = Set<UUID>()
            var result = initial.filter { playerIDs.contains($0.playerID) && seen.insert($0.playerID).inserted }
            for player in team.players where result.count < rules.fieldersCount && !seen.contains(player.id) {
                let usedPositions = Set(result.map(\.position))
                let position = FieldPosition.allCases.first(where: { !usedPositions.contains($0) }) ?? .rightField
                result.append(LineupAssignment(playerID: player.id, battingOrder: result.count + 1, position: position))
                seen.insert(player.id)
            }
            result = Array(result.prefix(rules.fieldersCount))
            for index in result.indices { result[index].battingOrder = index + 1 }
            return result
        }
        _awayAssignments = State(initialValue: preparedLineup(initialAwayLineup, for: awayTeam))
        _homeAssignments = State(initialValue: preparedLineup(initialHomeLineup, for: homeTeam))
    }

    private var validationMessages: [String] {
        lineupErrors(assignments: awayAssignments, role: "客队")
            + lineupErrors(assignments: homeAssignments, role: "主队")
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    BMCard {
                        VStack(alignment: .leading, spacing: 7) {
                            Label("观赛记录", systemImage: "eye.fill")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(BMTheme.green)
                            Text("双方都不会被计为本方球队；可在这里确认双方棒次、守位和先发球员，记录过程中仍可换人和调整守位。")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                    editableObservedLineup(team: awayTeam, role: "客队 · 先攻", assignments: $awayAssignments)
                    editableObservedLineup(team: homeTeam, role: "主队 · 后攻", assignments: $homeAssignments)

                    if !validationMessages.isEmpty {
                        BMCard {
                            VStack(alignment: .leading, spacing: 7) {
                                ForEach(validationMessages, id: \.self) { message in
                                    Label(message, systemImage: "exclamationmark.triangle.fill")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(BMTheme.orange)
                                }
                            }
                        }
                    }
                }
                .padding(BMTheme.horizontalPadding)
            }

            Button(startImmediately ? "确认双方阵容并开始记录" : "确认双方阵容并创建比赛") {
                showConfirmation = true
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!validationMessages.isEmpty)
            .opacity(validationMessages.isEmpty ? 1 : 0.45)
            .accessibilityIdentifier("confirm-observed-lineup")
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 12)
            .background(BMTheme.background)
        }
        .bmScreenBackground()
        .navigationTitle("观赛阵容")
        .navigationBarTitleDisplayMode(.inline)
        .alert(startImmediately ? "开始观赛记录？" : "创建未来观赛？", isPresented: $showConfirmation) {
            Button("取消", role: .cancel) {}
            Button(startImmediately ? "开始记录" : "创建比赛") {
                if let scheduledGameID {
                    showGame = store.startScheduledObservedGame(
                        id: scheduledGameID,
                        rules: rules,
                        awayLineup: awayAssignments,
                        homeLineup: homeAssignments
                    )
                } else {
                    _ = store.createObservedGame(
                        awayTeam: awayTeam,
                        homeTeam: homeTeam,
                        rules: rules,
                        awayLineup: awayAssignments,
                        homeLineup: homeAssignments,
                        scheduledAt: scheduledAt,
                        startImmediately: startImmediately
                    )
                    if startImmediately { showGame = true } else { showScheduledCreated = true }
                }
            }
        } message: {
            Text(confirmationSummary)
        }
        .alert("比赛已创建", isPresented: $showScheduledCreated) {
            Button("返回比赛首页") { store.returnToGameHome() }
        } message: {
            Text("未来观赛已保存，可从比赛首页的“即将进行”中开始。")
        }
        .navigationDestination(isPresented: $showGame) {
            ScorekeepingView()
                .navigationBarBackButtonHidden(true)
        }
    }

    private func editableObservedLineup(
        team: Team,
        role: String,
        assignments: Binding<[LineupAssignment]>
    ) -> some View {
        let selectedIDs = Set(assignments.wrappedValue.map(\.playerID))
        let bench = team.players.filter { !selectedIDs.contains($0.id) }
        return VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: role, subtitle: team.name)
            ForEach(Array(assignments.wrappedValue.enumerated()), id: \.element.playerID) { index, assignment in
                if let player = team.players.first(where: { $0.id == assignment.playerID }) {
                    HStack(spacing: 8) {
                        Text("\(index + 1)")
                            .font(.system(size: 14, weight: .black, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(width: 29, height: 29)
                            .background(BMTheme.green)
                            .clipShape(Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(player.name)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Text(player.numbersText)
                                .font(.system(size: 11))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                        Spacer()
                        if !bench.isEmpty {
                            Menu {
                                ForEach(bench) { replacement in
                                    Button(replacement.name) {
                                        assignments.wrappedValue[index].playerID = replacement.id
                                    }
                                }
                            } label: {
                                Image(systemName: "person.2.badge.gearshape.fill")
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                        }
                        Menu {
                            ForEach(FieldPosition.allCases) { position in
                                Button(position.fullName) {
                                    assignments.wrappedValue[index].position = position
                                }
                            }
                        } label: {
                            Text(assignment.position.shortName)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(BMTheme.green)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .background(BMTheme.greenSoft)
                                .clipShape(Capsule())
                        }
                        Button {
                            moveObservedLineup(assignments, index: index, offset: -1)
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .disabled(index == 0)
                        Button {
                            moveObservedLineup(assignments, index: index, offset: 1)
                        } label: {
                            Image(systemName: "chevron.down")
                        }
                        .disabled(index == assignments.wrappedValue.count - 1)
                    }
                    .buttonStyle(.borderless)
                    .padding(12)
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                }
            }
        }
    }

    private func moveObservedLineup(
        _ assignments: Binding<[LineupAssignment]>,
        index: Int,
        offset: Int
    ) {
        let destination = index + offset
        guard assignments.wrappedValue.indices.contains(index),
              assignments.wrappedValue.indices.contains(destination) else { return }
        assignments.wrappedValue.swapAt(index, destination)
        for position in assignments.wrappedValue.indices {
            assignments.wrappedValue[position].battingOrder = position + 1
        }
    }

    private func lineupErrors(assignments: [LineupAssignment], role: String) -> [String] {
        var messages: [String] = []
        if assignments.count != rules.fieldersCount {
            messages.append("\(role)需要选择 \(rules.fieldersCount) 名先发")
        }
        if Set(assignments.map(\.playerID)).count != assignments.count {
            messages.append("\(role)先发球员不能重复")
        }
        if Set(assignments.map(\.position)).count != assignments.count {
            messages.append("\(role)守备位置不能重复")
        }
        if !assignments.contains(where: { $0.position == .pitcher }) {
            messages.append("\(role)必须包含投手")
        }
        if !assignments.contains(where: { $0.position == .catcher }) {
            messages.append("\(role)必须包含捕手")
        }
        return messages
    }

    private var confirmationSummary: String {
        var text = "\(awayTeam.shortName)（客队）对 \(homeTeam.shortName)（主队）\n\(rules.scheduledInnings) 局，\(rules.fieldersCount) 人守备"
        if !startImmediately {
            text += "\n开赛：\(scheduledAt.formatted(date: .abbreviated, time: .shortened))"
        }
        return text
    }
}

private struct LineupDropDelegate: DropDelegate {
    let targetID: UUID
    @Binding var assignments: [LineupAssignment]
    @Binding var draggingPlayerID: UUID?

    func dropEntered(info: DropInfo) {
        guard let draggingPlayerID,
              draggingPlayerID != targetID,
              let sourceIndex = assignments.firstIndex(where: { $0.playerID == draggingPlayerID }),
              let targetIndex = assignments.firstIndex(where: { $0.playerID == targetID }) else { return }
        withAnimation(.easeInOut(duration: 0.18)) {
            assignments.move(
                fromOffsets: IndexSet(integer: sourceIndex),
                toOffset: targetIndex > sourceIndex ? targetIndex + 1 : targetIndex
            )
            for index in assignments.indices {
                assignments[index].battingOrder = index + 1
            }
        }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        draggingPlayerID = nil
        return true
    }

    func dropExited(info: DropInfo) {}
}

#Preview("球队名单") {
    NavigationStack { TeamRosterView() }
        .environmentObject(GameStore())
}

#Preview("比赛设置") {
    NavigationStack { NewGameSetupView() }
        .environmentObject(GameStore())
}

#Preview("先发选择") {
    let store = GameStore()
    NavigationStack {
        LineupSelectionView(opponent: store.opponentTeams[0], isHome: false, innings: 6)
    }
    .environmentObject(store)
}
