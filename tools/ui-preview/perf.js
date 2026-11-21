// Měření plynulosti UI s výkonem jako Pixel 9a: viewport 1080×2424 @ DPR 2,625 (411×923 CSS px),
// CPU zpomalené CDP throttlingem (výchozí 4× – mobilní WebView na Tensor G4 vůči desktopu), dotykové ovládání.
// Použití: node perf.js [throttle=4]
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const rate = Number(process.argv[2] || 4);
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 411, height: 923 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true });
  const p = await ctx.newPage();
  const cdp = await ctx.newCDPSession(p);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate });
  const errors = []; p.on('pageerror', e => errors.push(e.message));
  const t0 = Date.now();
  await p.goto('file://' + path.join(__dirname, 'out', 'index.html'));
  await p.waitForFunction(() => document.querySelector('#main .wrap'));
  const load = Date.now() - t0;
  // měří skript + vynucený přepočet rozvržení (layout), jako když uživatel čeká na obrazovku; 1. a 2. otevření
  const m = async (name, fn, selfTimed = false) => {
    const once = async () => p.evaluate(`(async () => { const s = performance.now(); const r = await (${fn.toString()})(); void document.body.offsetHeight; return ${selfTimed} ? r : performance.now() - s; })()`);
    const a = await once(); const b2 = await once();
    console.log(name.padEnd(36), String(Math.round(a)).padStart(5), 'ms', ' | znovu', String(Math.round(b2)).padStart(4), 'ms');
    return a;
  };
  console.log(`CPU ${rate}× zpomaleno · Pixel 9a viewport · načtení stránky ${load} ms`);
  await m('úvod (render hero)', () => { const s = performance.now(); threadId = 0; msgs = []; live = null; render(); return performance.now() - s; });
  await m('přepnutí na Nastavení', () => { const s = performance.now(); go('system'); return performance.now() - s; });
  await m('přepnutí na Moduly', () => { const s = performance.now(); go('modules'); return performance.now() - s; });
  await m('přepnutí na Paměť', () => { const s = performance.now(); go('memory'); return performance.now() - s; });
  await m('list voleb (výkon, varianty)', () => { const s = performance.now(); kindSheet(); const r = performance.now() - s; closeSheet(); return r; });
  await m('vlákno 200 zpráv (render)', () => {
    const base = 'Odpověď s **tučným** textem, seznamem:\n- bod jedna\n- bod dva\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\nA ještě odstavec textu, který je delší. ';
    const many = []; for (let i = 0; i < 200; i++) many.push(i % 2 ? { id: 1000 + i, role: 'assistant', content: base.repeat(3), meta: { engine: 'Gemma', millis: 4000, private: true } } : { id: 1000 + i, role: 'user', content: 'Otázka číslo ' + i });
    go('chat'); const s = performance.now(); threadId = 77; msgs = many; live = null; renderChat(); return performance.now() - s;
  });
  await m('stream: nejhorší snímek (60 snímků)', () => new Promise(res => {
    msgs = msgs.slice(-6); renderChat();
    let text = ''; const words = 'Tohle je streamovaná odpověď mozku v telefonu, která přibývá po slovech. '.split(' ');
    const s = performance.now(); let n = 0; let worst = 0;
    const tick = () => {
      const f = performance.now();
      for (let k = 0; k < 6; k++) text += words[(n * 6 + k) % words.length] + ' ';
      omegaReceive('stream', { pass: 1, passes: 1, text, reasoning: '' });
      worst = Math.max(worst, performance.now() - f);
      if (++n < 60) setTimeout(tick, 80); else { window.__worstFrame = worst; res(worst); }
    };
    live = { phase: 'Píšu', text: '', reasoning: '', steps: [] }; renderChat(); tick();
  }), true);
  await m('otevření seznamu vláken', () => { const s = performance.now(); openDrawer(); const r = performance.now() - s; closeDrawer(); return r; });
  console.log(errors.length ? 'CHYBY: ' + errors.join(' | ') : 'bez chyb JS');
  await b.close();
})();
