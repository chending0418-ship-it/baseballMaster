# BaseballMaster 官网首页

域名：`https://baseballmaster.cc/`。独立静态站点，沿用现有宝塔／Nginx 服务器；不依赖 Node、构建工具、外部字体或第三方网页脚本。

**品牌已定稿：** 用户确认当前 Logo、英文粗细组合和中文排版固定沿用。后续品牌相关修改参考 [品牌基准](../docs/brand/BRAND.md)，不随功能迭代重新设计。

**2026-09-28 已上线：** 根域名解析、独立目录、SSL 证书、HTTP → HTTPS 和公网访问验证完成。当前证书有效至 2026-12-27 10:59:59（北京时间），未配置自动续期。实际配置路径、换证和宝塔管理说明见 [上线记录](deploy/2026-09-28-https-deployment.md)。

## 页面与内容（2026-10-03）

- 官网首页展示 2.2：紧凑文字直播、投打对决、双方当前阵容、长按与连续阵容调整、指定比赛统计；补充首球前互换、文字简版投手信息与终场导航修复。
- 本版截图来自 2.2 实际 App／部署后的文字观赛网页，合成球队与球员。原始来源与两套商店图片见 [2.2 素材说明](../docs/releases/2.2/APPSTORE-ARTWORK.md)；旧版图片继续作为既有功能展示，标注其来源版本。
- 新增 2.2 更新海报，可在首页放大或下载；首页分享元信息使用同一海报。
- 慢垒、Free／Illegal、扩展打序及比赛预告桌面小组件均在 2.3 规划，不包含在 2.2 中。
- 文字直播 Web／API 已正式上线，支持已发布的 2.1 App 和新版 2.2 投影。2026-10-03 查询 Apple 中国区商店仍为 2.1，因此首页使用“2.2 App 正在发布中”，以 App Store 实际可下载版本为准。
- 支持与隐私页覆盖当前功能及直播公开范围。所有页面沿用既定图标、BASEBALL 粗体／MASTER 常规字重、中文副标题及深绿配色；图库支持键盘切换、关闭和焦点恢复。

最新内容、海报来源与部署记录见 [2.2 官网及传播素材更新](deploy/2026-10-03-website-marketing-2.2.md)。历史截图来源见 [SCREENSHOTS.md](SCREENSHOTS.md)。

`public/` 是唯一公开目录。部署包不包含本文件、TODO、源码、验证记录或旧域名。

## 本地预览

在仓库根目录运行：

```sh
python3 -m http.server 8321 --bind 127.0.0.1 --directory website/public
```

访问 `http://127.0.0.1:8321/`。不要以仓库根目录启动对外静态文件服务。

## 部署首页

逐项填写指南见 [腾讯云与宝塔上线步骤](deploy/腾讯云与宝塔上线步骤.md)。本次服务器地址为 `124.156.173.204`，根域名使用 `@`／`A`／默认线路指向该 IP。

1. 等待新域名命名审核完成，并在 DNSPod 配置根域名 `@` 的 A 记录，指向服务器公网 IPv4。
2. 在宝塔建立只绑定 `baseballmaster.cc` 的独立静态站点，根目录使用 `/www/wwwroot/baseballmaster.cc`，申请并配置 HTTPS 证书与续期。
3. 将 `public/` 里面的文件上传至这个根目录，确保 `index.html` 直接位于站点根目录。可使用下方命令生成的 ZIP，解压后同样检查目录层级。
4. 备份新站点配置，合并 [nginx-homepage.conf](deploy/nginx-homepage.conf) 到该站点 HTTPS `server` 块。已有相同 `location` 时替换，不重复添加。保留 SSL 和证书验证路径，启用 HTTP → HTTPS。首页不再跳转到直播入口。
5. 在服务器执行 `nginx -t` 后重载。打开首页、隐私页、支持页，确认图片加载、下载地址及手机菜单正常；`/TODO.md`、`/.git/config`、`/README.md`、`/assets/` 均应返回 404／403。
6. 本次仅首页可以独立发布。2026-09-30 已额外合并 [直播路径配置](../live/deploy/nginx-location.conf)，保留首页静态路由与 404 兜底。内部旧站点的访问验证仍须单独配置。

完整服务器与域名步骤见 [直播部署说明](../live/README.md)。以上为从零配置的参考；当前官网已通过 SSH 安装独立 Nginx 配置，不应重复创建或覆盖现有站点。文字直播 Web／API 已部署，见 [正式部署与模拟器验收](../live/deploy/2026-09-30-production.md)。

## 打包

```sh
python3 website/scripts/package.py
```

输出 `output/deployments/website/baseballmaster-home/baseballmaster-home.zip`，ZIP 根目录只包含 `public/` 的可公开文件。上传时不需要开发依赖或构建。

## 本地验证（2026-09-28）

已在浏览器检查 1440、768、390、320 px 宽度，无横向溢出或资源错误；检查三个界面页签、上下方向键与 Home 切换、FAQ 展开、手机菜单展开／跳转关闭／Escape 关闭，以及隐私和支持页的手机布局。三个 App Store 按钮均使用此前提供的地址；外部商店可用状态未由本地检查代替验证。

隐私与技术支持页使用与首页一致的品牌导航、颜色和阅读布局。网站图标从 App 原始图标生成 256 px 网页版本，减少首次加载体积；App 原图保持不变。

本地验证记录和截图位于 `output/deployments/website/baseballmaster-home/qa/`，不进入公开部署包。服务器上的 `nginx -t`、HTTPS 和实际公网访问已在此次上线时验证，结果见 [上线记录](deploy/2026-09-28-https-deployment.md)。

六项特点更新后，重新检查 1440、768、390、320 px 宽度，卡片与页面均无横向溢出。七张特色截图的弹窗、循环切换、键盘关闭与焦点恢复通过；统计和比赛报告页签均加载新截图。全部公开资源和图片链接均做本地 HTTP 检查，旧版三张 JPEG 已从公开目录移除。此次补充检查记录见 `output/deployments/website/baseballmaster-home/qa/features-2.0.json`。

2026-09-28 导航调整：移除“界面一览”入口和对应整块展示区；首屏“探索 App”跳转至紧邻的六项功能区。已更新线上首页和部署包，并验证实际滚动位置。

## 更新宣传海报

模板 `templates/release-2.1-poster.html`，运行 `node website/scripts/render-release-poster.cjs`。需要本机 Chrome、Playwright 和 Sharp（可设置 `CODEX_ARTIFACT_NODE_MODULES` 指向模块目录）。产物仅存 `output/marketing/2.1/release-poster/`，1440 × 1920 RGB PNG；品牌原图和已确认实际截图直接嵌入，不修改原图。不要把此海报当作新设备截图上传。

## 2.2 更新宣传海报

沿用 2.1 的 1440 × 1920 竖版、三张实际界面与四项更新卡片。模板 `templates/release-2.2-poster.html`，执行 `node website/scripts/render-release-2.2-poster.cjs`；可设置 `CODEX_ARTIFACT_NODE_MODULES`，使用本机 Chrome、Playwright 与 Sharp 元数据检查。

成图位于 `output/marketing/2.2/release-poster/baseballmaster-2.2-update.png`，HTML 与原图哈希／布局检查 manifest 同目录保存；公开副本为 `public/assets/baseballmaster-2.2-update.png`。原始截图直接嵌入，未修改界面内容，App Store 两套截图不变。标题为“2.2 · 版本更新”，不预写 App 已审核通过或正式上架。
