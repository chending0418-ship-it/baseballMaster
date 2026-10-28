package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun AppSettingsScreen(model: ScorekeeperViewModel, dismiss: ()->Unit) {
    FormPage("App 设置",false,false,"","",dismiss,{},dismissLabel="返回") {
        Text("外观",fontWeight=FontWeight.Bold)
        Choices("App 外观",mapOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),model.appAppearance) {model.setAppearance(it)}
        Text("辅助提示",fontWeight=FontWeight.Bold)
        CardBlock {
            Row {Checkbox(model.showPracticeGuidance,model::setPracticeGuidance,modifier=Modifier.testTag("show-practice-guidance"));Column {Text("显示练习引导");Text("练习记分时显示下一步提示")}}
            Row {Checkbox(model.showRuleExamples,model::setRuleExamples,modifier=Modifier.testTag("show-rule-examples"));Column {Text("显示规则示例");Text("常用规则条目中显示简短场景")}}
        }
    }
}

@Composable internal fun AppAboutScreen(dismiss: ()->Unit) {
    val browser=LocalUriHandler.current
    var failedUrl by remember {mutableStateOf<String?>(null)}
    fun open(url: String) {runCatching {browser.openUri(url)}.onFailure {failedUrl=url}}
    FormPage("关于",false,false,"","",dismiss,{},dismissLabel="返回") {
        Image(painterResource(R.drawable.launch_app_icon),"棒球比赛记录大师",Modifier.size(56.dp))
        Text("BaseballMaster",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Android ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        CardBlock {Text("为现场记分而设计",fontWeight=FontWeight.Bold);Text("帮助非专业记分员用更直观的方式记录比赛，并查看球队、球员与年度表现。")}
        CardBlock {
            Text("隐私与数据",fontWeight=FontWeight.Bold)
            Text("本机使用无需注册账号。你确认共享的球队资料和名单上传到 baseballmaster.cc 自有服务，队内成员按授权访问；本机原稿保持独立。报表与备份由你主动导出或分享。应用启动时异步检查公共开屏配置，失败使用本机缓存或内置内容。")
            TextButton(onClick={open("https://baseballmaster.cc/privacy.html")},modifier=Modifier.testTag("about-privacy-policy")) {Text("查看完整隐私政策")}
            TextButton(onClick={open("https://baseballmaster.cc/support.html")},modifier=Modifier.testTag("about-support")) {Text("技术支持与使用指南")}
            failedUrl?.let {url -> Text("无法打开浏览器，请复制链接后用浏览器打开：");SelectionContainer {Text(url)}}
        }
        Text("内置规则资料：《棒球规则 2022 版》，中国棒球协会审定。比赛判罚应以现场裁判及赛事最新规定为准。")
    }
}

@Composable internal fun LocalDataScreen(model: ScorekeeperViewModel, manageTeams: ()->Unit, dismiss: ()->Unit) {
    FormPage("本地数据",model.busy,false,"","",dismiss,{},dismissLabel="返回") {
        Text("本机记分、统计、报表和备份可离线使用。共享资料保留独立缓存，最新授权和修改结果需联网核对。")
        CardBlock {
            Text("当前数据",fontWeight=FontWeight.Bold)
            Text("本队 ${model.teams.size} · 对手 ${model.rosters.count {it.isOpponent && !it.archived}}")
            Text("现役球员 ${model.rosters.filter {!it.archived}.sumOf {team -> team.players.count {!it.archived}}}")
            Text("比赛记录 ${model.games.size} · 进行中 ${model.games.count {!it.isFinal && !it.isScheduled}}")
            TextButton(onClick=manageTeams,modifier=Modifier.testTag("manage-roster-from-profile")) {Text("管理球队、对手与球员")}
        }
        CardBlock {
            Text("备份与恢复",fontWeight=FontWeight.Bold)
            Text("可核验 iOS 备份、导入名单或受来源保护的比赛。球队档案文件需单独另存；完整可编辑互导仍未完成。")
            DeviceBackupControls(model);BackupInspection(model);TeamArchiveControls()
        }
    }
}
