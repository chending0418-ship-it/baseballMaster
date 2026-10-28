import Foundation

private struct ScenarioInput: Decodable {
    var scenario: String
    var stored: StoredGame
    var steps: [ScenarioStep]
}
private struct ScenarioStep: Decodable {
    var id: UUID
    var command: HistoryCommand
    var timestamp: Date
    var observation: BattedBallObservation?
    var expected: [String: MatchJSON]
}

@MainActor enum ScenarioHarness {
    private static func equal(_ lhs: MatchJSON,_ rhs: MatchJSON) -> Bool {
        switch (lhs,rhs) {
        case (.null,.null): return true
        case (.bool(let a),.bool(let b)): return a==b
        case (.number(let a),.number(let b)): return a==b
        case (.string(let a),.string(let b)): return a==b
        case (.array(let a),.array(let b)): return a.count==b.count && zip(a,b).allSatisfy {equal($0,$1)}
        case (.object(let a),.object(let b)): return Set(a.keys)==Set(b.keys) && a.allSatisfy {key,value in equal(value,b[key]!)}
        default: return false
        }
    }
    private static func canonical(_ store: GameStore) throws -> [String:Any] {
        var result=try OriginalCanonical(store).canonical()
        result["clockRunning"]=store.game.clockRunningSince != nil
        result["statisticsIncomplete"]=store.game.statisticsIncomplete == true
        result["correctionCount"]=(store.game.correctionRevisions ?? []).count
        return result
    }
    private static func differences(_ actual: [String:Any],_ expected: [String:MatchJSON]) throws -> [String] {
        let typed=try JSONDecoder().decode([String:MatchJSON].self,from:JSONSerialization.data(withJSONObject:actual))
        return expected.keys.filter {key in !equal(typed[key] ?? .null,expected[key]!)}.sorted()
    }
    static func run(input: URL,output: URL) throws {
        let scenario=try JSONDecoder().decode(ScenarioInput.self,from:Data(contentsOf:input))
        let initial=scenario.stored
        guard initial.sharedTeamID==nil,initial.sharedGameID==nil,initial.isSpectator != true else {
            throw LocalDataError.invalid("Reference scenarios must be local games")
        }
        let store=GameStore(persistenceURL:nil)
        // Bootstrap only. Actions below use the real transaction/capture path;
        // persistence remains the explicitly declared Linux in-memory adapter.
        store.isHistoryReplay=true;store.activeGameID=nil
        let home=initial.state.homeTeam,away=initial.state.awayTeam
        let own=initial.isHome ? home : away,opponent=initial.isHome ? away : home
        store.teams=[own];store.opponentTeams=[opponent];store.currentTeam=own
        store.archivedTeams=[];store.playerGameRecords=[]
        store.game=initial.state;store.games=[initial];store.activeGameID=initial.id
        store.isHistoryReplay=false
        let replayView=GameStore(persistenceURL:nil);replayView.isHistoryReplay=true
        var results:[[String:Any]]=[]
        for (index,step) in scenario.steps.enumerated() {
            // Correction preview preserves clocks and cannot silently reopen a
            // final. It is a check AFTER the actual action, never its substitute.
            store.historyReplayDate=step.timestamp
            store.actionErrorMessage=nil
            var aliases:[UUID:UUID]=[:]
            try store.executeHistoryCommand(step.command,context:HistoryContext(store.game),aliases:&aliases,observation:step.observation)
            if let error=store.actionErrorMessage {throw LocalDataError.invalid("Step \(index): \(error)")}
            guard let stored=store.activeStoredGame else {throw LocalDataError.invalid("No saved local game after action")}
            let executed=try canonical(store)
            let executionDifferences=try differences(executed,step.expected)
            var replayDifferences:[String]=[]
            let replayApplicable=store.game.historyJournal != nil
            if replayApplicable {
                let draft=try store.makeCorrectionDraft()
                let preview=store.previewCorrection(draft)
                guard preview.canSave else {throw LocalDataError.invalid("Step \(index) replay: \(preview.conflict ?? "unknown")")}
                replayView.game=preview.game
                replayDifferences=try differences(canonical(replayView),step.expected)
            }
            let different=Array(Set(executionDifferences+replayDifferences)).sorted()
            let encoded=try JSONSerialization.jsonObject(with:JSONEncoder().encode(stored))
            results.append(["step":index,"inputOperationID":step.id.uuidString,
                            "scoreMatches":different.isEmpty,"differentFields":different,
                            "executionDifferentFields":executionDifferences,"replayDifferentFields":replayDifferences,
                            "replayApplicable":replayApplicable,"actual":executed,"storedGame":encoded])
        }
        let report:[String:Any]=["scenario":scenario.scenario,"passed":results.allSatisfy {$0["scoreMatches"] as? Bool == true},"steps":results]
        try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]).write(to:output,options:.atomic)
    }
}
