# BaseballMaster 品牌与产品设计规范 1.0

文档版本：**1.0**。日期：**2026-10-22**。24 页，A4 横向，内嵌中文黑体与 Arial，带可点击目录和 24 个书签。

[打开完整 PDF](BaseballMaster-品牌与产品设计规范-1.0.pdf)

本版覆盖母品牌、棒球比赛记录大师、教练口袋的标识、色彩、字体、版式和产品设计原则，以及首页应用、宣传参考、命名文案与交付要求。母品牌使用深藏青 `#0B1933`，产品保留深绿与酒红；英文统一 800/400 字重与 -0.045em 字距。

已确认的品牌基准、1.0 应用建议和本地首页示例在正文中分别说明。秒表、距离测量等列为后续方向；没有设计教练口袋独立产品页，没有改变 App 或正式网站，没有上传部署。

## 文件

- `build.py`：ReportLab 排版源稿，检查全部文字、图片边界和中文字符覆盖。
- `capture-assets.cjs`：冻结当前首页应用与两款产品的精确组合，检查 800/400 和 -0.045em；**只在有意更新本版快照或建立新版本时运行**。
- `verify.py`：检查页数、书签、内嵌字体、PDF 实际字符边界及英文两段间的精确字距，生成逐页总览。
- `assets/`：本版冻结的母品牌组合、产品签名与首页应用图。
- `manifest.json`：版本与 PDF 哈希；`handoff-manifest.json`：最终来源与检查记录。

## 重现与检查

在项目根目录执行。使用 Codex 依赖环境中的 Python、ReportLab、Pillow、pypdf、pdfplumber 与 Poppler。

```sh
/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 docs/brand/specifications/1.0/build.py
pdftoppm -r 100 -png 'output/marketing/brand/1.0/BaseballMaster-品牌与产品设计规范-1.0.pdf' 'output/validation/brand/1.0/pages/page'
/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 docs/brand/specifications/1.0/verify.py
```

PDF 同时归档在本目录并复制到 `output/marketing/brand/1.0/`；两份内容相同。检查记录与逐页 PNG 在 `output/validation/brand/1.0/`，不是额外的正式版本。

后续规范修改使用新文档版本记录变更；本版截图和旧确认参考用于追溯。母品牌图形的 SVG 当前含透明位图轮廓，不能表述为完整描摹的纯矢量文件。
