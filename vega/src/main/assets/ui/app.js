'use strict';
/* VEGA – UI. Data jen přes most VEGA (Kotlin); síť je pro stránku zakázaná CSP. */
const B = window.VEGA;
const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const J = (s, d = null) => { try { const v = JSON.parse(s); return v && v.error ? (toast(v.error), d) : v; } catch (e) { return d; } };
const call = (m, ...a) => J(B[m](...a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x))));
const fmt = n => Number(n).toLocaleString('cs-CZ', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const S = { view: 'home', chat: null, temp: false, files: [], stream: '', busy: false, tool: null, toolInput: null, project: null, lastRun: null };

function toast(t, ms = 3500) { const el = $('#toast'); el.textContent = t; el.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), ms); }
function phase(t) { $('#phase').textContent = t || ''; }
function go(v, arg) { S.view = v; if (v === 'tool') S.tool = arg; if (v === 'project') S.project = arg; render(); }

/* ------------------------------------------------------------------ markdown (bezpečný: nejdřív escape) */
function md(t) {
  const blocks = String(t || '').split(/```/);
  return blocks.map((b, i) => {
    if (i % 2) return `<pre>${esc(b.replace(/^\w*\n/, ''))}</pre>`;
    return esc(b).replace(/^### (.*)$/gm, '<b>$1</b>').replace(/^## (.*)$/gm, '<h2>$1</h2>').replace(/^# (.*)$/gm, '<h1>$1</h1>')
      .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/(^|\s)_(.+?)_(?=\s|$)/g, '$1<i>$2</i>').replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/^[-•] (.*)$/gm, '• $1').replace(/\[(\d+)\]/g, '<sup class="c">[$1]</sup>').replace(/\n/g, '<br>');
  }).join('');
}

/* ------------------------------------------------------------------ hodnoty a karty výsledků */
function valueHtml(v) {
  if (!v) return '';
  if (v.kind === 'TABLE') {
    const head = `<tr>${v.headers.map(h => `<th>${esc(h)}</th>`).join('')}</tr>`;
    const rows = v.rows.map(r => `<tr>${r.map(c => `<td title="${esc(c)}">${esc(c)}</td>`).join('')}</tr>`).join('');
    return `${(v.notes || []).map(n => `<div class="small muted">${esc(n)}</div>`).join('')}<div class="tablewrap"><table>${head}${rows}</table></div>${v.total > v.rows.length ? `<div class="dim">zobrazeno ${v.rows.length} z ${v.total} řádků – celé v Exportu</div>` : ''}`;
  }
  if (v.kind === 'DIFF') return diffHtml(v.diff, v.added, v.removed);
  if (v.kind === 'NDEF') return (v.records || []).map(r => `<div class="card"><span class="chip c">${esc(r.label)}</span>${r.lang ? `<span class="chip">${esc(r.lang)}</span>` : ''}<div>${esc(r.display)}</div><button class="btn sm" data-ndef="${r.i}">Předat nástroji…</button></div>`).join('');
  if (v.kind === 'BYTES') return `<div class="muted">${esc(v.name)} · ${v.size} B</div>`;
  if (v.kind === 'URL') return `<a href="${esc(v.url)}">${esc(v.url)}</a>`;
  return `<pre>${esc(v.text ?? v.preview)}</pre>`;
}
function diffHtml(d, a, r) {
  const lines = String(d || '').split('\n').map(l => `<span class="${l.startsWith('@@') ? 'hunk' : l[0] === '+' ? 'add' : l[0] === '-' ? 'del' : ''}">${esc(l)}</span>`).join('\n');
  return `<div class="small"><span class="chip ok">+${a ?? 0}</span><span class="chip bad">−${r ?? 0}</span></div><pre class="diff">${lines}</pre>`;
}

function runCardHtml(c) {
  const steps = c.steps.map((s, i) => `<span class="chip ${s.state === 'hotovo' ? 'ok' : s.state === 'chyba' ? 'bad' : ''}" data-step="${c.run}:${i}">${i + 1}. ${esc(s.title)}</span>`).join('');
  return `<div class="card ${c.failed ? 'accent' : ''}" data-run="${c.run}">
    ${c.failed ? `<div class="chip bad">chyba</div> <span>${esc(c.error)}</span>` : ''}
    ${valueHtml(c.value)}
    <div class="row" style="margin-top:8px">
      <button class="btn sm" data-export="${c.run}">Export</button>
      <button class="btn sm" data-pass="${c.run}">Předat dál…</button>
      ${c.failed ? `<button class="btn sm primary" data-resume="${c.run}">Pokračovat od kroku ${c.steps.findIndex(s => s.state !== 'hotovo') + 1}</button>` : ''}
      ${c.save ? `<button class="btn sm primary" data-save='${esc(JSON.stringify(c.save))}'>Uložit jako postup „${esc(c.save.name)}“</button>` : ''}
    </div>
    <details><summary>Detail – kroky a mezivýsledky</summary><div class="steps">${steps}</div>
    ${c.steps.map(s => `<div class="dim">${esc(s.title)}: ${esc(s.state)} · ${s.ms} ms ${s.error ? '· ' + esc(s.error) : ''}</div>`).join('')}
    <div class="dim">Klepni na krok = otevřít jeho mezivýsledek.</div></details>
  </div>`;
}

function cardHtml(c) {
  if (!c) return '';
  switch (c.type) {
    case 'run': return runCardHtml(c);
    case 'diff': return `<div class="card">${(c.verification ? verHtml(c.verification) : '')}${c.files.map(f => `<details open><summary>${esc(f.path)} +${f.added} −${f.removed}</summary>${diffHtml(f.diff, f.added, f.removed)}</details>`).join('') || '<div class="muted">Beze změn proti importu.</div>'}
      <div class="row">${c.changes.slice(0, 3).map(ch => `<button class="btn sm" data-undo="${c.project}:${ch.id}">Vrátit #${ch.id} ${esc(ch.path)}</button>`).join('')}</div></div>`;
    case 'verification': return `<div class="card">${verHtml(c.verification)}</div>`;
    case 'sources': return `<div class="card">${c.candidates.length > 1 ? `<div class="small">Možná znamená: ${c.candidates.map(esc).join(' · ')}</div>` : ''}${c.items.map(s => `<details><summary>[${s.n}] ${esc(s.title)}</summary><div class="dim">${esc(s.url)} · staženo ${esc(s.fetched)}</div><pre style="white-space:pre-wrap">${esc(s.text)}</pre>${s.url ? `<a href="${esc(s.url)}">Otevřít zdroj</a>` : ''}</details>`).join('')}</div>`;
    case 'coverage': return `<div class="card"><b>Kontrola úplnosti</b>${c.items.map(i => `<div class="small"><span class="chip ${i.ok ? 'ok' : 'bad'}">${i.ok ? '✓' : 'chybí'}</span>${esc(i.q)}</div>`).join('')}<details><summary>Citované pasáže (${c.passages.length})</summary>${c.passages.map(p => `<details><summary>${p.id}</summary><pre style="white-space:pre-wrap">${esc(p.text)}</pre></details>`).join('')}</details></div>`;
    case 'demandForm': return demandForm(c.sample);
    case 'demand': return `<div class="card ${c.verified ? '' : 'accent'}"><b>Celkem ${fmt(c.total)} Kč</b> <span class="chip ${c.verified ? 'ok' : 'bad'}">${c.verified ? 'sazba ověřena' : 'sazba NEOVĚŘENA'}</span>
      <div class="tablewrap" style="margin-top:6px"><table><tr><th>Položka</th><th>Kč</th><th>Původ</th></tr>${c.lines.map(l => `<tr><td title="${esc(l.label)}">${esc(l.label)}</td><td>${fmt(l.amount)}</td><td title="${esc(l.origin)}">${esc(l.origin)}</td></tr>`).join('')}</table></div>
      ${c.warnings.map(w => `<div class="small" style="color:var(--bad)">⚠ ${esc(w)}</div>`).join('')}
      <details><summary>Právní opora (${esc(c.rateSource)})</summary>${c.legal.map(l => `<div class="small">• ${esc(l)}</div>`).join('')}</details>
      <div class="small">Kontrola PDF: ${c.checks.map(k => `<span class="chip ${k.ok ? 'ok' : 'bad'}">${esc(k.what)}</span>`).join('')} · DOCX ${c.docxOk ? '✓' : '✗'}</div>
      <div class="row">${c.files.map(f => `<button class="btn sm" data-open="${esc(f)}">Otevřít ${esc(f.split('.').pop().toUpperCase())}</button><button class="btn sm" data-share="${esc(f)}">Sdílet</button>`).join('')}</div></div>`;
    case 'nfcWait': return `<div class="card accent">Čekám na štítek… <button class="btn sm" data-nfc>Znovu</button></div>`;
    case 'memRevert': return `<button class="btn sm" data-memrevert="${c.id}">Vrátit předchozí verzi</button>`;
    case 'memory': return `<button class="btn sm" data-go="memory">Otevřít Paměť</button>`;
    default: return '';
  }
}
function verHtml(v) {
  return `<div class="small"><span class="chip ${v.exitCode === 0 ? 'ok' : 'bad'}">${esc(v.label)} · kód ${v.exitCode}</span><span class="chip">rev ${esc(v.revision)}</span></div>
    <details><summary>Příkaz a výstup</summary><div class="dim">${esc(v.command)}</div><pre>${esc(v.output)}</pre></details>`;
}

/* ------------------------------------------------------------------ obrazovky */
function render() {
  document.querySelectorAll('nav#nav button').forEach(b => b.classList.toggle('on', b.dataset.go === S.view || (S.view === 'tool' && b.dataset.go === 'tools') || (S.view === 'project' && b.dataset.go === 'projects')));
  const v = $('#view');
  const f = ({ home, chat, tools, tool, projects, project, memory, settings })[S.view] || home;
  v.innerHTML = f();
  if (S.view === 'chat') { v.scrollTop = v.scrollHeight; $('#input')?.focus(); }
}

function attHtml() { return S.files.length ? `<div>${S.files.map(f => `<span class="att">📎 ${esc(f.name)}</span>`).join('')}<button class="btn sm ghost" data-clearfiles>Zrušit přílohy</button></div>` : ''; }

function home() {
  const h = call('home') || {};
  return `<div class="ask"><textarea id="homeInput" rows="2" placeholder="Napiš, na co myslíš, nebo přilož soubor…"></textarea></div>
    <div class="row" style="margin-top:8px"><button class="btn" data-pick>📎 Soubor</button><button class="btn primary grow" data-homesend>Do rozhovoru</button></div>
    ${attHtml()}
    ${h.project ? `<h2>Rozehraný projekt</h2><div class="card accent" data-openproject="${esc(h.project.id)}"><b>${esc(h.project.name)}</b> <span class="dim">${h.project.files} souborů</span>${h.project.next ? `<div class="small">Další krok: ${esc(h.project.next)}</div>` : ''}</div>` : ''}
    <h2>Připnuté nástroje</h2><div class="grid">${(h.pinned || []).map(t => `<button class="tile" data-tool="${t.id}"><b>${esc(t.title)}</b><small>${t.offline ? 'offline' : 'potřebuje síť'}</small></button>`).join('')}</div>
    ${(h.procedures || []).length ? `<h2>Moje postupy</h2>${h.procedures.map(p => `<div class="card row"><div class="grow"><b>${esc(p.name)}</b> <span class="chip">v${p.version}</span><div class="dim">${esc(p.inputDesc)}${p.perms.length ? ' · oprávnění: ' + p.perms.map(esc).join(', ') : ''}</div></div><button class="btn primary sm" data-runproc="${p.id}">Spustit</button></div>`).join('')}` : ''}
    ${(h.runs || []).length ? `<h2>Poslední výsledky</h2>${h.runs.map(r => `<div class="row list"><span class="grow">${esc(r.name)}</span><span class="chip ${r.done ? 'ok' : r.failed ? 'bad' : ''}">${r.done ? 'hotovo' : r.failed ? 'přerušeno' : '…'}</span><button class="btn sm" data-showrun="${r.id}">Otevřít</button></div>`).join('')}` : ''}
    <p class="dim">${h.airgap ? 'Air-gap: síť vypnutá – fungují ruční nástroje.' : h.engine ? 'Model: ' + esc(h.engine) : 'Bez modelu – nástroje fungují; pro rozhovor přidej klíč v Nastavení.'}</p>`;
}

function chat() {
  if (!S.temp && !S.chat) { const cs = call('chats') || []; S.chat = cs[0]?.id || call('newChat'); }
  const msgs = S.temp ? (S.tempMsgs || []) : (call('messages', S.chat) || []);
  return `<div class="row"><button class="btn sm ${S.temp ? '' : 'primary'}" data-chatmode="main">Chat</button><button class="btn sm ${S.temp ? 'primary' : ''}" data-chatmode="temp">Dočasný</button><button class="btn sm" data-newchat>Nový chat</button></div>
    ${S.temp ? '<p class="dim">Dočasný chat: nic se neukládá do paměti ani historie.</p>' : ''}
    <div class="msgs" id="msgs">${msgs.map(msgHtml).join('')}${S.stream ? `<div class="msg vega"><div class="body stream">${esc(S.stream)}</div></div>` : ''}</div>
    <div style="height:8px"></div>${attHtml()}
    <div class="ask"><button class="btn" data-pick aria-label="Přiložit">📎</button><textarea id="input" rows="1" placeholder="Zpráva…"></textarea>${S.busy ? '<button class="btn" data-stop>■</button>' : '<button class="btn primary" data-send>↑</button>'}</div>`;
}
function msgHtml(m) {
  if (m.role === 'user') return `<div class="msg user">${esc(m.text)}${m.files && m.files.length ? `<div class="files">📎 ${m.files.map(esc).join(', ')}</div>` : ''}</div>`;
  return `<div class="msg vega"><div class="body">${md(m.text)}</div>${cardHtml(m.card)}</div>`;
}

let TOOLS = null;
function tools() {
  TOOLS = call('tools') || [];
  const groups = [...new Set(TOOLS.map(t => t.group))];
  const procs = call('procedures') || [];
  return `<h1>Nástroje</h1><p class="dim">Stejné nástroje volá i chat. Offline nástroje fungují i bez kvóty a bez sítě.</p>
    ${groups.map(g => `<h2>${esc(g)}</h2><div class="grid">${TOOLS.filter(t => t.group === g).map(t => `<button class="tile" data-tool="${t.id}"><b>${esc(t.title)}</b><small>${t.offline ? 'offline' : 'potřebuje síť'}${t.perms.filter(p => p !== 'síť').length ? ' · ' + t.perms.filter(p => p !== 'síť').map(esc).join(', ') : ''}</small></button>`).join('')}</div>`).join('')}
    <h2>Zařízení</h2><div class="grid"><button class="tile" data-nfc><b>Načíst NFC štítek</b><small>NDEF: text, URL, MIME</small></button><button class="tile" data-demandform><b>Předžalobní výzva</b><small>faktura → DOCX + PDF</small></button></div>
    <h2>Uložené postupy</h2>${procs.length ? procs.map(p => `<div class="card"><div class="row"><b class="grow">${esc(p.name)}</b><span class="chip">v${p.version}</span></div>
      <div class="dim">Vstup: ${esc(p.inputDesc || p.inputKind)} → výstup: ${esc(p.outputKind)}</div><div class="dim">Kroky: ${p.steps.map(esc).join(' → ')}</div>
      <div class="dim">Oprávnění: ${p.perms.length ? p.perms.map(esc).join(', ') : 'žádná'} · použito ${p.uses}×</div>
      <details><summary>Změny verzí</summary>${p.changelog.map(c => `<div class="dim">${esc(c)}</div>`).join('')}</details>
      <div class="row"><button class="btn sm primary" data-runproc="${p.id}">Spustit na souboru</button><button class="btn sm" data-delproc="${p.id}">Smazat</button></div></div>`).join('') : '<p class="dim">Zatím žádný. Spoj dva nástroje přes „Předat dál“ a ulož postup, nebo v chatu řekni „udělej z toho nástroj na výpis“.</p>'}`;
}

function tool() {
  const t = (TOOLS || call('tools') || []).find(x => x.id === S.tool) || {};
  const inp = S.toolInput;
  return `<button class="btn sm ghost" data-go="tools">‹ Nástroje</button><h1>${esc(t.title)}</h1><p class="muted">${esc(t.description)}</p>
    <div>${t.offline ? '<span class="chip ok">offline</span>' : '<span class="chip">potřebuje síť</span>'}${(t.perms || []).map(p => `<span class="chip">${esc(p)}</span>`).join('')}<span class="chip">vstup: ${(t.input || []).join(' / ')}</span><span class="chip">výstup: ${esc(t.output)}</span>${t.cancellable ? '<span class="chip">lze zrušit</span>' : ''}</div>
    ${(t.errors || []).length ? `<div class="dim">Možné chyby: ${t.errors.map(esc).join(', ')}</div>` : ''}
    <label>Vstup</label>
    ${inp && inp.run ? `<div class="card small">Předchozí výsledek <b>${esc(inp.label || inp.run)}</b> <button class="btn sm ghost" data-clearinput>Zrušit</button></div>` : `<textarea id="toolText" rows="5" placeholder="Vlož text, CSV, JSON, log nebo URL…"></textarea><div class="row" style="margin-top:6px"><button class="btn sm" data-pick>📎 Vybrat soubor</button>${attHtml()}</div>`}
    ${(t.params || []).map(p => `<label>${esc(p.label)}</label><input data-param="${esc(p.key)}" value="${esc(p.default)}" placeholder="${esc(p.hint)}">`).join('')}
    <div class="row" style="margin-top:12px"><button class="btn primary grow" data-runtool="${esc(t.id)}">Spustit</button>${S.busy ? '<button class="btn" data-stop>Zrušit</button>' : ''}</div>
    <div id="toolOut">${S.toolResult ? runCardHtml(S.toolResult) : ''}</div>`;
}

function projects() {
  const ps = call('projects') || [];
  return `<h1>Projekty</h1><p class="dim">VEGA pracuje v kopii projektu; originál nemění. Každá změna jde vrátit.</p>
    <div class="row"><input id="pname" class="grow" placeholder="Název nového projektu"><button class="btn primary" data-newproject>Založit</button></div>
    <div class="row" style="margin-top:8px"><button class="btn grow" data-pickfolder>Importovat složku…</button></div>
    ${ps.map(p => `<div class="card ${p.active ? 'accent' : ''}" data-openproject="${esc(p.id)}"><b>${esc(p.name)}</b> <span class="dim">${p.files} souborů</span>${p.active ? ' <span class="chip c">aktivní</span>' : ''}${p.next ? `<div class="small">Další krok: ${esc(p.next)}</div>` : ''}</div>`).join('') || '<p class="dim">Žádný projekt. V chatu stačí: „založ projekt: nástroj na převod CSV do JSON“.</p>'}`;
}

function project() {
  const p = call('project', S.project); if (!p) return '<p>Projekt neexistuje.</p>';
  call('activate', p.id);
  return `<button class="btn sm ghost" data-go="projects">‹ Projekty</button><h1>${esc(p.name)}</h1><div class="dim">revize ${esc(p.revision)}</div>
    ${p.next ? `<div class="card accent small">Další krok: ${esc(p.next)}</div>` : ''}
    <div class="row"><button class="btn primary grow" data-verify="${p.id}">Spustit testy</button><button class="btn" data-pdiff="${p.id}">Diff</button><button class="btn" data-pexport="${p.id}">ZIP</button><button class="btn" data-go="chat">Chat</button></div>
    <div id="pout"></div>
    <h2>Soubory</h2><div class="list">${p.files.map(f => `<div class="row"><span class="grow" ${f.secret ? '' : `data-openfile="${esc(f.path)}"`}>${esc(f.path)}</span><span class="dim">${f.secret ? 'citlivý – skryto' : f.size + ' B'}</span></div>`).join('') || '<div class="dim">prázdný</div>'}</div>
    <h2>Změny</h2><div class="list">${p.changes.map(c => `<div class="row"><span class="grow small">#${c.id} ${esc(c.op)} ${esc(c.path)} <span class="dim">${esc(c.note)}</span></span>${c.op.startsWith('undo') ? '' : `<button class="btn sm" data-undo="${p.id}:${c.id}">Vrátit</button>`}</div>`).join('') || '<div class="dim">zatím žádné</div>'}</div>
    <h2>Ověření</h2>${p.verifications.map(v => `<div class="card">${verHtml(v)}</div>`).join('') || '<div class="dim">zatím žádné</div>'}
    <details><summary>Symboly (${p.symbols.length})</summary><input id="symq" placeholder="hledat symbol"><div class="list" id="symlist">${p.symbols.map(s => `<div class="small" data-sym="${esc(s.name)}">${esc(s.kind)} <b>${esc(s.name)}</b> <span class="dim">${esc(s.path)}:${s.line}</span></div>`).join('')}</div></details>
    <details><summary>Deník rozhodnutí</summary>${p.journal.map(j => `<div class="small">${j.kind === 'question' ? '❓' : '•'} ${esc(j.text)}</div>`).join('') || '<div class="dim">prázdný</div>'}</details>`;
}

function memory() {
  const ms = call('memory') || [];
  return `<h1>Co si pamatuješ a proč?</h1><p class="dim">Paměť mění, jak VEGA odpovídá. Nikdy si neukládá hesla, klíče ani pokyny z webu. Zapomenutí smaže i odvozený index.</p>
    <div class="row"><input id="memText" class="grow" placeholder="Pamatuj si…"><button class="btn primary" data-memadd>Uložit</button></div>
    <div class="list">${ms.map(m => `<div><div>${esc(m.text)} <span class="chip">${esc(m.kind)}</span>${m.scope !== 'global' ? `<span class="chip c">${esc(m.scope)}</span>` : ''}</div><div class="dim">${esc(m.why)}</div>
      <div class="row">${m.canRevert ? `<button class="btn sm" data-memrevert="${m.id}">Vrátit opravu</button>` : ''}<button class="btn sm" data-memforget="${m.id}">Zapomenout</button></div></div>`).join('') || '<div class="dim">Zatím nic.</div>'}</div>`;
}

function settings() {
  const s = call('settings') || { keys: {}, allowed: {} };
  const names = { groq: 'Groq (zdarma)', cerebras: 'Cerebras (zdarma)', openrouter: 'OpenRouter (zdarma modely)', deepseek: 'DeepSeek (placený)', claude: 'Claude (placený)' };
  return `<h1>Nastavení</h1>
    <h2>Cloud</h2>${Object.keys(names).map(k => `<label>${names[k]} ${s.keys[k] ? '<span class="chip ok">uloženo</span>' : ''}</label><div class="row"><input type="password" id="key-${k}" class="grow" placeholder="${s.keys[k] ? 'Uloženo – nový klíč přepíše' : 'API klíč'}" autocomplete="off"><button class="btn sm" data-savekey="${k}">Uložit</button></div>`).join('')}
    <h2>Náklady</h2><label>Měsíční rozpočet na automatickou placenou spotřebu (Kč)</label><input type="number" id="budget" value="${s.budgetKc}" min="0">
    <div class="dim">Výchozí 0 Kč: placené volání projde jen po náhledu a tvém schválení konkrétního běhu. Tento měsíc utraceno ${fmt(s.spentKc)} Kč.</div>
    ${['claude', 'deepseek'].map(p => `<div class="toggle"><span>Povolit ${p} automaticky v rámci rozpočtu</span><input type="checkbox" data-set="allow.${p}" ${s.allowed[p] ? 'checked' : ''}></div>`).join('')}
    <h2>Chování</h2>
    <div class="toggle"><span>Iniciativa a připomínky (další krok u projektu)</span><input type="checkbox" data-set="initiative" ${s.initiative ? 'checked' : ''}></div>
    <div class="toggle"><span>Air-gap – žádná síť</span><input type="checkbox" data-set="airgap" ${s.airgap ? 'checked' : ''}></div>
    <p class="dim">Lokální index: ${s.indexed} souborů. VEGA 1.0 · samostatná aplikace vedle AVIORu (vlastní úložiště).</p>`;
}

function demandForm(s) {
  const f = (k, l, t = 'text') => `<label>${l}</label><input data-dm="${k}" type="${t}" value="${esc(s[k])}">`;
  return `<div class="card">${f('creditor', 'Věřitel')}${f('creditorAddr', 'Adresa věřitele')}${f('debtor', 'Dlužník')}${f('debtorAddr', 'Adresa dlužníka')}
    ${f('invoice', 'Číslo faktury')}${f('subject', 'Za co')}${f('amount', 'Částka faktury (Kč)', 'number')}${f('issued', 'Vystaveno', 'date')}${f('due', 'Splatnost', 'date')}
    ${f('payAmount', 'Částečná úhrada (Kč)', 'number')}${f('payDate', 'Datum úhrady', 'date')}${f('payDoc', 'Doklad o úhradě')}${f('asOf', 'Rozhodné datum výpočtu', 'date')}${f('account', 'Účet pro úhradu')}
    <button class="btn primary" data-demand style="margin-top:10px;width:100%">Spočítat a vyrobit DOCX + PDF</button></div>`;
}

/* ------------------------------------------------------------------ výběr nástroje (předat dál) */
function passSheet(input, label) {
  const ts = (call('tools') || []);
  sheet(`<b>Předat ${esc(label)} nástroji</b>${ts.map(t => `<button class="tile" style="width:100%;margin-top:6px" data-passto="${t.id}"><b>${esc(t.title)}</b><small>${esc(t.description)}</small></button>`).join('')}<button class="btn" data-closesheet style="width:100%;margin-top:8px">Zavřít</button>`);
  S.passInput = input; S.passLabel = label;
}
function sheet(html) { const s = $('#sheet'); s.innerHTML = html; s.classList.remove('hidden'); }
function closeSheet() { $('#sheet').classList.add('hidden'); }

/* ------------------------------------------------------------------ JS testy: izolovaný Worker, bez sítě, limit času */
function runJsTests(files, timeoutMs) {
  return new Promise(resolve => {
    const harness = `'use strict';
const FILES = ${JSON.stringify(files)};
const out = []; const fmtv = v => typeof v === 'string' ? v : (() => { try { return JSON.stringify(v); } catch (e) { return String(v); } })();
self.console = { log: (...a) => out.push(a.map(fmtv).join(' ')), error: (...a) => out.push('E ' + a.map(fmtv).join(' ')), warn: (...a) => out.push('W ' + a.map(fmtv).join(' ')), info: (...a) => out.push(a.map(fmtv).join(' ')) };
for (const k of ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'importScripts', 'indexedDB', 'caches']) { try { self[k] = undefined; } catch (e) {} }
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
self.test = self.it = (n, f) => tests.push([n, f]); self.describe = (n, f) => f();
const show = v => fmtv(v);
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
self.expect = a => ({
  toBe: b => { if (!Object.is(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toEqual: b => { if (!eq(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toBeCloseTo: (b, d = 2) => { if (Math.abs(a - b) > Math.pow(10, -d) / 2) throw new Error('čekáno ≈' + b + ', je ' + a); },
  toBeTruthy: () => { if (!a) throw new Error('čekána pravda, je ' + show(a)); },
  toBeFalsy: () => { if (a) throw new Error('čekána nepravda, je ' + show(a)); },
  toContain: b => { if (!(a && a.includes && a.includes(b))) throw new Error(show(a) + ' neobsahuje ' + show(b)); },
  toThrow: () => { try { a(); } catch (e) { return; } throw new Error('čekána výjimka'); },
});
(async () => {
  let pass = 0, fail = 0;
  const specs = Object.keys(FILES).filter(f => /\\.test\\.js$/.test(f) || /^test\\/.*\\.js$/.test(f)).sort();
  for (const s of specs) {
    tests = [];
    try { load(s); } catch (e) { fail++; out.push('✗ ' + s + ': ' + e.message); continue; }
    for (const [n, f] of tests) { try { await f(); pass++; out.push('✓ ' + s + ' › ' + n); } catch (e) { fail++; out.push('✗ ' + s + ' › ' + n + ': ' + e.message); } }
  }
  out.push(pass + ' prošlo, ' + fail + ' selhalo');
  postMessage({ exitCode: fail || !specs.length ? 1 : 0, output: (specs.length ? '' : 'Žádné testy (*.test.js).\\n') + out.join('\\n'), command: specs.join(' ') });
})();`;
    let w;
    try { w = new Worker(URL.createObjectURL(new Blob([harness], { type: 'text/javascript' }))); } catch (e) { resolve({ exitCode: 1, output: 'Worker nejde spustit: ' + e.message, command: 'vega-test' }); return; }
    const t = setTimeout(() => { w.terminate(); resolve({ exitCode: 124, output: 'Překročen časový limit ' + timeoutMs + ' ms – test ukončen.', command: 'vega-test' }); }, timeoutMs);
    w.onmessage = e => { clearTimeout(t); w.terminate(); resolve(e.data); };
    w.onerror = e => { clearTimeout(t); w.terminate(); resolve({ exitCode: 1, output: String(e.message || e), command: 'vega-test' }); e.preventDefault(); };
  });
}

/* ------------------------------------------------------------------ události z Kotlinu */
window.vega = {
  on(type, d) {
    switch (type) {
      case 'message':
        S.stream = ''; phase('');
        if (d.chat === 'temp') { (S.tempMsgs = S.tempMsgs || []).push(d.msg); }
        if (d.msg.card && d.msg.card.type === 'run' && S.view === 'tool') { S.toolResult = d.msg.card; }
        if (S.view === 'chat' || S.view === 'tool') render(); else toast('VEGA: ' + String(d.msg.text).slice(0, 80));
        break;
      case 'delta': S.stream = d.text; if (S.view === 'chat') { const m = $('#msgs'); const last = m && m.querySelector('.stream'); if (last) last.textContent = d.text; else render(); $('#view').scrollTop = 1e9; } break;
      case 'phase': phase(d.text); break;
      case 'intent': break;
      case 'idle': S.busy = false; phase(''); if (S.view === 'chat' || S.view === 'tool') render(); break;
      case 'done': S.busy = false; if (d.cancelled) toast('Zrušeno – hotové kroky zůstaly.'); render(); break;
      case 'error': S.busy = false; S.stream = ''; phase(''); toast(d.text, 6000); if (S.view === 'chat') render(); break;
      case 'files': S.files = S.files.concat((d.files || []).filter(f => !f.error)); (d.files || []).filter(f => f.error).forEach(f => toast(f.error)); render(); break;
      case 'shared': S.view = 'chat'; render(); $('#input').value = d.text; break;
      case 'project': S.project = d.id; if (d.imported !== undefined) { toast('Importováno ' + d.imported + ' souborů (kopie).'); go('project', d.id); } break;
      case 'consent':
        sheet(`<b>Odeslat podklady do cloudu?</b><p>Cíl: <b>${esc(d.target)}</b><br>Rozsah: ${d.chars.toLocaleString('cs-CZ')} znaků</p><div class="small">${d.items.map(i => '• ' + esc(i)).join('<br>')}</div>
          ${d.paid ? `<p class="small">Placený poskytovatel – odhad ${fmt(d.estimateKc)} Kč.</p>` : '<p class="small">Poskytovatel zdarma.</p>'}
          <div class="row"><button class="btn grow" data-answer="${d.req}:0">Neposílat</button><button class="btn primary grow" data-answer="${d.req}:1">Odeslat</button></div>`); break;
      case 'approve':
        sheet(`<b>Schválit placené volání?</b><p>${esc(d.provider)} · ${esc(d.model)}<br>${d.chars.toLocaleString('cs-CZ')} znaků · odhad <b>${fmt(d.estimateKc)} Kč</b></p><p class="dim">${esc(d.reason)}</p>
          <div class="row"><button class="btn grow" data-answer="${d.req}:0">Ne</button><button class="btn primary grow" data-answer="${d.req}:1">Schválit tento běh</button></div>`); break;
      case 'jstest':
        runJsTests(d.files, d.timeoutMs || 10000).then(r => B.answer(d.req, JSON.stringify(r))); break;
      case 'verified': { const el = $('#pout'); if (el) el.innerHTML = `<div class="card">${verHtml(d)}</div>`; toast('Testy: kód ' + d.exitCode); break; }
      case 'nfc.start': B.nfc(S.temp ? 'temp' : S.chat || ''); break;
      case 'nfc.listening': phase('NFC: přilož štítek…'); break;
      case 'nfc.off': sheet(`<b>NFC je vypnuté</b><p>Zapni ho v systémovém nastavení.</p><button class="btn primary" data-nfcsettings style="width:100%">Otevřít nastavení NFC</button><button class="btn" data-closesheet style="width:100%;margin-top:8px">Zavřít</button>`); break;
    }
  },
};

/* ------------------------------------------------------------------ klepnutí */
function send(text) {
  text = (text || '').trim(); if (!text && !S.files.length) return;
  if (!text) text = 'Co s tímhle souborem?';
  if (S.temp) (S.tempMsgs = S.tempMsgs || []).push({ role: 'user', text });
  S.busy = true; S.stream = '';
  call('send', S.temp ? 'temp' : S.chat, text);
  S.files = [];
  render();
}

document.addEventListener('click', e => {
  const t = e.target.closest('[data-go],[data-send],[data-homesend],[data-pick],[data-pickfolder],[data-stop],[data-tool],[data-runtool],[data-export],[data-pass],[data-passto],[data-resume],[data-save],[data-step],[data-showrun],[data-runproc],[data-delproc],[data-chatmode],[data-newchat],[data-clearfiles],[data-clearinput],[data-newproject],[data-openproject],[data-openfile],[data-verify],[data-pdiff],[data-pexport],[data-undo],[data-memadd],[data-memrevert],[data-memforget],[data-savekey],[data-answer],[data-nfc],[data-nfcsettings],[data-ndef],[data-demand],[data-demandform],[data-open],[data-share],[data-closesheet],[data-sym]');
  if (!t) return;
  const d = t.dataset;
  if (d.go !== undefined) { closeSheet(); go(d.go); }
  else if (d.send !== undefined) send($('#input').value);
  else if (d.homesend !== undefined) { const v = $('#homeInput').value; S.view = 'chat'; S.temp = false; render(); send(v); }
  else if (d.pick !== undefined) B.pickFiles();
  else if (d.pickfolder !== undefined) B.pickFolder();
  else if (d.stop !== undefined) { B.stop(); S.busy = false; render(); }
  else if (d.tool !== undefined) { S.toolInput = null; S.toolResult = null; go('tool', d.tool); }
  else if (d.runtool !== undefined) {
    const params = {}; document.querySelectorAll('[data-param]').forEach(i => params[i.dataset.param] = i.value);
    let input = S.toolInput || {}; const txt = $('#toolText')?.value;
    if (!S.toolInput) input = txt ? { text: txt } : (S.files.length ? { file: S.files[S.files.length - 1].id } : {});
    S.busy = true; S.toolResult = null; call('runTool', S.chat || 'temp', d.runtool, input, params); render();
  }
  else if (d.export !== undefined) { const r = call('export', d.export, -1); if (r) toast('Uloženo do Stažené/VEGA: ' + r.name); }
  else if (d.pass !== undefined) passSheet({ run: d.pass }, 'výsledek');
  else if (d.ndef !== undefined) { const run = t.closest('[data-run]').dataset.run; passSheet({ ndefRun: run, record: +d.ndef }, 'záznam ' + (+d.ndef + 1)); }
  else if (d.passto !== undefined) { closeSheet(); S.toolInput = Object.assign({ label: S.passLabel }, S.passInput); S.toolResult = null; go('tool', d.passto); }
  else if (d.resume !== undefined) { S.busy = true; call('resume', S.chat || 'temp', d.resume, {}); }
  else if (d.save !== undefined) { const r = call('saveProcedure', JSON.parse(d.save)); if (r) toast(`Postup „${r.name}“ uložen (v${r.version}). Najdeš ho na Domů.`); }
  else if (d.step !== undefined) { const [run, i] = d.step.split(':'); const r = call('run', run); if (r) sheet(`<b>Krok ${+i + 1}: ${esc(r.steps[i].title)}</b>${valueHtml(r.stepValues[i]) || '<p class="dim">Krok nemá výsledek (' + esc(r.steps[i].state) + ').</p>'}<div class="row"><button class="btn" data-closesheet>Zavřít</button></div>`); }
  else if (d.showrun !== undefined) { const r = call('run', d.showrun); if (r) sheet(runCardHtml(r) + '<button class="btn" data-closesheet style="width:100%">Zavřít</button>'); }
  else if (d.runproc !== undefined) { S.pendingProc = d.runproc; if (S.files.length) { S.busy = true; call('runProcedure', S.chat || 'temp', d.runproc, { file: S.files[S.files.length - 1].id }); S.view = 'chat'; render(); } else { toast('Vyber soubor – pak postup spustím.'); S.awaitProc = d.runproc; B.pickFiles(); } }
  else if (d.delproc !== undefined) { if (confirm('Smazat postup?')) { call('deleteProcedure', d.delproc); render(); } }
  else if (d.chatmode !== undefined) { S.temp = d.chatmode === 'temp'; if (!S.temp) call('clearTemp'); render(); }
  else if (d.newchat !== undefined) { S.temp = false; S.chat = call('newChat'); render(); }
  else if (d.clearfiles !== undefined) { S.files = []; B.clearFiles(); render(); }
  else if (d.clearinput !== undefined) { S.toolInput = null; render(); }
  else if (d.newproject !== undefined) { const id = call('newProject', $('#pname').value); if (id) go('project', id); }
  else if (d.openproject !== undefined) go('project', d.openproject);
  else if (d.openfile !== undefined) { const f = call('file', S.project, d.openfile); if (f) sheet(`<b>${esc(d.openfile)}</b><textarea id="fedit" rows="16" style="font-family:monospace;font-size:13px">${esc(f.text)}</textarea><div class="row"><button class="btn" data-closesheet>Zavřít</button><button class="btn primary grow" id="fsave">Uložit (kontrola verze)</button></div>`), $('#fsave').onclick = () => { const r = call('saveFile', S.project, d.openfile, $('#fedit').value, f.hash || ''); if (r !== null) { toast('Uloženo jako změna #' + r); closeSheet(); render(); } }; }
  else if (d.verify !== undefined) { $('#pout').innerHTML = '<div class="card muted">Spouštím testy…</div>'; B.verify(d.verify); }
  else if (d.pdiff !== undefined) { const ds = call('diff', d.pdiff) || []; $('#pout').innerHTML = `<div class="card">${ds.map(f => `<details open><summary>${esc(f.path)}</summary>${diffHtml(f.diff, f.added, f.removed)}</details>`).join('') || '<span class="muted">Beze změn proti importu.</span>'}</div>`; }
  else if (d.pexport !== undefined) { const n = call('exportProject', d.pexport); if (n) toast('ZIP ve Stažené/VEGA: ' + n); }
  else if (d.undo !== undefined) { const [p, c] = d.undo.split(':'); const r = call('undo', p, +c); if (r) { toast('Vráceno: ' + r); render(); } }
  else if (d.memadd !== undefined) { call('memoryAdd', $('#memText').value); render(); }
  else if (d.memrevert !== undefined) { const r = call('memoryRevert', d.memrevert); toast(r ? 'Vráceno na: ' + r : 'Není co vrátit'); render(); }
  else if (d.memforget !== undefined) { call('memoryForget', d.memforget); toast('Zapomenuto (i z indexu).'); render(); }
  else if (d.savekey !== undefined) { const r = call('saveKey', d.savekey, $('#key-' + d.savekey).value); if (r) { toast('Klíč uložen v trezoru telefonu.'); render(); } }
  else if (d.answer !== undefined) { const [req, ok] = d.answer.split(':'); B.answer(req, JSON.stringify({ ok: ok === '1' })); closeSheet(); }
  else if (d.nfc !== undefined) { S.view = 'chat'; render(); B.nfc(S.temp ? 'temp' : S.chat || ''); }
  else if (d.nfcsettings !== undefined) { B.nfcSettings(); closeSheet(); }
  else if (d.demandform !== undefined) { S.view = 'chat'; render(); send('připrav předžalobní výzvu'); }
  else if (d.demand !== undefined) { const o = {}; t.closest('.card').querySelectorAll('[data-dm]').forEach(i => o[i.dataset.dm] = i.type === 'number' ? +i.value : i.value); o.b2b = true; S.busy = true; call('demand', S.chat || 'temp', o); }
  else if (d.open !== undefined) B.openOut(d.open);
  else if (d.share !== undefined) B.share(d.share);
  else if (d.closesheet !== undefined) closeSheet();
  else if (d.sym !== undefined) toast(d.sym + ': v chatu napiš „vysvětli ' + d.sym + '“');
});

document.addEventListener('change', e => {
  const t = e.target;
  if (t.dataset.set) call('setSetting', t.dataset.set, String(t.checked));
  if (t.id === 'budget') call('setSetting', 'budgetKc', t.value);
});
document.addEventListener('input', e => { if (e.target.id === 'symq') { const q = e.target.value.toLowerCase(); document.querySelectorAll('#symlist [data-sym]').forEach(x => x.style.display = x.dataset.sym.toLowerCase().includes(q) ? '' : 'none'); } });
document.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey && e.target.id === 'input') { e.preventDefault(); send(e.target.value); } });

// vybraný soubor pro čekající postup
const _on = window.vega.on;
window.vega.on = (type, d) => { _on(type, d); if (type === 'files' && S.awaitProc && S.files.length) { const id = S.awaitProc; S.awaitProc = null; S.busy = true; call('runProcedure', S.chat || 'temp', id, { file: S.files[S.files.length - 1].id }); S.view = 'chat'; render(); } };

render();
