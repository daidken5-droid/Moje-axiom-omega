# CRUCIBLE – výsledky testů (stav 1013 / 276230c)

| Sada | Výsledek | Kde |
|---|---|---|
| :core:test (57 tříd, vč. FaultlineTest, InvariantsTest, NoBrainEmulationTest, AnswerMemoryPerfTest, Δ Lab) | **213 / 213 prošlo** | lokálně + CI |
| :app:testDebugUnitTest (DbMigrationTest, Robolectric) | **3 / 3 prošly** | lokálně + CI |
| :app:compileDebugAndroidTestKotlin | překlad OK | lokálně |
| :app:lintDebug | **0 chyb**, 39 varování (posouzena: závislosti, záměrná LAN konfigurace, cizí knihovna BouncyCastle) | lokálně |
| CI „Build AVIOR APK“ #121/#122 | **úspěch** | GitHub Actions |
| Instrumentace na emulátoru | 1 test prošel (#4, bez mozku); ostatní **neověřeny** (emulátor zmizel) | CI |
| Smoke na emulátoru | studený/teplý start bez pádu aplikace; další kroky **neověřeny** | CI |

LLM: testy jádra používají deterministické/mockované odpovědi mozku tam, kde je to uvedeno v testu; skutečný
model nebyl v CI spuštěn.
