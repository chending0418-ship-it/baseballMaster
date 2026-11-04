# AN04 非空检查点及扩展历史导入（2026-10-28）

这是实际安卓导入读取功能增量，**AN04 完整可编辑互导仍未完成；AN07 不执行**。与前组只检查 `AppleStateProjection` 不同，本组已经通过 `AppleSourceBackup.prepare` 产品门面读取和重放更多原始历史。没有开放新的来源写入、共享记分或原生正式 iOS 导出入口。

## 实现与保留

- `AppleReplayPrototype` 保留历史类名，改用已验证的状态读取器初始化非空检查点，接受 `legacyPrefix`。原有比分、统计、球数、责任投手／打者、阵容、跑者、时间和延长赛状态作为基线，不清零重算前缀，不为旧事件伪造操作。
- 在原来 pitch/play/runner/基础换人等基础上读取 illegalPitch、decision、extraInning、enableTB、placeTB、swapOpeningSides、note、clock、droppedThirdStrike、doubleSwitch、lineup、situation、review、violation、adjudication。**有解码分支不等于所有变体通过跨端验证**；本组 109 份冻结用例实际覆盖见下一节，clock/note/adjudication/enableTB/swap 等未在此批冻结动作中覆盖。
- review 根据完整原始 journal 的 eventIDs 查所属操作；找不到或不同操作重复拥有同一事件直接拒绝。不假定操作编号就是事件编号。
- 当前和旧审计 `previousJournal` 分别初始化、重放；保留原始修订、原因、摘要和操作身份。完整原始事件／日志／打席／检查点 Data／纠错审计仍由原始备份字节保存，Native Game 是阅读和核对结果，不用于重写原历史。
- 当前 StoredGame 的 updatedAt／revision／playedAt 等外层元数据不放入旧 GameState 检查点。否则追加一球会悄悄改变之前审计的 Native checkpoint。本组修复了该实现回归。
- 原始 Swift 有时保存全零统计行，Native 不创建同一行。按现有官方统计基准将缺省零与显式全零视为等价，非零值必须严格匹配；原始字典原文仍保留。没有删除来源文件中的零行。

## 实际证据

沿用 [交互核心对照](AN04-INTERACTIVE-2026-10-28.md) 的原始 Swift session。109 份用例的 598 帧当前动作结果一致；488 帧具备 journal：

- 487 帧 Kotlin journal 重放通过原始冻结基准；再把真实 Swift `storedGame` 包装到新的测试 envelope，通过实际 `AppleSourceBackup.prepare` 导入，487 份均逐字段一致、原 envelope 字节导出不变。
- 已知“漏记换投／阵容调整”第二次来源重放三字段差异 1 帧：Kotlin 读取拒绝，不能把不一致统计覆盖到资料库，也不把它计入 487 份成功。原始 Swift 的该差异继续单列，不改全局或 iOS。
- 7 次纠错／撤回、2 次复核及 6 次 undo/redo 的真实结果沿用前组执行；新门面检查覆盖实际产生的各帧完整来源记录。
- 3 项新增普通核心测试：合成非空 prefix 边界保留分数／球数／统计；篡改当前比分不能越过原检查点；外层修订和更新时间变化不改旧检查点。
- 1 项新增 API31 Android JVM＋Native SQLite 测试：非空 prefix 导入、幂等、Android 格式 2 备份恢复、原／当前文件字节一致，不支持的编辑被拒绝且没有 Room／操作流水写入。这是合成边界和本机数据库检查，不是实际旧 iOS 安装或 UI 测试。

最终 **296 项常规（161 core＋63 协作服务＋8 直播服务＋64 app），另显式 Swift session 1 项，均 0 failures/errors/skipped**。App／AndroidTest 构建通过；lint 0 errors、19 warnings。任务 UP-TO-DATE 时复用相同输入结果，不宣称每个任务都重新执行。协作／直播服务只用现有 Node 服务的 127.0.0.1 动态端口与内存 SQLite。

证据：`output/validation/android/an04-journal-read/{final.log,results.json,freeze/}`；原始 Swift 编译和逐帧完整状态仍在 `an04-interactive/swift-build-second`、`an04-interactive/sessions`，这些输出不随 Git 推送。可复现源码及命令见前组；最后核对额外执行普通 core、两个隔离服务、app JVM、assembleDebug、assembleDebugAndroidTest 和 lintDebug。

## 失败记录与继续边界

首次扩展出现 8 个读取用例失败，原因是全零字典行与缺省行的比较；按已有冻结统计口径处理后通过。原有续记／研究 fixture 又暴露外层更新时间／修订渗入旧检查点；修复并重跑旧核心和 Room 全套后通过。失败日志 `an04-interactive/journal-first.log`、`journal-second.log` 保留，不能当成最终通过日志。

原始来源仍仅能执行之前明确支持的受限投球续记。非空／legacy prefix 本组只读。`additionalErrors` 独立命令、`unavailable`、未覆盖的复杂阵容／日期规则、任意旧个人记录、真实 v5、`.bmteam` 可编辑映射、已有跨端比赛更新合并仍待继续；不支持或重放不一致时整批拒绝导入，不偷偷跳过比赛或清空统计。原始 Native→iOS writer 的多事件图和完整审计转换仍是 AN04 主缺口，AN05 正式共享发布／离线编辑依赖它。

Swift／现有服务／公共网站均未修改；未运行 UI／AN07、iOS App、双端真实协作、生产或发布。构建输出仅本地校验，没有分发 APK。
