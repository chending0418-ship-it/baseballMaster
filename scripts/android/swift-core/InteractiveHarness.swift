import Foundation

private struct SessionInsertion: Decodable {
    var index: Int;var id: UUID;var command: HistoryCommand;var timestamp: Date
    var observation: BattedBallObservation?
}
private struct SessionRequest: Decodable {
    var action: String
    var stored: StoredGame?
    var command: HistoryCommand?
    var timestamp: Date?
    var observation: BattedBallObservation?
    var insertions: [SessionInsertion]?
    var index: Int?
    var errors: [HistoryErrorCredit]?
    var statisticsRuling: HistoryStatisticsRuling?
    var reason: String?
}

/// Local line protocol used only by the explicit Kotlin/Swift interoperability
/// task. Real action/undo/redo/correction APIs, declared memory-only adapters.
@MainActor enum InteractiveHarness {
    private static func snapshot(_ store: GameStore) throws -> [String:Any] {
        guard let stored=store.activeStoredGame else {throw LocalDataError.invalid("No saved local game")}
        var value:[String:Any]=["ok":true,"actual":try ScenarioHarness.canonical(store),
            "storedGame":try JSONSerialization.jsonObject(with:JSONEncoder().encode(stored)),
            "canUndo":store.canUndo]
        if store.game.historyJournal != nil {
            let draft=try store.makeCorrectionDraft();let preview=store.previewCorrection(draft)
            guard preview.canSave else {throw LocalDataError.invalid("Current history replay: \(preview.conflict ?? "unknown")")}
            let view=GameStore(persistenceURL:nil);view.isHistoryReplay=true;view.game=preview.game
            // Audit revisions are retained separately, not rebuilt by preview.
            value["replayed"]=try ScenarioHarness.canonical(view).filter {$0.key != "correctionCount"}
        }
        return value
    }
    static func run() throws {
        var store: GameStore?
        while let line=readLine() {
            do {
                guard line.utf8.count<=18*1_048_576 else {throw LocalDataError.invalid("Reference request too large")}
                let request=try JSONDecoder().decode(SessionRequest.self,from:Data(line.utf8))
                if request.action=="init" {
                    guard let initial=request.stored else {throw LocalDataError.invalid("Missing initial stored game")}
                    store=try ScenarioHarness.localStore(initial)
                } else {
                    guard let current=store else {throw LocalDataError.invalid("Initialize the local reference first")}
                    current.actionErrorMessage=nil;current.historyReplayDate=request.timestamp
                    switch request.action {
                    case "command":
                        guard let command=request.command else {throw LocalDataError.invalid("Missing command")}
                        var aliases:[UUID:UUID]=[:]
                        try current.executeHistoryCommand(command,context:HistoryContext(current.game),aliases:&aliases,observation:request.observation)
                    case "undo": current.undo()
                    case "redo": current.redo()
                    case "insert":
                        var draft=try current.makeCorrectionDraft()
                        // A multi-insertion edit is validated as a whole. An
                        // intermediate re-entry may deliberately be invalid
                        // until the preceding replacement is also inserted.
                        let originalPreview=current.previewCorrection(draft)
                        guard originalPreview.canSave else {throw LocalDataError.invalid(originalPreview.conflict ?? "Original history invalid")}
                        for item in request.insertions ?? [] {
                            guard (0...draft.journal.operations.count).contains(item.index) else {throw LocalDataError.invalid("Insertion index out of range")}
                            let before: GameState
                            if item.index<draft.journal.operations.count {
                                guard let state=originalPreview.statesBefore[draft.journal.operations[item.index].id] else {throw LocalDataError.invalid("Missing original insertion context")}
                                before=state
                            } else {before=originalPreview.game}
                            var operation=HistoryOperation(command:item.command,before:before,timestamp:item.timestamp,inserted:true)
                            operation.id=item.id;operation.battedBall=item.observation
                            draft.journal.operations.insert(operation,at:item.index)
                        }
                        draft.reason=request.reason ?? "补记"
                        guard current.saveCorrection(draft) else {throw LocalDataError.invalid(current.actionErrorMessage ?? "Correction save failed")}
                    case "amend":
                        var draft=try current.makeCorrectionDraft()
                        guard let index=request.index,draft.journal.operations.indices.contains(index) else {throw LocalDataError.invalid("Amend index out of range")}
                        draft.journal.operations[index].edited=true
                        if let errors=request.errors {draft.journal.operations[index].errors=errors}
                        if let ruling=request.statisticsRuling {draft.journal.operations[index].statisticsRuling=ruling}
                        draft.reason=request.reason ?? "人工复核"
                        guard current.saveCorrection(draft) else {throw LocalDataError.invalid(current.actionErrorMessage ?? "Amend save failed")}
                    case "revert":
                        guard let revision=current.game.correctionRevisions?.last else {throw LocalDataError.invalid("No correction to revert")}
                        let draft=try current.draftRevertingCorrection(revision)
                        guard current.saveCorrection(draft) else {throw LocalDataError.invalid(current.actionErrorMessage ?? "Revert save failed")}
                    case "snapshot": break
                    default: throw LocalDataError.invalid("Unknown reference action")
                    }
                    if let error=current.actionErrorMessage {throw LocalDataError.invalid(error)}
                }
                guard let current=store else {throw LocalDataError.invalid("Missing reference store")}
                let value=try snapshot(current)
                let bytes=try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys])
                FileHandle.standardOutput.write(bytes);FileHandle.standardOutput.write(Data([10]))
            } catch {
                let bytes=try JSONSerialization.data(withJSONObject:["ok":false,"error":error.localizedDescription],options:[.sortedKeys])
                FileHandle.standardOutput.write(bytes);FileHandle.standardOutput.write(Data([10]))
            }
        }
    }
}
