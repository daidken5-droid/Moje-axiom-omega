// Audit UI obrazovku po obrazovce (Chromium, telefon 360×740): chyby JS, vodorovné přetečení, tlačítka bez popisku,
// pole bez názvu. Most VEGA je náhrada s realistickými daty – ověřuje se vykreslení a ovládání, ne Kotlin.
// Spuštění: node tools/ui-tests/audit.cjs [složka pro snímky]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const root = path.join(__dirname, '../../vega/src/main/assets/ui');
const outDir = process.argv[2];

const DATA = {
  isBusy: false,
  home: { userName: 'Lukáš', model: 'Gemini 2.5 Flash (zdarma)', engine: true, buttons: [{ id: 'p1', name: 'Výpis z banky → tabulka', inputKind: 'BYTES' }] },
  chats: [{ id: 'c1', title: 'Rozpočet na dovolenou v Chorvatsku a co všechno zabalit s dětmi' }, { id: 'c2', title: 'Faktura' }],
  messages: [],
  settings: { keys: { gemini: true, groq: true }, allowed: {}, budgets: {}, spent: { geminipro: 0 }, models: { gemini: 'gemini-2.5-flash' }, webAllowed: true, airgap: false,
    indexed: 3, requests: { own: 0, commercial: 12 }, spentKc: 0, usdKc: 23.5, userName: 'Lukáš', ownUrl: '', verifierReady: false, verifier: 'ověřovač citací není nainstalovaný' },
  providerChoices: [{ id: 'gemini', name: 'Gemini', model: 'gemini-2.5-flash', paid: false, ready: true, active: true }, { id: 'claude', name: 'Claude', model: 'claude-sonnet', paid: true, allowed: false, ready: false }],
  tools: [{ id: 'csv', group: 'Data', title: 'Tabulka z CSV', description: 'načte CSV v češtině (středník, desetinná čárka)', offline: true, params: [], input: ['TEXT'] },
    { id: 'url', group: 'Web', title: 'Přečíst stránku', description: 'stáhne a vyčte text z adresy', offline: false, params: [{ key: 'max', label: 'Max. znaků', default: '5000', hint: '' }], input: ['URL'] }],
  procedures: [{ id: 'p1', name: 'Výpis z banky → tabulka příjmů a výdajů', version: 3, inputDesc: 'CSV výpis', inputKind: 'BYTES', outputKind: 'TABLE', steps: [1, 2, 3], perms: [] }],
  projects: [{ id: 'pr1', name: 'Web pro kavárnu', files: 12, next: 'Doplnit kontaktní formulář' }],
  project: { id: 'pr1', name: 'Web pro kavárnu', revision: 'a1b2c3', next: '', changes: [{ id: 1, path: 'index.html', op: 'edit', note: 'nadpis' }], files: [{ path: 'index.html', size: 1200 }, { path: '.env', secret: true }], verifications: [], symbols: [] },
  memory: [{ id: 'm1', text: 'Mám dvě děti, 6 a 9 let.', kind: 'fakt', scope: 'global', why: 'řekl jsi „pamatuj si“', canRevert: false }],
  improvements: { rules: [], weak: [{ kind: 'router', area: 'kurzy', count: 3, example: '100 eur kolik to je', propose: true, raw: 'x' }], extensions: [] },
  selfUpdates: { canInstall: true, hasToken: false, repo: 'daidken5-droid/Moje-axiom-omega', base: 'main', auto: false, items: [] },
  hwCapabilities: { hce: true, nfc: true, nfcOn: true, ble: true },
  hwJson: { activeId: 'k1', cards: [{ id: 'k1', name: 'Vrtačka Bosch GSB 18V', publicId: 'INV-00123', shared: ['umístění'], fields: { umístění: 'dílna' } }] },
  rootState: { allowed: false },
  indexDocs: [{ id: 'd1', name: 'faktura-2026-03.pdf' }, { id: 'd2', name: 'smlouva o nájmu bytu – Praha 3, Žižkov, ulice Seifertova.pdf' }],
  demandSample: { creditor: 'Jan Novák', debtor: 'Firma s.r.o.', invoice: 'FV-2026-001', amount: 12000, due: '2026-09-01' },
  ready: true, activate: true, chatRev: '',
};

const SCREENS = [
  ['úvod', null],
  ['menu', ['drawer']],
  ['přidat', ['plus']],
  ['přidat-pokročilé', ['plus'], () => { const d = document.querySelector('#layer details'); if (d) d.open = true; }],
  ['nástroje', ['tools']],
  ['nastavení', ['settings']],
  ['nastavení-pokročilé', ['settings'], () => document.querySelectorAll('#layer details').forEach(d => d.open = true)],
  ['kdo-odpovídá', ['pickModel']],
  ['průvodce-zdarma', ['freeSetup']],
  ['hardware', ['hw']],
  ['hardware-kompatibilita', ['hw'], () => document.querySelectorAll('#layer details').forEach(d => d.open = true)],
  ['nová-karta', ['hwNew']],
  ['wifi-heslo-qr', ['wifiTools']],
  ['zápis-ndef', ['ndefWrite']],
  ['wifi-vysvětlení-polohy', ['wifi']],
  ['projekty', ['libTab', { tab: 'projects' }]],
  ['moje-nástroje', ['libTab', { tab: 'procs' }]],
  ['paměť', ['libTab', { tab: 'memory' }]],
  ['vylepšení', ['libTab', { tab: 'improve' }]],
  ['dokumenty', ['docsManage']],
  ['předžalobní-výzva', ['demand']],
];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 360, height: 740 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(d => {
    window.__calls = [];
    window.VEGA = new Proxy({}, { get(_, k) { return (...a) => { window.__calls.push(k); const v = d[k]; return v === undefined ? '' : JSON.stringify(v); }; } });
    try { localStorage.clear(); } catch (e) { }
  }, DATA);
  await page.goto('file://' + path.join(root, 'index.html'));
  await page.waitForTimeout(300);
  const problems = [];
  for (const [name, action, after] of SCREENS) {
    const before = errors.length;
    await page.evaluate(([a]) => { closeSheet(true); if (a) act(a[0], a[1] || {}); }, [action]);
    if (after) await page.evaluate(after);
    await page.waitForTimeout(450); // po doběhnutí animace panelu (0,2 s)
    const r = await page.evaluate(() => {
      const W = innerWidth, out = [];
      const vis = el => { const s = getComputedStyle(el); const b = el.getBoundingClientRect(); return s.display !== 'none' && s.visibility !== 'hidden' && b.width > 0 && b.height > 0; };
      // přetečení: prvek vyčnívá vpravo a není uvnitř vodorovně posuvného kontejneru
      const scroller = el => { for (let p = el.parentElement; p; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o === 'auto' || o === 'scroll' || o === 'hidden') return true; } return false; };
      for (const el of document.querySelectorAll('body *')) {
        if (!vis(el)) continue;
        const b = el.getBoundingClientRect();
        if (b.right > W + 1 && !scroller(el)) out.push(`přetéká (${Math.round(b.right)} > ${W}px): <${el.tagName.toLowerCase()} class="${el.className}"> „${(el.textContent || '').trim().slice(0, 40)}“`);
      }
      if (document.documentElement.scrollWidth > W + 1) out.push(`stránka je širší než displej: ${document.documentElement.scrollWidth}px`);
      for (const el of document.querySelectorAll('button')) {
        if (!vis(el)) continue;
        const name = (el.getAttribute('aria-label') || el.textContent || el.title || '').trim();
        if (!name || /^[⋯…·•+×✕]$/.test(name) && !el.getAttribute('aria-label')) out.push(`tlačítko bez popisku pro čtečku: <button data-act="${el.dataset.act}"> „${name}“`);
      }
      for (const el of document.querySelectorAll('input:not([type=hidden]):not([type=checkbox]),textarea,select')) {
        if (!vis(el)) continue;
        const lab = el.getAttribute('aria-label') || el.placeholder || (el.id && document.querySelector(`label[for="${el.id}"]`)) || el.closest('label');
        if (!lab) out.push(`pole bez názvu: <${el.tagName.toLowerCase()} id="${el.id}">`);
      }
      // malé dotykové cíle (< 32 px výšky) u tlačítek – na telefonu se špatně trefují
      for (const el of document.querySelectorAll('#layer button, .bar button')) {
        if (!vis(el)) continue; const b = el.getBoundingClientRect();
        if (b.height < 32 && b.width < 120) out.push(`malý dotykový cíl ${Math.round(b.width)}×${Math.round(b.height)}: „${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}“`);
      }
      return out;
    });
    const errs = errors.slice(before);
    if (outDir) { fs.mkdirSync(outDir, { recursive: true }); await page.screenshot({ path: path.join(outDir, name + '.png'), fullPage: false }); }
    const all = [...errs.map(e => 'CHYBA JS: ' + e), ...r];
    console.log(`${all.length ? '✗' : '✓'} ${name}${all.length ? '\n   ' + all.join('\n   ') : ''}`);
    if (all.length) problems.push([name, all]);
  }
  await browser.close();
  console.log(`\n${SCREENS.length} obrazovek, ${problems.length} s nálezy.`);
  process.exitCode = problems.some(([, a]) => a.some(x => x.startsWith('CHYBA JS') || x.startsWith('stránka je širší'))) ? 1 : 0;
})();
