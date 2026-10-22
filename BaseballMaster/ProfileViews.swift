import PDFKit
import SwiftUI

enum AppAppearance: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "跟随系统"
        case .light: "浅色"
        case .dark: "深色"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct ProfileView: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                identityCard

                SectionHeader(title: "学习与帮助")
                VStack(spacing: 10) {
                    NavigationLink(destination: BaseballRulesView()) {
                        ProfileMenuRow(
                            icon: "book.closed.fill",
                            title: "棒球规则查询",
                            subtitle: "常用规则解释与完整规则 PDF"
                        )
                    }
                    .accessibilityIdentifier("open-baseball-rules")

                    NavigationLink(destination: PracticeInningView()) {
                        ProfileMenuRow(
                            icon: "figure.baseball",
                            title: "练习一局",
                            subtitle: "独立练习，不影响正式比赛数据"
                        )
                    }
                    .accessibilityIdentifier("open-practice-inning")
                }

                SectionHeader(title: "本机数据与设置")
                VStack(spacing: 10) {
                    NavigationLink(destination: LocalDataManagementView()) {
                        ProfileMenuRow(
                            icon: "externaldrive.fill",
                            title: "本地数据",
                            subtitle: "查看存储范围与数据数量"
                        )
                    }
                    .accessibilityIdentifier("open-local-data")

                    NavigationLink(destination: AppSettingsView()) {
                        ProfileMenuRow(
                            icon: "gearshape.fill",
                            title: "App 设置",
                            subtitle: "外观与辅助提示"
                        )
                    }
                    .accessibilityIdentifier("open-app-settings")

                    NavigationLink(destination: AppAboutView()) {
                        ProfileMenuRow(
                            icon: "info.circle.fill",
                            title: "关于 BaseballMaster",
                            subtitle: "版本信息与数据说明"
                        )
                    }
                    .accessibilityIdentifier("open-app-about")
                }
            }
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 18)
        }
        .bmScreenBackground()
        .navigationTitle("我的")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var identityCard: some View {
        BMCard {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(BMTheme.greenSoft)
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(BMTheme.green)
                }
                .frame(width: 62, height: 62)

                VStack(alignment: .leading, spacing: 5) {
                    Text("本机记分员")
                        .font(.system(size: 21, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text("当前球队 · \(store.currentTeam.name)")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BMTheme.secondaryText)
                    Label("Core Data 本地保存", systemImage: "checkmark.shield.fill")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BMTheme.green)
                }
                Spacer()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("本机记分员，当前球队\(store.currentTeam.name)，数据保存在本机")
    }
}

private struct ProfileMenuRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(BMTheme.green)
                .frame(width: 42, height: 42)
                .background(BMTheme.greenSoft)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
        .padding(15)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 15))
        .overlay {
            RoundedRectangle(cornerRadius: 15)
                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
        }
    }
}

struct LocalDataManagementView: View {
    @EnvironmentObject private var store: GameStore

    private var playerCount: Int {
        store.teams.reduce(0) { $0 + $1.players.count }
    }

    private var opponentPlayerCount: Int {
        store.opponentTeams.reduce(0) { $0 + $1.players.count }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                BMCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("仅保存在这台设备", systemImage: "iphone.gen3")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                        Text("本队、对手球队、球员、赛季、未来比赛和现场记录都保存在 App 沙盒中的 Core Data SQLite 数据库，不需要登录，也不会上传到云端。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                SectionHeader(title: "当前数据")
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    DataMetricCard(title: "本队", value: store.teams.count, icon: "person.3.fill")
                    DataMetricCard(title: "对手", value: store.opponentTeams.count, icon: "shield.fill")
                    DataMetricCard(title: "本队球员", value: playerCount, icon: "person.fill")
                    DataMetricCard(title: "对手球员", value: opponentPlayerCount, icon: "person.2.fill")
                    DataMetricCard(title: "赛季", value: store.seasons.count, icon: "calendar")
                    DataMetricCard(title: "比赛记录", value: store.games.count, icon: "baseball.diamond.bases")
                    DataMetricCard(title: "球员统计", value: store.recordedPlayerGameCount, icon: "chart.bar.doc.horizontal")
                    DataMetricCard(title: "进行中", value: store.ongoingGames.count, icon: "play.circle.fill")
                }

                NavigationLink(destination: TeamRosterView()) {
                    Label("管理球队与球员", systemImage: "person.3.sequence.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("manage-roster-from-profile")

                NavigationLink(destination: OpponentTeamsView()) {
                    Label("管理对手球队与名单", systemImage: "shield.lefthalf.filled")
                }
                .buttonStyle(SecondaryButtonStyle(color: BMTheme.green))

                NavigationLink(destination: BackupManagementView()) {
                    Label("备份与恢复", systemImage: "externaldrive.badge.timemachine")
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("open-backup-management")
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("本地数据")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DataMetricCard: View {
    let title: String
    let value: Int
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(BMTheme.green)
            Text("\(value)")
                .font(.system(size: 28, weight: .black, design: .rounded))
                .foregroundStyle(BMTheme.navy)
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BMTheme.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
        .padding(15)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 15))
        .overlay {
            RoundedRectangle(cornerRadius: 15)
                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title)\(value)")
    }
}

struct AppSettingsView: View {
    @AppStorage("appAppearance") private var appearance = AppAppearance.system.rawValue
    @AppStorage("showPracticeGuidance") private var showPracticeGuidance = true
    @AppStorage("showRuleExamples") private var showRuleExamples = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                SectionHeader(title: "外观")
                BMCard {
                    Picker("App 外观", selection: $appearance) {
                        ForEach(AppAppearance.allCases) { option in
                            Text(option.title).tag(option.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("appearance-picker")
                }

                SectionHeader(title: "辅助提示")
                BMCard {
                    VStack(spacing: 0) {
                        settingsToggle(
                            title: "显示练习引导",
                            detail: "练习记分时显示下一步提示",
                            isOn: $showPracticeGuidance
                        )
                        Divider().padding(.vertical, 12)
                        settingsToggle(
                            title: "显示规则示例",
                            detail: "常用规则条目中显示简短场景",
                            isOn: $showRuleExamples
                        )
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("App 设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func settingsToggle(title: String, detail: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
        }
        .tint(BMTheme.green)
    }
}

struct AppAboutView: View {
    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "版本 \(version)（\(build)）"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                VStack(spacing: 10) {
                    Image(systemName: "baseball.diamond.bases.fill")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundStyle(BMTheme.green)
                    Text("BaseballMaster")
                        .font(.system(size: 25, weight: .black))
                        .foregroundStyle(BMTheme.navy)
                    Text(versionText)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                .padding(.vertical, 12)

                BMCard {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("为现场记分而设计")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                        Text("帮助非专业记分员用更直观的方式记录比赛，并查看球队、球员与赛季表现。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                }

                BMCard {
                    VStack(alignment: .leading, spacing: 9) {
                        Label("隐私与数据", systemImage: "lock.shield.fill")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                        Text("球队、球员、赛季和逐场统计保存在本机 Core Data 数据库中，不启用 CloudKit，也不要求用户账号。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                }

                Text("内置规则资料：《棒球规则 2022 版》，中国棒球协会审定。比赛判罚应以现场裁判及赛事最新规定为准。")
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
    }
}

enum BaseballRuleCategory: String, CaseIterable, Identifiable {
    case basics = "基础"
    case offense = "进攻"
    case defense = "守备"
    case pitching = "投手"
    case violations = "违规"
    case scoring = "记录"

    var id: String { rawValue }
}

private struct BaseballRuleItem: Identifiable {
    let category: BaseballRuleCategory
    let term: String
    let abbreviation: String
    let explanation: String
    let example: String

    var id: String { "\(category.rawValue)-\(term)" }
}

struct BaseballRulesView: View {
    @AppStorage("showRuleExamples") private var showRuleExamples = true
    @State private var selectedCategory = BaseballRuleCategory.basics

    private let items: [BaseballRuleItem] = [
        .init(category: .basics, term: "界内球", abbreviation: "Fair", explanation: "击出的球停留或经过界内区域，且未按规则成为界外球。", example: "滚地球从一垒垒包上方通过后进入外野。"),
        .init(category: .basics, term: "界外球", abbreviation: "Foul", explanation: "击出的球在界外区域停留，或按首次落点、触碰位置被判为界外。", example: "球在一垒前落入界内，随后滚出界外且未经过垒包。"),
        .init(category: .basics, term: "活球", abbreviation: "Live Ball", explanation: "比赛正在进行，球员可以依规则完成传球、跑垒和出局。", example: "裁判宣布开始比赛后，投手持球准备投球。"),
        .init(category: .basics, term: "死球", abbreviation: "Dead Ball", explanation: "比赛暂时停止，通常不能继续完成跑垒或制造出局。", example: "裁判叫暂停后，比赛进入死球状态。"),

        .init(category: .offense, term: "安打", abbreviation: "H", explanation: "击球员依靠有效击球安全到垒，且不是因为守备失误或野手选择。", example: "球穿过内野，打者安全到达一垒。"),
        .init(category: .offense, term: "四坏球", abbreviation: "BB", explanation: "击球员获得四个坏球后被判安全上一垒。", example: "垒上满垒时四坏球会迫使三垒跑者得分。"),
        .init(category: .offense, term: "触身球", abbreviation: "HBP", explanation: "投球触及击球员，符合条件时击球员获得一垒。", example: "击球员没有挥棒，投球击中其手臂。"),
        .init(category: .offense, term: "牺牲高飞", abbreviation: "SF", explanation: "两出局前的高飞球使打者出局，但跑者随后合法回本垒得分。", example: "三垒跑者在外野接杀后启动并得分。"),
        .init(category: .offense, term: "野手选择", abbreviation: "FC", explanation: "守备选择处理其他跑者，使打者安全上垒，通常不记安打。", example: "游击手传二垒封杀跑者，打者到达一垒。"),
        .init(category: .offense, term: "盗垒", abbreviation: "SB", explanation: "跑者在投球过程中主动推进，并依记录规则获得盗垒。", example: "一垒跑者在投手投球时成功进入二垒。"),

        .init(category: .defense, term: "刺杀", abbreviation: "PO", explanation: "守备员直接完成出局时取得的守备记录。", example: "一垒手接球并踩垒，使击跑员出局。"),
        .init(category: .defense, term: "助杀", abbreviation: "A", explanation: "守备员传球或处理球，协助队友完成出局。", example: "游击手接滚地球后传给一垒手完成出局。"),
        .init(category: .defense, term: "失误", abbreviation: "E", explanation: "守备员以正常努力本可完成处理，却因失误让进攻方获益。", example: "守备员漏接普通滚地球，打者安全上垒。"),
        .init(category: .defense, term: "双杀", abbreviation: "DP", explanation: "同一连续攻守过程中，守备方使两名进攻球员出局。", example: "游击手传二垒封杀跑者，再传一垒封杀打者。"),
        .init(category: .defense, term: "内野高飞", abbreviation: "IFF", explanation: "满足特定垒况和出局数时，普通努力可接住的内野高飞球会提前宣判打者出局。", example: "一、二垒有人且少于两出局时出现容易接住的内野高飞球。"),

        .init(category: .pitching, term: "三振", abbreviation: "K", explanation: "击球员累计三个好球后出局；三振未接住等情况可能允许其尝试上一垒。", example: "两好球后挥棒落空，捕手稳稳接住投球。"),
        .init(category: .pitching, term: "暴投", abbreviation: "WP", explanation: "投球过高、过低或过偏，捕手以正常努力无法接住并导致跑者推进。", example: "投球落地弹离捕手，二垒跑者进入三垒。"),
        .init(category: .pitching, term: "捕逸", abbreviation: "PB", explanation: "捕手以正常努力本应接住投球，却未能控制并导致跑者推进。", example: "普通投球从捕手手套弹开，跑者进垒。"),
        .init(category: .pitching, term: "投手犯规", abbreviation: "BK", explanation: "垒上有跑者时，投手做出规则禁止的投球相关动作，通常判跑者推进一垒。", example: "投手开始投球动作后无正当理由中止。"),
        .init(category: .pitching, term: "自责分", abbreviation: "ER", explanation: "在不考虑守备失误和捕逸影响的情况下，由投手责任造成的得分。", example: "连续安打带回的得分通常计入投手自责分。"),

        .init(category: .violations, term: "妨碍", abbreviation: "Interference", explanation: "进攻球员、教练或其他人员干扰守备方处理球，裁判会按具体规则判罚。", example: "跑者故意阻挡守备员接正在飞行的击球。"),
        .init(category: .violations, term: "阻挡", abbreviation: "Obstruction", explanation: "未持球且并非正在处理击出球的守备员阻碍跑者前进。", example: "守备员无球站在跑垒路径上，迫使跑者减速。"),
        .init(category: .violations, term: "打击次序错误", abbreviation: "BOO", explanation: "未按正式打序击球时，防守方可在规定时机提出申诉。", example: "第三棒应上场时第四棒先完成了打席。"),
        .init(category: .violations, term: "申诉行为", abbreviation: "Appeal", explanation: "守备方针对漏踏垒、过早离垒或打序错误等情况向裁判提出判罚请求。", example: "外野接杀后，守备方申诉三垒跑者过早离垒。"),

        .init(category: .scoring, term: "打率", abbreviation: "AVG", explanation: "安打数除以打数，用于描述击球员取得安打的比例。", example: "10 个打数击出 3 支安打，打率为 .300。"),
        .init(category: .scoring, term: "上垒率", abbreviation: "OBP", explanation: "衡量击球员通过安打、保送和触身球等方式上垒的比例。", example: "保送虽然不是安打，但会提高上垒率。"),
        .init(category: .scoring, term: "长打率", abbreviation: "SLG", explanation: "垒打数除以打数，用不同垒打价值反映击球威力。", example: "二垒打按两个垒打数计算。"),
        .init(category: .scoring, term: "综合攻击指数", abbreviation: "OPS", explanation: "上垒率与长打率之和，用于概括上垒和长打贡献。", example: "OBP .380 加 SLG .450，OPS 为 .830。"),
        .init(category: .scoring, term: "打点", abbreviation: "RBI", explanation: "击球员的打击行为使队友或自己得分时，依记录规则获得的统计。", example: "满垒时击出二垒打带回两名跑者。")
    ]

    private var visibleItems: [BaseballRuleItem] {
        items.filter { $0.category == selectedCategory }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                NavigationLink(destination: BundledRulePDFView()) {
                    HStack(spacing: 14) {
                        Image(systemName: "doc.richtext.fill")
                            .font(.system(size: 27, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 54, height: 54)
                            .background(BMTheme.green)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("查看完整《棒球规则 2022 版》")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Text("中国棒球协会审定 · 229 页 · 离线阅读")
                                .font(.system(size: 12))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(BMTheme.secondaryText)
                    }
                    .padding(15)
                    .background(BMTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(BMTheme.green.opacity(0.3), lineWidth: 1)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("open-rule-pdf")

                Text("以下为便于现场理解的简化说明；正式比赛以现场裁判、赛事规程和最新规则为准。")
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)

                SectionHeader(title: "常用规则与术语", subtitle: "\(visibleItems.count) 项")

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(BaseballRuleCategory.allCases) { category in
                            Button(category.rawValue) {
                                selectedCategory = category
                            }
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(selectedCategory == category ? Color.white : BMTheme.navy)
                            .padding(.horizontal, 15)
                            .frame(minHeight: 38)
                            .background(selectedCategory == category ? BMTheme.green : BMTheme.surface)
                            .clipShape(Capsule())
                            .overlay {
                                Capsule().stroke(BMTheme.line.opacity(selectedCategory == category ? 0 : 1), lineWidth: 1)
                            }
                            .accessibilityIdentifier("rule-category-\(category.rawValue)")
                        }
                    }
                }

                ForEach(visibleItems) { item in
                    RuleItemCard(item: item, showExample: showRuleExamples)
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("棒球规则查询")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct RuleItemCard: View {
    let item: BaseballRuleItem
    let showExample: Bool

    var body: some View {
        BMCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(item.term)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text(item.abbreviation)
                        .font(.system(size: 11, weight: .black, design: .rounded))
                        .foregroundStyle(BMTheme.green)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(BMTheme.greenSoft)
                        .clipShape(Capsule())
                    Spacer()
                }
                Text(item.explanation)
                    .font(.system(size: 14))
                    .foregroundStyle(BMTheme.navy)
                    .fixedSize(horizontal: false, vertical: true)
                if showExample {
                    Text("例：\(item.example)")
                        .font(.system(size: 13))
                        .foregroundStyle(BMTheme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct BundledRulePDFView: View {
    private let resourceURL = Bundle.main.url(forResource: "BaseballRules2022", withExtension: "pdf")
    @State private var searchText: String
    @State private var submittedQuery: String
    @State private var selectedResultIndex = 0
    @State private var resultCount = 0
    @FocusState private var searchFieldFocused: Bool

    init(initialQuery: String = "") {
        _searchText = State(initialValue: initialQuery)
        _submittedQuery = State(initialValue: initialQuery)
    }

    var body: some View {
        Group {
            if let resourceURL {
                VStack(spacing: 0) {
                    pdfSearchBar
                    Divider()
                    RulePDFKitView(
                        url: resourceURL,
                        query: submittedQuery,
                        selectedIndex: selectedResultIndex,
                        resultCount: $resultCount
                    )
                    .accessibilityIdentifier("rule-pdf-reader")
                }
            } else {
                SimpleEmptyState(
                    title: "无法打开规则文件",
                    systemImage: "doc.badge.exclamationmark",
                    message: "内置 PDF 文件缺失，请重新安装 App。"
                )
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("棒球规则 2022")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Text("229 页")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BMTheme.secondaryText)
                    .accessibilityIdentifier("rule-pdf-page-count")
            }
        }
    }

    private var pdfSearchBar: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(BMTheme.secondaryText)
                TextField("输入单字或关键词", text: $searchText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($searchFieldFocused)
                    .onSubmit(performSearch)
                    .accessibilityIdentifier("rule-pdf-search-field")

                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                        submittedQuery = ""
                        resultCount = 0
                        selectedResultIndex = 0
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .foregroundStyle(BMTheme.secondaryText)
                    .accessibilityLabel("清除查找")
                }

                Button("查找", action: performSearch)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BMTheme.green)
                    .disabled(searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("rule-pdf-search-button")
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 42)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 11))

            if !submittedQuery.isEmpty {
                HStack {
                    Text(resultCount == 0 ? "未找到“\(submittedQuery)”" : "第 \(selectedResultIndex + 1) / \(resultCount) 处")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(resultCount == 0 ? BMTheme.red : BMTheme.secondaryText)
                        .accessibilityIdentifier("rule-pdf-search-status")
                    Spacer()
                    Button {
                        guard resultCount > 0 else { return }
                        selectedResultIndex = (selectedResultIndex - 1 + resultCount) % resultCount
                    } label: {
                        Image(systemName: "chevron.up")
                            .frame(width: 34, height: 28)
                    }
                    .disabled(resultCount == 0)
                    .accessibilityLabel("上一个匹配")
                    .accessibilityIdentifier("rule-pdf-previous-match")

                    Button {
                        guard resultCount > 0 else { return }
                        selectedResultIndex = (selectedResultIndex + 1) % resultCount
                    } label: {
                        Image(systemName: "chevron.down")
                            .frame(width: 34, height: 28)
                    }
                    .disabled(resultCount == 0)
                    .accessibilityLabel("下一个匹配")
                    .accessibilityIdentifier("rule-pdf-next-match")
                }
                .foregroundStyle(BMTheme.green)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Color(uiColor: .systemBackground))
    }

    private func performSearch() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        selectedResultIndex = 0
        resultCount = 0
        submittedQuery = query
        searchFieldFocused = false
    }
}

private struct RulePDFKitView: UIViewRepresentable {
    let url: URL
    let query: String
    let selectedIndex: Int
    @Binding var resultCount: Int

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.displaysPageBreaks = true
        view.pageBreakMargins = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        view.document = PDFDocument(url: url)
        view.accessibilityIdentifier = "rule-pdf-reader"
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document?.documentURL != url {
            view.document = PDFDocument(url: url)
            context.coordinator.lastQuery = ""
            context.coordinator.matches = []
        }

        if context.coordinator.lastQuery != query {
            context.coordinator.lastQuery = query
            context.coordinator.resetShownMatch()
            if query.isEmpty {
                context.coordinator.matches = []
            } else {
                context.coordinator.matches = view.document?.findString(
                    query,
                    withOptions: [.caseInsensitive]
                ) ?? []
            }
            view.highlightedSelections = context.coordinator.matches
            let count = context.coordinator.matches.count
            if resultCount != count {
                DispatchQueue.main.async {
                    resultCount = count
                }
            }
        }

        context.coordinator.showMatch(at: selectedIndex, in: view)
    }

    final class Coordinator {
        var lastQuery = ""
        var matches: [PDFSelection] = []
        private var lastShownIndex: Int?

        func resetShownMatch() {
            lastShownIndex = nil
        }

        func showMatch(at requestedIndex: Int, in view: PDFView) {
            guard !matches.isEmpty else {
                view.highlightedSelections = []
                view.currentSelection = nil
                lastShownIndex = nil
                return
            }

            let index = min(max(requestedIndex, 0), matches.count - 1)
            for selection in matches {
                selection.color = UIColor.systemYellow.withAlphaComponent(0.38)
            }
            let selectedMatch = matches[index]
            selectedMatch.color = UIColor.systemOrange.withAlphaComponent(0.72)
            view.highlightedSelections = matches

            guard lastShownIndex != index else { return }
            lastShownIndex = index
            view.layoutDocumentView()
            DispatchQueue.main.async {
                if let page = selectedMatch.pages.first {
                    view.go(to: page)
                }
                view.currentSelection = selectedMatch
                view.go(to: selectedMatch)
                view.scrollSelectionToVisible(nil)
            }
        }
    }
}

struct PracticeInningView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                BMCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("安全练习模式", systemImage: "checkmark.shield.fill")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(BMTheme.green)
                        Text("练习会创建一场独立的一局比赛。所有点选、比分和记录只存在于本次练习，不会写入正式比赛或球队统计。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                SectionHeader(title: "建议练习顺序")
                VStack(spacing: 10) {
                    practiceStep(number: 1, title: "记录投球", detail: "尝试坏球、看振、挥空和界外。")
                    practiceStep(number: 2, title: "记录击球", detail: "先选择打者最终位置，再确认发生原因。")
                    practiceStep(number: 3, title: "处理跑者", detail: "确认跑者进垒、得分或出局。")
                    practiceStep(number: 4, title: "修正与撤销", detail: "模拟看错现场后进行修正。")
                }

                NavigationLink(destination: PracticeScorekeepingView()) {
                    Label("开始练习一局", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("start-practice-inning")
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("练习一局")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func practiceStep(number: Int, title: String, detail: String) -> some View {
        HStack(spacing: 13) {
            Text("\(number)")
                .font(.system(size: 15, weight: .black, design: .rounded))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(BMTheme.green)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
        }
        .padding(14)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay {
            RoundedRectangle(cornerRadius: 14)
                .stroke(BMTheme.line.opacity(0.75), lineWidth: 1)
        }
    }
}

struct PracticeScorekeepingView: View {
    @StateObject private var practiceStore: GameStore
    @AppStorage("showPracticeGuidance") private var showPracticeGuidance = true
    @State private var showResetConfirmation = false

    init() {
        let store = GameStore(persistenceURL: nil)
        store.resetPracticeInning()
        _practiceStore = StateObject(wrappedValue: store)
    }

    var body: some View {
        ScorekeepingView()
            .environmentObject(practiceStore)
            .safeAreaInset(edge: .top, spacing: 0) {
                if showPracticeGuidance {
                    practiceBanner
                }
            }
            .toolbar {
                ToolbarItem(placement: .bottomBar) {
                    Button {
                        showResetConfirmation = true
                    } label: {
                        Label("重新练习", systemImage: "arrow.counterclockwise")
                    }
                    .accessibilityIdentifier("reset-practice-inning")
                }
            }
            .confirmationDialog("重新开始这一局练习？", isPresented: $showResetConfirmation) {
                Button("重新开始", role: .destructive) {
                    practiceStore.resetPracticeInning()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("当前练习中的比分、垒况和记录会被清除，不影响正式比赛数据。")
            }
    }

    private var practiceBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "graduationcap.fill")
                .foregroundStyle(BMTheme.green)
            VStack(alignment: .leading, spacing: 2) {
                Text("练习模式 · 不会保存到正式数据")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BMTheme.navy)
                Text(practicePrompt)
                    .font(.system(size: 11))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(BMTheme.greenSoft)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityIdentifier("practice-mode-banner")
    }

    private var practicePrompt: String {
        if practiceStore.game.playLog.count <= 1 {
            return "先尝试记录一个坏球或好球。"
        }
        if practiceStore.game.batting.values.allSatisfy({ $0.plateAppearances == 0 }) {
            return "接着点击“球打出去了”，按你看到的结果完成记录。"
        }
        return "继续尝试跑者变化、撤销和现场修正。"
    }
}

#Preview("我的") {
    NavigationStack { ProfileView() }
        .environmentObject(GameStore(persistenceURL: nil))
}

#Preview("棒球规则查询") {
    NavigationStack { BaseballRulesView() }
}

#Preview("练习一局") {
    NavigationStack { PracticeInningView() }
}
