// Zoom and rotation check, required in every app (same file in each of Martin's apps).
// 1. Settings: no web page blocks pinch zoom, no manifest or Android activity locks the orientation,
//    every Android WebView has pinch zoom on, every Tauri window has zoom keys (Ctrl/Cmd + and -) on.
// 2. On a phone and a tablet: the page is opened upright, turned sideways, turned back, and must re-fit
//    each time (nothing runs off the side); then a two-finger pinch must really zoom the page.
// Used by scripts/check-screens.mjs.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, basename } from 'node:path';

const SKIP = new Set(['node_modules', '.git', 'dist', 'build', 'target', 'gen', '.dart_tool', 'vendor', 'screens', '.gradle']);

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP.has(name)) continue;
    const p = join(dir, name);
    const s = statSync(p);
    if (s.isDirectory()) walk(p, out);
    else if (s.size < 8_000_000) out.push(p);
  }
  return out;
}

export function checkZoomRotationSettings(root) {
  const problems = [];
  for (const file of walk(root)) {
    const rel = relative(root, file);
    const name = basename(file);
    if (/\.html?$/.test(name)) {
      const text = readFileSync(file, 'utf8');
      for (const m of text.matchAll(/<meta[^>]*name=["']?viewport["']?[^>]*>/gi)) {
        if (/user-scalable\s*=\s*(no|0)/i.test(m[0])) problems.push(`${rel}: the viewport blocks pinch zoom (user-scalable=no)`);
        const max = /maximum-scale\s*=\s*([\d.]+)/i.exec(m[0]);
        if (max && Number(max[1]) < 5) problems.push(`${rel}: the viewport limits zoom (maximum-scale=${max[1]})`);
      }
    } else if (/\.webmanifest$/.test(name) || name === 'manifest.json') {
      try {
        const o = JSON.parse(readFileSync(file, 'utf8')).orientation;
        if (o && o !== 'any' && o !== 'natural') problems.push(`${rel}: the installed app is locked to "${o}" (use "any")`);
      } catch { /* not a web app manifest */ }
    } else if (name === 'AndroidManifest.xml') {
      const m = /android:screenOrientation="([^"]+)"/.exec(readFileSync(file, 'utf8'));
      if (m && !['unspecified', 'sensor', 'fullSensor', 'fullUser', 'user'].includes(m[1])) problems.push(`${rel}: the Android app is locked to ${m[1]}`);
    } else if (/\.(java|kt)$/.test(name)) {
      const text = readFileSync(file, 'utf8');
      if (/getSettings\(\)|\.settings\b/.test(text) && /WebView/.test(text) && !/setBuiltInZoomControls\(true\)|builtInZoomControls\s*=\s*true/.test(text)) problems.push(`${rel}: the WebView has pinch zoom off (setSupportZoom/setBuiltInZoomControls)`);
    } else if (name === 'tauri.conf.json') {
      for (const w of JSON.parse(readFileSync(file, 'utf8')).app?.windows ?? []) {
        if (w.zoomHotkeysEnabled !== true) problems.push(`${rel}: window "${w.title ?? w.label ?? 'main'}" has zoom keys off (zoomHotkeysEnabled)`);
      }
    } else if (name === 'project.godot') {
      const m = /window\/handheld\/orientation=(\d+)/.exec(readFileSync(file, 'utf8'));
      if (m && m[1] !== '6') problems.push(`${rel}: the game is locked to one orientation (window/handheld/orientation=${m[1]}, use 6 = sensor)`);
    }
  }
  return problems;
}

const fits = () => {
  const out = [];
  const W = document.documentElement.clientWidth;
  if (Math.abs(window.innerWidth - W) > 20) out.push(`the page did not re-fit (${window.innerWidth} wide, screen ${W})`);
  if (document.documentElement.scrollWidth > W + 1) out.push(`the page scrolls sideways (${document.documentElement.scrollWidth} > ${W})`);
  for (const el of document.querySelectorAll('button, input, select, textarea, h1, h2, h3, img, canvas, iframe, video')) {
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height || getComputedStyle(el).visibility === 'hidden') continue;
    let scrolls = false;
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o === 'auto' || o === 'scroll') { scrolls = true; break; } }
    if (scrolls || r.right < 0 || r.left > W) continue; // inside a sideways strip, or a hidden drawer
    if (r.left < -1 || r.right > W + 1) out.push(`${el.tagName.toLowerCase()} "${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30)}" runs off the side`);
  }
  return [...new Set(out)];
};

const DEVICES = [
  { name: 'phone', portrait: { width: 390, height: 844 }, deviceScaleFactor: 3 },
  { name: 'tablet', portrait: { width: 820, height: 1180 }, deviceScaleFactor: 2 },
];

// prepare(page) opens the screen to test (optional); audit runs in the page and returns a list of problems.
export async function checkRotationAndPinch(browser, url, { prepare, audit = fits, contextOptions = {}, pinch = true } = {}) {
  const problems = [];
  let turns = 0;
  for (const d of DEVICES) {
    const ctx = await browser.newContext({ viewport: d.portrait, deviceScaleFactor: d.deviceScaleFactor, isMobile: true, hasTouch: true, serviceWorkers: 'block', ...contextOptions });
    const page = await ctx.newPage();
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(600);
      if (prepare) await prepare(page);
      const steps = [['upright', d.portrait], ['turned sideways', { width: d.portrait.height, height: d.portrait.width }], ['turned back upright', d.portrait]];
      for (const [label, vp] of steps) {
        await page.setViewportSize(vp);
        await page.waitForTimeout(500);
        turns++;
        for (const i of [...await page.evaluate(audit), ...(audit === fits ? [] : await page.evaluate(fits))]) problems.push(`${d.name} ${label} (${vp.width}x${vp.height}): ${i}`);
      }
      if (pinch) {
        const cdp = await ctx.newCDPSession(page);
        await cdp.send('Input.synthesizePinchGesture', { x: d.portrait.width / 2, y: d.portrait.height / 2, scaleFactor: 2.5, gestureSourceType: 'default', relativeSpeed: 400 });
        await page.waitForTimeout(300);
        const scale = await page.evaluate(() => window.visualViewport ? window.visualViewport.scale : 1);
        if (scale < 1.3) problems.push(`${d.name}: a two-finger pinch does not zoom the page (scale ${scale.toFixed(2)})`);
      }
    } finally {
      await ctx.close();
    }
  }
  return { problems, turns };
}
