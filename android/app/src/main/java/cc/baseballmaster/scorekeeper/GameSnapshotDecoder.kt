package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.Game

/** Used by one serialized background flow. Compare the full row, not just its revision. */
internal class GameSnapshotDecoder(private val decode: (GameEntity) -> Game = GameRepository::decode) {
    private val cache = mutableMapOf<String, Pair<GameEntity, Game>>()
    fun decode(rows: List<GameEntity>): List<Game> {
        cache.keys.retainAll(rows.mapTo(mutableSetOf()) { it.id })
        return rows.map { row ->
            cache[row.id]?.takeIf { it.first == row }?.second ?: decode(row).also {
                cache[row.id] = row to it
            }
        }
    }
}
