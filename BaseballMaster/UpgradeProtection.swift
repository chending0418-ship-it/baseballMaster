import CoreData
import Foundation
import CryptoKit

/// Upgrade operates on a separate store and commits only an atomic pointer.
/// The original SQLite family is never replaced, even across process termination.
@MainActor
extension CoreDataRosterStore {
    enum UpgradeStage: String { case copied, migrated, validated, beforeActivation }
    static var upgradeFault: ((UpgradeStage) throws -> Void)?

    static func protectAndMigrate(at source: URL) throws -> URL {
        let fm = FileManager.default
        let root = source.deletingLastPathComponent()
        let pointer = root.appendingPathComponent("active-store-v3.json")
        if fm.fileExists(atPath: pointer.path) {
            let relative = try JSONDecoder().decode(String.self, from: Data(contentsOf: pointer))
            guard !relative.contains(".."), !relative.hasPrefix("/") else {
                throw LocalDataError.invalid("升级数据库路径无效，原资料已保留。")
            }
            let active = root.appendingPathComponent(relative)
            guard fm.fileExists(atPath: active.path) else {
                throw LocalDataError.invalid("升级数据库暂不可用，未回退覆盖原记录。")
            }
            return active
        }
        guard fm.fileExists(atPath: source.path) else { return source }
        // This is run before attaching the app's only writable coordinator.
        // Copy the entire quiescent family, including uncheckpointed WAL data.
        let archive = root.appendingPathComponent("UpgradeArchives/\(UUID().uuidString)", isDirectory: true)
        let original = archive.appendingPathComponent("original", isDirectory: true)
        let working = archive.appendingPathComponent("working", isDirectory: true)
        try copyStoreFamily(source, to: original)
        if let domain = Bundle.main.bundleIdentifier,
           let settings = UserDefaults.standard.persistentDomain(forName: domain) {
            let data = try PropertyListSerialization.data(fromPropertyList: settings, format: .binary, options: 0)
            try data.write(to: archive.appendingPathComponent("settings.plist"), options: .atomic)
        }
        try upgradeFault?(.copied)
        let savedSource = original.appendingPathComponent(source.lastPathComponent)
        let metadata = try NSPersistentStoreCoordinator.metadataForPersistentStore(ofType: NSSQLiteStoreType, at: savedSource)
        let models = [makeManagedObjectModel(), makeManagedObjectModel(version: 2), makeV1ManagedObjectModel()]
        guard let oldModel = models.first(where: { $0.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) }) else {
            throw LocalDataError.invalid("不支持此数据库版本，已保留升级前副本。")
        }
        if makeManagedObjectModel().isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) {
            // Already current: no need to migrate or rotate a successful archive.
            try fm.removeItem(at: archive)
            return source
        }
        let originalRows = try rows(at: savedSource, model: oldModel)
        try copyStoreFamily(savedSource, to: working)
        let target = working.appendingPathComponent(source.lastPathComponent)
        let candidate = try CoreDataRosterStore(storeURL: target, protectsUpgrade: false)
        try upgradeFault?(.migrated)
        try candidate.close()
        let migratedRows = try rows(at: target, model: makeManagedObjectModel(), fieldsFrom: oldModel)
        guard originalRows.isEqual(migratedRows) else {
            throw LocalDataError.invalid("升级前后内容不一致，原库未切换。")
        }
        // Keep a reviewable manifest without duplicating player names or statistics.
        let auditEntities = (originalRows as? [String: NSDictionary] ?? [:]).map { name, records in
            ["entity": name, "count": records.count,
             "recordIDs": records.allKeys.compactMap { $0 as? String }.sorted(),
             "comparedFields": oldModel.entitiesByName[name]?.attributesByName.keys.sorted() ?? []] as [String: Any]
        }.sorted { ($0["entity"] as? String ?? "") < ($1["entity"] as? String ?? "") }
        let audit: [String: Any] = ["sourceVersion": oldModel === models[2] ? 1 : 2,
            "targetVersion": schemaVersion, "allOriginalFieldsEqual": true, "entities": auditEntities]
        try JSONSerialization.data(withJSONObject: audit, options: [.prettyPrinted, .sortedKeys])
            .write(to: archive.appendingPathComponent("migration-audit.json"), options: .atomic)
        let checked = try CoreDataRosterStore(storeURL: target, protectsUpgrade: false)
        guard let snapshot = try checked.loadSnapshot() else {
            throw LocalDataError.invalid("升级结果为空，原库未切换。")
        }
        try checked.replaceAll(with: snapshot)
        guard try checked.loadSnapshot() == snapshot else {
            throw LocalDataError.invalid("升级回读校验失败，原库未切换。")
        }
        try checked.close()
        try upgradeFault?(.validated)
        try LocalBackup(snapshot: snapshot).write(to: archive.appendingPathComponent("verified.bmbackup"))
        let relative = String(target.path.dropFirst(root.path.count + 1))
        try upgradeFault?(.beforeActivation)
        try JSONEncoder().encode(relative).write(to: pointer, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return target
    }

    static func copyStoreFamily(_ source: URL, to directory: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let stem = source.deletingPathExtension().lastPathComponent
        let items = try fm.contentsOfDirectory(at: source.deletingLastPathComponent(), includingPropertiesForKeys: nil)
        var hashes: [String: String] = [:]
        for file in items where file.lastPathComponent == source.lastPathComponent
            || file.lastPathComponent == source.lastPathComponent + "-wal"
            || file.lastPathComponent == source.lastPathComponent + "-shm"
            || file.lastPathComponent == ".\(stem)_SUPPORT" {
            let destination = directory.appendingPathComponent(file.lastPathComponent)
            try fm.copyItem(at: file, to: destination)
            var isDirectory: ObjCBool = false
            fm.fileExists(atPath: file.path, isDirectory: &isDirectory)
            if isDirectory.boolValue {
                guard let children = fm.enumerator(at: file, includingPropertiesForKeys: [.isRegularFileKey]) else {
                    throw LocalDataError.invalid("无法检查外部数据附件")
                }
                for case let child as URL in children {
                    guard try child.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
                    let relative = String(child.path.dropFirst(file.path.count + 1))
                    let before = try Data(contentsOf: child)
                    let after = try Data(contentsOf: destination.appendingPathComponent(relative))
                    guard before == after else { throw LocalDataError.invalid("外部数据附件校验失败") }
                    hashes[file.lastPathComponent + "/" + relative] = SHA256.hash(data: before).map { String(format: "%02x", $0) }.joined()
                }
            } else {
                let before = try Data(contentsOf: file)
                let after = try Data(contentsOf: destination)
                guard before == after else { throw LocalDataError.invalid("升级前副本校验失败") }
                hashes[file.lastPathComponent] = SHA256.hash(data: before).map { String(format: "%02x", $0) }.joined()
            }
        }
        guard hashes[source.lastPathComponent] != nil else { throw LocalDataError.invalid("原数据库缺失") }
        try JSONEncoder().encode(hashes).write(to: directory.appendingPathComponent("checksums.json"), options: .atomic)
    }

    private static func rows(at url: URL, model: NSManagedObjectModel, fieldsFrom oldModel: NSManagedObjectModel? = nil) throws -> NSDictionary {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        let store = try coordinator.addPersistentStore(ofType: NSSQLiteStoreType, configurationName: nil, at: url,
            options: [NSReadOnlyPersistentStoreOption: true])
        defer { try? coordinator.remove(store) }
        let context = NSManagedObjectContext(concurrencyType: .mainQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        var result: [String: NSDictionary] = [:]
        for entity in (oldModel ?? model).entities {
            guard let name = entity.name else { continue }
            let request = NSFetchRequest<NSManagedObject>(entityName: name)
            let count = try context.count(for: request)
            let objects = try context.fetch(request)
            guard count == objects.count else { throw LocalDataError.invalid("原库存在无法读取的记录") }
            var records: [String: NSDictionary] = [:]
            for object in objects {
                var fields: [String: Any] = [:]
                for key in entity.attributesByName.keys { fields[key] = object.value(forKey: key) ?? NSNull() }
                guard let id = fields["id"], !(id is NSNull) else { throw LocalDataError.invalid("原记录缺少标识") }
                let key = String(describing: id)
                guard records[key] == nil else { throw LocalDataError.invalid("原记录标识重复") }
                records[key] = fields as NSDictionary
            }
            result[name] = records as NSDictionary
        }
        return result as NSDictionary
    }
}
