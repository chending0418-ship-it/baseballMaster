const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { webkit } = require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../public');
const output = path.resolve(__dirname, '../../output/validation/3.0/device-recovery/browser');
fs.mkdirSync(output, { recursive: true });
const requests = [];
const server = http.createServer((req, res) => {
  requests.push(req.url);
  const url = new URL(req.url, 'http://localhost');
  const file = path.resolve(root, '.' + (['/pair', '/pair/'].includes(url.pathname) ? '/pair/index.html' : url.pathname));
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); return res.end(); }
  res.setHeader('Content-Type', { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png' }[path.extname(file)] || 'application/octet-stream');
  fs.createReadStream(file).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await webkit.launch();
  const results = [];
  try {
    for (const [width, colorScheme] of [[393, 'light'], [402, 'dark'], [440, 'light']]) {
      const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme });
      const page = await context.newPage(), errors = [], network = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('request', req => network.push(req.url()));
      const token = crypto.randomBytes(32).toString('base64url');
      await page.goto(`http://127.0.0.1:${server.address().port}/pair#${token}`, { waitUntil: 'networkidle' });
      assert.equal(await page.locator('#open-app').getAttribute('href'), `baseballmaster://pair#${token}`);
      assert.equal(await page.locator('#open-app').isVisible(), true);
      assert.equal(await page.locator('a[href*="apps.apple.com"]').getAttribute('href'), 'https://apps.apple.com/cn/app/id6802063886');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.deepEqual(errors, []);
      assert.equal(network.some(url => { const parsed = new URL(url); parsed.hash = ''; return !url.startsWith('http://127.0.0.1:') || parsed.href.includes(token); }), false);
      assert.equal(requests.some(url => url.includes(token)), false);
      await page.screenshot({ path: path.join(output, `pair-${width}-${colorScheme}.png`), fullPage: true });
      await page.goto(`http://127.0.0.1:${server.address().port}/pair#%3Cscript%3Ebad%3C/script%3E`);
      assert.equal(await page.locator('#open-app').isVisible(), false);
      assert.equal(await page.locator('#copy-link').isVisible(), false);
      results.push({ width, colorScheme, validPairing: true, malformedBlocked: true, secretNeverRequested: true, horizontalOverflow: false });
      await context.close();
    }
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2));
    console.log('3 pairing landing page viewports passed; no secret transmitted or horizontal overflow.');
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; server.close(); });
