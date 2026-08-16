import SwiftUI

struct TeamRosterView: View {
    @EnvironmentObject private var store: MockGameStore
    @State private var showAddPlayer = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 13) {
                    TeamMark(team: store.currentTeam, size: 58)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(store.currentTeam.name)
                            .font(.system(size: 23, weight: .black))
                            .foregroundStyle(BMTheme.navy)
                        Text("2026 夏季 · \(store.currentTeam.players.count) 名球员")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    Spacer()
                }

                SectionHeader(title: "球队名单", subtitle: "点击查看球员")

                LazyVStack(spacing: 10) {
                    ForEach(store.currentTeam.players) { player in
                        NavigationLink(destination: PlayerDetailView(player: player)) {
                            PlayerRow(player: player)
                                .padding(14)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                        }
                        .buttonStyle(.plain)
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
                    showAddPlayer = true
                } label: {
                    Image(systemName: "person.badge.plus")
                }
                .accessibilityLabel("添加球员")
            }
        }
        .sheet(isPresented: $showAddPlayer) {
            AddPlayerSheet()
                .environmentObject(store)
        }
    }
}

struct AddPlayerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: MockGameStore
    @State private var name = ""
    @State private var number = ""
    @State private var position: FieldPosition = .pitcher

    var body: some View {
        NavigationStack {
            Form {
                Section("基本资料") {
                    TextField("姓名或昵称", text: $name)
                    TextField("背号", text: $number)
                        .keyboardType(.numberPad)
                    Picker("常用守位", selection: $position) {
                        ForEach(FieldPosition.allCases) { position in
                            Text(position.fullName).tag(position)
                        }
                    }
                }
                Section {
                    Text("只有创建名单时需要输入文字；比赛过程中全部使用点选。")
                        .font(.footnote)
                        .foregroundStyle(BMTheme.secondaryText)
                }
            }
            .navigationTitle("添加球员")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        store.addPlayer(name: name, number: Int(number) ?? 0, position: position)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || Int(number) == nil)
                }
            }
        }
    }
}

struct NewGameSetupView: View {
    @EnvironmentObject private var store: MockGameStore
    @State private var opponentIndex = 1
    @State private var isHome = false
    @State private var innings = 6
    @State private var generatedOpponent = false

    private var opponent: Team { store.teams[min(opponentIndex, store.teams.count - 1)] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                SectionHeader(title: "比赛对手", subtitle: "第 1 步，共 2 步")
                BMCard {
                    VStack(spacing: 14) {
                        HStack(spacing: 12) {
                            TeamMark(team: opponent, size: 46)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(opponent.name)
                                    .font(.system(size: 17, weight: .bold))
                                    .foregroundStyle(BMTheme.navy)
                                Text(generatedOpponent ? "已生成 9 名背号占位球员" : "已有球队 · 9 名球员")
                                    .font(.system(size: 12))
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            Spacer()
                        }

                        Button {
                            generatedOpponent = true
                        } label: {
                            Label(generatedOpponent ? "已生成对手打序" : "一键生成 9 名对手球员", systemImage: "wand.and.stars")
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))
                    }
                }

                SectionHeader(title: "比赛设置")
                BMCard {
                    VStack(spacing: 0) {
                        settingPickerRow(title: "本队身份", icon: "house.fill") {
                            Picker("本队身份", selection: $isHome) {
                                Text("客队 · 先攻").tag(false)
                                Text("主队 · 后攻").tag(true)
                            }
                            .pickerStyle(.menu)
                        }

                        Divider().padding(.leading, 42)

                        settingPickerRow(title: "比赛局数", icon: "number.circle.fill") {
                            Picker("比赛局数", selection: $innings) {
                                Text("6 局").tag(6)
                                Text("7 局").tag(7)
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 140)
                        }
                    }
                }

                NavigationLink(destination: LineupSelectionView(opponent: opponent, isHome: isHome, innings: innings)) {
                    HStack {
                        Text("下一步：选择先发")
                        Spacer()
                        Image(systemName: "arrow.right")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("新比赛")
        .navigationBarTitleDisplayMode(.inline)
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

struct LineupSelectionView: View {
    @EnvironmentObject private var store: MockGameStore
    let opponent: Team
    let isHome: Bool
    let innings: Int

    @State private var selectedIDs: [UUID] = []
    @State private var showGame = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("依次点击球员")
                                .font(.system(size: 21, weight: .black))
                                .foregroundStyle(BMTheme.navy)
                            Text("点击顺序就是打序，不需要拖动")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                        Spacer()
                        Text("\(selectedIDs.count)/9")
                            .font(.system(size: 17, weight: .black, design: .rounded))
                            .foregroundStyle(selectedIDs.count == 9 ? BMTheme.green : BMTheme.orange)
                    }

                    HStack(spacing: 10) {
                        Button("沿用上一场") {
                            selectedIDs = Array(store.currentTeam.players.prefix(9).map(\.id))
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))

                        Button("清空") {
                            selectedIDs.removeAll()
                        }
                        .buttonStyle(SecondaryButtonStyle(color: BMTheme.red))
                    }

                    LazyVStack(spacing: 9) {
                        ForEach(store.currentTeam.players) { player in
                            let order = selectedIDs.firstIndex(of: player.id).map { $0 + 1 }
                            Button {
                                toggle(player)
                            } label: {
                                HStack {
                                    PlayerRow(player: player, order: order)
                                    Image(systemName: order == nil ? "circle" : "checkmark.circle.fill")
                                        .font(.system(size: 22))
                                        .foregroundStyle(order == nil ? BMTheme.line : BMTheme.green)
                                }
                                .padding(14)
                                .background(BMTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 15))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 15)
                                        .stroke(order == nil ? BMTheme.line.opacity(0.5) : BMTheme.green.opacity(0.55), lineWidth: 1)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(BMTheme.horizontalPadding)
            }

            VStack(spacing: 8) {
                Button {
                    let lineup = selectedIDs.compactMap { id in
                        store.currentTeam.players.first(where: { $0.id == id })
                    }
                    store.startNewGame(opponent: opponent, isHome: isHome, innings: innings, lineup: lineup)
                    showGame = true
                } label: {
                    Text("确认先发，开始比赛")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(selectedIDs.count != 9)
                .opacity(selectedIDs.count == 9 ? 1 : 0.45)

                Text("对手打序已准备，可在比赛中随时调整")
                    .font(.system(size: 11))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 12)
            .background(BMTheme.background)
        }
        .bmScreenBackground()
        .navigationTitle("选择先发")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if selectedIDs.isEmpty {
                selectedIDs = Array(store.currentTeam.players.prefix(9).map(\.id))
            }
        }
        .navigationDestination(isPresented: $showGame) {
            ScorekeepingView()
                .navigationBarBackButtonHidden(true)
        }
    }

    private func toggle(_ player: Player) {
        if let index = selectedIDs.firstIndex(of: player.id) {
            selectedIDs.remove(at: index)
        } else if selectedIDs.count < 9 {
            selectedIDs.append(player.id)
        }
    }
}

#Preview("球队名单") {
    NavigationStack { TeamRosterView() }
        .environmentObject(MockGameStore())
}

#Preview("比赛设置") {
    NavigationStack { NewGameSetupView() }
        .environmentObject(MockGameStore())
}

#Preview("先发选择") {
    let store = MockGameStore()
    NavigationStack {
        LineupSelectionView(opponent: store.teams[1], isHome: false, innings: 6)
    }
    .environmentObject(store)
}
