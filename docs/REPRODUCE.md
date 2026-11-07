# Reprodukce výsledků (Delta IV)

Požadavky: JDK 21, Android SDK (jen pro testy aplikace a APK).

| Co | Příkaz | Kde je výsledek |
|---|---|---|
| Všechny testy jádra vč. Δ Lab, FAULTLINE a invariantů | `./gradlew :core:test` | `core/build/reports/tests/test/index.html` |
| Δ Lab (správně / chybně / zdržel se, p50/p95, vlastní neověřené odpovědi) | `./gradlew :core:test --tests '*DeltaLabTest*'` | `core/build/delta-lab/report.md` a `report.json` |
| Fuzz a adversariální testy | `./gradlew :core:test --tests '*FaultlineTest*'` | konzole: řádek `FAULTLINE fuzz` |
| Invarianty (provenience, rozpočet cloudu, paměť, soukromí) | `./gradlew :core:test --tests '*InvariantsTest*' --tests '*NoFalsePrecisionTest*'` | report testů |
| Zátěž bez mozku / mozek (simulovaný) / cloud | `./gradlew :core:test --tests '*StressTest*'` | konzole: řádky `STRESS` |
| Migrace SQLite (Robolectric) | `./gradlew :app:testDebugUnitTest` | `app/build/reports/tests/testDebugUnitTest` |
| Měření UI (Playwright, CPU 4× zpomaleno) | `sh tools/ui-preview/build.sh && node tools/ui-preview/perf.js 4` | konzole |
| APK | `./gradlew :app:assembleRelease` (nebo CI `build-apk.yml`) | `app/build/outputs/apk` |

**Brána Δ Labu:** základna je `core/src/test/resources/deltalab/baseline.json`. Nová generace nesmí mít víc chyb ani méně správných odpovědí na žádné sadě.

Pravidlo sad:
- `dev.tsv` se smí ladit,
- `final.tsv` se neladí,
- 3 položky závěrečné sady jsou od opravy desetinné čárky „kontaminované“ (viz README).

**Co tu měřit nejde:** výkon llama.cpp, RAM, teplo a životní cyklus Androidu na skutečném zařízení.
