# 3.0 上传与提交审核

App、正式服务和网站已准备完成；Apple 分发验证、上传、提交审核与实际发布由你操作。

1. 打开 `output/releases/3.0/archives/BaseballMaster-3.0-1-final.xcarchive`，进入 Xcode Organizer。核对 Version **3.0**、Build **1**、Bundle ID **com.jasonchen.baseballmaster**、Team **JUTVG7XR9H**。这是本机签名归档，上传时选择 App Store Connect 分发，由 Xcode 按商店方式重新签名。若 Connect 已有 3.0 Build 1，则递增 Build 后重新归档，不能重复上传同一构建号。
2. 选择 Distribute App → App Store Connect，先 Validate，再 Upload。构建处理完成后，在已有 App（ID **6802063886**）下创建／选择 3.0 版本并选择该构建。不要新建另一款 App。
3. 按 `APPSTORE-METADATA.md` 填写；对应 TXT 可直接复制。上传 `screenshots/` 中匹配设备栏目的一套九张 PNG，按 01–09 排列。再按 `CREATIVE-ASSETS.md`，在「标题与搜索结果」分别选择 `creative-assets/upload/` 中对应原图，并用 Preview 核对裁切。
4. 对照 `APP-PRIVACY.md` 更新隐私问卷。此次增加可选云端共享，涉及设备 ID 和操作审计，不能继续填写不收集数据。核对支持、隐私和营销链接；使用真实审核联系方式。
5. 登录要求选择“不需要登录”。粘贴英文审核备注；审核人员能自行创建共享球队，无需等待过期邀请或使用共享账号。按 Connect 当前问题如实完成出口合规、年龄分级和版权等已有账户项目，不根据此文档臆填证书或授权。
6. 提交审核，按你的安排手动或自动发布。官网目前明确 3.0 准备审核，实际商店上架后再更新官网状态。

常用入口：球队 → 球队共享；共享球队 → 球队比赛与历史；统计 → 球队／年度；我的 → 备份与恢复。简要更新与使用说明 PDF 随包提供。

最终归档已完成 Release 构建、严格签名、版本、隐私文件及 dSYM UUID 核对。这里不代表 Apple 服务端已验证、已上传或已批准；无须再归档旧的 `BaseballMaster-3.0-1-release.xcarchive`，最终交付以 `-final` 为准。

2026-10-13 已更新最终归档，包含记录员新增／修改球员和每队最多两名管理员。使用当前 `-final.xcarchive`（App／dSYM UUID `4B72E9AA-35F4-38F7-9165-A79057698EA6`）；`-before-roles.xcarchive` 仅为旧档备份，不用于本次提交。
