# 2.2 官网与传播图片更新

2026-10-03 08:11（北京时间），按用户要求同步更新官网并直接上线。沿用 2.1 宣传图的品牌与版式；本轮仅修改网站和传播素材，未改动 App 工程、归档或商店截图。

## 已上线内容

- [官网](https://baseballmaster.cc/)：集中展示紧凑文字直播、双方当前阵容、长按与连续阵容调整、自选比赛统计，补充首球前互换、文字简版投手信息和终场导航修复。
- [技术支持](https://baseballmaster.cc/support.html)：目录加入 2.2 优化入口，说明与当前功能一致；修正底部慢垒旧版本标记。慢垒与桌面小组件继续明确列为 2.3 规划。
- [2.2 更新宣传图](https://baseballmaster.cc/assets/baseballmaster-2.2-update.png)：首页提供放大预览与保存入口，首页分享元信息也引用该图。关闭图库后焦点回到原图片链接。
- 清除首页残留的“2.1 即将上线”。查询 Apple 中国区公开商店仍为 2.1，因此官网使用“2.2 App 正在发布中”，海报使用“2.2 · 版本更新”；没有提前宣称 App 已审核通过或正式上架。

隐私页现有 2.2 说明保持有效；本轮未新增数据上传或收集能力。

## 宣传图

1440 × 1920 RGB PNG，563,960 字节。沿用深绿底、既定图标和字标、三张实际界面及四项更新摘要。主标题为“记分更顺手，观赛更清楚。”，展示文字直播、阵容连续调整、自选比赛统计和投手信息补全。

三张界面直接嵌入 `output/releases/2.2/app-store/raw/02.png`、`04.png`、`05.png`；使用合成比赛与球员数据，未重画或修改原始界面。图标直接引用 App 的原始 1024 px 图标。两套 App Store 截图保持不变。

- 模板：`website/templates/release-2.2-poster.html`
- 渲染器：`website/scripts/render-release-2.2-poster.cjs`
- 成图、独立预览 HTML、原图哈希与布局清单：`output/marketing/2.2/release-poster/`
- 公开副本：`website/public/assets/baseballmaster-2.2-update.png`
- PNG SHA-256：`928c597f1c709293cb08e5850a6355dbb7c94a4ea46a0c65eb6739b22e43b194`

海报仅用于传播，不作为设备截图上传 App Store。未向社交平台或其他人发送消息。

## 部署与回滚

独立公开根目录仍为 `/www/wwwroot/baseballmaster.cc`，部署包仅包含 `website/public/` 的 37 个公开文件。实际变化为五个文件：`index.html`、`support.html`、`assets/gallery.js`、新 `assets/release-2.2.css` 和新宣传 PNG。其余 32 个文件校验一致，旧 CSS 与图片保留。

部署前保存旧文件及哈希，逐文件原子替换；完成后逐一校验全部 37 个文件。备份与部署清单位于服务器 `/root/baseballmaster-deploy/20261003-website-marketing-2.2/`，权限 0700，包含 `before/`、`before.tar.gz`、`deployment.json` 和部署包。本机副本在 `output/backups/website/2.2-marketing/`。

如需回滚，先核对部署清单的当前文件哈希仍匹配本次发布，再恢复 `before/` 的旧首页、支持页与图库脚本，删除本次新增 CSS 和 PNG。若网站已有后续变更，应先比较差异，避免覆盖新内容。

未更改 Nginx、证书、文字直播程序、数据库或其他站点，未重启服务。直播仍指向 `releases/2.2-2026-10-03`，部署前后健康检查均正常，没有创建公开测试比赛。

## 验证

- 本地与公网 WebKit：官网、技术支持、隐私三页分别在 320、390、768、1440 px 检查，共 24 组通过。页面和图片加载正常，无横向溢出；手机导航、目录锚点、海报版本标签、键盘前后切换、Escape 关闭、滚动恢复及焦点返回通过。
- 宣传 PNG 的图片加载、三图与说明对齐、文本布局、尺寸、无透明通道和原图哈希检查通过；检查手机与电脑实际排版。
- HTTPS 全部 37 个公开文件逐字节匹配本地；根页面、海报下载及直播健康检查正常。HTTP → HTTPS 为 308；`/README.md`、`/TODO.md`、`/.git/config`、`/assets/` 均为 404。
- 核对 Apple 官方 lookup 接口的当前中国区公开版本为 2.1，官网文案与该状态一致。本次未执行 App 上传或审核。
- `node --check` 与 `git diff --check` 通过；本轮未改动 App，不重复已完成的 App 回归。

验证脚本、浏览器截图与实际结果统一保存在 `output/validation/2.2/website-marketing/`，包括 `local-browser.json`、`production-browser.json`、`public-verification.json`、海报渲染记录和 `deployment.json`。
