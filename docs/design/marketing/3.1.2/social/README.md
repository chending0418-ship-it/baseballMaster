# 宣传源稿与当前交付

2026-11-04。当前仅发布准备，没有对外发布。宣传图片和正文无版本号，Android 保留测试状态。

## 当前设计

- 封面及微信采用四设备产品组合：iPad、iPhone、iPhone Duo、Android 手机。保留原摄影构图、光影与比例；主视觉仅一台折叠设备 Duo，接近展开且折痕轻。
- 四块屏幕分别展示 iPad 工作台、iPhone 原有 BM 开机画面、Duo 记录和 Android 通用比赛规则。iPad／Duo 画面只用演示队和球员字母标记，不使用真实姓名。
- 产品组合由内置 ImageGen 最小编辑生成；[最终编辑提示](design-review/four-device-revision-prompt.txt)及[资源来源](assets/hero/provenance.json)保留。主海报标“产品效果图”，不能作为商店真实截图或真机验收证据。
- 单功能页继续展示 iPad、Duo、名单、球员落点与 Android。Android 只批准通用规则／设置两张不含球员资料的真实截图；球员落点采用 iOS 的张三演示数据。
- 品牌、两行标题、说明与主视觉统一居中。删除逐台设备标签和设备清单式排版。

## 私人资料边界

用户指出旧 Android 统计里的姓名属于真实孩子。先前将测试截图称为“合成资料”的判断错误，已撤回。旧 Android 比赛、名单、草稿、统计图片全部停止用于宣传，并移出当前 Git 资产树与交付包；用户原始证据不改写。不能因资料出现在测试代码中就认定为虚构。

[安全截图审查](assets/screens/android-safe-provenance.json)记录规则／设置原图与 XML 的核对依据。[Android 记录](assets/android-provenance.json)现仅保留真实机壳来源，不再批准旧球员数据图片。当前图片另执行本地 OCR 名单排除检查，不输出私人姓名。

## 复现与交付

```sh
node docs/design/marketing/3.1.2/social/render.cjs
python3 scripts/release/package-release-3.1.2.py
```

需现有 Playwright、sharp、Chrome；Apple 官方机壳原件仅本机使用，见 [机壳与许可](assets/hardware/README.md)。唯一编辑入口是 render.cjs、copy.md 与 assets/；design-review/ 和 archive/ 为退稿追溯，不是当前生成入口。

输出：`output/marketing/3.1.2/xiaohongshu/2026-11-04/` 六张 1080×1440 PNG／JPG及文案；`output/marketing/3.1.2/wechat/2026-11-04/` 一张 1080×1800 一页图。各有总览、manifest 与图片文案 ZIP；总包从 `output/releases/3.1.2/index.html` 取用。旧退稿／私人原图不进入包。
