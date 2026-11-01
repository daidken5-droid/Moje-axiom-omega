// Screenshoty všech obrazovek: node shoot.js <out-dir>
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const outDir = process.argv[2] || '/tmp/preview';
  const base = 'file://' + path.join(__dirname, 'out', 'index.html');
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 412, height: 892 }, deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  p.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  const shot = async n => { await p.waitForTimeout(250); await p.screenshot({ path: `${outDir}/${n}.png` }); };
  await p.goto(base + '?welcome'); await shot('01-welcome');
  await p.goto(base); await shot('02-chat');
  await p.click('[data-new-thread]').catch(() => {});
  await p.evaluate(() => { threadId = 0; msgs = []; live = null; render(); }); await shot('03-empty');
  await p.evaluate(() => { __live(); loadThread(2); omegaReceive('phase', { text: 'L4 · Red-team (2/3) · Kimi K3 · cloud (kimi-k3)' }); omegaReceive('stream', { pass: 2, passes: 3, text: '**Slabina 1 – marže:** karty z Japonska mají po DPH a dopravě marži jen 12–18 %…\n\n- Riziko padělků\n- Kurzové riziko JPY', reasoning: 'Zvažuji clo, DPH, konkurenci Cardmarketu…' }); }); await shot('04-streaming');
  await p.evaluate(() => { live = null; go('brains'); }); await shot('05-brains');
  await p.evaluate(() => { memTab = 'mem'; go('memory'); }); await shot('06-memory');
  await p.evaluate(() => { memTab = 'db'; go('memory'); }); await shot('07-offline-db');
  await p.evaluate(() => go('self')); await shot('08-self');
  await p.evaluate(() => go('system')); await shot('09-system');
  await p.evaluate(() => { go('chat'); modeSheet(); }); await shot('10-modes');
  await p.goto(base + '?theme=platinum'); await shot('11-platinum');
  console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'no js errors');
  await b.close();
})();
