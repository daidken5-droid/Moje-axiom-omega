# FAULTLINE Ω – adversariální audit AVIOR Delta IV (sestavení 1004)

Postup: hypotéza → reprodukční test → příčina → oprava → regresní test. Testy: `core/src/test/.../FaultlineTest.kt`,
`ProofTest.kt`, `DeltaLabTest.kt`.

## A. Stav projektu
- **Zkontrolováno:**
  - jádro (reflexy, Strážce, PROOFCORE, kalkulačka, normalizace, E-ARL),
  - most WebView ↔ Kotlin (navigace, odkazy, `openExternal`),
  - migrace databáze v3 → v4 (SQL ověřeno v SQLite mimo Android).
- **Sestavení:** `:core:test` (205 testů, 0 selhání) a `:app:compileDebugKotlin` prošly. APK sestavuje CI.
- **Neověřeno:** běh na telefonu (llama.cpp, paměť, teplo, životní cyklus Androidu). Kontejner nemá zařízení ani emulátor.

## B. Nalezené problémy

| ID | Priorita | Komponenta | Reprodukce / důkaz | Příčina | Stav |
|---|---|---|---|---|---|
| F-01 | P1 | `Proof.originOf` (vlastní kód z Δ IV) | `h16_partialProofIsNotWholeProof`: „Karel Gott (1939–2019) byl slovenský astronaut…“ → původ WIKI | ověřené roky narození a úmrtí se braly jako důkaz celé odpovědi | **opraveno**: WIKI jen u krátké odpovědi na otázku po ověřitelném údaji |
| F-02 | P1/P2 | `Reflex.compound` (regex) | fuzz: 20 000 znaků bez mezer = **3 896 ms** blokace fronty dotazů | `\S+\s+\S+` bez ukotvení → kvadratický backtracking | **opraveno**: slova omezena na 40 znaků, reflex přijímá max. 2 000 znaků; nejhorší vstup **44 ms** |
| F-03 | P0 (hypotéza) | migrace DB v4 | simulace SQL (upgrade i čistá instalace, opakovaná migrace) | — | **nepotvrzeno** (SQL funguje; na Androidu neověřeno) |
| F-04 | P1 | oprava od uživatele (`OmegaService`) | `f04_keepOrReviseOnDisagreement`: „Špatně, narodil se 1945“ smazalo **doloženou** odpověď a uložilo omyl jako fakt | každá „oprava“ se brala jako pravda bez důkazu | **opraveno**: doložený závěr AVIOR drží a ukáže důkaz; se zdrojem („podle …“, odkaz) ho přehodnotí |
| F-05 | P2 | `Politics` (tabulka STEP-0) | `f05_tableExpires`: „stav k 2026“ by platil i v roce 2028 s jistotou 90 | tabulka neměla revizní smlouvu | **opraveno**: po roce 2027 jistota proměnlivých údajů 80 → mozek |
| F-06 | — | navigace WebView / `openExternal` / vykreslení odkazů | kontrola kódu | — | **nepotvrzeno**: jen https, mimo vlastní doménu nic neběží s mostem, text se escapuje |
| F-07 | — | metamorfní invariance (velikost písmen, diakritika, interpunkce) | `metamorphicInvariance` (10 otázek × 5 variant) | — | **nepotvrzeno**: odpovědi jsou shodné |
| R-01 | riziko | `Db.createV4` | — | `runCatching` u ALTER skryje neočekávanou chybu → `SELECT origin` by pak padal | otevřené (doporučení níže) |

Hypotéza H4 (opakované mazání při variantách otázky) se testem **nepotvrdila**.

## C. Opravy a testy
- **Kód:** `Proof.kt` (původ podle otázky a délky odpovědi, `onCorrection`), `Reflex.kt` (lineární regex, limit délky),
  `Politics.kt` (revize tabulky), `OmegaService.kt` (zapojení `onCorrection`, původ podle otázky).
- **Testy:** `FaultlineTest` (7 testů včetně fuzzu 415 vstupů a metamorfních), úprava `ProofTest`.

## D. Vlastní vylepšení
- **Revize závěru s důkazem:** doložená odpověď se nemění kvůli nepodloženému nesouhlasu. Ušetří i celý průchod mozku, protože odpoví bez modelu.
- **Revizní smlouva pro statické tabulky.**

## E. Optimalizace (naměřeno v kontejneru, JVM)

| | před | po |
|---|---|---|
| Nejhorší vstup ve fuzzu (reflex + kalkulačka + Strážce + E-ARL) | 3 896 ms | 44 ms |
| `Reflex.isCompound` na 50 000 znacích | ≈ kvadratický | < 200 ms (test) |

## F. Regrese
- Δ Lab beze změny k horšímu: dev 30/30, final 49/50, 0 chyb, 0 z 8 vrácených neověřených odpovědí.
- Všech 205 testů jádra prošlo.

## G. Otevřená rizika
- Měření na zařízení (RAM, teplo, rušení během načítání modelu, návrat z pozadí) **neproběhlo**.
- R-01: migrace s `runCatching`.
- Strážce ověřuje jen data narození, úmrtí a vzniku. U jiných tvrzení PROOFCORE nic nedokazuje, a proto je správně označí jako MODEL.

## H. Doporučené další kroky
1. Instrumentační test na emulátoru/zařízení: migrace DB a rušení generování při uvolňování paměti.
2. `Db.createV4`: ověřit sloupce po migraci (`PRAGMA table_info`) a při chybě je doplnit. Selhání nepřecházet mlčky.
3. Fuzz/ReDoS kontrola všech regexů v CI: projít tabulky `facts/*.tsv` a vzory stejným testem s časovým limitem.
