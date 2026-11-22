'use strict';
/*
 * VEGA – UI „simply clever“: jedna plocha, jeden vstup, návrhy podle kontextu, detaily až na klepnutí.
 * Data jen přes most VEGA (Kotlin). Stránka nesmí na síť (CSP). Žádné inline skripty.
 */
const B = window.VEGA;
const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const J = (s, d = null) => { if (s === '' || s == null) return d; try { const v = JSON.parse(s); if (v && v.error) { toast(v.error); return d; } return v; } catch (e) { return s; } };
const call = (m, ...a) => J(B[m](...a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x))));
/** Akce, která rozběhne úlohu: chyba mostu (null) nesmí nechat UI „zaneprázdněné“ navždy. */
const busyCall = (m, ...a) => { S.busy = true; S.busyChat = chatId(); const r = call(m, ...a); if (r === null) S.busy = false; return r; };
const fmt = n => Number(n || 0).toLocaleString('cs-CZ', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** Odkaz jen pro http(s) – „javascript:“ z NFC štítku, webu nebo modelu se nikdy nestane klikatelným. */
const safeUrl = u => /^https?:\/\/[^\s"'<>]+$/i.test(String(u || '')) ? String(u) : null;
const link = (u, label) => safeUrl(u) ? `<a href="${esc(u)}" rel="noopener">${esc(label ?? u)}</a>` : esc(label ?? u);
const BUSY_MSG = 'Ještě dokončuji předchozí odpověď – počkej, nebo ji zastav (■).';

const S = {
  space: { kind: 'chat', id: null, name: 'Konverzace' }, // chat | temp | project
  files: [], busy: false, stream: '', req: null, tool: null, toolInput: null,
  tempMsgs: [], lastCard: null, tools: null, libTab: 'projects', savedRuns: new Set(), procs: null,
};

/* ------------------------------------------------------------------ drobnosti */
function toast(t, ms = 3800) {
  document.querySelectorAll('.toast').forEach(x => x.remove());
  const el = document.createElement('div'); el.className = 'toast'; el.textContent = t; el.setAttribute('role', 'status');
  document.body.appendChild(el); setTimeout(() => el.remove(), ms);
}
function md(t) {
  return String(t || '').split(/```/).map((b, i) => i % 2 ? `<pre>${esc(b.replace(/^\w*\n/, ''))}</pre>` :
    mdTables(esc(b)).replace(/^#{1,4} (.*)$/gm, '<h3>$1</h3>')
      .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/(^|[\s(])_([^_\n]+?)_(?=[\s.,;:!?)]|$)/g, '$1<i>$2</i>').replace(/`([^`]+)`/g, '<code>$1</code>')
      // odrážky jako je píšou modely („*   položka“, „- položka“, vnořené odsazením); *kurzíva* až po nich, ať se „* “ nesplete
      .replace(/^([ \t]*)[-•*+][ \t]+(.*)$/gm, (m, ind, t) => (ind.length >= 2 ? '&nbsp;&nbsp;&nbsp;◦ ' : '• ') + t)
      .replace(/(^|[\s(])\*([^\s*][^*\n]*?)\*(?=[\s.,;:!?)]|$)/g, '$1<i>$2</i>')
      // odkazy: [text](https://…) i holé https://… – jen http(s) přes safeUrl (escapovaný text, žádné javascript:)
      .replace(/\[([^\]\n]{1,120})\]\((https?:\/\/[^\s)<>"']+)\)/g, (m, t, u) => linkEsc(u, t))
      .replace(/(^|[\s(])(https?:\/\/[^\s<>"')]+[^\s<>"').,;:!?])/g, (m, p, u) => p + linkEsc(u, u.length > 48 ? u.slice(0, 45) + '…' : u))
      .replace(/\[(\d+)\]/g, '<sup>[$1]</sup>').replace(/\n/g, '<br>').replace(/(<\/h3>|<\/table><\/div>)<br>/g, '$1')).join('');
}
/** Text odpovědi bez markdown značek – pro kopírování a sdílení (do e-mailu nepatří „**“ ani „|---|“). */
function plainText(t) {
  return String(t || '').replace(/```\w*\n?/g, '').replace(/^\|[ \t:|-]*-[ \t:|-]*\|[ \t]*(\n|$)/gm, '').replace(/^\|(.*)\|[ \t]*$/gm, (m, c) => c.split('|').map(x => x.trim()).join('\t'))
    .replace(/^#{1,4} /gm, '').replace(/\*\*(.+?)\*\*/g, '$1').replace(/^([ \t]*)[*+-][ \t]+/gm, '$1• ').replace(/(^|[\s(])[*_]([^\s*_][^*_\n]*?)[*_](?=[\s.,;:!?)]|$)/g, '$1$2')
    .replace(/`([^`]+)`/g, '$1').replace(/\[([^\]\n]+)\]\((https?:[^)\s]+)\)/g, '$1 ($2)').replace(/\n{3,}/g, '\n\n').trim();
}
/** Markdown tabulka (| a | b |, oddělovač |---|) → HTML tabulka; text buněk už je escapovaný, formátování (tučné, odkazy) projde dál. */
function mdTables(t) {
  return t.replace(/(^|\n)(\|[^\n]*\|[ \t]*\n\|[ \t:|-]*-[ \t:|-]*\|[ \t]*((?:\n\|[^\n]*\|[ \t]*)*))/g, (m, pre, all, body) => {
    const cells = l => l.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
    const rows = all.split('\n'); const head = cells(rows[0]);
    const tr = (cs, tag) => '<tr>' + cs.map(c => `<${tag}>${c}</${tag}>`).join('') + '</tr>';
    return pre + '<div class="tbl"><table>' + tr(head, 'th') + rows.slice(2).filter(l => l.trim()).map(l => tr(cells(l), 'td')).join('') + '</table></div>';
  });
}
/** „↓ Nová odpověď“: když uživatel čte výš, obrazovka neskáče dolů – jen nabídne skok. */
function jumpPill(on) {
  let b = $('#jump');
  if (!on) { if (b) b.remove(); return; }
  if (b || $('#layer .sheet')) return; // nad otevřeným dialogem ne
  b = document.createElement('button'); b.id = 'jump'; b.className = 'jump'; b.type = 'button'; b.textContent = '↓ Nová odpověď';
  b.addEventListener('click', () => { const st = $('#stream'); st.scrollTop = st.scrollHeight; S.stick = true; jumpPill(false); });
  document.body.appendChild(b);
}
/** Odkaz z UŽ escapovaného textu (md) – adresu vrátit do původní podoby jen pro kontrolu, do atributu zase escapovaně. */
function linkEsc(u, label) { const raw = u.replace(/&amp;/g, '&'); return safeUrl(raw) ? `<a href="${esc(raw)}" rel="noopener">${label}</a>` : label; }
/** localStorage jen pro drobné preference; v soukromém režimu / bez úložiště tiše nic (vrátí null). */
const lsGet = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { } };
const plural = (n, one, few, many) => `${n} ${n === 1 ? one : n >= 2 && n <= 4 ? few : many}`;
function hello() {
  const h = new Date().getHours();
  return h < 5 ? 'Dobrou noc' : h < 10 ? 'Dobré ráno' : h < 18 ? 'Ahoj' : 'Dobrý večer';
}
/** Pozdravy na úvodní obrazovce – střídají se klepnutím na hvězdu (oslovení podle Nastavení). */
const GREETS = [n => `${hello()}${n ? ', ' + n : ''}. Co dnes vyřešíme?`, n => `Na čem budeme pracovat${n ? ', ' + n : ''}?`, n => `Jak ti můžu pomoct${n ? ', ' + n : ''}?`,
  n => `Co tě dnes zajímá${n ? ', ' + n : ''}?`, n => `Pošli soubor, otázku nebo nápad${n ? ', ' + n : ''}.`];
let greetI = Math.floor(Math.random() * GREETS.length);
/** Vlastní znak VEGA (stejné „V“ s modrou stopou jako ikona aplikace). */
const STAR = `<svg viewBox="18 26 72 56" aria-hidden="true"><path class="v-body" fill="#F1EEE7" d="M24,35h12l15,28 11,-20h12L55,77h-9z"/><path class="v-trail" fill="#5B8CFF" d="M49,32h29l6,10 -10,17 -6,-10 4,-7H55z"/></svg>`;
const VOICE = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3" stroke="currentColor" stroke-width="1.9" fill="none" stroke-linecap="round"/></svg>`;
const isTouch = matchMedia('(pointer: coarse)').matches;
const procs = () => (S.procs = S.procs || call('procedures') || []);
const chatId = () => S.space.kind === 'temp' ? 'temp' : S.space.kind === 'project' ? 'proj-' + S.space.id : S.space.id;
function busyGuard() { if (S.busy) { toast(BUSY_MSG); return true; } return false; }

/* ------------------------------------------------------------------ plocha */
function msgs() {
  if (S.space.kind === 'temp') return S.tempMsgs;
  if (S.space.kind === 'chat' && !S.space.id) { const cs = call('chats') || []; S.space.id = cs[0]?.id || call('newChat'); }
  // cache podle revize: překreslení (idle, done, busy…) nečte a neparsuje celý soubor chatu znovu, když se nezměnil
  const id = chatId(), rev = B.chatRev ? B.chatRev(id) : '';
  if (rev && S.mc && S.mc.id === id && S.mc.rev === rev) return S.mc.list;
  const list = call('messages', id) || [];
  S.mc = rev ? { id, rev, list } : null;
  return list;
}

let msgsCount = 0;
function renderStream() {
  const list = msgs(); msgsCount = list.length;
  const el = $('#stream');
  document.body.classList.toggle('chatting', !!list.length || S.busy);
  if (!list.length && !S.busy) { el.innerHTML = emptyState(); S.lastCard = null; renderChips(); return; }
  // dlouhý chat: vykreslit jen posledních WINDOW zpráv (rychlost na telefonu); starší na klepnutí
  const WINDOW = 80; const key = S.space.kind + ':' + S.space.id;
  const shown = list.length > WINDOW && S.showAll !== key ? list.slice(-WINDOW) : list;
  const last = list[list.length - 1];
  el.innerHTML = (shown.length < list.length ? `<button class="pill older" data-act="older">Zobrazit starší zprávy (${list.length - shown.length})</button>` : '') + shown.map(m => m.role === 'user'
    ? `<div class="me">${esc(m.text)}${m.files && m.files.length ? `<span class="att">📎 ${m.files.map(esc).join(', ')}</span>` : ''}</div>`
    : `<div class="vega">${m.text ? `<div class="txt">${md(m.text)}</div>` : ''}${safeCard(m.card, m === last)}${m.text && m.text.length > 60 && !(m === last && S.busy) ? `<div class="msg-a"><button class="mini" data-act="copyMsg" data-i="${list.indexOf(m)}">Kopírovat</button><button class="mini" data-act="shareMsg" data-i="${list.indexOf(m)}">Sdílet</button></div>` : ''}</div>`).join('') +
    (S.busy && (!S.busyChat || S.busyChat === chatId()) ? `<div class="vega">${S.stream ? `<div class="txt" id="live">${md(S.stream)}</div>` : ''}<div class="thinking"><span class="dots"><i></i><i></i><i></i></span><span id="phase">${S.stream ? 'Píšu…' : 'Přemýšlím…'}</span></div></div>` : '');
  if (!el.dataset.sl) { el.dataset.sl = '1'; el.addEventListener('scroll', () => { S.stick = el.scrollHeight - el.scrollTop - el.clientHeight < 80; if (S.stick) jumpPill(false); }, { passive: true }); }
  if (S.stick !== false) el.scrollTop = el.scrollHeight;
  el.setAttribute('aria-busy', S.busy ? 'true' : 'false');
  S.lastCard = [...list].reverse().find(m => m.card && m.card.type === 'run')?.card || null;
  renderChips();
}

/** Prázdná plocha = jen pozdrav (jako úvod Gemini). Všechno ostatní je za „+“ a menu – nic nezahlcuje. */
function emptyState() {
  if (S.space.kind === 'temp') return `<div class="hello"><h1>Dočasný chat</h1><p>Nic se neukládá.</p></div>`;
  if (S.space.kind === 'project') {
    const p = call('project', S.space.id) || {};
    return `<div class="hello"><h1>${esc(p.name || 'Projekt')}</h1><p>${p.next ? esc(p.next) : 'Co se má udělat?'}</p></div>`;
  }
  const h = S.home = call('home') || {};
  const btns = (h.buttons || []).slice(0, 6).map(b => `<button class="btnchip" data-act="runBtn" data-id="${esc(b.id)}" data-kind="${esc(b.inputKind)}"><i></i>${esc(b.name)}</button>`);
  if (h.project) btns.unshift(`<button class="btnchip cont" data-act="say" data-v="Pokračuj tam, kde jsme skončili">↺ Pokračovat: ${esc(h.project.name)}</button>`);
  const noModel = !h.engine && !h.own;
  // laikovi napoví, co může napsat (jen na prázdné hlavní obrazovce, ne v projektu); klepnutím se příklad odešle
  const prompts = noModel || h.airgap ? ['Kolik je 15 procent z 2400?', 'Kolik dní do Vánoc?'] : [
    'Spočítej 15 % z 2400', 'Napiš omluvný e-mail do práce', 'Kolik dní do Vánoc?', 'Vysvětli mi, co je inflace', '100 eur na koruny'
  ];
  const examples = !btns.length ? prompts.map(x => `<button class="btnchip ex" data-act="say" data-v="${esc(x)}">${esc(x)}</button>`) : [];
  // běžná cesta laika: pole dole = zeptat se; tady soubor a hledání v dokumentech (odborné je v „+“ a Nastavení)
  const tasks = `<div class="btns tasks"><button class="btnchip" data-act="pick"><i></i>Pracovat se souborem</button><button class="btnchip" data-act="findDocs"><i></i>Najít v dokumentech</button></div>`;
  return `<div class="hello"><button class="star" data-act="star" aria-label="VEGA – jiný pozdrav">${STAR}</button><h1 id="greet">${esc(GREETS[greetI](h.userName))}</h1>
    ${h.airgap ? `<button class="pill pick" data-act="pickModel" aria-label="Režim Bez sítě – změnit"><b>Bez sítě · místní nástroje</b> ▾</button>` : ''}
    ${noModel && !h.airgap ? `<div class="setup"><p>Výpočty a převody můžeš zkusit hned. Pro volný rozhovor si můžeš připojit online model.</p><button class="btn primary" data-act="freeSetup">Zapnout rozhovor zdarma</button></div>` : ''}
    ${tasks}
    ${btns.length ? `<div class="btns">${btns.join('')}</div>` : ''}
    ${examples.length ? `<div class="examples"><small>Zkus třeba:</small><div class="btns">${examples.join('')}</div></div>` : ''}</div>`;
}

/* ------------------------------------------------------------------ výsledky: nejdřív to podstatné, detail na klepnutí */
function headline(v) {
  if (!v) return '';
  if (v.kind === 'TABLE') {
    const n = (v.notes || [])[0]; if (n && n.length < 90) return n;
    // nejdůležitější číslo tabulky: saldo / součet, jinak počet řádků se správným tvarem
    const si = v.headers.findIndex(h => /^(saldo|součet|celkem)/i.test(h));
    if (si >= 0 && v.rows.length) {
      const sum = v.rows.reduce((a, r) => a + (parseFloat(String(r[si]).replace(/[\s\u00a0]/g, '').replace('−', '-').replace(',', '.')) || 0), 0);
      return `${v.headers[si]} ${fmt(sum)}`;
    }
    return plural(v.total, 'řádek', 'řádky', 'řádků');
  }
  if (v.kind === 'DIFF') return `+${v.added} −${v.removed}`;
  if (v.kind === 'NDEF') return plural((v.records || []).length, 'záznam', 'záznamy', 'záznamů') + ' ze štítku';
  if (v.kind === 'BYTES') return v.name;
  return '';
}
function valueHtml(v, rows = 6) {
  if (!v) return '';
  if (v.kind === 'TABLE') {
    const r = (v.rows || []).slice(0, rows);
    return `<div class="tbl"><table><tr>${v.headers.map(h => `<th>${esc(h)}</th>`).join('')}</tr>${r.map(x => `<tr>${x.map(c => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</table></div>` +
      ((v.notes || []).slice(1, 4).map(n => `<div class="note">${esc(n)}</div>`).join(''));
  }
  if (v.kind === 'DIFF') return diffHtml(v.diff);
  if (v.kind === 'NDEF') return (v.records || []).map(r => `<div class="inline" style="margin:6px 0"><span class="pill sig">${esc(r.label)}</span><span class="grow">${esc(r.display)}</span><button class="btn" data-act="passRecord" data-run="${esc(v.run || '')}" data-i="${r.i}">Předat</button></div>`).join('');
  if (v.kind === 'URL') return `<p>${link(v.url)}</p>`;
  if (v.kind === 'BYTES') return '';
  return `<pre>${esc(String(v.text ?? v.preview ?? '').slice(0, 3000))}</pre>`;
}
/** Opora ve zdroji: u každého tvrzení výsledek pro KONKRÉTNÍ citovanou pasáž; klepnutí otevře pasáž v originále. */
const CHK = { PODPORUJE: ['ok', 'podporuje'], ODPORUJE: ['bad', 'odporuje'], NEDOSTACUJE: ['', 'nedostačuje'], NEVYHODNOCENO: ['', 'nevyhodnoceno'], NECITOVANO: ['', 'bez citace'] };
function checksHtml(c) {
  const ch = c.checks || []; if (!ch.length) return '';
  const cited = ch.filter(x => x.label !== 'NECITOVANO'), unc = ch.length - cited.length;
  if (cited.length && cited.every(x => x.label === 'NEVYHODNOCENO')) return `<div class="note">Opora citací: nevyhodnoceno – ${esc(cited[0].reason)}${unc ? ` · ${unc} vět bez citace` : ''}</div>`;
  const ok = cited.filter(x => x.label === 'PODPORUJE').length;
  return `<div class="res"><div class="res-h">Opora ve zdroji · podloženo ${ok} z ${ch.length} tvrzení${unc ? ` (${unc} bez citace)` : ''}</div><div class="res-body">${ch.map((x, i) => `<button class="chk" style="width:100%;text-align:left" data-act="chkOpen" data-i="${i}" data-c='${esc(JSON.stringify({ x, item: (c.items || []).find(s => s.n === x.n) || null }))}'><span class="pill ${CHK[x.label][0]}">[${x.n}] ${CHK[x.label][1]}</span><span><b>${esc(x.claim)}</b><small>${esc(x.kind)}${x.reason ? ' · ' + esc(x.reason) : ''}</small></span></button>`).join('')}</div></div>`;
}
/** ASTRAEA audit: na první pohled „přímo doloženo“ × „rozbor (odvozeno)“ × „neověřeno“ – bez procent, jen počty. */
function auditHtml(a) {
  if (!a) return '';
  if (a.outcome === 'UNVERIFIED') return `<div class="note"><span class="pill">neověřený koncept</span> Ověřovač citací není nainstalovaný – text je návrh modelu, ne doložený závěr (Nastavení → Pro pokročilé → Ověřovač citací).</div>`;
  const parts = [`${a.supported} z ${a.claims} tvrzení přímo doloženo`, plural(a.origins, 'nezávislý původ', 'nezávislé původy', 'nezávislých původů') + ` (${plural(a.sources, 'zdroj', 'zdroje', 'zdrojů')})`];
  if (a.conflicts) parts.push(plural(a.conflicts, 'rozpor mezi zdroji', 'rozpory mezi zdroji', 'rozporů mezi zdroji'));
  if (a.revisions) parts.push('1× opraveno podle kontroly');
  const head = a.render === 'rozbor' ? '<span class="pill">rozbor – odvozené závěry</span>' : a.outcome === 'ABSTAINED' ? '<span class="pill bad">nedoloženo</span>' : `<span class="pill ${a.outcome === 'ANSWERED' ? 'ok' : ''}">${a.outcome === 'ANSWERED' ? 'doloženo' : 'doloženo částečně'}</span>`;
  return `<div class="note">${head} ${esc(parts.join(' · '))}</div>`;
}
const diffHtml = d => `<pre class="diff">${String(d || '').split('\n').slice(0, 400).map(l => `<span class="${l.startsWith('@@') ? 'h' : l[0] === '+' ? 'a' : l[0] === '-' ? 'd' : ''}">${esc(l)}</span>`).join('\n')}</pre>`;

/** Karta uložená starší verzí nebo s chybějícím polem nesmí shodit vykreslení celé konverzace. */
function safeCard(c, isLast) {
  try { return card(c, isLast); } catch (e) { console.error('karta', c && c.type, e); return '<div class="note">(Tuhle část odpovědi nejde zobrazit – text výše platí.)</div>'; }
}
function card(c, isLast) {
  if (!c) return '';
  switch (c.type) {
    case 'wifiMeasure': { const data = Object.assign({ kind: 'WiFi připojení', unit: 'dBm' }, c.data || {});
      return `<div class="cardbox"><button class="btn" data-act="hwMeasureSave" data-d='${esc(JSON.stringify(data))}'>Uložit do podkladů pro výzkum</button></div>`; }
    case 'run': {
      const v = Object.assign({ run: c.run }, c.value);
      const primary = c.failed ? `<button class="btn signal" data-act="resume" data-run="${esc(c.run)}">Pokračovat</button>`
        : c.save && !S.savedRuns.has(c.run) ? `<button class="btn primary" data-act="saveProc" data-run="${c.run}" data-save='${esc(JSON.stringify(c.save))}'>Uložit jako nástroj</button>`
        : v.kind === 'TABLE' || v.kind === 'BYTES' ? `<button class="btn primary" data-act="export" data-run="${esc(c.run)}">Uložit soubor</button>` : '';
      return `<div class="res"><div class="res-h">${esc(c.name)} ${c.failed ? '<span class="pill bad">chyba</span>' : '<span class="pill ok">hotovo</span>'}</div>
        ${c.failed ? `<div class="res-sub" style="padding-top:6px">${esc(c.error)}</div>` : `<div class="res-big">${esc(headline(v))}</div>`}
        <div class="res-body">${valueHtml(v)}</div>
        <div class="res-a">${primary}<span class="grow"></span><button class="more" data-act="runMenu" data-run="${esc(c.run)}" aria-label="Další možnosti">⋯</button></div></div>`;
    }
    case 'diff': {
      const add = c.files.reduce((a, f) => a + (Number(f.added) || 0), 0), rem = c.files.reduce((a, f) => a + (Number(f.removed) || 0), 0), v = c.verification;
      const vp = v ? (v.kind === 'run' ? `<span class="pill ${v.exitCode === 0 ? 'ok' : 'bad'}">${v.exitCode === 0 ? 'testy prošly' : 'testy neprošly'}</span>` : `<span class="pill">${esc(v.label)}</span>`) : '';
      const task = (c.task || []), mine = (c.changes || []).filter(x => task.includes(x.id));
      // výběr změn: jen u poslední karty a když úloha udělala víc změn
      const pick = isLast && mine.length > 1 ? `<div class="res-body">${mine.slice().reverse().map(x => `<label class="chk"><input type="checkbox" data-keep="${esc(x.id)}" checked><span><b>#${esc(x.id)} ${esc(x.path)}</b><small>${esc(x.note || x.op)}</small></span></label>`).join('')}</div>` : '';
      const rounds = (c.rounds || []).length > 1 ? ` · ${c.rounds.length} kola (${c.rounds.map(r => r.exitCode === 0 ? '✓' : '✗').join(' ')})` : '';
      return `<div class="res"><div class="res-h">${esc(c.title || 'Změny v projektu')} ${vp}</div>
        <div class="res-big">+${add} −${rem}</div><div class="res-sub">${plural(c.files.length, 'soubor', 'soubory', 'souborů')}: ${c.files.slice(0, 3).map(f => esc(f.path)).join(', ')}${c.revision ? ` · revize ${esc(c.revision)}` : ''}${rounds}</div>${pick}
        <div class="res-a">${c.resumeTask ? `<button class="btn primary" data-act="resumeTask" data-p="${esc(c.resumeTask.project)}">Dokončit úlohu</button>` : pick ? `<button class="btn primary" data-act="keep" data-p="${esc(c.project)}" data-task="${esc(task.join(','))}">Přijmout vybrané</button>` : !c.title && c.changes && c.changes[0] ? `<button class="btn" data-act="undo" data-p="${esc(c.project)}" data-c="${esc(c.changes[0].id)}">Vrátit</button>` : ''}<span class="grow"></span>
        <button class="btn" data-act="cardDiff" data-files='${esc(JSON.stringify(c.files))}'>Rozdíl</button>${v ? `<button class="more" data-act="showVer" data-ver='${esc(JSON.stringify(v))}' aria-label="Výstup testu">⋯</button>` : ''}</div></div>`;
    }
    case 'compare': {
      const ch = c.prevTotal != null && c.prevTotal !== c.total;
      return `<div class="res"><div class="res-h">Porovnání výpisů <span class="pill">revize ${esc(c.rev)}</span></div>
        <div class="res-big">${fmt(c.total)} Kč</div>${ch ? `<div class="res-sub">předtím ${fmt(c.prevTotal)} Kč → rozdíl ${c.total - c.prevTotal > 0 ? '+' : ''}${fmt(c.total - c.prevTotal)} Kč</div>` : ''}
        <div class="res-body"><div class="tbl"><table><tr><th>#</th><th>Soubor</th><th>Saldo</th><th>Stav</th></tr>${c.files.map(f => `<tr><td>${f.i}</td><td>${esc(f.name)}${f.changed ? `<br><small class="note">místo ${esc(f.prevName)} (${fmt(f.prevSaldo)})</small>` : ''}</td><td>${fmt(f.saldo)}</td><td><span class="pill ${f.status === 'spočítáno' ? 'sig' : ''}">${esc(f.status)}</span></td></tr>`).join('')}</table></div></div>
        <div class="res-a"><span class="grow"></span><button class="btn" data-act="taskLog" data-log='${esc(JSON.stringify(c.log))}'>Záznam běhu</button></div></div>`;
    }
    case 'file': return `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="openOut" data-f="${esc(c.name)}">Otevřít ${esc(c.name)}</button><button class="btn" data-act="share" data-f="${esc(c.name)}">Sdílet</button></div>`;
    case 'netOff': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="netOn" data-what="${c.what === 'web' ? 'web' : 'airgap'}">${c.what === 'web' ? 'Zapnout veřejný web' : 'Vypnout Bez sítě'}</button></div>` : '';
    case 'memClear': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn danger" data-act="memClearAll">Smazat celou paměť (${Number(c.n) || 0})</button><button class="btn ghost" data-act="libTab" data-tab="memory">Ukázat paměť</button></div>` : '';
    case 'setup': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="freeSetup">Zapnout rozhovor zdarma</button></div>` : '';
    case 'exportRetry': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="exportRetry" data-run="${esc(c.run)}">Zkusit znovu</button></div>` : '';
    case 'econ': return `<div class="res"><div class="res-h">Kalkulace</div><div class="res-big">${c.profit == null ? '–' : (c.profit < 0 ? 'ztráta ' + fmt(-c.profit) : 'zisk ' + fmt(c.profit)) + ' Kč'}</div><div class="res-sub">cena ${fmt(c.price)} · variabilní ${fmt(c.variable)} · fixní ${fmt(c.fixed)}${c.breakEven != null ? ' · bod zvratu ' + esc(c.breakEven) + ' ks' : ''}</div></div>`;
    case 'evidence': return `<div class="res"><div class="res-h">Síla doložení</div><div class="res-body">${c.parts.map(p => `<div class="chk"><span class="pill ${/^potvrzeno|^dvě/.test(p.status) ? 'ok' : 'bad'}">${esc(p.lines)} ${p.lines >= 5 || p.lines === 0 ? 'linií' : 'linie'}</span><span><b>${esc(p.label)}</b><small>${esc(p.status)} · ${p.support.map(esc).join(', ')}</small></span></div>`).join('')}</div></div>`;
    case 'revision': return `<span class="pill sig">revize ${esc(c.rev)}</span>`;
    case 'resume': return isLast ? `<div class="res"><div class="res-h">Rozpracováno <span class="pill">uloženo</span></div><div class="res-sub" style="padding-top:6px">„${esc(c.text)}“</div><div class="res-a"><button class="btn primary" data-act="retry" data-req="${esc(c.req || '')}">Pokračovat</button></div></div>` : '';
    case 'project': return c.resumeTask ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="resumeTask" data-p="${esc(c.resumeTask.project)}">Dokončit přerušenou úlohu</button></div>` : '';
    case 'memScope': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn" data-act="memGlobal" data-t="${esc(c.text)}">Uložit pro všechno</button><button class="btn ghost" data-act="libTab" data-tab="projects">Otevřít projekt</button></div>` : '';
    case 'procedure': return `<div class="res-a" style="padding-left:0"><button class="btnchip" data-act="runBtn" data-id="${esc(c.id)}" data-kind="${esc(c.inputKind || 'BYTES')}"><i></i>${esc(c.name)} · v${esc(c.version)}</button></div>`;
    case 'buttonDraft': {
      const checks = (c.checks || []).map(k => `<li><span class="pill ${k.ok ? 'ok' : 'bad'}">${k.ok ? 'prošlo' : 'neprošlo'}</span> ${esc(k.file)}<small>${esc(k.note)}</small></li>`).join('');
      return `<div class="res"><div class="res-h">Návrh tlačítka ${c.offline ? '<span class="pill ok">bez sítě</span>' : '<span class="pill">potřebuje síť</span>'}</div>
        <div class="res-body"><input class="field" id="btnName" value="${esc(c.name)}" aria-label="Název tlačítka">
        <h4 class="note" style="margin:12px 0 2px">Pevné kroky</h4><ul class="steps">${c.fixed.map(f => `<li>${esc(f.title)}<small>${esc(f.detail)}</small></li>`).join('')}</ul>
        <h4 class="note" style="margin:8px 0 2px">Proměnné při spuštění</h4><ul class="steps">${c.variable.map(v => `<li>${esc(v.detail)}</li>`).join('')}</ul>
        <h4 class="note" style="margin:8px 0 2px">Oprávnění</h4><p class="note">${c.perms.map(esc).join(', ')}</p>
        <h4 class="note" style="margin:8px 0 2px">Ověřeno na</h4><ul class="steps">${c.verifiedOn.map(f => `<li>${esc(f)}</li>`).join('')}${checks}</ul></div>
        ${isLast ? `<div class="res-a"><button class="btn ${c.canSave ? '' : 'primary'}" data-act="btnTry">Vyzkoušet na jiném souboru</button><span class="grow"></span><button class="btn ${c.canSave ? 'primary' : ''}" data-act="btnSave" ${c.canSave ? '' : 'disabled style="opacity:.45"'}>Uložit a připnout</button></div>` : ''}</div>` + (c.value ? `<div class="res"><div class="res-body" style="padding-bottom:12px">${valueHtml(c.value, 4)}</div></div>` : '');
    }
    case 'verification': { const v = c.verification; return `<div class="res"><div class="res-h">Ověření</div><div class="res-big">${v.kind !== 'run' && v.kind !== 'static' ? 'Neověřeno' : v.exitCode === 0 ? 'Prošlo' : 'Neprošlo'}</div><div class="res-sub">${esc(v.label)} · rev ${esc(v.revision)}</div><div class="res-a"><span class="grow"></span><button class="btn" data-act="showVer" data-ver='${esc(JSON.stringify(v))}'>Výstup</button></div></div>`; }
    case 'sources': return auditHtml(c.audit) + (c.audit && c.audit.outcome === 'UNVERIFIED' ? '' : checksHtml(c)) + `<div class="inline" style="margin:4px 0;flex-wrap:wrap">${(c.candidates || []).length > 1 ? `<span class="note">Může znamenat: ${c.candidates.map(esc).join(' · ')}</span>` : ''}<button class="pill" data-act="sources" data-src='${esc(JSON.stringify(c.items))}'>${c.items.length} zdrojů ›</button></div>`;
    case 'coverage': { const ok = c.items.filter(i => i.ok).length; return `<button class="pill ${ok === c.items.length ? 'ok' : 'bad'}" data-act="coverage" data-cov='${esc(JSON.stringify(c))}'>Pokryto ${ok}/${c.items.length} požadavků ›</button>`; }
    case 'demandForm': return `<div class="res"><div class="res-h">Předžalobní výzva</div><div class="res-sub" style="padding-top:6px">Faktura a úhrady – vyplň svoje údaje (šedé texty jsou jen ukázka).</div><div class="res-a"><button class="btn primary" data-act="demandForm" data-s='${esc(JSON.stringify(c.sample))}'>Vyplnit a vyrobit</button></div></div>`;
    case 'demand': return `<div class="res"><div class="res-h">Předžalobní výzva <span class="pill ${c.verified ? 'ok' : 'bad'}">${c.verified ? 'sazba ověřena' : 'sazba neověřena'}</span></div>
      <div class="res-big">${fmt(c.total)} Kč</div><div class="res-sub">PDF zpětně zkontrolováno: ${c.checks.filter(k => k.ok).length}/${c.checks.length}</div>
      ${c.warnings.map(w => `<div class="res-body warn">⚠ ${esc(w)}</div>`).join('')}
      <div class="res-a"><button class="btn primary" data-act="openOut" data-f="${esc(c.files[1] || c.files[0])}">Otevřít PDF</button><span class="grow"></span><button class="more" data-act="demandMenu" data-d='${esc(JSON.stringify(c))}' aria-label="Další">⋯</button></div></div>`;
    case 'nfcWait': return isLast && S.nfc ? `<div class="thinking"><span class="dots"><i></i><i></i><i></i></span><span>Přilož štítek k zadní straně telefonu…</span></div>` : '';
    case 'memRevert': return `<button class="pill sig" data-act="memRevert" data-id="${esc(c.id)}">Vrátit předchozí verzi</button>`;
    case 'memory': return `<button class="pill" data-act="library" data-tab="memory">Otevřít paměť ›</button>`;
    case 'selfupdate': return `<button class="pill" data-act="library" data-tab="improve">Úpravy aplikace – stav a instalace ›</button>`;
    default: return '';
  }
}

/* ------------------------------------------------------------------ návrhy pod vstupem (max 4, jen co dává smysl teď) */
/** Návrhy jen když něco konkrétního čeká (přiložený soubor, uložitelný výsledek) – nejvýš 3. Prázdná plocha je čistá. */
function chips() {
  if (S.tool || S.busy) return [];
  const f = S.files[S.files.length - 1];
  if (f) {
    const ext = (f.name.split('.').pop() || '').toLowerCase();
    const proc = procs().find(p => p.inputKind === 'BYTES');
    if (['csv', 'tsv'].includes(ext)) return [...(proc ? [{ t: proc.name, a: 'proc:' + proc.id, sig: true }] : [{ t: 'Zpracovat výpis', a: 'say:Udělej z toho nástroj, který každý týden použiju na výpis.', sig: true }]), { t: 'Najít chyby', a: 'tool:csv.inspect' }];
    if (['log', 'txt'].includes(ext)) return [{ t: 'Rozbor pádu', a: 'tool:log.crash', sig: true }, { t: 'Shrnout', a: 'say:Shrň mi, co je v souboru.' }];
    if (ext === 'json') return [{ t: 'Prozkoumat', a: 'tool:json.inspect', sig: true }];
    if (['pdf', 'jpg', 'jpeg', 'png', 'webp', 'docx', 'xlsx'].includes(ext)) return [{ t: 'Shrnout', a: 'say:Shrň mi tento dokument.', sig: true }, { t: 'Přečíst text', a: 'tool:file.text' }];
    return [];
  }
  if (S.space.kind === 'project' && !msgsCount) return [{ t: 'Testy, změny a návrat', a: 'panel' }];
  if (S.lastCard && S.lastCard.save && !S.savedRuns.has(S.lastCard.run)) return [{ t: 'Uložit jako nástroj', a: 'saveLast', sig: true }];
  return [];
}
function renderChips() {
  $('#chips').innerHTML = chips().map(c => `<button class="chip ${c.sig ? 'sig' : ''}" data-act="chip" data-v="${esc(c.a)}">${esc(c.t)}</button>`).join('');
  $('#files').innerHTML = S.files.map(f => `<span class="file">📎 ${esc(f.name)}<button data-act="dropFile" data-id="${esc(f.id)}" aria-label="Odebrat">×</button></span>`).join('');
  $('#mode').innerHTML = S.tool ? `<span class="mode">${esc(S.tool.title)}${S.toolInput ? ' · na předchozí výsledek' : ''}<button data-act="exitTool" aria-label="Zrušit nástroj">×</button></span>` : '';
  const go = $('#go'); const empty = !S.busy && !$('#input').value.trim() && !S.files.length && !S.tool;
  // prázdné pole = hlas (jako u moderních asistentů), s textem = odeslat, při práci = zastavit
  go.className = 'round ' + (S.busy ? 'stop' : empty ? 'go voice' : 'go');
  if (S.busy) go.textContent = '■'; else if (empty) go.innerHTML = VOICE; else go.textContent = '↑';
  go.dataset.act = S.busy ? 'stop' : empty ? 'voice' : 'send';
  go.setAttribute('aria-label', S.busy ? 'Zastavit' : empty ? 'Mluvit' : 'Odeslat');
  $('#input').placeholder = S.tool ? ((S.tool.input || []).includes('URL') ? 'Vlož adresu…' : 'Vlož text nebo přilož soubor…') : S.space.kind === 'project' ? 'Co se má v projektu udělat?' : 'Zeptej se VEGA';
}
/** Sekce Hardware: virtuální karta VEGA, NFC štítky, WiFi, QR, Bluetooth. Bez rootu, offline, jen vlastní vybavení. */
function hardwareSheet() {
  const caps = call('hwCapabilities') || {}; const hw = call('hwJson') || { cards: [] };
  const cards = hw.cards || [];
  const cardRow = c => `<div class="row stack"><span class="grow"><b>${esc(c.name)}${hw.activeId === c.id ? ' <span class="pill ok">aktivní</span>' : ''}</b><small>${esc(c.publicId || 'bez identifikátoru')} · sdílí ${((c.shared || []).length + 2)} údajů</small></span>
    <span class="acts">${hw.activeId === c.id ? '<button class="btn" data-act="hwDeact">Deaktivovat</button>' : `<button class="btn primary" data-act="hwAct" data-id="${esc(c.id)}">Aktivovat</button>`}<button class="btn ghost" data-act="hwEdit" data-id="${esc(c.id)}">Upravit</button></span></div>`;
  sheet(`<h2>Hardware</h2>
    <p class="note">Bez rootu, offline. Vše jen pro tvoje vlastní vybavení – ne cizí, platební ani přístupové karty.</p>

    <h3>Virtuální karta VEGA</h3>
    <div class="note">${caps.hce ? 'Telefon se stane kartou (HCE) a druhý telefon s VEGA ji přečte.' : '⚠ Tenhle telefon neumí emulaci karty (HCE) – kartu nevytvoříš, ale cizí přečteš.'}</div>
    <div class="list">${cards.map(cardRow).join('') || '<div class="row"><span class="grow note">Zatím žádná karta.</span></div>'}</div>
    <div class="inline">${caps.hce ? '<button class="btn" data-act="hwNew">+ Nová karta</button>' : ''}<button class="btn" data-act="hwReadCard">Přečíst kartu z druhého telefonu</button></div>

    <h3>NFC štítky</h3>
    <div class="inline"><button class="btn" data-act="nfc">Přečíst štítek</button><button class="btn" data-act="ndefWrite">Zapsat text / URL</button></div>
    <div class="note">${caps.nfc ? (caps.nfcOn ? '' : 'NFC je vypnuté – zapni ho v systému.') : '⚠ Telefon nemá NFC.'}</div>

    <h3>WiFi</h3>
    <div class="inline"><button class="btn" data-act="wifiLink">Moje připojení</button><button class="btn" data-act="wifi">Sítě v okolí</button><button class="btn" data-act="wifiTools">Heslo a QR</button></div>

    <h3>Bluetooth</h3>
    <div class="inline"><button class="btn" data-act="ble" ${caps.ble ? '' : 'disabled'}>Hledat zařízení</button></div>
    ${caps.ble ? '' : '<div class="note">⚠ Telefon nemá Bluetooth LE.</div>'}

    <h3>Přístup root (nepovinné)</h3>
    <div class="note">Běžný chod VEGA root nepotřebuje. Ověřuj jen na rootnutém telefonu (Magisk). Trvalé oprávnění můžeš kdykoli odebrat i v Magisku.</div>
    <div class="inline"><button class="btn" data-act="rootVerify">Ověřit root</button>${(call('rootState') || {}).allowed ? '<button class="btn ghost" data-act="rootDisable">Vypnout používání rootu</button>' : ''}</div>
    <div class="status" id="rootStatus"></div>

    <details><summary>Co ještě jde a co ne (přehled kompatibility)</summary>
      <table class="hwtab"><tr><th>Funkce</th><th>Stav</th></tr>
      <tr><td>Virtuální karta VEGA (HCE) + čtečka</td><td>${caps.hce ? 'hotovo' : 'telefon neumí HCE'}</td></tr>
      <tr><td>NFC štítky: čtení + zápis text/URL</td><td>${caps.nfc ? 'hotovo' : 'telefon nemá NFC'}</td></tr>
      <tr><td>WiFi diagnostika, QR, kontrola hesla</td><td>hotovo</td></tr>
      <tr><td>Bluetooth LE: hledání + RSSI</td><td>${caps.ble ? 'hotovo' : 'telefon nemá BLE'}</td></tr>
      <tr><td>BLE připojení + GATT služby</td><td>chystá se</td></tr>
      <tr><td>USB / sériový terminál</td><td>jen s doloženým adaptérem (zatím ne)</td></tr>
      <tr><td>Infračervené ovládání</td><td>Pixel 9a nemá IR vysílač</td></tr>
      <tr><td>Sub-GHz, 125 kHz RFID, iButton, GPIO</td><td>potřebuje externí hardware (zatím ne)</td></tr></table>
      <p class="note">Samotná detekce USB/BLE zařízení nezaručuje podporu konkrétní operace – proto je zde přehled.</p>
    </details>`, true);
}

function hwCardSheet(card) {
  const c = card || { name: '', publicId: '', fields: {}, shared: [] };
  const fk = Object.keys(c.fields || {});
  const fieldRow = (i, k, v, on) => `<div class="hwfield"><input class="field" data-fk="${i}" placeholder="údaj (např. umístění)" value="${esc(k)}"><input class="field" data-fv="${i}" placeholder="hodnota" value="${esc(v)}"><label class="chk"><input type="checkbox" data-fs="${i}" ${on ? 'checked' : ''}> sdílet</label></div>`;
  const rows = [0, 1, 2].map(i => fieldRow(i, fk[i] || '', fk[i] ? c.fields[fk[i]] : '', fk[i] && (c.shared || []).includes(fk[i])));
  sheet(`<h2>${card ? 'Upravit kartu' : 'Nová virtuální karta'}</h2>
    <p class="lede">Karta vlastního vybavení. Sdílí se jen název, veřejný identifikátor a pole, která zaškrtneš jako „sdílet“.</p>
    <input type="hidden" id="hwId" value="${esc(c.id || '')}">
    <label class="lbl">Název (sdílí se vždy)</label><input class="field" id="hwName" value="${esc(c.name)}" placeholder="např. Vrtačka Bosch">
    <label class="lbl">Veřejný identifikátor (sdílí se vždy)</label><input class="field" id="hwPub" value="${esc(c.publicId)}" placeholder="např. INV-00123">
    <label class="lbl">Doplňující údaje</label>${rows.join('')}
    <div class="inline" style="margin-top:10px"><button class="btn primary" data-act="hwSave">Uložit</button>${card ? `<button class="btn ghost" data-act="hwDel" data-id="${esc(c.id)}">Smazat</button>` : ''}</div>`, true);
}

function docsManageSheet() {
  const docs = call('indexDocs') || [];
  const rows = docs.length ? docs.map(d => `<div class="row"><span class="grow">${esc(d.name)}</span><button class="btn ghost" data-act="docRemove" data-id="${esc(d.id)}">Odebrat</button></div>`).join('')
    : '<div class="row"><span class="grow note">Zatím žádné dokumenty.</span></div>';
  sheet(`<h2>Dokumenty ve vyhledávání</h2><p class="lede">Odebráním dokument zmizí z „najdi v mých souborech“. Soubor v telefonu to nemaže.</p><div class="list">${rows}</div>`, true);
}
function wifiToolsSheet() {
  sheet(`<h2>WiFi heslo a QR</h2>
    <p class="lede">Zkontroluj sílu hesla nebo vyrob QR kód pro snadné připojení hostů. Heslo zůstane jen tady – neukládá se do historie ani jinam.</p>
    <label class="lbl">Název sítě (SSID)</label><input class="field" id="qrSsid" placeholder="např. Doma_5G" autocomplete="off">
    <label class="lbl" for="qrPw">Heslo</label><div class="inline"><input class="field" id="qrPw" type="password" placeholder="heslo k WiFi" autocomplete="off" spellcheck="false"><button class="btn" data-act="qrReveal" aria-pressed="false">Ukázat heslo</button><button class="btn" data-act="wifiGen">Vymyslet silné</button></div>
    <div class="status" id="pwCheck"></div>
    <label class="chk" style="margin-top:6px"><input type="checkbox" id="qrHidden"> skrytá síť</label>
    <div class="inline" style="margin-top:10px"><button class="btn" data-act="wifiPwCheck">Zkontrolovat heslo</button><button class="btn primary" data-act="wifiQrGo">Vyrobit QR</button></div>
    <div id="qrOut" style="text-align:center;margin-top:12px"></div>`, true);
}

function ndefWriteSheet() {
  sheet(`<h2>Zapsat na NFC štítek</h2>
    <p class="lede">Zapíše text nebo odkaz na TVŮJ vlastní zapisovatelný štítek. Před zápisem ověřím kapacitu, po zápisu přečtu zpět.</p>
    <label class="lbl">Typ</label><select class="field" id="ndefKind"><option value="text">Text</option><option value="url">Odkaz (URL)</option></select>
    <label class="lbl">Obsah</label><input class="field" id="ndefContent" placeholder="text nebo https://…" autocomplete="off">
    <button class="btn primary wide" data-act="ndefWriteGo" style="margin-top:10px">Přiložit štítek a zapsat</button>`, true);
}
function wifiPwCheckNow() {
  const pw = ($('#qrPw') || {}).value || ''; const ssid = ($('#qrSsid') || {}).value || '';
  const el = $('#pwCheck'); if (!el) return;
  if (!pw) { el.textContent = ''; return; }
  const r = call('wifiCheckPw', pw, ssid) || {};
  const cls = r.score >= 3 ? 'ok' : r.score >= 2 ? '' : 'bad';
  el.innerHTML = `<span class="pill ${cls}">${esc(r.level || '')}</span> ${esc(r.reason || '')}`;
}

/** Domů = úvodní obrazovka s pozdravem. Když je nejnovější chat prázdný, použije ho (nehromadí prázdné chaty), jinak založí nový. */
function goHome() {
  closeSheet(true); S.tool = null; S.toolInput = null;
  if (S.space.kind === 'chat' && S.space.id && !(call('messages', S.space.id) || []).length) { render(); const i = $('#input'); if (i) i.focus(); return; }
  const cs = call('chats') || [];
  const empty = cs.find(c => !(call('messages', c.id) || []).length);
  setSpace({ kind: 'chat', id: empty ? empty.id : call('newChat'), name: 'Konverzace' });
  const i = $('#input'); if (i) i.focus();
}

function renderTop() {
  const h = S.home || call('home') || {};
  $('#spaceName').textContent = S.space.kind === 'temp' ? 'Dočasný chat' : S.space.kind === 'project' ? S.space.name : 'VEGA';
  $('#modelName').textContent = h.airgap ? '· Bez sítě' : h.model ? '· ' + h.model : '';
  $('#avatar').textContent = (h.userName || 'V').trim().charAt(0).toUpperCase() || 'V';
}
// Živá odpověď: hotové odstavce se vykreslí JEDNOU a zůstanou v DOM, znovu se překresluje jen rozepsaný konec;
// nejvýš jednou za snímek (requestAnimationFrame) – plynulé i u dlouhých odpovědí.
function liveSoon() { if (S.liveRaf) return; S.liveRaf = requestAnimationFrame(() => { S.liveRaf = 0; liveRender(); }); }
function liveCut(t) { // poslední hranice odstavce mimo blok kódu
  let i = t.lastIndexOf('\n\n');
  while (i > 0 && ((t.slice(0, i).match(/```/g) || []).length % 2)) i = t.lastIndexOf('\n\n', i - 1);
  return i > 0 ? i + 2 : 0;
}
function liveRender() {
  const l = $('#live'); if (!l) return; const t = S.stream;
  if (!S.live || S.live.el !== l || !t.startsWith(S.live.text.slice(0, S.live.done))) {
    l.innerHTML = '<div class="live-done"></div><div class="live-tail"></div>'; S.live = { el: l, text: '', done: 0 };
  }
  const cut = liveCut(t), done = l.firstChild, tail = l.lastChild;
  if (cut > S.live.done) { done.insertAdjacentHTML('beforeend', md(t.slice(S.live.done, cut))); S.live.done = cut; }
  tail.innerHTML = md(t.slice(S.live.done)); S.live.text = t;
  const ph = $('#phase'); if (ph) ph.textContent = 'Píšu…';
  if (S.stick) $('#stream').scrollTop = 1e9; else jumpPill(true);
}
function render() { S.home = null; renderStream(); renderTop(); waitHint(); }
/** Dlouhé čekání bez prvního slova: po 20 s říct, co se děje a že jde zastavit (laik jinak neví, jestli to nezamrzlo). */
function waitHint() {
  if (!S.busy) { clearTimeout(S.waitT); S.waitT = 0; S.waitSince = 0; return; }
  if (!S.waitSince) S.waitSince = Date.now();
  if (S.waitT) return;
  S.waitT = setTimeout(() => { S.waitT = 0; const ph = $('#phase');
    if (S.busy && !S.stream && ph && /Přemýšlím/.test(ph.textContent)) ph.textContent = 'Pořád čekám na odpověď modelu – u delších úloh to může trvat i minutu. Zastavit můžeš tlačítkem ■.'; }, 20000);
}

/* ------------------------------------------------------------------ listy */
function sheet(html, full) {
  // otevřený dotaz (souhlas / schválení ceny) nesmí tiše zmizet pod jiným listem – úloha by čekala 5 minut
  if (S.req && !html.includes('data-act="answer"')) { toast('Nejdřív odpověz na otevřený dotaz.'); return; }
  const prev = document.activeElement && !document.activeElement.closest('#layer') ? document.activeElement : S.prevFocus;
  closeSheet(true);
  $('#layer').innerHTML = `<div class="scrim" data-act="close"></div><div class="sheet ${full ? 'full' : ''}" role="dialog" aria-modal="true">${full ? `<div class="inline"><span class="grow"></span><button class="iconbtn" data-act="close" aria-label="Zavřít">✕</button></div>` : '<div class="grab"></div>'}${html}</div>`;
  // přístupnost: dialog pojmenovaný nadpisem, fokus do dialogu, po zavření zpět
  S.prevFocus = prev;
  const h = $('#layer .sheet h2'); if (h) { h.id = 'sheetTitle'; h.tabIndex = -1; $('#layer .sheet').setAttribute('aria-labelledby', 'sheetTitle'); h.focus({ preventScroll: true }); }
  // popisek .lbl nad polem → název pole pro čtečku obrazovky (jinak TalkBack řekne jen „editační pole“)
  document.querySelectorAll('#layer label.lbl').forEach(l => { const f = l.nextElementSibling; if (f && /^(INPUT|SELECT|TEXTAREA)$/.test(f.tagName) && !f.getAttribute('aria-label')) f.setAttribute('aria-label', l.textContent.trim()); });
}
/** Zavření listu s otevřeným dotazem (souhlas / schválení) = odpověď „ne“ – úloha nečeká 5 minut. */
function closeSheet(silent) {
  if (S.req && !silent) { B.answer(S.req, JSON.stringify({ ok: false })); S.req = null; }
  const had = !!$('#layer .sheet');
  $('#layer').innerHTML = '';
  if (had && S.prevFocus && document.contains(S.prevFocus)) { try { S.prevFocus.focus({ preventScroll: true }); } catch (e) {} S.prevFocus = null; }
}

function plusSheet() {
  sheet(`<h2>Přidat</h2><div class="list">
    <button class="row" data-act="pick"><b>📎</b><span class="grow"><b>Soubor</b><small>CSV, log, JSON, PDF, fotka dokumentu…</small></span></button>
    <button class="row" data-act="findDocs"><b>🔎</b><span class="grow"><b>Najít v dokumentech</b><small>hledání v souborech, které už znám (i bez sítě)</small></span></button>
    <button class="row" data-act="tools"><b>⌘</b><span class="grow"><b>Nástroje</b><small>diff, kontrolní součty, inspektory, čtení URL…</small></span></button>
  </div>
  <details style="margin-top:10px"><summary>Pokročilé</summary><div class="list" style="margin-top:8px">
    <button class="row" data-act="nfc"><b>◎</b><span class="grow"><b>NFC štítek – inspektor</b><small>typ karty, UID, technologie, obsah (jen čtení)</small></span></button>
    <button class="row" data-act="wifi"><b>📶</b><span class="grow"><b>WiFi kolem mě</b><small>kanály, signál, kontrola zabezpečení tvé sítě</small></span></button>
    <button class="row" data-act="hw"><b>🛠</b><span class="grow"><b>Hardware</b><small>virtuální karta, NFC štítky, WiFi, QR, Bluetooth</small></span></button>
    <button class="row" data-act="folder"><b>▣</b><span class="grow"><b>Projekt ze složky</b><small>pracuji v kopii, originál nechám být</small></span></button>
  </div></details>`);
}

function toolsSheet() {
  S.tools = S.tools || call('tools') || [];
  const groups = [...new Set(S.tools.map(t => t.group))];
  sheet(`<h2>Nástroje</h2><p class="lede">Stejné nástroje volá i rozhovor. Offline fungují bez sítě i bez kvóty.</p>
    <input class="field search" id="toolq" placeholder="Hledat nástroj" autocomplete="off">
    <div id="toollist">${toolList(S.tools, groups)}</div>
    <h3>Dokumenty</h3><div class="list"><button class="row" data-act="demand"><span class="grow"><b>Předžalobní výzva</b><small>faktura a úhrady → DOCX a PDF se zkontrolovaným výpočtem</small></span></button></div>`, true);
}
const toolList = (ts, groups) => groups.map(g => { const xs = ts.filter(t => t.group === g); return xs.length ? `<h3>${esc(g)}</h3><div class="list">${xs.map(t => `<button class="row" data-act="useTool" data-id="${esc(t.id)}"><span class="grow"><b>${esc(t.title)}</b><small>${esc(t.description)}</small></span><span class="end">${t.offline ? '' : 'síť'}</span></button>`).join('')}</div>` : ''; }).join('');

function useTool(id, input) {
  S.tools = S.tools || call('tools') || [];
  const t = S.tools.find(x => x.id === id); if (!t) return;
  S.tool = t; S.toolInput = input || null;
  closeSheet(true); renderChips();
  // nástroj s parametry → krátký formulář; bez parametrů stačí vstup v liště (nebo příloha / předchozí výsledek)
  if ((t.params || []).length) toolParams();
  else if (S.toolInput || S.files.length) runTool();
  else { $('#input').focus(); toast(`${t.title}: vlož vstup do pole a odešli.`); }
}
function toolParams() {
  const t = S.tool;
  sheet(`<h2>${esc(t.title)}</h2><p class="lede">${esc(t.description)}</p>
    ${t.params.map(p => `<label class="lbl">${esc(p.label)}</label><input class="field" data-param="${esc(p.key)}" value="${esc(p.default)}" placeholder="${esc(p.hint)}">`).join('')}
    ${S.toolInput || S.files.length ? `<p class="note">Vstup: ${S.toolInput ? 'předchozí výsledek' : esc(S.files[S.files.length - 1].name)}</p>` : `<label class="lbl">Vstup</label><textarea class="field" data-tool-text rows="5" placeholder="${(t.input || []).includes('URL') ? 'https://…' : 'Vlož text, CSV, JSON nebo log…'}">${esc($('#input').value)}</textarea>`}
    <button class="btn primary wide" data-act="runTool" style="margin-top:10px">Spustit</button>`);
}
function runTool() {
  const t = S.tool; if (!t || busyGuard()) return;
  const params = {}; document.querySelectorAll('[data-param]').forEach(i => params[i.dataset.param] = i.value);
  const tt = $('[data-tool-text]');
  const text = (tt ? tt.value : $('#input').value).trim();
  const input = S.toolInput || (text ? { text } : S.files.length ? { file: S.files[S.files.length - 1].id } : null);
  if (!input) { toast('Chybí vstup – vlož text nebo přilož soubor.'); (tt || $('#input')).focus(); return; }
  if (busyCall('runTool', chatId(), t.id, input, params) === null) return;
  closeSheet(true);
  S.stream = '';
  if (input.text) { $('#input').value = ''; autosize(); }
  if (input.file) S.files = S.files.filter(f => f.id !== input.file);
  S.tool = null; S.toolInput = null; render();
}

/** Menu jako u Gemini: vysouvací panel zleva – nová konverzace, nedávné, dole knihovna a nastavení. */
function drawer() {
  const cs = (call('chats') || []).slice(0, 30);
  closeSheet(true);
  $('#layer').innerHTML = `<div class="scrim" data-act="close"></div><nav class="drawer" aria-label="Menu">
    <button class="drow strong" data-act="new">＋ Nová konverzace</button>
    <button class="drow" data-act="temp">Dočasný chat</button>
    <div class="dlabel">Nedávné</div>
    <div class="dscroll">${cs.map(c => `<div class="drowwrap"><button class="drow ${S.space.kind === 'chat' && S.space.id === c.id ? 'on' : ''}" data-act="openChat" data-id="${esc(c.id)}">${esc(c.title)}</button><button class="dmore" data-act="chatMenu" data-id="${esc(c.id)}" data-t="${esc(c.title)}" aria-label="Možnosti konverzace ${esc(c.title)}">⋯</button></div>`).join('') || '<div class="dnote">Zatím žádné</div>'}</div>
    <div class="dfoot">
      <button class="drow" data-act="libTab" data-tab="projects">Projekty</button>
      <button class="drow" data-act="libTab" data-tab="procs">Moje nástroje</button>
      <button class="drow" data-act="libTab" data-tab="memory">Paměť</button>
      <button class="drow" data-act="settings">Nastavení</button>
    </div></nav>`;
}

/** Úpravy aplikace (vrstva C): VEGA upravuje vlastní kód → PR → CI bez klíčů → podepsané APK → instalace s potvrzením. */
function selfUpdateHtml() {
  const u = call('selfUpdates') || { items: [] };
  const setup = `<details ${u.hasToken ? '' : 'open'}><summary class="note">Nastavení GitHubu${u.hasToken ? ' ✓' : ''}</summary>
      <p class="note">Fine-grained token jen pro repozitář ${esc(u.repo)}: <b>Contents</b> zápis, <b>Actions</b> čtení. <b>Workflows NEPOVOLUJ</b> – pak úprava nikdy nezmění CI. Na GitHubu nastav pravidlo (ruleset), aby token směl zakládat jen větve <code>vega/auto-*</code>, a podpisový klíč dej do prostředí <code>vega-sign</code> (návod v docs/vega/AUTONOMIE.md). Token zůstane v trezoru telefonu.</p>
      <input class="field" id="ghTok" type="password" autocomplete="off" placeholder="${u.hasToken ? 'token uložen – nový přepíše' : 'github_pat_…'}">
      <div class="inline" style="margin-top:6px"><input class="field" id="ghRepo" value="${esc(u.repo)}" aria-label="Repozitář"><input class="field" id="ghBase" value="${esc(u.base)}" aria-label="Pracovní větev"></div>
      <button class="btn" data-act="suSave" style="margin-top:6px">Uložit</button></details>`;
  const items = u.items.map(x => `<div class="row stack"><span class="grow"><b>${esc(x.task.slice(0, 120))} <span class="pill ${x.status === 'READY' || x.status === 'INSTALLED' ? 'ok' : x.status === 'FAILED' ? 'bad' : ''}">${esc(x.label)}</span></b>
      <small>${esc(x.origin)} · ${x.files.length} souborů${x.note ? ' · ' + esc(x.note) : ''}${x.runUrl && /^https:\/\/github\.com\//.test(x.runUrl) ? ` · <a href="${esc(x.runUrl)}" target="_blank" rel="noopener">běh v CI</a>` : ''}</small><small>${x.files.map(f => esc(f.split('/').pop())).join(', ')}</small></span>
      <span class="acts">${['PUSHED', 'BUILDING', 'FAILED'].includes(x.status) || (x.status === 'READY' && !x.apkHere) ? `<button class="btn" data-act="suAct" data-id="${esc(x.id)}" data-a="poll">Zkontrolovat sestavení</button>` : ''}
      ${x.diff ? `<button class="btn ${x.status === 'READY' ? 'primary' : ''}" data-act="suDiff" data-id="${esc(x.id)}">${x.status === 'READY' ? 'Projít změny a nainstalovat' : 'Zobrazit změny'}</button>` : ''}
      ${!['INSTALLED', 'DISCARDED'].includes(x.status) ? `<button class="btn ghost" data-act="suAct" data-id="${esc(x.id)}" data-a="discard">Zahodit</button>` : ''}</span></div>`).join('');
  return `<h3>Úpravy aplikace (měním sama sebe)</h3>
    <p class="note">Napiš, co mám na sobě změnit. Úpravu napíšu, CI ji otestuje a podepíše; instalaci vždy potvrdíš ty. Měnit smím jen kód VEGY – ne sestavení, oprávnění ani klíče.</p>
    ${setup}
    ${u.hasToken ? `<textarea class="field" id="suTask" rows="2" placeholder="Např. „ať je tlačítko Odeslat větší“ nebo „přidej převod palců na centimetry“"></textarea>
    <div class="inline" style="margin-top:6px"><button class="btn primary" data-act="suStart">Upravit se</button>
    <label class="note" style="display:flex;gap:6px;align-items:center"><input type="checkbox" id="suAuto" data-act="suAuto" ${u.auto ? 'checked' : ''}> sama 1× denně z opakovaných slabin</label></div>` : ''}
    ${u.installed ? `<div class="card" style="margin-top:8px"><div class="note">Běží verze se samoúpravou „${esc(u.installed.task.slice(0, 80))}“ (${esc(u.installed.commit)}). Další úprava na ní naváže.</div>
      ${u.hasToken ? `<button class="btn ghost" data-act="suRollback" style="margin-top:6px">Vrátit poslední úpravu</button>` : ''}</div>` : ''}
    <div class="list" style="margin-top:10px">${items || '<div class="row"><span class="grow note">Zatím žádná úprava.</span></div>'}</div>`;
}

function library(tab) {
  S.libTab = tab || S.libTab;
  const T = { projects: 'Projekty', procs: 'Moje nástroje', memory: 'Paměť', improve: 'Vylepšení' };
  if (!T[S.libTab]) S.libTab = 'projects';
  let body = '';
  if (S.libTab === 'projects') {
    const ps = call('projects') || [];
    body = `<div class="inline"><input class="field" id="pname" placeholder="Název nového projektu"><button class="btn primary" data-act="newProject">Založit</button></div>
      <div class="list" style="margin-top:10px"><button class="row" data-act="folder"><span class="grow"><b>Importovat složku…</b><small>VEGA pracuje v kopii</small></span></button>
      ${ps.map(p => `<button class="row" data-act="openProject" data-id="${esc(p.id)}"><span class="grow"><b>${esc(p.name)}</b><small>${p.next ? esc(p.next) : p.files + ' souborů'}</small></span></button>`).join('')}</div>`;
  } else if (S.libTab === 'procs') {
    const ps = call('procedures') || [];
    body = ps.length ? `<div class="list">${ps.map(p => `<div class="row"><span class="grow"><b>${esc(p.name)} <span class="pill">v${p.version}</span></b><small>${esc(p.inputDesc || p.inputKind)} → ${esc(p.outputKind)} · ${plural(p.steps.length, 'krok', 'kroky', 'kroků')} · ${p.perms.length ? 'oprávnění: ' + p.perms.map(esc).join(', ') : 'bez oprávnění'}</small></span><button class="btn" data-act="runProc" data-id="${esc(p.id)}">Spustit</button><button class="more" data-act="procMenu" data-p='${esc(JSON.stringify(p))}' aria-label="Podrobnosti">⋯</button></div>`).join('')}</div>`
      : `<p class="lede">Zatím žádný uložený nástroj. Přilož výpis a řekni „udělej z toho nástroj“ – nebo spoj dva nástroje přes „Předat dál“ a ulož.</p>`;
  } else if (S.libTab === 'improve') {
    const v = call('improvements') || { rules: [], weak: [] };
    const st = { ACTIVE: ['zapnuto', 'ok'], PROPOSED: ['návrh', ''], REVERTED: ['vráceno', ''], REJECTED: ['zamítnuto', 'bad'] };
    body = `<p class="lede">Co jsem se naučila z tvých oprav. Pravidlo se zapne jen tehdy, když nezmění žádný kontrolní případ, a můžeš ho kdykoli vrátit. Deník zůstává jen v telefonu.</p>
      <h3>Naučená pravidla</h3><div class="list">${v.rules.map(r => `<div class="row stack"><span class="grow"><b>„${esc(r.examples[r.examples.length - 1] || r.tokens.join(' '))}“ → ${esc(r.target)} <span class="pill ${st[r.status][1]}">${st[r.status][0]}</span></b>
        <small>výrazy: ${r.tokens.map(esc).join(', ')} · doklady: ${r.evidence}${r.reason ? ' · ' + esc(r.reason) : ''}</small>
        <small>${r.history.map(h => esc(new Date(h.at).toLocaleDateString('cs-CZ') + ' ' + h.action)).join(' · ')}</small></span>
        <span class="acts">${r.status === 'ACTIVE' ? `<button class="btn" data-act="impr" data-id="${esc(r.id)}" data-a="revert">Vrátit</button>` : ''}
        ${r.status === 'PROPOSED' ? `<button class="btn primary" data-act="impr" data-id="${esc(r.id)}" data-a="activate">Zapnout</button><button class="btn ghost" data-act="impr" data-id="${esc(r.id)}" data-a="reject">Zamítnout</button>` : ''}</span></div>`).join('') || '<div class="row"><span class="grow note">Zatím nic. Když mě opravíš („ne, myslel jsem…“), zapíšu si to a příště to poznám.</span></div>'}</div>
      ${selfUpdateHtml()}
      <h3>Rozšíření (nástroje, které jsem napsala)</h3><div class="list">${(v.extensions || []).map(e => `<div class="row stack"><span class="grow"><b>${esc(e.name)} <span class="pill">v${e.version}</span> <span class="pill ${e.enabled ? 'ok' : ''}">${e.enabled ? 'zapnuto' : 'vypnuto'}</span></b>
        <small>${esc(e.description)} · bez sítě · ${esc(e.tests || 'testy prošly')}</small><small>${e.history.map(h => esc(new Date(h.at).toLocaleDateString('cs-CZ') + ' ' + h.action)).join(' · ')}</small></span>
        <span class="acts"><button class="btn ${e.enabled ? '' : 'primary'}" data-act="impr" data-id="${esc(e.id)}" data-a="${e.enabled ? 'extOff' : 'extOn'}">${e.enabled ? 'Vypnout' : 'Zapnout'}</button>
        ${e.canRollback ? `<button class="btn" data-act="impr" data-id="${esc(e.id)}" data-a="extBack">Vrátit verzi</button>` : ''}
        <button class="btn ghost" data-act="impr" data-id="${esc(e.id)}" data-a="extDel">Smazat</button></span></div>`).join('') || '<div class="row"><span class="grow note">Zatím žádné. Řekni třeba „napiš rozšíření, které z výpisu vytáhne IBANy“ – napíšu ho s testy, a když projdou, „ulož jako rozšíření“.</span></div>'}</div>
      <h3>Kde mám slabiny (30 dní)</h3><div class="list">${v.weak.map(w => `<div class="row ${w.propose ? 'stack' : ''}"><span class="grow"><b>${esc(w.kind)} · ${esc(w.area)} <span class="pill">${w.count}×</span></b><small>${esc(w.example)}</small></span>${w.propose ? `<span class="acts"><button class="btn" data-act="impr" data-id="${esc(w.raw)}" data-a="extPropose">Navrhnout rozšíření</button></span>` : ''}</div>`).join('') || '<div class="row"><span class="grow note">Bez záznamů.</span></div>'}</div>`;
  } else {
    const ms = call('memory') || [];
    body = `<p class="lede">Paměť mění, jak odpovídám. Klíče, hesla ani pokyny z webu si neukládám. Zapomenutí smaže i odvozený index.</p>
      <div class="inline"><input class="field" id="memText" placeholder="Pamatuj si…"><button class="btn primary" data-act="memAdd">Uložit</button></div>
      <div class="list" style="margin-top:10px">${ms.map(m => `<div class="row"><span class="grow"><b>${esc(m.text)}</b><small>${esc(m.kind)}${m.scope !== 'global' ? ' · jen projekt' : ''} · ${esc(m.why)}</small></span>${m.canRevert ? `<button class="btn" data-act="memRevert" data-id="${esc(m.id)}">Vrátit</button>` : ''}<button class="btn ghost" data-act="memForget" data-id="${esc(m.id)}">Zapomenout</button></div>`).join('') || '<div class="row"><span class="grow note">Zatím nic. Řekni „pamatuj si, že…“.</span></div>'}</div>`;
  }
  sheet(`<h2>${T[S.libTab]}</h2>${body}`, true);
}

const panelOpen = () => !!$('#layer [data-sheet="panel"]');
function projectPanel() {
  const p = call('project', S.space.id); if (!p) return;
  sheet(`<span data-sheet="panel" hidden></span><h2>${esc(p.name)}</h2><p class="lede">revize ${esc(p.revision)}${p.next ? ' · ' + esc(p.next) : ''}</p>
    <div class="inline" style="flex-wrap:wrap"><button class="btn primary" data-act="tests">Spustit testy</button><button class="btn" data-act="showDiff" data-p="${esc(p.id)}">Rozdíl</button><button class="btn" data-act="zip" data-p="${esc(p.id)}">ZIP</button></div>
    <h3>Změny</h3><div class="list">${p.changes.map(c => `<div class="row"><span class="grow"><b>#${c.id} ${esc(c.path)}</b><small>${esc(c.op)} ${esc(c.note)}</small></span>${c.op.startsWith('undo') ? '' : `<button class="btn" data-act="undo" data-p="${esc(p.id)}" data-c="${c.id}">Vrátit</button>`}</div>`).join('') || '<div class="row note">zatím žádné</div>'}</div>
    <h3>Soubory</h3><div class="list">${p.files.map(f => `<button class="row" ${f.secret ? '' : `data-act="openFile" data-path="${esc(f.path)}"`}><span class="grow"><b>${esc(f.path)}</b></span><span class="end">${f.secret ? 'citlivý – skryto' : f.size + ' B'}</span></button>`).join('') || '<div class="row note">prázdný</div>'}</div>
    <h3>Ověření</h3><div class="list">${p.verifications.map(v => `<button class="row" data-act="showVer" data-ver='${esc(JSON.stringify(v))}'><span class="grow"><b>${v.exitCode === 0 ? 'Prošlo' : 'Neprošlo'}</b><small>${esc(v.label)} · rev ${esc(v.revision)}</small></span></button>`).join('') || '<div class="row note">zatím žádné</div>'}</div>
    <details><summary>Symboly (${p.symbols.length})</summary><div class="list">${p.symbols.slice(0, 200).map(s => `<div class="row"><span class="grow"><b>${esc(s.name)}</b><small>${esc(s.kind)} · ${esc(s.path)}:${s.line}</small></span></div>`).join('')}</div></details>`, true);
}

function settingsSheet() {
  const s = Object.assign({ keys: {}, allowed: {}, budgets: {}, spent: {}, models: {} }, call('settings') || {});
  ['keys', 'allowed', 'budgets', 'spent', 'models'].forEach(k => { s[k] = s[k] || {}; });
  const keyRow = (k, name, hint) => `<div class="card"><div class="inline"><b class="grow">${name}</b>${s.keys[k] ? '<span class="pill ok">uloženo</span>' : ''}</div><div class="note">${hint}</div>
    <div class="inline" style="margin-top:8px"><input class="field" type="password" id="key-${k}" placeholder="${s.keys[k] ? 'Nový klíč (přepíše)' : 'Vlož API klíč'}" autocomplete="off"><button class="btn primary" data-act="saveKey" data-k="${k}">Uložit</button></div>
    <div class="status" id="kc-${k}">${s.models[k] ? 'Odpovídá: ' + esc(s.models[k]) : ''}${s.keys[k] ? ` <button class="btn ghost" data-act="checkKey" data-k="${k}" style="min-height:36px;padding:0 10px">Ověřit</button>` : ''}</div></div>`;
  sheet(`<h2>Nastavení</h2>
    <h3>Ty</h3>
    <div class="inline"><input class="field" id="uname" value="${esc(s.userName || '')}" placeholder="Jak tě mám oslovovat (např. Lukáši)"><button class="btn primary" data-act="saveName">Uložit</button></div>
    <h3>Odpovídání (zdarma)</h3>
    ${keyRow('gemini', 'Gemini – zdarma', 'Klíč z <b>aistudio.google.com/apikey</b> (projekt bez platební karty). Nevíš jak? Použij průvodce „Zapnout rozhovor zdarma“ na úvodní obrazovce.')}
    <label class="switch"><span><b>Veřejný web</b><small>hledání, zdroje a kurzy – nezávislé na volbě modelu</small></span><input type="checkbox" data-set="webAllowed" ${s.webAllowed !== false ? 'checked' : ''}></label>
    <label class="switch"><span><b>Bez sítě</b><small>nic neodejde z telefonu – fungují výpočty, nástroje a tvoje dokumenty</small></span><input type="checkbox" data-set="airgap" ${s.airgap ? 'checked' : ''}></label>

    <details><summary>Pro pokročilé</summary>
    <h3>Vlastní model (vlastní server)</h3>
    <div class="card"><div class="note">Server s otevřeným modelem (llama.cpp, Ollama, vLLM…) s OpenAI API. Doma v síti stačí <b>http://192.168.x.x:8080/v1</b>, přes internet jen https. Hotový server zdarma: <b>tools/vega-server</b> – po spuštění vypíše párovací kód.</div>
      <label class="lbl">Párovací kód</label><div class="inline"><input class="field" id="pairCode" placeholder="VEGA-PAIR:…" autocomplete="off" spellcheck="false"><button class="btn" data-act="pairOwn">Spárovat</button></div>
      <label class="lbl">Adresa serveru</label><input class="field" id="ownUrl" value="${esc(s.ownUrl || '')}" placeholder="http://192.168.1.20:8080/v1" inputmode="url" autocomplete="off">
      <label class="lbl">Model (prázdné = první, který server nabízí)</label><input class="field" id="ownModel" value="${esc(s.ownModel || '')}" placeholder="např. qwen2.5-7b-instruct" autocomplete="off">
      <label class="lbl">Klíč serveru (nepovinné)</label><input class="field" type="password" id="ownKey" placeholder="${s.ownKey ? 'uloženo – nový přepíše' : 'jen když ho server vyžaduje'}" autocomplete="off">
      <div class="inline" style="margin-top:10px"><button class="btn primary" data-act="saveOwn">Uložit a ověřit</button>${s.ownUrl ? '<button class="btn" data-act="testOwn">Vyzkoušet odpověď</button>' : ''}</div><div class="status" id="kc-own"></div></div>
    <label class="switch"><span><b>Pouze vlastní modely</b><small>celý požadavek jen na tvém serveru – žádné komerční API ani při výpadku</small></span><input type="checkbox" data-set="ownOnly" ${s.ownOnly ? 'checked' : ''}></label>

    <h3>Ověřovač citací</h3>
    <div class="card"><div class="note">Neuronový model v telefonu (bez sítě) ukáže, jestli citovaná pasáž tvrzení podporuje, odporuje mu, nebo nestačí. Váhy se stáhnou jednou jako ověřený balíček (otisk musí sedět), nebo ze souboru (.zip z tools/verifier).</div>
      <div class="status" id="kc-verifier">${s.verifierReady ? '<span class="pill ok">načteno</span> ' : '<span class="pill">vypnuto</span> '}${esc(s.verifier || '')}</div>
      <div class="inline" style="margin-top:8px"><button class="btn primary" data-act="verifierDownload">Stáhnout (150 MB)</button><button class="btn" data-act="verifierInstall">Ze souboru</button>${s.verifierReady ? '<button class="btn ghost" data-act="verifierRemove">Odebrat</button>' : ''}</div></div>

    <h3>Placené a záložní modely</h3>
    <div class="card"><div class="note">Používají se, až dojde bezplatná kvóta. Placené se bez tvého povolení s limitem nepoužijí.</div></div>
    ${keyRow('geminipro', 'Gemini – kredit Pro', 'Předplatné Google AI Pro má měsíční kredit (~10 USD). V placeném projektu vytvoř klíč a vlož ho sem.')}
    ${s.keys.geminipro ? `<label class="lbl">Strop kreditu za měsíc (Kč) · utraceno ${fmt(s.spent.geminipro)} Kč</label><input class="field" type="number" min="0" data-budget="geminipro" value="${s.budgets.geminipro || ''}" placeholder="např. 150">
      <label class="switch"><span><b>Čerpat kredit automaticky</b><small>bez ptaní, do stropu výše</small></span><input type="checkbox" data-set="allow.geminipro" ${s.allowed.geminipro ? 'checked' : ''}></label>` : ''}
    ${keyRow('groq', 'Groq – zdarma', 'Záloha, když Gemini narazí na limit.')}
    ${keyRow('cerebras', 'Cerebras – zdarma', 'Záloha.')}
    ${keyRow('openrouter', 'OpenRouter – zdarma modely', 'Záloha.')}
    ${keyRow('deepseek', 'DeepSeek – placený', 'Servery v Číně – citlivé podklady raději ne.')}
    ${keyRow('claude', 'Claude – placený', 'Anthropic API.')}
    <label class="lbl">Společný strop pro ostatní placené (Kč / měsíc)</label><input class="field" type="number" min="0" data-budgetall value="${s.budgetKc || 0}">
    ${['deepseek', 'claude'].filter(k => s.keys[k]).map(k => `<label class="switch"><span><b>${k === 'claude' ? 'Claude' : 'DeepSeek'} automaticky</b><small>v rámci společného stropu</small></span><input type="checkbox" data-set="allow.${k}" data-shared="1" ${s.allowed[k] ? 'checked' : ''}></label>`).join('')}
    <label class="lbl">Kurz USD → Kč</label><input class="field" type="number" step="0.1" min="10" max="60" data-usdkc value="${s.usdKc || 23.5}">

    <h3>Učení a úpravy aplikace</h3>
    <div class="list"><button class="row" data-act="libTab" data-tab="improve"><span class="grow"><b>Vylepšení</b><small>naučená pravidla z tvých oprav, rozšíření, úpravy aplikace</small></span><span class="end">›</span></button></div>
    <h3>Chování</h3>
    <label class="switch"><span><b>Iniciativa</b><small>navrhnu další krok u projektu</small></span><input type="checkbox" data-set="initiative" ${s.initiative ? 'checked' : ''}></label>
    <h3>Dokumenty ve vyhledávání</h3>
    <div class="note">Texty, které VEGA zná pro „najdi v mých souborech“. Můžeš je jednotlivě odebrat.</div>
    <button class="btn" data-act="docsManage">Spravovat (${s.indexed || 0})</button>
    <p class="note">Požadavky na modely: vlastní ${(s.requests || {}).own || 0} · komerční ${(s.requests || {}).commercial || 0}. Tento měsíc utraceno ${fmt(s.spentKc)} Kč.</p>
    </details>
    <p class="note" style="margin-top:24px">VEGA · samostatná aplikace, vlastní úložiště. Klíče jsou šifrované v trezoru telefonu a do rozhraní se nevracejí.</p>`, true);
}

/** Výběr modulu / poskytovatele: „Automaticky“ nebo konkrétní; ostatní nastavení zůstanou jako záloha při výpadku. */
function providerList(s) {
  const ch = call('providerChoices') || [];
  const pref = s.preferredProvider || '';
  const row = (id, name, sub, ok, sel) => `<button class="row${sel ? ' sel' : ''}" data-act="${ok ? 'setProvider' : 'settings'}" data-id="${esc(id)}" aria-pressed="${sel}"><span class="grow"><b>${sel ? '● ' : '○ '}${esc(name)}</b><small>${sub}</small></span>${ok ? '' : '<span class="end">nastavit ›</span>'}</button>`;
  return `<div class="list">${row('', 'Automaticky (doporučeno)', 'vlastní server, pak zdarma, placené jen se schválením', true, !pref)}` +
    ch.map(c => row(c.id, c.name, esc([c.model, c.paid ? (c.allowed ? 'placený, povolený do limitu' : 'placený – před použitím schválíš') : 'zdarma', c.active ? 'teď odpovídá' : ''].filter(Boolean).join(' · ')), c.ready, pref === c.id)).join('') + '</div>' +
    (s.ownOnly ? '<p class="note warn">Zapnuté „Pouze vlastní modely“ – výběr platí, až ho vypneš.</p>' : '');
}
function modelSheet() {
  const s = call('settings') || {}; const h = call('home') || {};
  sheet(`<h2>Kdo odpovídá</h2><p class="lede">Teď: <b>${esc(h.model || 'bez modelu')}</b></p>
    <h3>Modul / poskytovatel</h3>${providerList(s)}
    <h3>Soukromí</h3>
    ${s.ownUrl || s.ownOnly ? `<label class="switch"><span><b>Pouze vlastní modely</b><small>${s.ownUrl ? esc(s.ownUrl) : 'server zatím není nastavený – vypni to'}</small></span><input type="checkbox" data-set="ownOnly" ${s.ownOnly ? 'checked' : ''}></label>` : ''}
    <label class="switch"><span><b>Veřejný web</b><small>rešerše, zdroje, odkazy</small></span><input type="checkbox" data-set="webAllowed" ${s.webAllowed !== false ? 'checked' : ''}></label>
    <label class="switch"><span><b>Bez sítě</b><small>nic neodejde z telefonu</small></span><input type="checkbox" data-set="airgap" ${s.airgap ? 'checked' : ''}></label>
    <div class="list" style="margin-top:12px"><button class="row" data-act="settings"><span class="grow"><b>Vlastní server a klíče…</b><small>adresa, model, Gemini zdarma, kredit Pro</small></span><span class="end">›</span></button>
    <button class="row" data-act="temp"><span class="grow"><b>Dočasný chat</b><small>nic se neukládá</small></span></button></div>`);
}
/** Průvodce pro laika: klíč Gemini zdarma ve 3 krocích, bez celého Nastavení. */
function freeSetup() {
  sheet(`<h2>Rozhovor zdarma za 2 minuty</h2>
    <p class="lede">VEGA potřebuje <b>klíč</b> – něco jako heslo, kterým se přihlásí k modelu Google Gemini. Je zdarma, platební kartu nezadáváš.</p>
    <ol class="steps">
      <li>Otevři <a href="https://aistudio.google.com/apikey" target="_blank" rel="noopener">aistudio.google.com/apikey</a> a přihlas se svým Google účtem.</li>
      <li>Klepni na <b>Create API key</b> (Vytvořit klíč API) a klíč zkopíruj – začíná na <code>AIza</code> (velké I jako Ivan).</li>
      <li>Vlož ho sem a klepni na <b>Uložit a vyzkoušet</b>.</li>
    </ol>
    <input class="field" id="freeKey" type="password" placeholder="sem vlož klíč (AIza…)" autocomplete="off" spellcheck="false" aria-label="Klíč Gemini">
    <button class="btn primary wide" data-act="freeSave" style="margin-top:8px">Uložit a vyzkoušet</button><div class="status" id="kc-gemini" aria-live="polite"></div>
    <p class="note">Klíč zůstane šifrovaný jen v tomhle telefonu. Nový klíč z AI Studia je bez fakturace, takže nic nezaplatíš – nanejvýš dojde denní limit zdarma, který se druhý den obnoví.</p>
    <p class="note">Jiné možnosti (vlastní server, další poskytovatelé): <button class="linkbtn" data-act="settings">Nastavení</button></p>`);
  const k = $('#freeKey'); if (k) k.focus();
}
function pairOwn() {
  const el = $('#pairCode'); const p = el && el.value.trim() ? call('pairCode', el.value) : null;
  if (!p) { if (el && !el.value.trim()) toast('Vlož párovací kód ze serveru (začíná VEGA-PAIR:).'); return; }
  $('#ownUrl').value = p.url; $('#ownModel').value = p.model || '';
  const st = $('#kc-own'); if (st) st.textContent = 'Adresa a model vyplněny. Teď do „Klíč serveru“ vlož svůj VEGA_KEY a klepni na Uložit a ověřit.';
  const k = $('#ownKey'); if (k) k.focus();
}
function saveOwn() {
  const url = ($('#ownUrl') || {}).value.trim(), model = ($('#ownModel') || {}).value.trim(), key = ($('#ownKey') || {}).value.trim();
  if (call('setSetting', 'ownUrl', url) === null) return;
  call('setSetting', 'ownModel', model);
  if (key) call('saveKey', 'own', key);
  const st = $('#kc-own'); if (st) st.textContent = url ? 'Ověřuji server…' : 'Vlastní server odebrán.';
  if (url) B.checkKey('own');
  S.home = null; renderTop();
}

function demandSheet(s) {
  // ukázka je jen ŠEDÁ nápověda (placeholder) – cizí jména a částky z ukázky nesmí skončit v tvém PDF
  const f = (k, l, t = 'text') => `<label class="lbl">${l}</label><input class="field" data-dm="${k}" type="${t}" ${t === 'date' ? '' : `placeholder="např. ${esc(s[k])}"`}>`;
  sheet(`<h2>Předžalobní výzva</h2><p class="lede">Spočítám jistinu a úrok z prodlení k rozhodnému datu, u každé částky uvedu doklad a vyrobím DOCX i PDF.</p>
    <h3>Strany</h3>${f('creditor', 'Věřitel')}${f('creditorAddr', 'Adresa věřitele')}${f('debtor', 'Dlužník')}${f('debtorAddr', 'Adresa dlužníka')}
    <h3>Faktura</h3>${f('invoice', 'Číslo')}${f('subject', 'Za co')}${f('amount', 'Částka (Kč)', 'number')}${f('issued', 'Vystaveno', 'date')}${f('due', 'Splatnost', 'date')}
    <h3>Úhrada</h3>${f('payAmount', 'Částečná úhrada (Kč, nebo prázdné)', 'number')}${f('payDate', 'Datum úhrady', 'date')}${f('payDoc', 'Doklad')}
    <h3>Výpočet</h3>${f('asOf', 'Rozhodné datum', 'date')}${f('account', 'Účet pro úhradu')}
    <button class="btn primary wide" data-act="demandGo" style="margin-top:16px">Spočítat a vyrobit</button>`, true);
}

/* ------------------------------------------------------------------ odeslání */
function send() {
  const inp = $('#input'); const text = inp.value.trim();
  if (S.tool) { runTool(); return; }
  if (!text && !S.files.length) return;
  if (busyGuard()) return;
  const t = text || 'Co s tímhle souborem?';
  if (busyCall('send', chatId(), t, S.files.map(f => f.id)) === null) {
    renderChips();
    inp.focus();
    return;
  }
  if (S.space.kind === 'temp') S.tempMsgs.push({ role: 'user', text: t });
  S.stream = '';
  S.files = []; inp.value = ''; autosize();
  if (S.drafts) delete S.drafts[spaceKey()]; // odeslané už není rozpracované
  render();
}
function autosize() { const i = $('#input'); i.style.height = 'auto'; i.style.height = Math.min(i.scrollHeight, innerHeight * .34) + 'px'; }

// rozpracovaný vstup (text + přílohy) patří konkrétní konverzaci – přepnutím chatu se nepřenese adresát ani obsah
function spaceKey(sp) { const x = sp || S.space; return x.kind + ':' + (x.id || ''); }
function saveDraft() {
  const i = $('#input'); if (!i) return; S.drafts = S.drafts || {}; const k = spaceKey();
  if (i.value.trim() || S.files.length) S.drafts[k] = { text: i.value, files: S.files.slice() }; else delete S.drafts[k];
}
function setSpace(sp) {
  saveDraft();
  if (S.space && S.space.kind === 'chat' && S.space.id && sp.kind !== 'chat') S.prevChat = S.space.id;
  S.space = sp; S.tool = null; S.toolInput = null; S.stream = ''; S.lastCard = null;
  const d = (S.drafts || {})[spaceKey(sp)] || { text: '', files: [] };
  S.files = (d.files || []).slice();
  call('activate', sp.kind === 'project' ? sp.id : '');
  if (!S.req) closeSheet(true); // otevřený souhlas nesmí tiše zmizet (úloha by čekala 5 minut)
  S.stick = true; render();
  const i = $('#input'); if (i) { i.value = d.text || ''; autosize(); }
}

/* ------------------------------------------------------------------ JS testy: izolovaný Worker, bez sítě, limit času.
 * Harness běží v uzávěru: kód projektu (new Function) nevidí nonce ani skutečné postMessage a nemůže přepsat test/expect. */
function runJsTests(files, timeoutMs) {
  return new Promise(resolve => {
    const nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    let tampered = false;
    const harness = `(() => { 'use strict';
const post = self.postMessage.bind(self); const NONCE = ${JSON.stringify(nonce)};
const OIs = Object.is, JStr = JSON.stringify.bind(JSON), Freeze = Object.freeze, Keys = Object.keys, DefP = Object.defineProperty, Str = String, MathAbs = Math.abs, MathPow = Math.pow;
try { Object.defineProperty(self, 'postMessage', { value: () => {}, writable: false, configurable: false }); } catch (e) {}
const FILES = ${JSON.stringify(files)};
const out = []; const fmtv = v => typeof v === 'string' ? v : (() => { try { return JStr(v); } catch (e) { return Str(v); } })();
const cons = { log: (...a) => out.push(a.map(fmtv).join(' ')), error: (...a) => out.push('E ' + a.map(fmtv).join(' ')), warn: (...a) => out.push('W ' + a.map(fmtv).join(' ')), info: (...a) => out.push(a.map(fmtv).join(' ')) };
const lock = (k, v) => { try { DefP(self, k, { value: v, writable: false, configurable: false }); } catch (e) {} };
lock('console', Freeze(cons));
for (const k of ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'importScripts', 'indexedDB', 'caches']) lock(k, undefined);
const cache = {};
const dir = p => p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '';
function resolve(base, p) {
  const parts = (p.startsWith('/') ? [] : base.split('/').filter(Boolean)); for (const s of p.split('/')) { if (s === '..') parts.pop(); else if (s && s !== '.') parts.push(s); }
  const r = parts.join('/'); for (const c of [r, r + '.js', r + '/index.js', r + '.json']) if (c in FILES) return c; return null;
}
function load(path) {
  if (cache[path]) return cache[path].exports;
  const module = { exports: {} }; cache[path] = module;
  if (path.endsWith('.json')) { module.exports = JSON.parse(FILES[path]); return module.exports; }
  const req = p => { const r = resolve(dir(path), p); if (!r) throw new Error('Modul nenalezen: ' + p + ' (z ' + path + ')'); return load(r); };
  new Function('require', 'module', 'exports', '__filename', FILES[path])(req, module, module.exports, path);
  return module.exports;
}
let tests = [];
const show = v => { try { return typeof v === 'string' ? v : JStr(v, (k, x) => x === undefined ? 'undefined' : (typeof x === 'number' && !Number.isFinite(x)) ? Str(x) : typeof x === 'function' ? 'function' : x); } catch (e) { return Str(v); } }; const eq = (a, b) => JStr(a) === JStr(b);
let expects = 0;
// zachycené před načtením kódu projektu: projekt nesmí podvrhnout porovnání (String.prototype.includes = () => true)
const SIncl = Function.prototype.call.bind(String.prototype.includes), AIncl = Function.prototype.call.bind(Array.prototype.includes);
const IsArr = Array.isArray, NumFin = Number.isFinite, OKeys = Object.keys, ProtoOf = Object.getPrototypeOf, HasOwn = Function.prototype.call.bind(Object.prototype.hasOwnProperty);
const czErr = m => SIncl(m, 'Cannot use import statement') || SIncl(m, 'import declarations') ? m + ' → použij module.exports a require() místo import/export'
  : SIncl(m, 'is not defined') ? m + ' → chybí deklarace nebo require()' : SIncl(m, 'is not a function') ? m + ' → volaná věc není funkce (překlep, špatný export?)' : m;
const msgOf = e => { try { return e && e.message !== undefined ? Str(e.message) : Str(e); } catch (x) { return '?'; } };
/** Skutečné hluboké porovnání (ne přes JSON: [undefined, NaN] ≠ [null, null], Map ≠ {}, funkce ≠ undefined). */
const deq = (x, y, d) => { d = d || 0; if (OIs(x, y)) return true; if (d > 60 || typeof x !== 'object' || typeof y !== 'object' || x === null || y === null || ProtoOf(x) !== ProtoOf(y)) return false;
  if (x instanceof Map || x instanceof Set) return x.size === y.size && deq(Array.from(x), Array.from(y), d + 1);
  const kx = OKeys(x), ky = OKeys(y); if (kx.length !== ky.length) return false;
  for (let i = 0; i < kx.length; i++) { const k = kx[i]; if (!HasOwn(y, k) || !deq(x[k], y[k], d + 1)) return false; } return true; };
const matchers = (a, neg) => { const chk = (ok, msg) => { expects++; if (ok === !!neg) throw new Error((neg ? 'NEMĚLO platit: ' : '') + msg); };
  return Freeze({
    toBe: b => chk(OIs(a, b), 'čekáno ' + show(b) + ', je ' + show(a)),
    toEqual: b => chk(deq(a, b), 'čekáno ' + show(b) + ', je ' + show(a)),
    toBeCloseTo: (b, d = 2) => chk(typeof a === 'number' && NumFin(a) && NumFin(b) && MathAbs(a - b) <= MathPow(10, -d) / 2, 'čekáno ≈' + b + ', je ' + show(a)),
    toBeTruthy: () => chk(!!a, 'čekána pravda, je ' + show(a)),
    toBeFalsy: () => chk(!a, 'čekána nepravda, je ' + show(a)),
    toBeNull: () => chk(a === null, 'čekáno null, je ' + show(a)),
    toBeUndefined: () => chk(a === undefined, 'čekáno undefined, je ' + show(a)),
    toContain: b => chk(typeof a === 'string' ? SIncl(a, b) : IsArr(a) ? AIncl(a, b) : false, show(a) + ' neobsahuje ' + show(b)),
    toThrow: m => { let thrown = false, em = ''; try { a(); } catch (e) { thrown = true; em = msgOf(e); }
      chk(thrown && (m === undefined || SIncl(em, Str(m))), thrown ? 'výjimka „' + em + '“ neobsahuje „' + m + '“' : 'čekána výjimka'); },
  }); };
// expect(x) bez porovnání se NEpočítá jako ověření; .not obrací porovnání
const expect = a => { const m = matchers(a, false); return Freeze(Object.assign({}, m, { not: matchers(a, true) })); };
const APush = Function.prototype.call.bind(Array.prototype.push);
const test = (n, f) => { APush(tests, [n, f]); };
let befores = [];
lock('test', test); lock('it', test); lock('describe', (n, f) => f()); lock('expect', expect); lock('beforeEach', f => { APush(befores, f); });
const specs = Keys(FILES).filter(f => /\\.test\\.js$/.test(f) || /^test\\/.*\\.js$/.test(f)).sort();
// kód projektu nesmí přepsat iteraci polí a tím „vyprázdnit“ seznam testů (podvržené „prošlo“)
// (Array.prototype se nezmrazuje – rozbilo by to platný kód „a.map = …“; harness iteruje přes zachycené funkce a indexy)
try { Freeze(Object.getPrototypeOf([][Symbol.iterator]())); Freeze(Promise.prototype); Freeze(Promise); } catch (e) {}
(async () => {
  let pass = 0, fail = 0;
  try {
    for (let si = 0; si < specs.length; si++) {
      const s = specs[si]; tests = []; befores = [];
      try { load(s); } catch (e) { fail++; APush(out, '✗ ' + s + ': ' + czErr(msgOf(e))); continue; }
      const mine = tests, bf = befores; const cnt = mine.length;
      for (let ti = 0; ti < cnt; ti++) { const n = mine[ti][0], f = mine[ti][1];
        try { expects = 0; for (let bi = 0; bi < bf.length; bi++) await bf[bi](); await f(); if (expects === 0) throw new Error('test nic neověřuje (žádné expect s porovnáním)'); pass++; APush(out, '✓ ' + s + ' › ' + n); }
        catch (e) { fail++; APush(out, '✗ ' + s + ' › ' + n + ': ' + czErr(msgOf(e))); } }
    }
  } catch (e) { fail++; APush(out, '✗ testovací běh: ' + msgOf(e)); }
  finally {
    // souhrn VŽDY první a výstup omezený (200 000 řádků logu by schovalo výsledek a zahltilo most)
    const sum = pass + ' prošlo, ' + fail + ' selhalo';
    const body = out.length > 2000 ? out.slice(0, 1000).concat(['… (' + (out.length - 2000) + ' řádků vynecháno) …'], out.slice(-1000)) : out;
    post({ nonce: NONCE, exitCode: fail || pass === 0 ? 1 : 0, output: sum + (specs.length ? '' : '\\nŽádné testy (*.test.js).') + '\\n' + body.join('\\n').slice(0, 200000) + '\\n' + sum, command: specs.join(' ') });
  }
})();
})();`;
    let w;
    try { w = new Worker(URL.createObjectURL(new Blob([harness], { type: 'text/javascript' }))); } catch (e) { resolve({ exitCode: 1, output: 'Worker nejde spustit: ' + e.message, command: 'vega-test' }); return; }
    const t = setTimeout(() => { w.terminate(); resolve({ exitCode: 124, output: 'Překročen časový limit ' + timeoutMs + ' ms – test ukončen.', command: 'vega-test', timeout: true }); }, timeoutMs);
    w.onmessage = e => {
      const r = e.data || {};
      if (r.nonce !== nonce) { tampered = true; return; }
      clearTimeout(t); w.terminate();
      resolve(tampered ? { exitCode: 1, output: 'Kód projektu se pokusil podvrhnout výsledek testů – výsledek neplatí.\n' + r.output, command: r.command } : { exitCode: r.exitCode, output: r.output, command: r.command });
    };
    w.onerror = e => { clearTimeout(t); w.terminate(); resolve({ exitCode: 1, output: String(e.message || e), command: 'vega-test' }); e.preventDefault(); };
  });
}

/* ------------------------------------------------------------------ rozšíření (vrstva B): stejná izolace jako testy.
 * Kód rozšíření dostane jen vstup a parametry; síť, úložiště a import skriptů jsou uzamčené, CSP navíc connect-src 'none'. */
function runExtension(code, input, params, timeoutMs) {
  return new Promise(resolve => {
    const nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    const src = `(() => { 'use strict';
const post = self.postMessage.bind(self); const NONCE = ${JSON.stringify(nonce)};
try { Object.defineProperty(self, 'postMessage', { value: () => {}, writable: false, configurable: false }); } catch (e) {}
for (const k of ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'importScripts', 'indexedDB', 'caches', 'BroadcastChannel']) { try { Object.defineProperty(self, k, { value: undefined, writable: false, configurable: false }); } catch (e) {} }
for (const k of ['test', 'it', 'describe', 'expect']) { try { Object.defineProperty(self, k, { value: () => ({}), writable: false, configurable: false }); } catch (e) {} }
const module = { exports: {} };
try {
  new Function('module', 'exports', 'require', ${JSON.stringify(code)})(module, module.exports, () => { throw new Error('require není v rozšíření dostupné'); });
  if (typeof module.exports.run !== 'function') throw new Error('tool.js neexportuje run(input, params)');
  Promise.resolve(module.exports.run(${JSON.stringify(input)}, Object.freeze(${JSON.stringify(params || {})})))
    .then(v => { try { post({ nonce: NONCE, ok: true, output: typeof v === 'string' ? v : JSON.stringify(v, null, 2) }); } catch (x) { post({ nonce: NONCE, ok: false, error: 'výsledek nejde převést na text: ' + String(x && x.message || x) }); } }, e => post({ nonce: NONCE, ok: false, error: String(e && e.message || e) }));
} catch (e) { post({ nonce: NONCE, ok: false, error: String(e && e.message || e) }); }
})();`;
    let w;
    try { w = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' }))); } catch (e) { resolve({ ok: false, error: 'Worker nejde spustit: ' + e.message }); return; }
    const t = setTimeout(() => { w.terminate(); resolve({ ok: false, error: 'Překročen časový limit ' + timeoutMs + ' ms.' }); }, timeoutMs);
    w.onmessage = e => { const r = e.data || {}; if (r.nonce !== nonce) return; clearTimeout(t); w.terminate(); resolve({ ok: !!r.ok, output: String(r.output || '').slice(0, 200000), error: r.error }); };
    w.onerror = e => { clearTimeout(t); w.terminate(); resolve({ ok: false, error: String(e.message || e) }); e.preventDefault(); };
  });
}

/* ------------------------------------------------------------------ události z Kotlinu */
window.vega = {
  on(type, d) {
    // jedna vadná / neúplná událost nesmí zaseknout UI („pracuji…“ navždy) – zbytek aplikace jede dál
    try { this.dispatch(type, d || {}); } catch (e) { console.error('událost', type, e); if (type === 'done' || type === 'idle' || type === 'error') { S.busy = false; render(); }
      // nezobrazitelný dotaz na souhlas / cenu = „ne“ (nic neodejde ani se nezaplatí) – jinak by úloha 5 minut čekala
      if ((type === 'consent' || type === 'approve') && d && d.req) { try { B.answer(d.req, JSON.stringify({ ok: false })); } catch (x) { } S.req = null; } toast('Něco se nepodařilo zobrazit – zkus to prosím znovu.'); }
  },
  dispatch(type, d) {
    switch (type) {
      case 'message':
        S.stream = ''; if (!(d.msg && d.msg.card && d.msg.card.type === 'nfcWait')) S.nfc = false;
        if (d.chat === 'temp') S.tempMsgs.push(d.msg);
        if (d.chat === chatId()) { render(); if (!S.stick) jumpPill(true); const sr = $('#sr'); if (sr && d.msg && d.msg.role !== 'user') sr.textContent = String(d.msg.text || '').slice(0, 400); }
        else if (d.chat !== 'temp' || S.space.kind !== 'temp') toast('VEGA: ' + String(d.msg.text || 'výsledek je hotový').slice(0, 120), 8000);
        break;
      case 'delta': if (d.chat === chatId()) {
          // přírůstek: from = délka, na kterou navazuje (0 = nové volání modelu); starší tvar bez from = celý text
          // mezera (reload, přepnutí chatu, zahozená událost): nelepit – do 2 s přijde celý text od začátku
          if (S.streamChat !== d.chat) { S.stream = ''; S.streamChat = d.chat; } // text jiného chatu se nesmí stát prefixem
          if (typeof d.from === 'number' && d.from > S.stream.length) break;
          S.stream = typeof d.from === 'number' ? S.stream.slice(0, d.from) + d.text : d.text;
          if ($('#live')) liveSoon(); else { S.live = null; render(); if (!S.stick) jumpPill(true); } } break;
      case 'phase': { const ph = $('#phase'); if (ph) ph.textContent = d.text; break; }
      case 'busy': S.busy = true; render(); break;
      case 'idle': S.busy = false; S.stream = ''; render(); break;
      case 'done': S.busy = false; if (d.cancelled) S.nfc = false; if (d.cancelled) toast('Zastaveno. Hotové kroky zůstaly.'); render(); break;
      case 'error': S.busy = false; S.nfc = false; S.stream = ''; render(); toast(d.text, 7000); break;
      case 'toast': toast(d.text, 7000); break;
      case 'selfupdate': if ($('#layer .sheet') && S.libTab === 'improve') library('improve'); break;
      case 'files': {
        const received = (d.files || []).filter(f => !f.error);
        const ev = new Set(received.flatMap(f => f.evicted || []));
        const ok = received.filter(f => !ev.has(f.id));
        // všechny chyby v jedné hlášce (jinak by každá další přepsala předchozí) + kolik souborů se nevešlo
        const errs = (d.files || []).filter(f => f.error).map(f => f.error).concat(d.skipped ? [`Vzala jsem prvních 8 souborů, ${d.skipped} dalších ne – pošli je zvlášť.`] : []);
        if (errs.length) toast(errs.join(' · '), 9000);
        // přílohy vyhozené kvůli stropu paměti (150 MB) zmizí i z čipů – jinak by se tiše neposlaly
        if (ev.size && S.files) { S.files = S.files.filter(f => !ev.has(f.id)); toast('Nejstarší příloha se už nevešla do paměti (strop 150 MB) – odebral jsem ji.', 6000); }
        // vyhozená příloha zmizí i z rozepsaných zpráv jiných konverzací (jinak by po návratu odeslání selhalo)
        if (ev.size && S.drafts) Object.values(S.drafts).forEach(dr => { dr.files = (dr.files || []).filter(f => !ev.has(f.id)); });
        if (S.awaitTry) { S.awaitTry = false; if (ok.length) { busyCall('buttonTry', chatId(), ok[0].id); render(); } return; }
        if (S.awaitProc) { // soubor vybraný pro uložený nástroj patří jen jemu
          const id = S.awaitProc; S.awaitProc = null;
          if (ok.length) { busyCall('runProcedure', chatId(), id, { file: ok[0].id }); render(); if (ok.length > 1) toast('Nástroj jsem spustil na prvním souboru.'); }
          return;
        }
        if (S.space.kind === 'temp') {
          ok.forEach(f => call('dropAttachment', f.id));
          if (ok.length) toast('Dočasný chat používá jen text. Pro soubory otevři běžnou konverzaci.');
          renderChips(); break;
        }
        S.files = S.files.concat(ok); renderChips(); break;
      }
      case 'shared': { const i = $('#input'); i.value = i.value ? i.value + '\n' + d.text : d.text; autosize(); renderChips(); toast('Sdílený text je v poli – zkontroluj a odešli.'); break; }
      case 'voice': { const g = $('#go'); if (g) g.classList.remove('listen'); if (d.ok && d.text) { const i = $('#input'); i.value = (i.value ? i.value + ' ' : '') + d.text; autosize(); renderChips(); i.focus(); } else if (d.error) toast(d.error); break; }
      case 'project': if (d.silent) { S.home = null; renderTop(); break; } if (d.id) { const p = call('project', d.id); setSpace({ kind: 'project', id: d.id, name: p ? p.name : 'Projekt' }); if (d.imported !== undefined) toast(`Importováno souborů: ${d.imported}.${d.skipped ? ` Přes limit 2 MB: ${d.skipped}.` : ''}`); } break;
      case 'ask.closed': if (S.req === d.req) { S.req = null; closeSheet(true); } break;
      case 'consent':
        if (S.req === d.req) break;
        sheet(`<h2>Poslat podklady do cloudu?</h2><p class="lede">${esc(d.target)}${(d.fallback || []).length ? `<br>Při limitu může převzít: ${d.fallback.map(esc).join(', ')}` : ''}</p>
          <div class="card"><b>${d.chars.toLocaleString('cs-CZ')} znaků</b>${d.items.map(i => `<div class="note">• ${esc(i)}</div>`).join('')}</div>
          ${(d.notes || []).map(n => `<p class="note">${esc(n)}</p>`).join('')}
          <p class="note">${d.paid ? `Nejdřív zdarma; při limitu může čerpat placený klíč – nejvýš ~${fmt(d.estimateKc)} Kč za volání (v tvém stropu).` : 'Jen poskytovatelé zdarma.'}</p>
          <div class="inline" style="margin-top:12px"><button class="btn grow" data-act="answer" data-ok="0">Neposílat</button><button class="btn signal grow" data-act="answer" data-ok="1">Poslat</button></div>`);
        S.req = d.req; break;
      case 'approve':
        if (S.req === d.req) break;
        sheet(`<h2>${d.overCap ? 'Strop je vyčerpaný' : d.noFree ? 'Bezplatný model není nastavený' : 'Zdarma teď neodpovídá'}</h2><p class="lede">${esc(d.reason)}</p>
          <div class="card"><b>${esc(d.name)}</b> jen pro tento úkol<div class="res-big" style="padding:6px 0 0">nejvýš ${fmt(d.capKc ?? d.estimateKc)} Kč</div><div class="note">${d.calls > 1 ? `${Number(d.calls) || 0} volání · ` : ''}${(Number(d.chars) || 0).toLocaleString('cs-CZ')} znaků · nad tuhle částku úkol nepůjde</div></div>
          <p class="note">${esc(d.hint)}</p>
          <div class="inline" style="margin-top:12px"><button class="btn grow" data-act="answer" data-ok="0">Ne</button><button class="btn signal grow" data-act="answer" data-ok="1">Použít</button></div>`);
        S.req = d.req; break;
      case 'jstest': runJsTests(d.files, d.timeoutMs || 10000).then(r => B.answer(d.req, JSON.stringify(r))); break;
      case 'extrun': runExtension(d.code, d.input, d.params, d.timeoutMs || 8000).then(r => B.answer(d.req, JSON.stringify(r))); break;
      case 'verified': toast(d.kind === 'none' ? 'Testy nedoběhly – výsledek neplatí, zkus znovu.' : d.exitCode === 0 ? 'Testy prošly.' : 'Testy neprošly – výstup v panelu projektu.', 5000); if (S.space.kind === 'project' && panelOpen()) projectPanel(); break;
      case 'verifier': toast(d.message, 7000); if ($('#kc-verifier')) settingsSheet(); break;
      case 'keycheck': { const el = $('#kc-' + d.provider); if (el) el.innerHTML = `<span class="pill ${d.ok ? 'ok' : 'bad'}">${d.ok ? 'funguje' : 'neověřeno'}</span> ${esc(d.message)}` +
          // průvodce prvním spuštěním: po úspěchu jedním klepnutím do chatu
          (d.ok && $('#freeKey') ? '<button class="btn primary wide" data-act="freeDone" style="margin-top:10px">Hotovo – můžeme si povídat</button>' : (!d.ok ? ` <button class="btn ghost" data-act="checkKey" data-k="${esc(d.provider)}">Ověřit znovu</button>` : ''));
        else toast(d.message, 6000); S.home = null; break; }
      case 'nfc.start': B.nfc(chatId()); break;
      case 'nfc.listening': { S.nfc = true; render(); const ph = $('#phase'); if (ph) ph.textContent = 'Přilož štítek…'; break; }
      case 'nfc.writeDone': { S.nfc = false; render();
        if (d.error) toast(d.error, 8000);
        else toast(`Zapsáno (${d.size} B)${d.verified ? ' a ověřeno zpětným čtením ✓' : ' – zpětné čtení se nepovedlo, zkontroluj štítek'}.`, 8000); break; }
      case 'root': { const st = $('#rootStatus'); const cls = d.ok ? 'ok' : 'bad';
        if (st) st.innerHTML = `<span class="pill ${cls}">${d.ok ? 'Root ověřen' : 'nepovedlo se'}</span> ${esc(d.message || '')}`;
        else toast(d.message || '', 7000); if (d.ok && $('#layer .sheet')) hardwareSheet(); break; }
      case 'wifiQr': { const out = $('#qrOut');
        const html = `<img src="${/^data:image\/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$/.test(d.img || '') ? d.img : ''}" alt="WiFi QR" style="width:220px;height:220px;border-radius:12px;background:#fff;padding:8px"><p class="note">Naskenuj fotoaparátem telefonu hosta. Síť: ${esc(d.ssid)}. QR se nikam neukládá.</p>`;
        if (out) out.innerHTML = html; else sheet(`<h2>WiFi QR</h2>${html}`, true); break; }
      case 'nfc.off': S.nfc = false; render(); sheet(`<h2>NFC je vypnuté</h2><p class="lede">Zapni ho v systému a zkus to znovu.</p><button class="btn primary wide" data-act="nfcSettings">Otevřít nastavení NFC</button>`); break;
    }
  },
  /** Systémové Zpět: nejdřív zavřít list (= „ne“ u dotazu), pak režim nástroje; jinak ať aplikace jde do pozadí. */
  back() {
    if ($('#layer').innerHTML) { closeSheet(); return true; }
    if (S.tool) { S.tool = null; S.toolInput = null; renderChips(); return true; }
    // zpět do konverzace, ze které uživatel přišel (ne do nejnovější)
    if (S.space.kind !== 'chat') { setSpace(S.prevChat ? { kind: 'chat', id: S.prevChat, name: 'Konverzace' } : { kind: 'chat', id: null, name: 'Konverzace' }); return true; }
    return false;
  },
};

/* ------------------------------------------------------------------ klepnutí */
function act(a, d) {
  switch (a) {
    case 'send': send(); break;
    case 'voice': if (S.space.kind === 'temp' || !busyGuard()) { const g = $('#go'); if (g) g.classList.add('listen'); B.voice(JSON.stringify({ airgap: !!((call('settings') || {}).airgap) })); } break;
    case 'star': { const st = $('.star'); const h = $('#greet'); if (st) { st.classList.toggle('spin'); } if (h) { h.classList.add('fade'); setTimeout(() => { greetI = (greetI + 1) % GREETS.length; h.textContent = GREETS[greetI]((S.home || {}).userName); h.classList.remove('fade'); }, 300); } break; }
    case 'say': $('#input').value = d.v; send(); break;
    case 'findDocs': { closeSheet(true); const i = $('#input'); if (i) { i.value = 'Najdi v mých dokumentech: '; autosize(); i.focus(); i.setSelectionRange(i.value.length, i.value.length); } break; }
    case 'retry': if (busyGuard()) return; S.busy = true; if (call('retry', chatId(), d.req || '') === null) S.busy = false; render(); break;
    case 'resumeTask': if (busyGuard()) return; busyCall('resumeTask', chatId(), d.p); render(); break;
    case 'keep': { if (busyGuard()) return; const keep = [...document.querySelectorAll('[data-keep]')].filter(x => x.checked).map(x => x.dataset.keep);
      if (!keep.length) { toast('Vyber aspoň jednu změnu – nebo použij Vrátit.'); return; } busyCall('keepChanges', chatId(), d.p, d.task, keep.join(',')); render(); break; }
    case 'cardDiff': { const fs = JSON.parse(d.files); sheet(`<h2>Rozdíl</h2>${fs.map(f => `<h3>${esc(f.path)} · +${f.added} −${f.removed}</h3>${diffHtml(f.diff)}`).join('')}`, true); break; }
    case 'btnTry': if (busyGuard()) return; S.awaitTry = true; toast('Vyber JINÝ soubor stejného typu – ověřím na něm stejné kroky.'); B.pickFiles(); break;
    case 'btnSave': { const n = ($('#btnName') || {}).value || ''; const r = call('buttonSave', chatId(), n); if (r) { S.procs = null; toast(`Připnuto: ${r.name} (v${r.version})`); render(); } break; }
    case 'runBtn': if (busyGuard()) return; if (d.kind === 'TEXT') { const t = $('#input').value.trim(); if (!t) { toast('Napiš text, na který tlačítko použiju.'); return; } busyCall('runProcedure', chatId(), d.id, { text: t }); $('#input').value = ''; render(); }
      else { S.awaitProc = d.id; toast('Vyber soubor – tlačítko ho zpracuje.'); B.pickFiles(); } break;
    case 'memGlobal': { const r = call('rememberGlobal', d.t); if (r) toast('Uloženo jako globální preference.'); break; }
    case 'model': modelSheet(); break;
    case 'chkOpen': { const o = JSON.parse(d.c); const x = o.x, it = o.item;
      const t = it ? String(it.text || '') : ''; const ws = Math.max(0, x.ws || 0), we = Math.min(t.length, x.we || t.length);
      sheet(`<h2>[${x.n}] ${esc(CHK[x.label][1])}</h2><p class="lede">${esc(x.claim)}</p>${x.p ? `<p class="note">podpora ${fmt(x.p[0] * 100)} % · nedostačuje ${fmt(x.p[1] * 100)} % · odporuje ${fmt(x.p[2] * 100)} % (výsledek o této pasáži, ne o pravdivosti světa)</p>` : `<p class="note">${esc(x.reason)}</p>`}
        ${it ? `<div class="card"><b>${esc(it.title)}</b><div class="note">${link(it.url)} · staženo ${esc(it.fetched || '')}</div><p style="white-space:pre-wrap">${esc(t.slice(0, ws))}<mark>${esc(t.slice(ws, we))}</mark>${esc(t.slice(we))}</p></div>` : ''}`, true); break; }
    case 'verifierDownload': if (S.busy) { toast('Počkej, až doběhne rozdělaná práce.'); break; } if (busyCall('verifierDownload') !== null) toast('Stahuji ověřovač – stačí jednou, pak funguje i bez sítě. Doporučuju Wi-Fi.', 6000); break;
    case 'verifierInstall': B.verifierInstall(); toast('Vyber balíček ověřovače (.zip).'); break;
    case 'verifierRemove': call('verifierRemove'); toast('Ověřovač odebrán – citace budou „nevyhodnoceno“.'); settingsSheet(); break;
    case 'exportRetry': if (busyGuard()) return; busyCall('exportRetry', chatId(), d.run); render(); break;
    case 'taskLog': { const l = JSON.parse(d.log); sheet(`<h2>Záznam běhu</h2><div class="list">${l.map(x => `<div class="row"><span class="pill">r${x.rev}</span><span class="grow"><b>${esc(x.step)}${x.input !== '*' ? ' · ' + esc(x.input) : ''}</b><small>${esc(x.action)}</small></span></div>`).join('')}</div>`, true); break; }
    case 'profile': settingsSheet(); break;
    case 'saveOwn': saveOwn(); break;
    case 'pairOwn': pairOwn(); break;
    case 'pickModel': modelSheet(); break;
    case 'chatMenu': sheet(`<h2>${esc(d.t)}</h2><input class="field" id="chatTitle" value="${esc(d.t)}" aria-label="Název konverzace">
        <div class="inline" style="margin-top:8px"><button class="btn" data-act="chatRename" data-id="${esc(d.id)}">Přejmenovat</button><button class="btn ghost" data-act="chatDelete" data-id="${esc(d.id)}">Smazat konverzaci</button></div>`); break;
    case 'chatRename': if (call('renameChat', d.id, ($('#chatTitle') || {}).value || '') !== null) { closeSheet(true); drawer(); toast('Přejmenováno.'); } break;
    case 'chatDelete': if (!confirm('Smazat celou konverzaci? Nejde to vrátit.')) break;
      if (call('deleteChat', d.id) !== null) { if (S.drafts) delete S.drafts['chat:' + d.id]; closeSheet(true); if (S.space.kind === 'chat' && S.space.id === d.id) { S.mc = null; setSpace({ kind: 'chat', id: null }); } else render(); drawer(); toast('Konverzace smazána.'); } break;
    case 'freeSetup': freeSetup(); break;
    // jedním klepnutím z hlášky (místo hledání přepínače); dotaz pak stačí poslat znovu
    case 'netOn': if (d.what === 'web') call('setSetting', 'webAllowed', 'true'); else call('setSetting', 'airgap', 'false'); S.home = null; renderTop();
      toast(d.what === 'web' ? 'Veřejný web je zapnutý – pošli dotaz znovu.' : 'Režim Bez sítě je vypnutý – pošli dotaz znovu.', 5000); render(); break;
    case 'freeDone': closeSheet(true); S.home = null; render(); { const i = $('#input') || $('textarea'); if (i) i.focus(); } break;
    case 'freeSave': { const v = (($('#freeKey') || {}).value || '').trim();
      if (!v) { toast('Vlož klíč z AI Studia (začíná na AIza).'); break; }
      if (!/^AIza/.test(v.replace(/^.*?(AIza)/, '$1'))) { const st = $('#kc-gemini'); if (st) st.textContent = 'Tohle nevypadá jako klíč Gemini – ten začíná na „AIza“. Zkopíruj ho z AI Studia znovu.'; break; }
      doSaveKey('gemini', v); const st = $('#kc-gemini'); if (st && st.textContent.startsWith('Uloženo')) { const f = $('#freeKey'); if (f) f.value = ''; }
      S.home = null; break; }
    case 'setProvider': if (call('setSetting', 'preferredProvider', d.id || '') !== null) { S.home = null; renderTop(); modelSheet(); toast(d.id ? 'Odpovídat bude vybraný poskytovatel (ostatní jen jako záloha při výpadku).' : 'Automatický výběr.'); } break;
    case 'testOwn': { const st = $('#kc-own'); if (st) st.textContent = 'Ptám se serveru (u usnutého Space může první odpověď trvat i minuty)…'; call('testOwn'); break; }
    case 'saveName': { call('setSetting', 'userName', ($('#uname') || {}).value || ''); S.home = null; toast('Uloženo.'); render(); break; }
    case 'stop': B.stop(); break;
    case 'plus': plusSheet(); break;
    case 'pick': closeSheet(true); if (S.space.kind === 'temp') { toast('Dočasný chat soubory nepoužívá – otevři běžnou konverzaci.'); return; } B.pickFiles(); break;
    case 'folder': closeSheet(true); B.pickFolder(); break;
    case 'tools': toolsSheet(); break;
    case 'useTool': useTool(d.id); break;
    case 'runTool': runTool(); break;
    case 'exitTool': S.tool = null; S.toolInput = null; renderChips(); break;
    case 'nfc': if (busyGuard()) return; closeSheet(true);
      if (S.space.kind === 'temp') { toast('Přilož štítek k zadní straně telefonu…'); B.nfc('temp'); return; }
      busyCall('send', chatId(), 'Načti NFC štítek', []); render(); break;
    case 'nfcSettings': B.nfcSettings(); closeSheet(true); break;
    case 'wifi': if (busyGuard()) return; closeSheet(true);
      if (!B.wifiScan) { toast('WiFi analyzátor v této verzi není.'); break; }
      // laikovi předem vysvětlit, proč systém žádá o polohu a co se s ní (ne)děje
      if (!lsGet('vega.locInfo')) { sheet(`<h2>Proč WiFi potřebuje polohu</h2><p class="lede">Android ukáže okolní WiFi sítě jen aplikaci s oprávněním k poloze – z názvů sítí by se dala poloha odvodit.</p>
        <p class="note">VEGA polohu <b>nezjišťuje, neukládá ani nikam neposílá</b>. Seznam sítí zůstává v telefonu. Oprávnění můžeš kdykoli odebrat v Nastavení telefonu → Aplikace → VEGA.</p>
        <div class="inline" style="margin-top:12px"><button class="btn primary" data-act="wifiGo">Pokračovat</button><button class="btn ghost" data-act="close">Teď ne</button></div>`); break; }
      act('wifiGo', {}); break;
    case 'wifiGo': lsSet('vega.locInfo', '1'); closeSheet(true); if (busyGuard()) return;
      S.busy = true; S.busyChat = chatId(); B.wifiScan(chatId() || 'temp'); render(); toast('Projíždím WiFi kolem… (Android může požádat o polohu)', 6000); break;
    // --- Hardware ---
    case 'hw': if (!B.hwJson) { toast('Sekce Hardware v této verzi není.'); break; } hardwareSheet(); break;
    case 'hwNew': hwCardSheet(null); break;
    case 'hwEdit': { const hw = call('hwJson') || { cards: [] }; const c = (hw.cards || []).find(x => x.id === d.id); if (c) hwCardSheet(c); break; }
    case 'hwSave': {
      const name = ($('#hwName') || {}).value || ''; if (!name.trim()) { toast('Zadej název karty.'); break; }
      const fields = {}; const shared = [];
      [0, 1, 2].forEach(i => { const k = ($(`[data-fk="${i}"]`) || {}).value; const v = ($(`[data-fv="${i}"]`) || {}).value; const on = ($(`[data-fs="${i}"]`) || {}).checked;
        if (k && k.trim()) { fields[k.trim()] = (v || '').trim(); if (on) shared.push(k.trim()); } });
      const card = { id: ($('#hwId') || {}).value || '', name: name.trim(), publicId: (($('#hwPub') || {}).value || '').trim(), fields, shared };
      if (call('hwSaveCard', JSON.stringify(card)) !== null) { closeSheet(true); hardwareSheet(); toast('Karta uložená.'); } break;
    }
    case 'hwDel': if (!confirm('Smazat kartu?')) break; if (call('hwDeleteCard', d.id) !== null) { closeSheet(true); hardwareSheet(); toast('Smazáno.'); } break;
    case 'hwAct': { const r = call('hwActivate', d.id); if (r) { hardwareSheet(); toast(r, 7000); } break; }
    case 'hwDeact': call('hwDeactivate'); hardwareSheet(); toast('Karta deaktivovaná – už se nesdílí.'); break;
    case 'hwReadCard': if (busyGuard()) return; closeSheet(true); S.nfc = true; S.busy = true; S.busyChat = chatId(); B.nfcReadCard(chatId() || 'temp'); render(); toast('Přilož druhý telefon s aktivní kartou VEGA zády k sobě…', 7000); break;
    case 'ndefWrite': ndefWriteSheet(); break;
    case 'ndefWriteGo': {
      const kind = ($('#ndefKind') || {}).value || 'text'; const content = (($('#ndefContent') || {}).value || '').trim();
      if (!content) { toast('Napiš, co se má na štítek zapsat.'); break; }
      closeSheet(true); S.nfc = true; render(); call('nfcWrite', chatId() || 'temp', kind, content); toast('Přilož vlastní zapisovatelný štítek k telefonu…', 7000); break;
    }
    case 'wifiLink': if (busyGuard()) return; closeSheet(true); S.busy = true; S.busyChat = chatId(); B.wifiLink(chatId() || 'temp'); render(); break;
    case 'wifiTools': wifiToolsSheet(); break;
    case 'docsManage': docsManageSheet(); break;
    case 'docRemove': if (call('indexRemove', d.id) !== null) { docsManageSheet(); toast('Dokument odebrán z vyhledávání.'); } break;
    case 'qrReveal': {
      const field = $('#qrPw'), button = $('[data-act="qrReveal"]');
      const show = field.type === 'password';
      field.type = show ? 'text' : 'password';
      button.textContent = show ? 'Skrýt heslo' : 'Ukázat heslo';
      button.setAttribute('aria-pressed', String(show));
      break;
    }
    case 'wifiGen': { const g = call('wifiGenPw'); if (g) { const el = $('#qrPw'); if (el) el.value = g; wifiPwCheckNow(); } break; }
    case 'wifiPwCheck': wifiPwCheckNow(); break;
    case 'wifiQrGo': {
      const ssid = (($('#qrSsid') || {}).value || '').trim(); if (!ssid) { toast('Zadej název sítě (SSID).'); break; }
      call('wifiQr', ssid, ($('#qrPw') || {}).value || '', 'WPA', !!($('#qrHidden') || {}).checked); break;
    }
    case 'ble': if (busyGuard()) return; closeSheet(true); S.nfc = true; S.busy = true; S.busyChat = chatId(); B.bleScan(chatId() || 'temp'); render(); toast('Hledám Bluetooth zařízení (6 s)…', 6000); break;
    case 'rootVerify': { const st = $('#rootStatus'); if (st) st.textContent = 'Ověřuji… (v Magisku potvrď přístup)'; call('rootVerify'); break; }
    case 'rootDisable': call('rootDisable'); { const st = $('#rootStatus'); if (st) st.innerHTML = '<span class="pill">vypnuto</span> Relace ukončena. Oprávnění odebereš i v Magisku.'; } break;
    case 'hwMeasureSave': { const r = call('hwSaveMeasure', d.d); if (r) toast(r, 6000); } break;
    case 'demand': if (busyGuard()) return; { const smp = call('demandSample'); if (smp) demandSheet(smp); } break;
    case 'demandForm': demandSheet(JSON.parse(d.s)); break;
    case 'demandGo': {
      const o = {}; let missing = null;
      if (busyGuard()) return;
      document.querySelectorAll('[data-dm]').forEach(i => {
        if (i.type === 'number' && i.validity.badInput) missing = missing || i.previousElementSibling.textContent + ' (číslo s desetinnou tečkou nebo čárkou)';
        o[i.dataset.dm] = i.type === 'number' ? (i.value === '' ? 0 : +i.value) : i.value;
        if (['issued', 'due', 'asOf', 'creditor', 'debtor', 'amount'].includes(i.dataset.dm) && !i.value) missing = missing || i.previousElementSibling.textContent;
      });
      if (missing) { toast('Vyplň: ' + missing); return; }
      if (o.payAmount > 0 && !o.payDate) { toast('Doplň datum úhrady.'); return; }
      o.b2b = true; closeSheet(true); busyCall('demand', chatId(), o); render(); break;
    }
    case 'demandMenu': { const c = JSON.parse(d.d); sheet(`<h2>Výzva</h2><div class="list">${c.files.map(f => `<button class="row" data-act="openOut" data-f="${esc(f)}"><span class="grow"><b>Otevřít ${esc(f.split('.').pop().toUpperCase())}</b></span></button><button class="row" data-act="share" data-f="${esc(f)}"><span class="grow"><b>Sdílet ${esc(f.split('.').pop().toUpperCase())}</b></span></button>`).join('')}</div>
        <h3>Výpočet a původ částek</h3><div class="tbl"><table><tr><th>Položka</th><th>Kč</th><th>Doklad</th></tr>${c.lines.map(l => `<tr><td>${esc(l.label)}</td><td>${fmt(l.amount)}</td><td>${esc(l.origin)}</td></tr>`).join('')}</table></div>
        <h3>Právní opora (${esc(c.rateSource)})</h3>${c.legal.map(l => `<p class="note">• ${esc(l)}</p>`).join('')}
        <h3>Kontrola PDF</h3><p>${c.checks.map(k => `<span class="pill ${k.ok ? 'ok' : 'bad'}">${esc(k.what)}</span>`).join(' ')}</p>`, true); break; }
    case 'openOut': B.openOut(d.f); break;
    case 'share': B.share(d.f); break;
    case 'copyMsg': case 'shareMsg': { const m = msgs()[+d.i]; if (!m || !m.text) break;
      const t = plainText(m.text);
      if (a === 'copyMsg') { call('copyText', t); toast('Zkopírováno – vlož ho, kam potřebuješ.'); } else call('shareText', t); break; }
    case 'library': if (d.tab) library(d.tab); else drawer(); break;
    case 'libTab': library(d.tab); break;
    case 'suSave': { const t = ($('#ghTok') || {}).value || ''; const r1 = call('setSetting', 'ghRepo', ($('#ghRepo') || {}).value || ''); const r2 = call('setSetting', 'ghBase', ($('#ghBase') || {}).value || '');
      const r3 = t ? call('saveKey', 'github', t) : true; if (r1 !== null && r2 !== null && r3 !== null) toast('Uloženo.'); library('improve'); break; }
    case 'suStart': { const t = (($('#suTask') || {}).value || '').trim(); if (!t) { toast('Napiš, co mám na sobě změnit.'); break; } if (busyGuard()) return; S.busy = true; if (call('selfUpdateStart', t, chatId() || '')) { closeSheet(true); toast('Připravuji úpravu sebe sama – výsledek přijde do chatu a do karty Vylepšení.', 6000); } render(); break; }
    case 'suDiff': { const x = ((call('selfUpdates') || {}).items || []).find(i => i.id === d.id); if (!x) break;
      sheet(`<h2>Změny, které jsem na sobě udělala</h2><p class="lede">${esc(x.task)}</p>${x.diffCut ? '<p class="note warn"><b>Diff je zkrácený</b> – celý ho projdi na GitHubu, než instaluješ.</p>' : ''}<p class="note">Hlídač (heuristika, ne záruka – proto čti diff) zkontroloval: jen kód a UI VEGY, bez citlivých míst (klíče, síť, most, platby, souhlasy, samoúpravy), testy v CI prošly${x.status === 'READY' ? '' : ' (zatím neprošly – čeká se na CI)'}. Přesto si změny projdi – instaluješ kód, který napsal model.</p>
        <pre class="diff">${esc(x.diff)}</pre>${x.status === 'READY' ? `<button class="btn primary wide" data-act="suAct" data-id="${esc(x.id)}" data-a="install">Prošel jsem změny – nainstalovat</button>` : ''}`, true); break; }
    case 'suRollback': if (busyGuard()) return; if (!confirm('Sestavit novou verzi bez samoúprav? Data v aplikaci zůstanou.')) break; if (busyCall('selfUpdateRollback', chatId() || '') !== null) toast('Připravuji návrat – výsledek přijde do chatu a do Vylepšení.', 6000); break;
    case 'suAuto': call('setSetting', 'autoImprove', String(!!($('#suAuto') || {}).checked)); break;
    case 'suAct': { const r = call('selfUpdateAction', d.id, d.a); if (typeof r === 'string') toast(r, 7000); else toast({ poll: 'Kontroluji sestavení…', discard: 'Zahazuji úpravu (větev).', install: 'Potvrď instalaci v systémovém dialogu.' }[d.a]); if (d.a !== 'install') setTimeout(() => library('improve'), 1500); break; }
    case 'older': { S.showAll = S.space.kind + ':' + S.space.id; const el = $('#stream'); const h = el.scrollHeight; renderStream(); el.scrollTop = el.scrollHeight - h; break; }
    case 'impr': { const r = call('improvementAction', d.id, d.a); if (typeof r === 'string') toast('Nezapnuto: ' + r); else toast({ revert: 'Vráceno – pravidlo už neplatí.', reject: 'Zamítnuto.', extOff: 'Vypnuto.', extBack: 'Vráceno na předchozí verzi.', extDel: 'Smazáno.', extPropose: 'Píšu rozšíření v novém projektu – až testy projdou, napiš „ulož jako rozšíření“.' }[d.a] || 'Zapnuto.'); library('improve'); break; }
    case 'settings': settingsSheet(); break;
    case 'new': goHome(); break; // znovu použije prázdnou konverzaci – v „Nedávné“ se nehromadí prázdné chaty
    case 'home': goHome(); break;
    case 'temp': S.tempMsgs = []; call('clearTemp'); setSpace({ kind: 'temp' }); break;
    case 'openChat': setSpace({ kind: 'chat', id: d.id, name: 'Konverzace' }); break;
    case 'newProject': { const name = ($('#pname') || {}).value || 'Nový projekt'; const id = call('newProject', name); if (id) setSpace({ kind: 'project', id, name }); break; }
    case 'openProject': { const p = call('project', d.id); if (p) setSpace({ kind: 'project', id: d.id, name: p.name }); break; }
    case 'close': closeSheet(); break;
    case 'answer': { const ok = d.ok === '1'; const r = J(B.answer(S.req, JSON.stringify({ ok }))); S.req = null; closeSheet(true); if (r === false) toast('Žádost už vypršela – pošli zprávu znovu.'); break; }
    case 'chip': chipAct(d.v); break;
    case 'dropFile': S.files = S.files.filter(f => f.id !== d.id); if (B.dropAttachment) B.dropAttachment(d.id); renderChips(); break;
    case 'export': { const r = call('export', d.run, -1); if (!r) break;
      // kam se soubor opravdu uložil + rovnou Otevřít / Sdílet (laik ho jinak nenajde)
      sheet(`<h2>Soubor je uložený</h2><p class="lede"><b>${esc(r.name)}</b><br>${r.public ? 'Najdeš ho i ve Stažených souborech → složka VEGA.' : 'Je uložený v aplikaci – otevřeš nebo pošleš ho tady.'}</p>
        <div class="inline"><button class="btn primary" data-act="openOut" data-f="${esc(r.name)}">Otevřít</button><button class="btn" data-act="share" data-f="${esc(r.name)}">Sdílet / poslat</button></div>`); break; }
    case 'saveProc': { const r = call('saveProcedure', JSON.parse(d.save)); if (r) { if (d.run) S.savedRuns.add(d.run); S.procs = null; toast(`Nástroj „${r.name}“ uložen (v${r.version}). Nabídnu ho u dalšího souboru.`); closeSheet(true); render(); } break; }
    case 'resume': if (busyGuard()) return; busyCall('resume', chatId(), d.run, {}); render(); break;
    case 'runMenu': runMenu(d.run); break;
    case 'step': { const r = call('run', d.run); if (r) sheet(`<h2>Krok ${+d.i + 1}: ${esc(r.steps[d.i].title)}</h2>${valueHtml(Object.assign({ run: d.run }, r.stepValues[d.i] || {}), 500) || '<p class="lede">Krok nemá výsledek.</p>'}`, true); break; }
    case 'full': { const r = call('run', d.run); if (r) sheet(`<h2>${esc(r.name)}</h2>${valueHtml(Object.assign({ run: d.run }, r.value), 500)}`, true); break; }
    case 'pass': passSheet({ run: d.run }); break;
    case 'passRecord': if (d.run) passSheet({ ndefRun: d.run, record: +d.i }); break;
    case 'passTo': useTool(d.id, S.passInput); break;
    case 'undo': { const r = call('undo', d.p, +d.c); if (r) { toast('Vráceno: ' + r); render(); if (S.space.kind === 'project' && panelOpen()) projectPanel(); } break; }
    case 'showDiff': { const ds = call('diff', d.p) || []; sheet(`<h2>Rozdíl proti importu</h2>${ds.map(f => `<h3>${esc(f.path)} · +${f.added} −${f.removed}</h3>${diffHtml(f.diff)}`).join('') || '<p class="lede">Beze změn.</p>'}`, true); break; }
    case 'showVer': { const v = JSON.parse(d.ver); sheet(`<h2>${v.kind !== 'run' && v.kind !== 'static' ? 'Neověřeno' : v.exitCode === 0 ? 'Prošlo' : 'Neprošlo'}</h2><p class="lede">${esc(v.label)} · revize ${esc(v.revision)}</p><p class="note">${esc(v.command)}</p><pre>${esc(v.output)}</pre>`, true); break; }
    case 'tests': closeSheet(true); if (S.space.kind === 'project') { toast('Spouštím testy…'); B.verify(S.space.id); } break;
    case 'zip': { const n = call('exportProject', d.p); if (n) toast('ZIP ve Stažené/VEGA (bez souborů s klíči): ' + n); break; }
    case 'openFile': { const f = call('file', S.space.id, d.path); if (f && !f.secret) { sheet(`<h2>${esc(d.path)}</h2><textarea class="field" id="fedit" rows="18" style="font-family:var(--mono);font-size:13px">${esc(f.text)}</textarea><button class="btn primary wide" id="fsave" style="margin-top:10px">Uložit (s kontrolou verze)</button>`, true); $('#fsave').onclick = () => { const r = call('saveFile', S.space.id, d.path, $('#fedit').value, f.hash || ''); if (r !== null) { toast('Uloženo jako změna #' + r); closeSheet(true); } }; } break; }
    case 'sources': { const it = JSON.parse(d.src); sheet(`<h2>Zdroje</h2>${it.map(s => `<div class="card"><b>[${s.n}] ${esc(s.title)}</b><div class="note">${link(s.url)} · staženo ${esc(s.fetched)}</div><details><summary>Původní text</summary><p class="note" style="white-space:pre-wrap">${esc(s.text)}</p></details></div>`).join('')}`, true); break; }
    case 'coverage': { const c = JSON.parse(d.cov); sheet(`<h2>Kontrola úplnosti</h2><div class="list">${(c.items || []).map(i => `<div class="row"><span class="pill ${i.ok ? 'ok' : 'bad'}">${i.ok ? '✓' : 'chybí'}</span><span class="grow">${esc(i.q)}</span></div>`).join('')}</div>${(c.passages || []).length ? '<h3>Citované pasáže</h3>' : ''}${(c.passages || []).map(p => `<details><summary>${esc(p.id)}</summary><p class="note" style="white-space:pre-wrap">${esc(p.text)}</p></details>`).join('')}`, true); break; }
    case 'runProc': closeSheet(true); if (busyGuard()) return; S.awaitProc = d.id; toast('Vyber soubor – pak nástroj spustím.'); B.pickFiles(); break;
    case 'pinProc': call('pinProcedure', d.id, d.on); S.procs = null; toast(d.on === 'true' ? 'Připnuto na úvodní obrazovku.' : 'Odepnuto.'); library('procs'); break;
    case 'procMenu': { const p = JSON.parse(d.p); sheet(`<h2>${esc(p.name)} <span class="pill">v${p.version}</span></h2><p class="lede">${esc(p.description || '')}</p>
        <button class="btn wide" data-act="pinProc" data-id="${esc(p.id)}" data-on="${p.pinned ? 'false' : 'true'}" style="margin:6px 0">${p.pinned ? 'Odepnout z úvodní obrazovky' : 'Připnout na úvodní obrazovku'}</button>
        <div class="card"><div class="note">Vstup: ${esc(p.inputDesc || p.inputKind)}</div><div class="note">Výstup: ${esc(p.outputKind)}</div><div class="note">Kroky: ${p.steps.map(esc).join(' → ')}</div><div class="note">Oprávnění: ${p.perms.length ? p.perms.map(esc).join(', ') : 'žádná'} · použito ${p.uses}×</div></div>
        <h3>Verze</h3>${p.changelog.map(c => `<p class="note">${esc(c)}</p>`).join('')}${p.version > 1 ? `<button class="btn wide" data-act="revProc" data-id="${esc(p.id)}">Vrátit předchozí verzi</button>` : ''}
        <button class="btn wide" data-act="delProc" data-id="${esc(p.id)}" style="margin-top:14px;color:var(--bad)">Smazat nástroj</button>`); break; }
    case 'revProc': { const r = call('revertProcedure', d.id); if (r) { toast('Vráceno – postup je teď v' + r.version + ' (obsah předchozí verze).'); S.procs = null; library('procs'); } break; }
    case 'delProc': if (confirm('Smazat uložený nástroj?')) { call('deleteProcedure', d.id); S.procs = null; library('procs'); } break;
    case 'memAdd': { const v = ($('#memText') || {}).value; if (v) { const r = call('memoryAdd', v); if (r) toast(r.ok ? 'Zapamatováno.' : r.reason, 5000); library('memory'); } break; }
    case 'memRevert': { const r = call('memoryRevert', d.id); toast(r ? 'Vráceno na: ' + r : 'Není co vrátit'); if ($('#layer').innerHTML) library('memory'); break; }
    case 'memClearAll': { const n = call('memoryClear'); if (n !== null) { toast(`Smazáno ${Number(n) || 0} záznamů z paměti.`); S.mc = null; render(); } break; }
    case 'memForget': call('memoryForget', d.id); toast('Zapomenuto – i z indexu.'); library('memory'); break;
    case 'saveKey': saveKey(d.k); break;
    case 'checkKey': B.checkKey(d.k); { const el = $('#kc-' + d.k); if (el) el.textContent = 'Ověřuji…'; } break;
    case 'isFree': { closeSheet(true); settingsSheet(); doSaveKey('gemini', S.pendingKey); S.pendingKey = null; break; }
    case 'toPro': { closeSheet(true); const r = call('saveKey', 'geminipro', S.pendingKey); S.pendingKey = null; settingsSheet(); if (r) toast('Uloženo jako kredit Pro – ověřuji. Pro automatické čerpání nastav strop a zapni „Čerpat automaticky“.', 6000); break; }
    case 'cancelKey': S.pendingKey = null; closeSheet(true); settingsSheet(); break;
  }
}

function saveKey(k) {
  const el = $('#key-' + k); const v = el ? el.value : '';
  if (!v.trim()) { toast('Vlož klíč.'); return; }
  if (k === 'gemini') {
    S.pendingKey = v;
    sheet(`<h2>Zadával jsi u Googlu platební kartu?</h2><p class="lede">Klíč, který sis právě vytvořil v AI Studiu, je obvykle <b>zdarma</b>. Jen pokud máš u Googlu Cloud zapnuté placení (kartu nebo kredit), mohl by klíč čerpat peníze – pak ho dej do „kredit Pro“, kde hlídám limit.</p>
      <div class="list"><button class="row" data-act="isFree"><span class="grow"><b>Ne, kartu jsem nezadával (doporučeno)</b><small>uložit jako Gemini zdarma</small></span></button><button class="row" data-act="toPro"><span class="grow"><b>Ano, mám u Googlu placení</b><small>uložit jako Gemini – kredit Pro (hlídám strop)</small></span></button><button class="row" data-act="cancelKey"><span class="grow"><b>Zrušit</b></span></button></div>`);
    return;
  }
  doSaveKey(k, v);
}
function doSaveKey(k, v) {
  const r = call('saveKey', k, v);
  if (r) { const st = $('#kc-' + k); if (st) st.textContent = 'Uloženo – ověřuji…'; const el = $('#key-' + k); if (el) el.value = ''; }
}

function runMenu(run) {
  const r = call('run', run); if (!r) return;
  sheet(`<h2>${esc(r.name)}</h2><div class="list">
    <button class="row" data-act="full" data-run="${run}"><span class="grow"><b>Otevřít celé</b></span></button>
    <button class="row" data-act="export" data-run="${run}"><span class="grow"><b>Uložit soubor</b><small>Stažené/VEGA</small></span></button>
    <button class="row" data-act="pass" data-run="${run}"><span class="grow"><b>Předat dál…</b><small>spojit s dalším nástrojem</small></span></button>
    ${r.save && !S.savedRuns.has(run) ? `<button class="row" data-act="saveProc" data-run="${run}" data-save='${esc(JSON.stringify(r.save))}'><span class="grow"><b>Uložit jako nástroj</b></span></button>` : ''}
  </div><h3>Kroky</h3><div class="list">${r.steps.map((s, i) => `<button class="row" data-act="step" data-run="${run}" data-i="${i}"><span class="pill ${s.state === 'hotovo' ? 'ok' : s.state === 'chyba' ? 'bad' : ''}">${esc(s.state)}</span><span class="grow"><b>${esc(s.title)}</b><small>${s.ms} ms${s.error ? ' · ' + esc(s.error) : ''}</small></span><span class="end">›</span></button>`).join('')}</div>`);
}

function passSheet(input) {
  S.tools = S.tools || call('tools') || []; S.passInput = input;
  sheet(`<h2>Předat nástroji</h2><div class="list">${S.tools.map(t => `<button class="row" data-act="passTo" data-id="${esc(t.id)}"><span class="grow"><b>${esc(t.title)}</b><small>${esc(t.description)}</small></span></button>`).join('')}</div>`, true);
}

function chipAct(v) {
  const [k, ...rest] = v.split(':'); const arg = rest.join(':');
  if (k === 'say') { $('#input').value = arg; send(); }
  else if (k === 'tool') useTool(arg);
  else if (k === 'proc') { if (busyGuard()) return; const f = S.files.pop(); busyCall('runProcedure', chatId(), arg, { file: f.id }); render(); }
  else if (k === 'panel') projectPanel();
  else if (k === 'saveLast' && S.lastCard && S.lastCard.save) act('saveProc', { save: JSON.stringify(S.lastCard.save), run: S.lastCard.run });
}

document.addEventListener('focusin', e => { if (e.target.id === 'input') document.body.classList.add('focus'); });
document.addEventListener('focusout', e => { if (e.target.id === 'input') document.body.classList.remove('focus'); });
document.addEventListener('click', e => { const t = e.target.closest('[data-act]'); if (!t) return; act(t.dataset.act, t.dataset); });
document.addEventListener('change', e => {
  const t = e.target;
  if (t.dataset.set) {
    // povolení automatického čerpání jen s nenulovým stropem (jinak by se nečerpalo nic a přepínač by lhal)
    if (t.dataset.set.startsWith('allow.') && t.checked) {
      const own = document.querySelector(`[data-budget="${t.dataset.set.slice(6)}"]`);
      const cap = own || (t.dataset.shared ? document.querySelector('[data-budgetall]') : null);
      if (cap && !(+cap.value > 0)) { toast('Nejdřív zadej strop v Kč – bez něj se automaticky nečerpá nic.', 5000); t.checked = false; cap.focus(); return; }
      if (own) call('setSetting', 'budget.' + own.dataset.budget, own.value);
    }
    call('setSetting', t.dataset.set, String(t.checked));
    if (t.dataset.set === 'ownOnly' && t.checked && !(call('settings') || {}).ownUrl) toast('Režim je zapnutý, ale vlastní server zatím není nastavený – do té doby model neodpoví (nástroje fungují).', 6000);
    S.home = null; renderTop();
  }
  if (t.dataset.budget) call('setSetting', 'budget.' + t.dataset.budget, t.value);
  if (t.dataset.budgetall !== undefined) call('setSetting', 'budgetKc', t.value);
  if (t.dataset.usdkc !== undefined) call('setSetting', 'usdKc', t.value);
});
document.addEventListener('input', e => {
  if (e.target.id === 'input') { autosize(); renderChips(); document.body.classList.add('typing'); clearTimeout(S.typingT); S.typingT = setTimeout(() => document.body.classList.remove('typing'), 900); }
  if (e.target.id === 'toolq') { const q = e.target.value.toLowerCase(); const ts = (S.tools || []).filter(t => (t.title + ' ' + t.description).toLowerCase().includes(q)); $('#toollist').innerHTML = toolList(ts, [...new Set(ts.map(t => t.group))]); }
});
document.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !isTouch && e.target.id === 'input') { e.preventDefault(); send(); }
  if (e.key === 'Escape' && $('#layer').innerHTML) closeSheet();
});

S.busy = J(B.isBusy(), false) === true; // po obnovení stránky převzít stav běžící úlohy
render();
B.ready(); // až teď smí přijít odložené události (sdílení při studeném startu, otevřené dotazy)
