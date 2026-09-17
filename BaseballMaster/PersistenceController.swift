import CoreData
import Foundation

struct RosterSnapshot: Codable {
    let teams: [Team]
    let opponentTeams: [Team]
    let currentTeamID: UUID
    let seasons: [Season]
    let playerGameRecords: [PlayerGameRecord]
    let games: [StoredGame]

    init(
        teams: [Team],
        opponentTeams: [Team] = [],
        currentTeamID: UUID,
        seasons: [Season],
        playerGameRecords: [PlayerGameRecord],
        games: [StoredGame] = []
    ) {
        self.teams = teams
        self.opponentTeams = opponentTeams
        self.currentTeamID = currentTeamID
        self.seasons = seasons
        self.playerGameRecords = playerGameRecords
        self.games = games
    }

    private enum CodingKeys: String, CodingKey {
        case teams, opponentTeams, currentTeamID, seasons, playerGameRecords, games
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        teams = try container.decode([Team].self, forKey: .teams)
        opponentTeams = try container.decodeIfPresent([Team].self, forKey: .opponentTeams) ?? []
        currentTeamID = try container.decode(UUID.self, forKey: .currentTeamID)
        seasons = try container.decode([Season].self, forKey: .seasons)
        playerGameRecords = try container.decode([PlayerGameRecord].self, forKey: .playerGameRecords)
        games = try container.decodeIfPresent([StoredGame].self, forKey: .games) ?? []
    }
}

@MainActor
final class CoreDataRosterStore {
    static let schemaVersion = 2
    private(set) var loadedSchemaVersion: Int?
    private(set) var migratedLegacySchema = false

    private enum Entity {
        static let metadata = "RosterMetadata"
        static let team = "RosterTeam"
        static let player = "RosterPlayer"
        static let season = "RosterSeason"
        static let gameRecord = "RosterPlayerGameRecord"
        static let game = "StoredGame"
    }

    private let container: NSPersistentContainer
    private var context: NSManagedObjectContext { container.viewContext }

    init(storeURL: URL?) throws {
        let managedObjectModel = Self.makeManagedObjectModel()
        if let storeURL {
            try FileManager.default.createDirectory(
                at: storeURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            migratedLegacySchema = try Self.migrateV1StoreIfNeeded(at: storeURL, destinationModel: managedObjectModel)
        }

        container = NSPersistentContainer(
            name: "BaseballMaster",
            managedObjectModel: managedObjectModel
        )

        let description: NSPersistentStoreDescription
        if let storeURL {
            description = NSPersistentStoreDescription(url: storeURL)
            description.type = NSSQLiteStoreType
            description.shouldMigrateStoreAutomatically = true
            description.shouldInferMappingModelAutomatically = true
        } else {
            description = NSPersistentStoreDescription()
            description.type = NSInMemoryStoreType
        }
        description.setOption(true as NSNumber, forKey: NSMigratePersistentStoresAutomaticallyOption)
        description.setOption(true as NSNumber, forKey: NSInferMappingModelAutomaticallyOption)
        container.persistentStoreDescriptions = [description]

        var loadingError: Error?
        container.loadPersistentStores { _, error in
            loadingError = error
        }
        if let loadingError { throw loadingError }

        context.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        context.undoManager = nil
    }

    func loadSnapshot() throws -> RosterSnapshot? {
        let teamObjects = try fetch(Entity.team, sortedBy: "sortOrder")
        if teamObjects.isEmpty {
            for entity in [Entity.player, Entity.game, Entity.gameRecord, Entity.season, Entity.metadata] {
                if try !fetch(entity).isEmpty { throw LocalDataError.invalid("数据库缺少球队但仍有历史数据，原文件已保留。") }
            }
            return nil
        }

        let playerObjects = try fetch(Entity.player, sortedBy: "sortOrder")
        let playersByTeam = Dictionary(grouping: playerObjects) { object in
            object.value(forKey: "rosterTeamID") as? UUID
        }

        let decodedTeams = teamObjects.compactMap { object -> (team: Team, isOpponent: Bool)? in
            guard let id = object.value(forKey: "id") as? UUID,
                  let name = object.value(forKey: "name") as? String,
                  let shortName = object.value(forKey: "shortName") as? String,
                  let city = object.value(forKey: "city") as? String else { return nil }

            let players = (playersByTeam[id] ?? []).compactMap(Self.player(from:))
            let team = Team(id: id, name: name, shortName: shortName, city: city, players: players)
            let isOpponent = (object.value(forKey: "isOpponent") as? NSNumber)?.boolValue ?? false
            return (team, isOpponent)
        }
        let teams = decodedTeams.filter { !$0.isOpponent }.map(\.team)
        let opponentTeams = decodedTeams.filter { $0.isOpponent }.map(\.team)
        guard !teams.isEmpty else { throw LocalDataError.invalid("数据库缺少本队，原文件已保留。") }

        let seasonObjects = try fetch(Entity.season, sortedBy: "sortOrder")
        let seasons = seasonObjects.compactMap { object -> Season? in
            guard let id = object.value(forKey: "id") as? String,
                  let name = object.value(forKey: "name") as? String else { return nil }
            return Season(id: id, name: name)
        }

        let recordObjects = try fetch(
            Entity.gameRecord,
            sortDescriptors: [NSSortDescriptor(key: "date", ascending: false)]
        )
        let records = recordObjects.compactMap(Self.gameRecord(from:))
        guard seasons.count == seasonObjects.count, records.count == recordObjects.count else {
            throw LocalDataError.invalid("赛季或个人统计记录不完整，原数据库已保留。")
        }

        let metadata = try fetch(Entity.metadata).first
        let currentTeamID = metadata?.value(forKey: "currentTeamID") as? UUID ?? teams[0].id
        loadedSchemaVersion = metadata?.value(forKey: "schemaVersion") as? Int
        if let version = metadata?.value(forKey: "schemaVersion") as? Int, version > Self.schemaVersion {
            throw LocalDataError.invalid("此数据库由较新版本创建，请更新 App。")
        }
        let games = try fetch(
            Entity.game,
            sortDescriptors: [NSSortDescriptor(key: "updatedAt", ascending: false)]
        ).map { object -> StoredGame in
            guard let payload = object.value(forKey: "payloadData") as? Data else {
                throw LocalDataError.invalid("比赛记录缺少内容，原数据库已保留。")
            }
            return try JSONDecoder().decode(StoredGame.self, from: payload)
        }
        guard decodedTeams.count == teamObjects.count,
              decodedTeams.flatMap({ $0.team.players }).count == playerObjects.count else {
            throw LocalDataError.invalid("名单记录不完整，原数据库已保留。")
        }
        let snapshot = RosterSnapshot(
            teams: teams,
            opponentTeams: opponentTeams,
            currentTeamID: currentTeamID,
            seasons: seasons,
            playerGameRecords: records,
            games: games
        )
        try LocalBackup.validate(snapshot)
        return snapshot
    }

    func close() throws {
        for store in container.persistentStoreCoordinator.persistentStores {
            try container.persistentStoreCoordinator.remove(store)
        }
    }

    static func replaceDatabase(at destination: URL, from source: URL) throws {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: makeManagedObjectModel())
        try coordinator.replacePersistentStore(at: destination, destinationOptions: nil,
            withPersistentStoreFrom: source, sourceOptions: nil, ofType: NSSQLiteStoreType)
    }

    func replaceAll(with snapshot: RosterSnapshot) throws {
        for entityName in [Entity.game, Entity.gameRecord, Entity.player, Entity.team, Entity.season, Entity.metadata] {
            for object in try fetch(entityName) {
                context.delete(object)
            }
        }

        let metadata = NSEntityDescription.insertNewObject(forEntityName: Entity.metadata, into: context)
        metadata.setValue("main", forKey: "id")
        metadata.setValue(snapshot.currentTeamID, forKey: "currentTeamID")
        metadata.setValue(Int32(Self.schemaVersion), forKey: "schemaVersion")

        for (teamIndex, team) in snapshot.teams.enumerated() {
            insertTeam(team, sortOrder: teamIndex, isOpponent: false)
            for (playerIndex, player) in team.players.enumerated() {
                insertPlayer(player, rosterTeamID: team.id, sortOrder: playerIndex)
            }
        }
        for (teamIndex, team) in snapshot.opponentTeams.enumerated() {
            insertTeam(team, sortOrder: teamIndex, isOpponent: true)
            for (playerIndex, player) in team.players.enumerated() {
                insertPlayer(player, rosterTeamID: team.id, sortOrder: playerIndex)
            }
        }
        for (index, season) in snapshot.seasons.enumerated() {
            let object = NSEntityDescription.insertNewObject(forEntityName: Entity.season, into: context)
            object.setValue(season.id, forKey: "id")
            object.setValue(season.name, forKey: "name")
            object.setValue(Int32(index), forKey: "sortOrder")
        }
        for record in snapshot.playerGameRecords {
            insertGameRecord(record)
        }
        for game in snapshot.games {
            insertGame(game)
        }
        try save()
    }

    func upsertTeam(_ team: Team, sortOrder: Int, isOpponent: Bool = false) throws {
        let object = try object(withID: team.id, in: Entity.team)
            ?? NSEntityDescription.insertNewObject(forEntityName: Entity.team, into: context)
        apply(team, to: object, sortOrder: sortOrder, isOpponent: isOpponent)
        try save()
    }

    func upsertGame(_ game: StoredGame) throws {
        let object = try object(withID: game.id, in: Entity.game)
            ?? NSEntityDescription.insertNewObject(forEntityName: Entity.game, into: context)
        apply(game, to: object)
        try save()
    }

    func deleteGame(id: UUID) throws {
        if let game = try object(withID: id, in: Entity.game) {
            context.delete(game)
        }
        try save()
    }

    func deleteTeam(id: UUID, playerIDs: Set<UUID>, currentTeamID: UUID) throws {
        if let team = try object(withID: id, in: Entity.team) {
            context.delete(team)
        }
        for player in try objects(withIDs: playerIDs, in: Entity.player) {
            context.delete(player)
        }
        for record in try objects(matching: NSPredicate(format: "playerID IN %@", Array(playerIDs)), in: Entity.gameRecord) {
            context.delete(record)
        }
        try setCurrentTeamID(currentTeamID, shouldSave: false)
        try save()
    }

    func setCurrentTeamID(_ id: UUID) throws {
        try setCurrentTeamID(id, shouldSave: true)
    }

    func upsertPlayer(_ player: Player, rosterTeamID: UUID, sortOrder: Int) throws {
        let object = try object(withID: player.id, in: Entity.player)
            ?? NSEntityDescription.insertNewObject(forEntityName: Entity.player, into: context)
        apply(player, rosterTeamID: rosterTeamID, to: object, sortOrder: sortOrder)
        try save()
    }

    func deletePlayer(id: UUID) throws {
        if let player = try object(withID: id, in: Entity.player) {
            context.delete(player)
        }
        for record in try objects(matching: NSPredicate(format: "playerID == %@", id as CVarArg), in: Entity.gameRecord) {
            context.delete(record)
        }
        try save()
    }

    private func setCurrentTeamID(_ id: UUID, shouldSave: Bool) throws {
        let metadata = try fetch(Entity.metadata).first
            ?? NSEntityDescription.insertNewObject(forEntityName: Entity.metadata, into: context)
        metadata.setValue("main", forKey: "id")
        metadata.setValue(id, forKey: "currentTeamID")
        metadata.setValue(Int32(Self.schemaVersion), forKey: "schemaVersion")
        if shouldSave { try save() }
    }

    private func insertTeam(_ team: Team, sortOrder: Int, isOpponent: Bool) {
        let object = NSEntityDescription.insertNewObject(forEntityName: Entity.team, into: context)
        apply(team, to: object, sortOrder: sortOrder, isOpponent: isOpponent)
    }

    private func apply(_ team: Team, to object: NSManagedObject, sortOrder: Int, isOpponent: Bool) {
        object.setValue(team.id, forKey: "id")
        object.setValue(team.name, forKey: "name")
        object.setValue(team.shortName, forKey: "shortName")
        object.setValue(team.city, forKey: "city")
        object.setValue(Int32(sortOrder), forKey: "sortOrder")
        object.setValue(isOpponent, forKey: "isOpponent")
    }

    private func insertPlayer(_ player: Player, rosterTeamID: UUID, sortOrder: Int) {
        let object = NSEntityDescription.insertNewObject(forEntityName: Entity.player, into: context)
        apply(player, rosterTeamID: rosterTeamID, to: object, sortOrder: sortOrder)
    }

    private func apply(_ player: Player, rosterTeamID: UUID, to object: NSManagedObject, sortOrder: Int) {
        object.setValue(player.id, forKey: "id")
        object.setValue(rosterTeamID, forKey: "rosterTeamID")
        object.setValue(player.chineseName, forKey: "chineseName")
        object.setValue(player.englishName, forKey: "englishName")
        object.setValue(try? JSONEncoder().encode(player.numbers), forKey: "numbersData")
        object.setValue(Int16(player.primaryPosition.rawValue), forKey: "primaryPosition")
        object.setValue(Int32(sortOrder), forKey: "sortOrder")
    }

    private func insertGameRecord(_ record: PlayerGameRecord) {
        let object = NSEntityDescription.insertNewObject(forEntityName: Entity.gameRecord, into: context)
        object.setValue(record.id, forKey: "id")
        object.setValue(record.playerID, forKey: "playerID")
        object.setValue(record.seasonID, forKey: "seasonID")
        object.setValue(record.date, forKey: "date")
        object.setValue(record.opponent, forKey: "opponent")
        object.setValue(record.result, forKey: "result")
        object.setValue(Int32(record.batting.plateAppearances), forKey: "plateAppearances")
        object.setValue(Int32(record.batting.atBats), forKey: "atBats")
        object.setValue(Int32(record.batting.runs), forKey: "runs")
        object.setValue(Int32(record.batting.hits), forKey: "hits")
        object.setValue(Int32(record.batting.doubles), forKey: "doubles")
        object.setValue(Int32(record.batting.triples), forKey: "triples")
        object.setValue(Int32(record.batting.homeRuns), forKey: "homeRuns")
        object.setValue(Int32(record.batting.runsBattedIn), forKey: "runsBattedIn")
        object.setValue(Int32(record.batting.walks), forKey: "walks")
        object.setValue(Int32(record.batting.hitByPitch), forKey: "hitByPitch")
        object.setValue(Int32(record.batting.strikeouts), forKey: "strikeouts")
        object.setValue(Int32(record.batting.stolenBases), forKey: "stolenBases")
        object.setValue(Int32(record.batting.caughtStealing), forKey: "caughtStealing")
        object.setValue(Int32(record.batting.sacrifices), forKey: "sacrifices")
    }

    private func insertGame(_ game: StoredGame) {
        let object = NSEntityDescription.insertNewObject(forEntityName: Entity.game, into: context)
        apply(game, to: object)
    }

    private func apply(_ game: StoredGame, to object: NSManagedObject) {
        object.setValue(game.id, forKey: "id")
        object.setValue(game.updatedAt, forKey: "updatedAt")
        object.setValue(Int16(game.status.rawValue), forKey: "status")
        object.setValue(try? JSONEncoder().encode(game), forKey: "payloadData")
    }

    private static func player(from object: NSManagedObject) -> Player? {
        guard let id = object.value(forKey: "id") as? UUID,
              let chineseName = object.value(forKey: "chineseName") as? String,
              let englishName = object.value(forKey: "englishName") as? String else { return nil }
        let numbersData = object.value(forKey: "numbersData") as? Data
        let numbers = numbersData.flatMap { try? JSONDecoder().decode([Int].self, from: $0) } ?? []
        let positionRawValue = (object.value(forKey: "primaryPosition") as? NSNumber)?.intValue ?? 1
        return Player(
            id: id,
            chineseName: chineseName,
            englishName: englishName,
            numbers: numbers,
            primaryPosition: FieldPosition(rawValue: positionRawValue) ?? .pitcher
        )
    }

    private static func gameRecord(from object: NSManagedObject) -> PlayerGameRecord? {
        guard let id = object.value(forKey: "id") as? UUID,
              let playerID = object.value(forKey: "playerID") as? UUID,
              let seasonID = object.value(forKey: "seasonID") as? String,
              let date = object.value(forKey: "date") as? Date,
              let opponent = object.value(forKey: "opponent") as? String,
              let result = object.value(forKey: "result") as? String else { return nil }

        func integer(_ key: String) -> Int {
            (object.value(forKey: key) as? NSNumber)?.intValue ?? 0
        }
        var batting = BattingLine()
        batting.plateAppearances = integer("plateAppearances")
        batting.atBats = integer("atBats")
        batting.runs = integer("runs")
        batting.hits = integer("hits")
        batting.doubles = integer("doubles")
        batting.triples = integer("triples")
        batting.homeRuns = integer("homeRuns")
        batting.runsBattedIn = integer("runsBattedIn")
        batting.walks = integer("walks")
        batting.hitByPitch = integer("hitByPitch")
        batting.strikeouts = integer("strikeouts")
        batting.stolenBases = integer("stolenBases")
        batting.caughtStealing = integer("caughtStealing")
        batting.sacrifices = integer("sacrifices")
        return PlayerGameRecord(
            id: id,
            playerID: playerID,
            seasonID: seasonID,
            date: date,
            opponent: opponent,
            result: result,
            batting: batting
        )
    }

    private func save() throws {
        guard context.hasChanges else { return }
        do {
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    private func object(withID id: UUID, in entityName: String) throws -> NSManagedObject? {
        try objects(matching: NSPredicate(format: "id == %@", id as CVarArg), in: entityName).first
    }

    private func objects(withIDs ids: Set<UUID>, in entityName: String) throws -> [NSManagedObject] {
        guard !ids.isEmpty else { return [] }
        return try objects(matching: NSPredicate(format: "id IN %@", Array(ids)), in: entityName)
    }

    private func objects(matching predicate: NSPredicate, in entityName: String) throws -> [NSManagedObject] {
        let request = NSFetchRequest<NSManagedObject>(entityName: entityName)
        request.predicate = predicate
        return try context.fetch(request)
    }

    private func fetch(_ entityName: String, sortedBy key: String) throws -> [NSManagedObject] {
        try fetch(entityName, sortDescriptors: [NSSortDescriptor(key: key, ascending: true)])
    }

    private func fetch(
        _ entityName: String,
        sortDescriptors: [NSSortDescriptor] = []
    ) throws -> [NSManagedObject] {
        let request = NSFetchRequest<NSManagedObject>(entityName: entityName)
        request.sortDescriptors = sortDescriptors
        // Core Data may log and omit a damaged SQLite row instead of throwing.
        // Compare with the store's count before treating a partial read as valid.
        let expected = try context.count(for: request)
        let objects = try context.fetch(request)
        guard objects.count == expected else {
            throw LocalDataError.invalid("数据库存在无法读取的记录，原文件已保留。")
        }
        return objects
    }

    private static func makeManagedObjectModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()
        model.versionIdentifiers = ["RosterSchemaV2"]

        let metadata = entity(Entity.metadata, attributes: [
            attribute("id", .stringAttributeType),
            attribute("currentTeamID", .UUIDAttributeType),
            attribute("schemaVersion", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let team = entity(Entity.team, attributes: [
            attribute("id", .UUIDAttributeType),
            attribute("name", .stringAttributeType),
            attribute("shortName", .stringAttributeType),
            attribute("city", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType),
            attribute("isOpponent", .booleanAttributeType, defaultValue: false)
        ], uniquenessConstraints: [["id"]])

        let player = entity(Entity.player, attributes: [
            attribute("id", .UUIDAttributeType),
            attribute("rosterTeamID", .UUIDAttributeType),
            attribute("chineseName", .stringAttributeType),
            attribute("englishName", .stringAttributeType),
            attribute("numbersData", .binaryDataAttributeType, allowsExternalStorage: false),
            attribute("primaryPosition", .integer16AttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let season = entity(Entity.season, attributes: [
            attribute("id", .stringAttributeType),
            attribute("name", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let recordAttributes: [NSAttributeDescription] = [
            attribute("id", .UUIDAttributeType),
            attribute("playerID", .UUIDAttributeType),
            attribute("seasonID", .stringAttributeType),
            attribute("date", .dateAttributeType),
            attribute("opponent", .stringAttributeType),
            attribute("result", .stringAttributeType),
            attribute("plateAppearances", .integer32AttributeType),
            attribute("atBats", .integer32AttributeType),
            attribute("runs", .integer32AttributeType),
            attribute("hits", .integer32AttributeType),
            attribute("doubles", .integer32AttributeType),
            attribute("triples", .integer32AttributeType),
            attribute("homeRuns", .integer32AttributeType),
            attribute("runsBattedIn", .integer32AttributeType),
            attribute("walks", .integer32AttributeType),
            attribute("hitByPitch", .integer32AttributeType),
            attribute("strikeouts", .integer32AttributeType),
            attribute("stolenBases", .integer32AttributeType),
            attribute("caughtStealing", .integer32AttributeType),
            attribute("sacrifices", .integer32AttributeType)
        ]
        let gameRecord = entity(
            Entity.gameRecord,
            attributes: recordAttributes,
            uniquenessConstraints: [["id"]]
        )

        let game = entity(Entity.game, attributes: [
            attribute("id", .UUIDAttributeType),
            attribute("updatedAt", .dateAttributeType),
            attribute("status", .integer16AttributeType),
            attribute("payloadData", .binaryDataAttributeType, allowsExternalStorage: true)
        ], uniquenessConstraints: [["id"]])

        model.entities = [metadata, team, player, season, gameRecord, game]
        return model
    }

    private static func migrateV1StoreIfNeeded(
        at storeURL: URL,
        destinationModel: NSManagedObjectModel
    ) throws -> Bool {
        guard FileManager.default.fileExists(atPath: storeURL.path) else { return false }
        let metadata = try NSPersistentStoreCoordinator.metadataForPersistentStore(
            ofType: NSSQLiteStoreType,
            at: storeURL,
            options: nil
        )
        if destinationModel.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) {
            return false
        }

        let sourceModel = makeV1ManagedObjectModel()
        guard sourceModel.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) else {
            throw NSError(
                domain: "BaseballMaster.CoreDataMigration",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "无法识别现有本地数据库版本"]
            )
        }

        let mapping = try NSMappingModel.inferredMappingModel(
            forSourceModel: sourceModel,
            destinationModel: destinationModel
        )
        let migrationManager = NSMigrationManager(sourceModel: sourceModel, destinationModel: destinationModel)
        let temporaryURL = storeURL.deletingLastPathComponent()
            .appendingPathComponent("BaseballMaster-migration-\(UUID().uuidString).sqlite")
        do {
            try migrationManager.migrateStore(
                from: storeURL,
                sourceType: NSSQLiteStoreType,
                options: nil,
                with: mapping,
                toDestinationURL: temporaryURL,
                destinationType: NSSQLiteStoreType,
                destinationOptions: nil
            )
            let coordinator = NSPersistentStoreCoordinator(managedObjectModel: destinationModel)
            try coordinator.replacePersistentStore(
                at: storeURL,
                destinationOptions: nil,
                withPersistentStoreFrom: temporaryURL,
                sourceOptions: nil,
                ofType: NSSQLiteStoreType
            )
        } catch {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw error
        }
        try? FileManager.default.removeItem(at: temporaryURL)
        return true
    }

    private static func makeV1ManagedObjectModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()
        model.versionIdentifiers = ["RosterSchemaV1"]

        let metadata = entity(Entity.metadata, attributes: [
            attribute("id", .stringAttributeType),
            attribute("currentTeamID", .UUIDAttributeType),
            attribute("schemaVersion", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let team = entity(Entity.team, attributes: [
            attribute("id", .UUIDAttributeType),
            attribute("name", .stringAttributeType),
            attribute("shortName", .stringAttributeType),
            attribute("city", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let player = entity(Entity.player, attributes: [
            attribute("id", .UUIDAttributeType),
            attribute("rosterTeamID", .UUIDAttributeType),
            attribute("chineseName", .stringAttributeType),
            attribute("englishName", .stringAttributeType),
            attribute("numbersData", .binaryDataAttributeType, allowsExternalStorage: false),
            attribute("primaryPosition", .integer16AttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let season = entity(Entity.season, attributes: [
            attribute("id", .stringAttributeType),
            attribute("name", .stringAttributeType),
            attribute("sortOrder", .integer32AttributeType)
        ], uniquenessConstraints: [["id"]])

        let recordNames = [
            "plateAppearances", "atBats", "runs", "hits", "doubles", "triples",
            "homeRuns", "runsBattedIn", "walks", "hitByPitch", "strikeouts",
            "stolenBases", "caughtStealing", "sacrifices"
        ]
        var recordAttributes = [
            attribute("id", .UUIDAttributeType),
            attribute("playerID", .UUIDAttributeType),
            attribute("seasonID", .stringAttributeType),
            attribute("date", .dateAttributeType),
            attribute("opponent", .stringAttributeType),
            attribute("result", .stringAttributeType)
        ]
        recordAttributes.append(contentsOf: recordNames.map { attribute($0, .integer32AttributeType) })
        let gameRecord = entity(Entity.gameRecord, attributes: recordAttributes, uniquenessConstraints: [["id"]])

        model.entities = [metadata, team, player, season, gameRecord]
        return model
    }

    private static func entity(
        _ name: String,
        attributes: [NSAttributeDescription],
        uniquenessConstraints: [[String]]
    ) -> NSEntityDescription {
        let entity = NSEntityDescription()
        entity.name = name
        entity.managedObjectClassName = NSStringFromClass(NSManagedObject.self)
        entity.properties = attributes
        entity.uniquenessConstraints = uniquenessConstraints
        return entity
    }

    private static func attribute(
        _ name: String,
        _ type: NSAttributeType,
        allowsExternalStorage: Bool = false,
        defaultValue: Any? = nil
    ) -> NSAttributeDescription {
        let attribute = NSAttributeDescription()
        attribute.name = name
        attribute.attributeType = type
        attribute.isOptional = false
        attribute.allowsExternalBinaryDataStorage = allowsExternalStorage
        attribute.defaultValue = defaultValue
        return attribute
    }
}
