package cc.baseballmaster.core

/** A presentation projection for Android records. The scoring journal remains authoritative. */
data class ProjectedAppearance(
    val id: String,
    val sequence: Int,
    val inning: Int,
    val isTop: Boolean,
    val batterID: String,
    val complete: Boolean,
    val interrupted: Boolean,
    val events: List<PitchEntry>,
)

object AppearanceProjection {
    fun build(game: Game): List<ProjectedAppearance> {
        val records = mutableListOf<ProjectedAppearance>()
        val teamCounts = mutableMapOf(true to 0, false to 0)
        var current: ProjectedAppearance? = null
        fun start(id: String, inning: Int, isTop: Boolean, batterID: String): ProjectedAppearance {
            val sequence = (teamCounts[isTop] ?: 0) + 1; teamCounts[isTop] = sequence
            return ProjectedAppearance(id, sequence, inning, isTop, batterID, false, false, emptyList())
        }
        val commands = game.journal.associateBy { it.id }
        game.entries.forEach { event ->
            val before = event.before
            val previous = current
            if (previous == null || previous.complete || previous.interrupted || previous.inning != event.inning || previous.isTop != event.isTop) {
                if (previous != null) records += previous
                val batter = event.batterID ?: game.batter.id
                current = start(event.id, event.inning, event.isTop, batter)
            }
            var next = current!!
            val command = commands[event.id]?.command
            if (event.kind == "batter" && command?.playerID != null) next = next.copy(batterID = command.playerID)
            next = next.copy(events = next.events + event)
            val after = event.after
            if (before != null && after != null) {
                val halfChanged = before.inning != after.inning || before.isTop != after.isTop
                val batterChanged = before.batterIndex != after.batterIndex
                if (halfChanged || batterChanged) {
                    val interrupted = event.kind in setOf("runner", "endHalf", "situation", "lineup", "position", "fielder", "pitcher", "pinchRunner", "doubleSwitch")
                    next = next.copy(complete = !interrupted, interrupted = interrupted)
                }
            }
            current = next
        }
        current?.let { records += it }
        // iOS keeps the current, unfinished appearance even before its first event.
        if (current == null || current!!.complete || current!!.interrupted) {
            records += start("${game.id}-current-${game.entries.size}", game.inning, game.isTop, game.batter.id)
        }
        return records
    }
}
