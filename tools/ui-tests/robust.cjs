// Odolnost UI proti neúplným datům: každá událost z nativní vrstvy a každý typ karty s prázdnými / neúplnými poli.
// Výjimka JS = zamrzlé UI na telefonu. Spuštění: node tools/ui-tests/robust.cjs
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const root = path.join(__dirname, '../../vega/src/main/assets/ui');
const src = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const onSrc = src.slice(src.indexOf('window.vega = {')); const evs = [...new Set([...onSrc.slice(0, onSrc.indexOf('\n};')).matchAll(/case '([a-zA-Z.]+)':/g)].map(m => m[1]))];
const cardSrc = src.slice(src.indexOf('function card(c, isLast)')); const cards = [...new Set([...cardSrc.slice(0, cardSrc.indexOf('\n}\n')).matchAll(/case '([a-zA-Z]+)':/g)].map(m => m[1]))];
const variants = [{}, { text: null, items: null, files: null }, { text: '', items: [], files: [], checks: [], parts: [], lines: [], changes: [], rounds: [] }];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 360, height: 740 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const handled = []; page.on('console', m => { if (m.type() === 'error') handled.push(m.text().slice(0, 60)); });
  await page.addInitScript(() => {
    window.__msgs = [];
    const data = { isBusy: false, home: { engine: true, model: 'Gemini' }, chats: [{ id: 'c1', title: 'T' }], settings: {}, ready: true, activate: true, chatRev: '' };
    window.VEGA = new Proxy({}, { get(_, k) { return () => k === 'messages' ? JSON.stringify(window.__msgs) : (data[k] === undefined ? '' : JSON.stringify(data[k])); } });
  });
  await page.goto('file://' + path.join(root, 'index.html'));
  await page.waitForTimeout(300);
  const bad = [];
  for (const ev of evs) for (const v of variants) {
    const before = errors.length;
    await page.evaluate(([e, d]) => { try { S.req = null; closeSheet(true); } catch (x) { } window.vega.on(e, d); }, [ev, v]).catch(e => errors.push(String(e)));
    await page.waitForTimeout(20);
    if (errors.length > before) bad.push(`událost „${ev}“ ${JSON.stringify(v)}: ${errors.slice(before).join(' | ').slice(0, 160)}`);
  }
  for (const t of cards) for (const v of variants) {
    const before = errors.length;
    await page.evaluate(([t, v]) => { window.__msgs = [{ role: 'vega', text: 'x', card: Object.assign({ type: t }, v) }]; S.mc = null; S.busy = false; render(); }, [t, v]).catch(e => errors.push(String(e)));
    await page.waitForTimeout(20);
    if (errors.length > before) bad.push(`karta „${t}“ ${JSON.stringify(v)}: ${errors.slice(before).join(' | ').slice(0, 160)}`);
  }
  await browser.close();
  console.log(bad.length ? bad.join('\n') : '');
  console.log(`${evs.length} událostí × ${variants.length} a ${cards.length} karet × ${variants.length}: ${bad.length} pádů; záchranný blok použit ${handled.length}× (data bez povinných polí – UI nespadlo).`);
  process.exitCode = bad.length ? 1 : 0;
})();
