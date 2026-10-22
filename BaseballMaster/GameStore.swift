import Combine
import Foundation

@MainActor
final class GameStore: ObservableObject {
    @Published var teams: [Team]
    @Published var opponentTeams: [Team]
    @Published var currentTeam: Team?
    @Published var game: GameState {
        didSet { persistCurrentGameIfNeeded() }
    }
    @Published var seasons: [Season]
    @Published var playerGameRecords: [PlayerGameRecord]
    @Published var archivedTeams: [Team] = []
    @Published var games: [StoredGame]
    @Published var storageErrorMessage: String?
    @Published var actionErrorMessage: String?
    @Published private(set) var requiresDataRecovery = false
    @Published var selectedTab = 0
    @Published var gameNavigationID = UUID()

    lazy var liveBroadcasts: LiveBroadcastManager = {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--live-production-persistent-test") {
            return LiveBroadcastManager(store: self, transport: LiveIntegrationTransport.shared, enabled: AppFeatureAvailability.liveBroadcast)
        }
        if sourceDatabaseURL == nil && ProcessInfo.processInfo.arguments.contains("--live-local-test") {
            let base = URL(string: "http://127.0.0.1:18088/livestreaming/novideo")!
            // Release artwork uses the configured public URL while all demo traffic remains local.
            let viewBase = ProcessInfo.processInfo.arguments.contains("--live-release-preview") ? LiveHTTPClient.baseURL : base
            return LiveBroadcastManager(store: self, vault: LivePreviewCredentials(), transport: LiveHTTPClient(baseURL: base), enabled: true, viewBaseURL: viewBase)
        }
        #endif
        return LiveBroadcastManager(store: self, enabled: AppFeatureAvailability.liveBroadcast && sourceDatabaseURL != nil)
    }()

    var isHistoryReplay = false
    var historyReplayDate: Date?
    var historyPendingCommand: HistoryCommand?
    var historySuppressCapture = false
    private var actionDepth = 0
    private var actionOriginal: GameState?
    private var actionOriginalGames: [StoredGame] = []
    private var actionOriginalActiveID: UUID?
    private var actionUndo: [GameState] = []
    private var actionRedo: [GameState] = []
    private var actionEffectiveScorers: [UUID]?
    private var undoStack: [GameState] = []
    var historyPreviousActionState: GameState? { undoStack.last }
    func remapHistoryUndoIdentities(events: [UUID: UUID], logs: [UUID: UUID], appearances: [UUID: UUID]) {
        guard isHistoryReplay else { return }
        for i in undoStack.indices {
            for j in undoStack[i].playLog.indices { undoStack[i].playLog[j].id = logs[undoStack[i].playLog[j].id] ?? undoStack[i].playLog[j].id }
            for j in undoStack[i].scoringEvents?.indices ?? 0..<0 {
                let old = undoStack[i].scoringEvents![j]
                undoStack[i].scoringEvents?[j].id = events[old.id] ?? old.id
                if let id = old.logEntryID { undoStack[i].scoringEvents?[j].logEntryID = logs[id] ?? id }
                if let id = old.plateAppearanceID { undoStack[i].scoringEvents?[j].plateAppearanceID = appearances[id] ?? id }
            }
            for j in undoStack[i].plateAppearances?.indices ?? 0..<0 {
                let old = undoStack[i].plateAppearances![j]
                undoStack[i].plateAppearances?[j].id = appearances[old.id] ?? old.id
                if let id = old.previousSegmentID { undoStack[i].plateAppearances?[j].previousSegmentID = appearances[id] ?? id }
            }
            if let id = undoStack[i].currentPlateAppearanceID { undoStack[i].currentPlateAppearanceID = appearances[id] ?? id }
            if let id = undoStack[i].homeInterruptedAppearanceID { undoStack[i].homeInterruptedAppearanceID = appearances[id] ?? id }
            if let id = undoStack[i].awayInterruptedAppearanceID { undoStack[i].awayInterruptedAppearanceID = appearances[id] ?? id }
        }
    }
    private var redoStack: [GameState] = []
    var nextEventBeforeSituation: GameSituationSnapshot?
    private var persistenceStore: CoreDataRosterStore
    private var databaseURL: URL?
    private let sourceDatabaseURL: URL?
    var activeGameID: UUID?

    convenience init() {
        self.init(
            persistenceURL: Self.defaultDatabaseURL,
            legacyJSONURL: Self.defaultLegacyJSONURL
        )
    }

    init(persistenceURL: URL?, legacyJSONURL: URL? = nil) {
        var databaseStore: CoreDataRosterStore
        var initialStorageError: String?
        var databaseSnapshot: RosterSnapshot?
        var openedStore: CoreDataRosterStore?
        do {
            if let legacyJSONURL, FileManager.default.fileExists(atPath: legacyJSONURL.path) {
                let original = try Data(contentsOf: legacyJSONURL)
                let archive = legacyJSONURL.deletingLastPathComponent().appendingPathComponent("legacy-before-v3.json")
                if !FileManager.default.fileExists(atPath: archive.path) {
                    try original.write(to: archive, options: .atomic)
                    guard try Data(contentsOf: archive) == original else { throw LocalDataError.invalid("旧 JSON 备份校验失败") }
                }
            }
            let opened = try CoreDataRosterStore(storeURL: persistenceURL)
            openedStore = opened
            databaseSnapshot = try opened.loadSnapshot()
            databaseStore = opened
        } catch {
            try? openedStore?.close()
            NSLog("Unable to open the local Core Data store: %@", String(describing: error))
            databaseStore = try! CoreDataRosterStore(storeURL: nil)
            initialStorageError = "本地数据无法完整读取，原文件已保留。请从备份恢复后继续使用。"
        }

        var waves = Self.makeWaves()
        var falcons = Self.makeFalcons()
        var rockets = Self.makeRockets()
        waves.isDemo = true; falcons.isDemo = true; rockets.isDemo = true
        let defaultSeasons = Self.makeSeasons()
        let legacySnapshot = databaseSnapshot == nil && initialStorageError == nil
            ? legacyJSONURL.flatMap(Self.loadLegacyRosterData(from:))
            : nil
        if databaseSnapshot == nil, legacySnapshot == nil, let legacyJSONURL,
           FileManager.default.fileExists(atPath: legacyJSONURL.path), initialStorageError == nil {
            initialStorageError = "旧版数据文件无法读取，原文件已保留。请导入有效备份后继续。"
        }
        let persisted = databaseSnapshot ?? legacySnapshot
        let loadedTeams = persisted?.teams ?? (initialStorageError == nil ? [waves] : [])
        let needsLegacyDatabaseDefaults = databaseStore.migratedLegacySchema
            || (databaseStore.loadedSchemaVersion.map { $0 < CoreDataRosterStore.schemaVersion } ?? false)
        let loadedOpponentTeams = persisted?.opponentTeams ?? (initialStorageError == nil ? [falcons, rockets] : [])
        let loadedCurrentTeam = loadedTeams.first(where: { $0.id == persisted?.currentTeamID }) ?? loadedTeams.first
        let loadedSeasons = persisted?.seasons ?? defaultSeasons
        // A new formal installation starts with no fabricated box scores.
        // Player statistics are created only from locally recorded games.
        let loadedRecords = persisted?.playerGameRecords ?? []
        let loadedGames = persisted?.games.sorted { $0.updatedAt > $1.updatedAt } ?? []

        self.persistenceStore = databaseStore
        self.sourceDatabaseURL = persistenceURL
        self.databaseURL = databaseStore.resolvedURL ?? persistenceURL
        self.archivedTeams = persisted?.archivedTeams ?? []
        self.teams = loadedTeams
        self.opponentTeams = loadedOpponentTeams
        self.currentTeam = loadedCurrentTeam
        self.seasons = loadedSeasons
        self.playerGameRecords = loadedRecords
        self.games = loadedGames
        self.storageErrorMessage = initialStorageError
        self.actionErrorMessage = nil
        self.requiresDataRecovery = initialStorageError != nil

        let latestOngoing = loadedGames.first(where: { $0.status == .ongoing })
        if let latestOngoing {
            self.game = latestOngoing.state
            self.activeGameID = latestOngoing.id
            if self.game.clockRunningSince == nil,
               self.game.clockElapsedSeconds == nil,
               let legacyStartedAt = latestOngoing.startedAt {
                self.game.clockElapsedSeconds = 0
                self.game.clockRunningSince = legacyStartedAt
                self.game.clockDisplayMode = .elapsed
            }
        } else {
            let initialAway = (loadedCurrentTeam?.players.count ?? 0) >= 9 ? loadedCurrentTeam! : waves
            let initialHome = loadedOpponentTeams.first(where: { $0.players.count >= 9 }) ?? falcons
            self.game = GameState(homeTeam: initialHome, awayTeam: initialAway, scheduledInnings: 6)
            self.activeGameID = nil
        }
        if (databaseSnapshot == nil || needsLegacyDatabaseDefaults) && initialStorageError == nil {
            let initialSnapshot = makeRosterSnapshot()
            do {
                try databaseStore.replaceAll(with: initialSnapshot)
                if legacySnapshot != nil, let legacyJSONURL {
                    Self.archiveLegacyRosterData(at: legacyJSONURL)
                }
            } catch {
                NSLog("Unable to initialize the local Core Data store: %@", String(describing: error))
                requiresDataRecovery = true
                storageErrorMessage = "初始化本地数据库失败，已停止写入并保留原资料。"
            }
        }
        if !requiresDataRecovery {
            do {
                try migrateStoredAppearanceIdentities()
                createAutomaticBackup()
            } catch {
                requiresDataRecovery = true
                storageErrorMessage = "打席资料升级未能保存，已停止写入并保留原资料。请重试或从备份恢复。"
            }
        }
    }

    private func migrateStoredAppearanceIdentities() throws {
        let originalGames = games
        let initialGame = game
        let storedID = activeGameID
        activeGameID = nil
        for index in games.indices {
            game = games[index].state
            migrateAppearanceIdentities()
            if games[index].status == .ongoing { ensureCurrentAppearance() }
            games[index].state = game
        }
        game = storedID.flatMap { id in games.first { $0.id == id }?.state } ?? initialGame
        activeGameID = storedID
        do {
            if games != originalGames { try persistenceStore.replaceAll(with: makeRosterSnapshot()) }
        } catch {
            activeGameID = nil
            games = originalGames
            game = initialGame
            activeGameID = storedID
            throw error
        }
    }

    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }
    var hasRunners: Bool { !game.baseRunners.isEmpty }
    var currentBatter: Player { game.currentBatter }
    var currentPitcher: Player { game.currentPitcher }
    var ongoingGames: [StoredGame] {
        games.filter { $0.status == .ongoing }.sorted { $0.updatedAt > $1.updatedAt }
    }
    var recentGames: [StoredGame] {
        games.filter { $0.status == .completed }.sorted { $0.updatedAt > $1.updatedAt }
    }
    var scheduledGames: [StoredGame] {
        games.filter { $0.status == .scheduled }.sorted { $0.effectiveScheduledAt < $1.effectiveScheduledAt }
    }
    var activeGameRecordedAt: Date? {
        guard let stored = games.first(where: { $0.id == activeGameID }) else { return nil }
        return stored.startedAt ?? stored.scheduledAt ?? stored.createdAt
    }
    var activeStoredGame: StoredGame? { games.first { $0.id == activeGameID } }
    var activeRules: GameRules? {
        activeGameID.flatMap { id in games.first(where: { $0.id == id })?.rules }
    }
    private var activeRulesOrDefault: GameRules {
        activeRules ?? GameRules(scheduledInnings: game.scheduledInnings)
    }
    var requiresTiebreakRunnerPlacement: Bool {
        guard !game.isFinal,
              let startInning = game.tiebreakStartInning,
              game.inning >= startInning else { return false }
        let keys = Set(game.tiebreakPlacementKeys ?? [])
        if keys.contains(currentHalfKey) { return false }
        return tiebreakRunnerBases.contains { !keys.contains(tiebreakPlacementKey(for: $0)) }
    }
    var recommendedTiebreakRunner: Player? {
        let lineup = game.battingOrderPlayers
        guard !lineup.isEmpty else { return nil }
        let currentIndex = game.isTop ? game.awayBatterIndex : game.homeBatterIndex
        let occupiedIDs = Set(game.baseRunners.values.map(\.id))
        for offset in 1..<lineup.count {
            let index = (currentIndex - offset + lineup.count) % lineup.count
            let player = lineup[index]
            if !occupiedIDs.contains(player.id) {
                return player
            }
        }
        return nil
    }
    var eligibleTiebreakRunners: [Player] {
        let batterID = currentBatter.id
        return game.battingOrderPlayers.filter { player in
            player.id != batterID
                && !(game.baseRunners.values.contains { $0.id == player.id })
        }
    }
    var battingBenchPlayers: [Player] {
        availableBenchPlayers(forHomeTeam: !game.isTop)
    }
    var fieldingBenchPlayers: [Player] {
        availableBenchPlayers(forHomeTeam: game.isTop)
    }
    var availablePitchers: [Player] {
        var players = activeFielders.filter { $0.id != currentPitcher.id } + fieldingBenchPlayers
        let dh = game.isTop ? game.homeDesignatedHitterID : game.awayDesignatedHitterID
        if activeRulesOrDefault.twoWayPlayerEnabled, let dh, dh != currentPitcher.id,
           !players.contains(where: { $0.id == dh }), let player = game.fieldingTeam.players.first(where: { $0.id == dh }) {
            players.append(player)
        }
        return players
    }

    var activeFielders: [Player] {
        let team = game.fieldingTeam
        let ids = activeFieldingPlayerIDs(forHomeTeam: game.isTop)
        return team.sortedPlayers.filter { ids.contains($0.id) }
    }
    var tiebreakRunnerBases: [Base] {
        let configured = game.tiebreakRunnerBases ?? [.second]
        return configured.isEmpty ? [.second] : configured
    }
    var nextTiebreakRunnerBase: Base? {
        let keys = Set(game.tiebreakPlacementKeys ?? [])
        return tiebreakRunnerBases.first { base in
            !keys.contains(tiebreakPlacementKey(for: base)) && game.baseRunners[base] == nil
        }
    }
    var canConfirmExtraInning: Bool {
        !game.isFinal
            && game.inning > game.scheduledInnings
            && game.homeScore == game.awayScore
            && game.extraInningConfirmed != true
    }
    var canEnableTiebreak: Bool {
        !game.isFinal
            && game.inning > game.scheduledInnings
            && game.tiebreakStartInning == nil
    }
    var hasStartedGameClock: Bool {
        game.clockRunningSince != nil || (game.clockElapsedSeconds ?? 0) > 0
    }
    var isGameClockRunning: Bool { game.clockRunningSince != nil }
    var gameClockDisplayMode: GameClockDisplayMode {
        game.clockDisplayMode ?? .elapsed
    }
    var canShowRemainingGameTime: Bool { activeRules?.timeLimitMinutes != nil }
    var pendingReviewEvents: [ScoringEventRecord] {
        (game.scoringEvents ?? []).filter(\.needsReview)
    }

    func elapsedGameTime(at date: Date = Date()) -> TimeInterval {
        let accumulated = max(0, game.clockElapsedSeconds ?? 0)
        guard let runningSince = game.clockRunningSince else { return accumulated }
        return accumulated + max(0, date.timeIntervalSince(runningSince))
    }

    func remainingGameTime(at date: Date = Date()) -> TimeInterval? {
        guard let minutes = activeRules?.timeLimitMinutes else { return nil }
        return TimeInterval(minutes * 60) - elapsedGameTime(at: date)
    }

    func gameClockText(at date: Date = Date()) -> String {
        let interval: TimeInterval
        if gameClockDisplayMode == .remaining,
           let remaining = remainingGameTime(at: date) {
            interval = max(0, remaining)
        } else {
            interval = elapsedGameTime(at: date)
        }
        let totalSeconds = max(0, Int(interval.rounded(.down)))
        return String(format: "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    func startGameClock(at date: Date = Date()) {
        let date = historyReplayDate ?? date
        beginAtomicAction()
        recordHistoryCommand(.clock("start", date))

        defer { commitAtomicAction() }
        guard !game.isFinal, game.clockRunningSince == nil else { return }
        finalizeLatestEventSituation()
        nextEventBeforeSituation = game.situationSnapshot
        let isFirstStart = !hasStartedGameClock
        if isFirstStart {
            game.clockElapsedSeconds = 0
            game.clockDisplayMode = .elapsed
            if let activeGameID,
               let index = games.firstIndex(where: { $0.id == activeGameID }) {
                games[index].startedAt = date
            }
        }
        game.clockRunningSince = date
        addLog(
            isFirstStart ? "Play Ball，比赛计时开始" : "比赛计时继续",
            category: .clock,
            notation: isFirstStart ? "PLAY" : "RESUME"
        )
        persistActiveStoredGameMetadata()
    }

    func pauseGameClock(at date: Date = Date()) {
        beginAtomicAction()
        recordHistoryCommand(.clock("pause", date))

        defer { commitAtomicAction() }
        guard let runningSince = game.clockRunningSince else { return }
        finalizeLatestEventSituation()
        nextEventBeforeSituation = game.situationSnapshot
        game.clockElapsedSeconds = max(0, game.clockElapsedSeconds ?? 0)
            + max(0, date.timeIntervalSince(runningSince))
        game.clockRunningSince = nil
        addLog("比赛计时暂停", category: .clock, notation: "PAUSE")
    }

    func toggleGameClockDisplayMode() {
        beginAtomicAction()
        recordHistoryCommand(.clock("display", Date()))

        defer { commitAtomicAction() }
        guard canShowRemainingGameTime else {
            game.clockDisplayMode = .elapsed
            return
        }
        game.clockDisplayMode = gameClockDisplayMode == .elapsed ? .remaining : .elapsed
    }

    func returnToGameHome() {
        selectedTab = 0
        gameNavigationID = UUID()
    }

    @discardableResult
    func scheduleGame(
        ourTeam: Team,
        opponent: Team,
        isHome: Bool,
        scheduledAt: Date
    ) -> UUID {
        let home = isHome ? ourTeam : opponent
        let away = isHome ? opponent : ourTeam
        var state = GameState(
            homeTeam: home,
            awayTeam: away,
            homeBattingOrderIDs: [],
            awayBattingOrderIDs: []
        )
        state.playLog.append(
            PlayLogEntry(inning: 1, isTop: true, text: "比赛已安排：\(away.shortName) 对 \(home.shortName)")
        )
        let stored = StoredGame(
            scheduledAt: scheduledAt,
            startedAt: nil,
            seasonID: seasons.first?.id ?? "unspecified",
            ourTeamID: ourTeam.id,
            opponentTeamID: opponent.id,
            isHome: isHome,
            rules: GameRules(),
            lineup: [],
            status: .scheduled,
            state: state
        )
        games.insert(stored, at: 0)
        persist { try persistenceStore.upsertGame(stored) }
        return stored.id
    }

    @discardableResult
    func scheduleObservedGame(
        awayTeam: Team,
        homeTeam: Team,
        scheduledAt: Date
    ) -> UUID {
        var state = GameState(
            homeTeam: homeTeam,
            awayTeam: awayTeam,
            homeBattingOrderIDs: [],
            awayBattingOrderIDs: []
        )
        state.playLog.append(
            PlayLogEntry(inning: 1, isTop: true, text: "观赛已安排：\(awayTeam.shortName) 对 \(homeTeam.shortName)")
        )
        let stored = StoredGame(
            scheduledAt: scheduledAt,
            startedAt: nil,
            seasonID: seasons.first?.id ?? "unspecified",
            ourTeamID: nil,
            opponentTeamID: nil,
            isHome: false,
            isSpectator: true,
            rules: GameRules(),
            lineup: [],
            secondaryLineup: [],
            status: .scheduled,
            state: state
        )
        games.insert(stored, at: 0)
        persist { try persistenceStore.upsertGame(stored) }
        return stored.id
    }

    func startNewGame(opponent: Team, isHome: Bool, innings: Int, lineup: [Player]) {
        let assignments = lineup.enumerated().map { index, player in
            LineupAssignment(
                playerID: player.id,
                battingOrder: index + 1,
                position: FieldPosition.allCases[index % FieldPosition.allCases.count]
            )
        }
        _ = startNewGame(
            opponent: opponent,
            isHome: isHome,
            rules: GameRules(scheduledInnings: innings),
            lineup: assignments
        )
    }

    @discardableResult
    func startNewGame(
        opponent: Team,
        isHome: Bool,
        rules: GameRules,
        lineup: [LineupAssignment],
        scheduledAt: Date = Date(),
        startImmediately: Bool = true
    ) -> UUID? {
        let orderedLineup = lineup.sorted { $0.battingOrder < $1.battingOrder }
        guard var ourTeam = currentTeam, !requiresDataRecovery else { actionErrorMessage = "请先创建并选择球队。"; return nil }
        for assignment in orderedLineup {
            if let index = ourTeam.players.firstIndex(where: { $0.id == assignment.playerID }) {
                ourTeam.players[index].primaryPosition = assignment.position
            }
        }

        var gameOpponent = opponent
        for index in gameOpponent.players.indices.prefix(rules.fieldersCount) {
            gameOpponent.players[index].primaryPosition = FieldPosition.allCases[index % FieldPosition.allCases.count]
        }

        let opponentLineup = defaultAssignments(for: gameOpponent, count: rules.fieldersCount)
        let ourParticipation = configuredParticipation(for: ourTeam, assignments: orderedLineup, rules: rules)
        let opponentParticipation = configuredParticipation(for: gameOpponent, assignments: opponentLineup, rules: rules)
        let ourBattingOrder = ourParticipation.battingOrder
        let opponentBattingOrder = opponentParticipation.battingOrder
        let home = isHome ? ourTeam : gameOpponent
        let away = isHome ? gameOpponent : ourTeam
        var newState = GameState(
            homeTeam: home,
            awayTeam: away,
            scheduledInnings: rules.scheduledInnings,
            homeBattingOrderIDs: isHome ? ourBattingOrder : opponentBattingOrder,
            awayBattingOrderIDs: isHome ? opponentBattingOrder : ourBattingOrder
        )
        applyParticipation(ourParticipation, forHomeTeam: isHome, to: &newState)
        applyParticipation(opponentParticipation, forHomeTeam: !isHome, to: &newState)
        newState.playLog.append(
            PlayLogEntry(
                inning: 1,
                isTop: true,
                text: startImmediately
                    ? "已进入现场记分：\(away.shortName) 对 \(home.shortName)，等待 Play Ball"
                    : "比赛已安排：\(away.shortName) 对 \(home.shortName)"
            )
        )

        let stored = StoredGame(
            scheduledAt: scheduledAt,
            startedAt: nil,
            seasonID: seasons.first?.id ?? "unspecified",
            ourTeamID: currentTeam?.id,
            opponentTeamID: opponent.id,
            isHome: isHome,
            rules: rules,
            lineup: orderedLineup,
            status: startImmediately ? .ongoing : .scheduled,
            state: newState
        )
        do { try persistenceStore.upsertGame(stored) }
        catch { storageErrorMessage = error.localizedDescription; return nil }
        games.insert(stored, at: 0)
        if startImmediately {
            activeGameID = nil
            game = newState
            undoStack.removeAll()
            redoStack.removeAll()
            activeGameID = stored.id
            initializeAppearanceForOpenedGame()
        }
        return stored.id
    }

    @discardableResult
    func createObservedGame(
        awayTeam: Team,
        homeTeam: Team,
        rules: GameRules,
        awayLineup requestedAwayLineup: [LineupAssignment]? = nil,
        homeLineup requestedHomeLineup: [LineupAssignment]? = nil,
        scheduledAt: Date = Date(),
        startImmediately: Bool = true
    ) -> UUID? {
        let awayLineup = (requestedAwayLineup ?? defaultAssignments(for: awayTeam, count: rules.fieldersCount))
            .sorted { $0.battingOrder < $1.battingOrder }
        let homeLineup = (requestedHomeLineup ?? defaultAssignments(for: homeTeam, count: rules.fieldersCount))
            .sorted { $0.battingOrder < $1.battingOrder }
        let configuredAway = applying(awayLineup, to: awayTeam)
        let configuredHome = applying(homeLineup, to: homeTeam)
        let awayParticipation = configuredParticipation(for: configuredAway, assignments: awayLineup, rules: rules)
        let homeParticipation = configuredParticipation(for: configuredHome, assignments: homeLineup, rules: rules)
        var state = GameState(
            homeTeam: configuredHome,
            awayTeam: configuredAway,
            scheduledInnings: rules.scheduledInnings,
            homeBattingOrderIDs: homeParticipation.battingOrder,
            awayBattingOrderIDs: awayParticipation.battingOrder
        )
        applyParticipation(homeParticipation, forHomeTeam: true, to: &state)
        applyParticipation(awayParticipation, forHomeTeam: false, to: &state)
        state.playLog.append(
            PlayLogEntry(
                inning: 1,
                isTop: true,
                text: startImmediately
                    ? "已进入观赛记分：\(awayTeam.shortName) 对 \(homeTeam.shortName)，等待 Play Ball"
                    : "观赛已安排：\(awayTeam.shortName) 对 \(homeTeam.shortName)"
            )
        )
        let stored = StoredGame(
            scheduledAt: scheduledAt,
            startedAt: nil,
            seasonID: seasons.first?.id ?? "unspecified",
            ourTeamID: nil,
            opponentTeamID: nil,
            isHome: false,
            isSpectator: true,
            rules: rules,
            lineup: awayLineup,
            secondaryLineup: homeLineup,
            status: startImmediately ? .ongoing : .scheduled,
            state: state
        )
        do { try persistenceStore.upsertGame(stored) }
        catch { storageErrorMessage = error.localizedDescription; return nil }
        games.insert(stored, at: 0)
        if startImmediately {
            activeGameID = nil
            game = state
            undoStack.removeAll()
            redoStack.removeAll()
            activeGameID = stored.id
            initializeAppearanceForOpenedGame()
        }
        return stored.id
    }

    func resetPracticeInning() {
        activeGameID = nil
        let awayTeam = teams.first(where: { $0.players.count >= 9 }) ?? Self.makeWaves()
        let homeTeam = opponentTeams.first(where: { $0.players.count >= 9 }) ?? Self.makeFalcons()
        game = GameState(homeTeam: homeTeam, awayTeam: awayTeam, scheduledInnings: 1)
        undoStack.removeAll()
        redoStack.removeAll()
        addLog("练习开始：所有记录只用于本次练习")
    }

    func team(withID id: UUID) -> Team? {
        teams.first(where: { $0.id == id })
    }

    func opponentTeam(withID id: UUID) -> Team? {
        opponentTeams.first(where: { $0.id == id })
    }

    func player(withID playerID: UUID, in teamID: UUID) -> Player? {
        team(withID: teamID)?.players.first(where: { $0.id == playerID })
    }

    @discardableResult
    func addTeam(name: String, shortName: String, city: String) -> UUID {
        let team = Team(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            shortName: shortName.trimmingCharacters(in: .whitespacesAndNewlines),
            city: city.trimmingCharacters(in: .whitespacesAndNewlines),
            players: []
        )
        teams.append(team)
        if currentTeam == nil { currentTeam = team }
        persist {
            try persistenceStore.replaceAll(with: makeRosterSnapshot())
        }
        return team.id
    }

    func updateTeam(id: UUID, name: String, shortName: String, city: String) {
        guard let index = teams.firstIndex(where: { $0.id == id }) else { return }
        teams[index].name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        teams[index].shortName = shortName.trimmingCharacters(in: .whitespacesAndNewlines)
        teams[index].city = city.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentTeam?.id == id {
            currentTeam = teams[index]
        }
        let updatedTeam = teams[index]
        persist {
            try persistenceStore.upsertTeam(updatedTeam, sortOrder: index)
        }
    }

    @discardableResult
    func deleteTeam(id: UUID) -> Bool {
        guard !requiresDataRecovery, let index = teams.firstIndex(where: { $0.id == id }) else { return false }
        if let reason = teamDeletionIssue(id) { actionErrorMessage = reason; return false }
        let original = makeRosterSnapshot()
        var archived = teams[index]
        if let previous = archivedTeams.first(where: { $0.id == id }) {
            archived.players += previous.players.filter { old in !archived.players.contains { $0.id == old.id } }
        }
        archivedTeams.removeAll { $0.id == id }
        archivedTeams.append(archived)
        teams.remove(at: index)
        if currentTeam?.id == id { currentTeam = teams.first }
        do { try persistenceStore.replaceAll(with: makeRosterSnapshot()) }
        catch { teams = original.teams; archivedTeams = original.archivedTeams
            currentTeam = teams.first { $0.id == original.currentTeamID }
            storageErrorMessage = error.localizedDescription; return false }
        return true
    }

    func teamDeletionIssue(_ id: UUID) -> String? {
        let linked = games.filter { $0.status != .completed && ($0.state.homeTeam.id == id || $0.state.awayTeam.id == id) }
        guard !linked.isEmpty else { return nil }
        return "球队有未结束的比赛，不能删除。请先在比赛／赛程中处理：" + linked.map {
            "\($0.state.awayTeam.name) 对 \($0.state.homeTeam.name)"
        }.joined(separator: "、")
    }

    var statisticsTeams: [Team] {
        var result = teams + archivedTeams.filter { archived in !teams.contains { $0.id == archived.id } }
        for stored in games where !stored.isObservation && stored.ourTeamID != nil {
            if !result.contains(where: { $0.id == stored.ourTeam.id }) { result.append(stored.ourTeam) }
        }
        return result
    }

    func historicalPersonalPlayers(teamID: UUID) -> [Player] {
        let ids = Set(playerGameRecords.map(\.playerID))
        var players = (teams + archivedTeams).filter { $0.id == teamID }.flatMap(\.players)
        var seen = Set<UUID>()
        players = players.filter { ids.contains($0.id) && seen.insert($0.id).inserted }
        return players.sorted(by: Player.rosterOrder)
    }

    func setCurrentTeam(id: UUID) {
        guard let team = team(withID: id) else { return }
        currentTeam = team
        persist {
            try persistenceStore.setCurrentTeamID(id)
        }
    }

    @discardableResult
    func addOpponentTeam(name: String, shortName: String, city: String) -> UUID {
        let team = Team(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            shortName: shortName.trimmingCharacters(in: .whitespacesAndNewlines),
            city: city.trimmingCharacters(in: .whitespacesAndNewlines),
            players: []
        )
        opponentTeams.append(team)
        persist {
            try persistenceStore.upsertTeam(team, sortOrder: opponentTeams.count - 1, isOpponent: true)
        }
        return team.id
    }

    func updateOpponentTeam(id: UUID, name: String, shortName: String, city: String) {
        guard let index = opponentTeams.firstIndex(where: { $0.id == id }) else { return }
        opponentTeams[index].name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        opponentTeams[index].shortName = shortName.trimmingCharacters(in: .whitespacesAndNewlines)
        opponentTeams[index].city = city.trimmingCharacters(in: .whitespacesAndNewlines)
        let updated = opponentTeams[index]
        persist {
            try persistenceStore.upsertTeam(updated, sortOrder: index, isOpponent: true)
        }
    }

    @discardableResult
    func deleteOpponentTeam(id: UUID) -> Bool {
        guard !requiresDataRecovery, let index = opponentTeams.firstIndex(where: { $0.id == id }) else { return false }
        if let reason = teamDeletionIssue(id) { actionErrorMessage = reason; return false }
        let team = opponentTeams.remove(at: index)
        do { try persistenceStore.replaceAll(with: makeRosterSnapshot()) }
        catch { opponentTeams.insert(team, at: index); storageErrorMessage = error.localizedDescription; return false }
        return true
    }

    @discardableResult
    func addOpponentPlayer(
        to teamID: UUID,
        chineseName: String,
        englishName: String,
        numbers: [Int],
        numberTexts: [String]? = nil
    ) -> UUID? {
        guard let teamIndex = opponentTeams.firstIndex(where: { $0.id == teamID }) else { return nil }
        let position = FieldPosition.allCases[opponentTeams[teamIndex].players.count % FieldPosition.allCases.count]
        var player = Player(
            chineseName: chineseName.trimmingCharacters(in: .whitespacesAndNewlines),
            englishName: englishName.trimmingCharacters(in: .whitespacesAndNewlines),
            numbers: numberTexts?.compactMap(Int.init) ?? normalizedNumbers(numbers),
            primaryPosition: position
        )
        player.jerseyNumbers = numberTexts
        opponentTeams[teamIndex].players.append(player)
        persist {
            try persistenceStore.upsertPlayer(
                player,
                rosterTeamID: teamID,
                sortOrder: opponentTeams[teamIndex].players.count - 1
            )
        }
        return player.id
    }

    func updateOpponentPlayer(
        in teamID: UUID,
        playerID: UUID,
        chineseName: String,
        englishName: String,
        numbers: [Int],
        numberTexts: [String]? = nil
    ) {
        guard let teamIndex = opponentTeams.firstIndex(where: { $0.id == teamID }),
              let playerIndex = opponentTeams[teamIndex].players.firstIndex(where: { $0.id == playerID }) else { return }
        opponentTeams[teamIndex].players[playerIndex].chineseName = chineseName.trimmingCharacters(in: .whitespacesAndNewlines)
        opponentTeams[teamIndex].players[playerIndex].englishName = englishName.trimmingCharacters(in: .whitespacesAndNewlines)
        opponentTeams[teamIndex].players[playerIndex].numbers = numberTexts?.compactMap(Int.init) ?? normalizedNumbers(numbers)
        opponentTeams[teamIndex].players[playerIndex].jerseyNumbers = numberTexts
        let updated = opponentTeams[teamIndex].players[playerIndex]
        persist {
            try persistenceStore.upsertPlayer(updated, rosterTeamID: teamID, sortOrder: playerIndex)
        }
    }

    func deleteOpponentPlayer(from teamID: UUID, playerID: UUID) {
        guard let teamIndex = opponentTeams.firstIndex(where: { $0.id == teamID }) else { return }
        opponentTeams[teamIndex].players.removeAll { $0.id == playerID }
        persist { try persistenceStore.deletePlayer(id: playerID) }
    }

    @discardableResult
    func addPlayer(
        to teamID: UUID,
        chineseName: String,
        englishName: String,
        numbers: [Int],
        numberTexts: [String]? = nil
    ) -> UUID? {
        guard let teamIndex = teams.firstIndex(where: { $0.id == teamID }) else { return nil }
        let position = FieldPosition.allCases[teams[teamIndex].players.count % FieldPosition.allCases.count]
        var player = Player(
            chineseName: chineseName.trimmingCharacters(in: .whitespacesAndNewlines),
            englishName: englishName.trimmingCharacters(in: .whitespacesAndNewlines),
            numbers: numberTexts?.compactMap(Int.init) ?? normalizedNumbers(numbers),
            primaryPosition: position
        )
        player.jerseyNumbers = numberTexts
        teams[teamIndex].players.append(player)
        syncCurrentTeamIfNeeded(teamID)
        persist {
            try persistenceStore.upsertPlayer(
                player,
                rosterTeamID: teamID,
                sortOrder: teams[teamIndex].players.count - 1
            )
        }
        return player.id
    }

    func updatePlayer(
        in teamID: UUID,
        playerID: UUID,
        chineseName: String,
        englishName: String,
        numbers: [Int],
        numberTexts: [String]? = nil
    ) {
        guard let teamIndex = teams.firstIndex(where: { $0.id == teamID }),
              let playerIndex = teams[teamIndex].players.firstIndex(where: { $0.id == playerID }) else { return }
        teams[teamIndex].players[playerIndex].chineseName = chineseName.trimmingCharacters(in: .whitespacesAndNewlines)
        teams[teamIndex].players[playerIndex].englishName = englishName.trimmingCharacters(in: .whitespacesAndNewlines)
        teams[teamIndex].players[playerIndex].numbers = numberTexts?.compactMap(Int.init) ?? normalizedNumbers(numbers)
        teams[teamIndex].players[playerIndex].jerseyNumbers = numberTexts
        syncCurrentTeamIfNeeded(teamID)
        let updatedPlayer = teams[teamIndex].players[playerIndex]
        persist {
            try persistenceStore.upsertPlayer(
                updatedPlayer,
                rosterTeamID: teamID,
                sortOrder: playerIndex
            )
        }
    }

    func deletePlayer(from teamID: UUID, playerID: UUID) {
        guard let teamIndex = teams.firstIndex(where: { $0.id == teamID }) else { return }
        var archive = archivedTeams.first { $0.id == teamID } ?? teams[teamIndex]
        if let player = teams[teamIndex].players.first(where: { $0.id == playerID }), !archive.players.contains(where: { $0.id == playerID }) { archive.players.append(player) }
        archivedTeams.removeAll { $0.id == teamID }; archivedTeams.append(archive)
        teams[teamIndex].players.removeAll { $0.id == playerID }
        syncCurrentTeamIfNeeded(teamID)
        persist {
            try persistenceStore.replaceAll(with: makeRosterSnapshot())
        }
    }

    func addPlayer(name: String, number: Int, position: FieldPosition) {
        guard let currentTeam else { return }
        guard let playerID = addPlayer(
            to: currentTeam.id,
            chineseName: name,
            englishName: "",
            numbers: [number]
        ), let teamIndex = teams.firstIndex(where: { $0.id == currentTeam.id }),
           let playerIndex = teams[teamIndex].players.firstIndex(where: { $0.id == playerID }) else { return }
        teams[teamIndex].players[playerIndex].primaryPosition = position
        syncCurrentTeamIfNeeded(currentTeam.id)
        let updatedPlayer = teams[teamIndex].players[playerIndex]
        persist {
            try persistenceStore.upsertPlayer(
                updatedPlayer,
                rosterTeamID: currentTeam.id,
                sortOrder: playerIndex
            )
        }
    }

    func recordPitch(_ action: PitchAction) {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.pitch(action))

        defer { commitAtomicAction() }
        pushUndo(startsClock: true)
        guard canRecordAction else { return }
        let appearance = game.currentPlateAppearanceID
        game.plateAppearancePitchCount = (game.plateAppearancePitchCount ?? 0) + 1
        defer { if isCoachPitch, game.currentPlateAppearanceID == appearance { checkCoachPitchLimit() } }
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updatePitcher(pitcher.id) { line in
            line.pitches += 1
            if action != .ball { line.strikes += 1 }
        }

        switch action {
        case .ball:
            game.balls = isCoachPitch ? 0 : game.balls + 1
            addLog(
                "\(batter.name)：坏球（\(game.balls)坏 \(game.strikes)好）",
                category: .pitch,
                notation: "B",
                primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                secondaryPlayerID: batter.id
            )
            if !isCoachPitch && game.balls >= 4 { applyWalkWithoutUndo() }
        case .calledStrike:
            game.strikes += 1
            addLog(
                "\(batter.name)：看振（\(game.balls)坏 \(game.strikes)好）",
                category: .pitch,
                notation: "C",
                primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                secondaryPlayerID: batter.id
            )
            if game.strikes >= 3 { applyStrikeoutWithoutUndo(swinging: false) }
        case .swingingStrike:
            game.strikes += 1
            addLog(
                "\(batter.name)：挥空（\(game.balls)坏 \(game.strikes)好）",
                category: .pitch,
                notation: "S",
                primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                secondaryPlayerID: batter.id
            )
            if game.strikes >= 3 { applyStrikeoutWithoutUndo(swinging: true) }
        case .foul:
            if game.strikes < 2 { game.strikes += 1 }
            addLog(
                "\(batter.name)：界外球（\(game.balls)坏 \(game.strikes)好）",
                category: .pitch,
                notation: "F",
                primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                secondaryPlayerID: batter.id
            )
        }
    }

    func suggestedRunnerDecisions(
        for outcome: PlayOutcome,
        batterDestination overrideDestination: RunnerDestination? = nil
    ) -> [RunnerDecision] {
        var decisions: [RunnerDecision] = []
        let existing = Base.allCases.reversed().compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: suggestedDestination(from: base, outcome: outcome))
        }
        decisions.append(contentsOf: existing)

        let suggestedBatterDestination: RunnerDestination
        switch outcome {
        case .single, .error, .fieldersChoice, .runnerTagOut, .pending: suggestedBatterDestination = .base(.first)
        case .double: suggestedBatterDestination = .base(.second)
        case .triple: suggestedBatterDestination = .base(.third)
        case .homeRun: suggestedBatterDestination = .score
        case .groundOut, .flyOut, .lineOut, .foulFlyOut, .infieldFly,
             .sacrificeBunt, .sacrificeFly, .doublePlay, .triplePlay, .pendingOut:
            suggestedBatterDestination = .out
        case .other: suggestedBatterDestination = .base(.first)
        }
        let batterDestination = overrideDestination ?? suggestedBatterDestination
        decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: batterDestination))

        if outcome == .fieldersChoice, let firstRunnerIndex = decisions.firstIndex(where: {
            if case .base = $0.origin { return true }
            return false
        }) {
            decisions[firstRunnerIndex].destination = .out
        }

        if outcome == .doublePlay {
            if let runnerIndex = decisions.firstIndex(where: {
                if case .base = $0.origin { return true }
                return false
            }) {
                decisions[runnerIndex].destination = .out
            }
        }
        if outcome == .triplePlay {
            for index in decisions.indices where decisions[index].origin != .batter {
                guard decisions.filter({ $0.destination == .out }).count < 3 else { break }
                decisions[index].destination = .out
            }
        }
        return decisions
    }

    func suggestedRunnerEventDecisions(for kind: RunnerEventKind) -> [RunnerDecision] {
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: .hold)
        }

        switch kind {
        case .wildPitch, .passedBall, .uncertainLooseBall, .balk, .doubleSteal:
            for index in decisions.indices {
                guard case .base(let base) = decisions[index].origin else { continue }
                decisions[index].destination = nextDestination(after: base)
            }
        case .stolenBase, .delayedSteal, .relayAdvance, .defensiveErrorAdvance:
            if let index = decisions.indices.last,
               case .base(let base) = decisions[index].origin {
                decisions[index].destination = nextDestination(after: base)
            }
        case .caughtStealing, .pickoff, .tagOut, .forceOut, .appealOut,
             .leftEarly, .missedBase, .outOfBasePath, .passedRunner,
             .runnerInterference, .runnerHitByBall, .coachAssistance:
            if let index = decisions.indices.first {
                decisions[index].destination = .out
            }
        }
        return decisions
    }

    func droppedThirdStrikeDecisions() -> [RunnerDecision] {
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: .hold)
        }
        decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: .base(.first)))
        return decisions
    }

    var canReachOnDroppedThirdStrike: Bool {
        if isCoachPitch { return false }
        return         game.outs == 2 || game.baseRunners[.first] == nil
    }

    func availableDestinations(for decision: RunnerDecision) -> [RunnerDestination] {
        switch decision.origin {
        case .batter:
            return [.base(.first), .base(.second), .base(.third), .score, .out]
        case .base(.first):
            return [.hold, .base(.second), .base(.third), .score, .out]
        case .base(.second):
            return [.hold, .base(.third), .score, .out]
        case .base(.third):
            return [.hold, .score, .out]
        }
    }

    private func playPreconditionError(_ outcome: PlayOutcome) -> String? {
        switch outcome {
        case .infieldFly:
            guard game.outs < 2,
                  game.baseRunners[.first] != nil,
                  game.baseRunners[.second] != nil else {
                return "内野高飞必死只适用于一、二垒有跑者（或满垒）且不足两出局。"
            }
        case .doublePlay:
            guard game.outs <= 1, !game.baseRunners.isEmpty else {
                return "当前局面无法形成双杀。"
            }
        case .triplePlay:
            guard game.outs == 0, game.baseRunners.count >= 2 else {
                return "三杀需要零出局且至少两名跑者在垒。"
            }
        case .sacrificeFly:
            guard game.outs < 2, game.baseRunners[.third] != nil else {
                return "牺牲高飞需要不足两出局且三垒有跑者。"
            }
        case .sacrificeBunt:
            guard game.outs < 2, !game.baseRunners.isEmpty else {
                return "牺牲触击需要不足两出局且垒上有跑者。"
            }
        default:
            break
        }
        return nil
    }

    private func runnerDecisionValidationError(
        _ decisions: [RunnerDecision],
        includesBatter: Bool
    ) -> String? {
        guard Set(decisions.map(\.player.id)).count == decisions.count else {
            return "同一名球员不能在一次记录中出现两次。"
        }
        let baseDecisions = decisions.filter {
            if case .base = $0.origin { return true }
            return false
        }
        let expectedRunnerIDs = Set(game.baseRunners.values.map(\.id))
        let recordedRunnerIDs = Set(baseDecisions.map(\.player.id))
        guard expectedRunnerIDs == recordedRunnerIDs else {
            return "跑者名单与记录前的垒上局面不一致。"
        }
        for decision in baseDecisions {
            guard case .base(let base) = decision.origin,
                  game.baseRunners[base]?.id == decision.player.id else {
                return "\(decision.player.name) 的起始垒位与现场局面不一致。"
            }
            guard availableDestinations(for: decision).contains(decision.destination) else {
                return "\(decision.player.name) 不能从\(base.title)移动到该位置。"
            }
        }
        let batterDecisions = decisions.filter {
            if case .batter = $0.origin { return true }
            return false
        }
        if includesBatter {
            guard batterDecisions.count == 1,
                  batterDecisions[0].player.id == game.currentBatter.id,
                  availableDestinations(for: batterDecisions[0]).contains(batterDecisions[0].destination) else {
                return "打者结果与当前棒次不一致。"
            }
        } else if !batterDecisions.isEmpty {
            return "独立跑垒事件不能同时改变打者。"
        }
        let destinationBases = decisions.compactMap { decision -> Base? in
            if case .base(let base) = decision.destination { return base }
            return nil
        }
        guard Set(destinationBases).count == destinationBases.count else {
            return "同一个垒位不能同时站两名跑者。"
        }
        let newOuts = decisions.filter { $0.destination == .out }.count
        guard game.outs + newOuts <= 3 else {
            return "这次记录会产生超过三个出局。"
        }
        return nil
    }

    /// Nil means the ordinary runner/timing flow is still needed.
    func halfEndingRunnerOutCount(for outcome: PlayOutcome) -> Int? {
        if game.outs == 2, [.groundOut, .flyOut, .lineOut, .foulFlyOut, .pendingOut].contains(outcome) { return 0 }
        if game.outs == 1, outcome == .doublePlay, !game.baseRunners.isEmpty { return 1 }
        if game.outs == 0, outcome == .triplePlay, game.baseRunners.count >= 2 { return 2 }
        return nil
    }

    func halfEndingDecisions(for outcome: PlayOutcome, runnerOutIDs: Set<UUID> = []) -> [RunnerDecision]? {
        guard let count = halfEndingRunnerOutCount(for: outcome), runnerOutIDs.count == count,
              runnerOutIDs.isSubset(of: Set(game.baseRunners.values.map(\.id))) else { return nil }
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: runnerOutIDs.contains(runner.id) ? .out : .hold)
        }
        decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: .out))
        return decisions
    }

    func playNeedsTimingDecision(_ outcome: PlayOutcome, decisions: [RunnerDecision]) -> Bool {
        let outs = decisions.filter { $0.destination == .out }.count
        let runs = decisions.filter { $0.destination == .score }.count
        return outcome == .runnerTagOut && game.outs + outs >= 3 && runs > 0
    }

    @discardableResult
    func applyPlay(_ outcome: PlayOutcome, defensivePlay: DefensivePlay? = nil, decisions: [RunnerDecision]? = nil, timingRunCounts: Bool? = nil) -> Bool {
        beginAtomicAction()
        let result = applyPlayOperation(outcome, defensivePlay: defensivePlay, decisions: decisions, timingRunCounts: timingRunCounts)
        commitAtomicAction()
        return result && actionErrorMessage == nil
    }

    private func applyPlayOperation(
        _ outcome: PlayOutcome,
        defensivePlay: DefensivePlay? = nil,
        decisions: [RunnerDecision]? = nil,
        timingRunCounts: Bool? = nil
    ) -> Bool {
        beginAtomicAction()
        recordHistoryCommand(.play(outcome, defensivePlay, decisions ?? suggestedRunnerDecisions(for: outcome), timingRunCounts))

        defer { commitAtomicAction() }
        guard canRecordAction else { return false }
        actionErrorMessage = nil
        let proposedDecisions = decisions ?? suggestedRunnerDecisions(for: outcome)
        if let issue = playPreconditionError(outcome) {
            actionErrorMessage = issue
            return false
        }
        if let issue = runnerDecisionValidationError(proposedDecisions, includesBatter: true) {
            actionErrorMessage = issue
            return false
        }
        if playNeedsTimingDecision(outcome, decisions: proposedDecisions), timingRunCounts == nil {
            actionErrorMessage = "第三出局同时发生得分，请先确认得分与触杀的先后。"
            return false
        }
        pushUndo(startsClock: true)
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        game.plateAppearancePitchCount = (game.plateAppearancePitchCount ?? 0) + 1
        let finalDecisions = proposedDecisions
        var battingLine = game.batting[batter.id, default: BattingLine()]
        var pitchingLine = game.pitching[pitcher.id, default: PitchingLine()]
        battingLine.plateAppearances += 1
        pitchingLine.battersFaced += 1
        pitchingLine.pitches += 1
        pitchingLine.strikes += 1

        switch outcome {
        case .single, .double, .triple, .homeRun:
            battingLine.atBats += 1
            battingLine.hits += 1
            pitchingLine.hits += 1
            if outcome == .double { battingLine.doubles += 1 }
            if outcome == .triple { battingLine.triples += 1 }
            if outcome == .homeRun { battingLine.homeRuns += 1 }
            incrementTeamHit()
        case .error, .fieldersChoice, .runnerTagOut, .groundOut, .flyOut, .lineOut, .foulFlyOut,
             .infieldFly, .doublePlay, .triplePlay, .pendingOut, .pending:
            battingLine.atBats += 1
        case .sacrificeBunt, .sacrificeFly:
            battingLine.sacrifices += 1
        case .other:
            break
        }

        let automaticRunnerRuns = automaticRunnerRunCount(in: finalDecisions)
        var resolution = resolveRunners(finalDecisions, unearnedBatter: outcome == .error)
        // A batter reaching on a fielder's choice can replace an inherited
        // runner in the preceding pitcher's responsibility chain (9.16(g)).
        if outcome == .fieldersChoice,
           finalDecisions.contains(where: { $0.origin == .batter && $0.destination != .out }),
           let replaced = finalDecisions.first(where: { if case .base = $0.origin { return $0.destination == .out }; return false }),
           let owner = game.runnerPitcherIDs?[replaced.player.id] {
            game.runnerPitcherIDs?[batter.id] = owner
            if (game.unearnedRunnerIDs ?? []).contains(replaced.player.id) {
                game.unearnedRunnerIDs = Array(Set(game.unearnedRunnerIDs ?? []).union([batter.id])).sorted { $0.uuidString < $1.uuidString }
            }
        }
        let batterIsOut = finalDecisions.contains { decision in
            if case .batter = decision.origin, decision.destination == .out { return true }
            return false
        }
        let reachesThirdOut = game.outs + resolution.outs >= 3
        let thirdOutCancelsRuns = reachesThirdOut && (
            (game.outs == 2 && batterIsOut)
                || [.groundOut, .fieldersChoice, .doublePlay, .triplePlay, .pendingOut].contains(outcome)
        )
        if thirdOutCancelsRuns {
            resolution = (runs: 0, outs: resolution.outs, scorers: [])
            actionEffectiveScorers = []
        }
        if outcome == .runnerTagOut, timingRunCounts == false {
            resolution = (runs: 0, outs: resolution.outs, scorers: [])
            actionEffectiveScorers = []
        }
        battingLine.runsBattedIn += [.error, .pendingOut, .pending, .other].contains(outcome) ? 0 : resolution.runs
        pitchingLine.outsRecorded += resolution.outs
        game.batting[batter.id] = battingLine
        if !isCoachPitch { game.pitching[pitcher.id] = pitchingLine }
        chargePitcherRuns(resolution.scorers, fallback: pitcher.id, earned: ![.error, .pendingOut, .pending].contains(outcome))

        for scorer in resolution.scorers {
            updateBatter(scorer.id) { $0.runs += 1 }
        }
        addRuns(resolution.runs)
        applyDefensiveCredits(defensivePlay, outcome: outcome)

        let defenseText = defensivePlay.map { "，\($0.title)（\($0.notation)）" } ?? ""
        let runnerText = runnerSummary(finalDecisions)
        addLog(
            "\(batter.name)：\(outcome.rawValue)\(defenseText)\(runnerText.isEmpty ? "" : "；\(runnerText)")",
            incomplete: outcome == .other || outcome == .pendingOut || outcome == .pending,
            category: .battedBall,
            notation: defensivePlay?.notation ?? outcome.notation,
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: finalDecisions,
            resolvedOutcome: outcome,
            automaticRunnerRuns: automaticRunnerRuns
        )
        if evaluateWalkOff() { return true }
        advanceBatter()
        if resolution.outs > 0 { registerOuts(resolution.outs) }
        return true
    }

    func recordSubstitution(_ text: String) {
        beginAtomicAction()
        recordHistoryCommand(.note(text))

        defer { commitAtomicAction() }
        pushUndo()
        addLog(text, category: .substitution)
    }

    func recordHitByPitch() {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.hitByPitch)

        defer { commitAtomicAction() }
        pushUndo(startsClock: true)
        guard canRecordAction else { return }
        if isCoachPitch {
            game.plateAppearancePitchCount = (game.plateAppearancePitchCount ?? 0) + 1
            addLog("触身球，继续打击（计入投球数）", category: .pitch, notation: "HBP-CONTINUE", secondaryPlayerID: game.currentBatter.id)
            checkCoachPitchLimit()
            return
        }
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.hitByPitch += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.hitByPitch += 1
            $0.pitches += 1
        }

        let decisions = forcedAdvanceDecisions(for: batter)
        let automaticRunnerRuns = automaticRunnerRunCount(in: decisions)
        let resolution = resolveRunners(decisions)
        updateBatter(batter.id) { $0.runsBattedIn += resolution.runs }
        addRunsAndPitcherResponsibility(
            resolution,
            pitcherID: pitcher.id,
            earned: true,
            automaticRunnerRuns: automaticRunnerRuns
        )
        addLog(
            "\(batter.name) 被球击中，上一垒（HBP）",
            category: .battedBall,
            notation: "HBP",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions
        )
        if evaluateWalkOff() { return }
        advanceBatter()
    }

    func recordIntentionalWalk() {
        beginAtomicAction()
        recordHistoryCommand(.intentionalWalk)

        defer { commitAtomicAction() }
        guard canRecordAction, !isCoachPitch else { actionErrorMessage = "教练投手模式不适用故意保送。"; return }
        pushUndo(startsClock: true)
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        let owner = game.walkResponsiblePitcherID ?? pitcher.id
        updateBatter(batter.id) {
            $0.plateAppearances += 1
            $0.walks += 1
        }
        updatePitcher(owner) {
            $0.battersFaced += 1
            $0.walks += 1
        }

        let decisions = forcedAdvanceDecisions(for: batter)
        let automaticRunnerRuns = automaticRunnerRunCount(in: decisions)
        let resolution = resolveRunners(decisions, batterResponsiblePitcherID: owner)
        updateBatter(batter.id) { $0.runsBattedIn += resolution.runs }
        addRunsAndPitcherResponsibility(
            resolution,
            pitcherID: pitcher.id,
            earned: true,
            automaticRunnerRuns: automaticRunnerRuns
        )
        addLog(
            "\(batter.name) 被故意保送上一垒（IBB）",
            category: .battedBall,
            notation: "IBB",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions
        )
        if evaluateWalkOff() { return }
        advanceBatter()
    }

    @discardableResult
    func recordDroppedThirdStrike(decisions: [RunnerDecision]) -> Bool {
        guard canRecordAction else { return false }
        beginAtomicAction()
        recordHistoryCommand(.droppedThirdStrike(decisions))

        defer { commitAtomicAction() }
        actionErrorMessage = nil
        guard canReachOnDroppedThirdStrike else {
            actionErrorMessage = "一垒有人且不足两出局，打者不能因第三好球未接住而上一垒。"
            return false
        }
        if let issue = runnerDecisionValidationError(decisions, includesBatter: true) {
            actionErrorMessage = issue
            return false
        }
        pushUndo(startsClock: true)
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(game.strikeoutResponsibleBatterID ?? batter.id) {
            $0.plateAppearances += 1
            $0.atBats += 1
            $0.strikeouts += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.strikeouts += 1
            $0.pitches += 1
            $0.strikes += 1
        }

        let automaticRunnerRuns = automaticRunnerRunCount(in: decisions)
        let resolution = resolveRunners(decisions)
        addRunsAndPitcherResponsibility(
            resolution,
            pitcherID: pitcher.id,
            earned: true,
            automaticRunnerRuns: automaticRunnerRuns
        )
        addLog(
            "\(batter.name) 三振，但捕手未接住；\(runnerSummary(decisions))",
            incomplete: true,
            category: .out,
            notation: "K WP/PB",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions
        )
        if evaluateWalkOff() { return true }
        advanceBatter()
        if resolution.outs > 0 { registerOuts(resolution.outs) }
        return true
    }

    func halfEndingRunnerDecisions(for kind: RunnerEventKind, outID: UUID, scorerIDs: Set<UUID>) -> [RunnerDecision]? {
        let ids = Set(game.baseRunners.values.map(\.id))
        guard game.outs == 2, kind.group == .out, ids.contains(outID),
              scorerIDs.isSubset(of: ids), !scorerIDs.contains(outID),
              kind != .forceOut || scorerIDs.isEmpty else { return nil }
        return Base.allCases.compactMap { base in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base),
                                  destination: runner.id == outID ? .out : scorerIDs.contains(runner.id) ? .score : .hold)
        }
    }

    func runnerEventNeedsTimingDecision(_ kind: RunnerEventKind, decisions: [RunnerDecision]) -> Bool {
        let outs = decisions.filter { $0.destination == .out }.count
        let runs = decisions.filter { $0.destination == .score }.count
        return kind.group == .out
            && kind != .forceOut
            && game.outs + outs >= 3
            && runs > 0
    }

    @discardableResult
    func recordRunnerEvent(_ kind: RunnerEventKind, decisions: [RunnerDecision], timingRunCounts: Bool? = nil) -> Bool {
        beginAtomicAction()
        let result = recordRunnerEventOperation(kind, decisions: decisions, timingRunCounts: timingRunCounts)
        commitAtomicAction()
        return result && actionErrorMessage == nil
    }

    private func recordRunnerEventOperation(
        _ kind: RunnerEventKind,
        decisions: [RunnerDecision],
        timingRunCounts: Bool? = nil
    ) -> Bool {
        beginAtomicAction()
        recordHistoryCommand(.runner(kind, decisions, timingRunCounts))

        defer { commitAtomicAction() }
        guard canRecordAction, allowedRunnerEvents.contains(kind) else { actionErrorMessage = "教练投手模式不适用此跑垒事件。"; return false }
        actionErrorMessage = nil
        guard !decisions.isEmpty else {
            actionErrorMessage = "当前没有可记录的跑者变化。"
            return false
        }
        if let issue = runnerDecisionValidationError(decisions, includesBatter: false) {
            actionErrorMessage = issue
            return false
        }
        if runnerEventNeedsTimingDecision(kind, decisions: decisions), timingRunCounts == nil {
            actionErrorMessage = "第三出局同时发生得分，请确认得分与出局的先后。"
            return false
        }
        pushUndo(startsClock: true)
        let pitcher = game.currentPitcher
        let automaticRunnerRuns = automaticRunnerRunCount(in: decisions)
        var resolution = resolveRunners(decisions)
        let thirdOutIsForce = kind == .forceOut && game.outs + resolution.outs >= 3
        if thirdOutIsForce || timingRunCounts == false {
            resolution = (runs: 0, outs: resolution.outs, scorers: [])
            actionEffectiveScorers = []
        }

        switch kind {
        case .stolenBase, .doubleSteal, .delayedSteal:
            for decision in decisions where decision.destination != .hold && decision.destination != .out {
                updateBatter(decision.player.id) { $0.stolenBases += 1 }
            }
        case .caughtStealing:
            for decision in decisions where decision.destination == .out {
                updateBatter(decision.player.id) { $0.caughtStealing += 1 }
            }
        case .wildPitch:
            updatePitcher(pitcher.id) { $0.wildPitches += 1 }
        case .relayAdvance, .defensiveErrorAdvance,
             .passedBall, .uncertainLooseBall, .pickoff, .balk,
             .tagOut, .forceOut, .appealOut, .leftEarly, .missedBase,
             .outOfBasePath, .passedRunner, .runnerInterference, .runnerHitByBall, .coachAssistance:
            break
        }

        let isEarned = kind != .passedBall && kind != .uncertainLooseBall
        addRunsAndPitcherResponsibility(
            resolution,
            pitcherID: pitcher.id,
            earned: isEarned,
            automaticRunnerRuns: automaticRunnerRuns
        )
        let summary = runnerSummary(decisions)
        addLog(
            "\(kind.rawValue)\(summary.isEmpty ? "" : "；\(summary)")",
            incomplete: kind == .uncertainLooseBall || kind == .defensiveErrorAdvance,
            category: kind.group == .out ? .out : .runner,
            notation: kind.notation,
            primaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions,
            ballStatus: kind.ballStatus
        )
        if evaluateWalkOff() { return true }
        if resolution.outs > 0 { registerOuts(resolution.outs) }
        return true
    }

    func suggestedViolationDecisions(for violation: ViolationKind) -> [RunnerDecision] {
        if violation.resolution == .batterFirst {
            return forcedAdvanceDecisions(for: game.currentBatter)
        }
        var decisions = Base.allCases.compactMap { base -> RunnerDecision? in
            guard let runner = game.baseRunners[base] else { return nil }
            return RunnerDecision(player: runner, origin: .base(base), destination: .hold)
        }
        if violation.resolution == .ballOrAdvance {
            for index in decisions.indices {
                guard case .base(let base) = decisions[index].origin else { continue }
                decisions[index].destination = nextDestination(after: base)
            }
        } else if violation.category == .offense, let first = decisions.indices.first {
            decisions[first].destination = .out
        }
        return decisions
    }

    func suggestedViolationAdjudication(for violation: ViolationKind) -> ViolationAdjudication {
        let plateDisposition: PlateAppearanceDisposition = switch violation.resolution {
        case .batterOut: .batterOut
        case .batterFirst: .batterFirst
        default: .continueAtBat
        }
        return ViolationAdjudication(
            ballStatus: violation.ballStatus,
            previousPlayDisposition: .notApplicable,
            plateAppearanceDisposition: plateDisposition,
            runnerDecisions: adjudicationDecisions(for: violation, plateDisposition: plateDisposition),
            countsAsAtBat: plateDisposition == .batterOut,
            isFinalRuling: violation.resolution != .recordForReview
        )
    }

    func adjudicationDecisions(
        for violation: ViolationKind,
        plateDisposition: PlateAppearanceDisposition
    ) -> [RunnerDecision] {
        if plateDisposition == .batterFirst {
            return forcedAdvanceDecisions(for: game.currentBatter)
        }
        var decisions = suggestedViolationDecisions(for: violation).filter {
            if case .batter = $0.origin { return false }
            return true
        }
        if plateDisposition == .batterOut {
            decisions.append(RunnerDecision(player: game.currentBatter, origin: .batter, destination: .out))
        }
        return decisions
    }

    @discardableResult
    func recordViolation(_ violation: ViolationKind, decisions: [RunnerDecision] = []) -> Bool {
        guard canRecordAction, !isCoachPitch || violation.category != .pitcher else { actionErrorMessage = "此模式不适用投手犯规。"; return false }
        beginAtomicAction()
        recordHistoryCommand(.violation(violation, decisions.isEmpty ? suggestedViolationDecisions(for: violation) : decisions))
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        let proposedDecisions = decisions.isEmpty ? suggestedViolationDecisions(for: violation) : decisions
        switch violation.resolution {
        case .batterFirst:
            if let issue = runnerDecisionValidationError(proposedDecisions, includesBatter: true) {
                actionErrorMessage = issue
                return false
            }
        case .ballOrAdvance where !game.baseRunners.isEmpty,
             .resolveRunners where !proposedDecisions.isEmpty:
            if let issue = runnerDecisionValidationError(proposedDecisions, includesBatter: false) {
                actionErrorMessage = issue
                return false
            }
        default:
            break
        }
        pushUndo(startsClock: true)

        switch violation.resolution {
        case .ballOrAdvance:
            if game.baseRunners.isEmpty {
                updatePitcher(pitcher.id) { $0.pitches += 1 }
                game.balls += 1
                addLog(
                    "裁判判罚：\(violation.rawValue)，垒上无人，记一坏球",
                    category: .violation,
                    notation: "IP-B",
                    primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                    secondaryPlayerID: batter.id,
                    ballStatus: violation.ballStatus
                )
                if game.balls >= 4 { applyWalkWithoutUndo() }
            } else {
                let finalDecisions = proposedDecisions
                let automaticRunnerRuns = automaticRunnerRunCount(in: finalDecisions)
                let resolution = resolveRunners(finalDecisions)
                addRunsAndPitcherResponsibility(
                    resolution,
                    pitcherID: pitcher.id,
                    earned: false,
                    automaticRunnerRuns: automaticRunnerRuns
                )
                addLog(
                    "裁判判罚：\(violation.rawValue)；\(runnerSummary(finalDecisions))",
                    category: .violation,
                    notation: "BALK",
                    primaryPlayerID: isCoachPitch ? nil : pitcher.id,
                    runnerDecisions: finalDecisions,
                    ballStatus: violation.ballStatus
                )
                if evaluateWalkOff() { return true }
            }

        case .batterOut:
            updateBatter(batter.id) {
                $0.plateAppearances += 1
                $0.atBats += 1
            }
            updatePitcher(pitcher.id) {
                $0.battersFaced += 1
                $0.outsRecorded += 1
            }
            addLog(
                "裁判判罚：\(violation.rawValue)，打者 #\(batter.numberText) \(batter.name) 出局",
                category: .violation,
                notation: "INT/ILLEGAL",
                primaryPlayerID: batter.id,
                ballStatus: violation.ballStatus
            )
            advanceBatter()
            registerOuts(1)

        case .batterFirst:
            let finalDecisions = proposedDecisions
            let automaticRunnerRuns = automaticRunnerRunCount(in: finalDecisions)
            updateBatter(batter.id) { $0.plateAppearances += 1 }
            updatePitcher(pitcher.id) { $0.battersFaced += 1 }
            let resolution = resolveRunners(finalDecisions)
            addRunsAndPitcherResponsibility(
                resolution,
                pitcherID: pitcher.id,
                earned: false,
                automaticRunnerRuns: automaticRunnerRuns
            )
            addLog(
                "裁判判罚：\(violation.rawValue)，打者上一垒\(resolution.runs > 0 ? "，推进得到 \(resolution.runs) 分" : "")",
                category: .violation,
                notation: "CI",
                primaryPlayerID: batter.id,
                runnerDecisions: finalDecisions,
                ballStatus: violation.ballStatus
            )
            if evaluateWalkOff() { return true }
            advanceBatter()

        case .resolveRunners:
            let finalDecisions = proposedDecisions
            let automaticRunnerRuns = automaticRunnerRunCount(in: finalDecisions)
            let resolution = resolveRunners(finalDecisions)
            addRunsAndPitcherResponsibility(
                resolution,
                pitcherID: pitcher.id,
                earned: false,
                automaticRunnerRuns: automaticRunnerRuns
            )
            addLog(
                "裁判判罚：\(violation.rawValue)\(finalDecisions.isEmpty ? "" : "；\(runnerSummary(finalDecisions))")",
                incomplete: finalDecisions.isEmpty,
                category: .violation,
                primaryPlayerID: batter.id,
                runnerDecisions: finalDecisions,
                ballStatus: violation.ballStatus
            )
            if evaluateWalkOff() { return true }
            if resolution.outs > 0 { registerOuts(resolution.outs) }

        case .recordForReview:
            addLog(
                "裁判判罚：\(violation.rawValue)，结果待补充确认",
                incomplete: true,
                category: .violation,
                primaryPlayerID: violation.category == .pitcher ? pitcher.id : batter.id,
                ballStatus: violation.ballStatus
            )
        }
        return true
    }

    @discardableResult
    func recordViolation(_ violation: ViolationKind, adjudication: ViolationAdjudication) -> Bool {
        guard canRecordAction, !isCoachPitch || violation.category != .pitcher else { actionErrorMessage = "此模式不适用投手犯规。"; return false }
        beginAtomicAction()
        recordHistoryCommand(.adjudication(violation, adjudication))
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        finalizeLatestEventSituation()
        let originalGame = game
        if adjudication.previousPlayDisposition == .cancel {
            guard let stateBeforePreviousAction = undoStack.last else {
                actionErrorMessage = "当前没有可以由裁判取消的上一比赛结果。"
                return false
            }
            undoStack.append(originalGame)
            if undoStack.count > 30 { undoStack.removeFirst() }
            redoStack.removeAll()
            game = stateBeforePreviousAction
            nextEventBeforeSituation = game.situationSnapshot
        } else {
            pushUndo(startsClock: true)
        }

        let includesBatter = adjudication.plateAppearanceDisposition != .continueAtBat
        let decisions = adjudication.runnerDecisions
        if let issue = runnerDecisionValidationError(decisions, includesBatter: includesBatter) {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = issue
            return false
        }

        let scoredRuns = decisions.filter { $0.destination == .score }.count
        let automaticRunnerRuns = automaticRunnerRunCount(in: decisions)
        let maximumEarnedRuns = max(0, scoredRuns - automaticRunnerRuns)
        if !(0...scoredRuns).contains(adjudication.runsBattedIn) {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = "打点责任不能超过本次判罚产生的得分。"
            return false
        }
        if !(0...maximumEarnedRuns).contains(adjudication.earnedRuns) {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = "自责分不能超过本次判罚中可计自责分的得分，TB 自动跑者得分不得计为自责分。"
            return false
        }
        if adjudication.battingCredit != .none,
           adjudication.plateAppearanceDisposition == .continueAtBat {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = "打席继续时不能同时记入最终打击结果。"
            return false
        }
        if adjudication.battingCredit.isHit,
           decisions.first(where: { $0.origin == .batter })?.destination == .out {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = "打者被判出局时不能记为安打。"
            return false
        }
        if adjudication.battingCredit == .homeRun,
           decisions.first(where: { $0.origin == .batter })?.destination != .score {
            game = originalGame
            if !undoStack.isEmpty { undoStack.removeLast() }
            actionErrorMessage = "记本垒打时，打者最终位置必须为得分。"
            return false
        }
        if adjudication.battingCredit == .reachedOnError {
            let activeFielders = activeFieldingPlayerIDs(forHomeTeam: game.isTop)
            guard let fielderID = adjudication.fieldingErrorPlayerID,
                  activeFielders.contains(fielderID) else {
                game = originalGame
                if !undoStack.isEmpty { undoStack.removeLast() }
                actionErrorMessage = "记守备失误时必须指定当前场上的失误责任人。"
                return false
            }
        }

        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        var resolution = resolveRunners(decisions)
        if adjudication.plateAppearanceDisposition == .batterOut,
           game.outs + resolution.outs >= 3 {
            resolution = (runs: 0, outs: resolution.outs, scorers: [])
            actionEffectiveScorers = []
        }

        switch adjudication.plateAppearanceDisposition {
        case .continueAtBat:
            break
        case .batterOut:
            updateBatter(batter.id) {
                $0.plateAppearances += 1
                if adjudication.countsAsAtBat { $0.atBats += 1 }
            }
            updatePitcher(pitcher.id) { $0.battersFaced += 1 }
        case .batterFirst:
            updateBatter(batter.id) {
                $0.plateAppearances += 1
                if adjudication.countsAsAtBat { $0.atBats += 1 }
            }
            updatePitcher(pitcher.id) { $0.battersFaced += 1 }
        }
        if adjudication.battingCredit.isHit {
            updateBatter(batter.id) {
                $0.hits += 1
                if adjudication.battingCredit == .double { $0.doubles += 1 }
                if adjudication.battingCredit == .triple { $0.triples += 1 }
                if adjudication.battingCredit == .homeRun { $0.homeRuns += 1 }
            }
            updatePitcher(pitcher.id) { $0.hits += 1 }
            incrementTeamHit()
        } else if adjudication.battingCredit == .sacrifice {
            updateBatter(batter.id) { $0.sacrifices += 1 }
        } else if adjudication.battingCredit == .reachedOnError,
                  let fielderID = adjudication.fieldingErrorPlayerID {
            updateFielder(fielderID) { $0.errors += 1 }
            if game.isTop { game.homeErrors += 1 } else { game.awayErrors += 1 }
        }
        updateBatter(batter.id) { $0.runsBattedIn += min(adjudication.runsBattedIn, resolution.runs) }
        addRunsAndPitcherResponsibility(
            resolution,
            pitcherID: pitcher.id,
            earned: false,
            automaticRunnerRuns: automaticRunnerRuns
        )
        updatePitcher(pitcher.id) {
            $0.outsRecorded += resolution.outs
            $0.earnedRuns += min(adjudication.earnedRuns, max(0, resolution.runs - automaticRunnerRuns))
        }

        let runnerText = runnerSummary(decisions)
        let previousText = adjudication.previousPlayDisposition == .notApplicable
            ? ""
            : "，\(adjudication.previousPlayDisposition.rawValue)"
        let plateText = adjudication.plateAppearanceDisposition == .continueAtBat
            ? "，打席继续"
            : "，\(adjudication.plateAppearanceDisposition.rawValue)"
        let statisticalText = adjudication.battingCredit == .none
            ? "，统计责任：不另记打击结果，\(min(adjudication.runsBattedIn, resolution.runs)) 打点，\(min(adjudication.earnedRuns, max(0, resolution.runs - automaticRunnerRuns))) 自责分"
            : "，统计责任：\(adjudication.battingCredit.rawValue)，\(min(adjudication.runsBattedIn, resolution.runs)) 打点，\(min(adjudication.earnedRuns, max(0, resolution.runs - automaticRunnerRuns))) 自责分"
        addLog(
            "裁判最终宣判：\(violation.rawValue)（\(adjudication.ballStatus.rawValue)）\(previousText)\(plateText)\(runnerText.isEmpty ? "" : "；\(runnerText)")\(statisticalText)",
            incomplete: !adjudication.isFinalRuling,
            category: .violation,
            notation: adjudication.battingCredit.outcome?.notation ?? "RULING",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions,
            ballStatus: adjudication.ballStatus,
            resolvedOutcome: adjudication.battingCredit.outcome,
            automaticRunnerRuns: automaticRunnerRuns
        )
        if adjudication.plateAppearanceDisposition != .continueAtBat { advanceBatter() }
        if evaluateWalkOff() { return true }
        if resolution.outs > 0 { registerOuts(resolution.outs) }
        return true
    }

    func recordFoulBuntStrikeout() {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.foulBunt)

        defer { commitAtomicAction() }
        guard game.strikes == 2 else { return }
        pushUndo(startsClock: true)
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updatePitcher(pitcher.id) {
            $0.pitches += 1
            $0.strikes += 1
        }
        updateBatter(game.strikeoutResponsibleBatterID ?? batter.id) {
            $0.plateAppearances += 1
            $0.atBats += 1
            $0.strikeouts += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.strikeouts += 1
            $0.outsRecorded += 1
        }
        addLog(
            "\(batter.name) 两好球后触击界外，第三好球出局",
            category: .out,
            notation: "K-BUNT",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id
        )
        advanceBatter()
        registerOuts(1)
    }

    @discardableResult
    func correctGameState(
        inning: Int,
        isTop: Bool,
        balls: Int,
        strikes: Int,
        outs: Int,
        awayScore: Int,
        homeScore: Int,
        batterIndex: Int,
        pitcherID: UUID?,
        baseRunners: [Base: Player]
    ) -> Bool {
        beginAtomicAction()
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        let runnerIDs = baseRunners.values.map(\.id)
        guard inning >= 1,
              (0...3).contains(balls),
              (0...2).contains(strikes),
              (0...2).contains(outs),
              awayScore >= 0,
              homeScore >= 0 else {
            actionErrorMessage = "局面数据超出可记录范围，请检查局数、球数、出局数和比分。"
            return false
        }
        guard Set(runnerIDs).count == runnerIDs.count else {
            actionErrorMessage = "同一名跑者不能同时占据两个垒位。"
            return false
        }
        let battingTeam = isTop ? game.awayTeam : game.homeTeam
        let battingOrder = isTop ? game.awayBattingOrderIDs : game.homeBattingOrderIDs
        for runnerID in runnerIDs {
            guard battingTeam.players.contains(where: { $0.id == runnerID }),
                  battingOrder.contains(runnerID) else {
                actionErrorMessage = "垒上跑者必须是当前进攻方仍在比赛中的球员。"
                return false
            }
        }
        if let pitcherID {
            let fieldingTeam = isTop ? game.homeTeam : game.awayTeam
            let fieldingIsHome = isTop
            guard fieldingTeam.players.contains(where: { $0.id == pitcherID }),
                  activeFieldingPlayerIDs(forHomeTeam: fieldingIsHome).contains(pitcherID) else {
                actionErrorMessage = "当前投手必须是防守方仍在比赛中的球员。"
                return false
            }
        }
        var historySituation = HistorySituation(game)
        historySituation.inning = inning; historySituation.isTop = isTop
        historySituation.balls = balls; historySituation.strikes = strikes; historySituation.outs = outs
        historySituation.awayScore = awayScore; historySituation.homeScore = homeScore
        historySituation.batterIndex = batterIndex; historySituation.pitcherID = pitcherID; historySituation.runners = baseRunners
        recordHistoryCommand(.situation(historySituation))
        pushUndo()
        game.inning = max(1, inning)
        game.isTop = isTop
        ensureInningCapacity()
        setScore(max(0, awayScore), forHomeTeam: false)
        setScore(max(0, homeScore), forHomeTeam: true)
        game.balls = min(max(0, balls), 3)
        game.strikes = min(max(0, strikes), 2)
        game.outs = min(max(0, outs), 2)
        game.baseRunners = baseRunners

        let lineupCount = max(1, game.battingOrderIDs.count)
        if isTop {
            game.awayBatterIndex = min(max(0, batterIndex), lineupCount - 1)
            game.activeHomePitcherID = pitcherID
        } else {
            game.homeBatterIndex = min(max(0, batterIndex), lineupCount - 1)
            game.activeAwayPitcherID = pitcherID
        }
        game.statisticsIncomplete = true
        addLog("现场状态已人工修正；缺失过程与统计待确认", incomplete: true, category: .correction, notation: "COR")
        return true
    }

    var eventReviewPlayers: [Player] {
        var seen = Set<UUID>()
        return (game.awayTeam.players + game.homeTeam.players).filter { seen.insert($0.id).inserted }
    }

    @discardableResult
    func reviewPendingEvent(
        id: UUID,
        title: String,
        category: ScoringEventCategory,
        notation: String?,
        primaryPlayerID: UUID?,
        secondaryPlayerID: UUID?,
        ballStatus: BallStatus?,
        resolvedOutcome: PlayOutcome?,
        note: String
    ) -> Bool {
        beginAtomicAction()
        recordHistoryCommand(.review(HistoryEventReview(id: id, title: title, category: category, notation: notation, primaryPlayerID: primaryPlayerID, secondaryPlayerID: secondaryPlayerID, ballStatus: ballStatus, resolvedOutcome: resolvedOutcome, note: note)))
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        finalizeLatestEventSituation()
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty, !cleanNote.isEmpty else {
            actionErrorMessage = "请填写确认后的中文记录和复核说明。"
            return false
        }
        guard var events = game.scoringEvents,
              let eventIndex = events.firstIndex(where: { $0.id == id }),
              events[eventIndex].needsReview else {
            actionErrorMessage = "这条记录已经复核，或已不存在。"
            return false
        }
        guard timelineValidationError(in: events, startingAt: eventIndex) == nil else {
            actionErrorMessage = timelineValidationError(in: events, startingAt: eventIndex)
            return false
        }

        let oldEvent = events[eventIndex]
        let needsOutcomeResolution = oldEvent.resolvedOutcome.map {
            [.pending, .pendingOut, .other].contains($0)
        } ?? false
        if needsOutcomeResolution {
            guard let resolvedOutcome,
                  ![.pending, .pendingOut, .other].contains(resolvedOutcome) else {
                actionErrorMessage = "请选择已确认的正式记分结果。"
                return false
            }
            if let issue = reviewedOutcomeValidationError(resolvedOutcome, for: oldEvent) {
                actionErrorMessage = issue
                return false
            }
        }
        if let primaryPlayerID,
           !eventReviewPlayers.contains(where: { $0.id == primaryPlayerID }) {
            actionErrorMessage = "主要责任人不属于本场比赛名单。"
            return false
        }
        if let secondaryPlayerID,
           !eventReviewPlayers.contains(where: { $0.id == secondaryPlayerID }) {
            actionErrorMessage = "次要责任人不属于本场比赛名单。"
            return false
        }
        if resolvedOutcome == .error {
            guard let secondaryPlayerID,
                  fieldingPlayers(for: oldEvent).contains(where: { $0.id == secondaryPlayerID }) else {
                actionErrorMessage = "记为守备失误时，请选择当时防守方的失误责任人。"
                return false
            }
        }
        if [.battedBall, .out].contains(oldEvent.category),
           let originalBatterID = oldEvent.primaryPlayerID,
           primaryPlayerID != originalBatterID {
            actionErrorMessage = "修改历史打者会影响后续棒次，当前不能直接保存；请先修正事件局面。"
            return false
        }

        pushUndo()
        if let resolvedOutcome {
            applyReviewedOutcomeStatistics(
                resolvedOutcome,
                event: oldEvent,
                batterID: primaryPlayerID ?? oldEvent.primaryPlayerID,
                responsibilityPlayerID: secondaryPlayerID
            )
        }
        let cleanNotation = notation?.trimmingCharacters(in: .whitespacesAndNewlines)
        events[eventIndex].title = cleanTitle
        events[eventIndex].category = category
        events[eventIndex].notation = cleanNotation?.isEmpty == true ? nil : cleanNotation
        events[eventIndex].primaryPlayerID = primaryPlayerID
        events[eventIndex].secondaryPlayerID = secondaryPlayerID
        events[eventIndex].ballStatus = ballStatus
        events[eventIndex].resolvedOutcome = resolvedOutcome
        events[eventIndex].needsReview = false
        events[eventIndex].reviewNote = cleanNote
        events[eventIndex].reviewedAt = historyReplayDate ?? Date()
        game.scoringEvents = events

        if let logIndex = matchingLogIndex(for: oldEvent) {
            game.playLog[logIndex].text = cleanTitle
            game.playLog[logIndex].isIncomplete = false
            game.playLog[logIndex].reviewNote = cleanNote
        }
        addLog(
            "待确认记录已复核：\(cleanTitle)；说明：\(cleanNote)",
            category: .correction,
            notation: "REVIEW",
            primaryPlayerID: primaryPlayerID,
            secondaryPlayerID: secondaryPlayerID
        )
        return true
    }

    private func reviewedOutcomeValidationError(
        _ outcome: PlayOutcome,
        for event: ScoringEventRecord
    ) -> String? {
        guard let batterMovement = event.runnerMovements.first(where: { $0.origin == RunnerOrigin.batter.title }) else {
            return "这条待确认记录缺少打者去向，无法安全修改统计结果。"
        }
        let batterWasOut = batterMovement.destination == RunnerDestination.out.title
        let outcomeRequiresBatterOut = outcome.recordedOuts > 0
            && ![.doublePlay, .triplePlay, .runnerTagOut].contains(outcome)
        if outcomeRequiresBatterOut && !batterWasOut {
            return "打者在原记录中安全上垒，不能复核为打者出局。请先修正该事件的局面结果。"
        }
        if (outcome.isHit || [.error, .fieldersChoice].contains(outcome)) && batterWasOut {
            return "打者在原记录中出局，不能复核为安全上垒。请先修正该事件的局面结果。"
        }
        if outcome == .homeRun, batterMovement.destination != RunnerDestination.score.title {
            return "本垒打要求打者在该事件中得分。请先修正该事件的局面结果。"
        }
        return nil
    }

    private func fieldingPlayers(for event: ScoringEventRecord) -> [Player] {
        event.isTop ? game.homeTeam.players : game.awayTeam.players
    }

    private func applyReviewedOutcomeStatistics(
        _ outcome: PlayOutcome,
        event: ScoringEventRecord,
        batterID: UUID?,
        responsibilityPlayerID: UUID?
    ) {
        guard let batterID else { return }
        let pitcherID = event.secondaryPlayerID
            ?? (event.isTop ? event.beforeSituation?.activeHomePitcherID : event.beforeSituation?.activeAwayPitcherID)
        let runs = scoringRunDifference(for: event)
        let originalOutcome = event.resolvedOutcome

        if originalOutcome == .other,
           ![.sacrificeBunt, .sacrificeFly].contains(outcome) {
            updateBatter(batterID) { $0.atBats += 1 }
        }

        if outcome.isHit {
            updateBatter(batterID) {
                $0.hits += 1
                if outcome == .double { $0.doubles += 1 }
                if outcome == .triple { $0.triples += 1 }
                if outcome == .homeRun { $0.homeRuns += 1 }
            }
            if event.isTop { game.awayHits += 1 } else { game.homeHits += 1 }
            if let pitcherID { updatePitcher(pitcherID) { $0.hits += 1 } }
        } else if [.sacrificeBunt, .sacrificeFly].contains(outcome) {
            updateBatter(batterID) {
                $0.atBats = max(0, $0.atBats - 1)
                $0.sacrifices += 1
            }
        }

        if outcome == .error, let responsibilityPlayerID {
            updateFielder(responsibilityPlayerID) { $0.errors += 1 }
            if event.isTop { game.homeErrors += 1 } else { game.awayErrors += 1 }
        }

        if outcome != .error {
            updateBatter(batterID) { $0.runsBattedIn += runs }
            if let pitcherID {
                let scorers = event.effectiveScorerIDs ?? event.runnerMovements.filter { $0.destination == RunnerDestination.score.title }.map(\.playerID)
                if let owners = event.beforeSituation?.runnerPitcherIDs {
                    let unearned = Set(event.beforeSituation?.unearnedRunnerIDs ?? [])
                    for id in scorers.prefix(runs) where !unearned.contains(id) {
                        updatePitcher(owners[id] ?? pitcherID) { $0.earnedRuns += 1 }
                    }
                } else {
                    let unearnedAutomaticRuns = event.automaticRunnerRuns ?? 0
                    updatePitcher(pitcherID) { $0.earnedRuns += max(0, runs - unearnedAutomaticRuns) }
                }
            }
        }
    }

    private func scoringRunDifference(for event: ScoringEventRecord) -> Int {
        guard let before = event.beforeSituation, let after = event.afterSituation else {
            return event.runnerMovements.filter { $0.destination == RunnerDestination.score.title }.count
        }
        let beforeRuns = event.isTop
            ? before.awayRunsByInning.reduce(0, +)
            : before.homeRunsByInning.reduce(0, +)
        let afterRuns = event.isTop
            ? after.awayRunsByInning.reduce(0, +)
            : after.homeRunsByInning.reduce(0, +)
        return max(0, afterRuns - beforeRuns)
    }

    /// Replaces the recorded result of a pending event only when every later
    /// event can still be replayed from the changed situation. A conflict never
    /// mutates the live game and identifies the first incompatible event.
    @discardableResult
    func replacePendingEventSituation(id: UUID, with corrected: GameSituationSnapshot) -> Bool {
        beginAtomicAction()
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        finalizeLatestEventSituation()
        guard var events = game.scoringEvents,
              let index = events.firstIndex(where: { $0.id == id && $0.needsReview }) else {
            actionErrorMessage = "只能修改仍标记为待确认的事件。"
            return false
        }
        if let issue = snapshotValidationError(corrected) {
            actionErrorMessage = issue
            return false
        }
        events[index].afterSituation = corrected
        if let issue = timelineValidationError(in: events, startingAt: index) {
            actionErrorMessage = issue
            return false
        }
        guard let finalSituation = events.last?.afterSituation,
              snapshotValidationError(finalSituation) == nil else {
            actionErrorMessage = "复核后的最终局面无法还原到当前比赛。"
            return false
        }
        var replayedGame = game
        guard applySituationSnapshot(finalSituation, to: &replayedGame) else {
            actionErrorMessage = "复核后的最终局面引用了不存在的球员。"
            return false
        }
        pushUndo()
        game = replayedGame
        game.scoringEvents = events
        addLog("待确认事件的局面结果已修改，并已重放校验后续记录", category: .correction, notation: "REPLAY")
        return true
    }

    func validateScoringTimeline() -> String? {
        finalizeLatestEventSituation()
        return timelineValidationError(in: game.scoringEvents ?? [], startingAt: 0)
    }

    func changePitcher(to player: Player) {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.pitcher(player.id))

        defer { commitAtomicAction() }
        let isHomeTeam = game.isTop
        let activeIDs = activeFieldingPlayerIDs(forHomeTeam: isHomeTeam)
        guard availablePitchers.contains(where: { $0.id == player.id }),
              player.id != game.currentPitcher.id else { return }
        pushUndo()
        var team = game.fieldingTeam
        let previous = game.currentPitcher
        var responsibility = game.runnerPitcherIDs ?? [:]
        for runner in game.baseRunners.values where responsibility[runner.id] == nil { responsibility[runner.id] = previous.id }
        game.runnerPitcherIDs = responsibility
        if game.balls >= 3 || (game.balls == 2 && game.strikes < 2) {
            game.walkResponsiblePitcherID = game.walkResponsiblePitcherID ?? previous.id
        } else { game.walkResponsiblePitcherID = nil }
        let designatedHitterID = isHomeTeam ? game.homeDesignatedHitterID : game.awayDesignatedHitterID
        let twoWayPitcherContinuesAsDH = activeRulesOrDefault.twoWayPlayerEnabled
            && designatedHitterID == previous.id
            && !activeIDs.contains(player.id)
        if let newIndex = team.players.firstIndex(where: { $0.id == player.id }),
           let oldIndex = team.players.firstIndex(where: { $0.id == previous.id }),
           team.players[newIndex].primaryPosition != .pitcher {
            team.players[newIndex].primaryPosition = .pitcher
            if activeIDs.contains(player.id) {
                let replacementPosition = player.primaryPosition
                team.players[oldIndex].primaryPosition = replacementPosition
            }
        }
        if !activeIDs.contains(player.id) {
            replaceFieldingPlayer(previous.id, with: player.id, forHomeTeam: isHomeTeam)
            if activeLineupPlayerIDs(forHomeTeam: isHomeTeam).contains(previous.id),
               !twoWayPitcherContinuesAsDH {
                replaceLineupPlayer(previous.id, with: player.id, forHomeTeam: isHomeTeam)
            }
            if !twoWayPitcherContinuesAsDH {
                markPlayerExited(previous.id, forHomeTeam: isHomeTeam)
            }
        } else if activeRulesOrDefault.twoWayPlayerEnabled, designatedHitterID == previous.id {
            // The former pitcher remains in the batting order as a fielder, so
            // the special two-way DH role is no longer active for this team.
            if isHomeTeam { game.homeDesignatedHitterID = nil } else { game.awayDesignatedHitterID = nil }
        }
        if game.isTop {
            game.homeTeam = team
            game.activeHomePitcherID = player.id
        } else {
            game.awayTeam = team
            game.activeAwayPitcherID = player.id
        }
        addLog(
            "换投手：#\(player.numberText) \(player.name) 替换 #\(previous.numberText) \(previous.name) 登板"
                + (twoWayPitcherContinuesAsDH ? "；#\(previous.numberText) \(previous.name) 继续以 DH 身份打击" : ""),
            category: .substitution,
            primaryPlayerID: player.id,
            secondaryPlayerID: previous.id
        )
    }

    @discardableResult
    func replaceFielder(_ previous: Player, with replacement: Player) -> Bool {
        guard canRecordAction else { return false }
        beginAtomicAction()
        recordHistoryCommand(.fielder(previous.id, replacement.id))

        defer { commitAtomicAction() }
        actionErrorMessage = nil
        let isHomeTeam = game.isTop
        guard activeFieldingPlayerIDs(forHomeTeam: isHomeTeam).contains(previous.id),
              fieldingBenchPlayers.contains(where: { $0.id == replacement.id }),
              previous.id != replacement.id else {
            actionErrorMessage = "请选择一名场上守备员和一名当前未上场的球员。"
            return false
        }
        pushUndo()
        var team = game.fieldingTeam
        guard let previousIndex = team.players.firstIndex(where: { $0.id == previous.id }),
              let replacementIndex = team.players.firstIndex(where: { $0.id == replacement.id }) else {
            actionErrorMessage = "换人球员不在当前防守方名单中。"
            undo()
            return false
        }
        let position = team.players[previousIndex].primaryPosition
        team.players[replacementIndex].primaryPosition = position
        replaceFieldingPlayer(previous.id, with: replacement.id, forHomeTeam: isHomeTeam)
        if activeLineupPlayerIDs(forHomeTeam: isHomeTeam).contains(previous.id) {
            replaceLineupPlayer(previous.id, with: replacement.id, forHomeTeam: isHomeTeam)
        }
        replaceDesignatedHitterIfNeeded(previous.id, with: replacement.id, forHomeTeam: isHomeTeam)
        markPlayerExited(previous.id, forHomeTeam: isHomeTeam)
        if game.isTop {
            game.homeTeam = team
            if position == .pitcher { game.activeHomePitcherID = replacement.id }
        } else {
            game.awayTeam = team
            if position == .pitcher { game.activeAwayPitcherID = replacement.id }
        }
        addLog(
            "守备换人：#\(replacement.numberText) \(replacement.name) 替换 #\(previous.numberText) \(previous.name)，守\(position.fullName)",
            category: .substitution,
            primaryPlayerID: replacement.id,
            secondaryPlayerID: previous.id
        )
        return true
    }

    @discardableResult
    func performDoubleSwitch(
        firstOut: Player,
        firstIn: Player,
        firstPosition: FieldPosition,
        secondOut: Player,
        secondIn: Player,
        secondPosition: FieldPosition
    ) -> Bool {
        beginAtomicAction()
        recordHistoryCommand(.doubleSwitch(firstOut.id, firstIn.id, firstPosition, secondOut.id, secondIn.id, secondPosition))
        defer { commitAtomicAction() }
        actionErrorMessage = nil
        let isHomeTeam = game.isTop
        let active = activeFieldingPlayerIDs(forHomeTeam: isHomeTeam)
        let bench = Set(fieldingBenchPlayers.map(\.id))
        guard firstOut.id != secondOut.id,
              firstIn.id != secondIn.id,
              active.contains(firstOut.id), active.contains(secondOut.id),
              bench.contains(firstIn.id), bench.contains(secondIn.id),
              firstPosition != secondPosition else {
            actionErrorMessage = "双重换人必须选择两名不同的退场球员、两名不同的替补和两个不同守位。"
            return false
        }
        let activeDesignatedHitterID = isHomeTeam ? game.homeDesignatedHitterID : game.awayDesignatedHitterID
        guard activeDesignatedHitterID == nil else {
            actionErrorMessage = "启用 DH 的比赛请先在单次换人中处理 DH／投手关系，再进行双重换人。"
            return false
        }
        let occupiedByOthers = game.fieldingTeam.players.filter {
            active.contains($0.id) && $0.id != firstOut.id && $0.id != secondOut.id
        }
        guard !occupiedByOthers.contains(where: { $0.primaryPosition == firstPosition || $0.primaryPosition == secondPosition }) else {
            actionErrorMessage = "所选守位仍由未退场球员占用。"
            return false
        }

        pushUndo()
        var team = game.fieldingTeam
        guard let firstInIndex = team.players.firstIndex(where: { $0.id == firstIn.id }),
              let secondInIndex = team.players.firstIndex(where: { $0.id == secondIn.id }) else {
            actionErrorMessage = "替补球员不在当前防守方名单中。"
            undo()
            return false
        }
        team.players[firstInIndex].primaryPosition = firstPosition
        team.players[secondInIndex].primaryPosition = secondPosition
        replaceFieldingPlayer(firstOut.id, with: firstIn.id, forHomeTeam: isHomeTeam)
        replaceFieldingPlayer(secondOut.id, with: secondIn.id, forHomeTeam: isHomeTeam)
        replaceLineupPlayer(firstOut.id, with: firstIn.id, forHomeTeam: isHomeTeam)
        replaceLineupPlayer(secondOut.id, with: secondIn.id, forHomeTeam: isHomeTeam)
        markPlayerExited(firstOut.id, forHomeTeam: isHomeTeam)
        markPlayerExited(secondOut.id, forHomeTeam: isHomeTeam)
        if game.isTop {
            game.homeTeam = team
            if firstPosition == .pitcher { game.activeHomePitcherID = firstIn.id }
            if secondPosition == .pitcher { game.activeHomePitcherID = secondIn.id }
        } else {
            game.awayTeam = team
            if firstPosition == .pitcher { game.activeAwayPitcherID = firstIn.id }
            if secondPosition == .pitcher { game.activeAwayPitcherID = secondIn.id }
        }
        addLog(
            "双重换人：#\(firstIn.numberText) \(firstIn.name) 替换 #\(firstOut.numberText) \(firstOut.name) 守\(firstPosition.fullName)；#\(secondIn.numberText) \(secondIn.name) 替换 #\(secondOut.numberText) \(secondOut.name) 守\(secondPosition.fullName)，两人分别接管原棒次",
            category: .substitution,
            primaryPlayerID: firstIn.id,
            secondaryPlayerID: secondIn.id
        )
        return true
    }

    func replaceRunner(on base: Base, with player: Player) {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.pinchRunner(base, player.id))

        defer { commitAtomicAction() }
        let isHomeTeam = !game.isTop
        guard let previous = game.baseRunners[base],
              battingBenchPlayers.contains(where: { $0.id == player.id }) else { return }
        pushUndo()
        game.baseRunners[base] = player
        if let owner = game.runnerPitcherIDs?[previous.id] { game.runnerPitcherIDs?[player.id] = owner }
        if (game.unearnedRunnerIDs ?? []).contains(previous.id) { game.unearnedRunnerIDs = (game.unearnedRunnerIDs ?? []) + [player.id] }
        replaceLineupPlayer(previous.id, with: player.id, forHomeTeam: isHomeTeam)
        if activeFieldingPlayerIDs(forHomeTeam: isHomeTeam).contains(previous.id) {
            replaceFieldingPlayer(previous.id, with: player.id, forHomeTeam: isHomeTeam)
            inheritFieldingPosition(from: previous, to: player, forHomeTeam: isHomeTeam)
        }
        replaceDesignatedHitterIfNeeded(previous.id, with: player.id, forHomeTeam: isHomeTeam)
        markPlayerExited(previous.id, forHomeTeam: isHomeTeam)
        var automaticRunnerIDs = Set(game.automaticRunnerIDs ?? [])
        if automaticRunnerIDs.remove(previous.id) != nil {
            automaticRunnerIDs.insert(player.id)
            game.automaticRunnerIDs = Array(automaticRunnerIDs)
        }
        addLog(
            "代跑：#\(player.numberText) \(player.name) 替换 #\(previous.numberText) \(previous.name)，接管原棒次",
            category: .substitution,
            primaryPlayerID: player.id,
            secondaryPlayerID: previous.id
        )
    }

    func replaceCurrentBatter(with player: Player) {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.batter(player.id))

        defer { commitAtomicAction() }
        let team = game.battingTeam
        let currentIndex = game.isTop ? game.awayBatterIndex : game.homeBatterIndex
        var battingOrder = game.battingOrderIDs
        let lineupIndex = currentIndex % max(battingOrder.count, 1)
        let isHomeTeam = !game.isTop
        guard battingBenchPlayers.contains(where: { $0.id == player.id }),
              !battingOrder.isEmpty else { return }

        pushUndo()
        if game.strikes == 2 { game.strikeoutResponsibleBatterID = game.strikeoutResponsibleBatterID ?? game.currentBatter.id }
        let previousID = battingOrder[lineupIndex]
        let previous = team.players.first(where: { $0.id == previousID }) ?? game.currentBatter
        battingOrder[lineupIndex] = player.id
        if game.isTop {
            game.awayBattingOrderIDs = battingOrder
        } else {
            game.homeBattingOrderIDs = battingOrder
        }
        if activeFieldingPlayerIDs(forHomeTeam: isHomeTeam).contains(previous.id) {
            replaceFieldingPlayer(previous.id, with: player.id, forHomeTeam: isHomeTeam)
            inheritFieldingPosition(from: previous, to: player, forHomeTeam: isHomeTeam)
        }
        replaceDesignatedHitterIfNeeded(previous.id, with: player.id, forHomeTeam: isHomeTeam)
        markPlayerExited(previous.id, forHomeTeam: isHomeTeam)
        addLog(
            "代打：#\(player.numberText) \(player.name) 替换 #\(previous.numberText) \(previous.name)，接管原棒次",
            category: .substitution,
            primaryPlayerID: player.id,
            secondaryPlayerID: previous.id
        )
    }

    func changeFieldingPosition(for player: Player, to position: FieldPosition) {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.position(player.id, position))

        defer { commitAtomicAction() }
        var team = game.fieldingTeam
        let isHomeTeam = game.isTop
        guard activeFieldingPlayerIDs(forHomeTeam: isHomeTeam).contains(player.id),
              let playerIndex = team.players.firstIndex(where: { $0.id == player.id }) else { return }
        pushUndo()

        let oldPosition = team.players[playerIndex].primaryPosition
        let activeIDs = activeFieldingPlayerIDs(forHomeTeam: isHomeTeam)
        if let otherIndex = team.players.firstIndex(where: { activeIDs.contains($0.id) && $0.primaryPosition == position && $0.id != player.id }) {
            team.players[otherIndex].primaryPosition = oldPosition
        }
        team.players[playerIndex].primaryPosition = position

        if game.isTop {
            game.homeTeam = team
            game.activeHomePitcherID = team.players.first { activeIDs.contains($0.id) && $0.primaryPosition == .pitcher }?.id
        } else {
            game.awayTeam = team
            game.activeAwayPitcherID = team.players.first { activeIDs.contains($0.id) && $0.primaryPosition == .pitcher }?.id
        }
        let dh = isHomeTeam ? game.homeDesignatedHitterID : game.awayDesignatedHitterID
        let pitcher = isHomeTeam ? game.activeHomePitcherID : game.activeAwayPitcherID
        if let dh, activeIDs.contains(dh), dh != pitcher {
            if isHomeTeam { game.homeDesignatedHitterID = nil } else { game.awayDesignatedHitterID = nil }
        }
        addLog(
            "守位调整：#\(player.numberText) \(player.name) 改守\(position.fullName)",
            category: .substitution,
            primaryPlayerID: player.id
        )
    }

    func lineupDraft(forHomeTeam isHomeTeam: Bool) -> LiveLineupDraft {
        LiveLineupDraft(game: game, isHomeTeam: isHomeTeam, allowsTwoWayPlayer: activeRulesOrDefault.twoWayPlayerEnabled)
    }

    @discardableResult
    func saveLineup(_ draft: LiveLineupDraft) -> Bool {
        guard canRecordAction else { actionErrorMessage = "请先完成当前比赛决定，再调整阵容。"; return false }
        guard draft.source == game else { actionErrorMessage = "比赛局面已变化，请重新打开阵容后修改。"; return false }
        if let issue = draft.validationMessage { actionErrorMessage = issue; return false }
        guard draft.hasChanges else { return true }
        beginAtomicAction()
        recordHistoryCommand(.lineup(HistoryLineup(draft)))

        actionErrorMessage = nil
        pushUndo()
        let isHomeTeam = draft.isHomeTeam
        let beforeIDs = activeLineupPlayerIDs(forHomeTeam: isHomeTeam).union(activeFieldingPlayerIDs(forHomeTeam: isHomeTeam))
        let afterIDs = Set(draft.battingOrderIDs + draft.fieldingIDs)
        for id in beforeIDs.subtracting(afterIDs) { markPlayerExited(id, forHomeTeam: isHomeTeam) }
        let index = draft.battingOrderIDs.firstIndex(of: draft.batterAnchorID!)!
        if isHomeTeam {
            game.homeTeam.players = draft.players
            game.homeBattingOrderIDs = draft.battingOrderIDs
            game.homeFieldingPlayerIDs = draft.fieldingIDs
            game.homeDesignatedHitterID = draft.designatedHitterID
            game.activeHomePitcherID = draft.pitcherID
            game.homeBatterIndex = index
        } else {
            game.awayTeam.players = draft.players
            game.awayBattingOrderIDs = draft.battingOrderIDs
            game.awayFieldingPlayerIDs = draft.fieldingIDs
            game.awayDesignatedHitterID = draft.designatedHitterID
            game.activeAwayPitcherID = draft.pitcherID
            game.awayBatterIndex = index
        }
        game.baseRunners = draft.runners
        game.automaticRunnerIDs = draft.automaticRunnerIDs
        game.recordsLineupSnapshots = true
        addLog("阵容调整（\(draft.team.shortName)）：" + draft.changes.joined(separator: "；"), category: .substitution, notation: "LINEUP")
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    func confirmExtraInning(useTiebreak: Bool, runnerBases: [Base] = [.second]) {
        beginAtomicAction()
        recordHistoryCommand(.extraInning(useTiebreak, runnerBases))

        defer { commitAtomicAction() }
        guard canConfirmExtraInning else { return }
        pushUndo()
        game.extraInningConfirmed = true
        if useTiebreak {
            game.tiebreakStartInning = game.inning
            game.tiebreakRunnerBases = normalizedTiebreakBases(runnerBases)
        }
        addLog(
            useTiebreak
                ? "记录员确认进入第 \(game.inning) 局延长赛，并从本局启用 TB 垒上放人"
                : "记录员确认进入第 \(game.inning) 局普通延长赛",
            category: .tiebreak,
            notation: useTiebreak ? "TB" : "EX"
        )
    }

    func enableTiebreakFromCurrentInning(runnerBases: [Base] = [.second]) {
        beginAtomicAction()
        recordHistoryCommand(.enableTB(runnerBases))

        defer { commitAtomicAction() }
        guard canEnableTiebreak else { return }
        pushUndo()
        game.extraInningConfirmed = true
        game.tiebreakStartInning = game.inning
        game.tiebreakRunnerBases = normalizedTiebreakBases(runnerBases)
        addLog(
            "记录员从第 \(game.inning) 局启用 TB 垒上放人",
            category: .tiebreak,
            notation: "TB"
        )
    }

    func placeTiebreakRunner(_ player: Player, on requestedBase: Base? = nil) {
        beginAtomicAction()
        recordHistoryCommand(.placeTB(player.id, requestedBase))

        defer { commitAtomicAction() }
        let targetBase = requestedBase ?? nextTiebreakRunnerBase
        guard requiresTiebreakRunnerPlacement,
              eligibleTiebreakRunners.contains(where: { $0.id == player.id }),
              let targetBase,
              tiebreakRunnerBases.contains(targetBase),
              game.baseRunners[targetBase] == nil else { return }
        pushUndo()
        game.baseRunners[targetBase] = player
        var keys = game.tiebreakPlacementKeys ?? []
        keys.append(tiebreakPlacementKey(for: targetBase))
        game.tiebreakPlacementKeys = keys
        var automaticRunnerIDs = Set(game.automaticRunnerIDs ?? [])
        automaticRunnerIDs.insert(player.id)
        game.automaticRunnerIDs = Array(automaticRunnerIDs)
        addLog(
            "TB 放人：#\(player.numberText) \(player.name) 作为自动跑者置于\(targetBase.title)",
            category: .tiebreak,
            notation: "TB-R\(targetBase.rawValue)",
            primaryPlayerID: player.id
        )
    }

    func skipTiebreakRunnerForCurrentHalf() {
        beginAtomicAction()
        recordHistoryCommand(.skipTB)

        defer { commitAtomicAction() }
        guard requiresTiebreakRunnerPlacement else { return }
        pushUndo()
        var keys = game.tiebreakPlacementKeys ?? []
        for base in tiebreakRunnerBases where !keys.contains(tiebreakPlacementKey(for: base)) {
            keys.append(tiebreakPlacementKey(for: base))
        }
        game.tiebreakPlacementKeys = keys
        addLog(
            "本半局未放置 TB 自动跑者，标记为待确认",
            incomplete: true,
            category: .tiebreak,
            notation: "TB?"
        )
    }

    var canSwapOpeningSides: Bool {
        let isFresh = isHistoryReplay || activeStoredGame?.openingPitchRecorded == false
        return isFresh && !game.isFinal && game.inning == 1 && game.isTop
            && game.balls == 0 && game.strikes == 0 && game.outs == 0
            && game.homeScore == 0 && game.awayScore == 0 && game.baseRunners.isEmpty
            && game.pendingDecision == nil && game.halfEndedAwaitingDecision != true
            && game.pitching.values.allSatisfy { $0.pitches == 0 && $0.battersFaced == 0 }
            && game.batting.values.allSatisfy { $0.plateAppearances == 0 }
    }

    @discardableResult
    func swapOpeningSides() -> Bool {
        guard canSwapOpeningSides else {
            actionErrorMessage = "仅能在比赛首球前互换先攻／后攻；已记首球的比赛撤销后也不能互换。"
            return false
        }
        beginAtomicAction()
        recordHistoryCommand(.swapOpeningSides)
        pushUndo()
        (game.homeTeam, game.awayTeam) = (game.awayTeam, game.homeTeam)
        (game.homeBattingOrderIDs, game.awayBattingOrderIDs) = (game.awayBattingOrderIDs, game.homeBattingOrderIDs)
        (game.homeFieldingPlayerIDs, game.awayFieldingPlayerIDs) = (game.awayFieldingPlayerIDs, game.homeFieldingPlayerIDs)
        (game.homeDesignatedHitterID, game.awayDesignatedHitterID) = (game.awayDesignatedHitterID, game.homeDesignatedHitterID)
        (game.activeHomePitcherID, game.activeAwayPitcherID) = (game.activeAwayPitcherID, game.activeHomePitcherID)
        (game.homeExitedPlayerIDs, game.awayExitedPlayerIDs) = (game.awayExitedPlayerIDs, game.homeExitedPlayerIDs)
        (game.homeBatterIndex, game.awayBatterIndex) = (game.awayBatterIndex, game.homeBatterIndex)
        (game.homeRunsByInning, game.awayRunsByInning) = (game.awayRunsByInning, game.homeRunsByInning)
        (game.homeHits, game.awayHits) = (game.awayHits, game.homeHits)
        (game.homeErrors, game.awayErrors) = (game.awayErrors, game.homeErrors)
        (game.homeInterruptedAppearanceID, game.awayInterruptedAppearanceID) = (game.awayInterruptedAppearanceID, game.homeInterruptedAppearanceID)
        // Start a separate appearance for the new opening batter; pregame administrative logs remain intact.
        game.currentPlateAppearanceID = nil
        game.plateAppearancePitchCount = 0
        addLog("互换先攻／后攻：\(game.awayTeam.shortName) 先攻，\(game.homeTeam.shortName) 后攻", category: .game)
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    func undo() {
        beginAtomicAction()
        historySuppressCapture = true; actionErrorMessage = nil
        defer { commitAtomicAction() }
        finalizeLatestEventSituation()
        guard let pending = undoStack.last else { return }
        guard pending.correctionRevisions == game.correctionRevisions else {
            actionErrorMessage = "请从纠正记录 → 更正历史撤回更正，以保留审计记录和后续记分。"; return
        }
        guard let previous = undoStack.popLast() else { return }
        redoStack.append(game)
        if redoStack.count > 30 { redoStack.removeFirst() }
        game = previous
        nextEventBeforeSituation = game.situationSnapshot
    }

    func redo() {
        beginAtomicAction()
        historySuppressCapture = true; actionErrorMessage = nil
        defer { commitAtomicAction() }
        finalizeLatestEventSituation()
        guard let restored = redoStack.popLast() else { return }
        undoStack.append(game)
        if undoStack.count > 30 { undoStack.removeFirst() }
        game = restored
        nextEventBeforeSituation = game.situationSnapshot
    }

    func finishGame(reason: GameEndReason = .scorerDecision, at date: Date = Date()) {
        beginAtomicAction()
        recordHistoryCommand(.finish(reason, date))

        defer { commitAtomicAction() }
        pushUndo()
        if !reason.isCompletedResult {
            freezeGameClock(at: date)
            addLog("比赛中断：已保存当前局面，可稍后继续", category: .game, notation: "SUSP")
            return
        }
        markGameFinal(reason: reason, at: date)
        addLog("比赛结束：\(reason.rawValue)", category: .game, notation: "END")
    }

    func openGame(id: UUID) {
        guard let stored = games.first(where: { $0.id == id }) else { return }
        activeGameID = nil
        game = stored.state
        // Completed games still accept post-game reviews; keep their edits
        // attached to the stored snapshot so season statistics refresh too.
        activeGameID = stored.status == .scheduled ? nil : stored.id
        undoStack.removeAll()
        redoStack.removeAll()
    }

    @discardableResult
    func startScheduledGame(id: UUID) -> Bool {
        guard let index = games.firstIndex(where: { $0.id == id && $0.status == .scheduled }) else {
            return false
        }
        actionErrorMessage = nil
        beginAtomicAction()
        activeGameID = nil
        games[index].status = .ongoing
        games[index].openingPitchRecorded = false
        games[index].startedAt = nil
        games[index].updatedAt = Date()
        games[index].state.playLog.append(
            PlayLogEntry(inning: 1, isTop: true, text: "已进入现场记分，等待 Play Ball：\(games[index].state.awayTeam.shortName) 对 \(games[index].state.homeTeam.shortName)")
        )
        let stored = games[index]
        game = stored.state
        undoStack.removeAll()
        redoStack.removeAll()
        activeGameID = stored.id
        ensureCurrentAppearance()
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    @discardableResult
    func startScheduledGame(
        id: UUID,
        rules: GameRules,
        lineup: [LineupAssignment]
    ) -> Bool {
        guard let index = games.firstIndex(where: {
            $0.id == id && $0.status == .scheduled && !$0.isObservation
        }),
        let ourTeamID = games[index].ourTeamID,
        let opponentTeamID = games[index].opponentTeamID,
        var ourTeam = team(withID: ourTeamID),
        var opponent = opponentTeam(withID: opponentTeamID) else { return false }

        let orderedLineup = lineup.sorted { $0.battingOrder < $1.battingOrder }
        guard orderedLineup.count == rules.fieldersCount,
              Set(orderedLineup.map(\.playerID)).count == orderedLineup.count,
              Set(orderedLineup.map(\.position)).count == orderedLineup.count,
              orderedLineup.contains(where: { $0.position == .pitcher }),
              orderedLineup.contains(where: { $0.position == .catcher }) else { return false }
        for assignment in orderedLineup {
            guard let playerIndex = ourTeam.players.firstIndex(where: { $0.id == assignment.playerID }) else {
                return false
            }
            ourTeam.players[playerIndex].primaryPosition = assignment.position
        }
        guard opponent.players.count >= rules.fieldersCount else { return false }
        for playerIndex in opponent.players.indices.prefix(rules.fieldersCount) {
            opponent.players[playerIndex].primaryPosition = FieldPosition.allCases[playerIndex % FieldPosition.allCases.count]
        }

        let opponentLineup = defaultAssignments(for: opponent, count: rules.fieldersCount)
        let opponentParticipation = configuredParticipation(for: opponent, assignments: opponentLineup, rules: rules)
        let ourParticipation = configuredParticipation(for: ourTeam, assignments: orderedLineup, rules: rules)
        let opponentBattingOrder = opponentParticipation.battingOrder
        let ourBattingOrder = ourParticipation.battingOrder
        let home = games[index].isHome ? ourTeam : opponent
        let away = games[index].isHome ? opponent : ourTeam
        var state = GameState(
            homeTeam: home,
            awayTeam: away,
            scheduledInnings: rules.scheduledInnings,
            homeBattingOrderIDs: games[index].isHome ? ourBattingOrder : opponentBattingOrder,
            awayBattingOrderIDs: games[index].isHome ? opponentBattingOrder : ourBattingOrder
        )
        applyParticipation(ourParticipation, forHomeTeam: games[index].isHome, to: &state)
        applyParticipation(opponentParticipation, forHomeTeam: !games[index].isHome, to: &state)
        state.playLog.append(
            PlayLogEntry(inning: 1, isTop: true, text: "已进入现场记分：\(away.shortName) 对 \(home.shortName)，等待 Play Ball")
        )

        actionErrorMessage = nil
        beginAtomicAction()
        activeGameID = nil
        games[index].rules = rules
        games[index].lineup = orderedLineup
        games[index].secondaryLineup = nil
        games[index].status = .ongoing
        games[index].openingPitchRecorded = false
        games[index].startedAt = nil
        games[index].updatedAt = Date()
        games[index].state = state
        let stored = games[index]
        game = state
        undoStack.removeAll()
        redoStack.removeAll()
        activeGameID = stored.id
        ensureCurrentAppearance()
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    @discardableResult
    func startScheduledObservedGame(
        id: UUID,
        rules: GameRules,
        awayLineup requestedAwayLineup: [LineupAssignment]? = nil,
        homeLineup requestedHomeLineup: [LineupAssignment]? = nil
    ) -> Bool {
        guard let index = games.firstIndex(where: {
            $0.id == id && $0.status == .scheduled && $0.isObservation
        }) else { return false }

        let scheduled = games[index]
        guard let awayTeam = opponentTeam(withID: scheduled.state.awayTeam.id),
              let homeTeam = opponentTeam(withID: scheduled.state.homeTeam.id),
              awayTeam.players.count >= rules.fieldersCount,
              homeTeam.players.count >= rules.fieldersCount else { return false }

        let awayLineup = (requestedAwayLineup ?? defaultAssignments(for: awayTeam, count: rules.fieldersCount))
            .sorted { $0.battingOrder < $1.battingOrder }
        let homeLineup = (requestedHomeLineup ?? defaultAssignments(for: homeTeam, count: rules.fieldersCount))
            .sorted { $0.battingOrder < $1.battingOrder }
        guard awayLineup.count == rules.fieldersCount,
              homeLineup.count == rules.fieldersCount,
              Set(awayLineup.map(\.playerID)).count == awayLineup.count,
              Set(homeLineup.map(\.playerID)).count == homeLineup.count,
              Set(awayLineup.map(\.position)).count == awayLineup.count,
              Set(homeLineup.map(\.position)).count == homeLineup.count,
              awayLineup.allSatisfy({ assignment in awayTeam.players.contains(where: { $0.id == assignment.playerID }) }),
              homeLineup.allSatisfy({ assignment in homeTeam.players.contains(where: { $0.id == assignment.playerID }) }),
              awayLineup.contains(where: { $0.position == .pitcher }),
              awayLineup.contains(where: { $0.position == .catcher }),
              homeLineup.contains(where: { $0.position == .pitcher }),
              homeLineup.contains(where: { $0.position == .catcher }) else { return false }
        let configuredAway = applying(awayLineup, to: awayTeam)
        let configuredHome = applying(homeLineup, to: homeTeam)
        let awayParticipation = configuredParticipation(for: configuredAway, assignments: awayLineup, rules: rules)
        let homeParticipation = configuredParticipation(for: configuredHome, assignments: homeLineup, rules: rules)
        var state = GameState(
            homeTeam: configuredHome,
            awayTeam: configuredAway,
            scheduledInnings: rules.scheduledInnings,
            homeBattingOrderIDs: homeParticipation.battingOrder,
            awayBattingOrderIDs: awayParticipation.battingOrder
        )
        applyParticipation(homeParticipation, forHomeTeam: true, to: &state)
        applyParticipation(awayParticipation, forHomeTeam: false, to: &state)
        state.playLog.append(
            PlayLogEntry(inning: 1, isTop: true, text: "已进入观赛记分：\(awayTeam.shortName) 对 \(homeTeam.shortName)，等待 Play Ball")
        )

        actionErrorMessage = nil
        beginAtomicAction()
        activeGameID = nil
        games[index].rules = rules
        games[index].lineup = awayLineup
        games[index].secondaryLineup = homeLineup
        games[index].status = .ongoing
        games[index].openingPitchRecorded = false
        games[index].startedAt = nil
        games[index].updatedAt = Date()
        games[index].state = state
        let stored = games[index]
        game = state
        undoStack.removeAll()
        redoStack.removeAll()
        activeGameID = stored.id
        ensureCurrentAppearance()
        commitAtomicAction()
        return actionErrorMessage == nil
    }

    @discardableResult
    func deleteGame(id: UUID) -> Bool {
        guard !requiresDataRecovery, games.contains(where: { $0.id == id }) else { return false }
        do { try persistenceStore.deleteGame(id: id) }
        catch { storageErrorMessage = error.localizedDescription; return false }
        if activeGameID == id { activeGameID = nil }
        games.removeAll { $0.id == id }
        liveBroadcasts.close(id)
        return true
    }

    func previousLineup(for teamID: UUID, excluding gameID: UUID? = nil) -> [LineupAssignment]? {
        games
            .filter { $0.ourTeamID == teamID && $0.id != gameID }
            .sorted { $0.updatedAt > $1.updatedAt }
            .first?
            .lineup
    }

    var currentBattingOrder: Int { game.currentBattingOrder }

    private struct RuleNotice {
        let summary: String
        let detail: String
        var pitcherID: UUID? = nil
        var limitReached = false
    }

    func ruleNotices(at date: Date = Date()) -> [String] {
        detailedRuleNotices(at: date).map(\.detail)
    }

    /// The badge sits beside the current pitcher, so another pitcher's limit must be explicit.
    func ruleNoticeSummary(at date: Date = Date()) -> String? {
        let notices = detailedRuleNotices(at: date)
        if let current = notices.first(where: { $0.pitcherID == currentPitcher.id }) {
            return current.summary
        }
        if notices.contains(where: { $0.pitcherID != nil && $0.limitReached }) { return "其他达限" }
        return notices.first?.summary
    }

    private func detailedRuleNotices(at date: Date) -> [RuleNotice] {
        guard let activeGameID,
              let stored = games.first(where: { $0.id == activeGameID }),
              stored.status == .ongoing else { return [] }
        var notices: [RuleNotice] = []
        var reachedLimits: [RuleNotice] = []
        if game.inning == stored.rules.scheduledInnings {
            notices.append(RuleNotice(summary: "比赛末局", detail: "当前为规定的最后一局"))
        } else if game.inning > stored.rules.scheduledInnings {
            notices.append(RuleNotice(summary: "延长局", detail: "比赛已进入延长局"))
        }
        if hasStartedGameClock,
           let limit = stored.rules.timeLimitMinutes,
           let warning = stored.rules.timeWarningMinutes {
            let elapsed = max(0, Int(elapsedGameTime(at: date) / 60))
            let remaining = limit - elapsed
            if remaining <= warning {
                notices.append(RuleNotice(summary: remaining > 0 ? "余 \(remaining) 分钟" : "时间已到",
                    detail: remaining > 0 ? "比赛时间剩余约 \(remaining) 分钟" : "已到 \(limit) 分钟时间限制"))
            }
        }
        if !isCoachPitch {
            // Keep reached limits visible after the third out or a pitching change.
            for team in [game.fieldingTeam, game.battingTeam] {
                for player in team.players {
                    let line = pitchingLine(for: player)
                    let isCurrent = player.id == currentPitcher.id
                    let name = "\(team.shortName) · #\(player.numberText) \(player.name)"
                    if let limit = stored.rules.pitchLimit {
                        let remaining = limit - line.pitches
                        if remaining <= 0 {
                            reachedLimits.append(RuleNotice(summary: "球数达限",
                                detail: "\(name) 已达到 \(limit) 球限制（已投 \(line.pitches) 球）",
                                pitcherID: player.id, limitReached: true))
                        } else if isCurrent, let warning = stored.rules.pitchWarningRemaining, remaining <= warning {
                            notices.append(RuleNotice(summary: "还有 \(remaining) 球",
                                detail: "\(name) 距投球限制还剩 \(remaining) 球（\(line.pitches)/\(limit)）", pitcherID: player.id))
                        }
                    }
                    if let limit = stored.rules.pitcherInningsLimit {
                        if line.outsRecorded >= limit * 3 {
                            reachedLimits.append(RuleNotice(summary: "局数达限",
                                detail: "\(name) 已达到 \(limit) 局投球限制（\(line.inningsText) 局）",
                                pitcherID: player.id, limitReached: true))
                        } else if isCurrent, line.outsRecorded >= max(0, limit - 1) * 3 {
                            notices.append(RuleNotice(summary: "最后一局",
                                detail: "\(name) 已进入允许的最后一局（已投 \(line.inningsText) / \(limit) 局）", pitcherID: player.id))
                        }
                    }
                }
            }
        }
        return reachedLimits + notices
    }

    func battingLine(for player: Player) -> BattingLine {
        game.batting[player.id, default: BattingLine()]
    }

    func plateAppearanceRecords() -> [PlateAppearanceRecord] {
        var teamSequences: [Bool: Int] = [:]
        return (game.plateAppearances ?? []).compactMap { identity in
            let sequence = teamSequences[identity.isTop, default: 0] + 1
            teamSequences[identity.isTop] = sequence
            let team = identity.isTop ? game.awayTeam : game.homeTeam
            guard let batter = team.players.first(where: { $0.id == identity.batterIDs.last }) else { return nil }
            return PlateAppearanceRecord(id: identity.id, sequence: sequence, inning: identity.inning,
                isTop: identity.isTop, batter: batter,
                events: (game.scoringEvents ?? []).filter { $0.plateAppearanceID == identity.id },
                isComplete: identity.completed)
        }
    }

    func nonPlateAppearanceGameEvents() -> [ScoringEventRecord] {
        (game.scoringEvents ?? []).filter { !isPlateAppearanceEvent($0) }
    }

    func pitchingLine(for player: Player) -> PitchingLine {
        game.pitching[player.id, default: PitchingLine()]
    }

    func fieldingLine(for player: Player) -> FieldingLine {
        game.fielding[player.id, default: FieldingLine()]
    }

    func gameRecords(for player: Player, seasonID: String, teamID: UUID? = nil) -> [PlayerGameRecord] {
        let completed = completedStatisticsGames(teamID: teamID, seasonID: seasonID)
            .filter { $0.statisticsParticipantIDs.contains(player.id) }
        let derived = completed.map { stored in
            let result = stored.ourScore == stored.opponentScore ? "平" : (stored.ourScore > stored.opponentScore ? "胜" : "负")
            return PlayerGameRecord(
                id: stored.id, playerID: player.id, seasonID: seasonID,
                date: stored.startedAt ?? stored.scheduledAt ?? stored.createdAt,
                opponent: stored.opponent.name,
                result: "\(result) \(stored.ourScore):\(stored.opponentScore)",
                batting: stored.state.batting[player.id] ?? BattingLine()
            )
        }
        let gameIDs = Set(games.map(\.id))
        // Legacy records predate full game snapshots and contain batting only.
        let legacy = playerGameRecords.filter {
            $0.playerID == player.id && $0.seasonID == seasonID && !gameIDs.contains($0.id)
                && (teamID == nil || (teams + archivedTeams).contains(where: { $0.id == teamID && $0.players.contains(where: { $0.id == player.id }) }))
        }
        return (derived + legacy).sorted {
            $0.date == $1.date ? $0.id.uuidString < $1.id.uuidString : $0.date > $1.date
        }
    }

    func battingLine(for records: [PlayerGameRecord]) -> BattingLine {
        BattingLine.aggregate(records.map(\.batting))
    }

    func seasonBattingLine(for player: Player) -> BattingLine {
        guard let seasonID = statisticsSeasons.first?.id else { return BattingLine() }
        return battingLine(for: gameRecords(for: player, seasonID: seasonID))
    }

    func exitedPlayerIDs(forHomeTeam isHomeTeam: Bool) -> Set<UUID> {
        Set(isHomeTeam ? (game.homeExitedPlayerIDs ?? []) : (game.awayExitedPlayerIDs ?? []))
    }

    private func activeLineupPlayerIDs(forHomeTeam isHomeTeam: Bool) -> Set<UUID> {
        Set(isHomeTeam ? game.homeBattingOrderIDs : game.awayBattingOrderIDs)
    }

    private func isPlateAppearanceEvent(_ event: ScoringEventRecord) -> Bool {
        switch event.category {
        case .pitch, .battedBall, .runner, .out, .violation:
            true
        case .game, .clock, .substitution, .tiebreak, .correction:
            false
        }
    }

    private func plateAppearanceBatter(for event: ScoringEventRecord) -> Player? {
        let battingTeam = event.isTop ? game.awayTeam : game.homeTeam
        if let id = event.plateAppearanceBatterID,
           let player = battingTeam.players.first(where: { $0.id == id }) {
            return player
        }
        if event.category == .pitch,
           let id = event.secondaryPlayerID,
           let player = battingTeam.players.first(where: { $0.id == id }) {
            return player
        }
        if let id = event.primaryPlayerID,
           let player = battingTeam.players.first(where: { $0.id == id }) {
            return player
        }
        if let id = event.secondaryPlayerID,
           let player = battingTeam.players.first(where: { $0.id == id }) {
            return player
        }
        guard let snapshot = event.beforeSituation ?? event.afterSituation else { return nil }
        let order = event.isTop ? (snapshot.awayBattingOrderIDs ?? game.awayBattingOrderIDs) : (snapshot.homeBattingOrderIDs ?? game.homeBattingOrderIDs)
        guard !order.isEmpty else { return nil }
        let index = event.isTop ? snapshot.awayBatterIndex : snapshot.homeBatterIndex
        let id = order[index % order.count]
        return battingTeam.players.first(where: { $0.id == id })
    }

    private func eventEndsPlateAppearance(_ event: ScoringEventRecord) -> Bool {
        if event.resolvedOutcome != nil { return true }
        let terminalNotations: Set<String> = [
            "BB", "IBB", "HBP", "K", "ꓘ", "K-BUNT", "K WP/PB", "CI", "INT/ILLEGAL", "PITCH-LIMIT"
        ]
        if let notation = event.notation, terminalNotations.contains(notation) { return true }
        if event.category == .violation,
           (event.title.contains("打者出局") || event.title.contains("打者上一垒")) {
            return true
        }
        guard let before = event.beforeSituation, let after = event.afterSituation else { return false }
        if before.inning != after.inning || before.isTop != after.isTop { return false }
        return event.isTop
            ? before.awayBatterIndex != after.awayBatterIndex
            : before.homeBatterIndex != after.homeBatterIndex
    }

    private func activeFieldingPlayerIDs(forHomeTeam isHomeTeam: Bool) -> Set<UUID> {
        let stored = isHomeTeam ? game.homeFieldingPlayerIDs : game.awayFieldingPlayerIDs
        return Set(stored ?? Array(activeLineupPlayerIDs(forHomeTeam: isHomeTeam)))
    }

    private func availableBenchPlayers(forHomeTeam isHomeTeam: Bool) -> [Player] {
        let team = isHomeTeam ? game.homeTeam : game.awayTeam
        let unavailable = activeLineupPlayerIDs(forHomeTeam: isHomeTeam)
            .union(activeFieldingPlayerIDs(forHomeTeam: isHomeTeam))
            .union(game.baseRunners.values.map(\.id))
        return team.sortedPlayers.filter { !unavailable.contains($0.id) }
    }

    private func replaceLineupPlayer(_ previousID: UUID, with replacementID: UUID, forHomeTeam isHomeTeam: Bool) {
        var order = isHomeTeam ? game.homeBattingOrderIDs : game.awayBattingOrderIDs
        guard let index = order.firstIndex(of: previousID), !order.contains(replacementID) else { return }
        order[index] = replacementID
        if isHomeTeam {
            game.homeBattingOrderIDs = order
        } else {
            game.awayBattingOrderIDs = order
        }
    }

    private func replaceFieldingPlayer(_ previousID: UUID, with replacementID: UUID, forHomeTeam isHomeTeam: Bool) {
        var fielders = isHomeTeam
            ? (game.homeFieldingPlayerIDs ?? game.homeBattingOrderIDs)
            : (game.awayFieldingPlayerIDs ?? game.awayBattingOrderIDs)
        guard let index = fielders.firstIndex(of: previousID), !fielders.contains(replacementID) else { return }
        fielders[index] = replacementID
        if isHomeTeam {
            game.homeFieldingPlayerIDs = fielders
        } else {
            game.awayFieldingPlayerIDs = fielders
        }
    }

    private func replaceDesignatedHitterIfNeeded(
        _ previousID: UUID,
        with replacementID: UUID,
        forHomeTeam isHomeTeam: Bool
    ) {
        if isHomeTeam, game.homeDesignatedHitterID == previousID {
            game.homeDesignatedHitterID = replacementID
        } else if !isHomeTeam, game.awayDesignatedHitterID == previousID {
            game.awayDesignatedHitterID = replacementID
        }
    }

    private func inheritFieldingPosition(from previous: Player, to replacement: Player, forHomeTeam isHomeTeam: Bool) {
        var team = isHomeTeam ? game.homeTeam : game.awayTeam
        guard let oldIndex = team.players.firstIndex(where: { $0.id == previous.id }),
              let newIndex = team.players.firstIndex(where: { $0.id == replacement.id }) else { return }
        team.players[newIndex].primaryPosition = team.players[oldIndex].primaryPosition
        if isHomeTeam {
            game.homeTeam = team
            if game.activeHomePitcherID == previous.id { game.activeHomePitcherID = replacement.id }
        } else {
            game.awayTeam = team
            if game.activeAwayPitcherID == previous.id { game.activeAwayPitcherID = replacement.id }
        }
    }

    private func markPlayerExited(_ playerID: UUID, forHomeTeam isHomeTeam: Bool) {
        var exited = exitedPlayerIDs(forHomeTeam: isHomeTeam)
        exited.insert(playerID)
        if isHomeTeam {
            game.homeExitedPlayerIDs = Array(exited)
        } else {
            game.awayExitedPlayerIDs = Array(exited)
        }
    }

    private var currentHalfKey: String {
        "\(game.inning)-\(game.isTop ? "top" : "bottom")"
    }

    private func tiebreakPlacementKey(for base: Base) -> String {
        "\(currentHalfKey)-base\(base.rawValue)"
    }

    private func normalizedTiebreakBases(_ bases: [Base]) -> [Base] {
        let unique = Array(Set(bases)).sorted { $0.rawValue < $1.rawValue }
        return unique.isEmpty ? [.second] : unique
    }

    private func normalizedNumbers(_ numbers: [Int]) -> [Int] {
        var seen = Set<Int>()
        return numbers.filter { (0...99).contains($0) && seen.insert($0).inserted }
    }

    private func defaultAssignments(for team: Team, count: Int) -> [LineupAssignment] {
        Array(team.players.prefix(count)).enumerated().map { index, player in
            LineupAssignment(
                playerID: player.id,
                battingOrder: index + 1,
                position: FieldPosition.allCases[index % FieldPosition.allCases.count]
            )
        }
    }

    private typealias TeamParticipation = (
        battingOrder: [UUID],
        fieldingPlayers: [UUID],
        designatedHitterID: UUID?,
        pitcherID: UUID?
    )

    private func configuredParticipation(
        for team: Team,
        assignments: [LineupAssignment],
        rules: GameRules
    ) -> TeamParticipation {
        let ordered = assignments.sorted { $0.battingOrder < $1.battingOrder }
        let fieldingPlayers = ordered.map(\.playerID)
        let selectedPitcherID = ordered.first(where: { $0.position == .pitcher })?.playerID
        guard rules.designatedHitterEnabled, let pitcherID = selectedPitcherID else {
            return (fieldingPlayers, fieldingPlayers, nil, selectedPitcherID)
        }
        if rules.twoWayPlayerEnabled {
            return (fieldingPlayers, fieldingPlayers, pitcherID, pitcherID)
        }
        guard let hitter = team.players.first(where: { !fieldingPlayers.contains($0.id) }),
              let pitcherSlot = fieldingPlayers.firstIndex(of: pitcherID) else {
            return (fieldingPlayers, fieldingPlayers, nil, pitcherID)
        }
        var battingOrder = fieldingPlayers
        battingOrder[pitcherSlot] = hitter.id
        return (battingOrder, fieldingPlayers, hitter.id, pitcherID)
    }

    private func applyParticipation(
        _ participation: TeamParticipation,
        forHomeTeam isHomeTeam: Bool,
        to state: inout GameState
    ) {
        if isHomeTeam {
            state.activeHomePitcherID = participation.pitcherID
            state.homeFieldingPlayerIDs = participation.fieldingPlayers
            state.homeDesignatedHitterID = participation.designatedHitterID
        } else {
            state.activeAwayPitcherID = participation.pitcherID
            state.awayFieldingPlayerIDs = participation.fieldingPlayers
            state.awayDesignatedHitterID = participation.designatedHitterID
        }
    }

    private func applying(_ assignments: [LineupAssignment], to team: Team) -> Team {
        var result = team
        for assignment in assignments {
            if let index = result.players.firstIndex(where: { $0.id == assignment.playerID }) {
                result.players[index].primaryPosition = assignment.position
            }
        }
        return result
    }

    private func syncCurrentTeamIfNeeded(_ teamID: UUID) {
        guard currentTeam?.id == teamID,
              let updated = team(withID: teamID) else { return }
        currentTeam = updated
    }

    private func applyWalkWithoutUndo() {
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        let responsible = game.walkResponsiblePitcherID ?? pitcher.id
        var line = game.batting[batter.id, default: BattingLine()]
        line.plateAppearances += 1
        line.walks += 1
        game.batting[batter.id] = line
        updatePitcher(responsible) {
            $0.battersFaced += 1
            $0.walks += 1
        }

        var decisions = forcedAdvanceDecisions(for: batter)
        let resolution = resolveRunners(decisions, batterResponsiblePitcherID: responsible)
        updateBatter(batter.id) { $0.runsBattedIn += resolution.runs }
        addRuns(resolution.runs)
        for scorer in resolution.scorers { updateBatter(scorer.id) { $0.runs += 1 } }
        chargePitcherRuns(resolution.scorers, fallback: responsible, earned: true)
        addLog(
            "\(batter.name) 四坏球保送上一垒\(resolution.runs > 0 ? "，挤回 \(resolution.runs) 分" : "")",
            category: .battedBall,
            notation: "BB",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id,
            runnerDecisions: decisions
        )
        if evaluateWalkOff() { return }
        advanceBatter()
        decisions.removeAll()
    }

    private func applyStrikeoutWithoutUndo(swinging: Bool) {
        let batter = game.currentBatter
        let pitcher = game.currentPitcher
        updateBatter(game.strikeoutResponsibleBatterID ?? batter.id) {
            $0.plateAppearances += 1
            $0.atBats += 1
            $0.strikeouts += 1
        }
        updatePitcher(pitcher.id) {
            $0.battersFaced += 1
            $0.strikeouts += 1
            $0.outsRecorded += 1
        }
        addLog(
            "\(batter.name) \(swinging ? "挥棒" : "看着")三振出局（K）",
            category: .out,
            notation: swinging ? "K" : "ꓘ",
            primaryPlayerID: batter.id,
            secondaryPlayerID: isCoachPitch ? nil : pitcher.id
        )
        advanceBatter()
        registerOuts(1)
    }

    private func forcedAdvanceDecisions(for batter: Player) -> [RunnerDecision] {
        var decisions: [RunnerDecision] = []
        if let runner = game.baseRunners[.third], game.baseRunners[.second] != nil, game.baseRunners[.first] != nil {
            decisions.append(RunnerDecision(player: runner, origin: .base(.third), destination: .score))
        } else if let runner = game.baseRunners[.third] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.third), destination: .hold))
        }
        if let runner = game.baseRunners[.second] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.second), destination: game.baseRunners[.first] == nil ? .hold : .base(.third)))
        }
        if let runner = game.baseRunners[.first] {
            decisions.append(RunnerDecision(player: runner, origin: .base(.first), destination: .base(.second)))
        }
        decisions.append(RunnerDecision(player: batter, origin: .batter, destination: .base(.first)))
        return decisions
    }

    private func suggestedDestination(from base: Base, outcome: PlayOutcome) -> RunnerDestination {
        switch outcome {
        case .homeRun: return .score
        case .triple:
            return base == .first ? .score : .score
        case .double:
            return base == .first ? .base(.third) : .score
        case .single, .error, .pending:
            switch base {
            case .first: return .base(.second)
            case .second: return .base(.third)
            case .third: return .score
            }
        case .sacrificeFly:
            return base == .third ? .score : .hold
        case .sacrificeBunt:
            switch base {
            case .first: return .base(.second)
            case .second: return .base(.third)
            case .third: return .score
            }
        case .fieldersChoice, .runnerTagOut, .doublePlay, .triplePlay, .groundOut, .flyOut,
             .lineOut, .foulFlyOut, .infieldFly, .pendingOut, .other:
            return .hold
        }
    }

    private func nextDestination(after base: Base) -> RunnerDestination {
        switch base {
        case .first: .base(.second)
        case .second: .base(.third)
        case .third: .score
        }
    }

    private func resolveRunners(_ decisions: [RunnerDecision], batterResponsiblePitcherID: UUID? = nil, unearnedBatter: Bool = false) -> (runs: Int, outs: Int, scorers: [Player]) {
        var newBases: [Base: Player] = [:]
        var scorers: [Player] = []
        var outs = 0
        var automaticRunnerIDs = Set(game.automaticRunnerIDs ?? [])

        var responsibility = game.runnerPitcherIDs ?? [:]
        var unearned = Set(game.unearnedRunnerIDs ?? [])
        unearned.formUnion(automaticRunnerIDs)
        for runner in game.baseRunners.values where responsibility[runner.id] == nil { responsibility[runner.id] = game.currentPitcher.id }
        for decision in decisions {
            if case .batter = decision.origin {
                responsibility[decision.player.id] = batterResponsiblePitcherID ?? game.currentPitcher.id
                unearned.remove(decision.player.id)
                if unearnedBatter { unearned.insert(decision.player.id) }
            }
            switch decision.destination {
            case .hold:
                if case .base(let base) = decision.origin { newBases[base] = decision.player }
            case .base(let base):
                newBases[base] = decision.player
            case .score:
                scorers.append(decision.player)
                automaticRunnerIDs.remove(decision.player.id)
            case .out:
                outs += 1
                automaticRunnerIDs.remove(decision.player.id)
            }
        }
        game.runnerPitcherIDs = responsibility
        game.unearnedRunnerIDs = Array(unearned).sorted { $0.uuidString < $1.uuidString }
        game.baseRunners = newBases
        game.automaticRunnerIDs = Array(automaticRunnerIDs)
        scorers = Array(scorers.prefix(remainingHalfRuns))
        actionEffectiveScorers = scorers.map(\.id)
        return (scorers.count, outs, scorers)
    }

    private func automaticRunnerRunCount(in decisions: [RunnerDecision]) -> Int {
        let automaticRunnerIDs = Set(game.automaticRunnerIDs ?? [])
        return decisions.filter { $0.destination == .score }.prefix(remainingHalfRuns).filter { automaticRunnerIDs.contains($0.player.id) }.count
    }

    private func runnerSummary(_ decisions: [RunnerDecision]) -> String {
        decisions
            .filter { decision in
                if case .hold = decision.destination { return false }
                return true
            }
            .map { "\($0.player.name)\($0.destination == .score && !(actionEffectiveScorers ?? []).contains($0.player.id) ? "回本垒（不计分）" : $0.destination.title)" }
            .joined(separator: "，")
    }

    private func applyDefensiveCredits(_ play: DefensivePlay?, outcome: PlayOutcome) {
        guard let play else { return }
        let activeIDs = activeFieldingPlayerIDs(forHomeTeam: game.isTop)
        let defense = game.fieldingTeam.players.filter { activeIDs.contains($0.id) }
        for position in play.assistPositions {
            if let player = defense.first(where: { $0.primaryPosition == position }) {
                updateFielder(player.id) { $0.assists += 1 }
            }
        }
        for position in play.putoutPositions {
            if let player = defense.first(where: { $0.primaryPosition == position }) {
                updateFielder(player.id) { $0.putouts += 1 }
            }
        }
        if outcome == .doublePlay {
            for position in Set(play.routePositions) {
                if let player = defense.first(where: { $0.primaryPosition == position }) {
                    updateFielder(player.id) { $0.doublePlays += 1 }
                }
            }
        }
        if let position = play.errorPosition,
           let player = defense.first(where: { $0.primaryPosition == position }) {
            updateFielder(player.id) { $0.errors += 1 }
            if game.isTop { game.homeErrors += 1 } else { game.awayErrors += 1 }
        }
    }

    private func registerOuts(_ newOuts: Int) {
        game.outs = min(3, game.outs + newOuts)
        guard game.outs >= 3 else { return }
        endHalfWithoutUndo(reason: "三出局，攻守交换")
    }

    @discardableResult
    private func evaluateWalkOff() -> Bool {
        if !game.isTop, game.inning >= game.scheduledInnings,
           game.homeScore > game.awayScore, game.dismissedWalkOffHalf != currentHalfKey {
            game.pendingDecision = .walkOff
        }
        return false
    }

    private func markGameFinal(reason: GameEndReason, at date: Date = Date()) {
        let date = historyReplayDate ?? date
        freezeGameClock(at: date)
        game.isFinal = true
        game.endedAt = date
        game.endReason = reason
        game.canResumePrecisely = true
        game.pendingDecision = nil
    }

    private func advanceBatter() {
        game.walkResponsiblePitcherID = nil
        game.strikeoutResponsibleBatterID = nil
        closeAppearance(completed: true)
        let lineupCount = max(1, game.battingOrderIDs.count)
        if game.isTop {
            game.awayBatterIndex = (game.awayBatterIndex + 1) % lineupCount
        } else {
            game.homeBatterIndex = (game.homeBatterIndex + 1) % lineupCount
        }
        game.balls = 0
        game.strikes = 0
    }

    private func addRuns(_ runs: Int) {
        guard runs > 0 else { return }
        ensureInningCapacity()
        if game.isTop {
            game.awayRunsByInning[game.inning - 1] += runs
        } else {
            game.homeRunsByInning[game.inning - 1] += runs
        }
    }

    private func chargePitcherRuns(_ scorers: [Player], fallback: UUID, earned: Bool) {
        let unearned = Set(game.unearnedRunnerIDs ?? [])
        for scorer in scorers {
            let owner = game.runnerPitcherIDs?[scorer.id] ?? fallback
            updatePitcher(owner) { line in
                line.runs += 1
                if earned && !unearned.contains(scorer.id) { line.earnedRuns += 1 }
            }
        }
    }

    private func addRunsAndPitcherResponsibility(
        _ resolution: (runs: Int, outs: Int, scorers: [Player]),
        pitcherID: UUID,
        earned: Bool,
        automaticRunnerRuns: Int = 0
    ) {
        addRuns(resolution.runs)
        for scorer in resolution.scorers {
            updateBatter(scorer.id) { $0.runs += 1 }
        }
        chargePitcherRuns(resolution.scorers, fallback: pitcherID, earned: earned)
    }

    private func setScore(_ target: Int, forHomeTeam: Bool) {
        var innings = forHomeTeam ? game.homeRunsByInning : game.awayRunsByInning
        while innings.count < game.inning { innings.append(0) }
        var difference = target - innings.reduce(0, +)
        if difference >= 0 {
            innings[game.inning - 1] += difference
        } else {
            for index in innings.indices.reversed() where difference < 0 {
                let removable = min(innings[index], -difference)
                innings[index] -= removable
                difference += removable
            }
        }
        if forHomeTeam {
            game.homeRunsByInning = innings
        } else {
            game.awayRunsByInning = innings
        }
    }

    private func incrementTeamHit() {
        if game.isTop { game.awayHits += 1 } else { game.homeHits += 1 }
    }

    private func ensureInningCapacity() {
        while game.homeRunsByInning.count < game.inning {
            game.homeRunsByInning.append(0)
            game.awayRunsByInning.append(0)
        }
    }

    private func updateBatter(_ id: UUID, _ change: (inout BattingLine) -> Void) {
        var line = game.batting[id, default: BattingLine()]
        change(&line)
        game.batting[id] = line
    }

    private func updatePitcher(_ id: UUID, _ change: (inout PitchingLine) -> Void) {
        guard !isCoachPitch else { return }
        var line = game.pitching[id, default: PitchingLine()]
        change(&line)
        game.pitching[id] = line
    }

    private func updateFielder(_ id: UUID, _ change: (inout FieldingLine) -> Void) {
        var line = game.fielding[id, default: FieldingLine()]
        change(&line)
        game.fielding[id] = line
    }

    private func matchingLogIndex(for event: ScoringEventRecord) -> Int? {
        if let logEntryID = event.logEntryID,
           let index = game.playLog.firstIndex(where: { $0.id == logEntryID }) {
            return index
        }
        return game.playLog.firstIndex {
            $0.timestamp == event.timestamp && $0.text == event.title
        }
    }

    private func timelineValidationError(
        in events: [ScoringEventRecord],
        startingAt requestedIndex: Int
    ) -> String? {
        guard !events.isEmpty else { return nil }
        let startIndex = min(max(0, requestedIndex), events.count - 1)
        var expectedSituation = startIndex > 0
            ? events[startIndex - 1].afterSituation
            : events[startIndex].beforeSituation

        for index in startIndex..<events.count {
            let event = events[index]
            if let before = event.beforeSituation {
                if let issue = snapshotValidationError(before) {
                    return "第 \(index + 1) 条“\(event.title)”的修改前局面无效：\(issue)"
                }
                if let expectedSituation, expectedSituation != before {
                    // Events written before structured timeline V2 did not carry
                    // reliable linkage IDs. They remain readable but are treated
                    // as a new replay checkpoint instead of blocking migration.
                    let isLegacy = event.logEntryID == nil
                    if !isLegacy {
                        return "第 \(index + 1) 条“\(event.title)”与上一条记录的局面不衔接。"
                    }
                }
            } else if expectedSituation != nil, event.logEntryID != nil {
                return "第 \(index + 1) 条“\(event.title)”缺少修改前局面。"
            }

            guard let after = event.afterSituation else {
                if event.logEntryID != nil {
                    return "第 \(index + 1) 条“\(event.title)”缺少修改后局面。"
                }
                continue
            }
            if let issue = snapshotValidationError(after) {
                return "第 \(index + 1) 条“\(event.title)”的修改后局面无效：\(issue)"
            }
            if event.category != .correction,
               let before = event.beforeSituation,
               after.inning < before.inning {
                return "第 \(index + 1) 条“\(event.title)”造成局次倒退。"
            }
            expectedSituation = after
        }
        return nil
    }

    private func snapshotValidationError(_ snapshot: GameSituationSnapshot) -> String? {
        guard snapshot.inning >= 1 else { return "局数必须大于零" }
        // A pitch event is stored before its automatic walk/strikeout event,
        // so 4 balls and 3 strikes are valid transient snapshots in the event
        // chain even though they are never left as the live editable count.
        guard (0...4).contains(snapshot.balls),
              (0...3).contains(snapshot.strikes),
              (0...2).contains(snapshot.outs) else {
            return "球数或出局数超过合法范围"
        }
        guard snapshot.homeRunsByInning.count >= snapshot.inning,
              snapshot.awayRunsByInning.count >= snapshot.inning,
              snapshot.homeRunsByInning.allSatisfy({ $0 >= 0 }),
              snapshot.awayRunsByInning.allSatisfy({ $0 >= 0 }) else {
            return "逐局比分与当前局次不一致"
        }
        let runnerIDs = snapshot.baseRunners.map(\.playerID)
        guard Set(runnerIDs).count == runnerIDs.count,
              Set(snapshot.baseRunners.map(\.base)).count == snapshot.baseRunners.count else {
            return "存在重复跑者或垒位冲突"
        }
        let battingTeam = snapshot.isTop ? game.awayTeam : game.homeTeam
        guard runnerIDs.allSatisfy({ id in battingTeam.players.contains(where: { $0.id == id }) }) else {
            return "垒上存在不属于进攻方的球员"
        }
        for (order, team) in [(snapshot.homeBattingOrderIDs, game.homeTeam), (snapshot.awayBattingOrderIDs, game.awayTeam)] {
            if let order, order.isEmpty || Set(order).count != order.count || !Set(order).isSubset(of: Set(team.players.map(\.id))) {
                return "棒次包含重复或不属于该队的球员"
            }
        }
        let battingOrderCount = snapshot.isTop
            ? (snapshot.awayBattingOrderIDs ?? game.awayBattingOrderIDs).count
            : (snapshot.homeBattingOrderIDs ?? game.homeBattingOrderIDs).count
        let batterIndex = snapshot.isTop ? snapshot.awayBatterIndex : snapshot.homeBatterIndex
        guard battingOrderCount > 0,
              batterIndex >= 0,
              batterIndex < battingOrderCount else {
            return "当前打者不在有效棒次内"
        }
        if let pitcherID = snapshot.isTop ? snapshot.activeHomePitcherID : snapshot.activeAwayPitcherID {
            let fieldingTeam = snapshot.isTop ? game.homeTeam : game.awayTeam
            guard fieldingTeam.players.contains(where: { $0.id == pitcherID }) else {
                return "当前投手不属于防守方"
            }
        }
        return nil
    }

    func applySituationSnapshot(_ snapshot: GameSituationSnapshot, to state: inout GameState) -> Bool {
        let battingTeam = snapshot.isTop ? state.awayTeam : state.homeTeam
        var runners: [Base: Player] = [:]
        for occupancy in snapshot.baseRunners {
            guard let player = battingTeam.players.first(where: { $0.id == occupancy.playerID }),
                  runners[occupancy.base] == nil else { return false }
            runners[occupancy.base] = player
        }
        state.inning = snapshot.inning
        state.isTop = snapshot.isTop
        state.balls = snapshot.balls
        state.strikes = snapshot.strikes
        state.outs = snapshot.outs
        state.homeRunsByInning = snapshot.homeRunsByInning
        state.awayRunsByInning = snapshot.awayRunsByInning
        if let order = snapshot.homeBattingOrderIDs { state.homeBattingOrderIDs = order }
        if let order = snapshot.awayBattingOrderIDs { state.awayBattingOrderIDs = order }
        state.homeBatterIndex = snapshot.homeBatterIndex
        state.awayBatterIndex = snapshot.awayBatterIndex
        state.baseRunners = runners
        state.runnerPitcherIDs = snapshot.runnerPitcherIDs
        state.unearnedRunnerIDs = snapshot.unearnedRunnerIDs
        state.activeHomePitcherID = snapshot.activeHomePitcherID
        state.activeAwayPitcherID = snapshot.activeAwayPitcherID
        return true
    }

    func pushUndo(startsClock: Bool = false) {
        ensureCurrentAppearance()
        finalizeLatestEventSituation()
        if startsClock, activeGameID != nil, !hasStartedGameClock {
            startGameClock()
        }
        undoStack.append(game)
        if undoStack.count > 30 { undoStack.removeFirst() }
        redoStack.removeAll()
        nextEventBeforeSituation = game.situationSnapshot
    }

    func addLog(
        _ text: String,
        incomplete: Bool = false,
        category: ScoringEventCategory = .game,
        notation: String? = nil,
        primaryPlayerID: UUID? = nil,
        secondaryPlayerID: UUID? = nil,
        runnerDecisions: [RunnerDecision] = [],
        ballStatus: BallStatus? = nil,
        resolvedOutcome: PlayOutcome? = nil,
        automaticRunnerRuns: Int? = nil
    ) {
        let timestamp = historyReplayDate ?? Date()
        let logEntry = PlayLogEntry(
            inning: game.inning,
            isTop: game.isTop,
            text: text,
            timestamp: timestamp,
            isIncomplete: incomplete
        )
        game.playLog.append(logEntry)
        let movements = runnerDecisions.map {
            RecordedRunnerMovement(
                playerID: $0.player.id,
                origin: $0.origin.title,
                destination: $0.destination == .score && !(actionEffectiveScorers ?? []).contains($0.player.id) ? "回本垒（不计分）" : $0.destination.title
            )
        }
        let undoSituation = undoStack.last?.situationSnapshot
        let previousEventSituation = game.scoringEvents?.last?.afterSituation
        let beforeSituation = nextEventBeforeSituation ?? previousEventSituation ?? undoSituation
        let plateAppearanceBatterID: UUID? = switch category {
        case .pitch, .battedBall, .runner, .out, .violation:
            game.currentBatter.id
        case .game, .clock, .substitution, .tiebreak, .correction:
            nil
        }
        var event = ScoringEventRecord(
            logEntryID: logEntry.id,
            inning: game.inning,
            isTop: game.isTop,
            timestamp: timestamp,
            category: category,
            title: text,
            notation: notation,
            primaryPlayerID: primaryPlayerID,
            secondaryPlayerID: secondaryPlayerID,
            plateAppearanceBatterID: plateAppearanceBatterID,
            runnerMovements: movements,
            needsReview: incomplete,
            ballStatus: ballStatus,
            resolvedOutcome: resolvedOutcome,
            automaticRunnerRuns: automaticRunnerRuns,
            beforeSituation: beforeSituation,
            afterSituation: game.situationSnapshot
        )
        if plateAppearanceBatterID != nil {
            ensureCurrentAppearance()
            event.plateAppearanceID = game.currentPlateAppearanceID
            event.effectiveScorerIDs = actionEffectiveScorers
        }
        var events = game.scoringEvents ?? []
        events.append(event)
        game.scoringEvents = events
        nextEventBeforeSituation = game.situationSnapshot
    }

    private func finalizeLatestEventSituation() {
        guard var events = game.scoringEvents,
              !events.isEmpty,
              events[events.count - 1].afterSituation != game.situationSnapshot else {
            nextEventBeforeSituation = game.situationSnapshot
            return
        }
        events[events.count - 1].afterSituation = game.situationSnapshot
        game.scoringEvents = events
        nextEventBeforeSituation = game.situationSnapshot
    }

    private func freezeGameClock(at date: Date) {
        guard let runningSince = game.clockRunningSince else { return }
        game.clockElapsedSeconds = max(0, game.clockElapsedSeconds ?? 0)
            + max(0, date.timeIntervalSince(runningSince))
        game.clockRunningSince = nil
    }

    private func persistActiveStoredGameMetadata() {
        guard !isHistoryReplay, actionDepth == 0, !requiresDataRecovery else { return }
        guard let activeGameID,
              let index = games.firstIndex(where: { $0.id == activeGameID }) else { return }
        games[index].state = game
        games[index].updatedAt = Date()
        let stored = games[index]
        persist { try persistenceStore.upsertGame(stored) }
    }

    var isCoachPitch: Bool { activeRules?.gameMode == .coachPitch }
    var coachPitchLimit: Int { min(20, max(1, activeRules?.coachPitchLimit ?? 6)) }
    var canRecordAction: Bool { !requiresDataRecovery && !game.isFinal && game.pendingDecision == nil }
    var remainingHalfRuns: Int {
        guard let limit = activeRules?.halfInningRunLimit else { return Int.max }
        let values = game.isTop ? game.awayRunsByInning : game.homeRunsByInning
        return max(0, limit - (values.indices.contains(game.inning - 1) ? values[game.inning - 1] : 0))
    }
    var allowedRunnerEvents: [RunnerEventKind] {
        RunnerEventKind.allCases.filter {
            !isCoachPitch || ![.stolenBase, .doubleSteal, .delayedSteal, .caughtStealing, .leftEarly,
                .wildPitch, .passedBall, .uncertainLooseBall, .balk].contains($0)
        }
    }

    func beginAtomicAction() {
        if actionDepth == 0 {
            historyPendingCommand = nil; historySuppressCapture = false; actionErrorMessage = nil
            actionOriginal = game
            actionOriginalGames = games
            actionOriginalActiveID = activeGameID
            actionUndo = undoStack; actionRedo = redoStack
            actionEffectiveScorers = nil
        }
        actionDepth += 1
    }

    func commitAtomicAction() {
        guard actionDepth > 0 else { return }
        if actionDepth > 1 { actionDepth -= 1; return }
        defer { actionDepth = 0; actionOriginal = nil; actionOriginalGames = []; actionUndo = []; actionRedo = []; historyPendingCommand = nil; historySuppressCapture = false }
        guard let original = actionOriginal else { return }
        if requiresDataRecovery { game = original; return }
        guard game != original || games != actionOriginalGames else { return }
        if !historySuppressCapture, original.inning == game.inning, original.isTop == game.isTop {
            if original.currentPitcher.id != game.currentPitcher.id {
                var owners = game.runnerPitcherIDs ?? [:]
                for runner in original.baseRunners.values where owners[runner.id] == nil { owners[runner.id] = original.currentPitcher.id }
                game.runnerPitcherIDs = owners
                game.walkResponsiblePitcherID = original.balls >= 3 || (original.balls == 2 && original.strikes < 2)
                    ? original.walkResponsiblePitcherID ?? original.currentPitcher.id : nil
            }
            if case .lineup = historyPendingCommand {
                for base in Base.allCases {
                    if let old = original.baseRunners[base], let new = game.baseRunners[base], old.id != new.id {
                        var owners = game.runnerPitcherIDs ?? [:]; owners[new.id] = original.runnerPitcherIDs?[old.id] ?? original.currentPitcher.id; game.runnerPitcherIDs = owners
                        if (original.unearnedRunnerIDs ?? []).contains(old.id) { game.unearnedRunnerIDs = Array(Set(game.unearnedRunnerIDs ?? []).union([new.id])).sorted { $0.uuidString < $1.uuidString } }
                    }
                }
                if original.strikes == 2, original.currentBatter.id != game.currentBatter.id { game.strikeoutResponsibleBatterID = original.strikeoutResponsibleBatterID ?? original.currentBatter.id }
            }
        }
        finalizeLatestEventSituation()
        if !game.isFinal, game.pendingDecision == nil,
           game.inning == original.inning, game.isTop == original.isTop,
           activeRules?.halfInningRunLimit != nil, remainingHalfRuns == 0,
           game.dismissedRunLimitHalf != currentHalfKey {
            game.pendingDecision = .runLimit
        }
        if !game.isFinal, game.halfEndedAwaitingDecision != true { ensureCurrentAppearance() }
        if isHistoryReplay { return }
        captureHistoryOperation(from: original)
        guard actionErrorMessage == nil else {
            game = original; games = actionOriginalGames; undoStack = actionUndo; redoStack = actionRedo
            nextEventBeforeSituation = original.situationSnapshot
            return
        }
        guard let id = activeGameID, let index = games.firstIndex(where: { $0.id == id }) else { return }
        if games[index].openingPitchRecorded == false,
           historyPendingCommand?.recordsOpeningPitch == true
            || game.historyJournal?.operations.contains(where: { $0.command.recordsOpeningPitch }) == true {
            games[index].openingPitchRecorded = true
        }
        if activeGameID == actionOriginalActiveID,
           original.homeTeam.id == game.awayTeam.id && original.awayTeam.id == game.homeTeam.id {
            if games[index].isObservation {
                let previous = games[index].lineup
                games[index].lineup = games[index].secondaryLineup ?? []
                games[index].secondaryLineup = previous
            } else if let teamID = games[index].ourTeamID {
                games[index].isHome = game.homeTeam.id == teamID
            }
        }
        games[index].state = game
        games[index].status = game.isFinal ? .completed : .ongoing
        games[index].updatedAt = Date()
        games[index].revision = (actionOriginalGames.first { $0.id == id }?.revision ?? 0) + 1
        if games[index].publicationState == "publishing" {
            games[index].pendingPublicationRevisions = [games[index].revision!]
        }
        let originalTeams = teams, originalOpponents = opponentTeams, originalArchived = archivedTeams
        let originalCurrentTeam = currentTeam
        let profilesChanged = activeGameID == actionOriginalActiveID && synchronizeChangedProfiles(from: original)
        do {
            try LocalBackup.validate(makeRosterSnapshot())
            if profilesChanged { try persistenceStore.replaceAll(with: makeRosterSnapshot()) }
            else { try persistenceStore.upsertGame(games[index]) }
            liveBroadcasts.requestSync()
        } catch {
            teams = originalTeams; opponentTeams = originalOpponents; archivedTeams = originalArchived
            currentTeam = originalCurrentTeam
            activeGameID = actionOriginalActiveID
            game = original; games = actionOriginalGames
            undoStack = actionUndo; redoStack = actionRedo
            nextEventBeforeSituation = original.situationSnapshot
            storageErrorMessage = "本次操作未保存，已恢复操作前局面：\(error.localizedDescription)"
            actionErrorMessage = storageErrorMessage
        }
    }

    /// Identity is stable; only edited profile fields propagate to the team's roster.
    /// Used by lineup save and undo/redo, in the same database transaction as the game.
    private func synchronizeChangedProfiles(from original: GameState) -> Bool {
        var changed = false
        for (before, after) in [(original.homeTeam, game.homeTeam), (original.awayTeam, game.awayTeam)] where before.id == after.id {
            let updates = after.players.filter { player in
                guard let old = before.players.first(where: { $0.id == player.id }) else { return false }
                return old.chineseName != player.chineseName || old.englishName != player.englishName
                    || old.numberTexts != player.numberTexts
            }
            guard !updates.isEmpty else { continue }
            func update(_ collection: inout [Team]) {
                guard let index = collection.firstIndex(where: { $0.id == after.id }) else { return }
                for player in updates {
                    guard let p = collection[index].players.firstIndex(where: { $0.id == player.id }) else { continue }
                    collection[index].players[p].chineseName = player.chineseName
                    collection[index].players[p].englishName = player.englishName
                    collection[index].players[p].numbers = player.numbers
                    collection[index].players[p].jerseyNumbers = player.jerseyNumbers
                    changed = true
                }
            }
            update(&teams); update(&opponentTeams); update(&archivedTeams)
        }
        if changed, let id = currentTeam?.id { currentTeam = teams.first { $0.id == id } }
        return changed
    }

    private func initializeAppearanceForOpenedGame() {
        beginAtomicAction()
        ensureCurrentAppearance()
        commitAtomicAction()
    }

    func ensureCurrentAppearance() {
        guard !game.isFinal, game.halfEndedAwaitingDecision != true, !game.battingOrderPlayers.isEmpty else { return }
        if let id = game.currentPlateAppearanceID,
           let index = game.plateAppearances?.firstIndex(where: { $0.id == id }),
           game.plateAppearances?[index].inning == game.inning,
           game.plateAppearances?[index].isTop == game.isTop {
            if game.plateAppearances?[index].batterIDs.last != game.currentBatter.id {
                game.plateAppearances?[index].batterIDs.append(game.currentBatter.id)
            }
            return
        }
        if game.currentPlateAppearanceID != nil { closeAppearance(completed: false) }
        let previous = game.isTop ? game.awayInterruptedAppearanceID : game.homeInterruptedAppearanceID
        let identity = PlateAppearanceIdentity(id: UUID(), inning: game.inning, isTop: game.isTop,
            batterIDs: [game.currentBatter.id], completed: false, interrupted: false, previousSegmentID: previous)
        game.plateAppearances = (game.plateAppearances ?? []) + [identity]
        game.currentPlateAppearanceID = identity.id
        game.plateAppearancePitchCount = 0
        if game.isTop { game.awayInterruptedAppearanceID = nil } else { game.homeInterruptedAppearanceID = nil }
    }

    private func closeAppearance(completed: Bool) {
        if let id = game.currentPlateAppearanceID,
           let index = game.plateAppearances?.firstIndex(where: { $0.id == id }) {
            game.plateAppearances?[index].completed = completed
            game.plateAppearances?[index].interrupted = !completed
            if !completed {
                if game.plateAppearances?[index].isTop == true { game.awayInterruptedAppearanceID = id } else { game.homeInterruptedAppearanceID = id }
            }
        }
        game.currentPlateAppearanceID = nil
        game.plateAppearancePitchCount = 0
    }

    private func migrateAppearanceIdentities() {
        guard game.plateAppearances == nil, var events = game.scoringEvents else { return }
        var identities: [PlateAppearanceIdentity] = []
        var current: UUID?
        for index in events.indices where isPlateAppearanceEvent(events[index]) {
            let event = events[index]
            guard let batter = plateAppearanceBatter(for: event) else { continue }
            if let last = identities.last, last.inning != event.inning || last.isTop != event.isTop {
                if !last.completed { identities[identities.count - 1].interrupted = true }
                current = nil
            }
            if current == nil {
                // Deterministic legacy seed, persisted once; future aggregation never assigns identity.
                current = event.id
                identities.append(PlateAppearanceIdentity(id: event.id, inning: event.inning, isTop: event.isTop,
                    batterIDs: [batter.id], completed: false, interrupted: false))
            }
            if identities[identities.count - 1].batterIDs.last != batter.id {
                identities[identities.count - 1].batterIDs.append(batter.id)
            }
            events[index].plateAppearanceID = current
            if eventEndsPlateAppearance(event) {
                identities[identities.count - 1].completed = true
                current = nil
            }
        }
        game.scoringEvents = events
        game.plateAppearances = identities
        if let last = identities.last, last.inning == game.inning, last.isTop == game.isTop, !last.completed, !game.isFinal {
            game.currentPlateAppearanceID = current
        }
    }

    private func checkCoachPitchLimit() {
        guard isCoachPitch, (game.plateAppearancePitchCount ?? 0) >= coachPitchLimit else { return }
        let batter = game.currentBatter
        updateBatter(batter.id) { $0.plateAppearances += 1; $0.atBats += 1 }
        addLog("\(batter.name)：规定投球数用尽，打者出局（不记三振）", category: .out,
            notation: "PITCH-LIMIT", primaryPlayerID: batter.id)
        advanceBatter()
        registerOuts(1)
    }

    private func endHalfWithoutUndo(reason: String) {
        closeAppearance(completed: false)
        addLog("\(reason)，本半局结束（实际 \(game.outs) 出局）", category: .game, notation: "HALF-END")
        let needsDecision = game.inning >= game.scheduledInnings && (!game.isTop || game.homeScore > game.awayScore)
        if needsDecision {
            game.pendingDecision = .regulationHalf
            game.halfEndedAwaitingDecision = true
        } else { switchHalfWithoutUndo() }
    }

    private func switchHalfWithoutUndo() {
        game.outs = 0; game.balls = 0; game.strikes = 0
        game.baseRunners = [:]; game.automaticRunnerIDs = []
        game.runnerPitcherIDs = nil; game.unearnedRunnerIDs = nil
        game.walkResponsiblePitcherID = nil; game.strikeoutResponsibleBatterID = nil
        game.pendingDecision = nil; game.halfEndedAwaitingDecision = nil
        if game.isTop { game.isTop = false }
        else { game.isTop = true; game.inning += 1; ensureInningCapacity() }
        game.currentPlateAppearanceID = nil
        game.plateAppearancePitchCount = 0
        ensureCurrentAppearance()
    }

    func endCurrentHalf() {
        guard canRecordAction else { return }
        beginAtomicAction()
        recordHistoryCommand(.endHalf)
        defer { commitAtomicAction() }
        pushUndo()
        endHalfWithoutUndo(reason: "记录员结束半局")
    }

    func resolveGameDecision(finish: Bool) {
        guard let decision = game.pendingDecision, !requiresDataRecovery else { return }
        beginAtomicAction()
        recordHistoryCommand(.decision(finish))
        defer { commitAtomicAction() }
        pushUndo()
        game.pendingDecision = nil
        switch decision {
        case .runLimit:
            game.dismissedRunLimitHalf = currentHalfKey
            if finish { endHalfWithoutUndo(reason: "达到半局得分上限") }
            else { addLog("达到得分上限，继续本半局，后续回本垒不再计分", notation: "CAP-CONTINUE") }
        case .regulationHalf:
            if finish { markGameFinal(reason: .regulation); addLog("确认结束比赛", notation: "END") }
            else { switchHalfWithoutUndo(); addLog("确认继续比赛", notation: "CONTINUE") }
        case .walkOff:
            game.dismissedWalkOffHalf = currentHalfKey
            if finish { markGameFinal(reason: .walkOff); addLog("确认再见结束比赛", notation: "END") }
            else { addLog("取得领先后继续本半局", notation: "CONTINUE") }
        }
    }

    func reopenGame(confirmedLegacySituation: Bool = false) -> Bool {
        guard game.isFinal, !requiresDataRecovery else { return false }
        guard game.canResumePrecisely == true || confirmedLegacySituation else {
            actionErrorMessage = "旧比赛缺少恢复阶段，请先核对局次、棒次及垒况，再确认恢复。"
            return false
        }
        beginAtomicAction()
        recordHistoryCommand(.reopen(confirmedLegacySituation))
        defer { commitAtomicAction() }
        pushUndo()
        game.isFinal = false; game.endedAt = nil; game.endReason = nil
        game.clockRunningSince = nil; game.pendingDecision = nil
        game.dismissedWalkOffHalf = currentHalfKey
        if game.halfEndedAwaitingDecision == true { switchHalfWithoutUndo() }
        addLog("比赛恢复为进行中，计时暂停", notation: "REOPEN")
        return true
    }

    private static var applicationDataDirectory: URL? {
        guard let applicationSupport = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { return nil }
        return applicationSupport
            .appendingPathComponent("BaseballMaster", isDirectory: true)
    }

    private static var defaultDatabaseURL: URL? {
        applicationDataDirectory?.appendingPathComponent("BaseballMaster.sqlite", isDirectory: false)
    }

    private static var defaultLegacyJSONURL: URL? {
        applicationDataDirectory?.appendingPathComponent("roster-data.json", isDirectory: false)
    }

    private static func loadLegacyRosterData(from url: URL) -> RosterSnapshot? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        do {
            let snapshot = try JSONDecoder().decode(RosterSnapshot.self, from: data)
            try LocalBackup.validate(snapshot)
            return snapshot
        } catch { return nil }
    }

    private static func archiveLegacyRosterData(at url: URL) {
        let archiveURL = url
            .deletingPathExtension()
            .appendingPathExtension("migrated.json")
        guard FileManager.default.fileExists(atPath: url.path),
              !FileManager.default.fileExists(atPath: archiveURL.path) else { return }
        try? FileManager.default.moveItem(at: url, to: archiveURL)
    }

    private func makeRosterSnapshot() -> RosterSnapshot {
        RosterSnapshot(
            teams: teams,
            opponentTeams: opponentTeams,
            currentTeamID: currentTeam?.id,
            seasons: seasons,
            playerGameRecords: playerGameRecords,
            games: games,
            archivedTeams: archivedTeams
        )
    }

    var backupDirectory: URL? {
        sourceDatabaseURL?.deletingLastPathComponent().appendingPathComponent("Backups", isDirectory: true)
    }

    var automaticBackupURLs: [URL] {
        guard let directory = backupDirectory else { return [] }
        return ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "bmbackup" && $0.lastPathComponent.hasPrefix("automatic-") }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    var upgradeBackupURLs: [URL] {
        guard let root = sourceDatabaseURL?.deletingLastPathComponent(),
              let files = FileManager.default.enumerator(at: root.appendingPathComponent("UpgradeArchives"), includingPropertiesForKeys: nil) else { return [] }
        return files.compactMap { $0 as? URL }.filter { $0.lastPathComponent == "verified.bmbackup" }.sorted { $0.path < $1.path }
    }

    func exportOriginalData() throws -> URL {
        guard let root = sourceDatabaseURL?.deletingLastPathComponent(),
              let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) else {
            throw LocalDataError.invalid("没有可导出的原始资料")
        }
        var contents: [String: Data] = [:]
        for case let file as URL in files {
            guard try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
            contents[String(file.path.dropFirst(root.path.count + 1))] = try Data(contentsOf: file)
        }
        let url = try ReportExportFile.url(name: "BaseballMaster-原始恢复资料", extension: "bmrecovery")
        let encoder = PropertyListEncoder(); encoder.outputFormat = .binary
        try encoder.encode(contents).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return url
    }

    func exportBackup() throws -> URL {
        guard !requiresDataRecovery else { throw LocalDataError.invalid("请先恢复数据库；当前没有可导出的完整数据。") }
        let backup = try LocalBackup(snapshot: makeRosterSnapshot())
        let url = try ReportExportFile.url(name: "BaseballMaster-完整备份", extension: "bmbackup")
        try backup.write(to: url)
        return url
    }

    func createAutomaticBackup() {
        guard !requiresDataRecovery, let directory = backupDirectory else { return }
        do {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMdd-HHmmss-SSS"
            let url = directory.appendingPathComponent("automatic-\(formatter.string(from: Date())).bmbackup")
            try LocalBackup(snapshot: makeRosterSnapshot()).write(to: url)
            for old in automaticBackupURLs.dropFirst(3) { try FileManager.default.removeItem(at: old) }
        } catch {
            storageErrorMessage = "自动备份未完成：\(error.localizedDescription)"
        }
    }

    func restoreBackup(_ backup: LocalBackup) throws {
        let snapshot = try backup.snapshot()
        if let sourceDatabaseURL {
            let directory = sourceDatabaseURL.deletingLastPathComponent()
            let stagingDirectory = directory.appendingPathComponent("RestoredStores/\(UUID().uuidString)", isDirectory: true)
            let stagingURL = stagingDirectory.appendingPathComponent("BaseballMaster.sqlite")
            let staging = try CoreDataRosterStore(storeURL: stagingURL, protectsUpgrade: false)
            try staging.replaceAll(with: snapshot)
            guard try staging.loadSnapshot() == snapshot else {
                throw LocalDataError.invalid("恢复校验失败，原数据未修改。")
            }
            try staging.close()
            let archive = directory.appendingPathComponent("Recovery/\(UUID().uuidString)", isDirectory: true)
            try persistenceStore.close()
            do {
                if let databaseURL, FileManager.default.fileExists(atPath: databaseURL.path) {
                    try CoreDataRosterStore.copyStoreFamily(databaseURL, to: archive)
                }
                let candidate = try CoreDataRosterStore(storeURL: stagingURL, protectsUpgrade: false)
                let relative = String(stagingURL.path.dropFirst(directory.path.count + 1))
                try JSONEncoder().encode(relative).write(to: directory.appendingPathComponent("active-store-v3.json"), options: .atomic)
                persistenceStore = candidate
                databaseURL = stagingURL
            } catch {
                persistenceStore = try CoreDataRosterStore(storeURL: nil)
                requiresDataRecovery = true
                storageErrorMessage = "恢复未完成，当前资料及原文件已保留，请重试。"
                throw error
            }
        } else {
            try persistenceStore.replaceAll(with: snapshot)
        }
        activeGameID = nil
        teams = snapshot.teams
        opponentTeams = snapshot.opponentTeams
        currentTeam = teams.first(where: { $0.id == snapshot.currentTeamID }) ?? teams.first
        archivedTeams = snapshot.archivedTeams
        seasons = snapshot.seasons
        playerGameRecords = snapshot.playerGameRecords
        games = snapshot.games.sorted { $0.updatedAt > $1.updatedAt }
        liveBroadcasts.detachAfterRestore()
        if let ongoing = games.first(where: { $0.status == .ongoing }) {
            game = ongoing.state
            activeGameID = ongoing.id
        } else {
            game = GameState(homeTeam: Self.makeFalcons(), awayTeam: Self.makeWaves())
        }
        undoStack.removeAll()
        redoStack.removeAll()
        nextEventBeforeSituation = game.situationSnapshot
        requiresDataRecovery = false
        storageErrorMessage = nil
        selectedTab = 0
        gameNavigationID = UUID()
        do { try migrateStoredAppearanceIdentities() }
        catch {
            requiresDataRecovery = true
            storageErrorMessage = "备份已保留，但打席资料升级未能保存。请重试恢复。"
            throw error
        }
        createAutomaticBackup()
    }

    private func persist(_ operation: () throws -> Void) {
        guard !requiresDataRecovery else { return }
        do {
            try operation()
        } catch {
            NSLog("Unable to save the local Core Data store: %@", String(describing: error))
            storageErrorMessage = "本地数据保存失败，请保留当前页面并稍后重试。"
        }
    }

    func advanceLiveProjectionRevision(for id: UUID, matching revision: Int) throws -> StoredGame {
        guard !requiresDataRecovery, let index = games.firstIndex(where: { $0.id == id }), (games[index].revision ?? 0) == revision else {
            throw LiveError.message("直播资料升级暂未保存，请重试。")
        }
        var next = games[index]
        next.revision = revision + 1
        try persistenceStore.upsertGame(next)
        games[index] = next
        return next
    }

    private func persistCurrentGameIfNeeded() {
        guard !isHistoryReplay, actionDepth == 0, !requiresDataRecovery else { return }
        guard let activeGameID,
              let index = games.firstIndex(where: { $0.id == activeGameID }) else { return }
        let original = games[index]
        games[index].state = game
        games[index].updatedAt = Date()
        games[index].status = game.isFinal ? .completed : .ongoing
        games[index].revision = (original.revision ?? 0) + 1
        do {
            try persistenceStore.upsertGame(games[index])
            liveBroadcasts.requestSync()
        } catch {
            games[index] = original
            storageErrorMessage = "本次修改未能保存，直播仍保留上次已保存的局面。"
        }
    }

    func prepareScorekeepingPreview() {
        game.awayRunsByInning[0] = 1
        game.homeRunsByInning[0] = 0
        game.inning = 2
        game.isTop = true
        game.awayHits = 2
        game.homeHits = 1
        game.awayBatterIndex = 3
        if game.awayTeam.players.count > 2 {
            game.baseRunners[.first] = game.awayTeam.players[2]
        }
        let first = game.awayTeam.players[0]
        var firstLine = BattingLine()
        firstLine.plateAppearances = 1
        firstLine.atBats = 1
        firstLine.hits = 1
        firstLine.runs = 1
        game.batting[first.id] = firstLine
        game.playLog = [
            PlayLogEntry(inning: 1, isTop: true, text: "陈昊：一垒安打（1B），到一垒"),
            PlayLogEntry(inning: 1, isTop: true, text: "周子墨：二垒安打（2B），陈昊得分"),
            PlayLogEntry(inning: 1, isTop: true, text: "林宇轩：挥棒三振出局（K）"),
            PlayLogEntry(inning: 1, isTop: true, text: "三出局，攻守交换"),
            PlayLogEntry(inning: 1, isTop: false, text: "赵一鸣：中外野接杀（F8）")
        ]
    }

    private static func makeWaves() -> Team {
        Team(name: "青岛海浪", shortName: "海浪", city: "青岛", players: [
            Player(chineseName: "陈昊", englishName: "Chen Hao", numbers: [12, 88], primaryPosition: .shortstop),
            Player(chineseName: "周子墨", englishName: "Zhou Zimo", numbers: [7], primaryPosition: .centerField),
            Player(chineseName: "林宇轩", englishName: "Lin Yuxuan", numbers: [18], primaryPosition: .catcher),
            Player(chineseName: "王星野", englishName: "Wang Xingye", numbers: [23], primaryPosition: .pitcher),
            Player(chineseName: "许嘉树", englishName: "Xu Jiashu", numbers: [5], primaryPosition: .thirdBase),
            Player(chineseName: "韩一川", englishName: "Han Yichuan", numbers: [9], primaryPosition: .leftField),
            Player(chineseName: "顾晨", englishName: "Gu Chen", numbers: [3], primaryPosition: .firstBase),
            Player(chineseName: "沈亦航", englishName: "Shen Yihang", numbers: [16], primaryPosition: .secondBase),
            Player(chineseName: "唐乐天", englishName: "Tang Letian", numbers: [21], primaryPosition: .rightField),
            Player(chineseName: "陆景然", englishName: "Lu Jingran", numbers: [2], primaryPosition: .catcher),
            Player(chineseName: "江沐阳", englishName: "Jiang Muyang", numbers: [10], primaryPosition: .centerField),
            Player(chineseName: "宋知远", englishName: "Song Zhiyuan", numbers: [30], primaryPosition: .pitcher)
        ])
    }

    private static func makeFalcons() -> Team {
        let surnames = ["赵一鸣", "高博文", "邵子谦", "何俊熙", "梁天佑", "彭奕辰", "罗凯", "杜明泽", "郑文轩"]
        let englishNames = ["Zhao Yiming", "Gao Bowen", "Shao Ziqian", "He Junxi", "Liang Tianyou", "Peng Yichen", "Luo Kai", "Du Mingze", "Zheng Wenxuan"]
        let positions = FieldPosition.allCases
        return Team(
            name: "北京飞鹰",
            shortName: "飞鹰",
            city: "北京",
            players: surnames.enumerated().map { index, name in
                Player(
                    chineseName: name,
                    englishName: englishNames[index],
                    numbers: [index + 1],
                    primaryPosition: positions[index]
                )
            }
        )
    }

    private static func makeRockets() -> Team {
        let names = ["李承泽", "苏亦凡", "谢文博", "郭子睿", "冯浩然", "蔡明轩", "蒋嘉豪", "袁景行", "程宇航"]
        let englishNames = ["Li Chengze", "Su Yifan", "Xie Wenbo", "Guo Zirui", "Feng Haoran", "Cai Mingxuan", "Jiang Jiahao", "Yuan Jingxing", "Cheng Yuhang"]
        return Team(
            name: "天津火箭",
            shortName: "火箭",
            city: "天津",
            players: names.enumerated().map { index, name in
                Player(
                    chineseName: name,
                    englishName: englishNames[index],
                    numbers: [index + 11],
                    primaryPosition: FieldPosition.allCases[index]
                )
            }
        )
    }

    private static func makeSeasons() -> [Season] {
        [
            Season(id: "2026-summer", name: "2026 夏季"),
            Season(id: "2025-autumn", name: "2025 秋季")
        ]
    }

}


#if DEBUG
// Opt-in simulator release audit. Excluded entirely from the distributed app.
// Uses the normal on-disk store so installing over an older app tests the actual upgrade path.
extension GameStore {
    func runUpgradeReleaseAuditIfRequested() {
        let arguments = ProcessInfo.processInfo.arguments
        let phase: String
        if arguments.contains("--upgrade-audit-continue") { phase = "continued" }
        else if arguments.contains("--upgrade-audit-restore") { phase = "restored" }
        else if arguments.contains("--upgrade-audit-export") { phase = "after" }
        else { return }
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        do {
            guard !requiresDataRecovery else { throw LocalDataError.invalid(storageErrorMessage ?? "Recovery required") }
            if phase == "continued" { recordPitch(.ball) }
            if phase == "restored" { try restoreBackup(LocalBackup.read(exportBackup())) }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
            try encoder.encode(makeRosterSnapshot()).write(to: documents.appendingPathComponent("UpgradeFixture-\(phase).json"), options: .atomic)
            let settings: [String: String] = ["sentinel": UserDefaults.standard.string(forKey: "upgradeAuditSentinel") ?? "", "appearance": UserDefaults.standard.string(forKey: "appAppearance") ?? ""]
            try encoder.encode(settings).write(to: documents.appendingPathComponent("UpgradeFixture-settings.json"), options: .atomic)
            _ = try exportBackup()
        } catch {
            try? Data(error.localizedDescription.utf8).write(to: documents.appendingPathComponent("UpgradeFixture-error.txt"))
        }
    }
}
#endif
