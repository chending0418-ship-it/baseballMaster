# 3.1.4 商店截图和 App Review 操作图

沿用 3.1.3 深绿底、页首产品标志、底部 BM 署名以及当时定稿的常驻截图。源图为模拟器内的合成球队和成员。两张协作图依次摘取真实界面的岗位列表、先发入口、补员表单和替补区。iPhone 及 iPad 的守备路线均取自 3.1.4 的独立设备原生测试附件。

- 本版 iPhone 协作原图来自 3.1.4 开发工作树的 output/review/3.1.4/screens 中的 02、03、05、07、08。守备原图来自 Build 1 的 field-and-bench.xcresult。
- 13 英寸 iPad 图于 2026-11-26 以同版已编译测试产物运行 testV314GroundOutFromThirdToFirst 导出。路线上记录三垒手、一垒手；像素为 2064 × 2752。
- 产品反白标志取自品牌规范 1.3 的 scorekeeper-horizontal-reverse.svg，BM 反白横版取自 baseballmaster-2026-10-22。使用方式遵循工作区品牌规范 1.5。
- 渲染命令为 `node render.cjs <上一版送审包的 screenshots 目录>`。需要 sharp、playwright、本机 Chrome 和苹方字体。可用 CODEX_ARTIFACT_NODE_MODULES 指定依赖位置，CHROME_BIN 指定浏览器。既有定稿 JPEG 仅调整顺序，新增图按每组目标像素直接输出。审核附件由同目录 review-guide.html 打印。文本源稿见[送审清单](../../../../../ios/docs/releases/3.1.4/送审清单.md)。
