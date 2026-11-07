# ARCHITECTURE – AVIOR OMEGA (1016)

```
UI (WebView, app.js) ──most (OmegaBridge, @JavascriptInterface, nativní potvrzení citlivých akcí)──► OmegaService (ASK/BG linky)
   │                                                                                               │
   │  Zero-Brain (bez LLM): Reflex → ExactMath → Practical/Instant/Everyday → Facts/Politics → IsoReflex
   │                        → KnowledgeGraph → kalendář → paměť → paměť odpovědí → Srovnávač/WikiReflex
   │                        (AnswerContract: typ, důvod, podklady, asOf)                           │
   ▼                                                                                               ▼
 E-ARL (cesta) ─► Orchestrator: RANDY úroveň → ResearchDepth (FAST/DEEP/FORENSIC) → STEP-0 → podklady
                  (offline knihovna + WebGround: Wikipedie cs/en + DuckDuckGo, paralelně, 4 s, mezipaměť)
                  → LongInput (kontrolní seznam / MAP po částech) → průchody → auto-pokračování → EvidenceMatrix
 ActiveAgent (FSM CREATED…COMPLETED) ─ nástroje: nativní tool_calls / Kimi / Hermes / JSON / AKCE → Tool executor
                  (InjectionGuard na výstupy, Watchdog proti smyčkám, max 25 kroků)
 Enginy: LocalEngine (llama.cpp) · KimiEngine (OpenAI-kompat.: Chutes, FreeCloud, Kimi, HF, server, Node) · ClaudeEngine (SDK)
         obaly: DlpEngine (maskování tajemství) · GuardedEngine + CostGuard (placené jen s povolením a do stropu)
```

## Bezpečnostní hranice
- **Síť:** vše přes `Http.open` (air-gap = žádné spojení; http jen LAN); Claude SDK má vlastní kontrolu air-gapu.
- **Web pro agenta:** jen veřejné https, DNS i každé přesměrování ověřeno (SSRF).
- **Externí text** (web, dokumenty, nástroje) = data: InjectionGuard, „data, ne instrukce“ v promptu.
- **Most:** klíče se do JS nevrací; root povely a nevratné akce jen přes nativní dialog.
- **Zálohy:** E2EE AES-256-GCM (heslo uživatele).

## Offline / online
- Offline: Zero-Brain + offline knihovna + mozek v telefonu (pokud stažen). Bez mozku i bez sítě vždy odpověď z knihovny nebo srozumitelná abstence.
- Online: WebGround podklady pro cloudy; bez mozku a bez cloudu výňatek z webu (WebAnswer).

## Rozhodnutí
- Neuronový encoder (ONNX) **nepřidán**: AVIOR už má embedder pro RAG (llama.cpp GGUF) a FTS; nový runtime by zvětšil APK a RAM bez změřeného přínosu.
- Textový protokol agenta zůstává jen jako kontrolovaný fallback za nativními tool_calls.
