// Audit UI obrazovku po obrazovce (Chromium, telefon 360×740): chyby JS, vodorovné přetečení, tlačítka bez popisku,
// pole bez názvu. Most VEGA je náhrada s realistickými daty – ověřuje se vykreslení a ovládání, ne Kotlin.
// Spuštění: node tools/ui-tests/audit.cjs [složka pro snímky]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const root = path.join(__dirname, '../../vega/src/main/assets/ui');
const outDir = process.argv[2];
// FONT_SCALE=1.3 / 2: velké systémové písmo (WebView textZoom podle fontScale) – stejné kontroly, zvětšené písmo
const FONT_SCALE = parseFloat(process.env.FONT_SCALE || '1');

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

// konverzace se všemi druhy karet + záludný obsah (dlouhé URL a slova, široká tabulka, kód)
const LONG_URL = 'https://www.example.org/velmi/dlouha/cesta/k/clanku/o/tom/jak/se/pocita/urok/z/prodleni/podle/obcanskeho/zakoniku?utm_source=x&id=1234567890';
const CHAT = [
  { role: 'user', text: 'Nejdelšíslovobezmezerkterésenevejdenadisplejtelefonuanipřizmenšeníapůjdemimo ' + LONG_URL },
  { role: 'vega', text: '## Výsledek\nTady je **tučně**, `kód`, odkaz ' + LONG_URL + ' a [zdroj](' + LONG_URL + ') [1].\n```\nconst x = \"velmi dlouhý řádek kódu, který se nezalomí sám od sebe a měl by se posouvat vodorovně\";\n```',
    card: { type: 'run', run: 'r1', name: 'Výpis z banky → tabulka', value: { kind: 'TABLE', headers: ['Datum', 'Protistrana', 'Popis platby', 'Částka', 'Měna', 'Saldo', 'Kategorie'], rows: [['01.03.2026', 'Nějaký velmi dlouhý název obchodníka s.r.o.', 'Platba kartou v zahraničí', '-1 234,50', 'CZK', '12 000,00', 'jídlo']], total: 1, notes: ['Součet výdajů 1 234,50 Kč'] } } },
  { role: 'vega', text: 'Změny:', card: { type: 'diff', title: 'Úprava', files: [{ path: 'src/velmi/dlouha/cesta/k/souboru/KomponentaSeStrasneDlouhymNazvem.tsx', added: 3, removed: 1 }], changes: [], task: [] } },
  { role: 'vega', text: 'Kalkulace:', card: { type: 'econ', profit: -2000, price: 250, variable: 150, fixed: 8000, breakEven: 80 } },
  { role: 'vega', text: 'Chyba:', card: { type: 'run', run: 'r2', name: 'Import', failed: true, error: 'Soubor nemá očekávané sloupce: ' + LONG_URL, value: { kind: 'TEXT', text: '' } } },
  { role: 'vega', text: 'Zdroje:', card: { type: 'sources', items: [{ n: 1, title: 'Článek s hodně dlouhým titulkem o úroku z prodlení', url: LONG_URL, text: 'x' }],
    checks: [{ claim: 'Úrok z prodlení je repo sazba ČNB + 8 procentních bodů.', kind: 'tvrzení', n: 1, label: 'PODPORUJE', cz: 'podporuje', reason: '' }],
    audit: { outcome: 'PARTIAL', render: 'doložená tvrzení', sources: 1, origins: 1, claims: 2, supported: 1, conflicts: 0, revisions: 0 } } },
];

// den 24. 11., kolo 18: výpis → souhrn po měsících + doplňující odpovědi (dlouhé popisy plateb) + tři návrhy pod polem
const CHAT_STMT = [
  { role: 'user', text: 'Zpracuj výpis z banky.' },
  { role: 'vega', text: 'Výpis zpracován: 412 pohybů. **Největší výdaje:** Bydlení a energie −12 500,00 · Potraviny −8 431,20 · Restaurace a kavárny −2 310,00\n**Pravidelné platby:** 4 (Netflix, nájem, O2, pojištění)',
    card: { type: 'run', run: 'rs', name: 'Výpis z banky', value: { kind: 'TABLE', headers: ['Měsíc', 'Příjmy', 'Výdaje', 'Saldo', 'Pohybů'], rows: [['2026-10', '61 000,00', '−48 210,50', '12 789,50', '203'], ['2026-11', '31 000,00', '−40 120,00', '−9 120,00', '209']], total: 2, notes: ['Příjmy 92 000,00 · výdaje −88 330,50 · saldo 3 669,50 · 412 pohybů'] } } },
  { role: 'user', text: 'jaká byla největší platba?' },
  { role: 'vega', text: 'Největší platby:\n• −12 500,00 Kč – Platba kartou VELMIDLOUHYNAZEVOBCHODNIKABEZMEZERKTERYSENEVEJDE*PRAHA (5. 11.)\n• −3 400,00 Kč – ALZA.CZ (18. 11.)\n_Z tabulky výpisu, bez modelu._' },
];
const SCREENS = [
  ['výpis-návrhy', ['__chat', CHAT_STMT]],
  ['konverzace-karty', ['__chat']],
  ['úvod', null],
  ['menu', ['library']],
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
  ['souhlas-cloud', ['__ev', 'consent', { req: 'q1', target: 'Gemini · gemini-2.5-flash', fallback: ['Groq', 'Cerebras', 'OpenRouter'], chars: 123456, items: ['výpis-2026-03.csv (12 000 znaků)', 'smlouva o nájmu bytu – Praha 3, Žižkov, ulice Seifertova.pdf'], notes: ['Poznámka'], paid: true, estimateKc: 1.2 }]],
  ['schválení-ceny', ['__ev', 'approve', { req: 'q2', overCap: false, noFree: false, reason: 'Gemini: denní limit vyčerpán (429 RESOURCE_EXHAUSTED quota exceeded for metric generate_content_free_tier_requests)', name: 'Claude', capKc: 3.5, estimateKc: 2.9, calls: 2, chars: 40000, hint: 'Trvale: Nastavení → Pokročilé → společný strop.' }]],
  ['zdroje', ['sources', { src: JSON.stringify([{ n: 1, title: 'Článek s velmi dlouhým titulkem o úroku z prodlení podle občanského zákoníku', url: 'https://www.example.org/velmi/dlouha/cesta/k/clanku?id=1234567890&utm_source=newsletter', text: 'Text pasáže '.repeat(40), fetched: '2026-11-21', date: '2026-01-02' }]) }]],
  ['opora-citace', ['chkOpen', { i: '0', c: JSON.stringify({ x: { n: 1, label: 'NEDOSTACUJE', claim: 'Úrok z prodlení je repo sazba + 8 p. b.', kind: 'tvrzení', reason: 'pasáž o sazbě nemluví', ws: 0, we: 20, p: [0.1, 0.8, 0.1] }, item: { n: 1, title: 'Zdroj', url: 'https://example.org', text: 'Pasáž zdroje, která o sazbě nemluví a je dlouhá. '.repeat(10) } }) }]],
  ['konverzace-menu', ['chatMenu', { id: 'c1', t: 'Rozpočet na dovolenou v Chorvatsku a co všechno zabalit s dětmi' }]],
  ['pokrytí', ['coverage', { cov: JSON.stringify({ items: [{ q: 'Kolik stojí ubytování na týden pro čtyřčlennou rodinu v srpnu?', ok: true }, { q: 'Jaké jsou poplatky za dálnici?', ok: false }] }) }]],
  ['pokrytí-s-pasážemi', ['coverage', { cov: JSON.stringify({ items: [{ q: 'Otázka', ok: true }], passages: [{ id: 'P1', text: 'Pasáž '.repeat(80) }] }) }]],
  ['záznam-běhu', ['taskLog', { log: JSON.stringify([{ at: Date.now(), text: 'Načten soubor výpis-2026-03.csv (1 234 řádků)', ok: true }, { at: Date.now(), text: 'Chyba: neznámý formát data „2026/13/45“', ok: false }]) }]],
];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 360, height: 740 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(d => {
    window.__calls = [];
    window.VEGA = new Proxy({}, { get(_, k) { return (...a) => { window.__calls.push(k); if (k === 'messages' && window.__chat) return JSON.stringify(window.__chat); const v = d[k]; return v === undefined ? '' : JSON.stringify(v); }; } });
    try { localStorage.clear(); } catch (e) { }
  }, DATA);
  await page.goto('file://' + path.join(root, 'index.html'));
  await page.waitForTimeout(300);
  const problems = [];
  for (const [name, action, after] of SCREENS) {
    const before = errors.length;
    await page.evaluate(([a, chat]) => {
      S.req = null; closeSheet(true);
      if (a && a[0] === '__ev') { window.vega.on(a[1], a[2]); return; }
      if (a && a[0] === '__chat') { window.__chat = chat; S.mc = null; render(); return; }
      if (window.__chat) { window.__chat = null; S.mc = null; render(); }
      if (a) act(a[0], a[1] || {});
    }, [action, action && action[0] === '__chat' && action[1] ? action[1] : CHAT]);
    if (after) await page.evaluate(after);
    await page.waitForTimeout(450); // po doběhnutí animace panelu (0,2 s)
    if (FONT_SCALE !== 1) { await page.evaluate(k => { const els = [...document.querySelectorAll('body *')].filter(el => !el.dataset.fs); const fs = els.map(el => parseFloat(getComputedStyle(el).fontSize)); els.forEach((el, i) => { el.dataset.fs = '1'; el.style.setProperty('font-size', (fs[i] * k) + 'px', 'important'); }); }, FONT_SCALE); await page.waitForTimeout(100); }
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
      // svislé posuvné plochy (chat, panel): obsah nesmí jít posouvat do strany (overflow-y:auto dělá i overflow-x:auto)
      for (const el of document.querySelectorAll('#stream, #layer .sheet')) {
        if (vis(el) && el.scrollWidth > el.clientWidth + 1) {
          const wide = [...el.querySelectorAll('*')].filter(c => vis(c) && c.getBoundingClientRect().right > el.getBoundingClientRect().right + 1 && !(c.closest('.tbl,pre,.diff') && c.closest('.tbl,pre,.diff') !== c))
            .slice(0, 3).map(c => `<${c.tagName.toLowerCase()} class="${c.className}"> „${(c.textContent || '').trim().slice(0, 30)}“`);
          out.push(`${el.id || 'panel'} jde posouvat do strany (${el.scrollWidth} > ${el.clientWidth}px): ${wide.join(', ')}`);
        }
      }
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
      // panel (dialog) musí mít nadpis – jinak ho čtečka nepojmenuje a fokus do něj nepřejde (TalkBack čte dál obsah pod ním)
      const dlg = document.querySelector('#layer .sheet');
      if (dlg && vis(dlg) && !dlg.getAttribute('aria-labelledby') && !dlg.getAttribute('aria-label')) out.push('panel bez nadpisu pro čtečku (h2 / aria-label)');
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
  process.exitCode = problems.some(([, a]) => a.some(x => x.startsWith('CHYBA JS') || x.startsWith('stránka je širší') || (FONT_SCALE !== 1 && x.includes('jde posouvat do strany')))) ? 1 : 0;
})();
