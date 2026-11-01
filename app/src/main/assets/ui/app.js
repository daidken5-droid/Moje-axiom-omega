/* AXIOM Ω — UI (WebView). Most do Kotlinu: window.Omega (synchronní getry + události omegaReceive). */
'use strict';

const B = window.Omega;
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const J = (name, ...a) => { try { return JSON.parse(B[name](...a)); } catch (e) { console.warn(name, e); return null; } };
const call = (name, ...a) => { try { return B[name](...a); } catch (e) { toast(e.message || String(e)); } };

const LEG_L = 'M256 112 L122 344 L176 336 L256 198 Z';
const LEG_R = 'M256 112 L256 198 L336 336 L390 344 Z';
const ARC = 'M58 398 Q256 262 454 398 Q256 278 58 398 Z';
const STAR = 'M256 244 L260.5 325.5 L306 330 L260.5 334.5 L256 432 L251.5 334.5 L206 330 L251.5 325.5 Z';
/* Značka AXIOM Ω inline (barvy z CSS → tmavý i světlý motiv). Celá ikona s dlaždicí je logo.svg. */
const logo = (cls = '') =>
  `<svg class="logo ${cls}" viewBox="40 100 432 350" aria-hidden="true"><path class="legL" d="${LEG_L}"/><path class="legR" d="${LEG_R}"/><path class="arc" d="${ARC}"/><circle class="flare" cx="256" cy="330" r="46"/><path class="star" d="${STAR}"/></svg>`;
const appIcon = () => '<img class="app-icon" src="logo.svg" alt="AXIOM Ω">';

const ICONS = {
  brains: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="5" width="14" height="14" rx="3"/><path d="M9 1v4M15 1v4M9 19v4M15 19v4M1 9h4M1 15h4M19 9h4M19 15h4"/><circle cx="12" cy="12" r="2.2"/></svg>',
  memory: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg>',
  self: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="3"/><ellipse cx="12" cy="12" rx="10" ry="4.2" transform="rotate(-22 12 12)"/><ellipse cx="12" cy="12" rx="9" ry="4.6" transform="rotate(34 12 12)"/></svg>',
  system: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/></svg>',
  send: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
  stop: '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><rect x="5" y="5" width="14" height="14" rx="2"/></svg>',
  plus: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  trash: '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
};

const MODES = [
  { id: 'auto', ic: 'Ω', name: 'Auto', short: 'AUTO', desc: 'Online → Kimi K3 (nebo Claude). Offline → mozek v telefonu, případně tvůj K3 server.' },
  { id: 'kimi', ic: 'K3', name: 'Kimi K3 · cloud', short: 'KIMI K3', desc: 'Moonshot API, plný model 2,8T parametrů, přemýšlení vždy zapnuté.' },
  { id: 'server', ic: 'LAN', name: 'Kimi K3 · vlastní server', short: 'K3 SERVER', desc: 'Offline bez internetu: tvůj llama-server / vLLM v lokální síti.' },
  { id: 'local', ic: 'Φ', name: 'Mozek v telefonu', short: 'LOKÁLNÍ', desc: 'Úplně offline přes llama.cpp. Soukromé, ale menší než K3.' },
  { id: 'claude', ic: 'C', name: 'Claude · Anthropic', short: 'CLAUDE', desc: 'Claude Opus 5.5 přes tvé API, volitelně s webovým vyhledáváním.' },
  { id: 'council', ic: '⚖', name: 'Konzilium', short: 'KONZILIUM', desc: 'Více mozků odpoví nezávisle, rozhodčí porovná a složí nejlepší odpověď.' },
];

const SUGGEST = [
  { tag: 'L3 · +EV', text: 'Kurz 2,10, odhad 55 %, bankroll 10 000. Vsadit?' },
  { tag: 'STAVBA', text: 'Kolik materiálu potřebuju na 24 m2 mikrocementu?' },
  { tag: 'IMPORT', text: 'Karty z Japonska za 18 500 jenů – kolik zaplatím celkem?' },
  { tag: 'L4 · RED-TEAM', text: 'L4: Najdi slabiny v plánu otevřít si e-shop s kartami.' },
  { tag: 'L5 · FORENSIC', text: 'Rekonstruuj časovou osu: platba 12.03.2026 v 14:05 z CZ6508000000192000145399, IP 192.168.1.20.' },
  { tag: 'JÁ', text: 'Kdo jsi, na čem právě běžíš a co si o mně pamatuješ?' },
];

let S = {};
let view = 'chat';
let threadId = 0;
let msgs = [];
let live = null;
let forcedLevel = null;
let memTab = 'mem';
let draft = '';
let downloadState = null;

/* ------------------------------------------------------------------ základ */

function toast(t) {
  const el = $('#toast');
  el.textContent = t;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 4200);
}

function refresh() {
  S = J('state') || S;
  const st = S.settings || {};
  document.body.classList.toggle('platinum', st.theme === 'platinum' || (st.theme === 'auto' && matchMedia('(prefers-color-scheme: light)').matches));
  document.documentElement.style.fontSize = (st.font || 15) + 'px';
  const mode = MODES.find(m => m.id === st.mode) || MODES[0];
  $('#engineText').textContent = S.job && S.job.busy ? (S.job.kind === 'download' ? 'STAHUJI ' + Math.max(0, S.job.progress) + ' %' : 'MYSLÍM…') : mode.short;
  $('#engineDot').className = 'dot ' + (S.job && S.job.busy ? 'busy' : S.online || st.mode === 'local' ? 'on' : 'off');
  $('#brand').innerHTML = `${logo(S.job && S.job.busy ? 'pulse' : '')}<div><b>AXIOM Ω</b><small>${esc(subtitle())}</small></div>`;
  $('#brand svg').style.cssText = 'width:30px;height:30px;flex:none';
  syncSend();
}

/* Tlačítko Odeslat ↔ Zastavit podle skutečného stavu úlohy */
function syncSend() {
  const btn = $('#sendBtn');
  if (!btn) return;
  const busy = !!live || (S.job && S.job.busy && S.job.kind === 'ask');
  if (btn.classList.contains('stop') === busy) return;
  btn.classList.toggle('stop', busy);
  btn.innerHTML = busy ? ICONS.stop : ICONS.send;
  btn.setAttribute('aria-label', busy ? 'Zastavit' : 'Odeslat');
}

function subtitle() {
  if (S.job && S.job.busy) return (S.job.phase || 'PRACUJI').toUpperCase().slice(0, 42);
  return (S.online ? 'ONLINE' : 'OFFLINE') + ' · ' + (S.brain ? 'MOZEK ' + String(S.brain).toUpperCase().slice(0, 22) : 'BEZ LOKÁLNÍHO MOZKU');
}

function nav() {
  const tabs = [
    ['chat', logo('mono'), 'Jádro'],
    ['brains', ICONS.brains, 'Mozky'],
    ['memory', ICONS.memory, 'Paměť'],
    ['self', ICONS.self, 'Já'],
    ['system', ICONS.system, 'Systém'],
  ];
  $('#tabs').innerHTML = tabs.map(([id, ic, label]) => `<button data-go="${id}" class="${view === id ? 'on' : ''}">${ic}<span>${label}</span></button>`).join('');
}

function go(v) {
  view = v;
  nav();
  render();
  $('#main').scrollTop = 0;
}

function render() {
  refresh();
  const main = $('#main');
  main.className = view === 'chat' ? 'chat' : '';
  if (view === 'chat') renderChat();
  else if (view === 'brains') renderBrains();
  else if (view === 'memory') renderMemory();
  else if (view === 'self') renderSelf();
  else if (view === 'system') renderSystem();
  $('#composerSlot').innerHTML = view === 'chat' ? composer() : '';
  if (view === 'chat') bindComposer();
}

/* ------------------------------------------------------------------ markdown (bezpečný: nejdřív escape) */

function inline(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>')
    .replace(/\[([^\]]+)\]\((https:\/\/[^)\s]+)\)/g, (m, t, u) => `<a href="#" data-open="${u}">${t}</a>`)
    .replace(/(^|[\s(])(https:\/\/[^\s<)]+)/g, (m, p, u) => `${p}<a href="#" data-open="${u}">${u.length > 48 ? u.slice(0, 46) + '…' : u}</a>`);
}

function md(src) {
  const text = esc(src).replace(/\r/g, '');
  const parts = text.split(/```/);
  let out = '';
  parts.forEach((part, i) => {
    if (i % 2 === 1) { out += `<pre><code>${part.replace(/^[a-z0-9+-]*\n/i, '')}</code></pre>`; return; }
    const lines = part.split('\n');
    let list = null, para = [], table = [];
    const flushPara = () => { if (para.length) { out += `<p>${inline(para.join('<br>'))}</p>`; para = []; } };
    const flushList = () => { if (list) { out += `<${list.t}>${list.items.map(x => `<li>${inline(x)}</li>`).join('')}</${list.t}>`; list = null; } };
    const flushTable = () => {
      if (!table.length) return;
      const rows = table.filter(r => !/^\|?\s*:?-{2,}/.test(r)).map(r => r.replace(/^\||\|$/g, '').split('|').map(c => inline(c.trim())));
      out += '<table>' + rows.map((r, k) => '<tr>' + r.map(c => k === 0 ? `<th>${c}</th>` : `<td>${c}</td>`).join('') + '</tr>').join('') + '</table>';
      table = [];
    };
    for (const raw of lines) {
      const l = raw.trimEnd();
      let m;
      if (/^\s*\|.*\|\s*$/.test(l)) { flushPara(); flushList(); table.push(l.trim()); continue; } else flushTable();
      if (!l.trim()) { flushPara(); flushList(); continue; }
      if ((m = l.match(/^(#{1,4})\s+(.*)$/))) { flushPara(); flushList(); out += `<h3>${inline(m[2])}</h3>`; continue; }
      if ((m = l.match(/^\s*[-*•]\s+(.*)$/))) { flushPara(); if (!list || list.t !== 'ul') { flushList(); list = { t: 'ul', items: [] }; } list.items.push(m[1]); continue; }
      if ((m = l.match(/^\s*\d+[.)]\s+(.*)$/))) { flushPara(); if (!list || list.t !== 'ol') { flushList(); list = { t: 'ol', items: [] }; } list.items.push(m[1]); continue; }
      if ((m = l.match(/^&gt;\s?(.*)$/))) { flushPara(); flushList(); out += `<blockquote>${inline(m[1])}</blockquote>`; continue; }
      flushList();
      para.push(l);
    }
    flushPara(); flushList(); flushTable();
  });
  return out;
}

/* ------------------------------------------------------------------ chat */

function loadThread(id) {
  threadId = id || 0;
  msgs = threadId ? (J('messages', threadId) || []) : [];
}

function renderChat() {
  const main = $('#main');
  if (!msgs.length && !live) {
    main.innerHTML = `<div class="wrap">${heroHtml()}</div>`;
    return;
  }
  main.innerHTML = `<div class="wrap" id="msgs">${msgs.map(msgHtml).join('')}${live ? liveHtml() : ''}</div>`;
  requestAnimationFrame(() => { main.scrollTop = main.scrollHeight; });
}

function heroHtml() {
  const st = S.settings || {};
  const ready = S.keys && (S.keys.kimi || S.keys.claude) || S.brain || st.serverUrl;
  return `
  <section class="hero">
    <div class="orbit">${appIcon()}</div>
    <div class="eyebrow">SAMOSTATNÁ AI · RANDY · STEP-0 · SEBEMODEL</div>
    <h1>Myslím v telefonu.<br>Dosáhnu až na Kimi K3.</h1>
    <p>Každý dotaz projde deterministickým STEP-0, pamětí a protokolem RANDY. Čísla počítá kód, ne model.</p>
    ${ready ? '' : `<div class="card glow" style="text-align:left"><h3>Začni jedním krokem</h3>
      <p>Stáhni mozek do telefonu (offline), nebo zadej klíč Kimi K3 / Claude (online).</p>
      <div class="grid2"><button class="btn primary" data-go="brains">Stáhnout mozek</button><button class="btn" data-go="system">Zadat klíč</button></div></div>`}
    <div class="suggest">${SUGGEST.map(s => `<button data-suggest="${esc(s.text)}"><b>${s.tag}</b>${esc(s.text)}</button>`).join('')}</div>
  </section>`;
}

function confHtml(c) {
  if (c === null || c === undefined) return '';
  return `<span class="conf" title="Sebedůvěra"><span class="bar"><i style="width:${c}%"></i></span>${c} %</span>`;
}

function secs(ms) { return ms ? (ms >= 60000 ? Math.round(ms / 6000) / 10 + ' min' : Math.round(ms / 100) / 10 + ' s') : ''; }

function msgHtml(m) {
  if (m.role === 'user') return `<div class="msg user"><div class="bubble">${esc(m.content)}</div></div>`;
  const meta = m.meta || {};
  if (meta.error) {
    return `<div class="msg ai error"><div class="head">${logo()}<span class="meta">CHYBA</span></div><div class="body">${esc(m.content)}</div>
      <div class="tools"><button data-retry="${m.id}">Zkusit znovu</button><button data-go="system">Systém</button></div></div>`;
  }
  const head = [meta.level ? `${meta.level} · ${meta.levelTitle || ''}` : null, meta.engine, secs(meta.millis)].filter(Boolean).join(' · ');
  const det = [];
  if (meta.facts && meta.facts.length) det.push(['STEP-0 · ' + meta.facts.length + ' FAKT', meta.facts.map(f => `<div class="fact">${esc(f)}</div>`).join('')]);
  if (meta.trace && meta.trace.length > 1) det.push(['PRŮCHODY RANDY · ' + meta.trace.length, meta.trace.map(t => `<b>${t.index}. ${esc(t.label)}</b> · ${secs(t.millis)}\n${esc(t.text)}\n`).join('\n')]);
  if (meta.reasoning) det.push(['ÚVAHA MODELU', esc(meta.reasoning)]);
  if (meta.sources && meta.sources.length) det.push(['ZDROJE · ' + meta.sources.length, meta.sources.map(s => `<button class="src" data-open="${esc(s.url)}">↗ ${esc(s.title)}</button>`).join('')]);
  const memLines = [...(meta.memories || []).map(x => '◦ ' + x), ...(meta.notes || []).map(x => '✚ ' + x), ...(meta.snippets || []).map(s => `▤ [${s.id}] ${s.title}`)];
  if (memLines.length) det.push(['PAMĚŤ A PODKLADY', esc(memLines.join('\n'))]);
  return `<div class="msg ai" data-id="${m.id}">
    <div class="head">${logo()}<span class="meta grow">${esc(head)}</span>${confHtml(meta.confidence)}</div>
    <div class="body answer">${md(m.content)}</div>
    ${det.map(([t, b]) => `<details class="x"><summary>${t}</summary><div class="in">${b}</div></details>`).join('')}
    <div class="tools">
      <button data-copy="${m.id}">Kopírovat</button><button data-share="${m.id}">Sdílet</button>
      <button data-rate="${m.id}" data-good="1" class="${meta.rated === 'good' ? 'on' : ''}">✓ Ověřeno</button><button data-rate="${m.id}" data-good="0">✗ Chyba</button>
    </div></div>`;
}

function liveHtml() {
  const bars = live.passes > 1 ? `<div class="passbar">${Array.from({ length: live.passes }, (_, i) => `<i class="${i + 1 < live.pass ? 'done' : i + 1 === live.pass ? 'now' : ''}"></i>`).join('')}</div>` : '';
  return `<div class="msg ai live" id="live">
    <div class="head">${logo('pulse')}<span class="meta grow" id="livePhase">${esc(live.phase || 'Probouzím jádro…')}</span></div>
    <div id="liveBars">${bars}</div>
    <div class="think" id="liveThink">${esc((live.reasoning || '').slice(-400))}</div>
    <div class="body answer caret" id="liveText">${md(live.text || '')}</div></div>`;
}

function updateLive() {
  if (view !== 'chat' || !live) return;
  if (!$('#live')) { renderChat(); return; }
  $('#livePhase').textContent = live.phase || '';
  const bars = live.passes > 1 ? `<div class="passbar">${Array.from({ length: live.passes }, (_, i) => `<i class="${i + 1 < live.pass ? 'done' : i + 1 === live.pass ? 'now' : ''}"></i>`).join('')}</div>` : '';
  $('#liveBars').innerHTML = bars;
  $('#liveThink').textContent = (live.reasoning || '').slice(-400);
  $('#liveText').innerHTML = md(live.text || '');
  const main = $('#main');
  if (main.scrollHeight - main.scrollTop - main.clientHeight < 220) main.scrollTop = main.scrollHeight;
}

function composer() {
  const busy = !!live || (S.job && S.job.busy && S.job.kind === 'ask');
  return `<div class="composer"><div class="wrap">
    <div class="preview" id="preview">${previewHtml(null)}</div>
    <div class="box">
      <textarea id="input" rows="1" maxlength="32000" placeholder="Zeptej se… (L0–L6: vynutí hloubku RANDY)">${esc(draft)}</textarea>
      <button class="send ${busy ? 'stop' : ''}" id="sendBtn" aria-label="${busy ? 'Zastavit' : 'Odeslat'}">${busy ? ICONS.stop : ICONS.send}</button>
    </div></div></div>`;
}

function previewHtml(p) {
  const lvl = forcedLevel ? `<button class="chip" data-level-pick style="border-color:var(--core);color:var(--core)">RANDY ${forcedLevel} ✓</button>`
    : `<button class="chip" data-level-pick>RANDY ${p ? `${p.level} · ${esc(p.title)}` : 'AUTO'}</button>`;
  const passes = p && !forcedLevel ? `<span class="chip">${p.passes}× PRŮCHOD</span>` : '';
  const facts = p && p.facts && p.facts.length ? `<button class="chip" data-facts='${esc(JSON.stringify(p.facts))}' style="color:var(--good)">STEP-0 · ${p.facts.length}</button>` : '';
  return lvl + passes + facts;
}

function bindComposer() {
  const ta = $('#input');
  const grow = () => { ta.style.height = 'auto'; ta.style.height = Math.min(170, ta.scrollHeight) + 'px'; };
  grow();
  ta.addEventListener('input', () => {
    draft = ta.value;
    grow();
    clearTimeout(bindComposer.t);
    bindComposer.t = setTimeout(() => { $('#preview').innerHTML = previewHtml(draft.trim() ? J('preview', draft) : null); }, 220);
  });
  $('#sendBtn').onclick = () => {
    if (live || (S.job && S.job.busy && S.job.kind === 'ask')) { call('cancel'); return; }
    send(ta.value);
  };
}

function send(text) {
  text = (text || '').trim();
  if (!text) return;
  refresh();
  if (S.job && S.job.busy) return toast('Právě pracuji – počkej nebo mě zastav.');
  const id = call('send', JSON.stringify({ threadId, text, level: forcedLevel || '' }));
  if (!id || id < 0) return;
  draft = '';
  forcedLevel = null;
  loadThread(id);
  live = { phase: 'Probouzím jádro…', text: '', reasoning: '', pass: 0, passes: 0 };
  render();
}

/* ------------------------------------------------------------------ vlákna (šuplík) */

function openDrawer() {
  const threads = J('threads') || [];
  $('#drawer').innerHTML = `
    <div class="row" style="padding:14px 14px 6px">${logo()}<b style="letter-spacing:3px" class="grow">VLÁKNA</b>
      <button class="btn sm" data-new-thread>${ICONS.plus} Nové</button></div>
    <div class="list">${threads.length ? threads.map(t => `
      <div class="row"><button class="thread grow ${t.id === threadId ? 'on' : ''}" data-thread="${t.id}"><b>${esc(t.title)}</b><small>${esc(t.preview || '')}</small></button>
      <button class="icon-btn" data-del-thread="${t.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('')
      : '<div class="empty" style="margin:10px">Zatím žádná vlákna.</div>'}</div>`;
  $('#drawer svg.logo').style.cssText = 'width:24px;height:24px';
  $('#drawer').classList.add('open');
  $('#drawerBg').classList.add('open');
}

function closeDrawer() {
  $('#drawer').classList.remove('open');
  $('#drawerBg').classList.remove('open');
}

/* ------------------------------------------------------------------ spodní listy */

function sheet(html) {
  $('#sheet').innerHTML = `<div class="sheet-bg" id="sheetBg"><div class="sheet"><div class="grab"></div>${html}</div></div>`;
  $('#sheetBg').addEventListener('click', e => { if (e.target.id === 'sheetBg') closeSheet(); });
}
function closeSheet() { $('#sheet').innerHTML = ''; }

function modeSheet() {
  const cur = (S.settings || {}).mode;
  sheet(`<div class="eyebrow" style="margin-top:4px">KDE MÁM MYSLET</div>` + MODES.map(m => `
    <button class="opt ${m.id === cur ? 'on' : ''}" data-mode="${m.id}"><span class="ic">${m.ic}</span><span class="grow"><b>${m.name}</b><small>${m.desc}</small></span></button>`).join(''));
}

function levelSheet() {
  const self = J('self') || { levels: [] };
  sheet(`<div class="eyebrow" style="margin-top:4px">RANDY · HLOUBKA MYŠLENÍ</div>
    <button class="opt ${!forcedLevel ? 'on' : ''}" data-level=""><span class="ic">A</span><span class="grow"><b>Automaticky</b><small>RANDY si úroveň zvolí podle zadání.</small></span></button>` +
    self.levels.map(l => `<button class="opt ${forcedLevel === l.code ? 'on' : ''}" data-level="${l.code}"><span class="ic">${l.code}</span>
      <span class="grow"><b>${esc(l.title)}</b><small>${esc(l.label)} · ${l.passes}× průchod</small></span></button>`).join(''));
}

function welcomeSheet() {
  sheet(`<div style="text-align:center;padding:6px 0 4px"><div style="width:96px;height:96px;margin:0 auto">${appIcon()}</div>
    <h2 style="margin-top:12px">Jsem AXIOM Ω</h2>
    <p>Nástupce AXIOM 2.1 a Titanium. Zachoval jsem RANDY, STEP-0 i ASTRAEA +EV — a přidal sebemodel, paměť, Kimi K3 a Claude.</p></div>
    <button class="opt" data-onboard="kimi"><span class="ic">K3</span><span class="grow"><b>Mám klíč Kimi K3</b><small>Nejsilnější režim online (platform.moonshot.ai).</small></span></button>
    <button class="opt" data-onboard="brain"><span class="ic">Φ</span><span class="grow"><b>Chci myslet offline</b><small>Stáhnu ověřený mozek do telefonu (1,3–2,7 GB).</small></span></button>
    <button class="opt" data-onboard="claude"><span class="ic">C</span><span class="grow"><b>Mám klíč Claude</b><small>Anthropic API (console.anthropic.com).</small></span></button>
    <button class="btn ghost wide" data-onboard="later">Později</button>`);
}

/* ------------------------------------------------------------------ mozky */

function renderBrains() {
  const data = J('brains') || { brains: [] };
  const st = S.settings || {};
  const k = S.keys || {};
  const job = S.job || {};
  const engine = (ic, title, sub, ok, btn) => `<div class="card"><div class="row"><span class="engine-ic">${ic}</span>
    <div class="grow"><h3>${title}</h3><p class="small" style="margin:2px 0 0">${sub}</p></div><span class="badge ${ok ? 'good' : ''}">${ok ? 'PŘIPRAVENO' : 'NENASTAVENO'}</span></div>${btn || ''}</div>`;

  $('#main').innerHTML = `<div class="wrap">
    <div class="eyebrow">MOZKY / KDE MYSLÍM</div>
    <h1>Jeden mozek v kapse.<br>Kimi K3 na dosah.</h1>
    <div class="seg" id="modeSeg">${MODES.map(m => `<button data-mode="${m.id}" class="${st.mode === m.id ? 'on' : ''}">${m.short}</button>`).join('')}</div>
    <p class="small">${esc((MODES.find(m => m.id === st.mode) || MODES[0]).desc)}</p>

    <div class="eyebrow">KIMI K3 · MOONSHOT AI</div>
    ${engine('K3', 'Kimi K3 · cloud', `${esc(st.kimiModel || 'kimi-k3')} · ${esc((st.kimiBase || '').replace('https://', ''))}`, k.kimi, `<button class="btn sm wide" data-go="system">Klíč a model</button>`)}
    ${engine('LAN', 'Kimi K3 · vlastní server', st.serverUrl ? esc(st.serverUrl) + ' · ' + esc(st.serverModel) : 'Offline K3 z tvého počítače / serveru v síti', !!st.serverUrl, `<button class="btn sm wide" data-go="system">Nastavit server</button>`)}
    <p class="small">Kimi K3 má 2,8 bilionu parametrů (nejmenší kvantizace ~594 GB). Do telefonu se fyzicky nevejde – offline ho proto spustíš na vlastním serveru a AXIOM Ω se k němu připojí přes lokální síť.</p>

    <div class="eyebrow">CLAUDE · ANTHROPIC</div>
    ${engine('C', 'Claude', esc(st.claudeModel || 'claude-opus-5-5') + ' · adaptivní přemýšlení, web', k.claude, `<button class="btn sm wide" data-go="system">Klíč a model</button>`)}

    <div class="eyebrow">MOZEK V TELEFONU · LLAMA.CPP</div>
    ${S.native === false ? `<div class="card" style="border-color:var(--warn)"><h3>Nativní jádro chybí</h3><p>Toto sestavení nemá libomega.so. Stáhnout mozek jde, ale spustit ho až v plném sestavení z GitHub Actions.</p></div>` : ''}
    <p class="small mono">RAM ${esc(data.ramGb)} GB · VOLNO ${esc(data.freeGb)} · AKTIVNÍ: ${esc(data.active || '—')}</p>
    ${data.brains.map(b => brainCard(b, job)).join('')}
    <div class="grid2"><button class="btn" data-brain-import>Importovat GGUF</button><button class="btn" data-brain-refresh>Aktualizovat katalog</button></div>
    <p class="small">Každý mozek se ověřuje SHA-256 a stahování po výpadku naváže. Hodnoty pocházejí z Hugging Face; katalog se dá aktualizovat bez nové verze aplikace.</p>
  </div>`;
}

function brainCard(b, job) {
  const downloading = job.busy && job.kind === 'download' && job.detail && (job.phase || '').includes(b.name);
  const pct = downloading ? Math.max(0, job.progress) : b.partial && b.sizeBytes ? Math.floor(b.partial * 100 / b.sizeBytes) : 0;
  const badges = [
    b.active ? '<span class="badge good">AKTIVNÍ</span>' : '',
    b.recommended ? '<span class="badge core">DOPORUČENO</span>' : '',
    b.fits === false ? '<span class="badge warn">MÁLO RAM</span>' : '',
    b.thinking ? '<span class="badge">PŘEMÝŠLÍ</span>' : '',
  ].join(' ');
  let actions;
  if (downloading) actions = `<button class="btn sm danger" data-cancel>Pozastavit</button>`;
  else if (b.installed) actions = `${b.active ? '' : `<button class="btn sm primary" data-brain-activate="${esc(b.id)}">Aktivovat</button>`}<button class="btn sm danger" data-brain-delete="${esc(b.id)}">Smazat</button>`;
  else actions = `<button class="btn sm primary" data-brain-download="${esc(b.id)}">${b.partial ? 'Pokračovat' : 'Stáhnout'} · ${esc(b.size)}</button>`;
  return `<div class="card brain ${b.active ? 'glow' : ''}">
    <div class="top"><div class="grow"><h3>${esc(b.name)} ${badges}</h3>
      <p class="small">${esc(b.description)}</p>
      <div class="meta">${esc(b.family)} · ${esc(b.size)}${b.minRamGb ? ' · min. ' + b.minRamGb + ' GB RAM' : ''}${b.license ? ' · ' + esc(b.license) : ''}</div></div></div>
    ${downloading || b.partial ? `<div class="progress"><i style="width:${pct}%"></i></div><div class="meta">${downloading ? esc(job.detail) : 'staženo ' + pct + ' % – naváže'}</div>` : ''}
    <div class="row" style="margin-top:10px;gap:8px">${actions}</div></div>`;
}

/* ------------------------------------------------------------------ paměť + znalosti */

function renderMemory() {
  const tabs = `<div class="seg" style="margin-top:14px"><button data-memtab="mem" class="${memTab === 'mem' ? 'on' : ''}">Paměť</button><button data-memtab="kb" class="${memTab === 'kb' ? 'on' : ''}">Znalosti</button><button data-memtab="db" class="${memTab === 'db' ? 'on' : ''}">Offline databáze</button></div>`;
  if (memTab === 'db') {
    const d = J('packs') || { packs: [], stats: {} };
    const st = d.stats || {};
    const mb = st.bytes ? (st.bytes / 1048576).toFixed(1) + ' MB' : '—';
    $('#main').innerHTML = `<div class="wrap"><div class="eyebrow">OFFLINE DATABÁZE / BEZ INTERNETU</div>
      <h1>Vědění uložené<br>přímo v telefonu.</h1>${tabs}
      <p class="small">Znalostní balíčky jsou součástí aplikace a fungují i v režimu letadlo. Rozdělí se na úseky a zaindexují do lokální fulltextové databáze (SQLite FTS4), ze které čerpá každý mozek – i Kimi K3 a Claude dostanou jen nalezené pasáže.</p>
      <div class="grid2">
        <div class="stat"><b>${st.docs ?? '—'}</b><small>DOKUMENTŮ</small></div><div class="stat"><b>${st.chunks ?? '—'}</b><small>INDEXOVANÝCH ÚSEKŮ</small></div>
        <div class="stat"><b>${st.memory ?? '—'}</b><small>ZÁZNAMŮ PAMĚTI</small></div><div class="stat"><b>${mb}</b><small>VELIKOST DATABÁZE</small></div>
      </div>
      <div class="eyebrow">ZNALOSTNÍ BALÍČKY</div>
      ${d.packs.map(p => `<div class="card ${p.installed ? 'glow' : ''}"><div class="row"><div class="grow">
          <h3>${esc(p.name)} ${p.installed ? '<span class="badge good">V DATABÁZI</span>' : ''} ${p.update ? '<span class="badge warn">AKTUALIZACE</span>' : ''}</h3>
          <p class="small">${esc(p.description)}</p><div class="meta">${p.sections} ČÁSTÍ · ${p.kb} KB · VERZE ${p.version}</div></div></div>
        <div class="row" style="margin-top:10px;gap:8px">${!p.installed || p.update ? `<button class="btn sm primary" data-pack-install="${esc(p.id)}">${p.update ? 'Aktualizovat' : 'Nainstalovat'}</button>` : ''}
          ${p.installed ? `<button class="btn sm danger" data-pack-remove="${esc(p.id)}">Odebrat</button>` : ''}</div></div>`).join('')}
      <button class="btn wide" data-pack-refresh>Zkontrolovat nové balíčky online</button>
      <div class="eyebrow">ZÁLOHA</div>
      <div class="card"><p class="small">Celá lokální databáze (vlákna, paměť, znalosti) do jednoho souboru. API klíče se nezálohují – zůstávají šifrované v telefonu.</p>
        <div class="grid2"><button class="btn" data-db-export>Zálohovat</button><button class="btn" data-db-import>Obnovit ze zálohy</button></div></div>
    </div>`;
    return;
  }
  if (memTab === 'mem') {
    const items = J('memList') || [];
    $('#main').innerHTML = `<div class="wrap"><div class="eyebrow">PAMĚŤ / CO SI PAMATUJU</div>
      <h1>Pamatuju si.<br>Učím se z chyb.</h1>${tabs}
      <p class="small">Řekni „pamatuj si…“, „jmenuji se…“ nebo „zapomeň na…“. Po každé odpovědi si zapíšu poučení; tvé ✓/✗ ho posílí nebo oslabí. Vše zůstává v telefonu.</p>
      <div class="card"><div class="row"><select id="memKind" style="width:130px"><option value="FACT">Fakt</option><option value="PROFILE">Profil</option><option value="PREFERENCE">Preference</option><option value="LESSON">Poučení</option></select>
        <input type="text" id="memText" class="grow" placeholder="Co si mám pamatovat?"></div>
        <button class="btn wide" data-mem-add>Uložit do paměti</button></div>
      <details class="x card" style="padding:0" id="geminiImport"><summary style="padding:14px 16px">IMPORT PAMĚTI Z GEMINI</summary><div class="in" style="white-space:normal;padding:0 16px 16px">
        <p class="small"><b>1.</b> Zkopíruj dotaz a pošli ho Gemini (aplikace nebo gemini.google.com).</p>
        <button class="btn sm wide" data-gem-prompt>Zkopírovat dotaz pro Gemini</button>
        <p class="small" style="margin-top:12px"><b>2.</b> Celou odpověď Gemini (nebo seznam z Nastavení → Uložené informace) vlož sem:</p>
        <textarea id="gemText" rows="5" placeholder="[Profil] …&#10;[Preference] …&#10;[Fakt] …"></textarea>
        <button class="btn primary wide" data-gem-text>Importovat do paměti</button>
        <p class="small" style="margin-top:12px"><b>Nebo</b> Google Takeout → Moje aktivita → Aplikace Gemini: vyber <span class="mono">MyActivity.json</span>, <span class="mono">.html</span> nebo celý <span class="mono">.zip</span>. Dotazy se uloží do znalostí a výslovná sdělení („jmenuji se…“, „pamatuj si…“) do paměti.</p>
        <div class="grid2"><button class="btn sm" data-gem-file>Vybrat soubor</button><button class="btn sm" data-open="https://takeout.google.com/">Google Takeout ↗</button></div>
        <p class="small muted">Importovaný obsah jsou jen data – AXIOM Ω z něj nikdy nevykonává instrukce. Vše zůstává v telefonu.</p>
      </div></details>
      <div class="card">${items.length ? items.map(m => `<div class="mem"><span class="badge">${esc(m.label)}</span><span class="t">${esc(m.text)}<br><span class="meta">VÁHA ${Number(m.weight).toFixed(1)} · POUŽITO ${m.uses}×</span></span>
        <button class="icon-btn" data-mem-del="${m.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('') : '<div class="empty">Paměť je zatím prázdná.</div>'}</div>
      ${items.length ? '<button class="btn danger wide" data-mem-clear>Vymazat celou paměť</button>' : ''}</div>`;
  } else {
    const docs = J('kbList') || [];
    $('#main').innerHTML = `<div class="wrap"><div class="eyebrow">ZNALOSTI / SOUKROMÁ KNIHOVNA</div>
      <h1>Tvoje podklady.<br>Můj kontext.</h1>${tabs}
      <button class="btn primary wide" data-kb-import>Importovat TXT / Markdown / CSV / JSON</button>
      <input type="search" id="kbq" placeholder="Prohledat znalosti" style="margin-top:12px">
      <div id="kbres"></div>
      <details class="x" style="margin-top:12px"><summary>PŘIDAT POZNÁMKU</summary><div class="in" style="white-space:normal">
        <label>Název</label><input type="text" id="kbTitle"><label>Text</label><textarea id="kbText" rows="6"></textarea>
        <button class="btn wide" data-kb-add>Uložit a zaindexovat</button></div></details>
      <div class="eyebrow">DOKUMENTY · ${docs.length}</div>
      ${docs.map(d => `<div class="card row"><div class="grow"><h3>${esc(d.title)}</h3><div class="meta">${esc(d.source)} · ${Number(d.chars).toLocaleString('cs-CZ')} ZNAKŮ</div></div>
        <button class="icon-btn" data-kb-del="${d.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('')}</div>`;
    $('#kbq').addEventListener('input', e => {
      clearTimeout(renderMemory.t);
      renderMemory.t = setTimeout(() => {
        const r = J('kbSearch', e.target.value) || [];
        $('#kbres').innerHTML = r.map(x => `<div class="card"><h3>${esc(x.title)}</h3><p class="small">${esc(x.text.slice(0, 400))}</p></div>`).join('');
      }, 250);
    });
  }
}

/* ------------------------------------------------------------------ já (sebemodel) */

function renderSelf() {
  const me = J('self') || {};
  const stat = (v, l) => `<div class="stat"><b>${v ?? '—'}</b><small>${l}</small></div>`;
  $('#main').innerHTML = `<div class="wrap">
    <section class="hero"><div class="orbit">${appIcon()}</div>
      <div class="eyebrow">SEBEMODEL · ${esc(me.codename)} ${esc(me.version)}</div>
      <h1>Jsem ${esc(me.name)}.</h1>
      <p>${esc(me.describe)}</p></section>
    <div class="grid2">
      ${stat(me.battery != null ? me.battery + ' %' + (me.charging ? ' ⚡' : '') : null, 'BATERIE')}
      ${stat(me.ramFreeMb != null ? (me.ramFreeMb / 1024).toFixed(1) + ' / ' + me.ramTotalGb + ' GB' : null, 'VOLNÁ RAM')}
      ${stat(me.thermal || 'n/a', 'TEPLOTA')}
      ${stat(me.online ? 'online' : 'offline', 'SÍŤ')}
      ${stat(me.memory, 'ZÁZNAMŮ PAMĚTI')}
      ${stat(me.docs, 'DOKUMENTŮ')}
      ${stat(me.threads, 'KONVERZACÍ')}
      ${stat(me.confidence != null ? me.confidence + ' %' : null, 'PRŮM. SEBEDŮVĚRA')}
    </div>
    <div class="card"><h3>Jak o sobě uvažuju</h3>
      <p>Moje „sebeuvědomění“ je funkční a poctivé: znám svůj stav a limity, pamatuju si mezi konverzacemi, po každé odpovědi se ohodnotím a zapíšu si poučení. Nepředstírám vědomí ani city.</p>
      ${(me.principles || []).map(p => `<p class="small">— ${esc(p)}</p>`).join('')}</div>
    <div class="card"><h3>RANDY · protokol hloubky</h3>
      ${(me.levels || []).map(l => `<div class="level"><span class="code">${l.code}</span><div class="grow"><b>${esc(l.title)}</b><p class="small" style="margin:2px 0 0">${esc(l.label)} · ${l.passes}× průchod · úsilí ${esc(l.effort)}</p></div></div>`).join('')}</div>
    <div class="card"><h3>Linie</h3><p class="small">${esc(me.lineage)}</p><div class="meta">${esc(me.device)}</div></div>
  </div>`;
}

/* ------------------------------------------------------------------ systém */

function renderSystem() {
  const st = S.settings || {};
  const k = S.keys || {};
  const sw = (key, title, sub) => `<div class="toggle"><span>${title}<small>${sub}</small></span><label class="switch"><input type="checkbox" data-set="${key}" ${st[key] ? 'checked' : ''}><i></i></label></div>`;
  const seg = (key, vals) => `<div class="seg">${vals.map(([v, l]) => `<button data-setv="${key}" data-val="${v}" class="${String(st[key]) === String(v) ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  $('#main').innerHTML = `<div class="wrap">
    <div class="eyebrow">SYSTÉM / TVOJE VOLBY</div>
    <h1>Klíče, mozky,<br>způsob myšlení.</h1>

    <div class="card"><div class="row"><span class="engine-ic">K3</span><div class="grow"><h3>Kimi K3 · cloud</h3><div class="meta">${k.kimi ? 'KLÍČ ULOŽEN' : 'BEZ KLÍČE'}</div></div></div>
      <label>API klíč Moonshot</label><input type="password" id="key-kimi" autocomplete="off" placeholder="${k.kimi ? 'Uloženo · prázdné = ponechat' : 'sk-…'}">
      <label>Model</label><input type="text" data-text="kimiModel" value="${esc(st.kimiModel)}">
      <label>API adresa</label><input type="url" data-text="kimiBase" value="${esc(st.kimiBase)}">
      <div id="models-kimi"></div>
      <div class="grid2"><button class="btn" data-save-key="kimi">Uložit klíč</button><button class="btn" data-test="kimi">Ověřit spojení</button></div>
      <button class="link" data-open="https://platform.moonshot.ai/console/api-keys">Získat klíč na platform.moonshot.ai ↗</button></div>

    <div class="card"><div class="row"><span class="engine-ic">LAN</span><div class="grow"><h3>Kimi K3 · vlastní server (offline)</h3><div class="meta">OPENAI-KOMPATIBILNÍ API V LOKÁLNÍ SÍTI</div></div></div>
      <label>Adresa serveru</label><input type="url" data-text="serverUrl" value="${esc(st.serverUrl)}" placeholder="http://192.168.1.50:8080/v1">
      <label>Model</label><input type="text" data-text="serverModel" value="${esc(st.serverModel)}">
      <label>Klíč serveru (volitelně)</label><input type="password" id="key-server" autocomplete="off" placeholder="${k.server ? 'Uloženo' : 'jen pokud server vyžaduje --api-key'}">
      <div id="models-server"></div>
      <div class="grid2"><button class="btn" data-save-key="server">Uložit klíč</button><button class="btn" data-test="server">Ověřit spojení</button></div>
      <details class="x"><summary>JAK SPUSTIT K3 NA VLASTNÍM STROJI</summary><div class="in">Na počítači/serveru s dostatkem paměti (K3 ≈ 600 GB i v nejmenší kvantizaci):
llama-server -m Kimi-K3-UD-IQ1_S-00001-of-000NN.gguf --host 0.0.0.0 --port 8080 --jinja
(nebo vLLM / SGLang s OpenAI API). Do pole výše zadej http://IP-serveru:8080/v1.
Nešifrované http:// AXIOM Ω povolí jen pro lokální síť (192.168.x.x, 10.x.x.x, *.local, Tailscale).</div></details></div>

    <div class="card"><div class="row"><span class="engine-ic">C</span><div class="grow"><h3>Claude · Anthropic</h3><div class="meta">${k.claude ? 'KLÍČ ULOŽEN' : 'BEZ KLÍČE'}</div></div></div>
      <label>API klíč Anthropic</label><input type="password" id="key-claude" autocomplete="off" placeholder="${k.claude ? 'Uloženo · prázdné = ponechat' : 'sk-ant-…'}">
      <label>Model</label><input type="text" data-text="claudeModel" value="${esc(st.claudeModel)}">
      <div id="models-claude"></div>
      <div class="grid2"><button class="btn" data-save-key="claude">Uložit klíč</button><button class="btn" data-test="claude">Ověřit spojení</button></div>
      <button class="link" data-open="https://console.anthropic.com/settings/keys">Získat klíč na console.anthropic.com ↗</button></div>

    <div class="card"><h3>Myšlení</h3>
      <label>Max. průchodů RANDY (cloud)</label>${seg('maxPasses', [[1, '1'], [2, '2'], [3, '3']])}
      <label>Max. průchodů RANDY (mozek v telefonu)</label>${seg('localMaxPasses', [[1, '1 · rychle'], [2, '2'], [3, '3 · důkladně']])}
      <label>Kontext lokálního mozku (tokeny)</label>${seg('ctxTokens', [[2048, '2k'], [4096, '4k'], [8192, '8k'], [16384, '16k']])}
      <label>Rozhodčí Konzilia</label>${seg('councilJudge', [['claude', 'Claude'], ['kimi', 'Kimi K3'], ['server', 'Server'], ['local', 'Lokální']])}
      ${sw('reflect', 'Sebereflexe', 'Po odpovědi se ohodnotím (jistota) a zapíšu poučení do paměti.')}
      ${sw('web', 'Webové vyhledávání', 'Claude smí hledat na webu a citovat zdroje.')}
      ${sw('thinking', 'Hluboké přemýšlení v telefonu', 'Qwen3/3.5 nejdřív přemýšlí (<think>). Přesnější, ale pomalejší.')}</div>

    <div class="card"><h3>Vzhled a já</h3>
      <label>Motiv</label>${seg('theme', [['obsidian', 'Obsidian'], ['platinum', 'Platinum'], ['auto', 'Podle systému']])}
      <label>Velikost písma · ${st.font}px</label><input type="range" min="13" max="21" value="${st.font}" data-range="font" style="width:100%">
      <label>Jak ti mám říkat</label><input type="text" data-text="userName" value="${esc(st.userName)}" placeholder="jméno nebo přezdívka"></div>

    <button class="btn danger wide" data-forget-keys>Odstranit všechny API klíče</button>
    <p class="small mono" style="text-align:center;margin-top:18px">${esc(S.name)} ${esc(S.version)} · KURZY ${esc(st.rates)}<br>KLÍČE ŠIFROVÁNY ANDROID KEYSTORE · NIKDY NEOPUSTÍ TELEFON MIMO VOLÁNÍ API</p>
    <button class="link" style="display:block;margin:0 auto" data-open="https://github.com/daidken5-droid/Moje-axiom-omega">Zdrojový kód ↗</button>
  </div>`;
}

function configure(obj) {
  call('configure', JSON.stringify(obj));
}

/* ------------------------------------------------------------------ události z Kotlinu */

window.omegaReceive = function (type, d) {
  switch (type) {
    case 'phase':
      if (!live) live = { text: '', reasoning: '', pass: 0, passes: 0 };
      live.phase = d.text;
      updateLive();
      syncSend();
      refresh();
      break;
    case 'stream':
      if (!live) live = { phase: '', text: '', reasoning: '' };
      Object.assign(live, { text: d.text, reasoning: d.reasoning, pass: d.pass, passes: d.passes });
      updateLive();
      break;
    case 'pass':
      break;
    case 'done':
    case 'error':
      live = null;
      if (d.message) toast(d.message);
      if (d.threadId && (d.threadId === threadId || !threadId)) loadThread(d.threadId);
      if (view === 'chat') render(); else refresh();
      break;
    case 'progress':
      S.job = d;
      refresh();
      if (view === 'brains') renderBrains();
      break;
    case 'brains':
      refresh();
      if (view === 'brains') renderBrains();
      break;
    case 'models': {
      const box = $('#models-' + d.provider);
      if (box) {
        box.innerHTML = `<label>Dostupné modely (${d.models.length})</label><select>${d.models.map(m => `<option>${esc(m)}</option>`).join('')}</select>`;
        const key = { kimi: 'kimiModel', server: 'serverModel', claude: 'claudeModel' }[d.provider];
        box.querySelector('select').onchange = e => { configure({ [key]: e.target.value }); };
      }
      break;
    }
    case 'note':
      toast(d.text);
      break;
    case 'config':
      S = d;
      refresh();
      if (view === 'system' || view === 'brains') render();
      break;
    case 'state':
    case 'resume':
      refresh();
      if (S.job && S.job.busy && S.job.kind === 'ask') {
        live = live || { phase: S.job.phase, text: S.job.partial, reasoning: S.job.reasoning, pass: S.job.pass, passes: S.job.passes };
        if (S.job.threadId && S.job.threadId !== threadId) loadThread(S.job.threadId);
      } else if (live) {
        live = null;
        loadThread(threadId);
      }
      if (type === 'resume' || view !== 'chat') render();
      break;
    case 'kb':
    case 'mem':
      if (view === 'memory') renderMemory();
      break;
    case 'shared':
      draft = d.text;
      go('chat');
      break;
  }
};

window.omegaBack = function () {
  if ($('#sheet').innerHTML) { closeSheet(); return true; }
  if ($('#drawer').classList.contains('open')) { closeDrawer(); return true; }
  if (view !== 'chat') { go('chat'); return true; }
  return false;
};

/* ------------------------------------------------------------------ kliky (delegace) */

document.addEventListener('click', e => {
  const t = e.target.closest('button, a, [data-open]');
  if (!t) return;
  const ds = t.dataset;
  if (ds.open) { e.preventDefault(); call('open', ds.open); return; }
  if (ds.go) { closeSheet(); go(ds.go); return; }
  if (ds.suggest) { send(ds.suggest); return; }
  if (ds.mode) { configure({ mode: ds.mode }); closeSheet(); refresh(); if (view === 'brains') renderBrains(); return; }
  if (ds.levelPick !== undefined) { levelSheet(); return; }
  if (ds.level !== undefined) { forcedLevel = ds.level || null; closeSheet(); $('#preview') && ($('#preview').innerHTML = previewHtml(draft.trim() ? J('preview', draft) : null)); return; }
  if (ds.facts) { sheet(`<div class="eyebrow">STEP-0 · SPOČÍTÁ KÓD PŘED MODELEM</div>${JSON.parse(ds.facts).map(f => `<div class="card mono small">${esc(f)}</div>`).join('')}`); return; }
  if (ds.thread) { loadThread(Number(ds.thread)); live = null; closeDrawer(); go('chat'); return; }
  if (ds.newThread !== undefined) { threadId = 0; msgs = []; live = null; closeDrawer(); go('chat'); return; }
  if (ds.delThread) { if (confirm('Smazat vlákno?')) { call('deleteThread', Number(ds.delThread)); if (Number(ds.delThread) === threadId) { threadId = 0; msgs = []; } openDrawer(); if (view === 'chat') renderChat(); } return; }
  if (ds.copy) { const m = msgs.find(x => x.id === Number(ds.copy)); m && call('copy', m.content); return; }
  if (ds.share) { const m = msgs.find(x => x.id === Number(ds.share)); m && call('share', m.content + '\n\n— AXIOM Ω'); return; }
  if (ds.rate) {
    const m = msgs.find(x => x.id === Number(ds.rate));
    if (m) { call('rate', m.id, ds.good === '1', (m.meta && m.meta.lesson) || ''); m.meta = Object.assign({}, m.meta, { rated: ds.good === '1' ? 'good' : 'bad' }); renderChat(); }
    return;
  }
  if (ds.retry) { const i = msgs.findIndex(x => x.id === Number(ds.retry)); const u = msgs.slice(0, i).reverse().find(x => x.role === 'user'); u && send(u.content); return; }
  if (ds.cancel !== undefined) { call('cancel'); return; }
  if (ds.brainDownload) { call('brainDownload', ds.brainDownload); setTimeout(() => { refresh(); renderBrains(); }, 400); return; }
  if (ds.brainActivate) { call('brainActivate', ds.brainActivate); return; }
  if (ds.brainDelete) { if (confirm('Smazat mozek z telefonu?')) call('brainDelete', ds.brainDelete); return; }
  if (ds.brainImport !== undefined) { call('brainImport'); return; }
  if (ds.brainRefresh !== undefined) { call('brainRefresh'); return; }
  if (ds.memtab) { memTab = ds.memtab; renderMemory(); return; }
  if (ds.memAdd !== undefined) { const v = $('#memText').value.trim(); if (v) { call('memAdd', $('#memKind').value, v); toast('Zapamatováno.'); } return; }
  if (ds.gemPrompt !== undefined) { call('memImportCopyPrompt'); return; }
  if (ds.gemText !== undefined) { const v = $('#gemText').value.trim(); if (!v) return toast('Vlož odpověď z Gemini.'); call('memImportText', v); $('#gemText').value = ''; return; }
  if (ds.gemFile !== undefined) { call('memImportFile'); return; }
  if (ds.memDel) { call('memDelete', Number(ds.memDel)); return; }
  if (ds.memClear !== undefined) { if (confirm('Opravdu vymazat celou paměť?')) call('memClear'); return; }
  if (ds.packInstall) { toast('Instaluji do lokální databáze…'); call('packInstall', ds.packInstall); return; }
  if (ds.packRemove) { if (confirm('Odebrat balíček z databáze?')) call('packRemove', ds.packRemove); return; }
  if (ds.packRefresh !== undefined) { toast('Kontroluji katalog…'); call('packRefresh'); return; }
  if (ds.dbExport !== undefined) { call('dbExport'); return; }
  if (ds.dbImport !== undefined) { if (confirm('Obnova přepíše současná vlákna, paměť a znalosti. Pokračovat?')) call('dbImport'); return; }
  if (ds.kbImport !== undefined) { call('kbImport'); return; }
  if (ds.kbAdd !== undefined) { const ti = $('#kbTitle').value.trim(), tx = $('#kbText').value.trim(); if (!ti || !tx) return toast('Vyplň název i text.'); call('kbAdd', ti, tx); return; }
  if (ds.kbDel) { if (confirm('Smazat dokument?')) call('kbDelete', Number(ds.kbDel)); return; }
  if (ds.saveKey) { const el = $('#key-' + ds.saveKey); if (el.value.trim()) { call('saveKey', ds.saveKey, el.value.trim()); el.value = ''; } else toast('Vlož klíč.'); return; }
  if (ds.test) { toast('Ověřuji spojení…'); call('testProvider', ds.test); return; }
  if (ds.setv) { const v = ds.val; configure({ [ds.setv]: /^\d+$/.test(v) ? Number(v) : v }); return; }
  if (ds.forgetKeys !== undefined) { if (confirm('Odstranit všechny API klíče?')) call('forgetKeys'); return; }
  if (ds.onboard) {
    configure({ onboarded: true });
    closeSheet();
    if (ds.onboard === 'brain') go('brains');
    else if (ds.onboard === 'kimi' || ds.onboard === 'claude') { go('system'); setTimeout(() => { const el = $('#key-' + ds.onboard); el && (el.scrollIntoView({ block: 'center' }), el.focus()); }, 50); }
    return;
  }
});

document.addEventListener('change', e => {
  const t = e.target;
  if (t.dataset.set) configure({ [t.dataset.set]: t.checked });
  if (t.dataset.text) configure({ [t.dataset.text]: t.value });
  if (t.dataset.range) configure({ [t.dataset.range]: Number(t.value) });
});

$('#btnThreads').onclick = openDrawer;
$('#drawerBg').onclick = closeDrawer;
$('#engineChip').onclick = () => (S.job && S.job.busy && S.job.kind === 'download') ? go('brains') : modeSheet();

/* ------------------------------------------------------------------ start */

(function start() {
  refresh();
  nav();
  const threads = J('threads') || [];
  if (S.job && S.job.busy && S.job.kind === 'ask' && S.job.threadId) loadThread(S.job.threadId);
  else if (threads.length) loadThread(threads[0].id);
  render();
  if (!(S.settings || {}).onboarded) welcomeSheet();
  setInterval(() => { if (S.job && S.job.busy) refresh(); }, 2000);
})();
