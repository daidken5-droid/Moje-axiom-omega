// Vložení HTML/JS do UI: každá událost a karta dostane ve všech polích řetězec s <img onerror>, markdown odkaz javascript: apod.
// WebView má most do Kotlinu (window.VEGA) – spuštěný cizí skript = převzetí aplikace. Spuštění: node tools/ui-tests/xss.cjs
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const root = path.join(__dirname, '../../vega/src/main/assets/ui');
const src = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const onSrc = src.slice(src.indexOf('window.vega = {')); const evs = [...new Set([...onSrc.slice(0, onSrc.indexOf('\n};')).matchAll(/case '([a-zA-Z.]+)':/g)].map(m => m[1]))];
const cardSrc = src.slice(src.indexOf('function card(c, isLast)')); const cards = [...new Set([...cardSrc.slice(0, cardSrc.indexOf('\n}\n')).matchAll(/case '([a-zA-Z]+)':/g)].map(m => m[1]))];
const keys = [...new Set([...src.matchAll(/\b(?:c|d|m|x|it|f|r|p|s|e|t|a|v)\.([a-z][a-zA-Z]+)\b/g)].map(m => m[1]))]
  .filter(k => !['map', 'slice', 'includes', 'split', 'startsWith', 'lastIndexOf', 'preventDefault', 'getAttribute', 'setAttribute', 'dataset', 'tagName', 'shiftKey', 'type', 'role', 'kind', 'op', 'act', 'length', 'forEach', 'filter', 'join', 'push', 'trim', 'replace', 'toLowerCase', 'target', 'key', 'closest', 'value', 'checked', 'id'].includes(k));
const P = `"'><img src=x onerror="window.__xss=(window.__xss||'')+'1'"><svg onload="window.__xss='s'">[k](javascript:window.__xss='md')<a href="javascript:window.__xss='a'">a</a>`;
const obj = Object.fromEntries(keys.map(k => [k, P]));
const deep = Object.fromEntries(keys.map(k => [k, P]));
for (const k of ['items', 'files', 'checks', 'parts', 'lines', 'changes', 'rounds', 'candidates', 'steps', 'examples', 'history', 'tests', 'models', 'warnings', 'sample', 'fields', 'notes', 'passages', 'evidence', 'symbols', 'verifications', 'calls', 'params', 'perms', 'added', 'removed', 'tokens', 'stepValues', 'changelog', 'keys', 'headers', 'rows'])
  deep[k] = [P, obj, [P, P]];
deep.id = P; deep.url = 'javascript:window.__xss="u"';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 360, height: 740 } });
  await page.addInitScript(() => {
    window.__msgs = [];
    const data = { isBusy: false, home: { engine: true, model: 'Gemini' }, chats: [{ id: 'c1', title: 'T' }], settings: {}, ready: true, activate: true, chatRev: '' };
    window.VEGA = new Proxy({}, { get(_, k) { return () => k === 'messages' ? JSON.stringify(window.__msgs) : (data[k] === undefined ? '' : JSON.stringify(data[k])); } });
  });
  await page.goto('file://' + path.join(root, 'index.html'));
  await page.waitForTimeout(300);
  const hits = [];
  const check = async (what) => {
    // klik na všechny odkazy/tlačítka v obsahu by šel přes most; stačí: spustil se skript? je v DOM aktivní href=javascript:?
    const r = await page.evaluate(() => ({ where: [...document.querySelectorAll("img[onerror],svg[onload],[href^=\"javascript:\" i]")].map(e => (e.parentElement && e.parentElement.outerHTML || "").slice(0, 260)).slice(0,2), x: window.__xss || '', js: [...document.querySelectorAll('[href^="javascript:" i],[src^="javascript:" i]')].length, on: [...document.querySelectorAll('*')].filter(e => [...e.attributes].some(a => /^on/i.test(a.name))).map(e => e.tagName + ' ' + [...e.attributes].filter(a => /^on/i.test(a.name)).map(a => a.name).join(',')).slice(0, 3) }));
    await page.waitForTimeout(30);
    const x2 = await page.evaluate(() => window.__xss || '');
    if (r.x || x2 || r.js || r.on.length) hits.push(`${what}: xss=${r.x || x2} javascript:=${r.js} on*=${r.on.join(";")}${process.env.WHERE ? "\n   " + r.where.join("\n   ") : ""}`);
    await page.evaluate(() => { window.__xss = ''; });
  };
  for (const ev of evs) {
    await page.evaluate(([e, d]) => { try { S.req = null; closeSheet(true); } catch (x) { } try { window.vega.on(e, d); } catch (x) { } }, [ev, deep]).catch(() => { });
    await page.waitForTimeout(40);
    await check('událost ' + ev);
  }
  for (const t of cards) {
    await page.evaluate(([t, v]) => { window.__msgs = [{ role: 'vega', text: v.text, card: Object.assign({}, v, { type: t }) }, { role: 'user', text: v.text }]; S.mc = null; S.busy = false; try { render(); } catch (x) { } }, [t, deep]).catch(() => { });
    await page.waitForTimeout(40);
    await check('karta ' + t);
  }
  // den 24. 11., kolo 20: výpis z cizího souboru – popis platby v odpovědi, karta souhrnu, návrhy pod polem, název přílohy
  await page.evaluate(([p]) => {
    window.__msgs = [{ role: 'user', text: p }, { role: 'vega', text: 'U „' + p + '“ jsi utratil **−5,00 Kč** (1 platba).\n• −5,00 Kč – ' + p + '\n_Hledáno v popisu plateb, bez modelu._',
      card: { type: 'run', run: p, name: p, value: { kind: 'TABLE', headers: ['Měsíc', 'Příjmy', 'Výdaje', 'Saldo', 'Pohybů', p], rows: [[p, p, p, p, p, p]], total: 1, notes: [p] } } }, { role: 'user', text: p }];
    S.mc = null; S.busy = false; S.files = []; try { render(); } catch (x) { }
  }, [P]).catch(() => { });
  await page.waitForTimeout(40); await check('výpis + návrhy');
  await page.evaluate(([p]) => { S.files = [{ id: p, name: 'vypis' + p + '.pdf' }, { id: 'x', name: p + '.xlsx' }]; try { renderChips(); } catch (x) { } }, [P]).catch(() => { });
  await page.waitForTimeout(40); await check('příloha výpisu');
  await page.evaluate(() => { S.files = []; });
  // názvy konverzací v menu
  await page.evaluate(([p]) => { S.chats = [{ id: p, title: p }]; try { openDrawer && openDrawer(); } catch (x) { } }, [P]).catch(() => { });
  await page.waitForTimeout(40); await check('menu');
  await browser.close();
  console.log(hits.length ? hits.join('\n') : '');
  console.log(`${evs.length} událostí a ${cards.length} karet s vloženým HTML/JS: ${hits.length} průniků.`);
  process.exitCode = hits.length ? 1 : 0;
})();
