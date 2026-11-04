# 商店图片源稿

沿用品牌规范，实际模拟器截图放在 source/，每张原始路径、SHA-256 和验证批次在 source/content.json。该文件也保存可编辑标题和说明，图片无对外版本号。

从 iOS 根目录运行：

```sh
node scripts/release/render-appstore-3.1.2.cjs
```

依赖现有 Playwright、sharp 和 Google Chrome，支持 CODEX_ARTIFACT_NODE_MODULES／CHROME_BIN。输出固定写真实 output/releases/3.1.2/app-store，不上传。尺寸与边界见 docs/releases/3.1.2/APPSTORE-ARTWORK.md。
