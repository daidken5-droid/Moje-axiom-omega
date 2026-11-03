# AVIOR – technický audit pro nezávislou kontrolu

> Autor: AI vývojář projektu (Claude). Tento dokument **není nezávislý** – je podkladem pro nezávislou kontrolu.
> Každé tvrzení odkazuje na soubor, test nebo měření; co odkaz nemá, je označeno jako neověřené.

## 1. Identifikace verze
- **Aplikace:** `cz.axiom.omega`, versionName **Delta IV**, versionCode **1008** (`app/build.gradle.kts`).
- **Zdroj:** větev `claude/reverse-engineer-ai-app-o5jhyd`; přesný commit je uvedený v `TEST_RESULTS.md`.
- **Předchozí stabilní vydání:** 1004 a 1006 (GitHub Releases `avior-delta-iv-c100x`, podpis SHA-256 `66a2e14c…`). Návrat k nim = instalace staršího APK.
  - Pozor: databáze v4 je zpětně kompatibilní jen směrem nahoru; starší APK přidané sloupce ignoruje.

## 2. Architektura
Viz `ARCHITECTURE.md`. Shrnutí: Android WebView UI + Kotlin služba + čisté Kotlin jádro.
Odpověď vzniká v pořadí:
1. deterministický reflex (STEP-0),
2. výňatek z offline knihovny,
3. mozek v telefonu (llama.cpp),
4. cloud.

## 3. Implementované komponenty (s důkazem)

| Komponenta | Soubory | Důkaz funkčnosti | Úroveň ověření |
|---|---|---|---|
| Reflex / STEP-0 bez mozku | `agent/Reflex.kt`, `Practical.kt`, `Instant.kt`, `Everyday.kt`, `Facts.kt`, `Politics.kt`, `step0/*` | `DeltaLabTest`, `CorrectnessTest`, `ParaphraseTest`, `SlangTest`, `EverydayCoverageTest`, `NoFalseReflexTest` | JVM testy |
| WikiReflex, Srovnávač | `agent/WikiReflex.kt`, `agent/Compare.kt` | `CompareTest`, `InstantTest`, Δ Lab (mini-Wikipedie `deltalab/kb.tsv`) | JVM testy s fiktivní knihovnou; **ne se skutečnou 534k Wikipedií** |
| Strážce faktů | `agent/Sentinel.kt` | `SentinelTest`, `SentinelNoBrainTest`, `ProofTest` | JVM testy |
| PROOFCORE (původ, revize, oprava) | `agent/Proof.kt`, `data/Db.kt`, `jobs/OmegaService.kt` | `ProofTest`, `FaultlineTest.f04`, `InvariantsTest.b/d`, `DbMigrationTest` | jádro: JVM; DB: Robolectric v CI; **zapojení v `OmegaService` netestováno** |
| E-ARL, Pavučina | `agent/Earl.kt`, `agent/Pavucina.kt` | `EarlTest`, `EarlRealDataTest`, `EarlDecisionTableTest`, `OverheadTest` | JVM testy |
| RANDY Supervisor | `randy/*.kt` | existující testy RANDY v `core/src/test` | JVM testy; **přínos pro kvalitu odpovědí neměřen** |
| Cloudový řetěz | `llm/FreeCloud.kt` | `FreeCloudTest`, `StressTest.cloud`, `InvariantsTest.c` | proti lokálním HTTP serverům, ne proti skutečným poskytovatelům |
| Lokální inference | `engine/LocalEngine.kt`, `cpp/omega_jni.cpp` | sestavení v CI (CMake + NDK) | **jen kompilace**; inference v testech je simulovaná (`StressTest.SimBrain`) |
| UI | `assets/ui/*` | `tools/ui-preview/perf.js`, `shoot.js` (Chromium, 4× zpomalené CPU) | prohlížeč, **ne Android WebView** |

## 4. Navržené nebo neúplné
- **Value of Information** (u srovnání říct, který chybějící údaj rozhodne): pouze navrženo, neimplementováno.
- **Sloučení E-ARL / Router / RANDY:** pouze navrženo.
- **Úplné sloučení parserů data** (WikiReflex × Strážce): částečně (`Sentinel.UNCERTAIN`).
- **Varianty benchmarku s mozkem** (A–D): blokováno, v prostředí není model ani zařízení.

## 5. Nejvýznamnější změny posledních fází
Viz `CHANGELOG.md`, zprávy `docs/FAULTLINE_AUDIT.md`, `docs/CONVERGENCE_REPORT.md`.

## 6. Potvrzené chyby a opravy (každá s reprodukčním testem)

| ID | Problém | Test |
|---|---|---|
| ΔIV-1 | Paměť odpovědí vracela neověřenou odpověď mozku nad prahem Auto (6/8 případů) | `DeltaLabTest` (selfSourced), `ProofTest` |
| ΔIV-2 | Desetinná čárka „3,5 krát 4“ → 140 | Δ Lab dev, `FaultlineTest.calcHostileInputs` |
| F-01 | Částečné ověření = důkaz celé odpovědi | `FaultlineTest.h16_*` |
| F-02 | Kvadratický regex: 3 896 ms na 20 000 znaků | `FaultlineTest.f02_*`, fuzz |
| F-04 | „Špatně“ bez zdroje přepsalo doložený fakt | `FaultlineTest.f04_*` |
| F-05 | Tabulka funkcí bez data revize | `FaultlineTest.f05_*` |
| CV-1 | Poznámka modelu uložena jako fakt o uživateli | `InvariantsTest.d_*` |
| CV-2 | „asi 1045“ → „1045“ (falešná přesnost) | `NoFalsePrecisionTest` |
| R-01 | Migrace DB v4 skrývala chyby | `DbMigrationTest` (Robolectric, CI) |

## 7. Výsledky testů
Viz `TEST_RESULTS.md`.

## 8. Výkonnostní metriky (jen to, co bylo změřeno)
- **Reflex bez mozku (JVM, kontejner CI/vývoj):**
  - Δ Lab p50 0,4–0,8 ms, p95 2,6–3,6 ms (kolísá mezi běhy),
  - zátěž: 24 000 otázek z 8 vláken, 8–13 tis./s, 0 chyb.
- **Režie orchestrace kolem simulovaného mozku:** ~9–12 ms na dotaz; zrušení uprostřed odpovědi 81–87 ms.
- **UI (Chromium, CPU 4× zpomaleno, viewport Pixel 9a):** přepnutí obrazovek 5–32 ms, vlákno s 200 zprávami ~25 ms.
- **Neměřeno:** rychlost inference (tokeny/s), RAM, energie, teplo, studený start na zařízení.

## 9. Známá omezení
- Žádný test na fyzickém zařízení ani emulátoru.
- Strážce ověřuje jen data narození, úmrtí a vzniku.
- Δ Lab a jeho sady napsal tentýž vývojář. Závěrečná sada má 3 kontaminované položky.
- Offline Wikipedie je z 11/2023; faktové tabulky mají ruční „stav k 2026“.

## 10. Bezpečnostní rizika (zkontrolováno / otevřené)
- **Zkontrolováno kódem:**
  - WebView navigace a CSP,
  - most a escapování,
  - `openExternal` jen https,
  - sdílený text se nevkládá k odeslání automaticky,
  - exportované služby asistenta jsou chráněné systémovými oprávněními, `AviorRecognitionService` je prázdný stub.
- **Testy:** air-gap a http jen do LAN (`InvariantsTest.e`), DLP maskování tajemství (`SecurityEvolutionTest`), injection firewall.
- **Otevřené:**
  - široká oprávnění (`REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `SET_ALARM`) kvůli funkcím „vyrob APK“ a ovládání telefonu – zvětšují dopad případné chyby v mostu,
  - JNI (`omega_jni.cpp`) a validace GGUF nebyly fuzzovány,
  - most `OmegaBridge` má přes 100 metod dostupných JavaScriptu UI. Bezpečnost stojí na tom, že se nespustí cizí JS (CSP + escapování).

## 11. Potenciálně nadbytečné komponenty (hypotézy, neověřeno)
- E-ARL × `Router` × RANDY Supervisor: tři vrstvy volby hloubky.
- Dvě cesty k definici z knihovny (WikiReflex a E-ARL RETRIEVE + `OfflineAnswer`).
- Několik cloudových enginů (Kimi, HF, Node, Server, FreeCloud) s podobným OpenAI-kompatibilním klientem.

## 12. Zbývající architektonické problémy
- `OmegaService.kt` (1 080 řádků) a `OmegaBridge.kt` (1 118 řádků) jsou příliš velké a netestované.
- Logika toku dotazu žije v Android vrstvě, ne v testovatelném jádru.

## 13. Neověřené předpoklady
- Že E-ARL/RANDY volí hloubku lépe než jednoduché pravidlo (bez měření s mozkem).
- Že reflex zvládne ~90 % běžných otázek (`EverydayCoverageTest` 118/130 je na sadě autora).
- Že migrace DB funguje na skutečném Androidu (Robolectric není zařízení).
- Že prompt/kontext pro malé modely v telefonu (Gemma 4 E2B/E4B) dává kvalitní odpovědi.

## 14. Pět nejhodnotnějších dalších kroků
1. **Instrumentační smoke test na emulátoru v CI** (start, dotaz bez mozku, migrace, zrušení). Ověří invarianty A/F a zapojení v `OmegaService`.
2. **Přesun toku dotazu z `OmegaService` do jádra** (čistá funkce s rozhraními). Celá rozhodovací cesta bude testovatelná na JVM.
3. **Měření E-ARL × Router × RANDY na stejných dotazech** → sloučení nebo odstranění nadbytečné vrstvy.
4. **Benchmark s mozkem na telefonu:** tokeny/s, RAM, správnost na sadě s nezávislými referenčními odpověďmi.
5. **Zúžení oprávnění** podle skutečně používaných funkcí (bezpečnost) a fuzz JNI/GGUF.
