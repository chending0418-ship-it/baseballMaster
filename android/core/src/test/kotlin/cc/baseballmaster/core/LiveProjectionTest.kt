package cc.baseballmaster.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveProjectionTest {
    private fun game(): Game {
        fun team()=Team(UUID.randomUUID().toString(),"合成队",(1..12).map {Player(UUID.randomUUID().toString(),if(it==12) "不公开的替补资料" else "球员$it",if(it==1) "00" else "$it",position=it.coerceAtMost(9))})
        return MatchFactory.create(UUID.randomUUID().toString(),team(),team(),Rules())
    }
    @Test fun onlyPublicParticipantsAndProcessArePublishedAndProjectionIsDeterministic() {
        val g=MatchEngine.apply(game(),Command("pitch",pitch=PitchAction.ball,at=1_800_000_000_000)).copy(correctionRevisions=listOf("不公开的纠错审计"),appleSourceID="不公开的来源摘要")
        val a=LiveProjection.create(g);assertEquals(a.toString(),LiveProjection.create(g).toString())
        for(privateValue in listOf("不公开的替补资料","不公开的纠错审计","不公开的来源摘要","historyJournal","runnerPitcherIDs")) assertFalse(a.toString().contains(privateValue))
        assertEquals("00",a.getValue("batter").jsonObject.getValue("number").jsonPrimitive.content)
        assertEquals(9,a.getValue("home").jsonObject.getValue("lineup").jsonArray.size)
        assertTrue(a.getValue("hasStarted").jsonPrimitive.boolean)
    }
    @Test fun batterAndOnDeckUseTheOffensiveSideEvenWhenJerseyNumbersMatch() {
        fun team(id: String, label: String) = Team(id, label, (1..12).map {
            Player("$id-$it", "$label$it", "$it", position = it.coerceAtMost(9))
        })
        var game = MatchFactory.create("live-batter-parity", team("home", "主"), team("away", "客"), Rules())
        repeat(3) { game = MatchEngine.apply(game, Command("play", outcome = Outcome.single)) }

        fun assertBatters(label: String, order: Int, next: List<String>) {
            val live = LiveProjection.create(game)
            assertEquals(label, live.getValue("batter").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(order, live.getValue("batterOrder").jsonPrimitive.int)
            assertEquals(next, live.getValue("nextBatters").jsonArray.map {
                it.jsonObject.getValue("player").jsonObject.getValue("name").jsonPrimitive.content
            })
        }
        assertBatters("客4", 4, listOf("客5", "客6"))
        game = MatchEngine.apply(game, Command("batter", playerID = "away-10"))
        assertBatters("客10", 4, listOf("客5", "客6"))
        repeat(9) { game = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.calledStrike)) }
        assertFalse(game.isTop)
        assertBatters("主1", 1, listOf("主2", "主3"))
    }

    @Test fun finalProjectionClosesTheUnfinishedAppearanceAndHasNoUpcomingOrCurrentBatterOrder() {
        var g=MatchEngine.apply(game(),Command("pitch",pitch=PitchAction.ball,at=1_800_000_000_000))
        g=MatchEngine.apply(g,Command("finish",text="scorerDecision",at=1_800_000_001_000))
        val result=LiveProjection.create(g)
        assertEquals(JsonNull,result.getValue("currentAppearanceID"));assertEquals(JsonNull,result.getValue("batterOrder"));assertTrue(result.getValue("nextBatters").jsonArray.isEmpty())
        assertTrue(result.getValue("entries").jsonArray.none {it.jsonObject.getValue("status").jsonPrimitive.content=="current"})
    }
    @Test fun publicQRCodeDecodesToExactLinkAndOtherOriginsAndSecretQueriesAreRejected() {
        val url="https://baseballmaster.cc/livestreaming/novideo/ABCDEF0123456789ABCDEF01";val qr=liveViewQr(url)
        assertEquals(url,MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width,qr.height,qr.pixels)))).text)
        for(bad in listOf(url+"?token=private",url+"#private",url.replace("baseballmaster.cc","example.com"))) assertThrows(IllegalArgumentException::class.java) {liveViewQr(bad)}
        for(bad in listOf("https://example.com/livestreaming/novideo","http://baseballmaster.cc/livestreaming/novideo","https://baseballmaster.cc/livestreaming/novideo?token=private")) assertThrows(IllegalArgumentException::class.java) {LiveHttpTransport(bad)}
    }
    @Test fun sharedWatchQRKeepsInvitationsInFragmentsAndRejectsQueriesAndWrongOrigins() {
        val code="aB0_".repeat(8);val token=TeamSecrets.random()
        for(url in listOf("https://baseballmaster.cc/watch/#$code","https://baseballmaster.cc/watch/#$code/$token","https://baseballmaster.cc/watch/#live/$code")) {
            val qr=watchViewQr(url)
            // A generated bitmap has no camera perspective. Pure decoding checks every encoded
            // fragment byte without the detector randomly misidentifying dense token patterns.
            assertEquals(url,MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width,qr.height,qr.pixels))), mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text)
        }
        for(url in listOf("https://example.com/watch/#$code/$token","https://baseballmaster.cc/watch/?token=$token#$code","https://baseballmaster.cc/watch/#$code/invalid")) assertThrows(IllegalArgumentException::class.java) {watchViewQr(url)}
    }
}
