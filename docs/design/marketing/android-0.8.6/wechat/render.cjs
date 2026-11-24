// Run from any directory. Set NODE_PATH for Playwright and CHROME_BIN for Chromium.
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

async function render() {
    const root = path.resolve(__dirname, '../../../../..');
    const folder = path.join(root, 'output/marketing/0.8.6/wechat/2026-11-24/build33');
    fs.mkdirSync(folder, { recursive: true });
    const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN });
    try {
        const page = await browser.newPage({ viewport: { width: 1080, height: 1440 } });
        await page.goto(pathToFileURL(path.join(__dirname, 'poster.html')).href);
        await page.evaluate(() => document.fonts.ready);
        const loaded = await page.locator('img').evaluateAll(images =>
            images.length === 2 && images.every(img => img.complete && img.naturalWidth > 0));
        if (!loaded) throw new Error('品牌标志读取失败，请在 BaseballMaster 品牌工作区内导出。');
        await page.screenshot({ path: path.join(folder, 'Android-0.8.6-微信单页.png') });
    } finally {
        await browser.close();
    }
}
render().catch(error => { console.error(error); process.exitCode = 1; });
