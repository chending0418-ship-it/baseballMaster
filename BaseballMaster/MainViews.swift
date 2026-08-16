import SwiftUI

struct RootTabView: View {
    @EnvironmentObject private var store: MockGameStore

    var body: some View {
        TabView(selection: $store.selectedTab) {
            NavigationStack { GameHomeView() }
                .tabItem { Label("比赛", systemImage: "baseball.diamond.bases") }
                .tag(0)

            NavigationStack { TeamRosterView() }
                .tabItem { Label("球队", systemImage: "person.3.fill") }
                .tag(1)

            NavigationStack { StatsOverviewView() }
                .tabItem { Label("统计", systemImage: "chart.bar.fill") }
                .tag(2)

            NavigationStack { ProfileView() }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
                .tag(3)
        }
        .tint(BMTheme.green)
    }
}

struct GameHomeView: View {
    @EnvironmentObject private var store: MockGameStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                teamHeader

                NavigationLink(destination: NewGameSetupView()) {
                    Label("开始新比赛", systemImage: "plus.circle.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("start-new-game")

                SectionHeader(title: "正在进行", subtitle: "示例比赛")
                BMCard {
                    VStack(spacing: 16) {
                        HStack {
                            VStack(alignment: .leading, spacing: 5) {
                                Text("青岛海浪  vs  北京飞鹰")
                                    .font(.system(size: 17, weight: .bold))
                                    .foregroundStyle(BMTheme.navy)
                                Text("2局上半 · 一垒有人")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(BMTheme.secondaryText)
                            }
                            Spacer()
                            Text("1 : 0")
                                .font(.system(size: 27, weight: .black, design: .rounded))
                                .foregroundStyle(BMTheme.navy)
                        }

                        NavigationLink(destination: ScorekeepingView()) {
                            HStack {
                                Image(systemName: "play.fill")
                                Text("继续示例比赛")
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(BMTheme.green)
                            .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("continue-demo-game")
                    }
                }

                SectionHeader(title: "最近比赛", subtitle: "2026 夏季")
                recentGame(opponent: "天津火箭", result: "6 - 4", date: "8月3日", won: true)
                recentGame(opponent: "济南小熊", result: "2 - 5", date: "7月27日", won: false)
            }
            .padding(.horizontal, BMTheme.horizontalPadding)
            .padding(.vertical, 18)
        }
        .bmScreenBackground()
        .navigationTitle("比赛")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(destination: GlossaryView()) {
                    Image(systemName: "questionmark.circle")
                }
                .accessibilityLabel("规则帮助")
            }
        }
    }

    private var teamHeader: some View {
        HStack(spacing: 13) {
            TeamMark(team: store.currentTeam, size: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(store.currentTeam.name)
                    .font(.system(size: 24, weight: .black))
                    .foregroundStyle(BMTheme.navy)
                Text("U12 · 2026 夏季")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            Spacer()
            Text("3胜 1负")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(BMTheme.green)
                .padding(.horizontal, 11)
                .padding(.vertical, 7)
                .background(BMTheme.greenSoft)
                .clipShape(Capsule())
        }
    }

    private func recentGame(opponent: String, result: String, date: String, won: Bool) -> some View {
        NavigationLink(destination: BoxScoreView()) {
            HStack(spacing: 13) {
                Text(won ? "胜" : "负")
                    .font(.system(size: 15, weight: .black))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(won ? BMTheme.green : BMTheme.red)
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("对 \(opponent)")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BMTheme.navy)
                    Text(date)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(BMTheme.secondaryText)
                }
                Spacer()
                Text(result)
                    .font(.system(size: 19, weight: .black, design: .rounded))
                    .foregroundStyle(BMTheme.navy)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BMTheme.secondaryText)
            }
            .padding(15)
            .background(BMTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

struct ProfileView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                BMCard {
                    HStack(spacing: 14) {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 52))
                            .foregroundStyle(BMTheme.green)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("球队记分员")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundStyle(BMTheme.navy)
                            Text("本机演示模式 · 无需登录")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                        Spacer()
                    }
                }

                NavigationLink(destination: GlossaryView()) {
                    settingsRow(icon: "book.closed.fill", title: "棒球名词小抄", subtitle: "中文解释和记分符号")
                }
                NavigationLink(destination: ScorekeepingView()) {
                    settingsRow(icon: "figure.baseball", title: "练习一局", subtitle: "使用示例比赛熟悉点选")
                }

                BMCard {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("关于这个原型")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(BMTheme.navy)
                        Text("数据仅保存在内存中，关闭 App 后会恢复为示例比赛。本阶段用于确认现场记分体验。")
                            .font(.system(size: 14))
                            .foregroundStyle(BMTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("我的")
    }

    private func settingsRow(icon: String, title: String, subtitle: String) -> some View {
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
            Spacer()
            Image(systemName: "chevron.right")
                .foregroundStyle(BMTheme.secondaryText)
        }
        .padding(15)
        .background(BMTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 15))
    }
}

struct GlossaryView: View {
    private let items = [
        GlossaryItem(term: "野手选择", abbreviation: "FC", explanation: "守备方选择处理其他跑者，使打者安全上垒，不记安打。", example: "一垒有人，游击手传二垒封杀跑者，打者到一垒。"),
        GlossaryItem(term: "对方失误", abbreviation: "E", explanation: "守备员以正常努力本可完成出局，但因失误让进攻方获益。", example: "游击手漏接普通滚地球，打者安全到一垒。"),
        GlossaryItem(term: "牺牲高飞", abbreviation: "SF", explanation: "两出局前，外野飞球被接杀，但跑者随后回本垒得分。", example: "三垒跑者等接杀后启动并得分。"),
        GlossaryItem(term: "暴投", abbreviation: "WP", explanation: "投手的球偏离正常接捕范围，导致跑者推进。", example: "投球落地弹远，二垒跑者进入三垒。"),
        GlossaryItem(term: "捕逸", abbreviation: "PB", explanation: "捕手以正常努力本应接住投球，却漏接并让跑者推进。", example: "普通投球从捕手手套弹开，跑者进垒。"),
        GlossaryItem(term: "刺杀", abbreviation: "PO", explanation: "守备员直接完成一个出局。", example: "一垒手接到游击手传球踩一垒，记一垒手刺杀。")
    ]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(items) { item in
                    BMCard {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text(item.term)
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundStyle(BMTheme.navy)
                                Text(item.abbreviation)
                                    .font(.system(size: 12, weight: .black, design: .rounded))
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
                            Text("例：\(item.example)")
                                .font(.system(size: 13))
                                .foregroundStyle(BMTheme.secondaryText)
                        }
                    }
                }
            }
            .padding(BMTheme.horizontalPadding)
        }
        .bmScreenBackground()
        .navigationTitle("棒球名词小抄")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("首页") {
    NavigationStack { GameHomeView() }
        .environmentObject(MockGameStore())
}

#Preview("名词解释") {
    NavigationStack { GlossaryView() }
}

