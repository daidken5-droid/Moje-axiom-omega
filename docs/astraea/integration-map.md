# ASTRAEA CORE 3.0 – mapa integrace

| Schopnost | Stav před | Změna (soubor : funkce) | Důkaz / test | Stav po | Omezení |
|---|---|---|---|---|---|
| Hloubka FAST/VERIFIED/DEEP | jen typ úkolu | `astraea/AstraeaCore.kt : route`; rešerše = VERIFIED, hypotéza = DEEP | `AstraeaCoreTest.highImpactEscalates…` | hotovo | ruční volba „Rychle/Ověřit/Hloubkový“ v UI zatím není (automaticky podle typu úkolu) |
| Strážce sítě v orchestrátoru | jen v Ops | `AstraeaCore.execute` (filtr backendů/konektorů) | `offlineBans…`, `noSilentCloud…`, `untrustedSource…`, `injectedInstruction…` | hotovo (dvojí kontrola) | – |
| Žádné tiché přesměrování modelu | Engines | `preferredBackendName` v jádře | `explicitCloudPreference…` (2 testy) | hotovo | volbu poskytovatele dál dělá `Engines` |
| Agregace verdiktů, KONFLIKT | každá citace zvlášť | `AstraeaCore.check` | `contradictingSource…`, `AstraeaVegaAdapterTest.contradicting…` | hotovo | – |
| Nezávislé původy | jen nástroj „síla doložení“ | `VegaAdapters.originOf` (Evidence) | `copiedSources…`, `twoRewritesOfOneAgencyReport…` | hotovo | původ z textu jen u výslovného převzetí („podle zprávy ČTK“) |
| Smyšlená / nesouvisející citace | NEVYHODNOCENO / NEDOSTAČUJE po citacích | jádro: neznámé ID blokuje SUPPORTS | `fabricatedCitation…`, `onlySupportedClaims…` | hotovo | – |
| Bezpečné vykreslení | celý text modelu | `AstraeaCore.execute` + `Ops.governed(strictRender)` | `validSupport…`, `failedRevision…`, `onlySupportedClaims…` | hotovo pro rešerši | u hypotézy text zůstává (rozbor), označený v UI |
| Max. 1 revize, lepší kandidát | – | `AstraeaCore` + `Ops.governed` (druhé `llm`, neviditelné) | `oneRetryMaximum…`, `failedRevision…`, `deadlineSkipsRevision…` | hotovo | revize jen s načteným ověřovačem; placené schválení počítá 2 volání |
| Bez ověřovače | NEVYHODNOCENO | `UnavailableJudge`/`Grounding.Missing` → `UNVERIFIED` | `noRevisionWithoutLoadedJudge…`, `missingVerifierKeepsChat…` | hotovo | – |
| Audit bez citlivého textu | – | `Audit` + karta `audit` v `Ops.governed` | `generationFailureKeepsRealCauseOutOfAudit` | hotovo | audit se ukládá s odpovědí v chatu (jen počty a kódy) |
| UI „doloženo / rozbor / neověřeno“ | – | `ui/app.js : auditHtml` | Chromium snímek (VERIFIED PARTIAL, UNVERIFIED) | hotovo | na Pixelu 9a NEOVĚŘENO |
| Výpočty 10 a 11 | částečně | `EconTest.astraeaAcceptanceCalculations` | test | hotovo | – |
| Offline diktování | mohlo jít online | `MainActivity.voice(data)` (fáze E) | čtení kódu, build | hotovo | na telefonu NEOVĚŘENO |
| Hypothesis Compare panel | – | – | – | **neimplementováno** | rozbor hypotézy má strukturu opora/alternativy/co rozhodne; samostatný srovnávací panel je další krok |
