# 真实设备外壳来源与屏幕配对

查询日期：2026-11-04。本文件是制作依据，非对外宣传正文。本次仅核对来源与条件，未下载或分发第三方原始素材。

## iPad 与 iPhone Duo

优先使用 [Apple Design Resources](https://developer.apple.com/design/resources/) 的 Product Bezels，页面现有对应设备 Photoshop／PNG 资源：

- [iPhone Duo 官方外壳 DMG](https://devimages-cdn.apple.com/design/resources/download/Bezel-iPhone-Duo.dmg)
- [iPad Pro M5 官方外壳 DMG](https://devimages-cdn.apple.com/design/resources/download/Bezel-iPad-Pro-%28M5%29.dmg)
- [Apple 营销素材与标识指南](https://developer.apple.com/app-store/marketing/guidelines/)

官方指南要求使用原样设备图，禁止裁切、倾斜、额外阴影等修改；官方 Apple 产品图不与竞争产品图片同画面。双平台可以在正文或设备标签中介绍，因此建议 iPad、Duo、Android 分页，封面只并列文字与产品品牌视觉。屏幕使用真实 App 界面，不能把功能示意图贴进真实外壳冒充实际界面。

Duo 外屏相机位、内屏比例与系统侧边栏需按真实官方模型；不能用常规 iPhone 或通用平板外壳代替。参考 [Duo 设计指南](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo)。

## Android 折叠设备

### 官方 Samsung Galaxy Emulator Skin

[Samsung Galaxy Z 官方皮肤](https://developer.samsung.com/galaxy-emulator-skin/galaxy-z.html) 有 Fold6、Fold7 等模型；[使用指南](https://developer.samsung.com/galaxy-emulator-skin/guide.html) 要求屏幕尺寸与分辨率匹配，说明皮肤只提供外观和控件，系统仍为 stock Android，不包含 One UI。

核实的官方下载入口：

- [Galaxy Z Fold7 Skin](https://developer.samsung.com/EmulatorSkin/file/adb47a1d-57e9-40ae-86bf-5ccd44274308)
- [Galaxy Z Fold6 Skin](https://developer.samsung.com/EmulatorSkin/file/ac4acd49-ce5e-461a-bbd0-51cafce84125)

两者均跳转 Samsung 登录页；没有在本次研究中取得包。官方页面没有在正文给出可随意商业广告再分发的许可，选用前需检查包内许可。不使用 Samsung Newsroom 的新闻照片冒充已获得商业 mockup 许可：其 [素材说明](https://news.samsung.com/medialibrary/global/photo/57484/search/ai?page=2) 限于 media publishing purpose。

### 可直接取用的商业宣传备选

[Hoverify 设备框](https://tryhoverify.com/tools/device-frames/) 提供按真实设备尺寸绘制的透明框，属于第三方型号专属绘制资产，不是厂商官方照片。其 [Device Frames License](https://tryhoverify.com/tools/device-frames/license/) 允许个人及商业工作、广告、商店截图等，免署名；不能出售或分享原始框文件，也不能用其建立 mockup 工具。使用时可交付合成成图，不把原始外壳混进公共素材 ZIP 或 Git 源稿包。

- [Pixel 10 Pro Fold 模型与屏幕参数](https://tryhoverify.com/tools/device-frames/pixel-10-pro-fold/)
- [Pixel 10 Pro Fold 展开 Moonstone WebP](https://tryhoverify.com/images/devices/pixel_10_pro_fold_open/moonstone.webp)
- [Pixel 10 Pro Fold 折痕层](https://tryhoverify.com/images/devices/pixel_10_pro_fold_open/_crease.webp)
- [Galaxy Z Fold7 模型与屏幕参数](https://tryhoverify.com/tools/device-frames/galaxy-z-fold7/)
- [Galaxy Z Fold7 展开 Jetblack WebP](https://tryhoverify.com/images/devices/galaxy_z_fold7_open/jetblack.webp)

已验收 Android 展开屏幕原图为 2076 × 2152，比例约 0.9647；Pixel 10 Pro Fold 内屏 755 × 783，比例约 0.9642，基本一致。Galaxy Z Fold7 750 × 832、Fold6 1856 × 2160 明显不同，不拉伸现有图片去填这些外壳。若选 Samsung 外壳，应重新在其对应原生分辨率捕获界面。

Pixel 模型匹配比例仅说明适合排版，不代表本项目做过 Pixel 真机验证；Android 能力的真实证据仍是已验收官方折叠模拟器。成图描述“Android 折叠屏”，不写具体厂商真机通过。

## 已核对的本地原始界面

旧 Android 比赛／球员／草稿图片已撤回：其中包含不能确认虚构的未成年人姓名，先前“合成资料”的审查结论错误，不再有效。当前仅批准 assets/screens/android-safe-rules.png 与 android-safe-settings.png；二者为无姓名、无球队资料的实际通用设置界面，原图和XML来源见 android-safe-provenance.json。

当前主海报依用户最终指示，只保留 iPad、iPhone、Duo、Android 手机四台设备。通过内置 ImageGen 保留首张摄影构图作最小修改；产品效果图内的队名与球员采用泛化演示标记。旧六设备平铺图与多折叠设备图均不作为当前交付。
