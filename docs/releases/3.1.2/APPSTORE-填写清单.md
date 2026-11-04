# App Store 填写与宣传资料总清单

更新：2026-11-04。对应 iOS 3.1.2（Build 1）；仅 App Store 版本字段与审核备注需要版本号，公开宣传不写版本号。

## 按字段取用

| App Store Connect 字段 | 直接取用 |
| --- | --- |
| 名称 | 棒球比赛记录大师 |
| 副标题 | 棒球慢垒记分、球队共享与统计 |
| 宣传文本 | [复制本文件全文](promotional-text-zh-Hans.txt) |
| 描述 | [复制本文件全文](description-zh-Hans.txt) |
| 此版本新增内容 | [复制本文件全文](whats-new-zh-Hans.txt) |
| 关键词 | [复制本文件全文](keywords-zh-Hans.txt)，不额外加空格或文件名 |
| App 审核信息 → 备注 | [英文主稿](review-notes-en.txt)；[中文对照](review-notes-zh-Hans.txt)，二者择一，不同时粘贴 |
| 需要登录 | 否；不填虚构审核账号 |
| 营销 URL | https://baseballmaster.cc/scorekeeper |
| 支持 URL | https://baseballmaster.cc/scorekeeper/support.html |
| 隐私政策 URL | https://baseballmaster.cc/scorekeeper/privacy.html |
| 隐私选择 URL（如填写） | https://baseballmaster.cc/scorekeeper/privacy.html#rights |
| 英文支持 | https://baseballmaster.cc/scorekeeper/support-en.html |
| 英文隐私 | https://baseballmaster.cc/scorekeeper/privacy-en.html |
| App 备案 | 沪ICP备2026058730号-2A；用户已确认商店配置完成 |
| 审核邮箱 | chending0418@gmail.com |

以上网页已部署上海并通过匿名 HTTPS；首页底部网站备案为沪ICP备2026058730号-1，两种编号不要混填。旧隐私／支持地址继续可读。

## 商店图片

取 `output/releases/3.1.2/AppStore截图.zip`，共 11 组 34 张。解压后按 Connect 实际设备栏目选择对应子目录：iPhone 三档、Duo 内外屏横竖四档、iPad 两种分辨率横竖四档。每档图片按文件名前的数字排序；不要上传 overview.png 或 index.html，不把不同设备尺寸混为一组。详见 [尺寸表](APPSTORE-ARTWORK.md)。App 预览视频本轮未制作，PNG 上传到截屏栏。

## App 隐私

根据 [逐类填写依据](APP-PRIVACY.md) 核对姓名、其他用户内容、用户 ID、设备 ID、产品交互五项；用途为 App 功能、与身份关联、不用于追踪。不要选择整款 App“不收集数据”。服务处理及最终商店问卷须与当前上海隐私正文、最终构建保持一致。

## 宣传取用

- 小红书：`output/marketing/3.1.2/xiaohongshu/2026-11-04/宣传图片与文案.zip`，6 张 PNG/JPG 与可复制正文。
- 微信：`output/marketing/3.1.2/wechat/2026-11-04/宣传图片与文案.zip`，一页 PNG/JPG 与可复制正文。
- 官网已用四设备透明底效果图；社交海报保留完整摄影背景。四台为 iPad、iPhone、Duo、Android 手机，折叠主视觉只留 Duo。
- 当前素材已排除被指认的真实未成年人资料；不使用旧 Android 比赛、名单或统计截图。所有宣传无版本号，Android标注测试中。

## 提交前仍由实际发布流程完成

这批文字、图片和网页可以取用，未代您填写 App Store Connect、上传新构建或提交审核。审核联系人姓名／电话、Copyright、类别、分级、定价和发布方式沿用并核对真实账户信息，不填占位值。iOS 最终签名归档／上传仍待执行；共享球队批量导入后台尚未更新，当前文案已注明需服务支持，本机／对手批量不依赖该更新。社交宣传安排在相应 App 功能实际可下载后发布。
