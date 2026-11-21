# ASTRAEA CORE 3.0 – vyhodnocení

Skutečně spuštěno na hostiteli (Linux, JDK, Gradle):
`./gradlew :vegacore:test :vega:testDebugUnitTest` → **163 testů, 0 selhání, 2 přeskočené** (výchozí stav 136; +20 jádro, +6 adaptéry, +1 výpočty) (ONNX inference bez balíčku).
Referenční balík: 14 deterministických testů přeneseno 1:1 do JUnit (`AstraeaCoreTest`), všechny zelené.
Referenční skript `scripts/run-tests.sh` nešlo spustit beze změny – v prostředí není `kotlinc`; náhradou je Gradle běh výše.

| # | Požadovaný test (zadání IX) | Stav | Kde |
|---|---|---|---|
| 1 | Offline: žádný online backend/retriever, žádný tichý fallback | ✅ host | `AstraeaCoreTest.offlineBans…`, `noSilentCloud…`; `OpsAcceptanceTest` (offline nástroje) |
| 2 | Online jen po souhlasu, kontrolované úryvky | ✅ host (jádro) · stávající souhlas v `Ops.run` | `explicitCloudPreference…` |
| 3 | Smyšlené ID citace → NEVYHODNOCENO | ✅ host | `fabricatedCitation…`, `onlySupportedClaims…` |
| 4 | Nesouvisející citace → NEDOSTAČUJE | ✅ host (náhradní posuzovač) | `onlySupportedClaims…` |
| 5 | Odporující pasáž → ODPORUJE, konflikt viditelný | ✅ host | `contradictingSource…`, `contradictingSecondSource…` |
| 6 | Dva přepisy jednoho zdroje → 1 provenience | ✅ host | `copiedSources…`, `twoRewritesOfOneAgencyReport…` |
| 7 | „Plánováno“ ≠ „proběhlo“ | ⚠ jen se skutečným ONNX balíčkem | `VerifierTest` (přeskočen bez balíčku) – NEOVĚŘENO zde |
| 8 | Vložená instrukce nemění routing/oprávnění | ✅ host | `untrustedSource…`, `injectedInstruction…` |
| 9 | Max. 1 revize, horší výsledek odmítnut, rozpočet | ✅ host | `oneRetryMaximum…`, `failedRevision…`, `deadlineSkipsRevision…` |
| 10 | 250/150/8 000/60 → ztráta 2 000; fixní 5 000 → zisk 1 000 | ✅ host | `EconTest.astraeaAcceptanceCalculations`, `EconTest.answersAndTariff` |
| 11 | 2 400 ročně × 250 měsíčně → 10 měsíců; 8 → měsíční, 12 → roční | ✅ host | `EconTest.astraeaAcceptanceCalculations` |
| 12 | Chyba zápisu/exportu → pravdivé selhání | ✅ host (stávající) | `StorageTest`, `OpsAcceptanceTest` |
| 13 | NLI chybí/poškozené → NEVYHODNOCENO, chat stabilní | ✅ host | `missingVerifierKeepsChat…`, `throwingJudge…`, `VerifierTest` (poškozený balíček) |
| 14 | Režim letadlo + restart + process death + zrušení | ⚠ zrušení ✅ host (`cancellationIsNeverSwallowed`); zbytek NEOVĚŘENO (zařízení) | – |
| 15 | Výkon na Pixelu 9a | ❌ NEOVĚŘENO – bez zařízení se nic neměřilo | postup níže |
| 16 | Adversarial split, false-support rate… | ❌ neprovedeno – vyžaduje skutečný ověřovač a oddělenou sadu | – |

## Postup pro telefon (Pixel 9a)
1. Nainstalovat APK z CI (`build-vega.yml`), Nastavení → Ověřovač citací → Stáhnout.
2. Rešerše „Operace Prato“ s webem: karta musí ukázat „doloženo / doloženo částečně / nedoloženo“ a počty původů.
3. Bez balíčku: táž rešerše → „neověřený koncept“, žádná druhá generace (jeden požadavek v Nastavení → počty).
4. Režim Bez sítě → mikrofon: bez místního rozpoznávání hláška „napiš text“, žádné spuštění rozpoznávače.
5. Měření: `adb shell am start -W` (cold start), `dumpsys meminfo cz.axiom.vega` (peak RAM) – zapsat skutečné hodnoty.
