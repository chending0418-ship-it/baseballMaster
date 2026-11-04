package cc.baseballmaster.core

import kotlinx.serialization.json.*

/** Read-only evidence from the original wire state. Never replay or synthesize missing history. */
data class AppleArchiveHistory(
    val logs: List<JsonObject>,
    val events: List<JsonObject>,
    val appearances: List<JsonObject>,
    val operations: List<JsonObject>,
    val corrections: List<JsonObject>,
    val hasJournal: Boolean,
) {
    companion object {
        fun read(stored: JsonObject): AppleArchiveHistory {
            AppleStoredGameValidation.validate(stored)
            val state = stored.getValue("state").jsonObject
            fun rows(key: String) = state[key]?.takeUnless { it == JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }
            val journal = state["historyJournal"]?.takeUnless { it == JsonNull }?.jsonObject
            return AppleArchiveHistory(rows("playLog"), rows("scoringEvents"), rows("plateAppearances"),
                journal?.getValue("operations")?.jsonArray.orEmpty().map { it.jsonObject },
                rows("correctionRevisions"), journal != null)
        }
    }
}
