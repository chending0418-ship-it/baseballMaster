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
    var chineseName: String
    var englishName: String
    var numbers: [Int]

    // The live scoring state stores the current defensive assignment on the
    // player. It is intentionally not exposed as roster/profile information.
    var primaryPosition: FieldPosition

    init(
        id: UUID = UUID(),
        chineseName: String,
        englishName: String,
        numbers: [Int],
        primaryPosition: FieldPosition = .pitcher
    ) {
        self.id = id
        self.chineseName = chineseName
        self.englishName = englishName
        self.numbers = numbers
        self.primaryPosition = primaryPosition
    }

    init(id: UUID = UUID(), name: String, number: Int, primaryPosition: FieldPosition) {
        self.init(
            id: id,
            chineseName: name,
            englishName: "",
            numbers: [number],
            primaryPosition: primaryPosition
        )
    }

    var name: String {
        chineseName.isEmpty ? englishName : chineseName
    }

    var number: Int {
        numbers.first ?? 0
    }

    var numbersText: String {
        numbers.isEmpty ? "暂无背号" : numbers.map { "#\($0)" }.joined(separator: " / ")
    }

    var compactName: String { "\(numbersText) \(name)" }
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

    mutating func add(_ other: BattingLine) {
        plateAppearances += other.plateAppearances
        atBats += other.atBats
        runs += other.runs
        hits += other.hits
        doubles += other.doubles
        triples += other.triples
        homeRuns += other.homeRuns
        runsBattedIn += other.runsBattedIn
        walks += other.walks
        hitByPitch += other.hitByPitch
        strikeouts += other.strikeouts
        stolenBases += other.stolenBases
        caughtStealing += other.caughtStealing
        sacrifices += other.sacrifices
    }

    static func aggregate<S: Sequence>(_ lines: S) -> BattingLine where S.Element == BattingLine {
        lines.reduce(into: BattingLine()) { result, line in
            result.add(line)
        }
    }
}

struct Season: Identifiable, Hashable, Codable {
    let id: String
    var name: String
}

struct PlayerGameRecord: Identifiable, Equatable, Codable {
    let id: UUID
    let playerID: UUID
    let seasonID: String
    var date: Date
    var opponent: String
    var result: String
    var batting: BattingLine

    init(
        id: UUID = UUID(),
        playerID: UUID,
        seasonID: String,
        date: Date,
        opponent: String,
        result: String,
        batting: BattingLine
    ) {
        self.id = id
        self.playerID = playerID
        self.seasonID = seasonID
        self.date = date
        self.opponent = opponent
        self.result = result
        self.batting = batting
    }
}

enum StoredGameStatus: Int, Codable {
    case ongoing = 0
    case completed = 1
    case scheduled = 2
}

enum GameClockDisplayMode: String, CaseIterable, Codable {
    case elapsed
    case remaining

    var title: String {
        switch self {
        case .elapsed: "已进行"
        case .remaining: "剩余"
        }
    }
}

enum GameEndReason: String, CaseIterable, Identifiable, Codable {
    case regulation = "规定局数完成"
    case walkOff = "再见分结束"
    case timeLimit = "时间限制结束"
    case mercyRule = "提前结束规则"
    case forfeit = "弃权结束"
    case weather = "天气或场地原因"
    case suspended = "比赛中断"
    case scorerDecision = "记录员结束记录"

    var id: String { rawValue }

    var isCompletedResult: Bool { self != .suspended }
}

struct GameRules: Equatable, Codable {
    var scheduledInnings: Int
    var fieldersCount: Int
    var timeLimitMinutes: Int?
    var timeWarningMinutes: Int?
    var pitchLimit: Int?
    var pitchWarningRemaining: Int?
    var pitcherInningsLimit: Int?
    /// Reserved rule switches for leagues that use a designated hitter or the
    /// two-way-player ("Ohtani") exception. They do not change ordinary games.
    var usesDesignatedHitter: Bool?
    var allowsTwoWayPlayer: Bool?

    init(
        scheduledInnings: Int = 6,
        fieldersCount: Int = 9,
        timeLimitMinutes: Int? = nil,
        timeWarningMinutes: Int? = nil,
        pitchLimit: Int? = nil,
        pitchWarningRemaining: Int? = nil,
        pitcherInningsLimit: Int? = nil,
        usesDesignatedHitter: Bool? = nil,
        allowsTwoWayPlayer: Bool? = nil
    ) {
        self.scheduledInnings = scheduledInnings
        self.fieldersCount = fieldersCount
        self.timeLimitMinutes = timeLimitMinutes
        self.timeWarningMinutes = timeWarningMinutes
        self.pitchLimit = pitchLimit
        self.pitchWarningRemaining = pitchWarningRemaining
        self.pitcherInningsLimit = pitcherInningsLimit
        self.usesDesignatedHitter = usesDesignatedHitter
        self.allowsTwoWayPlayer = allowsTwoWayPlayer
    }

    var designatedHitterEnabled: Bool { usesDesignatedHitter == true }
    var twoWayPlayerEnabled: Bool { designatedHitterEnabled && allowsTwoWayPlayer == true }
}

struct LineupAssignment: Identifiable, Equatable, Codable {
    var playerID: UUID
    var battingOrder: Int
    var position: FieldPosition

    var id: UUID { playerID }
}

struct StoredGame: Identifiable, Equatable, Codable {
    let id: UUID
    let createdAt: Date
    var updatedAt: Date
    var scheduledAt: Date?
    var startedAt: Date?
    let seasonID: String
    let ourTeamID: UUID?
    let opponentTeamID: UUID?
    let isHome: Bool
    let isSpectator: Bool?
    var rules: GameRules
    var lineup: [LineupAssignment]
    var secondaryLineup: [LineupAssignment]?
    var status: StoredGameStatus
    var state: GameState

    init(
        id: UUID = UUID(),
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        scheduledAt: Date? = nil,
        startedAt: Date? = Date(),
        seasonID: String,
        ourTeamID: UUID?,
        opponentTeamID: UUID?,
        isHome: Bool,
        isSpectator: Bool = false,
        rules: GameRules,
        lineup: [LineupAssignment],
        secondaryLineup: [LineupAssignment]? = nil,
        status: StoredGameStatus = .ongoing,
        state: GameState
    ) {
        self.id = id
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.scheduledAt = scheduledAt
        self.startedAt = startedAt
        self.seasonID = seasonID
        self.ourTeamID = ourTeamID
        self.opponentTeamID = opponentTeamID
        self.isHome = isHome
        self.isSpectator = isSpectator
        self.rules = rules
        self.lineup = lineup
        self.secondaryLineup = secondaryLineup
        self.status = status
        self.state = state
    }

    var ourScore: Int { isHome ? state.homeScore : state.awayScore }
    var opponentScore: Int { isHome ? state.awayScore : state.homeScore }
    var opponent: Team { isHome ? state.awayTeam : state.homeTeam }
    var ourTeam: Team { isHome ? state.homeTeam : state.awayTeam }
    var isObservation: Bool { isSpectator == true }
    var effectiveScheduledAt: Date { scheduledAt ?? createdAt }
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

enum PlayOutcome: String, CaseIterable, Identifiable, Codable {
    case single = "一垒安打"
    case double = "二垒安打"
    case triple = "三垒安打"
    case homeRun = "本垒打"
    case groundOut = "滚地出局"
    case flyOut = "飞球出局"
    case lineOut = "平飞接杀"
    case foulFlyOut = "界外飞球接杀"
    case infieldFly = "内野高飞必死"
    case error = "对方失误"
    case fieldersChoice = "野手选择"
    case runnerTagOut = "触杀其他跑者"
    case sacrificeBunt = "牺牲触击"
    case sacrificeFly = "牺牲高飞"
    case doublePlay = "双杀"
    case triplePlay = "三杀"
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
        case .lineOut: "LO"
        case .foulFlyOut: "FF"
        case .infieldFly: "IF"
        case .error: "E"
        case .fieldersChoice: "FC"
        case .runnerTagOut: "TAG"
        case .sacrificeBunt: "SH"
        case .sacrificeFly: "SF"
        case .doublePlay: "DP"
        case .triplePlay: "TP"
        case .pendingOut: "待确认"
        case .pending: "待确认"
        case .other: "待补"
        }
    }

    var isHit: Bool { [.single, .double, .triple, .homeRun].contains(self) }
    var recordedOuts: Int {
        switch self {
        case .doublePlay: 2
        case .triplePlay: 3
        case .groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly,
             .sacrificeBunt, .sacrificeFly, .pendingOut: 1
        default: 0
        }
    }
    var requiresDefense: Bool {
        [.groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly,
         .error, .fieldersChoice, .runnerTagOut, .doublePlay, .triplePlay].contains(self)
    }
}

enum RunnerEventGroup: String, CaseIterable, Identifiable {
    case advance = "主动进垒"
    case looseBall = "投球或漏球"
    case out = "跑者出局"

    var id: String { rawValue }
}

enum RunnerEventKind: String, CaseIterable, Identifiable, Codable {
    case stolenBase = "偷垒成功"
    case doubleSteal = "双重盗垒"
    case delayedSteal = "延迟盗垒"
    case relayAdvance = "守备转传时额外进垒"
    case defensiveErrorAdvance = "守备失误造成额外进垒"
    case caughtStealing = "跑垒出局"
    case pickoff = "牵制出局"
    case tagOut = "触杀出局"
    case forceOut = "封杀出局"
    case appealOut = "申诉出局"
    case leftEarly = "离垒过早"
    case missedBase = "漏踩垒位"
    case outOfBasePath = "跑出限制道"
    case passedRunner = "超越前位跑者"
    case runnerInterference = "跑者妨碍守备"
    case runnerHitByBall = "跑垒员被击球击中"
    case coachAssistance = "教练员帮助跑者"
    case wildPitch = "球太偏，跑者进垒"
    case passedBall = "捕手漏球，跑者进垒"
    case uncertainLooseBall = "球漏过去，分不清原因"
    case balk = "投手犯规，跑者进垒"

    var id: String { rawValue }

    var shortTitle: String {
        switch self {
        case .stolenBase: "偷垒"
        case .doubleSteal: "双重盗垒"
        case .delayedSteal: "延迟盗垒"
        case .relayAdvance: "转传进垒"
        case .defensiveErrorAdvance: "失误进垒"
        case .caughtStealing: "跑垒出局"
        case .pickoff: "牵制出局"
        case .tagOut: "触杀"
        case .forceOut: "封杀"
        case .appealOut: "申诉"
        case .leftEarly: "离垒过早"
        case .missedBase: "漏踩垒"
        case .outOfBasePath: "限制道"
        case .passedRunner: "超越跑者"
        case .runnerInterference: "妨碍守备"
        case .runnerHitByBall: "跑者中球"
        case .coachAssistance: "帮助跑者"
        case .wildPitch: "球太偏"
        case .passedBall: "捕手漏球"
        case .uncertainLooseBall: "分不清"
        case .balk: "投手犯规"
        }
    }

    var group: RunnerEventGroup {
        switch self {
        case .stolenBase, .doubleSteal, .delayedSteal, .relayAdvance, .defensiveErrorAdvance:
            .advance
        case .wildPitch, .passedBall, .uncertainLooseBall, .balk:
            .looseBall
        case .caughtStealing, .pickoff, .tagOut, .forceOut, .appealOut,
             .leftEarly, .missedBase, .outOfBasePath, .passedRunner,
             .runnerInterference, .runnerHitByBall, .coachAssistance:
            .out
        }
    }

    var notation: String {
        switch self {
        case .stolenBase: "SB"
        case .doubleSteal: "DS"
        case .delayedSteal: "DSB"
        case .relayAdvance: "ADV"
        case .defensiveErrorAdvance: "E-ADV"
        case .caughtStealing: "CS"
        case .pickoff: "PO"
        case .tagOut: "TAG"
        case .forceOut: "FO"
        case .appealOut: "AP"
        case .leftEarly: "AP-EARLY"
        case .missedBase: "AP-MISS"
        case .outOfBasePath: "OBP"
        case .passedRunner: "PASS"
        case .runnerInterference: "R-INT"
        case .runnerHitByBall: "R-HIT"
        case .coachAssistance: "COACH"
        case .wildPitch: "WP"
        case .passedBall: "PB"
        case .uncertainLooseBall: "WP/PB?"
        case .balk: "BK"
        }
    }

    var ballStatus: BallStatus {
        switch self {
        case .runnerInterference, .runnerHitByBall, .coachAssistance,
             .leftEarly, .missedBase, .appealOut:
            .dead
        case .balk:
            .delayedDead
        default:
            .live
        }
    }
}

enum ViolationCategory: String, CaseIterable, Identifiable, Codable {
    case pitcher = "投手"
    case batter = "打者"
    case catcher = "捕手"
    case defense = "防守"
    case offense = "进攻"

    var id: String { rawValue }
}

enum BallStatus: String, CaseIterable, Identifiable, Codable {
    case live = "活球"
    case dead = "死球"
    case delayedDead = "延迟死球"
    case umpireDecision = "按裁判宣判"

    var id: String { rawValue }
}

enum ViolationResolution: Equatable {
    case ballOrAdvance
    case batterOut
    case batterFirst
    case resolveRunners
    case recordForReview
}

enum PreviousPlayDisposition: String, CaseIterable, Identifiable {
    case notApplicable = "不涉及上一比赛结果"
    case keep = "保留上一比赛结果"
    case cancel = "取消上一比赛结果"

    var id: String { rawValue }
}

enum PlateAppearanceDisposition: String, CaseIterable, Identifiable {
    case continueAtBat = "继续当前打席"
    case batterOut = "打者出局"
    case batterFirst = "打者上一垒"

    var id: String { rawValue }
}

enum AdjudicationBattingCredit: String, CaseIterable, Identifiable {
    case none = "不另记打击结果"
    case single = "一垒安打"
    case double = "二垒安打"
    case triple = "三垒安打"
    case homeRun = "本垒打"
    case reachedOnError = "守备失误上垒"
    case fieldersChoice = "野手选择"
    case sacrifice = "牺牲打"

    var id: String { rawValue }

    var outcome: PlayOutcome? {
        switch self {
        case .none: nil
        case .single: .single
        case .double: .double
        case .triple: .triple
        case .homeRun: .homeRun
        case .reachedOnError: .error
        case .fieldersChoice: .fieldersChoice
        case .sacrifice: .sacrificeFly
        }
    }

    var isHit: Bool {
        [.single, .double, .triple, .homeRun].contains(self)
    }
}

/// The scorer's final implementation of an umpire ruling. The individual
/// violation supplies a fast default, while this value allows the scorer to
/// reproduce the actual award when a ruling affects several runners or
/// supersedes the immediately preceding play.
struct ViolationAdjudication {
    var ballStatus: BallStatus
    var previousPlayDisposition: PreviousPlayDisposition
    var plateAppearanceDisposition: PlateAppearanceDisposition
    var runnerDecisions: [RunnerDecision]
    var countsAsAtBat: Bool
    var isFinalRuling: Bool
    var battingCredit: AdjudicationBattingCredit = .none
    var runsBattedIn: Int = 0
    var earnedRuns: Int = 0
    var fieldingErrorPlayerID: UUID? = nil
}

enum ViolationKind: String, CaseIterable, Identifiable, Codable {
    case balk = "投手犯规"
    case quickPitch = "快速投球"
    case illegalPitch = "违规投球"
    case foreignSubstance = "球体处理违规"
    case batterOutOfBox = "踏出打击区击球"
    case batterInterference = "打者干扰"
    case illegalBat = "违规球棒"
    case battingOutOfTurn = "打击次序错误"
    case catcherInterference = "捕手干扰打击"
    case plateObstruction = "本垒阻挡或冲撞"
    case obstruction = "阻挡跑垒"
    case fakeTag = "假触杀"
    case detachedEquipment = "脱离装备触球"
    case runnerInterference = "跑者干扰"
    case runningLaneInterference = "跑垒限制道干扰"
    case coachInterference = "教练员干扰"
    case passingRunner = "超越前位跑者"

    var id: String { rawValue }

    var category: ViolationCategory {
        switch self {
        case .balk, .quickPitch, .illegalPitch, .foreignSubstance: .pitcher
        case .batterOutOfBox, .batterInterference, .illegalBat, .battingOutOfTurn: .batter
        case .catcherInterference, .plateObstruction: .catcher
        case .obstruction, .fakeTag, .detachedEquipment: .defense
        case .runnerInterference, .runningLaneInterference, .coachInterference, .passingRunner: .offense
        }
    }

    var detail: String {
        switch self {
        case .balk: "有跑者时通常判跑者推进一垒"
        case .quickPitch: "按有无跑者记录投手犯规或坏球"
        case .illegalPitch: "按裁判宣判记录推进或坏球"
        case .foreignSubstance: "先记判罚，人员处置可随后调整"
        case .batterOutOfBox: "通常判打者出局、死球"
        case .batterInterference: "通常判打者出局，跑者返回"
        case .illegalBat: "通常判打者出局"
        case .battingOutOfTurn: "结果取决于何时申诉，保存后修正打序"
        case .catcherInterference: "通常判打者上一垒并强迫推进"
        case .plateObstruction: "按裁判最终判定确认跑者位置"
        case .obstruction: "确认受影响跑者最终获判位置"
        case .fakeTag: "确认裁判给予的跑者位置"
        case .detachedEquipment: "确认裁判给予的跑者位置"
        case .runnerInterference: "选择被判出局或返回的跑者"
        case .runningLaneInterference: "通常判打者跑者出局"
        case .coachInterference: "选择裁判宣判出局的跑者"
        case .passingRunner: "选择超越前位跑者而出局的跑者"
        }
    }

    var resolution: ViolationResolution {
        switch self {
        case .balk, .quickPitch, .illegalPitch: .ballOrAdvance
        case .batterOutOfBox, .batterInterference, .illegalBat, .runningLaneInterference: .batterOut
        case .catcherInterference: .batterFirst
        case .plateObstruction, .obstruction, .fakeTag, .detachedEquipment,
             .runnerInterference, .coachInterference, .passingRunner: .resolveRunners
        case .foreignSubstance, .battingOutOfTurn: .recordForReview
        }
    }

    var ballStatus: BallStatus {
        switch self {
        case .balk, .catcherInterference, .plateObstruction, .obstruction,
             .fakeTag, .detachedEquipment:
            .delayedDead
        case .batterOutOfBox, .batterInterference, .illegalBat,
             .battingOutOfTurn, .runnerInterference, .runningLaneInterference,
             .coachInterference:
            .dead
        case .passingRunner:
            .live
        case .quickPitch, .illegalPitch, .foreignSubstance:
            .umpireDecision
        }
    }
}

struct DefensivePlay: Identifiable, Hashable {
    let id: String
    let title: String
    let notation: String
    let routePositions: [FieldPosition]
    let assistPositions: [FieldPosition]
    let putoutPositions: [FieldPosition]
    let errorPosition: FieldPosition?

    var putoutPosition: FieldPosition? { putoutPositions.last }

    init(
        id: String,
        title: String,
        notation: String,
        assistPositions: [FieldPosition],
        putoutPosition: FieldPosition?,
        errorPosition: FieldPosition?,
        routePositions: [FieldPosition]? = nil,
        putoutPositions: [FieldPosition]? = nil
    ) {
        self.id = id
        self.title = title
        self.notation = notation
        self.assistPositions = assistPositions
        self.putoutPositions = putoutPositions ?? putoutPosition.map { [$0] } ?? []
        self.errorPosition = errorPosition
        self.routePositions = routePositions ?? assistPositions + (putoutPosition.map { [$0] } ?? [])
    }

    static let quickPlays: [DefensivePlay] = [
        DefensivePlay(id: "6-3", title: "游击传一垒", notation: "6-3", assistPositions: [.shortstop], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "4-3", title: "二垒传一垒", notation: "4-3", assistPositions: [.secondBase], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "5-3", title: "三垒传一垒", notation: "5-3", assistPositions: [.thirdBase], putoutPosition: .firstBase, errorPosition: nil),
        DefensivePlay(id: "F7", title: "左外野接杀", notation: "F7", assistPositions: [], putoutPosition: .leftField, errorPosition: nil),
        DefensivePlay(id: "F8", title: "中外野接杀", notation: "F8", assistPositions: [], putoutPosition: .centerField, errorPosition: nil),
        DefensivePlay(id: "F9", title: "右外野接杀", notation: "F9", assistPositions: [], putoutPosition: .rightField, errorPosition: nil),
        DefensivePlay(id: "6-4-3", title: "游击发起双杀", notation: "6-4-3", assistPositions: [.shortstop, .secondBase], putoutPosition: .firstBase, errorPosition: nil, putoutPositions: [.secondBase, .firstBase]),
        DefensivePlay(id: "5-4-3-TP", title: "三垒发起三杀", notation: "5-4-3 TP", assistPositions: [.thirdBase, .secondBase], putoutPosition: .firstBase, errorPosition: nil, putoutPositions: [.thirdBase, .secondBase, .firstBase]),
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

    static func caught(by position: FieldPosition) -> DefensivePlay {
        DefensivePlay(
            id: "F\(position.rawValue)",
            title: "\(position.fullName)接杀",
            notation: "F\(position.rawValue)",
            assistPositions: [],
            putoutPosition: position,
            errorPosition: nil
        )
    }

    static func custom(route: [FieldPosition], outcome: PlayOutcome) -> DefensivePlay {
        let outCount = min(outcome.recordedOuts, route.count)
        let putouts = outCount > 0 ? Array(route.suffix(outCount)) : []
        let assists = route.count > 1 ? Array(route.dropLast()) : []
        let notation = route.map { String($0.rawValue) }.joined(separator: "-")
            + (outcome == .triplePlay ? " TP" : outcome == .doublePlay ? " DP" : "")
        return DefensivePlay(
            id: "CUSTOM-\(notation)",
            title: "守备路线 \(notation)",
            notation: notation,
            assistPositions: assists,
            putoutPosition: putouts.last,
            errorPosition: nil,
            routePositions: route,
            putoutPositions: putouts
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
    var text: String
    let timestamp: Date
    var isIncomplete: Bool
    var reviewNote: String?

    init(
        id: UUID = UUID(),
        inning: Int,
        isTop: Bool,
        text: String,
        timestamp: Date = Date(),
        isIncomplete: Bool = false,
        reviewNote: String? = nil
    ) {
        self.id = id
        self.inning = inning
        self.isTop = isTop
        self.text = text
        self.timestamp = timestamp
        self.isIncomplete = isIncomplete
        self.reviewNote = reviewNote
    }

    var inningLabel: String { "\(isTop ? "上" : "下")\(inning)" }
}

enum ScoringEventCategory: String, CaseIterable, Identifiable, Codable {
    case game
    case clock
    case pitch
    case battedBall
    case runner
    case out
    case substitution
    case violation
    case tiebreak
    case correction

    var id: String { rawValue }

    var title: String {
        switch self {
        case .game: "比赛"
        case .clock: "计时"
        case .pitch: "投球"
        case .battedBall: "击球"
        case .runner: "跑垒"
        case .out: "出局"
        case .substitution: "换人"
        case .violation: "判罚"
        case .tiebreak: "延长／TB"
        case .correction: "修正"
        }
    }
}

struct RecordedRunnerMovement: Equatable, Codable {
    let playerID: UUID
    let origin: String
    let destination: String
}

struct RecordedBaseOccupancy: Equatable, Codable {
    let base: Base
    let playerID: UUID
}

struct GameSituationSnapshot: Equatable, Codable {
    let inning: Int
    let isTop: Bool
    let balls: Int
    let strikes: Int
    let outs: Int
    let homeRunsByInning: [Int]
    let awayRunsByInning: [Int]
    let homeBatterIndex: Int
    let awayBatterIndex: Int
    let baseRunners: [RecordedBaseOccupancy]
    let activeHomePitcherID: UUID?
    let activeAwayPitcherID: UUID?
}

/// Machine-readable companion to the Chinese play log. This is stored inside
/// the existing Core Data game payload so later stat corrections do not need to
/// parse display text.
struct ScoringEventRecord: Identifiable, Equatable, Codable {
    let id: UUID
    let logEntryID: UUID?
    let inning: Int
    let isTop: Bool
    let timestamp: Date
    var category: ScoringEventCategory
    var title: String
    var notation: String?
    var primaryPlayerID: UUID?
    var secondaryPlayerID: UUID?
    /// Stable owner of the at-bat containing this event. This is independent
    /// from the primary/secondary participants because a runner or fielder can
    /// be the principal actor while another player's plate appearance is live.
    var plateAppearanceBatterID: UUID?
    let runnerMovements: [RecordedRunnerMovement]
    var needsReview: Bool
    var ballStatus: BallStatus?
    var resolvedOutcome: PlayOutcome?
    /// Number of runs in this event scored by a TB automatic runner. Keeping it
    /// with the event lets a later review preserve earned-run responsibility.
    var automaticRunnerRuns: Int?
    var reviewNote: String?
    var reviewedAt: Date?
    var beforeSituation: GameSituationSnapshot?
    var afterSituation: GameSituationSnapshot?

    init(
        id: UUID = UUID(),
        logEntryID: UUID? = nil,
        inning: Int,
        isTop: Bool,
        timestamp: Date = Date(),
        category: ScoringEventCategory,
        title: String,
        notation: String? = nil,
        primaryPlayerID: UUID? = nil,
        secondaryPlayerID: UUID? = nil,
        plateAppearanceBatterID: UUID? = nil,
        runnerMovements: [RecordedRunnerMovement] = [],
        needsReview: Bool = false,
        ballStatus: BallStatus? = nil,
        resolvedOutcome: PlayOutcome? = nil,
        automaticRunnerRuns: Int? = nil,
        reviewNote: String? = nil,
        reviewedAt: Date? = nil,
        beforeSituation: GameSituationSnapshot? = nil,
        afterSituation: GameSituationSnapshot? = nil
    ) {
        self.id = id
        self.logEntryID = logEntryID
        self.inning = inning
        self.isTop = isTop
        self.timestamp = timestamp
        self.category = category
        self.title = title
        self.notation = notation
        self.primaryPlayerID = primaryPlayerID
        self.secondaryPlayerID = secondaryPlayerID
        self.plateAppearanceBatterID = plateAppearanceBatterID
        self.runnerMovements = runnerMovements
        self.needsReview = needsReview
        self.ballStatus = ballStatus
        self.resolvedOutcome = resolvedOutcome
        self.automaticRunnerRuns = automaticRunnerRuns
        self.reviewNote = reviewNote
        self.reviewedAt = reviewedAt
        self.beforeSituation = beforeSituation
        self.afterSituation = afterSituation
    }
}

struct PlateAppearanceRecord: Identifiable, Equatable {
    let id: UUID
    let sequence: Int
    let inning: Int
    let isTop: Bool
    let batter: Player
    let events: [ScoringEventRecord]
    let isComplete: Bool

    var needsReview: Bool { events.contains(where: \.needsReview) }
    var inningLabel: String { "第\(inning)局\(isTop ? "上" : "下")" }
    var resultText: String { events.last?.title ?? "打席进行中" }
    var chineseRecord: String {
        let details = events.map(\.title).joined(separator: "；")
        return "\(inningLabel)，第\(sequence)打席，#\(batter.number) \(batter.name)：\(details)"
    }
}

struct GameState: Equatable, Codable {
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
    var homeBattingOrderIDs: [UUID]
    var awayBattingOrderIDs: [UUID]
    /// Optional fields keep game payloads written by earlier app versions
    /// decodable while the formal scorekeeping model evolves.
    var scoringEvents: [ScoringEventRecord]? = nil
    var homeExitedPlayerIDs: [UUID]? = nil
    var awayExitedPlayerIDs: [UUID]? = nil
    var tiebreakPlacementKeys: [String]? = nil
    var automaticRunnerIDs: [UUID]? = nil
    /// Current defensive participants are stored independently from the
    /// batting order so DH games can keep the pitcher out of the batting order.
    var homeFieldingPlayerIDs: [UUID]? = nil
    var awayFieldingPlayerIDs: [UUID]? = nil
    var homeDesignatedHitterID: UUID? = nil
    var awayDesignatedHitterID: UUID? = nil
    /// Configured when the scorer enables TB during live scoring. One element
    /// means one automatic runner; the default remains a runner on second.
    var tiebreakRunnerBases: [Base]? = nil
    /// Set only when the scorer enables the competition's tiebreak procedure
    /// during live scoring; it is deliberately not a pregame setting.
    var tiebreakStartInning: Int? = nil
    var extraInningConfirmed: Bool? = nil
    /// The clock is stored with the game payload. While running, elapsed time is
    /// the accumulated value plus the interval since `clockRunningSince`.
    var clockRunningSince: Date? = nil
    var clockElapsedSeconds: TimeInterval? = nil
    var clockDisplayMode: GameClockDisplayMode? = nil
    var endedAt: Date? = nil
    var endReason: GameEndReason? = nil

    init(
        homeTeam: Team,
        awayTeam: Team,
        scheduledInnings: Int = 6,
        homeBattingOrderIDs: [UUID]? = nil,
        awayBattingOrderIDs: [UUID]? = nil
    ) {
        self.homeTeam = homeTeam
        self.awayTeam = awayTeam
        self.scheduledInnings = scheduledInnings
        self.homeRunsByInning = Array(repeating: 0, count: scheduledInnings)
        self.awayRunsByInning = Array(repeating: 0, count: scheduledInnings)
        self.activeHomePitcherID = homeTeam.players.first(where: { $0.primaryPosition == .pitcher })?.id
        self.activeAwayPitcherID = awayTeam.players.first(where: { $0.primaryPosition == .pitcher })?.id
        self.homeBattingOrderIDs = homeBattingOrderIDs ?? homeTeam.players.map(\.id)
        self.awayBattingOrderIDs = awayBattingOrderIDs ?? awayTeam.players.map(\.id)
    }

    var homeScore: Int { homeRunsByInning.reduce(0, +) }
    var awayScore: Int { awayRunsByInning.reduce(0, +) }
    var battingTeam: Team { isTop ? awayTeam : homeTeam }
    var fieldingTeam: Team { isTop ? homeTeam : awayTeam }
    var battingOrderIDs: [UUID] { isTop ? awayBattingOrderIDs : homeBattingOrderIDs }
    var fieldingPlayerIDs: [UUID] {
        if isTop { return homeFieldingPlayerIDs ?? homeBattingOrderIDs }
        return awayFieldingPlayerIDs ?? awayBattingOrderIDs
    }
    var battingOrderPlayers: [Player] {
        let players = battingOrderIDs.compactMap { id in
            battingTeam.players.first(where: { $0.id == id })
        }
        return players.isEmpty ? battingTeam.players : players
    }
    var currentBatter: Player {
        let lineup = battingOrderPlayers
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
    var situationSnapshot: GameSituationSnapshot {
        GameSituationSnapshot(
            inning: inning,
            isTop: isTop,
            balls: balls,
            strikes: strikes,
            outs: outs,
            homeRunsByInning: homeRunsByInning,
            awayRunsByInning: awayRunsByInning,
            homeBatterIndex: homeBatterIndex,
            awayBatterIndex: awayBatterIndex,
            baseRunners: baseRunners.map { RecordedBaseOccupancy(base: $0.key, playerID: $0.value.id) }
                .sorted { $0.base.rawValue < $1.base.rawValue },
            activeHomePitcherID: activeHomePitcherID,
            activeAwayPitcherID: activeAwayPitcherID
        )
    }
}
