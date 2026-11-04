package cc.baseballmaster.scorekeeper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

private val lineupPositionBadges = listOf("投", "捕", "一垒", "二垒", "三垒", "游击", "左外", "中外", "右外", "Free")
private val lineupPositionNames = listOf("投手", "捕手", "一垒手", "二垒手", "三垒手", "游击手", "左外野手", "中外野手", "右外野手", "自由人")

private fun Game.teamAt(home: Boolean) = if (home) this.home else away
private fun Game.orderAt(home: Boolean) = (if(home) homeOrder else awayOrder) ?: teamAt(home).players.take(rules.fielders).map { it.id }
private fun Game.positionsAt(home: Boolean) = (if(home) homePositions else awayPositions) ?: teamAt(home).players.take(rules.fielders).mapIndexed { i,p -> i+1 to p.id }.toMap()
private fun Game.dhAt(home: Boolean) = if(home) homeDesignatedHitterID else awayDesignatedHitterID
private fun Game.withLineup(home: Boolean, order: List<String> = orderAt(home), positions: Map<Int,String> = positionsAt(home), dh: String? = dhAt(home), team: Team = teamAt(home)): Game {
    val previousOrder = orderAt(home)
    val previousIndex = if(home) homeBatterIndex else awayBatterIndex
    val anchor = previousOrder.getOrNull(previousIndex % previousOrder.size.coerceAtLeast(1))
    val nextIndex = if(isPregame) 0 else order.indexOf(anchor).takeIf { it>=0 }
        ?: previousIndex.coerceIn(0, order.lastIndex.coerceAtLeast(0))
    return if(home) copy(home=team,homeOrder=order,homePositions=positions,homeDesignatedHitterID=dh,homePitcherID=positions[1],homeBatterIndex=nextIndex)
        else copy(away=team,awayOrder=order,awayPositions=positions,awayDesignatedHitterID=dh,awayPitcherID=positions[1],awayBatterIndex=nextIndex)
}

/** Saved instance state contains only edits. The source match and its complete
 * history stay with the task owner; they must never be copied into a Bundle. */
@kotlinx.serialization.Serializable
internal data class CompactLineupSide(
    val order: List<String>,
    val positions: Map<Int,String>,
    val designatedHitterID: String?,
    val profiles: List<Player> = emptyList(),
)
@kotlinx.serialization.Serializable
internal data class CompactLineupDraft(val away: CompactLineupSide? = null, val home: CompactLineupSide? = null)

internal fun captureCompactLineupDraft(source: Game, edited: Game): CompactLineupDraft {
    fun side(home: Boolean): CompactLineupSide? {
        val order=edited.orderAt(home)
        val positions=edited.positionsAt(home)
        val dh=edited.dhAt(home)
        val profiles=edited.teamAt(home).players.filter { p -> source.teamAt(home).players.firstOrNull {it.id==p.id}!=p }
        return if(order==source.orderAt(home) && positions==source.positionsAt(home) && dh==source.dhAt(home) && profiles.isEmpty()) null
            else CompactLineupSide(order,positions,dh,profiles)
    }
    return CompactLineupDraft(away=side(false),home=side(true))
}
internal fun applyCompactLineupDraft(source: Game, patch: CompactLineupDraft): Game {
    var edited=source
    listOf(false,true).forEach { home ->
        val side=if(home)patch.home else patch.away
        if(side!=null) {
            val team=edited.teamAt(home)
            val profiles=side.profiles.associateBy {it.id}
            val updatedTeam=if(profiles.isEmpty())team else team.copy(players=team.players.map {profiles[it.id] ?: it})
            edited=edited.withLineup(home,side.order,side.positions,side.designatedHitterID,updatedTeam)
        }
    }
    return edited
}
private fun emptyLineupDraftJSON()=GameJson.encodeToString(CompactLineupDraft())
private fun compactLineupDraftJSON(source:Game,edited:Game)=GameJson.encodeToString(captureCompactLineupDraft(source,edited))

/** Only infer explicit slot-preserving replacements, never a changed batting order. */
private fun lineupCommand(source: Game, draft: Game, home: Boolean): Command {
    val old=source.orderAt(home); val order=draft.orderAt(home)
    val replacements=old.mapIndexedNotNull { i,id -> order.getOrNull(i)?.takeIf { id !in order && it !in old }?.let { id to it } }.toMap()
    val updates=draft.teamAt(home).players.filter { p -> source.teamAt(home).players.firstOrNull { it.id==p.id } != p }
    return Command("lineup",homeSide=home,order=order,positions=draft.positionsAt(home),designatedHitterID=draft.dhAt(home),
        playerUpdates=updates.takeIf { it.isNotEmpty() },lineupReplacements=replacements.takeIf { it.isNotEmpty() })
}
private fun lineupIssue(source: Game,draft: Game,home: Boolean): String? = runCatching { MatchEngine.apply(source,lineupCommand(source,draft,home)) }.exceptionOrNull()?.message

@Composable
private fun LineupNotice(title: String, text: String) {
    CardBlock {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(NavigationSymbols.roster,null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
            Text(title,fontWeight=FontWeight.Bold,fontSize=18.sp,color=MaterialTheme.colorScheme.primary)
        }
        Text(text,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuardedExit(changed: Boolean,busy: Boolean, onExit:()->Unit, onSaveExit:(()->Unit)?=null,saveEnabled:Boolean=true, content: @Composable ((()->Unit))->Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val leave:()->Unit = { if(!busy) { if(changed) confirm=true else onExit() } }
    BackHandler { leave() }
    content(leave)
    if(confirm) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={confirm=false},title={Text("还有未保存的阵容调整")},text={Text("返回将放弃本次调整。已保存的比赛记录不受影响。")},
        confirmButton={
            if(onSaveExit!=null) TextButton({confirm=false;onSaveExit()},enabled=saveEnabled&&!busy){Text("保存并返回")}
            else TextButton({confirm=false;onExit()}){Text("放弃调整并返回")}
        },dismissButton={Column {
            if(onSaveExit!=null) TextButton({confirm=false;onExit()},enabled=!busy){Text("放弃调整并返回")}
            TextButton({confirm=false}){Text("继续调整")}
        }})
}

@Composable
internal fun PregameLineupConfirmation(game: Game,games: List<Game> = emptyList(),busy: Boolean,onBack:()->Unit,onConfirm:(Game)->Unit) {
    var json by rememberSaveable(game.id) { mutableStateOf(emptyLineupDraftJSON()) }
    val patch=remember(json) { GameJson.decodeFromString<CompactLineupDraft>(json) }
    val draft=remember(game,patch) { applyCompactLineupDraft(game,patch) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val errors=listOf(false,true).mapNotNull { home -> lineupIssue(game,draft,home)?.let { "${if(home)"主队" else "客队"}：$it" } }
    GuardedExit(patch!=CompactLineupDraft(),busy,onBack) { leave ->
        ProductPage(title=if(game.isObservation) "观赛阵容" else "双方阵容",busy=busy,onBack=leave,backLabel="返回",
            actionLabel=if(game.isScheduled) "确认双方阵容并创建比赛" else "确认双方阵容并开始记录",actionTag="start-confirmed-game",actionEnabled=errors.isEmpty(),
            onAction={confirm=true}) {
            LineupNotice(if(game.isObservation) "观赛记录" else "双方赛前阵容",if(game.isObservation) "分别确认双方棒次、守位和先发球员。双方均不计入本队统计，记录过程中仍可调整。" else "分别确认双方棒次、守位和先发投手。本队比赛身份与统计保持，比赛开始后仍可调整双方阵容。")
            BoxWithConstraints {
                if(maxWidth>=720.dp) Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    listOf(false,true).forEach { home -> Column(Modifier.weight(1f)) { LineupSideCards(draft,home,games,true) { json=compactLineupDraftJSON(game,it) } } }
                } else Column(verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    listOf(false,true).forEach { home -> LineupSideCards(draft,home,games,true) { json=compactLineupDraftJSON(game,it) } }
                }
            }
            errors.forEach { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
            if(!game.isObservation) Text(when {game.postgameSharingChoice==null -> "赛后共享：尚未选择";game.postgameSharingChoice?.teamID==null -> "赛后共享：仅本机";else -> "赛后共享：${game.postgameSharingChoice?.teamName}（赛后确认）"},Modifier.testTag("setup-postgame-summary"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(confirm) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={confirm=false},title={Text(if(game.isScheduled) "确认创建未来比赛？" else "建立比赛并开始记录？")},
        text={Text("${draft.away.name}（客队 · 先攻）\n${draft.home.name}（主队 · 后攻）\n${draft.rules.mode.title} · ${draft.rules.innings} 局")},
        confirmButton={TextButton({confirm=false
            var result=MatchEngine.apply(game,lineupCommand(game,draft,false))
            result=MatchEngine.apply(result,lineupCommand(game,draft,true))
            // Repository commits explicit roster patches and clears this pregame
            // journal atomically with creating/configuring the game.
            onConfirm(result)
        },Modifier.testTag("confirm-create-lineups")){Text(if(game.isScheduled)"创建比赛" else "开始记录")}},dismissButton={TextButton({confirm=false}){Text("继续检查")}})
}

@Composable
internal fun ParityLineupEditor(game: Game,busy: Boolean,dismiss:()->Unit,initialHome:Boolean=true,save:(Command)->Unit) {
    // A selected team's editor has one persistent draft; the task menu owns team selection.
    var json by rememberSaveable(game.id,initialHome) { mutableStateOf(emptyLineupDraftJSON()) }
    val patch=remember(json) { GameJson.decodeFromString<CompactLineupDraft>(json) }
    val draft=remember(game,patch) { applyCompactLineupDraft(game,patch) }
    var savedRevision by rememberSaveable(game.id,initialHome) { mutableLongStateOf(game.revision) }
    var showSaved by rememberSaveable(game.id,initialHome) { mutableStateOf(false) }
    var returnAfterSave by rememberSaveable(game.id,initialHome) { mutableStateOf(false) }
    LaunchedEffect(game.revision) { if(game.revision>savedRevision) {
        // A successful parent commit is the sole acknowledgement. Reset the
        // draft and its feedback together, including profile/roster flow updates.
        json=emptyLineupDraftJSON()
        savedRevision=game.revision
        showSaved=true
        if(returnAfterSave) { returnAfterSave=false; dismiss() }
    } }
    val changed=lineupCommand(game,draft,initialHome)!=lineupCommand(game,game,initialHome)
    val issue=lineupIssue(game,draft,initialHome)
    var confirmPitcher by rememberSaveable { mutableStateOf(false) }
    val command=lineupCommand(game,draft,initialHome)
    val submit:()->Unit={if(!game.isPregame && game.positionsAt(initialHome)[1]!=draft.positionsAt(initialHome)[1]) confirmPitcher=true else save(command)}
    GuardedExit(changed,busy,dismiss,onSaveExit={returnAfterSave=true;submit()},saveEnabled=issue==null) { leave ->
        ProductPage(title="${draft.teamAt(initialHome).shortName} · 阵容",busy=busy,onBack=leave,backLabel=if(changed)"返回" else "完成",actionLabel="保存",actionAtTop=true,actionTag="save-lineup",actionEnabled=changed&&issue==null,onAction={returnAfterSave=false;submit()},modal=true) {
            LineupNotice(if(game.isPregame) "开赛前阵容" else "本场阵容调整",if(game.isPregame) "可修改先发、棒次和守位，保存后按新阵容开赛。" else "姓名与号码更正保留球员身份和统计。排序保留当前打者与球数，换人接替原棒次、守位及垒位。")
            if(showSaved) Text("✓ 已保存，可继续调整或点完成返回",color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("lineup-saved"),fontSize=13.sp)
            LineupSideCards(draft,initialHome,emptyList(),game.isPregame) { showSaved=false;json=compactLineupDraftJSON(game,it) }
            if(changed) CardBlock {
                Text("待保存的调整",fontWeight=FontWeight.Bold)
                val before=game.orderAt(initialHome);val after=draft.orderAt(initialHome)
                if(before!=after) Text("打序／先发球员已调整",fontSize=13.sp)
                if(game.positionsAt(initialHome)!=draft.positionsAt(initialHome)) Text("守备位置已调整",fontSize=13.sp)
                command.playerUpdates.orEmpty().forEach { Text("更正 ${it.name} · #${it.jerseyNumbers.joinToString(" / ")}",fontSize=13.sp) }
                if(game.dhAt(initialHome)!=draft.dhAt(initialHome)) Text("DH 身份已调整",fontSize=13.sp)
            }
            issue?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
        }
    }
    if(confirmPitcher) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={confirmPitcher=false;returnAfterSave=false},title={Text("确认调整投手？")},text={Text("新投手接管投球，已记录的投球与垒上跑者原投手责任保留。")},confirmButton={TextButton({confirmPitcher=false;save(command)}){Text("确认保存")}},dismissButton={TextButton({confirmPitcher=false;returnAfterSave=false}){Text("继续调整")}})
}

@Composable
private fun LineupSideCards(game: Game,home: Boolean,games: List<Game>,pregame:Boolean,change:(Game)->Unit) {
    val team=game.teamAt(home);val order=game.orderAt(home);val positions=game.positionsAt(home)
    val bench=team.players.filter { !it.archived && it.id !in order && it.id !in positions.values }
    var profileID by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementID by rememberSaveable { mutableStateOf<String?>(null) }
    var pitchingOnly by rememberSaveable { mutableStateOf(false) }
    var fieldingIncomingID by rememberSaveable { mutableStateOf<String?>(null) }
    val rowBounds = remember(team.id) { mutableMapOf<String, Rect>() }
    var draggingID by remember(team.id) { mutableStateOf<String?>(null) }
    val latestGame by rememberUpdatedState(game)
    val latestChange by rememberUpdatedState(change)
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if(home) "主队 · 后攻" else "客队 · 先攻",fontWeight=FontWeight.Bold,fontSize=20.sp,modifier=Modifier.weight(1f))
            Text("${team.shortName} · ${order.size} 棒 · 守备 ${positions.size}/${game.rules.fielders}",modifier=Modifier.widthIn(max=210.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.End)
        }
        if(pregame) {
            val previous=games.filter { it.id!=game.id && it.rules.mode==game.rules.mode && !it.scheduledSetupPending && (it.home.id==team.id || it.away.id==team.id) }.maxByOrNull { it.playedAt ?: it.createdAt }
            if(previous!=null) TextButton({
                val priorHome=previous.home.id==team.id;val ids=team.players.filter { !it.archived }.map { it.id }.toSet()
                val priorOrder=previous.orderAt(priorHome).filter { it in ids };val priorPositions=previous.positionsAt(priorHome).filterValues { it in ids }
                change(game.withLineup(home,priorOrder,priorPositions,previous.dhAt(priorHome)?.takeIf { it in ids }))
            },Modifier.testTag("reuse-lineup-${if(home)"home" else "away"}")){Text("沿用上一场阵容")}
        }
        order.forEachIndexed { index,id -> key(id) {
            val player=team.players.first { it.id==id };val pos=positions.entries.firstOrNull { it.value==id }?.key
            var personMenu by remember(id) { mutableStateOf(false) }
            var positionMenu by remember(id) { mutableStateOf(false) }
            var handleCoordinates by remember(id) { mutableStateOf<LayoutCoordinates?>(null) }
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface,
                border=if(draggingID==id) BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null,
                shadowElevation=if(draggingID==id) 4.dp else 0.dp,
                modifier=Modifier.fillMaxWidth().zIndex(if(draggingID==id)1f else 0f)
                    .onGloballyPositioned {
                        // boundsInRoot clips offscreen rows to Rect.Zero. Using
                        // those centers would jump a dragged player to the end.
                        val origin=it.positionInRoot()
                        rowBounds[id]=Rect(origin.x,origin.y,origin.x+it.size.width,origin.y+it.size.height)
                    }
                    .testTag("lineup-row-${if(home)"home" else "away"}-$index")) {
                Row(Modifier.padding(horizontal=10.dp,vertical=if(pregame)4.dp else 8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(36.dp).testTag("lineup-drag-$id")
                        .onGloballyPositioned { handleCoordinates=it }
                        .semantics { contentDescription="第 ${index+1} 棒 ${player.name}，长按拖动调整棒次" }
                        .pointerInput(team.id,id) {
                            var previousRootY=0f
                            detectDragGesturesAfterLongPress(
                                onDragStart={offset ->
                                    draggingID=id
                                    previousRootY=handleCoordinates?.takeIf {it.isAttached}?.localToRoot(offset)?.y
                                        ?: rowBounds[id]?.center?.y ?: 0f
                                },
                                onDragEnd={draggingID=null},
                                onDragCancel={draggingID=null},
                                onDrag=drag@{pointer,_ ->
                                    pointer.consume()
                                    val coordinates=handleCoordinates?.takeIf {it.isAttached} ?: return@drag
                                    // Reordering moves the keyed pointer-input node.
                                    // Convert the pointer to root coordinates instead
                                    // of accumulating deltas in the relocating node.
                                    val rootY=coordinates.localToRoot(pointer.position).y
                                    val direction=rootY-previousRootY
                                    previousRootY=rootY
                                    val currentOrder=latestGame.orderAt(home)
                                    val from=currentOrder.indexOf(id)
                                    if(from>=0) {
                                        val crossed=currentOrder.indices.filter { index ->
                                            val center=rowBounds[currentOrder[index]]?.center?.y
                                            center!=null && if(direction>0) index>from && rootY>center
                                                else direction<0 && index<from && rootY<center
                                        }
                                        val target=if(direction>0)crossed.maxOrNull() else crossed.minOrNull()
                                        if(target!=null) {
                                            val moved=currentOrder.toMutableList().apply { add(target,removeAt(from)) }
                                            latestChange(latestGame.withLineup(home,moved))
                                        }
                                    }
                                },
                            )
                        }
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center) { Text("${index+1}",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimary,fontSize=15.sp) }
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth().testTag("lineup-player-$id").clickable { personMenu=true }.padding(vertical=4.dp)) {
                            Text(player.name,fontSize=15.sp,fontWeight=FontWeight.Bold)
                            Text("#${player.jerseyNumbers.joinToString(" / ")}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(!pregame) {
                                val batterIndex=if(home) game.homeBatterIndex else game.awayBatterIndex
                                val roles=buildList { if(index==batterIndex) add(if(home!=game.isTop)"当前打者" else "下一位打者"); game.bases.indexOf(id).takeIf { it>=0 && home!=game.isTop }?.let { add("${it+1} 垒跑者") };if(id==game.dhAt(home))add("DH") }
                                if(roles.isNotEmpty()) Text(roles.joinToString(" · "),fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                        DropdownMenu(personMenu,{personMenu=false},modifier=Modifier.semantics { testTagsAsResourceId=true }) {
                            DropdownMenuItem(text={Text("更正姓名／号码")},onClick={personMenu=false;profileID=id})
                            DropdownMenuItem(text={Text(if(pregame)"更换先发球员" else "换人（接替棒次与守位）")},onClick={personMenu=false;pitchingOnly=false;replacementID=id},enabled=bench.isNotEmpty())
                            if(game.rules.twoWayEnabled && id==positions[1] && id==game.dhAt(home)) {
                                DropdownMenuItem(text={Text("只换投手（原投手继续 DH）")},modifier=Modifier.testTag("lineup-pitching-only-$id"),onClick={personMenu=false;pitchingOnly=true;replacementID=id},enabled=bench.isNotEmpty())
                            }
                            if(game.rules.mode==GameMode.slowPitch && pos==null) {
                                DropdownMenuItem(text={Text("进入守备（棒次不变）")},modifier=Modifier.testTag("lineup-enter-fielding-$id"),onClick={personMenu=false;fieldingIncomingID=id})
                            }
                            if(pregame && game.rules.mode==GameMode.slowPitch && pos==null && order.size>10) DropdownMenuItem(text={Text("移出打序")},onClick={personMenu=false;change(game.withLineup(home,order-id))})
                        }
                    }
                    if(bench.isNotEmpty()) TextButton({pitchingOnly=false;replacementID=id},Modifier.sizeIn(minWidth=32.dp,minHeight=44.dp).testTag("lineup-replace-$id"),contentPadding=PaddingValues(2.dp)) {
                        Icon(NavigationSymbols.lineupManage,"更换${player.name}",Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        Surface(onClick={positionMenu=true},shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.testTag("lineup-position-${if(home)"home" else "away"}-$index")) {
                            Text(pos?.let { lineupPositionBadges[it-1] } ?: if(id==game.dhAt(home))"DH" else "暂不守备",fontSize=12.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.padding(horizontal=8.dp,vertical=7.dp))
                        }
                        DropdownMenu(positionMenu,{positionMenu=false},modifier=Modifier.semantics { testTagsAsResourceId=true }) {
                            (1..game.rules.fielders).forEach { target -> DropdownMenuItem(text={Text(lineupPositionNames[target-1])},modifier=Modifier.testTag("lineup-position-option-$target"),onClick={
                                val map=positions.toMutableMap();val other=map[target]
                                if(pos!=null && other!=null) map[pos]=other else if(pos!=null) map.remove(pos)
                                map[target]=id;positionMenu=false
                                change(game.withLineup(home,positions=map,dh=game.dhAt(home)?.takeUnless { it==id && target!=1 || it==other && pos!=null && pos!=1 }))
                            }) }
                        }
                    }
                    TextButton({val ids=order.toMutableList();java.util.Collections.swap(ids,index,index-1);change(game.withLineup(home,ids))},Modifier.sizeIn(minWidth=30.dp,minHeight=44.dp).testTag("lineup-up-${index+1}").semantics { contentDescription="${player.name} 棒次前移" },enabled=index>0,contentPadding=PaddingValues(2.dp)){Icon(Icons.Outlined.KeyboardArrowUp,null,Modifier.size(24.dp))}
                    TextButton({val ids=order.toMutableList();java.util.Collections.swap(ids,index,index+1);change(game.withLineup(home,ids))},Modifier.sizeIn(minWidth=30.dp,minHeight=44.dp).testTag("lineup-down-${index+1}").semantics { contentDescription="${player.name} 棒次后移" },enabled=index<order.lastIndex,contentPadding=PaddingValues(2.dp)){Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.size(24.dp))}
                }
            }
        } }
        val defenseOnly=positions.filterValues { it !in order }
        if(defenseOnly.isNotEmpty()) CardBlock {
            Text("只参与守备",fontWeight=FontWeight.Bold)
            defenseOnly.forEach { (pos,id) -> val p=team.players.first { it.id==id };TextButton({profileID=id}){Text("${p.name} · #${p.number} · ${ScoringOptions.positions[pos-1]}")} }
        }
        if(game.rules.dhEnabled) CardBlock {
            Text("指定打击 DH",fontWeight=FontWeight.Bold)
            PlayerChoice("DH 球员",team.players.filter { it.id in order && (it.id !in positions.values || game.rules.twoWayEnabled && it.id==positions[1]) },game.dhAt(home)) { change(game.withLineup(home,dh=it)) }
            if(game.dhAt(home)!=null) TextButton({change(game.withLineup(home,dh=null))}){Text("取消 DH 身份")}
        }
        if(pregame && game.rules.mode==GameMode.slowPitch && bench.isNotEmpty()) {
            var addMenu by remember { mutableStateOf(false) }
            Box { TextButton({addMenu=true}){Text("增加打者／全员加入")};DropdownMenu(addMenu,{addMenu=false}) {
                bench.forEach { p -> DropdownMenuItem(text={Text("#${p.number} ${p.name}")},onClick={addMenu=false;change(game.withLineup(home,order+p.id))}) }
                DropdownMenuItem(text={Text("全员加入打序")},onClick={addMenu=false;change(game.withLineup(home,order+bench.map { it.id }))})
            } }
        }
        if(!pregame) CardBlock {
            Text("替补 · 包括已换下球员",fontWeight=FontWeight.Bold)
            if(bench.isEmpty())Text("暂无可替换球员",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
            bench.forEach { p -> TextButton({profileID=p.id}){Text("#${p.number} ${p.name}${if(p.id in game.exitedPlayers)" · 已换下" else ""}")} }
        }
    }
    profileID?.let { id -> team.players.firstOrNull { it.id==id }?.let { p -> PlayerProfileCorrection(p,team.source,{profileID=null}) { edited ->
        change(game.withLineup(home,team=team.copy(players=team.players.map { if(it.id==id)edited else it })));profileID=null
    } } }
    replacementID?.let { outgoing ->
        AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={replacementID=null},title={Text(if(pitchingOnly)"只换投手" else if(pregame)"更换先发球员" else "换人 · ${team.players.first { it.id==outgoing }.name}")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Text(if(pitchingOnly) "新投手接管投球，原投手继续担任 DH，双方棒次保持。" else if(pregame) "新球员接替本棒次与守位。" else "新球员接替原棒次、守位及垒位，双方原有统计保留。",fontSize=13.sp)
            bench.forEach { p -> TextButton({
                val newOrder=if(pitchingOnly) order else order.map { if(it==outgoing)p.id else it }
                val newPositions=if(pitchingOnly) positions.toMutableMap().apply { put(1,p.id) }
                    else positions.mapValues { if(it.value==outgoing)p.id else it.value }
                val newDH=if(pitchingOnly) game.dhAt(home) else game.dhAt(home)?.let { if(it==outgoing)p.id else it }
                change(game.withLineup(home,newOrder,newPositions,newDH));replacementID=null
            },Modifier.testTag("lineup-replace-with-${p.id}")){Text("#${p.number} ${p.name}")} }
        }},confirmButton={TextButton({replacementID=null}){Text("取消")}})
    }
    fieldingIncomingID?.let { incoming ->
        val player=team.players.first { it.id==incoming }
        AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={fieldingIncomingID=null},
            title={Text("进入守备 · ${player.name}")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                Text("选择接替的守备员。双方棒次保持，原守备员改为额外打者，场上仍为 ${game.rules.fielders} 人守备。",fontSize=13.sp)
                positions.toSortedMap().forEach { (position,outgoing) ->
                    val original=team.players.first { it.id==outgoing }
                    TextButton({
                        val revised=positions.toMutableMap().apply { put(position,incoming) }
                        change(game.withLineup(home,positions=revised))
                        fieldingIncomingID=null
                    },Modifier.testTag("lineup-fielding-replace-$outgoing")) {
                        Text("接替 ${original.name} · ${ScoringOptions.positions[position-1]}")
                    }
                }
            }},confirmButton={TextButton({fieldingIncomingID=null}){Text("取消")}})
    }

}

@Composable
private fun PlayerProfileCorrection(player:Player,source:String,dismiss:()->Unit,save:(Player)->Unit) {
    var chinese by rememberSaveable(player.id){mutableStateOf(player.chineseName ?: player.name)}
    var english by rememberSaveable(player.id){mutableStateOf(player.englishName)}
    var numbers by rememberSaveable(player.id){mutableStateOf(player.jerseyNumbers.joinToString(", "))}
    val list=numbers.replace('，',',').replace('/',',').split(',').map {it.trim()}
    val edited=player.copy(name=chinese.trim().ifBlank { english.trim() }.ifBlank { "${list.firstOrNull().orEmpty()}号球员" },chineseName=chinese.trim(),englishName=english.trim(),number=list.firstOrNull().orEmpty(),jerseyNumbers=list)
    val issue=runCatching{Roster.validate(edited)}.exceptionOrNull()?.message
    AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest=dismiss,title={Text("更正球员资料")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(chinese,{chinese=it},label={Text("中文名")},singleLine=true,modifier=Modifier.testTag("lineup-chinese-name"))
        OutlinedTextField(english,{english=it},label={Text("英文名")},singleLine=true,modifier=Modifier.testTag("lineup-english-name"))
        OutlinedTextField(numbers,{numbers=it},label={Text("背号")},singleLine=true,modifier=Modifier.testTag("lineup-numbers"))
        Text("支持 0、00、1–99，多个背号用逗号分隔。姓名可留空，按背号展示。保留同一球员的全部本场统计，已有逐球文字保留原文。",fontSize=12.sp)
        Text(if(source=="local") "保存阵容时同步本机球队名单，以后新比赛使用更正后的资料。" else "只更正本场比赛资料，共享球队原名单保持。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        issue?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton({save(edited)},enabled=issue==null){Text("完成")}},dismissButton={TextButton(dismiss){Text("取消")}})
}

@Composable
internal fun ParitySubstitutionEditor(game:Game,busy:Boolean,dismiss:()->Unit,save:(Command)->Unit) {
    var task by rememberSaveable { mutableStateOf("menu") }
    var selectedHome by rememberSaveable { mutableStateOf(true) }
    if(task=="lineup") { ParityLineupEditor(game,busy,{task="menu"},selectedHome,save);return }
    if(task=="double") { DoubleSwitchEditor(game,busy,{task="menu"},save);return }
    if(task!="menu") { MatchSubstitutionTask(game,task,busy,{task="menu"},save);return }
    ProductPage(title=if(game.isPregame)"双方赛前阵容" else "阵容与换人",busy=busy,onBack=dismiss,backLabel="返回比赛",modal=true) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(false,true).forEach { home -> OutlinedButton({selectedHome=home;task="lineup"},Modifier.weight(1f).testTag("edit-lineup-${if(home)"home" else "away"}"),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(12.dp)) { Column(horizontalAlignment=Alignment.CenterHorizontally) {Text(if(home)"主队阵容" else "客队阵容");Text(game.teamAt(home).shortName,fontSize=12.sp)} } }
        }
        Text("双方均可调整棒次、守位、姓名和号码",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(game.isPregame) LineupNotice("保存后返回开赛","保存双方赛前阵容，返回后确认 Play Ball 开赛。")
        else listOf(Triple("pitcher",if(game.rules.mode==GameMode.coachPitch)"替换 P 位守备" else "换投手","${game.fieldingTeam.name} · 当前 #${game.pitcher.number} ${game.pitcher.name}"),Triple("batter","代打","${game.battingTeam.name} · 替换 #${game.batter.number} ${game.batter.name}"),Triple("pinchRunner","代跑","选择垒位和替换球员"),Triple("fielder","守备换人","替换一名场上守备球员"),Triple("double","双重换人","一次确认两组球员、棒次和守位"),Triple("position","调整守位","交换球员的防守位置")).forEach { (key,title,detail) ->
            Surface(onClick={task=key},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("substitution-$key")) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold);Text(detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",fontSize=25.sp,color=MaterialTheme.colorScheme.primary)} }
        }
    }
}

@Composable
private fun MatchSubstitutionTask(game:Game,type:String,busy:Boolean,dismiss:()->Unit,save:(Command)->Unit) {
    var playerID by rememberSaveable(type){mutableStateOf("")};var outgoing by rememberSaveable(type){mutableStateOf("")};var pos by rememberSaveable(type){mutableIntStateOf(1)}
    var confirm by rememberSaveable { mutableStateOf(false) }
    val fielders=game.fieldingTeam.players.filter {it.id in game.fieldingPositions.values}
    val fieldingOrder=game.orderAt(game.isTop)
    val fieldBench=game.fieldingTeam.players.filter {!it.archived && it.id !in fieldingOrder && it.id !in game.fieldingPositions.values}
    val bench=game.battingTeam.players.filter {!it.archived && it.id !in game.battingOrder && it.id !in game.positionsAt(!game.isTop).values && it.id !in game.bases}
    val candidates=when(type){"pitcher"->fielders.filter {it.id!=game.pitcher.id}+fieldBench;"batter","pinchRunner"->bench;else->fielders}
    val title=when(type){"pitcher"->if(game.rules.mode==GameMode.coachPitch)"替换 P 位守备" else "换投手";"batter"->"代打";"pinchRunner"->"代跑";"fielder"->"守备换人";else->"调整守位"}
    val command=Command(type,playerID=if(type=="fielder")outgoing else playerID,replacementID=if(type=="fielder")playerID else null,position=pos)
    val issue=runCatching{MatchEngine.apply(game,command)}.exceptionOrNull()?.message
    ProductPage(title=title,busy=busy,onBack=dismiss,actionLabel="确认$title",actionTag="save-substitution",actionEnabled=playerID.isNotBlank()&&issue==null,onAction={confirm=true},modal=true) {
        LineupNotice(if(type=="batter"||type=="pinchRunner")game.battingTeam.name else game.fieldingTeam.name,when(type){"pitcher"->"当前投手：#${game.pitcher.number} ${game.pitcher.name}。已记录的投球和垒上跑者原投手责任保留。";"batter"->"当前打者：#${game.batter.number} ${game.batter.name}。新球员接替原棒次和守位，当前球数保留。";"pinchRunner"->"选择实际被替换的垒上跑者。新球员接替原棒次、守位及垒位。";"fielder"->"先选择场上球员，再选择接替其棒次与守位的替补。";else->"选择球员与新的守位，双方自动交换防守位置。"})
        if(type=="pinchRunner") {
            val bases=game.bases.mapIndexedNotNull { i,id->id?.let { (i+1).toString() to "${i+1} 垒 · ${game.battingTeam.players.first {p->p.id==id}.name}" } }.toMap()
            if(bases.isEmpty())Text("垒上没有跑者，有跑者时才能安排代跑。",color=MaterialTheme.colorScheme.onSurfaceVariant) else Choices("替换哪位跑者",bases,pos.toString()){pos=it.toInt()}
        }
        if(type=="fielder") PlayerChoice("被换下的守备员",fielders,outgoing){outgoing=it}
        Text(if(type=="position")"选择守备球员" else "选择接替球员",fontWeight=FontWeight.Bold)
        val options=if(type=="fielder")fieldBench else candidates
        if(options.isEmpty())Text("暂无可替换球员",color=MaterialTheme.colorScheme.onSurfaceVariant)
        options.forEach { p -> Surface(onClick={playerID=p.id},shape=RoundedCornerShape(12.dp),color=if(playerID==p.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("substitution-player-${p.id}")) {Row(Modifier.padding(15.dp)){Text("#${p.jerseyNumbers.joinToString(" / ")}",fontSize=14.sp,modifier=Modifier.widthIn(min=54.dp));Text(p.name,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(playerID==p.id)Text("✓",color=MaterialTheme.colorScheme.primary)} } }
        if(type=="position") Choices("改守位置",ScoringOptions.positions.take(game.rules.fielders).mapIndexed {i,s->(i+1).toString() to s}.toMap(),pos.toString()){pos=it.toInt()}
        if(playerID.isNotBlank())issue?.let {Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
    }
    if(confirm)AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={confirm=false},title={Text("确认$title？")},text={Text("${(game.home.players+game.away.players).firstOrNull {it.id==playerID}?.name.orEmpty()}将接替所选职责，保存后继续本场记录。")},confirmButton={TextButton({confirm=false;save(command)}){Text("确认保存")}},dismissButton={TextButton({confirm=false}){Text("继续选择")}})
}
