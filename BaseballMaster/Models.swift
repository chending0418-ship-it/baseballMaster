import Foundation

enum FieldPosition: Int, CaseIterable, Identifiable, Codable, Hashable {
    case pitcher = 1
    case catcher
    case firstBase
    case secondBase
    case thirdBase
    case shortstop
    case leftField
    case centerField
    case rightField

    var id: Int { rawValue }

    var shortName: String {
        switch self {
        case .pitcher: "投"
        case .catcher: "捕"
        case .firstBase: "一垒"
        case .secondBase: "二垒"
        case .thirdBase: "三垒"
        case .shortstop: "游击"
        case .leftField: "左外"
        case .centerField: "中外"
        case .rightField: "右外"
        }
    }

    var fullName: String {
        switch self {
        case .pitcher: "投手"
        case .catcher: "捕手"
        case .firstBase: "一垒手"
        case .secondBase: "二垒手"
        case .thirdBase: "三垒手"
        case .shortstop: "游击手"
        case .leftField: "左外野手"
        case .centerField: "中外野手"
        case .rightField: "右外野手"
        }
    }
}

struct Player: Identifiable, Hashable, Codable {
    let id: UUID
    var name: String
    var number: Int
    var primaryPosition: FieldPosition

    init(id: UUID = UUID(), name: String, number: Int, primaryPosition: FieldPosition) {
        self.id = id
        self.name = name
        self.number = number
        self.primaryPosition = primaryPosition
    }

    var compactName: String { "\(number)号 \(name)" }
}

struct Team: Identifiable, Hashable, Codable {
    let id: UUID
    var name: String
    var shortName: String
    var city: String
    var players: [Player]

    init(id: UUID = UUID(), name: String, shortName: String, city: String, players: [Player]) {
        self.id = id
        self.name = name
        self.shortName = shortName
        self.city = city
        self.players = players
    }
}

struct BattingLine: Equatable, Codable {
    var plateAppearances = 0
    var atBats = 0
    var runs = 0
    var hits = 0
    var doubles = 0
    var triples = 0
    var homeRuns = 0
    var runsBattedIn = 0
    var walks = 0
    var hitByPitch = 0
    var strikeouts = 0
    var stolenBases = 0
    var caughtStealing = 0
    var sacrifices = 0

    var singles: Int { max(0, hits - doubles - triples - homeRuns) }
    var totalBases: Int { singles + doubles * 2 + triples * 3 + homeRuns * 4 }
    var average: Double { atBats == 0 ? 0 : Double(hits) / Double(atBats) }
    var onBasePercentage: Double {
        let denominator = atBats + walks + hitByPitch + sacrifices
        return denominator == 0 ? 0 : Double(hits + walks + hitByPitch) / Double(denominator)
    }
    var slugging: Double { atBats == 0 ? 0 : Double(totalBases) / Double(atBats) }
    var ops: Double { onBasePercentage + slugging }
}

struct PitchingLine: Equatable, Codable {
    var outsRecorded = 0
    var battersFaced = 0
    var hits = 0
    var runs = 0
    var earnedRuns = 0
    var walks = 0
    var hitByPitch = 0
    var strikeouts = 0
    var wildPitches = 0
    var pitches = 0
    var strikes = 0

    var inningsText: String { "\(outsRecorded / 3).\(outsRecorded % 3)" }
    var innings: Double { Double(outsRecorded) / 3.0 }
    var era: Double { innings == 0 ? 0 : Double(earnedRuns) * 7.0 / innings }
    var whip: Double { innings == 0 ? 0 : Double(walks + hits) / innings }
}

struct FieldingLine: Equatable, Codable {
    var putouts = 0
    var assists = 0
    var errors = 0
    var doublePlays = 0
}

enum Base: Int, CaseIterable, Hashable, Codable {
    case first = 1
    case second
    case third

    var title: String {
        switch self {
        case .first: "一垒"
        case .second: "二垒"
        case .third: "三垒"
        }
    }
}

enum PitchAction: String, CaseIterable, Identifiable {
    case ball = "坏球"
    case calledStrike = "看振"
    case swingingStrike = "挥空"
    case foul = "界外"

    var id: String { rawValue }
}

enum PlayOutcome: String, CaseIterable, Identifiable {
    case single = "一垒安打"
    case double = "二垒安打"
    case triple = "三垒安打"
    case homeRun = "本垒打"
    case groundOut = "滚地出局"
    case flyOut = "飞球出局"
    case error = "对方失误"
    case fieldersChoice = "野手选择"
    case sacrificeBunt = "牺牲触击"
    case sacrificeFly = "牺牲高飞"
    case doublePlay = "双杀"
    case pendingOut = "待确认出局"
    case pending = "待确认上垒"
    case other = "其他"

    var id: String { rawValue }

    var notation: String {
        switch self {
        case .single: "1B"
        case .double: "2B"
        case .triple: "3B"
        case .homeRun: "HR"
        case .groundOut: "GO"
        case .flyOut: "FO"
        case .error: "E"
        case .fieldersChoice: "FC"
        case .sacrificeBunt: "SH"
        case .sacrificeFly: "SF"
        case .doublePlay: "DP"
        case .pendingOut: "待确认"
        case .pending: "待确认"
        case .other: "待补"
        }
    }

    var isHit: Bool { [.single, .double, .triple, .homeRun].contains(self) }
    var requiresDefense: Bool {
        [.groundOut, .flyOut, .error, .fieldersChoice, .doublePlay].contains(self)
    }
}

enum RunnerEventKind: String, CaseIterable, Identifiable {
    case stolenBase = "偷垒成功"
    case caughtStealing = "跑垒出局"
    case pickoff = "牵制出局"
    case wildPitch = "球太偏，跑者进垒"
    case passedBall = "捕手漏球，跑者进垒"
    case uncertainLooseBall = "球漏过去，分不清原因"
    case balk = "投手犯规，跑者进垒"

    var id: String { rawValue }

    var shortTitle: String {
        switch self {
        case .stolenBase: "偷垒"
        case .caughtStealing: "跑垒出局"
        case .pickoff: "牵制出局"
        case .wildPitch: "球太偏"
        case .passedBall: "捕手漏球"
        case .uncertainLooseBall: "分不清"
        case .balk: "投手犯规"
        }
    }
}

struct DefensivePlay: Identifiable, Hashable {
    let id: String
    let title: String
    let notation: String
    let assistPositions: [FieldPosition]
    let putoutPosition: FieldPosition?
    let errorPosition: FieldPosition?

    static let quickPlays: [DefensivePlay] = [
        DefensivePlay(id: "6-3", title: "游击传一垒", notation: "6-3", assistPositions: [.shortstop], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "4-3", title: "二垒传一垒", notation: "4-3", assistPositions: [.secondBase], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "5-3", title: "三垒传一垒", notation: "5-3", assistPositions: [.thirdBase], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "F7", title: "左外野接杀", notation: "F7", assistPositions: [], putoutPosition: .leftField, errorPosition: nil),
        DefensivePlay(id: "F8", title: "中外野接杀", notation: "F8", assistPositions: [], putoutPosition: .centerField, errorPosition: nil),
        DefensivePlay(id: "F9", title: "右外野接杀", notation: "F9", assistPositions: [], putoutPosition: .rightField, errorPosition: nil),
        DefensivePlay(id: "6-4-3", title: "游击发起双杀", notation: "6-4-3", assistPositions: [.shortstop, .secondBase], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "OTHER", title: "其他处理", notation: "自定义", assistPositions: [], putoutPosition: nil, errorPosition: nil)
    ]

    static func error(at position: FieldPosition) -> DefensivePlay {
        DefensivePlay(
            id: "E\(position.rawValue)",
            title: "\(position.fullName)失误",
            notation: "E\(position.rawValue)",
            assistPositions: [],
            putoutPosition: nil,
            errorPosition: position
        )
    }
}

enum RunnerOrigin: Hashable {
    case batter
    case base(Base)

    var title: String {
        switch self {
        case .batter: "打者"
        case .base(let base): "\(base.title)跑者"
        }
    }
}

enum RunnerDestination: Hashable {
    case hold
    case base(Base)
    case score
    case out

    var title: String {
        switch self {
        case .hold: "停留"
        case .base(let base): "到\(base.title)"
        case .score: "得分"
        case .out: "出局"
        }
    }
}

struct RunnerDecision: Identifiable, Hashable {
    let id: UUID
    let player: Player
    let origin: RunnerOrigin
    var destination: RunnerDestination

    init(player: Player, origin: RunnerOrigin, destination: RunnerDestination) {
        self.id = player.id
        self.player = player
        self.origin = origin
        self.destination = destination
    }
}

struct PlayLogEntry: Identifiable, Equatable, Codable {
    let id: UUID
    let inning: Int
    let isTop: Bool
    let text: String
    let timestamp: Date
    let isIncomplete: Bool

    init(id: UUID = UUID(), inning: Int, isTop: Bool, text: String, timestamp: Date = Date(), isIncomplete: Bool = false) {
        self.id = id
        self.inning = inning
        self.isTop = isTop
        self.text = text
        self.timestamp = timestamp
        self.isIncomplete = isIncomplete
    }

    var inningLabel: String { "\(isTop ? "上" : "下")\(inning)" }
}

struct DemoGameState: Equatable {
    var homeTeam: Team
    var awayTeam: Team
    var scheduledInnings: Int
    var inning = 1
    var isTop = true
    var balls = 0
    var strikes = 0
    var outs = 0
    var homeRunsByInning: [Int]
    var awayRunsByInning: [Int]
    var homeHits = 0
    var awayHits = 0
    var homeErrors = 0
    var awayErrors = 0
    var homeBatterIndex = 0
    var awayBatterIndex = 0
    var baseRunners: [Base: Player] = [:]
    var batting: [UUID: BattingLine] = [:]
    var pitching: [UUID: PitchingLine] = [:]
    var fielding: [UUID: FieldingLine] = [:]
    var playLog: [PlayLogEntry] = []
    var isFinal = false
    var activeHomePitcherID: UUID?
    var activeAwayPitcherID: UUID?

    init(homeTeam: Team, awayTeam: Team, scheduledInnings: Int = 6) {
        self.homeTeam = homeTeam
        self.awayTeam = awayTeam
        self.scheduledInnings = scheduledInnings
        self.homeRunsByInning = Array(repeating: 0, count: scheduledInnings)
        self.awayRunsByInning = Array(repeating: 0, count: scheduledInnings)
        self.activeHomePitcherID = homeTeam.players.first(where: { $0.primaryPosition == .pitcher })?.id
        self.activeAwayPitcherID = awayTeam.players.first(where: { $0.primaryPosition == .pitcher })?.id
    }

    var homeScore: Int { homeRunsByInning.reduce(0, +) }
    var awayScore: Int { awayRunsByInning.reduce(0, +) }
    var battingTeam: Team { isTop ? awayTeam : homeTeam }
    var fieldingTeam: Team { isTop ? homeTeam : awayTeam }
    var currentBatter: Player {
        let lineup = battingTeam.players
        let index = isTop ? awayBatterIndex : homeBatterIndex
        return lineup[index % max(lineup.count, 1)]
    }
    var currentPitcher: Player {
        let activeID = isTop ? activeHomePitcherID : activeAwayPitcherID
        return fieldingTeam.players.first(where: { $0.id == activeID })
            ?? fieldingTeam.players.first(where: { $0.primaryPosition == .pitcher })
            ?? fieldingTeam.players[0]
    }
    var halfLabel: String { "\(isTop ? "上" : "下")半局" }
}

struct GlossaryItem: Identifiable {
    let id = UUID()
    let term: String
    let abbreviation: String
    let explanation: String
    let example: String
}
