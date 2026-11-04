package cc.baseballmaster.core

/** A read-only view of committed contacts, never reconstructed from names or aggregate totals. */
data class PlayerContactRecord(
    val id: String,
    val gameID: String,
    val entryID: String,
    val inning: Int,
    val isTop: Boolean,
    val outcome: Outcome?,
    val result: String,
    val originalText: String,
    val observation: Observation?,
    val point: FieldPoint?,
    val region: String?,
) {
    val category: String get() = if (outcome?.isHit == true) "hit" else if ((outcome?.outCount ?: 0) > 0) "out" else "other"
    val hasLocation: Boolean get() = point != null || region != null
}

data class PlayerContactSelection(val gameCount: Int, val contacts: List<PlayerContactRecord>) {
    val unknownCount: Int get() = contacts.count { !it.hasLocation }
    val hitCount: Int get() = contacts.count { it.category == "hit" }
    val outCount: Int get() = contacts.count { it.category == "out" }
    val otherCount: Int get() = contacts.count { it.category == "other" }
}

object PlayerContactProjection {
    /** Caller supplies the existing statistics projection's selectedGames (scope/identity/dedup already resolved).
     * Entries are the engine's current applied state, so undone/cancelled journal commands are never consulted.
     * Region observations remain regions: this projection deliberately supplies no invented point for them.
     */
    fun calculate(playerID: String, selectedGames: List<Game>): PlayerContactSelection {
        val games = selectedGames.filter { !it.practice && !it.isScheduled && it.isFinal &&
            (it.home.players + it.away.players).any { player -> player.id == playerID } }
        val contacts = games.flatMap { game ->
            game.entries.filter { it.batterID == playerID && (it.kind == "play" || it.kind == "illegal" && it.outcome != null) }
                .map { entry ->
                    val observation = entry.observation
                    val x = observation?.x; val y = observation?.y
                    val point = if (observation?.precision == "point" && x != null && y != null &&
                        x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) FieldPoint(x,y) else null
                    val region = observation?.region?.takeIf { observation.precision == "region" && it in ScoringOptions.regions.keys }
                    PlayerContactRecord("${game.id}/${entry.id}",game.id,entry.id,entry.inning,entry.isTop,
                        entry.outcome,entry.outcome?.title ?: entry.notation.ifBlank { "结果未注明" },entry.text,observation,point,region)
                }
        }
        return PlayerContactSelection(games.size, contacts)
    }
}
