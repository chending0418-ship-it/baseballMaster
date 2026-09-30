# 2.1 App Store 文案

更新：2026-09-30。文案对应当前 2.1，不包含成人慢垒。公开商店当前仍为 2.0；以下资料尚未由本任务写入 App Store Connect。

## 可直接复制的字段

| Connect 字段 | 内容／文件 |
| --- | --- |
| 版本 | `2.1` |
| 名称 | `棒球比赛记录大师`（沿用） |
| 副标题建议 | `文字直播与历史纠错，记录每个打席` |
| 本版本新增内容 | [whats-new-zh-Hans.txt](whats-new-zh-Hans.txt) |
| 宣传文本 | [promotional-text-zh-Hans.txt](promotional-text-zh-Hans.txt) |
| 描述 | [description-zh-Hans.txt](description-zh-Hans.txt) |
| 关键词 | [keywords-zh-Hans.txt](keywords-zh-Hans.txt) |
| 审核备注 | [review-notes-en.txt](review-notes-en.txt) |
| 营销 URL | `https://baseballmaster.cc/` |
| 技术支持 URL | `https://baseballmaster.cc/support.html` |
| 隐私政策 URL | `https://baseballmaster.cc/privacy.html` |
| 隐私选择 URL（若填写） | `https://baseballmaster.cc/privacy.html#rights` |
| 审核联系邮箱 | `chending0418@gmail.com`；姓名、电话使用账户中的真实资料 |
| 登录要求 | 无需登录，无需提供测试账号 |

英文审核备注已给出可现场创建的测试流程。直播按一小时到期规则删除，不使用永久示例链接作为审核凭据。工程 Build 当前为 1，备注不固定写死 Build，以实际上传构建为准。

Apple 当前字段上限：名称／副标题各 30 个字符，宣传文本 170 个字符，描述和更新说明各 4,000 个字符，关键词 100 字节，审核备注 4,000 字节。文件为纯文本；复制关键词时不要把文件末尾换行粘入字段。参考 [Apple 版本信息字段](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information)。

## 截图与宣传图

直接上传昨天的 `output/releases/2.1/app-store/iphone-6.9/` 七张图，备用尺寸在 `iphone-6.5/`。不上传 `overview.png`，本次不重制这两套图片。顺序和来源见 [截图说明](APPSTORE-ARTWORK.md)。

新增宣传海报是官网／社交媒体的更新介绍图，位于 `output/marketing/2.1/release-poster/baseballmaster-2.1-update.png`；它不是新增 App Store 设备截图。按用户要求，海报文案为“2.1 正式上线”，供 App Store 上架后发布使用；准备该素材不代表现在已经上架。

App 隐私标签与年龄分级按真实功能填报，见 [隐私申报](APP-PRIVACY.md) 和 [提交交接清单](REVIEW-HANDOFF.md)。文案更新不代替这些问卷。
