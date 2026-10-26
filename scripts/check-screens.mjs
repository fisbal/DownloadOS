// Opens the DownloadOS web page on phones, tablets, PC and Mac sizes, for every
// device tab, and fails when anything runs off the screen. GitHub is mocked.
// Usage: node scripts/check-screens.mjs [web dir]   (needs Playwright)
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkZoomRotationSettings, checkRotationAndPinch } from './zoom-rotation.mjs';

const dir = resolve(process.argv[2] || 'web');
const sizes = {
  'small-phone': [320, 568], phone: [390, 844], 'phone-landscape': [844, 390],
  tablet: [820, 1180], 'tablet-landscape': [1180, 820], pc: [1440, 900],
};
const types = { '.html': 'text/html', '.png': 'image/png', '.js': 'text/javascript', '.css': 'text/css' };
const server = createServer(async (req, res) => {
  const path = join(dir, decodeURIComponent(req.url.split('?')[0]).replace(/\/$/, '/index.html'));
  try { res.writeHead(200, { 'Content-Type': types[extname(path)] || 'application/octet-stream' }); res.end(await readFile(path)); }
  catch { res.writeHead(404); res.end(); }
}).listen(0);
const base = `http://127.0.0.1:${server.address().port}/`;

const long = 'A MartinB OS app with a fairly long description that has to wrap nicely on small phones';
const repos = ['DownloadOS', 'oi1', 'prodfos', 'MarbleOS-with-a-really-long-repository-name'].map(name => ({
  name, description: long, fork: false, archived: false, has_pages: true, homepage: '',
}));
const release = name => ({
  tag_name: 'v1.0.123', published_at: '2026-10-26T07:00:00Z', html_url: `https://github.com/fisbal/${name}/releases/tag/v1.0.123`,
  assets: ['android-arm64.apk', 'android.apk', 'Setup.exe', 'mac.dmg'].map(s => ({
    name: `${name}-${s}`, browser_download_url: `https://example.com/${name}-${s}`, size: 123456789 })),
});

const browser = await chromium.launch();
const failures = [];
for (const [label, [width, height]] of Object.entries(sizes)) {
  for (const locale of ['en-US', 'fr-FR']) {
    const ctx = await browser.newContext({ viewport: { width, height }, locale, isMobile: width < 900, hasTouch: width < 900 });
    const page = await ctx.newPage();
    await page.route('https://api.github.com/**', route => {
      const u = new URL(route.request().url());
      const m = u.pathname.match(/^\/repos\/fisbal\/([^/]+)\/releases\/latest$/);
      route.fulfill({ json: m ? release(m[1]) : repos });
    });
    await page.goto(base);
    await page.waitForSelector('.app');
    for (const device of ['android', 'windows', 'mac', 'web']) {
      await page.click(`[data-device="${device}"]`);
      const issues = await page.evaluate(() => {
        const out = [];
        const w = document.documentElement.clientWidth;
        if (document.documentElement.scrollWidth > w + 1) out.push(`page scrolls sideways (${document.documentElement.scrollWidth} > ${w})`);
        for (const el of document.querySelectorAll('main *')) {
          const r = el.getBoundingClientRect();
          if (r.width && (r.left < -1 || r.right > w + 1)) out.push(`${el.tagName.toLowerCase()} "${(el.textContent || '').trim().slice(0, 40)}" runs off the screen`);
          if (el.matches('a, button') && r.height && r.height < 40) out.push(`"${el.textContent.trim()}" is too small to tap (${Math.round(r.height)}px)`);
          if (el.matches('a, button') && el.scrollWidth > el.clientWidth + 1) out.push(`"${el.textContent.trim()}" is cut off`);
        }
        return out;
      });
      for (const i of issues) failures.push(`${label} ${width}x${height} ${locale} ${device}: ${i}`);
    }
    await ctx.close();
  }
}
// Zoom and rotation, required in every app: nothing blocks pinch zoom or locks the orientation,
// and a phone and a tablet turned sideways and back re-fit every time.
failures.push(...checkZoomRotationSettings(fileURLToPath(new URL('..', import.meta.url))));
const turned = await checkRotationAndPinch(browser, base, { prepare: async page => { await page.route('https://api.github.com/**', route => { const m = new URL(route.request().url()).pathname.match(/^\/repos\/fisbal\/([^/]+)\/releases\/latest$/); route.fulfill({ json: m ? release(m[1]) : repos }); }); await page.reload(); await page.waitForSelector('.app'); } });
failures.push(...turned.problems);
await browser.close();
server.close();
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`All screens fit: ${Object.keys(sizes).length} sizes x 2 languages x 4 devices, and the page re-fits when a phone or tablet turns (${turned.turns} turns) and pinch zooms.`);
