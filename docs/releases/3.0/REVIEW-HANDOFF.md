# 3.0 上线与审核交接

2026-10-13。**App 3.0（Build 1）的既定开发、验证及签名归档已完成；网站和配套协作服务已部署。Apple 上传、提交审核及商店发布由用户执行，目前未提交。**

## 先看这次更新

- **球队共享**：在「球队」邀请加入，无需注册；球员姓名、背号、比赛资料跨设备同步，仍可纯本机使用。管理员和记录员都可新增、修改球员，每队最多两名管理员。
- **比赛同步和交接**：不同记录员可记录同队比赛；同一场先同步、双方核对再交接，断线不自动换人。
- **历史观看权限**：实时直播仍持有效链接可看；历史另外选择永久开放、指定人员或不开放网页。关闭网页仍保留队内 App 查看。
- **年度与旧比赛**：按比赛日期自动归年，支持导入本机历史比赛和核对球员对应。
- **记分与设备细节**：补填／更正比赛时间、选人显示背号、确认换投；支持设备配对、管理恢复和完整档案导出。

“名单”就是球队的球员姓名、背号等资料；不是需要另外学习或执行的一项验收操作。原来的四栏导航、球场和记分操作保留。击球类型、落点图及常用局面改版留到 3.1 考虑；iPad／Duo、小组件与 Android 不属于本版。

## 交付文件

| 文件 | 位置／说明 |
| --- | --- |
| 完整材料 ZIP | `output/releases/3.0/BaseballMaster-3.0-AppStore.zip`；同内容版本副本在本目录 |
| 简要更新和使用 PDF | 包内 `BaseballMaster-3.0-更新与使用说明.pdf`，三页；第 3 页为升级后成为管理员及导入本机比赛的操作步骤 |
| 最终签名归档 | `output/releases/3.0/archives/BaseballMaster-3.0-1-final.xcarchive`；单独交付，不塞进截图资料 ZIP |
| 上传步骤 | [UPLOAD-GUIDE.md](UPLOAD-GUIDE.md) |
| 可复制文案 | [APPSTORE-METADATA.md](APPSTORE-METADATA.md) 及对应五份 TXT |
| 隐私问卷依据 | [APP-PRIVACY.md](APP-PRIVACY.md)，3.0 云端行为需更新 Connect |
| 商店截图 | 三套各九张实际界面，尺寸／用法见 [APPSTORE-ARTWORK.md](APPSTORE-ARTWORK.md) |
| 标题与搜索创意素材 | 两张标题、三张搜索 PNG，已加入完整包；独立包 `BaseballMaster-3.0-AppStore-Creative.zip` 含可编辑源文件，见 [使用说明](CREATIVE-ASSETS.md) |
| 截图总览 | `output/releases/3.0/app-store/overview.png` 与 `index.html` |
| 宣传海报 | `output/marketing/3.0/release-poster/baseballmaster-3.0-update.png`，1440×1920 |
| 验证与容量 | [ACCEPTANCE.md](ACCEPTANCE.md)、[CAPACITY.md](CAPACITY.md) |
| 服务运维 | [OPERATIONS.md](OPERATIONS.md)，密钥另行保管，不在包内 |

工程 `/Users/JasonChan/Documents/BaseballMaster/BaseballMaster.xcodeproj`，Scheme BaseballMaster，Bundle ID `com.jasonchen.baseballmaster`，Team `JUTVG7XR9H`，App Store ID `6802063886`。最低系统 iOS 16 保持不变，界面开发和验收按用户指定的 iPhone 16 及更新机型。

## 已验证与已上线

此前 274 个唯一 App 单元／集成方法成功，本次新增记录员草稿回归后共 275 个；原 11 个关键原生流程基础上，本次完成记录员编辑及两管理员上限界面验证。协作服务最新 77 项全部通过，原直播沿用 17 项通过结果。最后通过的结果包、前批证据和 49 场景映射见 ACCEPTANCE；没有把中途失败、专用测试跳过或重跑重复计数写成通过。最终 Release 签名归档已重新生成；版本仍为 3.0（1），签名、隐私文件及 dSYM UUID 已核对，当前 UUID 为 `4B72E9AA-35F4-38F7-9165-A79057698EA6`。本次证据在 `output/validation/3.0/roster-permissions/`。

[官网](https://baseballmaster.cc/) · [技术支持](https://baseballmaster.cc/support.html) · [隐私](https://baseballmaster.cc/privacy.html) · [协作健康](https://baseballmaster.cc/api/v3/health) · [3.0 海报](https://baseballmaster.cc/assets/baseballmaster-3.0-update.png)。

正式 HTTPS 服务完成 20 场 App 生成的完整合成比赛、30 并发历史读取、原生客户端同步及档案导出，程序重启保留完整资料。独立 COS 加密备份和隔离恢复通过，后来删除的比赛与已撤销权限不会复活。旧实时直播保持原服务。官网 59 文件逐字节核对、36 组页面／视口／主题检查通过。

20 场样本正文加网页投影约 **16.8 MB**；数据库索引、草稿、审计和备份另算。公网有波动，30 并发读取 P95 约 1.48 秒；不是所有地区或蜂窝网络的速度保证。实际 30 天增长和云套餐账单需运行期观察；未更改云资源套餐。系统监测已配置为 systemd 状态／日志，没有外部通知渠道。更多测量边界已写入 CAPACITY 和 OPERATIONS。

本次仍在 `codex/dev-3.0`，全部开发及资料保留在当前工作区；没有额外推送或合并，也没有替用户登录 Connect、上传或提交审核。

## 本次权限补充（2026-10-13）

记录员可新增、修改正式球员姓名和背号。成员、角色、球队资料、停用／恢复球员及解散仍由管理员管理；原有负责场次的历史观看权限不变。每队最多两名有效管理员，在「共享球队 → 成员管理」设置；同一管理员的多台配对设备不重复计数。达到上限后先调整现有管理员角色再设置新管理员，最后管理员保护保留。

正式服务已切换至 `3.0-20261013-roles`，部署前备份完成，旧版本保留。生产合成验证确认记录员增改同步、越权拒绝、第二管理员可管理、第三名拒绝及降级后释放名额；测试球队已解散。官网首页、支持与隐私说明已同步上线；两页 PDF、审核文案与 ZIP 已更新。新的 `-final.xcarchive` 取代旧最终归档，旧档重命名 `-before-roles` 留作备份，提交请只用当前 `-final`。

## 你的最后操作

1. 打开 `-final.xcarchive`，在 Xcode Organizer 按 App Store Connect 分发方式验证并上传。
2. 选择 3.0 构建，复制文案、上传一套对应尺寸的九张图；在「标题与搜索结果」分别选择新增创意素材并预览，按隐私说明核对问卷和真实审核联系方式。
3. 提交审核并安排上架；App 实际发布后再将官网“准备审核”状态更新为可下载。

审核不需要账号或固定邀请；审核人员可自行创建共享球队。设备、模拟器、浏览器和灾备验证限制均如实记录，不转交用户代做开发测试。
