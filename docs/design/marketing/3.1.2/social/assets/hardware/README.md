# Apple 官方设备壳来源与本机复现

2026-11-04。来源：[Apple Design Resources](https://developer.apple.com/design/resources/) → Product Bezels。原始 iPad Pro（M5）、iPhone Duo、iPhone 18 DMG 下载至本 App `output/cache/3.1.2-device-bezels/`，网址、字节数及 SHA-256 见 manifest.json。

用户在本聊天明确回复“同意协议，使用官方设备壳”后，才只读挂载并提取。本次使用官方原生横／竖方向，不旋转或裁切原壳；PNG 逐字节复制，未重新编码。完成后卸载本次三个卷。

## 文件与合成

- `apple-provenance.json`：五个设备模型、原始 PNG 尺寸、完整 SHA、归档内路径、屏幕孔范围与精确 alpha 蒙版，模型均为 `frameOnTop: true`。
- `apple-originals/`：仅本机的官方原始 PNG 与派生屏幕蒙版，已由本目录 `.gitignore` 排除 Git。
- `measure-apple-bezels.cjs`：从中心透明连通区域计算屏幕孔，生成精确蒙版，不修改原壳。
- `prepare-apple-bezels.py`：按冻结校验值下载／核验、要求显式许可参数、只读挂载、复制原件、自动卸载和重新测量。不会卸载其他已挂载卷；发现相同归档已挂载时停止。

屏幕实际截图按 `screen` 矩形定位，应用 `screen.mask`（alpha 模式，蒙版尺寸等于屏幕范围），再将完整原壳全幅覆盖在顶部。Duo 外屏左侧较方、右侧圆角较大，必须用精准蒙版，不统一猜圆角。不要裁切、倾斜、翻转、遮挡或给设备添加阴影。只能贴对应平台实际 App 界面与虚构示例资料。主海报同图列出所有设备的构图按用户后续明确要求执行，单功能页继续按平台展示。

## 许可与交付

许可：Apple Design Resources LYL142 06/21/2023。全文本机保留在 `output/cache/3.1.2-device-bezels/apple-design-resources-license-LYL142-2023-06-21.txt`，各 DMG 内原始 RTF 也保存到缓存。还需遵守 [Apple Marketing Resources and Identity Guidelines](https://developer.apple.com/app-store/marketing/guidelines/)。

许可不允许将独立 Template Content 作为素材重新分发。**原壳 PNG、派生屏幕蒙版与 DMG 不进入 Git，也不放进 source／交付 ZIP。最终交付 ZIP 只包含已合成的宣传图及本产品文案。** 本目录 JSON、README 和复现脚本可入 Git；需要复现的人自行从 Apple 下载并接受适用许可。不要将 Apple 壳用于 Android UI。

下载核验（不接受许可、不挂载）：

```sh
python3 docs/design/marketing/3.1.2/social/assets/hardware/prepare-apple-bezels.py --download-only
```

操作者已经阅读并明确接受许可后，才运行：

```sh
python3 docs/design/marketing/3.1.2/social/assets/hardware/prepare-apple-bezels.py --accept-apple-license
```

`CODEX_ARTIFACT_NODE_MODULES` 可指定包含 sharp 的 Node 模块目录。原件来源或哈希变化时应先核对 Apple 更新，不跳过校验或直接覆盖本版来源记录。
