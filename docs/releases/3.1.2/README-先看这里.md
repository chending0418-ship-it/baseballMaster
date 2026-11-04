# iOS 3.1.2 发布资料取用

2026-11-04。本轮准备商店图片、审核文字、隐私／支持内容、官网策划，以及双平台社交宣传。工程仍为 3.1.2（Build 1）。全部对外宣传图片和文案不展示版本号；内部目录、审核元数据与验证记录保留版本用于追溯。

## 六项交付

| 用户事项 | 本轮完成内容 | 入口／取用 |
| --- | --- | --- |
| App Store 产品图 | 原有三组 iPhone 加 Duo 四组、iPad 四组，共 11 组 34 张 PNG；保持真实 UI 与比例 | [尺寸与来源](APPSTORE-ARTWORK.md)、`output/releases/3.1.2/app-store/index.html` |
| 全部发布审核文字 | 宣传文本、描述、关键词、更新说明、中英文审核备注、账户待填项与隐私申报依据 | [元数据](APPSTORE-METADATA.md)、[审核交接](REVIEW-HANDOFF.md)、[隐私申报](APP-PRIVACY.md) |
| 新 privacy／support | 中英文完整内容、新产品专属目标路由、旧路径兼容与上海后替换段 | [网址与网页交接](WEB-CONTENT-HANDOFF.md) |
| 官网介绍 | 首屏、iPad、Duo、Android 折叠、名单、落点、FAQ 与上线门槛，只有策划 | [官网策划](../../design/marketing/3.1.2/web/website-plan.md) |
| 小红书图片与文案 | 6 张 1080×1440；四设备摄影产品主视觉（iPad、iPhone、Duo、Android 手机）与经审查的独立功能页；含 Android 测试版宣传 | [文案与边界](../../design/marketing/3.1.2/social/copy.md)、`output/marketing/3.1.2/xiaohongshu/2026-11-04/` |
| 微信一页图 | 1080×1800 PNG／JPG，介绍两平台及折叠支持，无版本号 | `output/marketing/3.1.2/wechat/2026-11-04/` |

总取用页：`output/releases/3.1.2/index.html`。总包：`output/releases/3.1.2/发布准备资料.zip`，仅包含本轮可取用图片与文字，不含私有原件、旧拒绝稿或独立官方机壳素材。

## 网站目标

- 中文隐私：`https://baseballmaster.cc/scorekeeper/privacy.html`
- 中文支持：`https://baseballmaster.cc/scorekeeper/support.html`
- 英文隐私／支持：同目录 `privacy-en.html`／`support-en.html`

**以上是待上线的新目标地址，不是已发布链接。** 旧 `/privacy.html` 与 `/support.html` 规划保留 HTTP 200 同等内容兼容 App 现有硬编码。本轮不改 App 链接、不改官网源码、不部署；正式上海切换完成后再核对实际环境、HTTPS 与公开内容，然后填写新网页地址；备案已通过且用户已确认商店备案配置完成。

## 本轮验证

- 34 张商店 PNG、6 张小红书 PNG／JPG 与微信 PNG／JPG：尺寸、无透明、图片加载、文字边界、标题无标点和输出 SHA-256 均检查；分组与单张视觉复核完成。社交新版 6 张与微信均逐张打开复核。
- 小红书正文／图片无版本号、网址、下载提示、联系信息或二维码；Android 明确测试状态。对外图片都不含版本号；版本字段只留审核和内部追溯。
- 主海报用内置 ImageGen 保留原摄影构图最小编辑为四设备产品效果图，并标注效果图；真实开机画面、无球员资料的 Android 规则页及泛化演示标记避免使用真实姓名。单功能页机身及界面来源可追溯，不以外壳或效果图表示真机验收。
- Apple 许可由用户明确同意后提取，原壳与蒙版排除 Git 和交付 ZIP。旧简化示意稿移入本机输出的 `drafts/illustrated-rejected/` 和源稿 archive，不再交付。
- 商店文本按 Apple 当前字符／UTF-8 字节上限检查；中英网页及内部链接核对。完整记录由 `scripts/release/package-release-3.1.2.py` 写入 `output/validation/3.1.2/release-materials-2026-11-04/final-validation.json`。
- 本轮没有重新构建 App、执行 App 功能／真机测试、正式归档或修改生产。截图从既有本版验证附件和原生显示屏导出，不将复用验证说成重新测试。

## 保留到发布阶段的事项

备案 `沪ICP备2026058730号-2A` 已通过、商店备案配置据用户确认完成。本轮尚需核对其余 App Store 账户字段、最终签名归档、真实旧比赛核对、共享批量服务上线、实际上海迁移、公开政策的环境与保留期限、网页可达验证、上传／审核／上架均仍待相应真实流程。App 预览视频未制作，用户列举的视频尺寸已独立记录，不将 PNG 算作视频。

本轮素材不表示 iOS 已上架新功能；社交稿待相应功能实际可下载后传播。Android 采用当前官网测试包的已验收界面，不把尚未分发的新包写成已发布。两端互导及全部云端能力一致未完成，不作跨平台数据完全同步的宣传承诺。

私人资料更正：旧 Android 测试截图含真实未成年人资料，先前“合成资料”判断错误，全部撤出当前资产树与交付包。当前 Android 仅使用无姓名、无球队资料的通用规则／设置截图；球员落点单页改用 iOS 张三演示记录。41 张当前 PNG 执行本地 OCR 排除检查，未命中被禁止的名单；人工复核仍是必要检查。

材料源稿：[Draft PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41)，目标 main，未合并。网页／服务／商店与社交平台没有因本材料 PR 而部署、上传或发布。
