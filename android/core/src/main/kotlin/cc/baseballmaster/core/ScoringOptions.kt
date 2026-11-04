package cc.baseballmaster.core

/** Codes stay independent from translated labels and are also used by persisted commands. */
object ScoringOptions {
    val runnerEvents =
        linkedMapOf(
            "stolenBase" to "盗垒",
            "doubleSteal" to "双盗垒",
            "delayedSteal" to "延迟盗垒",
            "relayAdvance" to "传球进垒",
            "defensiveErrorAdvance" to "守备失误进垒",
            "wildPitch" to "暴投",
            "passedBall" to "捕逸",
            "uncertainLooseBall" to "暴投／捕逸待确认",
            "balk" to "投手犯规进垒",
            "caughtStealing" to "盗垒失败",
            "pickoff" to "牵制出局",
            "tagOut" to "触杀",
            "forceOut" to "封杀",
            "appealOut" to "申诉出局",
            "leftEarly" to "提早离垒",
            "missedBase" to "漏踩垒",
            "outOfBasePath" to "偏离垒线",
            "passedRunner" to "超越跑者",
            "runnerInterference" to "跑者妨碍",
            "runnerHitByBall" to "击球碰跑者",
            "coachAssistance" to "教练协助出局",
        )
    val violations =
        linkedMapOf(
            "balk" to "投手犯规",
            "quickPitch" to "快速投球",
            "illegalPitch" to "非法投球",
            "foreignSubstance" to "异物投球",
            "batterOutOfBox" to "打者踏出打击区",
            "batterInterference" to "打者妨碍",
            "illegalBat" to "违规球棒",
            "battingOutOfTurn" to "打序错误",
            "catcherInterference" to "捕手妨碍",
            "plateObstruction" to "本垒阻挡",
            "obstruction" to "跑垒阻挡",
            "fakeTag" to "假触杀",
            "detachedEquipment" to "脱离装备触球",
            "runnerInterference" to "跑者妨碍",
            "runningLaneInterference" to "跑垒线妨碍",
            "coachInterference" to "教练妨碍",
            "passingRunner" to "超越前位跑者",
        )
    val ballTypes =
        linkedMapOf(
            "ground" to "地滚",
            "hardGround" to "强力地滚",
            "line" to "平飞",
            "fly" to "高飞",
            "popup" to "内野小飞",
            "bunt" to "触击",
            "unknown" to "没有看清",
        )
    val regions =
        linkedMapOf(
            "infieldLeft" to "内野左侧",
            "infieldMiddle" to "内野中间",
            "infieldRight" to "内野右侧",
            "left" to "左外野",
            "center" to "中外野",
            "right" to "右外野",
            "foulLeft" to "左侧界外",
            "foulRight" to "右侧界外",
        )
    val causes =
        linkedMapOf(
            "hit" to "击球本身",
            "relay" to "传球进垒",
            "error" to "守备失误",
            "award" to "裁判给垒",
            "tag" to "触杀",
            "appeal" to "申诉",
            "pending" to "待确认",
        )
    val positions = listOf("投手", "捕手", "一垒", "二垒", "三垒", "游击", "左外", "中外", "右外", "Free")

    fun runnerAllowed(mode: GameMode, kind: String): Boolean {
        if (kind !in runnerEvents) return false
        val stealing = listOf("stolenBase", "doubleSteal", "delayedSteal")
        return when (mode) {
            GameMode.standard -> true
            GameMode.coachPitch ->
                kind !in
                    stealing +
                        listOf(
                            "caughtStealing",
                            "leftEarly",
                            "wildPitch",
                            "passedBall",
                            "uncertainLooseBall",
                            "balk",
                        )
            GameMode.slowPitch -> kind !in stealing + "leftEarly"
        }
    }
}
