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
  agent: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/><circle cx="12" cy="12" r="3.2"/></svg>',
  copy: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/></svg>',
  share: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 15V3M7 8l5-5 5 5M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"/></svg>',
  trash: '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
};


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


const MODES = [
  { id: 'auto', ic: 'Ω', name: 'Auto', desc: 'Myslí mozek v telefonu; AXIOM sám volí hloubku, nástroje a agenty.' },
  { id: 'local', ic: 'Φ', name: 'V telefonu', desc: 'Jen lokální mozek – úplně offline a soukromé.' },
  { id: 'node', ic: 'N', name: 'AXIOM Node', desc: 'Tvůj zdarma cloud server (HF Space / Oracle).' },
  { id: 'server', ic: 'LAN', name: 'Vlastní server', desc: 'K3 nebo jiný model v domácí síti.' },
  { id: 'claude', ic: 'C', name: 'Claude', desc: 'Volitelně přes tvé Anthropic API.' },
  { id: 'council', ic: '⚖', name: 'Konzilium', desc: 'Víc mozků + rozhodčí.' },
];
const KINDS = [
  { id: '', name: 'Automaticky', desc: 'Odpověď, nebo agent s nástroji – podle zadání.' },
  { id: 'chat', name: 'Odpověď', desc: 'Rychlá odpověď s RANDY průchody.' },
  { id: 'agent', name: 'Aktivní agent', desc: 'Sám plánuje, hledá na webu, počítá a ukládá.' },
];
const ROLES = [
  { id: '', name: 'Auto' }, { id: 'RESEARCHER', name: 'Rešeršér' }, { id: 'ANALYST', name: 'Analytik' }, { id: 'PLANNER', name: 'Plánovač' },
  { id: 'WRITER', name: 'Pisatel' }, { id: 'SECURITY', name: 'Bezpečnost' }, { id: 'TUTOR', name: 'Učitel' },
];
let agentRole = '';
const SUGGEST = [
  'Kurz 2,10, odhad 55 %, bankroll 10 000. Vsadit?',
  'Kolik materiálu na 24 m2 mikrocementu?',
  '/agent Najdi nejnovější open-source modely pro telefon a porovnej je',
  'Jak funguje tepelné čerpadlo?',
  'L4: Najdi slabiny v plánu otevřít e-shop s kartami',
];

let S = {};
let view = 'chat';
let threadId = 0;
let msgs = [];
let live = null;
let forcedKind = '';
let forcedLevel = null;
let memTab = 'mem';
let draft = '';
let hf = { q: '', models: null, repo: null, files: null };

/* ------------------------------------------------------------------ základ */

function toast(t) {
  const el = $('#toast');
  el.textContent = t;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 4200);
}

function busy(kind) { return !!(S.job && S.job.busy && (!kind || S.job.kind === kind)); }
function thinking() { return !!live || busy('ask') || busy('agent'); }

function refresh() {
  S = J('state') || S;
  const st = S.settings || {};
  document.body.classList.toggle('platinum', st.theme === 'platinum' || (st.theme === 'auto' && matchMedia('(prefers-color-scheme: light)').matches));
  document.documentElement.style.fontSize = (st.font || 15) + 'px';
  const mode = MODES.find(m => m.id === st.mode) || MODES[0];
  let chip = mode.name.toUpperCase();
  if (busy('download')) chip = 'STAHUJI ' + Math.max(0, S.job.progress) + ' %';
  else if (busy('index')) chip = 'INDEX ' + Math.max(0, S.job.progress) + ' %';
  else if (busy('library')) chip = 'KNIHOVNA ' + Math.max(0, S.job.progress) + ' %';
  else if (thinking()) chip = 'MYSLÍM';
  $('#engineText').textContent = chip;
  $('#engineDot').className = 'dot ' + (S.job && S.job.busy ? 'busy' : S.online || (S.engines || []).includes('local') ? 'on' : 'off');
  $('#brand').innerHTML = `${logo(S.job && S.job.busy ? 'pulse' : '')}<div><b>AXIOM Ω</b><small>${esc(subtitle())}</small></div>`;
  $('#brand svg').style.cssText = 'width:30px;height:30px;flex:none';
  syncSend();
}

function subtitle() {
  if (S.job && S.job.busy) return (S.job.phase || 'PRACUJI').toUpperCase().slice(0, 48);
  const n = (S.engines || []).length;
  return (S.online ? 'ONLINE' : 'OFFLINE') + ' · ' + (n ? n + (n === 1 ? ' MOZEK' : n < 5 ? ' MOZKY' : ' MOZKŮ') : 'BEZ MOZKU');
}

function syncSend() {
  const btn = $('#sendBtn');
  if (!btn) return;
  const b = thinking();
  if (btn.classList.contains('stop') === b) return;
  btn.classList.toggle('stop', b);
  btn.innerHTML = b ? ICONS.stop : ICONS.send;
}

function nav() {
  const tabs = [['chat', logo('mono'), 'Chat'], ['agents', ICONS.agent, 'Agenti'], ['modules', ICONS.brains, 'Moduly'], ['memory', ICONS.memory, 'Paměť'], ['system', ICONS.system, 'Nastavení']];
  $('#tabs').innerHTML = tabs.map(([id, ic, label]) => `<button data-go="${id}" class="${view === id ? 'on' : ''}">${ic}<span>${label}</span></button>`).join('');
}

function go(v) { view = v; nav(); render(); $('#main').scrollTop = 0; }

function render() {
  refresh();
  $('#main').className = view === 'chat' ? 'chat' : '';
  ({ chat: renderChat, agents: renderAgents, modules: renderModules, memory: renderMemory, system: renderSystem }[view] || renderChat)();
  $('#composerSlot').innerHTML = view === 'chat' ? composer() : '';
  if (view === 'chat') bindComposer();
}

const secs = ms => ms ? (ms >= 60000 ? Math.round(ms / 6000) / 10 + ' min' : Math.round(ms / 100) / 10 + ' s') : '';
const card = (inner, cls = '') => `<div class="card ${cls}">${inner}</div>`;
const head = (eyebrow, title) => `<div class="eyebrow">${eyebrow}</div><h1>${title}</h1>`;

/* ------------------------------------------------------------------ chat */

function loadThread(id) { threadId = id || 0; msgs = threadId ? (J('messages', threadId) || []) : []; }

function renderChat() {
  const main = $('#main');
  if (!msgs.length && !live) {
    const ready = (S.engines || []).length > 0;
    main.innerHTML = `<div class="wrap"><section class="hero">
      <div class="orbit">${appIcon()}</div>
      <h1 style="margin-top:22px">Na co myslíš?</h1>
      ${ready ? '' : `<div class="card glow" style="text-align:left"><p style="margin:0 0 10px">Stáhni mi mozek do telefonu – pak myslím úplně offline.</p>
        <button class="btn primary wide" data-go="modules">Otevřít Moduly</button></div>`}
      <div class="suggest">${SUGGEST.map(s => `<button data-suggest="${esc(s)}">${esc(s.replace(/^\/agent /, '⚙ '))}</button>`).join('')}</div>
    </section></div>`;
    return;
  }
  main.innerHTML = `<div class="wrap">${msgs.map(msgHtml).join('')}${live ? liveHtml() : ''}</div>`;
  requestAnimationFrame(() => { main.scrollTop = main.scrollHeight; });
}

function confHtml(c) {
  if (c === null || c === undefined) return '';
  return `<span class="conf"><span class="bar"><i style="width:${c}%"></i></span>${c} %</span>`;
}

function stepsHtml(steps) {
  return (steps || []).map(st => `<div class="step"><b>${st.index}. ${st.tool ? esc(st.tool) : 'závěr'}</b> ${esc(st.thought || '')}
    ${st.observation ? `<div class="obs">${esc(String(st.observation).slice(0, 600))}</div>` : ''}</div>`).join('');
}

function msgHtml(m) {
  if (m.role === 'user') return `<div class="msg user"><div class="bubble">${esc(m.content)}</div></div>`;
  const meta = m.meta || {};
  if (meta.error) {
    return `<div class="msg ai error"><div class="head">${logo()}<span class="meta">CHYBA</span></div><div class="body">${esc(m.content)}</div>
      <div class="tools"><button data-retry="${m.id}">Zkusit znovu</button><button data-go="modules">Moduly</button></div></div>`;
  }
  const agent = meta.mode === 'agent';
  const label = agent ? `AGENT · ${(meta.steps || []).length} KROKŮ` : (meta.level ? `${meta.level} · ${meta.levelTitle || ''}` : '');
  const headTxt = [label, meta.engine, secs(meta.millis)].filter(Boolean).join(' · ');
  const det = [];
  if (meta.route) det.push(`<p class="small"><b>Proč takto:</b> ${esc(meta.route)}</p>`);
  if (agent) det.push(stepsHtml(meta.steps));
  if (meta.facts && meta.facts.length) det.push(`<div class="sub">STEP-0</div>` + meta.facts.map(f => `<div class="fact">${esc(f)}</div>`).join(''));
  if (meta.trace && meta.trace.length > 1) det.push(`<div class="sub">PRŮCHODY RANDY</div>` + meta.trace.map(t => `<p class="small"><b>${t.index}. ${esc(t.label)}</b> · ${secs(t.millis)}</p>`).join(''));
  if (meta.sources && meta.sources.length) det.push(`<div class="sub">ZDROJE</div>` + meta.sources.map(s => `<button class="src" data-open="${esc(s.url)}">↗ ${esc(s.title)}</button>`).join(''));
  const memo = [...(meta.memories || []), ...(meta.notes || [])];
  if (memo.length) det.push(`<div class="sub">PAMĚŤ</div><p class="small">${memo.map(esc).join('<br>')}</p>`);
  if (meta.reasoning) det.push(`<div class="sub">ÚVAHA</div><p class="small" style="white-space:pre-wrap">${esc(meta.reasoning.slice(0, 3000))}</p>`);
  return `<div class="msg ai" data-id="${m.id}">
    <div class="head">${logo()}<span class="meta grow">${esc(headTxt)}</span>${confHtml(meta.confidence)}</div>
    <div class="body answer">${md(m.content)}</div>
    <div class="tools">
      <button data-copy="${m.id}" aria-label="Kopírovat">${ICONS.copy}</button><button data-share="${m.id}" aria-label="Sdílet">${ICONS.share}</button>
      <button data-rate="${m.id}" data-good="1" class="${meta.rated === 'good' ? 'on' : ''}">✓</button><button data-rate="${m.id}" data-good="0" class="${meta.rated === 'bad' ? 'on bad' : ''}">✗</button>
      ${det.length ? `<details class="more"><summary>Podrobnosti</summary><div class="in">${det.join('')}</div></details>` : ''}
    </div></div>`;
}

function liveHtml() {
  return `<div class="msg ai live" id="live">
    <div class="head">${logo('pulse')}<span class="meta grow" id="livePhase">${esc(live.phase || 'Přemýšlím…')}</span></div>
    <div id="liveSteps">${(live.steps || []).map(st => `<div class="step done"><b>✓ ${st.tool ? esc(st.tool) : 'závěr'}</b> ${esc(st.thought || '')}</div>`).join('')}</div>
    <div class="think" id="liveThink">${esc((live.reasoning || '').slice(-300))}</div>
    <div class="body answer caret" id="liveText">${md(live.text || '')}</div></div>`;
}

function updateLive() {
  if (view !== 'chat' || !live) return;
  if (!$('#live')) { renderChat(); return; }
  $('#livePhase').textContent = live.phase || '';
  $('#liveSteps').innerHTML = (live.steps || []).map(st => `<div class="step done"><b>✓ ${st.tool ? esc(st.tool) : 'závěr'}</b> ${esc(st.thought || '')}</div>`).join('');
  $('#liveThink').textContent = (live.reasoning || '').slice(-300);
  $('#liveText').innerHTML = md(live.text || '');
  const main = $('#main');
  if (main.scrollHeight - main.scrollTop - main.clientHeight < 240) main.scrollTop = main.scrollHeight;
}

function composer() {
  const k = KINDS.find(x => x.id === forcedKind);
  const chip = (forcedKind ? k.name : 'Ω Auto') + (forcedLevel ? ' · ' + forcedLevel : '');
  return `<div class="composer"><div class="wrap">
    <div class="box">
      <button class="kind ${forcedKind || forcedLevel ? 'on' : ''}" data-kind-pick>${esc(chip)}</button>
      <textarea id="input" rows="1" maxlength="32000" placeholder="Zeptej se nebo zadej úkol…">${esc(draft)}</textarea>
      <button class="send ${thinking() ? 'stop' : ''}" id="sendBtn" aria-label="Odeslat">${thinking() ? ICONS.stop : ICONS.send}</button>
    </div></div></div>`;
}

function bindComposer() {
  const ta = $('#input');
  const grow = () => { ta.style.height = 'auto'; ta.style.height = Math.min(170, ta.scrollHeight) + 'px'; };
  grow();
  ta.addEventListener('input', () => { draft = ta.value; grow(); });
  ta.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(ta.value); } });
  $('#sendBtn').onclick = () => thinking() ? call('cancel') : send(ta.value);
}

function send(text, kind, role) {
  text = (text || '').trim();
  if (!text) return;
  refresh();
  if (S.job && S.job.busy) return toast('Právě pracuji – počkej nebo mě zastav.');
  const id = call('send', JSON.stringify({ threadId, text, level: forcedLevel || '', kind: kind ?? forcedKind, role: role || '' }));
  if (!id || id < 0) return;
  draft = '';
  forcedLevel = null;
  loadThread(id);
  live = { phase: 'Rozhoduji, jak na to…', text: '', reasoning: '', steps: [] };
  if (view !== 'chat') { view = 'chat'; nav(); }
  render();
}

/* ------------------------------------------------------------------ agenti */

function renderAgents() {
  const tasks = J('tasks') || [];
  const st = { running: ['badge core', 'BĚŽÍ'], done: ['badge good', 'HOTOVO'], partial: ['badge warn', 'ČÁSTEČNĚ'], failed: ['badge warn', 'CHYBA'], stopped: ['badge', 'ZASTAVENO'] };
  $('#main').innerHTML = `<div class="wrap">${head('AKTIVNÍ AGENTI', 'Zadej cíl.<br>Zbytek udělám sám.')}
    ${card(`<div class="chips">${ROLES.map(r => `<button class="chipbtn ${agentRole === r.id ? 'on' : ''}" data-role="${r.id}">${r.name}</button>`).join('')}</div>
      <textarea id="goal" rows="3" placeholder="${agentRole === 'SECURITY' ? 'Např. Zkontroluj zabezpečení mého webu mojefirma.cz (vlastním ho) – hlavičky, TLS, DNS, SPF/DMARC, subdomény.' : 'Např. Najdi 3 dodavatele mikrocementu v ČR, porovnej ceny a ulož shrnutí.'}"></textarea>
      <button class="btn primary wide" data-agent-start>Spustit agenta</button>
      ${agentRole === 'SECURITY' ? '<p class="small muted" style="margin-bottom:0">Jen pasivní průzkum a obranné audity. Testuj pouze systémy, které vlastníš nebo máš písemné povolení.</p>' : ''}`)}
    <div class="eyebrow">ÚKOLY</div>
    ${tasks.length ? tasks.map(t => `<div class="card row task" data-thread="${t.threadId}">
      <div class="grow"><b>${esc(t.goal)}</b><div class="meta"><span class="${(st[t.status] || st.stopped)[0]}">${(st[t.status] || st.stopped)[1]}</span> · ${t.steps} KROKŮ · ${esc(t.engine)}</div>
      ${t.result ? `<p class="small" style="margin:6px 0 0">${esc(t.result.slice(0, 160))}</p>` : ''}</div>
      <button class="icon-btn" data-task-del="${t.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('') : '<div class="empty">Zatím žádné úkoly.</div>'}
  </div>`;
}

/* ------------------------------------------------------------------ moduly */

function renderModules() {
  const data = J('brains') || { brains: [] };
  const st = S.settings || {};
  const k = S.keys || {};
  const job = S.job || {};
  const chat = data.brains.filter(b => b.kind !== 'embedding');
  const sense = data.brains.filter(b => b.kind === 'embedding');
  $('#main').innerHTML = `<div class="wrap">${head('MODULY', 'Mozky a rozšíření.')}
    <div class="seg" id="modeSeg">${MODES.map(m => `<button data-mode="${m.id}" class="${st.mode === m.id ? 'on' : ''}">${m.name}</button>`).join('')}</div>
    <p class="small">${esc((MODES.find(m => m.id === st.mode) || MODES[0]).desc)}</p>

    <div class="eyebrow">MOZEK V TELEFONU · ${esc(data.ramGb)} GB RAM · VOLNO ${esc(data.freeGb)}</div>
    ${S.native === false ? card('<p class="small" style="margin:0">Toto sestavení nemá nativní jádro – mozek v telefonu poběží až ve verzi z GitHub Actions.</p>') : ''}
    ${chat.map(b => brainCard(b, job)).join('')}

    <div class="eyebrow">ROZŠÍŘENÍ</div>
    ${sense.map(b => brainCard(b, job, `Indexováno: ${(J('packs') || { stats: {} }).stats.vectors || 0} úseků`)).join('')}

    <div class="eyebrow">HUGGING FACE</div>
    ${card(`<div class="row"><input type="search" id="hfq" placeholder="Hledat GGUF model (např. gemma 4)" value="${esc(hf.q)}"><button class="btn sm" data-hf-search>Hledat</button></div>
      <div id="hfres">${hfHtml()}</div>
      <details class="x" style="margin-top:12px"><summary>VLASTNÍ ODKAZ NEBO SOUBOR</summary><div class="in" style="white-space:normal">
        <input type="url" id="ggufUrl" placeholder="https://…/model.gguf"><button class="btn sm wide" data-gguf-url>Stáhnout z odkazu</button>
        <button class="btn sm wide" data-brain-import>Vybrat .gguf v telefonu</button></div></details>`)}

    <div class="eyebrow">PŘIPOJENÍ (VOLITELNÉ)</div>
    ${conn('kimi', 'K3', 'Kimi K3 (volitelné)', k.kimi, `
      <label>API klíč (platform.moonshot.ai)</label><input type="password" id="key-kimi" autocomplete="off" placeholder="${k.kimi ? 'Uloženo' : 'sk-…'}">
      <label>Model</label><input type="text" data-text="kimiModel" value="${esc(st.kimiModel)}">
      <div id="models-kimi"></div>
      <div class="grid2"><button class="btn sm" data-save-key="kimi">Uložit</button><button class="btn sm" data-test="kimi">Ověřit</button></div>
      <button class="link" data-open="https://platform.moonshot.ai/console/api-keys">Získat klíč ↗</button>`)}
    ${conn('node', 'N', 'AXIOM Node · free cloud', !!st.nodeUrl, `
      <p class="small">Tvůj vlastní zdarma server (Hugging Face Space nebo Oracle Cloud) – další mozek a výpočetní síla. Návod: složka <span class="mono">server/</span> v repozitáři.</p>
      <label>Adresa</label><input type="url" data-text="nodeUrl" value="${esc(st.nodeUrl)}" placeholder="https://uzivatel-axiom-node.hf.space/v1">
      <label>Model</label><input type="text" data-text="nodeModel" value="${esc(st.nodeModel)}">
      <label>Klíč (AXIOM_API_KEY)</label><input type="password" id="key-node" autocomplete="off" placeholder="${k.node ? 'Uloženo' : ''}">
      <div id="models-node"></div>
      <div class="grid2"><button class="btn sm" data-save-key="node">Uložit</button><button class="btn sm" data-test="node">Ověřit</button></div>
      <button class="link" data-open="https://huggingface.co/new-space">Vytvořit Space na Hugging Face ↗</button>`)}
    ${conn('server', 'LAN', 'Vlastní server v síti', !!st.serverUrl, `
      <label>Adresa</label><input type="url" data-text="serverUrl" value="${esc(st.serverUrl)}" placeholder="http://192.168.1.50:8080/v1">
      <label>Model</label><input type="text" data-text="serverModel" value="${esc(st.serverModel)}">
      <label>Klíč (volitelně)</label><input type="password" id="key-server" autocomplete="off" placeholder="${k.server ? 'Uloženo' : ''}">
      <div id="models-server"></div>
      <div class="grid2"><button class="btn sm" data-save-key="server">Uložit</button><button class="btn sm" data-test="server">Ověřit</button></div>`)}
    ${conn('claude', 'C', 'Claude (volitelné)', k.claude, `
      <p class="small">AXIOM na Claude nezávisí – je to jen další volitelný mozek.</p>
      <label>API klíč</label><input type="password" id="key-claude" autocomplete="off" placeholder="${k.claude ? 'Uloženo' : 'sk-ant-…'}">
      <label>Model</label><input type="text" data-text="claudeModel" value="${esc(st.claudeModel)}">
      <div id="models-claude"></div>
      <div class="grid2"><button class="btn sm" data-save-key="claude">Uložit</button><button class="btn sm" data-test="claude">Ověřit</button></div>`)}
  </div>`;
}

function conn(id, ic, title, ok, body) {
  return `<details class="card conn"><summary class="row"><span class="engine-ic">${ic}</span><b class="grow">${title}</b><span class="dot ${ok ? 'on' : ''}"></span></summary><div>${body}</div></details>`;
}

function brainCard(b, job, extra) {
  const downloading = job.busy && job.kind === 'download' && (job.phase || '').includes(b.name);
  const pct = downloading ? Math.max(0, job.progress) : b.partial && b.sizeBytes ? Math.floor(b.partial * 100 / b.sizeBytes) : 0;
  const badges = [b.active ? '<span class="badge good">AKTIVNÍ</span>' : '', b.recommended && !b.installed ? '<span class="badge core">DOPORUČENO</span>' : '',
    b.fits === false ? '<span class="badge warn">MÁLO RAM</span>' : '', b.verified === false ? '<span class="badge">NEOVĚŘENO</span>' : ''].join(' ');
  let actions;
  if (downloading) actions = `<button class="btn sm danger" data-cancel>Pozastavit</button>`;
  else if (b.installed) actions = (b.kind === 'embedding' ? `<button class="btn sm" data-index>Přeindexovat</button>` : (b.active ? '' : `<button class="btn sm primary" data-brain-activate="${esc(b.id)}">Použít</button>`)) + `<button class="btn sm danger" data-brain-delete="${esc(b.id)}">Smazat</button>`;
  else actions = `<button class="btn sm primary" data-brain-download="${esc(b.id)}">${b.partial ? 'Pokračovat' : 'Stáhnout'} · ${esc(b.size)}</button>`;
  return `<div class="card brain ${b.active ? 'glow' : ''}"><h3>${esc(b.name)} ${badges}</h3>
    <p class="small">${esc(b.description)}</p>${extra ? `<div class="meta">${esc(extra)}</div>` : ''}
    ${downloading || b.partial ? `<div class="progress"><i style="width:${pct}%"></i></div><div class="meta">${downloading ? esc(job.detail) : pct + ' % – naváže'}</div>` : ''}
    <div class="row" style="margin-top:10px;gap:8px">${actions}</div></div>`;
}

function hfHtml() {
  if (hf.files) {
    return `<div class="row" style="margin-top:10px"><b class="grow small">${esc(hf.repo)}</b><button class="link" data-hf-back>zpět</button></div>` +
      (hf.files.length ? hf.files.map(f => `<div class="row hfrow"><span class="grow small mono">${esc(f.quant)} · ${esc(f.sizeLabel)}${f.fits ? '' : ' · málo RAM'}</span>
        <button class="btn sm" data-hf-get="${esc(f.path)}" data-kind="chat">Stáhnout</button></div>`).join('') : '<p class="small">Žádné GGUF soubory.</p>');
  }
  if (hf.models) {
    return hf.models.length ? hf.models.map(m => `<button class="row hfrow" data-hf-repo="${esc(m.id)}" style="width:100%;text-align:left"><span class="grow small">${esc(m.id)}</span><span class="meta">${Number(m.downloads).toLocaleString('cs-CZ')} ↓</span></button>`).join('') : '<p class="small">Nic nenalezeno.</p>';
  }
  return '';
}

/* ------------------------------------------------------------------ paměť a znalosti */

function renderMemory() {
  const tabs = `<div class="seg" style="margin:4px 0 12px"><button data-memtab="mem" class="${memTab === 'mem' ? 'on' : ''}">Paměť</button><button data-memtab="kb" class="${memTab === 'kb' ? 'on' : ''}">Offline databáze</button></div>`;
  if (memTab === 'mem') {
    const items = J('memList') || [];
    $('#main').innerHTML = `<div class="wrap">${head('PAMĚŤ', 'Co si pamatuju.')}${tabs}
      ${card(`<div class="row"><input type="text" id="memText" placeholder="Zapamatuj si…"><button class="btn sm" data-mem-add>Uložit</button></div>`)}
      <details class="x card" style="padding:0" id="geminiImport"><summary style="padding:14px 16px">IMPORT PAMĚTI Z GEMINI</summary><div class="in" style="white-space:normal;padding:0 16px 16px">
        <button class="btn sm wide" data-gem-prompt>1 · Zkopírovat dotaz pro Gemini</button>
        <textarea id="gemText" rows="4" placeholder="2 · Vlož sem odpověď Gemini nebo seznam Uložených informací"></textarea>
        <button class="btn primary wide" data-gem-text>Importovat</button>
        <div class="grid2" style="margin-top:8px"><button class="btn sm" data-gem-file>Soubor z Takeoutu</button><button class="btn sm" data-open="https://takeout.google.com/">Google Takeout ↗</button></div>
      </div></details>
      ${card(items.length ? items.map(m => `<div class="mem"><span class="badge">${esc(m.label)}</span><span class="t">${esc(m.text)}</span>
        <button class="icon-btn" data-mem-del="${m.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('') : '<div class="empty" style="border:0">Řekni „pamatuj si…“ a já si to zapíšu.</div>')}
      ${items.length ? '<button class="btn danger wide" data-mem-clear>Vymazat paměť</button>' : ''}</div>`;
    return;
  }
  const d = J('packs') || { packs: [], stats: {} };
  const s = d.stats || {};
  const mb = s.bytes ? (s.bytes / 1048576).toFixed(1) + ' MB' : '—';
  const docs = J('kbList') || [];
  const lib = J('libraries') || { libraries: [], bytes: 0 };
  $('#main').innerHTML = `<div class="wrap">${head('OFFLINE DATABÁZE', 'Vědění v telefonu.')}${tabs}
    <div class="grid2"><div class="stat"><b>${s.docs ?? 0}</b><small>DOKUMENTŮ</small></div><div class="stat"><b>${s.vectors ?? 0}</b><small>SÉMANTICKY INDEXOVÁNO</small></div></div>
    <input type="search" id="kbq" placeholder="Hledat v databázi" style="margin-top:12px"><div id="kbres"></div>

    <div class="eyebrow">OFFLINE KNIHOVNA · ${((lib.bytes || 0) / 1e9).toFixed(2)} GB</div>
    ${lib.libraries.map(l => { const dl = busy('library') && (S.job.phase || '').includes(l.name); return card(`<b>${esc(l.name)}</b> ${l.done ? '<span class="badge good">OFFLINE</span>' : ''}
      <p class="small">${esc(l.description)} ~${l.approxGb} GB · ${esc(l.license)}</p>
      ${l.started && !l.done || dl ? `<div class="progress"><i style="width:${dl ? S.job.progress : l.percent}%"></i></div><div class="meta">${dl ? esc(S.job.detail) : l.percent + ' % – naváže'}</div>` : ''}
      <div class="row" style="gap:8px;margin-top:8px">${dl ? '<button class="btn sm danger" data-cancel>Pozastavit</button>' : l.done ? '' : `<button class="btn sm primary" data-lib-start="${l.id}">${l.started ? 'Pokračovat' : 'Stáhnout'}</button>`}
      ${l.started && !dl ? `<button class="icon-btn" data-lib-remove="${l.id}" aria-label="Smazat">${ICONS.trash}</button>` : ''}</div>`); }).join('')}

    <div class="eyebrow">PŘIDAT ZNALOSTI</div>
    ${card(`<div class="row"><input type="text" id="wikiTopic" placeholder="Téma z Wikipedie (např. tepelné čerpadlo)"><button class="btn sm" data-wiki>Stáhnout</button></div>
      <p class="small muted">Stáhne až 10 článků k tématu (CC BY-SA) – pak fungují offline.</p>
      <div class="row" style="margin-top:8px"><input type="text" id="dsName" placeholder="Dataset z Hugging Face, např. wikimedia/wikipedia"><button class="btn sm" data-dataset>Stáhnout</button></div>
      <div class="row" style="margin-top:8px"><input type="text" id="dsConfig" placeholder="config (např. 20231101.cs)"><input type="text" id="dsQuery" placeholder="filtr (volitelně)"></div>
      <button class="btn wide" data-kb-import>Vložit soubory (TXT, MD, CSV, JSON, JSONL, ZIP)</button>`)}

    <div class="eyebrow">BALÍČKY · ${d.packs.filter(p => p.installed).length}/${d.packs.length}</div>
    ${d.packs.map(p => `<div class="row pack"><span class="grow"><b>${esc(p.name)}</b><br><span class="small muted">${esc(p.description)}</span></span>
      ${!p.installed || p.update ? `<button class="btn sm" data-pack-install="${esc(p.id)}">${p.update ? 'Aktualizovat' : 'Přidat'}</button>` : `<button class="icon-btn" data-pack-remove="${esc(p.id)}" aria-label="Odebrat">${ICONS.trash}</button>`}</div>`).join('')}
    <button class="link" data-pack-refresh>Zkontrolovat nové balíčky</button>

    <div class="eyebrow">ZÁLOHA · ${mb}</div>
    ${card(`<div class="grid2"><button class="btn sm" data-db-export>Do souboru</button><button class="btn sm" data-db-import>Obnovit soubor</button></div>
      <label>Cloud zdarma – soukromý dataset na Hugging Face</label>
      <input type="password" id="key-hf" autocomplete="off" placeholder="${(S.keys || {}).hf ? 'HF token uložen' : 'HF token (write)'}">
      <div class="grid2" style="margin-top:8px"><button class="btn sm" data-save-key="hf">Uložit token</button><button class="btn sm" data-open="https://huggingface.co/settings/tokens">Získat token ↗</button></div>
      <div class="grid2" style="margin-top:8px"><button class="btn sm primary" data-cloud-backup>Zálohovat do cloudu</button><button class="btn sm" data-cloud-restore>Obnovit z cloudu</button></div>
      ${(S.settings || {}).hfRepo ? `<p class="small muted">Dataset: ${esc(S.settings.hfRepo)}</p>` : ''}`)}

    <div class="eyebrow">DOKUMENTY · ${docs.length}</div>
    ${docs.slice(0, 60).map(x => `<div class="row pack"><span class="grow small">${esc(x.title)}<br><span class="muted">${esc(x.source)}</span></span>
      <button class="icon-btn" data-kb-del="${x.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('')}
  </div>`;
  $('#kbq').addEventListener('input', e => {
    clearTimeout(renderMemory.t);
    renderMemory.t = setTimeout(() => {
      const r = [...(J('kbSearch', e.target.value) || []), ...(J('librarySearch', e.target.value) || [])];
      $('#kbres').innerHTML = r.slice(0, 12).map(x => card(`<b>${esc(x.title)}</b><p class="small">${esc(x.text.slice(0, 300))}</p>`)).join('');
    }, 250);
  });
}

/* ------------------------------------------------------------------ nastavení */

function renderSystem() {
  const st = S.settings || {};
  const sw = (key, title, sub) => `<div class="toggle"><span>${title}<small>${sub}</small></span><label class="switch"><input type="checkbox" data-set="${key}" ${st[key] ? 'checked' : ''}><i></i></label></div>`;
  const seg = (key, vals) => `<div class="seg">${vals.map(([v, l]) => `<button data-setv="${key}" data-val="${v}" class="${String(st[key]) === String(v) ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  $('#main').innerHTML = `<div class="wrap">${head('NASTAVENÍ', 'Jak mám myslet.')}
    ${card(`${sw('reflect', 'Sebereflexe', 'Po odpovědi se ohodnotím a zapíšu poučení.')}
      ${sw('semantic', 'Hledání podle významu', 'Používá rozšíření OMEGA Sense, je-li stažené.')}
      ${sw('web', 'Web u Claude', 'Claude smí hledat na webu.')}
      ${sw('thinking', 'Hluboké přemýšlení v telefonu', 'Přesnější, ale pomalejší.')}`)}
    ${card(`<label style="margin-top:0">Kroky agenta (max.)</label>${seg('agentSteps', [[5, '5'], [10, '10'], [15, '15'], [25, '25']])}
      <label>Průchody RANDY · cloud / telefon</label><div class="grid2">${seg('maxPasses', [[1, '1'], [2, '2'], [3, '3']])}${seg('localMaxPasses', [[1, '1'], [2, '2'], [3, '3']])}</div>
      <label>Kontext mozku v telefonu</label>${seg('ctxTokens', [[4096, '4k'], [8192, '8k'], [16384, '16k']])}
      <label>Rozhodčí Konzilia</label>${seg('councilJudge', [['kimi', 'Kimi'], ['node', 'Node'], ['local', 'Telefon'], ['claude', 'Claude']])}`)}
    ${card(`<label style="margin-top:0">Motiv</label>${seg('theme', [['obsidian', 'Obsidian'], ['platinum', 'Platinum'], ['auto', 'Systém']])}
      <label>Písmo · ${st.font}px</label><input type="range" min="13" max="21" value="${st.font}" data-range="font" style="width:100%">
      <label>Jak ti mám říkat</label><input type="text" data-text="userName" value="${esc(st.userName)}">`)}
    <button class="btn wide" data-self>Sebemodel AXIOM Ω</button>
    <button class="btn danger wide" data-forget-keys>Odstranit všechny klíče</button>
    <p class="small mono muted" style="text-align:center;margin-top:16px">${esc(S.name)} ${esc(S.version)} · kurzy ${esc(st.rates)}</p>
    <button class="link" style="display:block;margin:0 auto" data-open="https://github.com/daidken5-droid/Moje-axiom-omega">Zdrojový kód ↗</button>
  </div>`;
}

function selfSheet() {
  const me = J('self') || {};
  const stat = (v, l) => `<div class="stat"><b>${v ?? '—'}</b><small>${l}</small></div>`;
  sheet(`<div style="text-align:center"><div style="width:84px;height:84px;margin:4px auto 0">${appIcon()}</div><h2 style="margin-top:10px">Jsem ${esc(me.name)}</h2>
    <p class="small">${esc(me.describe)}</p></div>
    <div class="grid2">${stat(me.battery != null ? me.battery + ' %' : null, 'BATERIE')}${stat(me.ramFreeMb != null ? (me.ramFreeMb / 1024).toFixed(1) + ' GB' : null, 'VOLNÁ RAM')}
      ${stat(me.memory, 'VZPOMÍNEK')}${stat(me.confidence != null ? me.confidence + ' %' : null, 'SEBEDŮVĚRA')}</div>
    <p class="small muted" style="margin-top:12px">Moje sebeuvědomění je funkční: znám svůj stav a limity, pamatuju si a učím se z hodnocení. Nepředstírám vědomí ani city.</p>
    <details class="x"><summary>RANDY L0–L6</summary><div class="in" style="white-space:normal">${(me.levels || []).map(l => `<p class="small"><b>${l.code} ${esc(l.title)}</b> – ${esc(l.label)}</p>`).join('')}</div></details>`);
}

/* ------------------------------------------------------------------ listy a šuplík */

function sheet(html) {
  $('#sheet').innerHTML = `<div class="sheet-bg" id="sheetBg"><div class="sheet"><div class="grab"></div>${html}</div></div>`;
  $('#sheetBg').addEventListener('click', e => { if (e.target.id === 'sheetBg') closeSheet(); });
}
function closeSheet() { $('#sheet').innerHTML = ''; }

function kindSheet() {
  const lv = ['L0', 'L1', 'L2', 'L3', 'L4', 'L5', 'L6'];
  sheet(`<div class="eyebrow" style="margin-top:4px">JAK PRACOVAT</div>` +
    KINDS.map(k => `<button class="opt ${forcedKind === k.id ? 'on' : ''}" data-kind="${k.id}"><span class="grow"><b>${k.name}</b><small>${k.desc}</small></span></button>`).join('') +
    `<div class="eyebrow">HLOUBKA RANDY</div><div class="seg"><button data-level="" class="${!forcedLevel ? 'on' : ''}">Auto</button>${lv.map(l => `<button data-level="${l}" class="${forcedLevel === l ? 'on' : ''}">${l}</button>`).join('')}</div>`);
}

function modeSheet() {
  const cur = (S.settings || {}).mode;
  const avail = S.engines || [];
  sheet(`<div class="eyebrow" style="margin-top:4px">MOZEK</div>` + MODES.map(m => `
    <button class="opt ${m.id === cur ? 'on' : ''}" data-mode="${m.id}"><span class="ic">${m.ic}</span><span class="grow"><b>${m.name}</b><small>${m.desc}</small></span>
    ${m.id !== 'auto' && m.id !== 'council' ? `<span class="dot ${avail.includes(m.id) ? 'on' : ''}"></span>` : ''}</button>`).join(''));
}

function welcomeSheet() {
  sheet(`<div style="text-align:center;padding:6px 0 4px"><div style="width:96px;height:96px;margin:0 auto">${appIcon()}</div>
    <h2 style="margin-top:12px">AXIOM Ω</h2><p>Samostatná AI s mozkem v telefonu.</p></div>
    <button class="opt" data-onboard="brain"><span class="ic">Φ</span><span class="grow"><b>V telefonu (offline)</b><small>Doporučím nejsilnější Gemma 4, která se vejde do tvého telefonu.</small></span></button>
    <button class="opt" data-onboard="node"><span class="ic">N</span><span class="grow"><b>AXIOM Node (free cloud)</b><small>Vlastní server zdarma.</small></span></button>
    <button class="btn ghost wide" data-onboard="later">Později</button>`);
}

function openDrawer() {
  const threads = J('threads') || [];
  $('#drawer').innerHTML = `<div class="row" style="padding:14px 14px 6px">${logo()}<b style="letter-spacing:3px" class="grow">VLÁKNA</b>
      <button class="btn sm" data-new-thread>${ICONS.plus} Nové</button></div>
    <div class="list">${threads.length ? threads.map(t => `<div class="row"><button class="thread grow ${t.id === threadId ? 'on' : ''}" data-thread="${t.id}"><b>${esc(t.title)}</b><small>${esc(t.preview || '')}</small></button>
      <button class="icon-btn" data-del-thread="${t.id}" aria-label="Smazat">${ICONS.trash}</button></div>`).join('') : '<div class="empty" style="margin:10px">Zatím žádná vlákna.</div>'}</div>`;
  $('#drawer svg.logo').style.cssText = 'width:24px;height:24px';
  $('#drawer').classList.add('open');
  $('#drawerBg').classList.add('open');
}
function closeDrawer() { $('#drawer').classList.remove('open'); $('#drawerBg').classList.remove('open'); }

function configure(obj) { call('configure', JSON.stringify(obj)); }

/* ------------------------------------------------------------------ události z Kotlinu */

window.omegaReceive = function (type, d) {
  switch (type) {
    case 'phase':
      if (!live) live = { text: '', reasoning: '', steps: [] };
      live.phase = d.text; updateLive(); refresh(); break;
    case 'stream':
      if (!live) live = { phase: '', steps: [] };
      Object.assign(live, { text: d.text, reasoning: d.reasoning }); updateLive(); break;
    case 'step':
      if (!live) live = { phase: '', text: '', steps: [] };
      live.steps = [...(live.steps || []), d]; live.text = ''; updateLive(); break;
    case 'done':
    case 'error':
      live = null;
      if (d.message) toast(d.message);
      if (d.threadId && (d.threadId === threadId || !threadId)) loadThread(d.threadId);
      if (view === 'chat' || view === 'agents') render(); else refresh();
      break;
    case 'progress':
      S.job = d; refresh();
      if (view === 'modules') renderModules();
      if (view === 'memory' && memTab === 'kb' && !document.activeElement?.matches('input,textarea')) renderMemory();
      break;
    case 'brains': refresh(); if (view === 'modules') renderModules(); break;
    case 'hf_models': hf.models = d.models; hf.files = null; if (view === 'modules') $('#hfres').innerHTML = hfHtml(); break;
    case 'hf_files': hf.repo = d.repo; hf.files = d.files; if (view === 'modules') $('#hfres').innerHTML = hfHtml(); break;
    case 'models': {
      const box = $('#models-' + d.provider);
      if (box) {
        box.innerHTML = `<label>Modely (${d.models.length})</label><select>${d.models.map(m => `<option>${esc(m)}</option>`).join('')}</select>`;
        const key = { kimi: 'kimiModel', server: 'serverModel', claude: 'claudeModel', node: 'nodeModel' }[d.provider];
        box.querySelector('select').onchange = e => configure({ [key]: e.target.value });
      }
      break;
    }
    case 'note': toast(d.text); break;
    case 'config': S = d; refresh(); if (view === 'system') renderSystem(); break;
    case 'tasks': if (view === 'agents') renderAgents(); break;
    case 'state':
    case 'resume':
      refresh();
      if (thinkingJob()) {
        live = live || { phase: S.job.phase, text: S.job.partial, reasoning: S.job.reasoning, steps: [] };
        if (S.job.threadId && S.job.threadId !== threadId) loadThread(S.job.threadId);
      } else if (live) { live = null; loadThread(threadId); }
      if (type === 'resume' || view !== 'chat') render();
      break;
    case 'kb': case 'mem': if (view === 'memory') renderMemory(); if (view === 'modules') renderModules(); break;
    case 'shared': draft = d.text; go('chat'); break;
  }
};
function thinkingJob() { return busy('ask') || busy('agent'); }

window.omegaBack = function () {
  if ($('#sheet').innerHTML) { closeSheet(); return true; }
  if ($('#drawer').classList.contains('open')) { closeDrawer(); return true; }
  if (view !== 'chat') { go('chat'); return true; }
  return false;
};

/* ------------------------------------------------------------------ kliky */

document.addEventListener('click', e => {
  const t = e.target.closest('button, a, [data-open], [data-thread]');
  if (!t) return;
  const ds = t.dataset;
  const val = id => ($('#' + id) || {}).value?.trim() || '';
  if (ds.open) { e.preventDefault(); call('open', ds.open); return; }
  if (ds.go) { closeSheet(); go(ds.go); return; }
  if (ds.suggest) { send(ds.suggest); return; }
  if (ds.mode) { configure({ mode: ds.mode }); closeSheet(); refresh(); if (view === 'modules') renderModules(); return; }
  if (ds.kindPick !== undefined) { kindSheet(); return; }
  if (ds.kind !== undefined && t.classList.contains('opt')) { forcedKind = ds.kind; closeSheet(); render(); return; }
  if (ds.level !== undefined) { forcedLevel = ds.level || null; closeSheet(); render(); return; }
  if (ds.self !== undefined) { selfSheet(); return; }
  if (ds.thread) { if (ds.taskDel) return; loadThread(Number(ds.thread)); live = null; closeDrawer(); go('chat'); return; }
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
  if (ds.agentStart !== undefined) { const g = val('goal'); if (!g) return toast('Napiš cíl.'); threadId = 0; msgs = []; send(g, 'agent', agentRole); return; }
  if (ds.role !== undefined && t.classList.contains('chipbtn')) { agentRole = ds.role; renderAgents(); return; }
  if (ds.libStart) { call('libraryStart', ds.libStart); setTimeout(() => { refresh(); renderMemory(); }, 500); return; }
  if (ds.libRemove) { if (confirm('Smazat knihovnu z telefonu?')) call('libraryRemove', ds.libRemove); return; }
  if (ds.taskDel) { e.stopPropagation(); call('deleteTask', Number(ds.taskDel)); renderAgents(); return; }
  if (ds.cancel !== undefined) { call('cancel'); return; }
  if (ds.brainDownload) { call('brainDownload', ds.brainDownload); setTimeout(() => { refresh(); renderModules(); }, 500); return; }
  if (ds.brainActivate) { call('brainActivate', ds.brainActivate); return; }
  if (ds.brainDelete) { if (confirm('Smazat z telefonu?')) call('brainDelete', ds.brainDelete); return; }
  if (ds.brainImport !== undefined) { call('brainImport'); return; }
  if (ds.index !== undefined) { call('indexStart'); return; }
  if (ds.hfSearch !== undefined) { hf.q = val('hfq'); toast('Hledám na Hugging Face…'); call('hfSearch', hf.q); return; }
  if (ds.hfRepo) { hf.repo = ds.hfRepo; toast('Načítám soubory…'); call('hfFiles', ds.hfRepo); return; }
  if (ds.hfBack !== undefined) { hf.files = null; $('#hfres').innerHTML = hfHtml(); return; }
  if (ds.hfGet) { if (confirm('Stáhnout ' + ds.hfGet + '?')) { call('hfDownload', hf.repo, ds.hfGet, ds.kind || 'chat'); toast('Stahování začalo.'); } return; }
  if (ds.ggufUrl !== undefined) { const u = val('ggufUrl'); if (!u) return toast('Vlož odkaz.'); call('brainFromUrl', u); toast('Ověřuji odkaz…'); return; }
  if (ds.memtab) { memTab = ds.memtab; renderMemory(); return; }
  if (ds.memAdd !== undefined) { const v = val('memText'); if (v) { call('memAdd', 'FACT', v); toast('Zapamatováno.'); } return; }
  if (ds.memDel) { call('memDelete', Number(ds.memDel)); return; }
  if (ds.memClear !== undefined) { if (confirm('Opravdu vymazat celou paměť?')) call('memClear'); return; }
  if (ds.gemPrompt !== undefined) { call('memImportCopyPrompt'); return; }
  if (ds.gemText !== undefined) { const v = val('gemText'); if (!v) return toast('Vlož odpověď z Gemini.'); call('memImportText', v); $('#gemText').value = ''; return; }
  if (ds.gemFile !== undefined) { call('memImportFile'); return; }
  if (ds.wiki !== undefined) { const v = val('wikiTopic'); if (!v) return toast('Zadej téma.'); call('wikiImport', v, 'cs', 10); return; }
  if (ds.dataset !== undefined) { const v = val('dsName'); if (!v) return toast('Zadej dataset.'); call('datasetImport', v, val('dsConfig'), 'train', 200, val('dsQuery')); return; }
  if (ds.packInstall) { toast('Přidávám do databáze…'); call('packInstall', ds.packInstall); return; }
  if (ds.packRemove) { if (confirm('Odebrat balíček?')) call('packRemove', ds.packRemove); return; }
  if (ds.packRefresh !== undefined) { call('packRefresh'); return; }
  if (ds.dbExport !== undefined) { call('dbExport'); return; }
  if (ds.dbImport !== undefined) { if (confirm('Obnova přepíše vlákna, paměť a znalosti. Pokračovat?')) call('dbImport'); return; }
  if (ds.cloudBackup !== undefined) { toast('Nahrávám zálohu…'); call('cloudBackup'); return; }
  if (ds.cloudRestore !== undefined) { if (confirm('Obnovit z cloudu? Přepíše současná data.')) call('cloudRestore'); return; }
  if (ds.kbImport !== undefined) { call('kbImport'); return; }
  if (ds.kbDel) { if (confirm('Smazat dokument?')) call('kbDelete', Number(ds.kbDel)); return; }
  if (ds.saveKey) { const el = $('#key-' + ds.saveKey); if (el && el.value.trim()) { call('saveKey', ds.saveKey, el.value.trim()); el.value = ''; } else toast('Vlož klíč.'); return; }
  if (ds.test) { toast('Ověřuji…'); call('testProvider', ds.test); return; }
  if (ds.setv) { const v = ds.val; configure({ [ds.setv]: /^\d+$/.test(v) ? Number(v) : v }); return; }
  if (ds.forgetKeys !== undefined) { if (confirm('Odstranit všechny klíče?')) call('forgetKeys'); return; }
  if (ds.onboard) {
    configure({ onboarded: true });
    closeSheet();
    if (ds.onboard !== 'later') { go('modules'); }
    if (ds.onboard === 'node') setTimeout(() => { const d = [...document.querySelectorAll('details.conn')][1]; if (d) { d.open = true; d.scrollIntoView({ block: 'center' }); } }, 80);
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
$('#engineChip').onclick = () => busy('library') ? (memTab = 'kb', go('memory')) : (busy('download') || busy('index')) ? go('modules') : modeSheet();
$('#brand').onclick = selfSheet;

(function start() {
  refresh();
  nav();
  const threads = J('threads') || [];
  if (thinkingJob() && S.job.threadId) loadThread(S.job.threadId);
  else if (threads.length) loadThread(threads[0].id);
  render();
  if (!(S.settings || {}).onboarded) welcomeSheet();
  setInterval(() => { if (S.job && S.job.busy) refresh(); }, 2000);
})();
