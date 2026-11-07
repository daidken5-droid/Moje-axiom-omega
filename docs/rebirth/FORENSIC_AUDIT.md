# FORENSIC_AUDIT – AVIOR OMEGA (REBIRTH), výchozí stav 058c8cd → 1016

Úrovně ověření: **MOCK** = test s podvrženou službou · **UNIT** = jednotkový test · **STATIC** = čtení kódu · **NOT VERIFIED**.
„Delta IV.1“ a `CloudResponsePolicy.kt` ze zadání **v repozitáři neexistují** (žádná větev, žádný soubor) – nelze je ověřit; audit vychází ze skutečného kódu.

| ID | Prio | Komponenta | Důkaz | Reprodukce | Root cause | Oprava | Regresní test | Ověření |
|---|---|---|---|---|---|---|---|---|
| A-01 | P0 | `agent/ActiveAgent.kt` | původní kód: `finish=done toolRuns=0 rawShown=true` (běh na 058c8cd) | Kimi tokeny `<\|tool_call_begin\|>functions.web_search:0…` v odpovědi | `parse()` znal jen `AKCE:`; text > 400 znaků bez AKCE = finální odpověď „done“ | parsování nativních, Kimi, Hermes, holý JSON, AKCE; přezdívky; stavový automat; COMPLETED jen bez neprovedeného volání | `AgentToolCallIncidentTest` (8) | MOCK + lokální SSE server |
| A-02 | P0 | `llm/KimiEngine.kt` (Chutes, FreeCloud, Kimi, HF, server, Node) | `delta.tool_calls` se ignorovaly; prázdný text = výjimka | nativní volání nástroje | chybí skládání tool_calls ze streamu | `GenOptions.tools`, `GenResult.toolCalls`, skládání po indexu, HTTP 400 → bez tools | `kimiEngineAssemblesStreamedToolCallChunks` | MOCK (HTTP server) |
| C-01 | P0 | `llm/FreeCloud.kt`, `Omega.kt` | při výpadku free serveru přechod na placené (Chutes, NanoGPT…); Claude/Kimi v auto výběru a konziliu jen podle klíče | 429 u free člena | žádná politika nákladů | `CostGuard` + `GuardedEngine`: povolení, strop USD/měsíc, odhad před voláním, zápis spotřeby | `CostGuardTest` (4) | UNIT/MOCK |
| L-01 | P0 | `bridge/OmegaBridge.kt`, `app.js`, `Orchestrator` | limit 32 000 znaků; příloha tiše řezaná na ~30 000; zadání > okno = chyba | 50k zadání | pevné limity bez dělení | limit 120 000 (Binder), označení zkrácené přílohy, kontrolní seznam podotázek, MAP po částech + syntéza | `LongInputTest` (4) | MOCK |
| S-02 | P1 | `hub/Web.kt` | `instanceFollowRedirects=true` po kontrole jen první adresy | veřejná https → 302 na https://192.168.1.1 | SSRF přes přesměrování | ruční sledování přesměrování, každý krok `requirePublicHttps` | `RedirectSsrfTest` | UNIT |
| T-01 | P1 | `agent/Earl.kt`, `Orchestrator` | „operation Prato“ → lehký mozek bez hledání → chyba | holé téma | kategorie „obecné“ | `Topic.bare` → definice, jasné zadání | `TopicWebTest` | MOCK |
| W-01 | P1 | `jobs/OmegaService.kt` | web jen `kind == CLAUDE` | jakýkoli cloud | podmínka | `WebGround` pro všechny cloudy | `TopicWebTest` | MOCK; živý web NOT VERIFIED |
| R-01 | P2 | výzkum | krátké odpovědi na složité dotazy, žádné rozlišení evidence | — | chybí řízení hloubky | `ResearchDepth`, štítky evidence, `EvidenceMatrix`, auto-pokračování | `ResearchDepthTest` (6) | MOCK; kvalita s modelem NOT VERIFIED |
| Z-01 | P2 | Zero-Brain | žádné vícekrokové odvození | — | — | `KnowledgeGraph` (řetězec + podklady, bez kauzality) | `KnowledgeGraphTest` (3) | UNIT |
| X-01 | P2 | zaseknutí | „Pokračuj“ po chybě nic neopakovalo | — | — | reset a nové položení otázky | `RetryCommandTest` | UNIT; na zařízení NOT VERIFIED |

## Nevyřešené / neprokázané
- Živé chování skutečných API (Chutes Kimi-K3-TEE, OpenRouter, Groq, Gemini, Cerebras, Cloudflare): **NOT VERIFIED** – v tomto prostředí nejsou klíče a cloudové prostředí nemá zaručený přístup.
- Emulátor v CI padá (SIGSEGV v hostitelském qemu, C-08) → end-to-end testy **NOT VERIFIED**.
- Nativní function calling v `ClaudeEngine` (SDK) se nepoužívá – agent s Claude běží dál na textovém protokolu (funguje, ale méně robustní).
- Neuronový encoder pro Zero-Brain: **neimplementováno** (nebyl prokázán přínos vůči FTS + RAG; viz ARCHITECTURE).
