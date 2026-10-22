// Export the deep green composition, with its existing App icon at the bottom,
// into native launch layers. Both brand marks come from the original assets.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const { chromium } = require(path.join(modules, 'playwright'));
const sharp = require(path.join(modules, 'sharp'));
const root = path.resolve(__dirname, '../../../../..');
const assets = path.join(root, 'BaseballMaster/Assets.xcassets');
const input = path.join(root, 'output/samples/3.1.1/launch-screen/2026-10-22/v3/01-产品深绿.html');

(async () => {
    const browser = await chromium.launch({ headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 402, height: 874 }, deviceScaleFactor: 3 });
        await page.goto(pathToFileURL(input).href);
        await page.evaluate(async () => {
            await document.fonts.ready;
            const image = new Image();
            image.src = getComputedStyle(document.querySelector('.mark')).maskImage.slice(5, -2);
            await image.decode();
            await Promise.all([...document.images].map(image => image.decode()));
        });
        const exportLayer = async (name, clip, css) => {
            const style = await page.addStyleTag({ content: css });
            const buffer = await page.screenshot({ omitBackground: true, clip });
            await style.evaluate(element => element.remove());
            const directory = path.join(assets, name + '.imageset');
            fs.mkdirSync(directory, { recursive: true });
            const images = [];
            for (const scale of [1, 2, 3]) {
                const filename = `${name}@${scale}x.png`;
                await sharp(buffer).resize(clip.width * scale, clip.height * scale).png().toFile(path.join(directory, filename));
                images.push({ filename, idiom: 'universal', scale: `${scale}x` });
            }
            fs.writeFileSync(path.join(directory, 'Contents.json'), JSON.stringify({ images, info: { author: 'xcode', version: 1 } }, null, 2) + '\n');
        };
        await exportLayer('LaunchArtwork', { x: 0, y: 0, width: 402, height: 874 }, '.identity,.signoff{visibility:hidden}');
        await exportLayer('LaunchIdentity', { x: 71, y: 299, width: 260, height: 223 }, '.screen{background:transparent}.art,.signoff{visibility:hidden}');
        await exportLayer('LaunchSignoff', { x: 111, y: 715, width: 180, height: 74 }, '.screen{background:transparent}.art,.identity{visibility:hidden}');
        const colorDirectory = path.join(assets, 'LaunchBackground.colorset');
        fs.mkdirSync(colorDirectory, { recursive: true });
        fs.writeFileSync(path.join(colorDirectory, 'Contents.json'), JSON.stringify({ colors: [{ idiom: 'universal', color: { 'color-space': 'srgb', components: { red: '0x09', green: '0x2A', blue: '0x2A', alpha: '1.000' } } }], info: { author: 'xcode', version: 1 } }, null, 2) + '\n');
        console.log('Exported approved deep green launch layers at 1x, 2x and 3x.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
