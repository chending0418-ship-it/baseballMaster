'use strict';
// Reuse approved original screenshots. Never writes to the App Store screenshot set.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const repo = path.resolve(__dirname, '../..');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(process.env.HOME, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const { chromium } = require(path.join(modules, 'playwright'));
const sharp = require(path.join(modules, 'sharp'));
const destination = path.join(repo, 'output/marketing/2.1/release-poster');
const sources = {
  ICON: 'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png',
  SHOT02: 'output/releases/2.1/app-store/raw/02.png',
  SHOT03: 'output/releases/2.1/app-store/raw/03.png',
  SHOT04: 'output/releases/2.1/app-store/raw/04.png',
};
async function render() {
  fs.mkdirSync(destination, { recursive: true });
  let html = fs.readFileSync(path.join(repo, 'website/templates/release-2.1-poster.html'), 'utf8');
  const hashes = {};
  for (const [key, relative] of Object.entries(sources)) {
    const buffer = fs.readFileSync(path.join(repo, relative));
    hashes[relative] = crypto.createHash('sha256').update(buffer).digest('hex');
    html = html.replaceAll(`@@${key}@@`, `data:image/png;base64,${buffer.toString('base64')}`);
  }
  fs.writeFileSync(path.join(destination, 'release-2.1-poster.html'), html);
  const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1920 }, deviceScaleFactor: 1 });
    await page.setContent(html, { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    const layout = await page.evaluate(() => {
      const images = [...document.images];
      const featureBottom = document.querySelector('.features').getBoundingClientRect().bottom;
      const footerTop = document.querySelector('.footer').getBoundingClientRect().top;
      return { imagesLoaded: images.every(image => image.complete && image.naturalWidth > 0), featureBottom, footerTop, overflow: document.documentElement.scrollWidth > innerWidth };
    });
    if (!layout.imagesLoaded || layout.overflow || layout.featureBottom > layout.footerTop - 20) throw new Error(`Poster layout failed: ${JSON.stringify(layout)}`);
    const png = await page.screenshot({ type: 'png' });
    const filename = path.join(destination, 'baseballmaster-2.1-update.png');
    await sharp(png).removeAlpha().png().toFile(filename);
    const metadata = await sharp(filename).metadata();
    fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify({ created: new Date().toISOString(), appVersion: '2.1', publicationUse: 'after-app-store-release', width: metadata.width, height: metadata.height, hasAlpha: metadata.hasAlpha, sourceHashes: hashes, layout, sha256: crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex') }, null, 2) + '\n');
    console.log(`${filename} (${metadata.width} × ${metadata.height}, RGB, reused screenshots)`);
  } finally { await browser.close(); }
}
render().catch(error => { console.error(error.message); process.exitCode = 1; });
