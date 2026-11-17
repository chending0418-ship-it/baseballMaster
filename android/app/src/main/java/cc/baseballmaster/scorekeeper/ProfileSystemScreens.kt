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
import cc.baseballmaster.core.WebsiteAndroidRelease

@Composable internal fun AppSettingsScreen(model: ScorekeeperViewModel, dismiss: ()->Unit) {
    FormPage("App 设置",false,false,"","",dismiss,{},dismissLabel="返回") {
        CardBlock {
            Choices("默认记录方式", RecordingMethodLabels, model.defaultRecordingMethod, model::setDefaultRecordingMethod)
            Text("用于新建比赛和安排赛程，每场仍可单独选择。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("外观",fontWeight=FontWeight.Bold)
        Choices("App 外观",mapOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),model.appAppearance) {model.setAppearance(it)}
        Text("辅助提示",fontWeight=FontWeight.Bold)
        CardBlock {
            ProductToggleRow("显示练习引导", "练习记分时显示下一步提示", model.showPracticeGuidance, "show-practice-guidance", model::setPracticeGuidance)
            ProductToggleRow("显示规则示例", "常用规则条目中显示简短场景", model.showRuleExamples, "show-rule-examples", model::setRuleExamples)
        }
    }
}

@Composable internal fun AppAboutScreen(model: ScorekeeperViewModel, dismiss: ()->Unit) {
    val browser=LocalUriHandler.current
    var failedUrl by remember {mutableStateOf<String?>(null)}
    fun open(url: String) {runCatching {browser.openUri(url)}.onFailure {failedUrl=url}}
    FormPage("关于",false,false,"","",dismiss,{},dismissLabel="返回") {
        Image(painterResource(R.drawable.launch_app_icon),"棒球比赛记录大师",Modifier.size(56.dp))
        Text("BaseballMaster",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Android ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        val release = model.websiteRelease
        if (release != null && release.build > BuildConfig.VERSION_CODE) WebsiteUpdateNotice(release)
        else if (model.updateCheckFailed) Text("暂时无法检查官网版本，请稍后重试。")
        else if (release != null && release.build < BuildConfig.VERSION_CODE) Text("官网当前提供 ${release.version}。")
        else if (release != null) Text("已是官网最新版本。")
        TextButton(onClick = { model.checkForUpdate(manual = true) }, enabled = !model.checkingUpdate,
            modifier = Modifier.testTag("check-website-update")) {
            Text(if (model.checkingUpdate) "正在检查…" else "检查更新")
        }
        CardBlock {Text("为现场记分而设计",fontWeight=FontWeight.Bold);Text("帮助非专业记分员用更直观的方式记录比赛，并查看球队、球员与年度表现。")}
        CardBlock {
            Text("隐私与数据",fontWeight=FontWeight.Bold)
            Text("本机使用无需注册账号。你确认共享的球队资料和名单上传到 baseballmaster.cc 自有服务，队内成员按授权访问；本机原稿保持独立。报表与备份由你主动导出或分享。应用异步读取公共开屏配置和官网下载版本信息。")
            TextButton(onClick={open("https://baseballmaster.cc/privacy.html")},modifier=Modifier.testTag("about-privacy-policy")) {Text("查看完整隐私政策")}
            TextButton(onClick={open("https://baseballmaster.cc/support.html")},modifier=Modifier.testTag("about-support")) {Text("技术支持与使用指南")}
            failedUrl?.let {url -> Text("无法打开浏览器，请复制链接后用浏览器打开：");SelectionContainer {Text(url)}}
        }
        Text("内置规则资料：《棒球规则 2022 版》，中国棒球协会审定。比赛判罚应以现场裁判及赛事最新规定为准。")
    }
}

@Composable
internal fun WebsiteUpdateNotice(release: WebsiteAndroidRelease, onDismiss: (() -> Unit)? = null) {
    val browser = LocalUriHandler.current
    var showDownloadLink by remember { mutableStateOf(false) }
    CardBlock(Modifier.fillMaxWidth().testTag("website-update-available")) {
        Text("可更新至 ${release.version}", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                runCatching { browser.openUri(WebsiteAndroidRelease.downloadPage) }
                    .onFailure { showDownloadLink = true }
            }, modifier = Modifier.testTag("open-website-download")) { Text("前往官网下载") }
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("暂不提醒") }
        }
        if (showDownloadLink) SelectionContainer { Text(WebsiteAndroidRelease.downloadPage) }
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
            Text("导入名单、查看 iOS 比赛和只读球队档案。原文件会保留，球队共享权限不会随文件恢复。")
            DeviceBackupControls(model);BackupInspection(model);TeamArchiveControls()
        }
    }
}
