const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { webkit } = require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..');
const publicRoot = path.join(root, 'website/public');
const output = path.join(root, 'output/validation/2.3/release-acceptance/website');fs.mkdirSync(output,{recursive:true});
const production = process.argv.includes('--production');
const label = production ? 'production' : 'local';
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png', '.jpg': 'image/jpeg', '.xml': 'application/xml', '.txt': 'text/plain' };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const relative = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).slice(1);
  const file = path.resolve(publicRoot, relative);
  if (!file.startsWith(publicRoot + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404); return res.end();
  }
  res.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
  fs.createReadStream(file).pipe(res);
});
const results = [];
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function main() {
  let base = 'https://baseballmaster.cc';
  if (!production) {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    base = `http://127.0.0.1:${server.address().port}`;
  }
  const browser = await webkit.launch();
  try {
    for (const width of [320, 390, 768, 1440]) {
      const context = await browser.newContext({ viewport: { width, height: width > 700 ? 1000 : 844 }, deviceScaleFactor: 1 });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('response', r => { if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`); });
      for (const urlPath of ['/', '/support.html', '/privacy.html']) {
        const response = await page.goto(base + urlPath, { waitUntil: 'networkidle' });
        assert.equal(response.status(), 200);
        await page.evaluate(async () => {
          document.querySelectorAll('img[src]').forEach(img => img.loading = 'eager');
          await Promise.all([...document.querySelectorAll('img[src]')].map(img => img.decode()));
          await document.fonts.ready;
        });
        const layout = await page.evaluate(() => ({
          viewport: innerWidth, scrollWidth: document.documentElement.scrollWidth,
          images: [...document.querySelectorAll('img[src]')].map(i => ({ src: i.getAttribute('src'), loaded: i.complete && i.naturalWidth > 0 }))
        }));
        assert.ok(layout.scrollWidth <= width + 1, `${width} ${urlPath}: horizontal overflow ${layout.scrollWidth}`);
        assert.ok(layout.images.every(i => i.loaded));
        if (urlPath === '/') {
          const text = await page.locator('body').innerText();
          assert.ok(text.includes('2.3 App 已完成开发与模拟器验收'));
          assert.equal(await page.locator('#main-nav a[href="#next"]').textContent(), '3.0 预告');
          assert.ok(!text.includes('2.1 即将上线'));
          assert.equal(await page.locator('.release-card').count(), 4);
          assert.equal(await page.locator('.release-details article').count(), 3);
          assert.equal(await page.locator('meta[property="og:image"]').getAttribute('content'), 'https://baseballmaster.cc/assets/baseballmaster-2.3-update.png');
          assert.equal(await page.locator('.release-poster-download').getAttribute('download'), 'BaseballMaster-2.3-update.png');
          assert.equal(await page.locator('.release-poster-download').getAttribute('href'), '/assets/baseballmaster-2.3-update.png');
          for (const anchor of await page.locator('a[href^="#"]').all()) {
            const href = await anchor.getAttribute('href');
            assert.ok(href === '#' || await page.locator(href).count() === 1, `Missing anchor ${href}`);
          }
          if (width < 600) {
            await page.locator('.menu-toggle').click();
            assert.equal(await page.locator('.menu-toggle').getAttribute('aria-expanded'), 'true');
            await page.locator('#main-nav a[href="#updates"]').click();
            assert.equal(await page.locator('.menu-toggle').getAttribute('aria-expanded'), 'false');
          }
          await page.locator('.release-poster-thumb').click();
          assert.ok(await page.locator('#screenshot-dialog').evaluate(e => e.open));
          await page.locator('#gallery-image').evaluate(e => e.decode());
          assert.equal(await page.locator('#gallery-version').innerText(), 'BASEBALLMASTER · 2.3');
          assert.equal(await page.locator('#screenshot-title').innerText(), '2.3 版本更新海报');
          const image = await page.locator('#gallery-image').evaluate(e => ({ w: e.naturalWidth, h: e.naturalHeight }));
          assert.deepEqual(image, { w: 1440, h: 1920 });
          await page.keyboard.press('ArrowRight');
          assert.notEqual(await page.locator('#screenshot-title').innerText(), '2.3 版本更新海报');
          await page.keyboard.press('ArrowLeft');
          assert.equal(await page.locator('#screenshot-title').innerText(), '2.3 版本更新海报');
          await page.keyboard.press('Escape');
          assert.ok(!await page.locator('#screenshot-dialog').evaluate(e => e.open));
          assert.equal(await page.evaluate(() => document.documentElement.style.overflow), '');
          await page.waitForFunction(() => document.activeElement.matches('.release-poster-thumb'));
          await page.locator('.release-poster-thumb').evaluate(e => e.blur());
          await page.locator('#updates').screenshot({ path: path.join(output, `${label}-updates-${width}.png`), animations: 'disabled' });
          await page.locator('.release-share').screenshot({ path: path.join(output, `${label}-poster-block-${width}.png`), animations: 'disabled' });
          await page.screenshot({ path: path.join(output, `${label}-home-${width}.png`), fullPage: true, animations: 'disabled' });
        } else {
          const text = await page.locator('body').innerText();
          if (urlPath === '/support.html') {
            assert.ok(text.includes('2.3 使用指南'));
            assert.ok(text.includes('2.3 成人慢垒与赛前阵容'));
            assert.ok(text.includes('小组件'));
          }
          for (const anchor of await page.locator('a[href^="#"]').all()) {
            const href = await anchor.getAttribute('href');
            assert.ok(href === '#' || await page.locator(href).count() === 1, `Missing legal anchor ${href}`);
          }
        }
        results.push({ width, path: urlPath, passed: true, layout });
        console.log(`PASS ${label} ${width}px ${urlPath}`);
      }
      assert.deepEqual(errors, [], `${label} ${width}: ${errors.join('; ')}`);
      await context.close();
    }
    const poster = await fetch(base + '/assets/baseballmaster-2.3-update.png');
    assert.equal(poster.status, 200);
    assert.ok(poster.headers.get('content-type').startsWith('image/png'));
    const hash = sha(Buffer.from(await poster.arrayBuffer()));
    assert.equal(hash, sha(fs.readFileSync(path.join(publicRoot, 'assets/baseballmaster-2.3-update.png'))));
    results.push({ poster: { width: 1440, height: 1920, sha256: hash }, passed: true });
    fs.writeFileSync(path.join(output, `${label}-browser.json`), JSON.stringify({ checkedAt: new Date().toISOString(), base, results }, null, 2));
  } finally { await browser.close(); if (!production) await new Promise(resolve => server.close(resolve)); }
}
main().catch(e => { console.error(e); process.exitCode = 1; server.close(); });
