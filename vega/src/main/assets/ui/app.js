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
    esc(b).replace(/^#{2,3} (.*)$/gm, '<h3>$1</h3>').replace(/^# (.*)$/gm, '<h3>$1</h3>')
      .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/(^|\s)_(.+?)_(?=\s|$)/g, '$1<i>$2</i>').replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/^[-•] (.*)$/gm, '• $1').replace(/\[(\d+)\]/g, '<sup>[$1]</sup>').replace(/\n/g, '<br>')).join('');
}
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
  return call('messages', chatId()) || [];
}

let msgsCount = 0;
function renderStream() {
  const list = msgs(); msgsCount = list.length;
  const el = $('#stream');
  document.body.classList.toggle('chatting', !!list.length || S.busy);
  if (!list.length && !S.busy) { el.innerHTML = emptyState(); S.lastCard = null; renderChips(); return; }
  el.innerHTML = list.map(m => m.role === 'user'
    ? `<div class="me">${esc(m.text)}${m.files && m.files.length ? `<span class="att">📎 ${m.files.map(esc).join(', ')}</span>` : ''}</div>`
    : `<div class="vega">${m.text ? `<div class="txt">${md(m.text)}</div>` : ''}${card(m.card, m === list[list.length - 1])}</div>`).join('') +
    (S.busy ? `<div class="vega">${S.stream ? `<div class="txt" id="live">${md(S.stream)}</div>` : ''}<div class="thinking"><span class="dots"><i></i><i></i><i></i></span><span id="phase">${S.stream ? 'Píšu…' : 'Přemýšlím…'}</span></div></div>` : '');
  el.scrollTop = el.scrollHeight;
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
  return `<div class="hello"><button class="star" data-act="star" aria-label="VEGA – jiný pozdrav">${STAR}</button><h1 id="greet">${esc(GREETS[greetI](h.userName))}</h1>
    ${noModel ? `<div class="quiet">Rozhovor potřebuje model – <button class="linkbtn" data-act="settings">Gemini zdarma nebo vlastní server</button>. Nástroje fungují i bez něj.</div>` : ''}
    ${btns.length ? `<div class="btns">${btns.join('')}</div>` : ''}</div>`;
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
const CHK = { PODPORUJE: ['ok', 'podporuje'], ODPORUJE: ['bad', 'odporuje'], NEDOSTACUJE: ['', 'nedostačuje'], NEVYHODNOCENO: ['', 'nevyhodnoceno'] };
function checksHtml(c) {
  const ch = c.checks || []; if (!ch.length) return '';
  const allNev = ch.every(x => x.label === 'NEVYHODNOCENO');
  if (allNev) return `<div class="note">Opora citací: nevyhodnoceno – ${esc(ch[0].reason)}</div>`;
  return `<div class="res"><div class="res-h">Opora ve zdroji</div><div class="res-body">${ch.map((x, i) => `<button class="chk" style="width:100%;text-align:left" data-act="chkOpen" data-i="${i}" data-c='${esc(JSON.stringify({ x, item: (c.items || []).find(s => s.n === x.n) || null }))}'><span class="pill ${CHK[x.label][0]}">[${x.n}] ${CHK[x.label][1]}</span><span><b>${esc(x.claim)}</b><small>${esc(x.kind)}${x.reason ? ' · ' + esc(x.reason) : ''}</small></span></button>`).join('')}</div></div>`;
}
const diffHtml = d => `<pre class="diff">${String(d || '').split('\n').slice(0, 400).map(l => `<span class="${l.startsWith('@@') ? 'h' : l[0] === '+' ? 'a' : l[0] === '-' ? 'd' : ''}">${esc(l)}</span>`).join('\n')}</pre>`;

function card(c, isLast) {
  if (!c) return '';
  switch (c.type) {
    case 'run': {
      const v = Object.assign({ run: c.run }, c.value);
      const primary = c.failed ? `<button class="btn signal" data-act="resume" data-run="${c.run}">Pokračovat</button>`
        : c.save && !S.savedRuns.has(c.run) ? `<button class="btn primary" data-act="saveProc" data-run="${c.run}" data-save='${esc(JSON.stringify(c.save))}'>Uložit jako nástroj</button>`
        : v.kind === 'TABLE' || v.kind === 'BYTES' ? `<button class="btn primary" data-act="export" data-run="${c.run}">Uložit soubor</button>` : '';
      return `<div class="res"><div class="res-h">${esc(c.name)} ${c.failed ? '<span class="pill bad">chyba</span>' : '<span class="pill ok">hotovo</span>'}</div>
        ${c.failed ? `<div class="res-sub" style="padding-top:6px">${esc(c.error)}</div>` : `<div class="res-big">${esc(headline(v))}</div>`}
        <div class="res-body">${valueHtml(v)}</div>
        <div class="res-a">${primary}<span class="grow"></span><button class="more" data-act="runMenu" data-run="${c.run}" aria-label="Další možnosti">⋯</button></div></div>`;
    }
    case 'diff': {
      const add = c.files.reduce((a, f) => a + f.added, 0), rem = c.files.reduce((a, f) => a + f.removed, 0), v = c.verification;
      const vp = v ? (v.kind === 'run' ? `<span class="pill ${v.exitCode === 0 ? 'ok' : 'bad'}">${v.exitCode === 0 ? 'testy prošly' : 'testy neprošly'}</span>` : `<span class="pill">${esc(v.label)}</span>`) : '';
      const task = (c.task || []), mine = (c.changes || []).filter(x => task.includes(x.id));
      // výběr změn: jen u poslední karty a když úloha udělala víc změn
      const pick = isLast && mine.length > 1 ? `<div class="res-body">${mine.slice().reverse().map(x => `<label class="chk"><input type="checkbox" data-keep="${x.id}" checked><span><b>#${x.id} ${esc(x.path)}</b><small>${esc(x.note || x.op)}</small></span></label>`).join('')}</div>` : '';
      const rounds = (c.rounds || []).length > 1 ? ` · ${c.rounds.length} kola (${c.rounds.map(r => r.exitCode === 0 ? '✓' : '✗').join(' ')})` : '';
      return `<div class="res"><div class="res-h">${esc(c.title || 'Změny v projektu')} ${vp}</div>
        <div class="res-big">+${add} −${rem}</div><div class="res-sub">${plural(c.files.length, 'soubor', 'soubory', 'souborů')}: ${c.files.slice(0, 3).map(f => esc(f.path)).join(', ')}${c.revision ? ` · revize ${esc(c.revision)}` : ''}${rounds}</div>${pick}
        <div class="res-a">${c.resumeTask ? `<button class="btn primary" data-act="resumeTask" data-p="${esc(c.resumeTask.project)}">Dokončit úlohu</button>` : pick ? `<button class="btn primary" data-act="keep" data-p="${esc(c.project)}" data-task="${task.join(',')}">Přijmout vybrané</button>` : !c.title && c.changes && c.changes[0] ? `<button class="btn" data-act="undo" data-p="${esc(c.project)}" data-c="${c.changes[0].id}">Vrátit</button>` : ''}<span class="grow"></span>
        <button class="btn" data-act="cardDiff" data-files='${esc(JSON.stringify(c.files))}'>Rozdíl</button>${v ? `<button class="more" data-act="showVer" data-ver='${esc(JSON.stringify(v))}' aria-label="Výstup testu">⋯</button>` : ''}</div></div>`;
    }
    case 'compare': {
      const ch = c.prevTotal != null && c.prevTotal !== c.total;
      return `<div class="res"><div class="res-h">Porovnání výpisů <span class="pill">revize ${c.rev}</span></div>
        <div class="res-big">${fmt(c.total)} Kč</div>${ch ? `<div class="res-sub">předtím ${fmt(c.prevTotal)} Kč → rozdíl ${c.total - c.prevTotal > 0 ? '+' : ''}${fmt(c.total - c.prevTotal)} Kč</div>` : ''}
        <div class="res-body"><div class="tbl"><table><tr><th>#</th><th>Soubor</th><th>Saldo</th><th>Stav</th></tr>${c.files.map(f => `<tr><td>${f.i}</td><td>${esc(f.name)}${f.changed ? `<br><small class="note">místo ${esc(f.prevName)} (${fmt(f.prevSaldo)})</small>` : ''}</td><td>${fmt(f.saldo)}</td><td><span class="pill ${f.status === 'spočítáno' ? 'sig' : ''}">${esc(f.status)}</span></td></tr>`).join('')}</table></div></div>
        <div class="res-a"><span class="grow"></span><button class="btn" data-act="taskLog" data-log='${esc(JSON.stringify(c.log))}'>Záznam běhu</button></div></div>`;
    }
    case 'file': return `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="openOut" data-f="${esc(c.name)}">Otevřít ${esc(c.name)}</button><button class="btn" data-act="share" data-f="${esc(c.name)}">Sdílet</button></div>`;
    case 'exportRetry': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="exportRetry" data-run="${esc(c.run)}">Zkusit znovu</button></div>` : '';
    case 'econ': return `<div class="res"><div class="res-h">Kalkulace</div><div class="res-big">${c.profit == null ? '–' : (c.profit < 0 ? 'ztráta ' + fmt(-c.profit) : 'zisk ' + fmt(c.profit)) + ' Kč'}</div><div class="res-sub">cena ${fmt(c.price)} · variabilní ${fmt(c.variable)} · fixní ${fmt(c.fixed)}${c.breakEven != null ? ' · bod zvratu ' + c.breakEven + ' ks' : ''}</div></div>`;
    case 'evidence': return `<div class="res"><div class="res-h">Síla doložení</div><div class="res-body">${c.parts.map(p => `<div class="chk"><span class="pill ${/^potvrzeno|^dvě/.test(p.status) ? 'ok' : 'bad'}">${esc(p.lines)} ${p.lines >= 5 || p.lines === 0 ? 'linií' : 'linie'}</span><span><b>${esc(p.label)}</b><small>${esc(p.status)} · ${p.support.map(esc).join(', ')}</small></span></div>`).join('')}</div></div>`;
    case 'revision': return `<span class="pill sig">revize ${c.rev}</span>`;
    case 'resume': return isLast ? `<div class="res"><div class="res-h">Rozpracováno <span class="pill">uloženo</span></div><div class="res-sub" style="padding-top:6px">„${esc(c.text)}“</div><div class="res-a"><button class="btn primary" data-act="retry" data-req="${esc(c.req || '')}">Pokračovat</button></div></div>` : '';
    case 'project': return c.resumeTask ? `<div class="res-a" style="padding-left:0"><button class="btn primary" data-act="resumeTask" data-p="${esc(c.resumeTask.project)}">Dokončit přerušenou úlohu</button></div>` : '';
    case 'memScope': return isLast ? `<div class="res-a" style="padding-left:0"><button class="btn" data-act="memGlobal" data-t="${esc(c.text)}">Uložit pro všechno</button><button class="btn ghost" data-act="libTab" data-tab="projects">Otevřít projekt</button></div>` : '';
    case 'procedure': return `<div class="res-a" style="padding-left:0"><button class="btnchip" data-act="runBtn" data-id="${esc(c.id)}" data-kind="${esc(c.inputKind || 'BYTES')}"><i></i>${esc(c.name)} · v${c.version}</button></div>`;
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
    case 'sources': return checksHtml(c) + `<div class="inline" style="margin:4px 0;flex-wrap:wrap">${(c.candidates || []).length > 1 ? `<span class="note">Může znamenat: ${c.candidates.map(esc).join(' · ')}</span>` : ''}<button class="pill" data-act="sources" data-src='${esc(JSON.stringify(c.items))}'>${c.items.length} zdrojů ›</button></div>`;
    case 'coverage': { const ok = c.items.filter(i => i.ok).length; return `<button class="pill ${ok === c.items.length ? 'ok' : 'bad'}" data-act="coverage" data-cov='${esc(JSON.stringify(c))}'>Pokryto ${ok}/${c.items.length} požadavků ›</button>`; }
    case 'demandForm': return `<div class="res"><div class="res-h">Předžalobní výzva</div><div class="res-sub" style="padding-top:6px">Faktura a úhrady – předvyplněno syntetickým případem.</div><div class="res-a"><button class="btn primary" data-act="demandForm" data-s='${esc(JSON.stringify(c.sample))}'>Vyplnit a vyrobit</button></div></div>`;
    case 'demand': return `<div class="res"><div class="res-h">Předžalobní výzva <span class="pill ${c.verified ? 'ok' : 'bad'}">${c.verified ? 'sazba ověřena' : 'sazba neověřena'}</span></div>
      <div class="res-big">${fmt(c.total)} Kč</div><div class="res-sub">PDF zpětně zkontrolováno: ${c.checks.filter(k => k.ok).length}/${c.checks.length}</div>
      ${c.warnings.map(w => `<div class="res-body warn">⚠ ${esc(w)}</div>`).join('')}
      <div class="res-a"><button class="btn primary" data-act="openOut" data-f="${esc(c.files[1] || c.files[0])}">Otevřít PDF</button><span class="grow"></span><button class="more" data-act="demandMenu" data-d='${esc(JSON.stringify(c))}' aria-label="Další">⋯</button></div></div>`;
    case 'nfcWait': return isLast && S.busy !== false ? `<div class="thinking"><span class="dots"><i></i><i></i><i></i></span><span>Přilož štítek k zadní straně telefonu…</span></div>` : '';
    case 'memRevert': return `<button class="pill sig" data-act="memRevert" data-id="${esc(c.id)}">Vrátit předchozí verzi</button>`;
    case 'memory': return `<button class="pill" data-act="library" data-tab="memory">Otevřít paměť ›</button>`;
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
function renderTop() {
  const h = S.home || call('home') || {};
  $('#spaceName').textContent = S.space.kind === 'temp' ? 'Dočasný chat' : S.space.kind === 'project' ? S.space.name : 'VEGA';
  $('#modelName').textContent = h.model ? '· ' + h.model : '';
  $('#avatar').textContent = (h.userName || 'V').trim().charAt(0).toUpperCase() || 'V';
}
function render() { S.home = null; renderStream(); renderTop(); }

/* ------------------------------------------------------------------ listy */
function sheet(html, full) {
  // otevřený dotaz (souhlas / schválení ceny) nesmí tiše zmizet pod jiným listem – úloha by čekala 5 minut
  if (S.req && !html.includes('data-act="answer"')) { toast('Nejdřív odpověz na otevřený dotaz.'); return; }
  closeSheet(true);
  $('#layer').innerHTML = `<div class="scrim" data-act="close"></div><div class="sheet ${full ? 'full' : ''}" role="dialog" aria-modal="true">${full ? `<div class="inline"><span class="grow"></span><button class="iconbtn" data-act="close" aria-label="Zavřít">✕</button></div>` : '<div class="grab"></div>'}${html}</div>`;
}
/** Zavření listu s otevřeným dotazem (souhlas / schválení) = odpověď „ne“ – úloha nečeká 5 minut. */
function closeSheet(silent) {
  if (S.req && !silent) { B.answer(S.req, JSON.stringify({ ok: false })); S.req = null; }
  $('#layer').innerHTML = '';
}

function plusSheet() {
  sheet(`<h2>Přidat</h2><div class="list">
    <button class="row" data-act="pick"><b>📎</b><span class="grow"><b>Soubor</b><small>CSV, log, JSON, PDF, fotka dokumentu…</small></span></button>
    <button class="row" data-act="tools"><b>⌘</b><span class="grow"><b>Nástroje</b><small>diff, kontrolní součty, inspektory, čtení URL…</small></span></button>
    <button class="row" data-act="nfc"><b>◎</b><span class="grow"><b>NFC štítek</b><small>přečte text nebo adresu</small></span></button>
    <button class="row" data-act="folder"><b>▣</b><span class="grow"><b>Projekt ze složky</b><small>pracuji v kopii, originál nechám být</small></span></button>
  </div>`);
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
  closeSheet(true);
  S.busy = true; S.stream = '';
  call('runTool', chatId(), t.id, input, params);
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
    <div class="dscroll">${cs.map(c => `<button class="drow ${S.space.kind === 'chat' && S.space.id === c.id ? 'on' : ''}" data-act="openChat" data-id="${esc(c.id)}">${esc(c.title)}</button>`).join('') || '<div class="dnote">Zatím žádné</div>'}</div>
    <div class="dfoot">
      <button class="drow" data-act="libTab" data-tab="projects">Projekty</button>
      <button class="drow" data-act="libTab" data-tab="procs">Moje nástroje</button>
      <button class="drow" data-act="libTab" data-tab="memory">Paměť</button>
      <button class="drow" data-act="settings">Nastavení</button>
    </div></nav>`;
}

function library(tab) {
  S.libTab = tab || S.libTab;
  const T = { projects: 'Projekty', procs: 'Moje nástroje', memory: 'Paměť' };
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
    <div class="inline" style="margin-top:8px"><input class="field" type="password" id="key-${k}" placeholder="${s.keys[k] ? 'Nový klíč přepíše uložený' : 'Vlož API klíč'}" autocomplete="off"><button class="btn primary" data-act="saveKey" data-k="${k}">Uložit</button></div>
    <div class="status" id="kc-${k}">${s.models[k] ? 'Odpovídá: ' + esc(s.models[k]) : ''}${s.keys[k] ? ` <button class="btn ghost" data-act="checkKey" data-k="${k}" style="min-height:0;padding:0 6px">Ověřit</button>` : ''}</div></div>`;
  sheet(`<h2>Nastavení</h2>
    <h3>Ty</h3>
    <div class="inline"><input class="field" id="uname" value="${esc(s.userName || '')}" placeholder="Jak tě mám oslovovat (např. Lukáši)"><button class="btn primary" data-act="saveName">Uložit</button></div>
    <h3>Vlastní model</h3>
    <div class="card"><div class="note">Server s otevřeným modelem (llama.cpp server, Ollama, vLLM…) s OpenAI API. Doma v síti stačí <b>http://192.168.x.x:8080/v1</b>, přes internet jen https.</div>
      <label class="lbl">Adresa serveru</label><input class="field" id="ownUrl" value="${esc(s.ownUrl || '')}" placeholder="http://192.168.1.20:8080/v1" inputmode="url" autocomplete="off">
      <label class="lbl">Model (prázdné = první, který server nabízí)</label><input class="field" id="ownModel" value="${esc(s.ownModel || '')}" placeholder="např. qwen2.5-7b-instruct" autocomplete="off">
      <label class="lbl">Klíč serveru (nepovinné)</label><input class="field" type="password" id="ownKey" placeholder="${s.ownKey ? 'uloženo – nový přepíše' : 'jen když ho server vyžaduje'}" autocomplete="off">
      <button class="btn primary wide" data-act="saveOwn" style="margin-top:10px">Uložit a ověřit</button><div class="status" id="kc-own"></div></div>
    <label class="switch"><span><b>Pouze vlastní modely</b><small>celý požadavek jen na tvém serveru – žádné komerční API ani při výpadku</small></span><input type="checkbox" data-set="ownOnly" ${s.ownOnly ? 'checked' : ''}></label>
    <p class="note">Požadavky na modely: vlastní ${(s.requests || {}).own || 0} · komerční ${(s.requests || {}).commercial || 0}</p>
    <h3>Ověřovač citací</h3>
    <div class="card"><div class="note">Neuronový model v telefonu (bez sítě) ukáže, jestli citovaná pasáž tvrzení podporuje, odporuje mu, nebo nestačí. Váhy nejsou v aplikaci – instalují se jako balíček (.zip z tools/verifier).</div>
      <div class="status" id="kc-verifier">${s.verifierReady ? '<span class="pill ok">načteno</span> ' : '<span class="pill">vypnuto</span> '}${esc(s.verifier || '')}</div>
      <div class="inline" style="margin-top:8px"><button class="btn primary" data-act="verifierInstall">Nainstalovat balíček</button>${s.verifierReady ? '<button class="btn ghost" data-act="verifierRemove">Odebrat</button>' : ''}</div></div>
    <h3>Model v cloudu</h3>
    ${keyRow('gemini', 'Gemini – zdarma', 'Klíč z <b>aistudio.google.com/apikey</b> v projektu bez fakturace. Model Gemini 4 Hydrogen, pokud ho tvůj klíč už má. V Česku se data z bezplatné kvóty nepoužívají k tréninku.')}
    <details ${s.keys.geminipro ? 'open' : ''}><summary>Mám předplatné Google AI Pro</summary>
      ${keyRow('geminipro', 'Gemini – kredit Pro', 'Pro samo klíč nedává, obsahuje měsíční kredit (~10 USD). Aktivuj ho v AI Studiu, v tom placeném projektu vytvoř klíč a vlož ho sem. Použije se, až dojde bezplatná kvóta.')}
      ${s.keys.geminipro ? `<label class="lbl">Strop kreditu za měsíc (Kč) · utraceno ${fmt(s.spent.geminipro)} Kč</label><input class="field" type="number" min="0" data-budget="geminipro" value="${s.budgets.geminipro || ''}" placeholder="např. 150">
        <label class="switch"><span><b>Čerpat kredit automaticky</b><small>bez ptaní, do stropu výše</small></span><input type="checkbox" data-set="allow.geminipro" ${s.allowed.geminipro ? 'checked' : ''}></label>
        <p class="note">Doporučuji strop pod kreditem. Počítám skutečné tokeny včetně přemýšlení a rezervuji odhad předem. Útratu mimo VEGA (AI Studio, jiné aplikace) nevidím – v Cloud Billing si nastav rozpočet i u Google. Bez povolení se zeptám u každého úkolu.</p>` : ''}
    </details>
    <h3>Soukromí a chování</h3>
    <label class="switch"><span><b>Veřejný web</b><small>rešerše a čtení odkazů – nezávislé na volbě modelu</small></span><input type="checkbox" data-set="webAllowed" ${s.webAllowed !== false ? 'checked' : ''}></label>
    <label class="switch"><span><b>Air-gap</b><small>žádná síť – fungují jen nástroje v telefonu</small></span><input type="checkbox" data-set="airgap" ${s.airgap ? 'checked' : ''}></label>
    <label class="switch"><span><b>Iniciativa</b><small>navrhnu další krok u projektu</small></span><input type="checkbox" data-set="initiative" ${s.initiative ? 'checked' : ''}></label>
    <details><summary>Pokročilé</summary>
      ${keyRow('groq', 'Groq – zdarma', 'Záloha, když Gemini narazí na limit.')}
      ${keyRow('cerebras', 'Cerebras – zdarma', 'Záloha.')}
      ${keyRow('openrouter', 'OpenRouter – zdarma modely', 'Záloha.')}
      ${keyRow('deepseek', 'DeepSeek – placený', 'Servery v Číně – citlivé podklady raději ne.')}
      ${keyRow('claude', 'Claude – placený', 'Anthropic API.')}
      <label class="lbl">Společný strop pro ostatní placené (Kč / měsíc)</label><input class="field" type="number" min="0" data-budgetall value="${s.budgetKc || 0}">
      ${['deepseek', 'claude'].filter(k => s.keys[k]).map(k => `<label class="switch"><span><b>${k === 'claude' ? 'Claude' : 'DeepSeek'} automaticky</b><small>v rámci společného stropu</small></span><input type="checkbox" data-set="allow.${k}" data-shared="1" ${s.allowed[k] ? 'checked' : ''}></label>`).join('')}
      <label class="lbl">Kurz USD → Kč</label><input class="field" type="number" step="0.1" min="10" max="60" data-usdkc value="${s.usdKc || 23.5}">
      <p class="note">Tento měsíc utraceno celkem ${fmt(s.spentKc)} Kč. Lokální index: ${s.indexed} souborů.</p>
    </details>
    <p class="note" style="margin-top:24px">VEGA 1.3 · samostatná aplikace, vlastní úložiště. Klíče jsou šifrované v trezoru telefonu a do rozhraní se nevracejí.</p>`, true);
}

/** Rychlá volba z model-pillu: kdo odpovídá a kam smí data. Stejné přepínače jako v Nastavení. */
function modelSheet() {
  const s = call('settings') || {}; const h = call('home') || {};
  sheet(`<h2>Kdo odpovídá</h2><p class="lede">Teď: <b>${esc(h.model || 'bez modelu')}</b></p>
    <label class="switch"><span><b>Pouze vlastní modely</b><small>${s.ownUrl ? esc(s.ownUrl) : 'server zatím není nastavený'}</small></span><input type="checkbox" data-set="ownOnly" ${s.ownOnly ? 'checked' : ''}></label>
    <label class="switch"><span><b>Veřejný web</b><small>rešerše, zdroje, odkazy</small></span><input type="checkbox" data-set="webAllowed" ${s.webAllowed !== false ? 'checked' : ''}></label>
    <label class="switch"><span><b>Bez sítě (air-gap)</b><small>jen nástroje v telefonu</small></span><input type="checkbox" data-set="airgap" ${s.airgap ? 'checked' : ''}></label>
    <div class="list" style="margin-top:12px"><button class="row" data-act="settings"><span class="grow"><b>Vlastní server a klíče…</b><small>adresa, model, Gemini zdarma, kredit Pro</small></span><span class="end">›</span></button>
    <button class="row" data-act="temp"><span class="grow"><b>Dočasný chat</b><small>nic se neukládá</small></span></button></div>`);
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
  const f = (k, l, t = 'text') => `<label class="lbl">${l}</label><input class="field" data-dm="${k}" type="${t}" value="${esc(s[k])}">`;
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
  if (S.space.kind === 'temp') S.tempMsgs.push({ role: 'user', text: t });
  S.busy = true; S.stream = '';
  if (call('send', chatId(), t, S.files.map(f => f.id)) === null) S.busy = false; // chyba mostu → neviset na „pracuji“
  S.files = []; inp.value = ''; autosize();
  render();
}
function autosize() { const i = $('#input'); i.style.height = 'auto'; i.style.height = Math.min(i.scrollHeight, innerHeight * .34) + 'px'; }

function setSpace(sp) {
  S.space = sp; S.tool = null; S.toolInput = null; S.stream = ''; S.lastCard = null;
  call('activate', sp.kind === 'project' ? sp.id : '');
  closeSheet(true); render();
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
const show = v => fmtv(v); const eq = (a, b) => JStr(a) === JStr(b);
const expect = a => Freeze({
  toBe: b => { if (!OIs(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toEqual: b => { if (!eq(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toBeCloseTo: (b, d = 2) => { if (MathAbs(a - b) > MathPow(10, -d) / 2) throw new Error('čekáno ≈' + b + ', je ' + a); },
  toBeTruthy: () => { if (!a) throw new Error('čekána pravda, je ' + show(a)); },
  toBeFalsy: () => { if (a) throw new Error('čekána nepravda, je ' + show(a)); },
  toContain: b => { if (!(a && a.includes && a.includes(b))) throw new Error(show(a) + ' neobsahuje ' + show(b)); },
  toThrow: () => { try { a(); } catch (e) { return; } throw new Error('čekána výjimka'); },
});
const APush = Function.prototype.call.bind(Array.prototype.push);
const test = (n, f) => { APush(tests, [n, f]); };
lock('test', test); lock('it', test); lock('describe', (n, f) => f()); lock('expect', expect);
const specs = Keys(FILES).filter(f => /\\.test\\.js$/.test(f) || /^test\\/.*\\.js$/.test(f)).sort();
// kód projektu nesmí přepsat iteraci polí a tím „vyprázdnit“ seznam testů (podvržené „prošlo“)
try { Freeze(Array.prototype); Freeze(Object.getPrototypeOf([][Symbol.iterator]())); } catch (e) {}
(async () => {
  let pass = 0, fail = 0;
  for (let si = 0; si < specs.length; si++) {
    const s = specs[si]; tests = [];
    try { load(s); } catch (e) { fail++; APush(out, '✗ ' + s + ': ' + e.message); continue; }
    const mine = tests; const cnt = mine.length;
    for (let ti = 0; ti < cnt; ti++) { const n = mine[ti][0], f = mine[ti][1]; try { await f(); pass++; APush(out, '✓ ' + s + ' › ' + n); } catch (e) { fail++; APush(out, '✗ ' + s + ' › ' + n + ': ' + e.message); } }
  }
  APush(out, pass + ' prošlo, ' + fail + ' selhalo');
  post({ nonce: NONCE, exitCode: fail || pass === 0 ? 1 : 0, output: (specs.length ? '' : 'Žádné testy (*.test.js).\\n') + out.join('\\n'), command: specs.join(' ') });
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

/* ------------------------------------------------------------------ události z Kotlinu */
window.vega = {
  on(type, d) {
    switch (type) {
      case 'message':
        S.stream = '';
        if (d.chat === 'temp') S.tempMsgs.push(d.msg);
        if (d.chat === chatId()) render(); else if (d.chat !== 'temp') toast('VEGA: ' + String(d.msg.text || 'výsledek je hotový').slice(0, 80));
        break;
      case 'delta': if (d.chat === chatId()) { S.stream = d.text; const l = $('#live'); if (l) { l.innerHTML = md(d.text); const ph = $('#phase'); if (ph) ph.textContent = 'Píšu…'; $('#stream').scrollTop = 1e9; } else render(); } break;
      case 'phase': { const ph = $('#phase'); if (ph) ph.textContent = d.text; break; }
      case 'busy': S.busy = true; render(); break;
      case 'idle': S.busy = false; S.stream = ''; render(); break;
      case 'done': S.busy = false; if (d.cancelled) toast('Zastaveno. Hotové kroky zůstaly.'); render(); break;
      case 'error': S.busy = false; S.stream = ''; render(); toast(d.text, 7000); break;
      case 'files': {
        const ok = (d.files || []).filter(f => !f.error); (d.files || []).filter(f => f.error).forEach(f => toast(f.error));
        if (S.awaitTry) { S.awaitTry = false; if (ok.length) { S.busy = true; call('buttonTry', chatId(), ok[0].id); render(); } return; }
        if (S.awaitProc) { // soubor vybraný pro uložený nástroj patří jen jemu
          const id = S.awaitProc; S.awaitProc = null;
          if (ok.length) { S.busy = true; call('runProcedure', chatId(), id, { file: ok[0].id }); render(); if (ok.length > 1) toast('Nástroj jsem spustil na prvním souboru.'); }
          return;
        }
        S.files = S.files.concat(ok); renderChips(); break;
      }
      case 'shared': { const i = $('#input'); i.value = d.text; autosize(); toast('Sdílený text je v poli – zkontroluj a odešli.'); break; }
      case 'voice': { const g = $('#go'); if (g) g.classList.remove('listen'); if (d.ok && d.text) { const i = $('#input'); i.value = (i.value ? i.value + ' ' : '') + d.text; autosize(); renderChips(); i.focus(); } else if (d.error) toast(d.error); break; }
      case 'project': if (d.silent) { S.home = null; renderTop(); break; } if (d.id) { const p = call('project', d.id); setSpace({ kind: 'project', id: d.id, name: p ? p.name : 'Projekt' }); if (d.imported !== undefined) toast(`Importováno ${d.imported} souborů (kopie).`); } break;
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
          <div class="card"><b>${esc(d.name)}</b> jen pro tento úkol<div class="res-big" style="padding:6px 0 0">nejvýš ${fmt(d.capKc ?? d.estimateKc)} Kč</div><div class="note">${d.calls > 1 ? `${d.calls} volání · ` : ''}${d.chars.toLocaleString('cs-CZ')} znaků · nad tuhle částku úkol nepůjde</div></div>
          <p class="note">${esc(d.hint)}</p>
          <div class="inline" style="margin-top:12px"><button class="btn grow" data-act="answer" data-ok="0">Ne</button><button class="btn signal grow" data-act="answer" data-ok="1">Použít</button></div>`);
        S.req = d.req; break;
      case 'jstest': runJsTests(d.files, d.timeoutMs || 10000).then(r => B.answer(d.req, JSON.stringify(r))); break;
      case 'verified': toast(d.kind === 'none' ? 'Testy nedoběhly – výsledek neplatí, zkus znovu.' : d.exitCode === 0 ? 'Testy prošly.' : 'Testy neprošly – výstup v panelu projektu.', 5000); if (S.space.kind === 'project' && panelOpen()) projectPanel(); break;
      case 'verifier': toast(d.message, 7000); if ($('#kc-verifier')) settingsSheet(); break;
      case 'keycheck': { const el = $('#kc-' + d.provider); if (el) el.innerHTML = `<span class="pill ${d.ok ? 'ok' : 'bad'}">${d.ok ? 'funguje' : 'nefunguje'}</span> ${esc(d.message)}`; else toast(d.message, 6000); break; }
      case 'nfc.start': B.nfc(chatId()); break;
      case 'nfc.listening': { const ph = $('#phase'); if (ph) ph.textContent = 'Přilož štítek…'; break; }
      case 'nfc.off': sheet(`<h2>NFC je vypnuté</h2><p class="lede">Zapni ho v systému a zkus to znovu.</p><button class="btn primary wide" data-act="nfcSettings">Otevřít nastavení NFC</button>`); break;
    }
  },
  /** Systémové Zpět: nejdřív zavřít list (= „ne“ u dotazu), pak režim nástroje; jinak ať aplikace jde do pozadí. */
  back() {
    if ($('#layer').innerHTML) { closeSheet(); return true; }
    if (S.tool) { S.tool = null; S.toolInput = null; renderChips(); return true; }
    if (S.space.kind !== 'chat') { setSpace({ kind: 'chat', id: null, name: 'Konverzace' }); return true; }
    return false;
  },
};

/* ------------------------------------------------------------------ klepnutí */
function act(a, d) {
  switch (a) {
    case 'send': send(); break;
    case 'voice': if (S.space.kind === 'temp' || !busyGuard()) { const g = $('#go'); if (g) g.classList.add('listen'); B.voice(); } break;
    case 'star': { const st = $('.star'); const h = $('#greet'); if (st) { st.classList.toggle('spin'); } if (h) { h.classList.add('fade'); setTimeout(() => { greetI = (greetI + 1) % GREETS.length; h.textContent = GREETS[greetI]((S.home || {}).userName); h.classList.remove('fade'); }, 300); } break; }
    case 'say': $('#input').value = d.v; send(); break;
    case 'retry': if (busyGuard()) return; S.busy = true; if (call('retry', chatId(), d.req || '') === null) S.busy = false; render(); break;
    case 'resumeTask': if (busyGuard()) return; S.busy = true; call('resumeTask', chatId(), d.p); render(); break;
    case 'keep': { if (busyGuard()) return; const keep = [...document.querySelectorAll('[data-keep]')].filter(x => x.checked).map(x => x.dataset.keep);
      if (!keep.length) { toast('Vyber aspoň jednu změnu – nebo použij Vrátit.'); return; } S.busy = true; call('keepChanges', chatId(), d.p, d.task, keep.join(',')); render(); break; }
    case 'cardDiff': { const fs = JSON.parse(d.files); sheet(`<h2>Rozdíl</h2>${fs.map(f => `<h3>${esc(f.path)} · +${f.added} −${f.removed}</h3>${diffHtml(f.diff)}`).join('')}`, true); break; }
    case 'btnTry': if (busyGuard()) return; S.awaitTry = true; toast('Vyber JINÝ soubor stejného typu – ověřím na něm stejné kroky.'); B.pickFiles(); break;
    case 'btnSave': { const n = ($('#btnName') || {}).value || ''; const r = call('buttonSave', chatId(), n); if (r) { S.procs = null; toast(`Připnuto: ${r.name} (v${r.version})`); render(); } break; }
    case 'runBtn': if (busyGuard()) return; if (d.kind === 'TEXT') { const t = $('#input').value.trim(); if (!t) { toast('Napiš text, na který tlačítko použiju.'); return; } S.busy = true; call('runProcedure', chatId(), d.id, { text: t }); $('#input').value = ''; render(); }
      else { S.awaitProc = d.id; toast('Vyber soubor – tlačítko ho zpracuje.'); B.pickFiles(); } break;
    case 'memGlobal': { const r = call('rememberGlobal', d.t); if (r) toast('Uloženo jako globální preference.'); break; }
    case 'model': modelSheet(); break;
    case 'chkOpen': { const o = JSON.parse(d.c); const x = o.x, it = o.item;
      const t = it ? String(it.text || '') : ''; const ws = Math.max(0, x.ws || 0), we = Math.min(t.length, x.we || t.length);
      sheet(`<h2>[${x.n}] ${esc(CHK[x.label][1])}</h2><p class="lede">${esc(x.claim)}</p>${x.p ? `<p class="note">podpora ${fmt(x.p[0] * 100)} % · nedostačuje ${fmt(x.p[1] * 100)} % · odporuje ${fmt(x.p[2] * 100)} % (výsledek o této pasáži, ne o pravdivosti světa)</p>` : `<p class="note">${esc(x.reason)}</p>`}
        ${it ? `<div class="card"><b>${esc(it.title)}</b><div class="note">${link(it.url)} · staženo ${esc(it.fetched || '')}</div><p style="white-space:pre-wrap">${esc(t.slice(0, ws))}<mark>${esc(t.slice(ws, we))}</mark>${esc(t.slice(we))}</p></div>` : ''}`, true); break; }
    case 'verifierInstall': B.verifierInstall(); toast('Vyber balíček ověřovače (.zip).'); break;
    case 'verifierRemove': call('verifierRemove'); toast('Ověřovač odebrán – citace budou „nevyhodnoceno“.'); settingsSheet(); break;
    case 'exportRetry': if (busyGuard()) return; S.busy = true; call('exportRetry', chatId(), d.run); render(); break;
    case 'taskLog': { const l = JSON.parse(d.log); sheet(`<h2>Záznam běhu</h2><div class="list">${l.map(x => `<div class="row"><span class="pill">r${x.rev}</span><span class="grow"><b>${esc(x.step)}${x.input !== '*' ? ' · ' + esc(x.input) : ''}</b><small>${esc(x.action)}</small></span></div>`).join('')}</div>`, true); break; }
    case 'profile': settingsSheet(); break;
    case 'saveOwn': saveOwn(); break;
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
      S.busy = true; call('send', chatId(), 'Načti NFC štítek', []); render(); break;
    case 'nfcSettings': B.nfcSettings(); closeSheet(true); break;
    case 'demand': if (busyGuard()) return; { const smp = call('demandSample'); if (smp) demandSheet(smp); } break;
    case 'demandForm': demandSheet(JSON.parse(d.s)); break;
    case 'demandGo': {
      const o = {}; let missing = null;
      if (busyGuard()) return;
      document.querySelectorAll('[data-dm]').forEach(i => {
        if (i.type === 'number' && i.validity.badInput) missing = missing || i.previousElementSibling.textContent + ' (číslo s desetinnou tečkou nebo čárkou)';
        o[i.dataset.dm] = i.type === 'number' ? (i.value === '' ? 0 : +i.value) : i.value;
        if (['issued', 'due', 'asOf'].includes(i.dataset.dm) && !i.value) missing = missing || i.previousElementSibling.textContent;
      });
      if (missing) { toast('Vyplň: ' + missing); return; }
      if (o.payAmount > 0 && !o.payDate) { toast('Doplň datum úhrady.'); return; }
      o.b2b = true; closeSheet(true); S.busy = true; call('demand', chatId(), o); render(); break;
    }
    case 'demandMenu': { const c = JSON.parse(d.d); sheet(`<h2>Výzva</h2><div class="list">${c.files.map(f => `<button class="row" data-act="openOut" data-f="${esc(f)}"><span class="grow"><b>Otevřít ${esc(f.split('.').pop().toUpperCase())}</b></span></button><button class="row" data-act="share" data-f="${esc(f)}"><span class="grow"><b>Sdílet ${esc(f.split('.').pop().toUpperCase())}</b></span></button>`).join('')}</div>
        <h3>Výpočet a původ částek</h3><div class="tbl"><table><tr><th>Položka</th><th>Kč</th><th>Doklad</th></tr>${c.lines.map(l => `<tr><td>${esc(l.label)}</td><td>${fmt(l.amount)}</td><td>${esc(l.origin)}</td></tr>`).join('')}</table></div>
        <h3>Právní opora (${esc(c.rateSource)})</h3>${c.legal.map(l => `<p class="note">• ${esc(l)}</p>`).join('')}
        <h3>Kontrola PDF</h3><p>${c.checks.map(k => `<span class="pill ${k.ok ? 'ok' : 'bad'}">${esc(k.what)}</span>`).join(' ')}</p>`, true); break; }
    case 'openOut': B.openOut(d.f); break;
    case 'share': B.share(d.f); break;
    case 'library': if (d.tab) library(d.tab); else drawer(); break;
    case 'libTab': library(d.tab); break;
    case 'settings': settingsSheet(); break;
    case 'new': setSpace({ kind: 'chat', id: call('newChat'), name: 'Konverzace' }); break;
    case 'temp': S.tempMsgs = []; call('clearTemp'); setSpace({ kind: 'temp' }); break;
    case 'openChat': setSpace({ kind: 'chat', id: d.id, name: 'Konverzace' }); break;
    case 'newProject': { const name = ($('#pname') || {}).value || 'Nový projekt'; const id = call('newProject', name); if (id) setSpace({ kind: 'project', id, name }); break; }
    case 'openProject': { const p = call('project', d.id); if (p) setSpace({ kind: 'project', id: d.id, name: p.name }); break; }
    case 'close': closeSheet(); break;
    case 'answer': { const ok = d.ok === '1'; const r = J(B.answer(S.req, JSON.stringify({ ok }))); S.req = null; closeSheet(true); if (r === false) toast('Žádost už vypršela – pošli zprávu znovu.'); break; }
    case 'chip': chipAct(d.v); break;
    case 'dropFile': S.files = S.files.filter(f => f.id !== d.id); renderChips(); break;
    case 'export': { const r = call('export', d.run, -1); if (r) toast('Uloženo do Stažené/VEGA: ' + r.name); break; }
    case 'saveProc': { const r = call('saveProcedure', JSON.parse(d.save)); if (r) { if (d.run) S.savedRuns.add(d.run); S.procs = null; toast(`Nástroj „${r.name}“ uložen (v${r.version}). Nabídnu ho u dalšího souboru.`); closeSheet(true); render(); } break; }
    case 'resume': if (busyGuard()) return; S.busy = true; call('resume', chatId(), d.run, {}); render(); break;
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
    case 'coverage': { const c = JSON.parse(d.cov); sheet(`<h2>Kontrola úplnosti</h2><div class="list">${c.items.map(i => `<div class="row"><span class="pill ${i.ok ? 'ok' : 'bad'}">${i.ok ? '✓' : 'chybí'}</span><span class="grow">${esc(i.q)}</span></div>`).join('')}</div><h3>Citované pasáže</h3>${c.passages.map(p => `<details><summary>${esc(p.id)}</summary><p class="note" style="white-space:pre-wrap">${esc(p.text)}</p></details>`).join('')}`, true); break; }
    case 'runProc': closeSheet(true); if (busyGuard()) return; S.awaitProc = d.id; toast('Vyber soubor – pak nástroj spustím.'); B.pickFiles(); break;
    case 'pinProc': call('pinProcedure', d.id, d.on); S.procs = null; toast(d.on === 'true' ? 'Připnuto na úvodní obrazovku.' : 'Odepnuto.'); library('procs'); break;
    case 'procMenu': { const p = JSON.parse(d.p); sheet(`<h2>${esc(p.name)} <span class="pill">v${p.version}</span></h2><p class="lede">${esc(p.description || '')}</p>
        <button class="btn wide" data-act="pinProc" data-id="${esc(p.id)}" data-on="${p.pinned ? 'false' : 'true'}" style="margin:6px 0">${p.pinned ? 'Odepnout z úvodní obrazovky' : 'Připnout na úvodní obrazovku'}</button>
        <div class="card"><div class="note">Vstup: ${esc(p.inputDesc || p.inputKind)}</div><div class="note">Výstup: ${esc(p.outputKind)}</div><div class="note">Kroky: ${p.steps.map(esc).join(' → ')}</div><div class="note">Oprávnění: ${p.perms.length ? p.perms.map(esc).join(', ') : 'žádná'} · použito ${p.uses}×</div></div>
        <h3>Verze</h3>${p.changelog.map(c => `<p class="note">${esc(c)}</p>`).join('')}
        <button class="btn wide" data-act="delProc" data-id="${esc(p.id)}" style="margin-top:14px;color:var(--bad)">Smazat nástroj</button>`); break; }
    case 'delProc': if (confirm('Smazat uložený nástroj?')) { call('deleteProcedure', d.id); S.procs = null; library('procs'); } break;
    case 'memAdd': { const v = ($('#memText') || {}).value; if (v) { const r = call('memoryAdd', v); if (r) toast(r.ok ? 'Zapamatováno.' : r.reason, 5000); library('memory'); } break; }
    case 'memRevert': { const r = call('memoryRevert', d.id); toast(r ? 'Vráceno na: ' + r : 'Není co vrátit'); if ($('#layer').innerHTML) library('memory'); break; }
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
    sheet(`<h2>Je klíč z projektu bez fakturace?</h2><p class="lede">Do „zdarma“ patří klíč z projektu, kde není zapnutý Cloud Billing. Klíč z projektu s kreditem Pro by tu čerpal peníze bez stropu.</p>
      <div class="list"><button class="row" data-act="isFree"><span class="grow"><b>Bez fakturace</b><small>uložit jako Gemini – zdarma</small></span></button><button class="row" data-act="toPro"><span class="grow"><b>S fakturací / kredit Pro</b><small>uložit jako Gemini – kredit Pro (hlídám strop)</small></span></button><button class="row" data-act="cancelKey"><span class="grow"><b>Zrušit</b></span></button></div>`);
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
  else if (k === 'proc') { if (busyGuard()) return; const f = S.files.pop(); S.busy = true; call('runProcedure', chatId(), arg, { file: f.id }); render(); }
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
