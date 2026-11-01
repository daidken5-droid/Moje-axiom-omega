/* Mock window.Omega pro náhled UI v prohlížeči (tools/ui-preview). Neodesílá nic ven. */
(function () {
  const now = Date.now();
  let settings = { mode: 'auto', kimiBase: 'https://api.moonshot.ai/v1', kimiModel: 'kimi-k3', serverUrl: 'http://192.168.1.50:8080/v1', serverModel: 'kimi-k3', claudeModel: 'claude-opus-5-5', activeBrain: 'omega-core-qwen35-4b-q4km', maxPasses: 3, localMaxPasses: 2, reflect: true, web: true, thinking: false, ctxTokens: 4096, theme: new URLSearchParams(location.search).get('theme') || 'obsidian', font: 15, userName: 'Daniel', councilJudge: 'claude', onboarded: !location.search.includes('welcome'), rates: 'ČNB 31.10.2026' };
  const job = { busy: false, kind: '', phase: '', threadId: 0, progress: -1, detail: '', partial: '', reasoning: '', pass: 0, passes: 0 };
  const threads = [{ id: 1, title: 'Sázka kurz 2,10 / 55 %', updated: now, count: 2, preview: 'SILNÉ +EV…' }, { id: 2, title: 'Mikrocement 24 m²', updated: now - 9e6, count: 2, preview: 'Penetrace 3,96 kg…' }];
  const messages = {
    1: [
      { id: 1, role: 'user', content: 'Kurz 2,10, odhad 55 %, bankroll 10 000. Vsadit?', meta: {}, created: now },
      { id: 2, role: 'assistant', content: '**Ano – sázka je +EV, ale vsaď jen Half-Kelly.**\n\n| Ukazatel | Hodnota |\n|---|---|\n| EV | **+15,5 %** |\n| Férový kurz | 1,82 |\n| Half-Kelly | 7,05 % → **705 Kč** |\n\n- Tvůj odhad 55 % je výrazně nad implikovanými 47,6 %.\n- Největší riziko: přeceněný odhad p. Při skutečných 48 % je sázka už jen na nule.\n\nDoporučení: vsadit 705 Kč, odhad ověřit na 20+ podobných zápasech.', meta: { level: 'L3', levelTitle: 'STRATEGIC +EV', engine: 'Kimi K3 · cloud (kimi-k3)', model: 'kimi-k3', millis: 14200, confidence: 78, lesson: 'U +EV vždy uveď citlivost na chybu odhadu p.', facts: ['ASTRAEA +EV: kurz 2,1 | tvůj odhad 55 % vs. implikovaná 47,62 % | férový kurz 1,82 | EV 15,5 % [SILNÉ +EV] | Kelly 14,09 % → Half-Kelly 7,05 % bankrollu | vklad ≈ 704,55 z bankrollu 10000'], trace: [{ index: 1, label: 'Návrh', millis: 8100, model: 'kimi-k3', text: 'Návrh…' }, { index: 2, label: 'Kvantitativní kontrola', millis: 6100, model: 'kimi-k3', text: 'Kontrola…' }], sources: [], memories: ['Uživatel se jmenuje Daniel.'], notes: ['Poučení uloženo: U +EV vždy uveď citlivost na chybu odhadu p.'], snippets: [{ id: 4, title: 'Finance a +EV – Kellyho kritérium', source: 'pack:finance-ev' }] }, created: now },
    ],
    2: [],
  };
  const brains = { ramGb: '7.6', freeGb: '41,20 GB', recommended: 'omega-lite-qwen35-2b-q4km', active: 'OMEGA Lite · Qwen3.5 2B', brains: [
    { id: 'omega-core-gemma4-e4b-q4km', name: 'OMEGA Core · Gemma 4 E4B', family: 'Gemma 4', description: 'Doporučený mozek. Nejnovější model Google pro telefony (duben 2026).', size: '4,98 GB', sizeBytes: 4977171584, minRamGb: 8, license: 'Apache-2.0', thinking: true, installed: false, partial: 1100000000, recommended: false, fits: false, active: false },
    { id: 'omega-lite-gemma4-e2b-q4km', name: 'OMEGA Lite · Gemma 4 E2B', family: 'Gemma 4', description: 'Rychlá verze Gemma 4 pro telefony se 6 GB RAM.', size: '3,11 GB', sizeBytes: 1280835840, minRamGb: 4, license: 'Apache-2.0', thinking: true, installed: true, partial: 0, recommended: true, fits: true, active: true },
    { id: 'omega-sense-qwen3-embedding-0.6b', kind: 'embedding', name: 'OMEGA Sense · sémantická paměť', description: 'Hledání podle významu (vícejazyčné).', size: '0,64 GB', sizeBytes: 639150592, installed: true, fits: true, verified: true },
    { id: 'axiom-classic-qwen3-1.7b-q8', name: 'AXIOM Classic · Qwen3 1.7B Q8', family: 'Qwen3', description: 'Původní mozek z AXIOM 2.1 Hybrid.', size: '1,83 GB', sizeBytes: 1834426016, minRamGb: 4, license: 'Apache-2.0', thinking: true, installed: false, partial: 0, recommended: false, fits: true, active: false },
  ] };
  const packs = { stats: { docs: 41, chunks: 52, vectors: 52, memory: 6, threads: 2, messages: 4, bytes: 1572864 }, packs: [
    { id: 'axiom-metodika', name: 'AXIOM · metodika', description: 'RANDY, STEP-0, sebemodel a pravidla práce s důkazy.', version: 1, sections: 5, kb: 3, installed: true, update: false },
    { id: 'stavba-materialy', name: 'Stavba a materiály', description: 'Objemové hmotnosti, mikrocement, stěrky, prořez, DPH ve stavebnictví.', version: 2, sections: 5, kb: 3, installed: true, update: true },
    { id: 'kimi-k3-server', name: 'Kimi K3 a vlastní server', description: 'K3 online i offline, mozky v telefonu, Claude.', version: 1, sections: 5, kb: 3, installed: false, update: false },
  ] };
  const levels = [['L0', 'DIRECT', 'Okamžitý faktický výstup bez omáčky', 1, 'low'], ['L1', 'EXPERT', 'Technické parametry, normy a přesné hodnoty', 1, 'medium'], ['L2', 'DEEP', 'Hloubková syntéza z prvních principů', 2, 'high'], ['L3', 'STRATEGIC +EV', 'Kvantitativní výpočet marže, ROI a +EV', 2, 'high'], ['L4', 'NEMESIS RED-TEAM', 'Útočný audit, hledání slabin a rizik', 3, 'high'], ['L5', 'FORENSIC', 'Forenzní rekonstrukce stop a integrity dat', 3, 'high'], ['L6', 'OMNISCIENT', 'Multioborová syntéza s extrapolací dopadů', 3, 'max']].map(([code, title, label, passes, effort]) => ({ code, title, label, passes, effort }));
  window.Omega = {
    state: () => JSON.stringify({ version: '3.0.0', name: 'AXIOM Ω', job, settings, keys: { kimi: true, claude: false, server: false, node: true, hf: false }, engines: ['local', 'node'], sense: true, online: true, native: true, brain: 'OMEGA Lite · Qwen3.5 2B', counts: { memory: 6, docs: 27, threads: 2 } }),
    self: () => JSON.stringify({ name: 'AXIOM Ω', codename: 'OMEGA', version: '3.0.0', lineage: 'AXIOM 2.1 Hybrid → AXIOM Titanium 5.5 (RANDY, STEP-0, ASTRAEA +EV) → AXIOM Ω 3.0', principles: ['Rozlišuj doložená fakta, odvození, předpoklady a neznámé.', 'Čísla z bloku STEP-0 spočítal deterministický kód.', 'O sobě mluv přesně: jsi software s jazykovým modelem, sebemodelem a pamětí – ne vědomá bytost.'], describe: 'Běžím na: Kimi K3 · cloud (kimi-k3) (Kimi K3 · cloud, mozek OMEGA Lite · Qwen3.5 2B). Síť: online. Paměť: 6 záznamů, znalostní báze: 27 dokumentů, konverzací: 2. Baterie 64 %.', battery: 64, charging: false, ramFreeMb: 3120, ramTotalGb: '7.6', thermal: 'v normě', online: true, device: 'Google Pixel 9 · Android 16 · arm64-v8a', confidence: 81, memory: 6, docs: 27, threads: 2, levels }),
    threads: () => JSON.stringify(threads),
    messages: id => JSON.stringify(messages[id] || []),
    preview: t => JSON.stringify(/kurz/i.test(t) ? { level: 'L3', title: 'STRATEGIC +EV', label: '', passes: 2, facts: ['ASTRAEA +EV: kurz 1,85 …'] } : { level: 'L0', title: 'DIRECT', label: '', passes: 1, facts: [] }),
    send: () => { setTimeout(() => window.__simulate && window.__simulate(), 50); return 1; },
    brains: () => JSON.stringify(brains),
    tasks: () => JSON.stringify([
      { id: 2, threadId: 1, goal: 'Najdi 3 dodavatele mikrocementu v ČR a porovnej ceny', status: 'running', engine: 'Kimi K3 · cloud (kimi-k3)', steps: 4, result: '' },
      { id: 1, threadId: 2, goal: 'Porovnej nejnovější open-source modely pro telefon', status: 'done', engine: 'AXIOM Node (axiom)', steps: 7, result: 'Gemma 4 E4B vede v poměru výkon/velikost…' }]),
    deleteTask() {},
    libraries: () => JSON.stringify({ bytes: 2.31e9, articles: 214000, libraries: [
      { id: 'wiki-cs', name: 'Česká Wikipedie – celá', description: '534 tisíc článků: věda, technika, historie, zeměpis, osobnosti, právo, medicína…', rows: 534044, approxGb: 5.0, license: 'CC BY-SA 4.0', offset: 214000, done: false, percent: 40, started: true },
      { id: 'wiki-sk', name: 'Slovenská Wikipedie – celá', description: '242 tisíc článků ve slovenštině.', rows: 242235, approxGb: 1.8, license: 'CC BY-SA 4.0', offset: 0, done: false, percent: 0, started: false },
      { id: 'wiki-simple', name: 'Simple English Wikipedia', description: '242 tisíc článků v jednoduché angličtině.', rows: 241787, approxGb: 1.2, license: 'CC BY-SA 4.0', offset: 241787, done: true, percent: 100, started: true }] }),
    librarySearch: () => '[]', libraryStart() {}, libraryRemove() {}, hfSearch() { setTimeout(() => omegaReceive('hf_models', { models: [{ id: 'unsloth/gemma-4-E4B-it-GGUF', downloads: 812345 }, { id: 'unsloth/Qwen3.6-35B-A3B-GGUF', downloads: 402100 }] }), 50); },
    hfFiles(repo) { setTimeout(() => omegaReceive('hf_files', { repo, files: [{ path: 'a-Q4_K_M.gguf', quant: 'Q4_K_M', sizeLabel: '4.98 GB', fits: true }, { path: 'a-Q8_0.gguf', quant: 'Q8_0', sizeLabel: '8.19 GB', fits: false }] }), 50); },
    hfDownload() {}, brainFromUrl() {}, datasetImport() {}, wikiImport() {}, indexStart() {}, cloudBackup() {}, cloudRestore() {},
    packs: () => JSON.stringify(packs),
    memList: () => JSON.stringify([{ id: 1, kind: 'PROFILE', label: 'Profil', text: 'Uživatel se jmenuje Daniel.', weight: 1.5, uses: 12 }, { id: 2, kind: 'LESSON', label: 'Poučení', text: 'U +EV vždy uveď citlivost na chybu odhadu p.', weight: 1.1, uses: 3 }, { id: 3, kind: 'PREFERENCE', label: 'Preference', text: 'Preference uživatele: stručné odpovědi s tabulkou.', weight: 1.2, uses: 7 }]),
    kbList: () => JSON.stringify([{ id: 9, title: 'Ceník Topciment 2026.md', source: 'Import z telefonu', chars: 18200 }]),
    kbSearch: () => '[]',
    configure: j => { Object.assign(settings, JSON.parse(j)); setTimeout(() => omegaReceive('config', JSON.parse(window.Omega.state())), 10); },
    cancel() {}, open() {}, share() {}, copy() {}, rate() {}, saveKey() {}, testProvider() {}, newThread: () => 3, deleteThread() {}, renameThread() {},
    brainDownload() {}, brainActivate() {}, brainDelete() {}, brainImport() {}, brainRefresh() {}, memAdd() {}, memDelete() {}, memClear() {}, kbAdd() {}, kbDelete() {}, kbImport() {},
    packInstall() {}, memImportCopyPrompt() {}, memImportText() {}, memImportFile() {}, packRemove() {}, packRefresh() {}, dbExport() {}, dbImport() {}, forgetKeys() {},
  };
  window.__live = function () {
    job.busy = true; job.kind = 'ask'; job.threadId = 2;
    messages[2] = [{ id: 5, role: 'user', content: 'L4: Najdi slabiny v plánu otevřít si e-shop s kartami.', meta: {} }];
  };
})();
