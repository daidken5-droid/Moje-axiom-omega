# ASTRAEA CORE 3.0 – výchozí stav VEGY (baseline)

Vstupní revize: `152c922` (větev `claude/reverse-engineer-ai-app-o5jhyd`, VEGA 2.2.0 + den/fáze B–D).
Prostředí: Linux kontejner, JDK, Gradle, Chromium (Playwright). **Bez fyzického telefonu a bez emulátoru** → vše „na telefonu“ je NEOVĚŘENO.
Testy výchozího stavu: `./gradlew :vegacore:test :vega:testDebugUnitTest` → 136 testů, 0 selhání, 2 přeskočené (čekají na ONNX balíček ověřovače).

## Skutečná cesta zprávy

| Krok | Soubor : funkce | Volající |
|---|---|---|
| Odeslání | `ui/app.js : send()` → `B.send(chat, text, ids)` | tlačítko ↑ / Enter |
| Most | `MainActivity.kt : Bridge.send` (`@JavascriptInterface`, `safe{}`) | WebView |
| Přijetí | `Ops.kt : chat()` → `chat0()` (uloží zprávu uživatele, přílohy jen k této zprávě) | most |
| Deterministické cesty | `Ops.kt : decide()` – výpočty (Econ, Quick), důkazy (Evidence), export | `chat0` |
| Směrování | `vegacore core/Router.kt : Router.route()` → `Intent` (Talk, Research, Hypothesis, Tool, Code…) | `chat0` |
| Retrieval | rešerše `Web.gather` · lokální `LocalIndex.search/bodies` (FTS4) · `Rag` (BM25) · paměť | `research`, `talk` |
| Inference | `Ops.kt : llm()` → `Engines.pick()` / `ChainEngine` (souhlas, ceny, zálohy) | `talk`, `research`, `hypothesis` |
| Ověření citací | `Ops.kt : groundChecks()` → `Grounding.claims` + `Grounding.Checker` (ONNX / Missing) | `research`, `hypothesis` |
| Odpověď + uložení | `Ops.kt : reply()` → `addMsg` (soubor chatu) | všechny cesty |
| Zobrazení | `ui/app.js : renderStream()` → `card()` (`sources` → `checksHtml`) | událost `done` |

## Schopnosti – co už existovalo

| Schopnost | Soubor / místo | Stav | Důkaz | Nutná úprava |
|---|---|---|---|---|
| Router úkolů (HADES – část) | `core/Router.kt` | funguje | `RouterHeldOutTest`, `RouterTuningTest` | hloubka FAST/VERIFIED/DEEP chyběla |
| Deterministická kalkulačka („bez mozku“) | `core/Econ.kt`, `calc/Quick.kt` | funguje | `EconTest`, `LaikTest` | doplněny přesné případy 10 a 11 zadání |
| Atomizace tvrzení + citace [n] (VERITAS) | `core/Verifier.kt : Grounding.claims` | funguje | `VerifierTest` | žádná (převzato adaptérem) |
| Trénovatelný NLI ověřovač | `core/Verifier.kt : OnnxGrounding`, `tools/verifier` | funguje s balíčkem, bez něj NEVYHODNOCENO | `VerifierTest` (inference přeskočena bez balíčku) | žádná |
| Verdikty PODPORUJE / ODPORUJE / NEDOSTAČUJE / NEVYHODNOCENO | `Grounding.Label` | funguje (po citacích) | `VerifierTest` | agregace přes zdroje (KONFLIKT) chyběla |
| Provenience / nezávislé linie | `core/Evidence.kt` | funguje pro nástroj „síla doložení“ | `CoreScanTest`, `NewCoreTest` | do rešerše se nepromítala |
| Strážce sítě | `Ops.llm` (air-gap), `research` (webAllowed/airgap), `CostGuard`, souhlas | funguje | `OpsAcceptanceTest` | kontrola i na úrovni orchestrátoru chyběla |
| Bez tichého placeného fallbacku | `Ops.approvePaid`, `Engines` | funguje | `OwnServerTest`, `OpsDecideTest` | žádná |
| Bezpečné vykreslení (jen doložené) | – | **chybělo** – celý text modelu se ukázal i s nepodloženými větami | čtení kódu `research()` | doplněno |
| Max. 1 cílená revize (RANDY/SATAN) | – | **chybělo** | – | doplněno |
| Audit odpovědi bez citlivého textu | – | **chybělo** | – | doplněno |
| Paměť, izolace projektů | `core/Memory.kt`, `Workspace` | funguje | `StorageTest`, `WorkspaceScanTest` | žádná |
| WebView: bez sítě, CSP, jen lokální stránka | `MainActivity.kt` | funguje | revize 2.2.0, Chromium | žádná (viz security-review) |
| Offline diktování | `MainActivity.voice` | nebezpečné v Bez sítě (systémové rozpoznávání mohlo jít online) | čtení kódu | opraveno (fáze E) |
