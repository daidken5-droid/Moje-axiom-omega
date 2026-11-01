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
  await p.evaluate(() => { threadId = 0; msgs = []; live = null; render(); }); await shot('03-empty');
  await p.evaluate(() => { __live(); loadThread(2); omegaReceive('phase', { text: 'Agent · krok 3/10 · Kimi K3' }); omegaReceive('step', { index: 1, tool: 'web_search', thought: 'Hledám dodavatele' }); omegaReceive('step', { index: 2, tool: 'fetch_url', thought: 'Čtu ceník' }); omegaReceive('stream', { text: 'Porovnávám ceny…', reasoning: '' }); }); await shot('04-agent-live');
  await p.evaluate(() => { live = null; go('agents'); }); await shot('05-agents');
  await p.evaluate(() => go('modules')); await p.click('[data-hf-search]'); await p.waitForTimeout(200); await shot('06-modules');
  await p.evaluate(() => window.scrollTo(0, 0)); await p.evaluate(() => { const m = document.querySelector('#main'); m.scrollTop = m.scrollHeight; }); await shot('07-modules-bottom');
  await p.evaluate(() => { memTab = 'kb'; go('memory'); }); await shot('08-offline-db');
  await p.evaluate(() => { memTab = 'mem'; go('memory'); }); await shot('09-memory');
  await p.evaluate(() => go('system')); await shot('10-system');
  await p.evaluate(() => { go('chat'); kindSheet(); }); await shot('11-kind');
  await p.evaluate(() => { closeSheet(); selfSheet(); }); await shot('12-self');
  await p.goto(base + '?theme=platinum'); await shot('13-platinum');
  console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'no js errors');
  await b.close();
})();
