# 棒球比赛记录大师 3.1.5 送审材料

Bundle ID 为 `com.jasonchen.baseballmaster`。3.1.5（Build 1）已于 2026-11-10 上传到 App Store Connect，作为 TestFlight 对照。本页审核备注按下一候选的语音流程准备，归档后再填写实际 Build 编号和审核表单。

- 「此版本的新增内容」取 [简体中文更新说明](whats-new-zh-Hans.txt)。
- 「App 审核信息 → 备注」取 [中文审核备注](review-notes-zh-Hans.txt) 或 [英文审核备注](review-notes-en.txt)。提交前由管理员在总 Admin 手动生成未兑换、有效期覆盖审核的审核码，替换私有审核备注中的占位符。重审时另发一个未兑换的码。测试码只放私有审核信息。
- 「隐私政策」取记录大师专用链接。中英文分别为 [中文版](https://baseballmaster.cc/scorekeeper/privacy.html) 和 [英文版](https://baseballmaster.cc/scorekeeper/privacy-en.html)。先完成 [语音隐私核对](PRIVACY-REVIEW.md)，再送审。
- 「截屏」从交付包的对应设备目录上传。总览只供评审。

| 目录 | 分辨率 | 图片数 |
| --- | --- | ---: |
| `iphone-6.3` | 1206 × 2622 | 3 |
| `ipad-13` | 2064 × 2752 | 2 |
| `duo-outer` | 1398 × 2034 | 1 |
| `duo-inner` | 2853 × 2007 | 1 |

所有商店图为无透明通道 RGB PNG。尺寸对应 [Apple 截屏规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)。取实际候选 App 模拟器截图，使用合成球队与比赛，UI 等比显示。单产品标志直接取品牌 1.3 官方横版 SVG，浅底深绿字、深底反白，图标与完整中文名一并保留。输出 `manifest.json` 记录素材和成图的 SHA-256。总览、源截图与生成器分别保留。

可编辑源稿为 [图文内容](artwork.json) 和 [渲染器](../../../../scripts/release/render-appstore-3.1.5.cjs)。运行时将 `BASEBALLMASTER_BRAND_ROOT` 指向工作区的 `brand/specifications/1.3`，将素材放到本工作树的 `output/releases/3.1.5/app-store/source`。

## 提交前

1. 更新公开隐私政策并核对 Connect 隐私问卷。App 在每次录音前显示数据用途和接收方，由用户明确同意。
2. 用模拟器配合实际 OBS 检查转播，部署固定源修复。审核期间共享球队和语音服务均需可用。
3. 用最终候选构建复跑三端模拟器回归，正式归档后核对版本、Build、签名与最终界面。上传前填好未兑换的审核码并复核联系资料。
