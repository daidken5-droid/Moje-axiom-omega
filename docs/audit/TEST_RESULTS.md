# Výsledky testů

## Lokální běh (vývojový kontejner, JDK 21, Linux; ne Android)
`./gradlew :core:test` – **210 testů v 55 třídách, 0 selhání**. Vybrané výstupy:

```
Δ LAB · Delta IV
sada                 | otázek | správně | CHYBNĚ | zdržel se | mlčel | p50 µs | p95 µs
dev                  |     30 |      30 |      0 |         6 |     0 |    454 |   3354
final                |     50 |      49 |      0 |        15 |     1 |    636 |   3554
final·A-bez-knihovny |     50 |      40 |      0 |        15 |    10 |    395 |    970
paměť odpovědí: vráceno 0 z 8 neověřených odpovědí mozku jako odpověď bez mozku
FAULTLINE fuzz: 415 vstupů · nejhorší 82 ms
STRESS bez mozku: 24000 otázek / 8 vláken za 2.87 s = 8376/s · p50 209 µs · p99 13508 µs · chyby 0 · paměť +0 MB
STRESS mozek offline (simulovaný): režie orchestrace 11.53 ms/dotaz
STRESS zrušení uprostřed odpovědi: 84 ms (CancelledException)
STRESS cloud: 300 dotazů / 6 vláken · úspěch 300 · selhání 0 · přepnutí na zálohu 113
PROBE HIT 118/130 (EverydayCoverageTest)
```

**Benchmark variant (jen režim bez mozku):**

| Varianta | Výsledek |
|---|---|
| A = jen deterministika STEP-0 (bez offline knihovny) | 40/50 |
| plné AVIOR bez mozku | 49/50 |
| Delta III (základna `deltalab/baseline.json` z dřívějšího běhu) | 46/50 · 1 chyba · 6/8 vlastních neověřených odpovědí |

- Obě aktuální varianty mají 0 chybných odpovědí.
- Varianty s mozkem (RANDY, RANDY + PROOFCORE) **nebyly měřeny**: v prostředí není model ani zařízení.

## Sestavení 1009: emulace uživatelů, modul bez mozku (`NoBrainEmulationTest`)
- 1 500 dotazů, 17 rodin (násobení, sčítání, procenta, převody jednotek, dny do data, DPH, desetinná čísla, dělení, záporné výsledky, čísla slovy, měny, °C→°F, den v týdnu, sleva, věk).
- Správný výsledek počítá test nezávisle (orákulum).

| Stav | správně | chybně | mlčel |
|---|---|---|---|
| před opravami | 1 409 | 20 | 71 |
| po opravách | **1 500** | **0** | **0** |

- 300 mozkových otázek s čísly: **0 falešných reflexů**.
- `:core:test`: **213 testů, 0 selhání**; Δ Lab beze změny.

## Sestavení 1008: kontrola mrtvého kódu a výkonu
- **Analýza mrtvého kódu** (skript `tools/audit/dead_code.py`):
  - každá deklarace v `src/main` × výskyty v kódu (komentáře se nepočítají), včetně volání z `app.js`,
  - metody mostu nevolané UI: 6 (odstraněno),
  - nepoužité symboly: 2 (odstraněno),
  - po úpravě: 0 nepoužitých symbolů, 0 nevolaných metod mostu.
- **Varování kompilátoru:**
  - jen zastaralá API (`URL(...)`, `Locale(...)`, barvy lišt),
  - jedno „Condition is always true“ v `SecTools.kt:192` je planý poplach: Java vrací v `headerFields` klíč `null`.
- `AnswerMemoryPerfTest`: p95 Reflex s 2 000 uloženými odpověďmi **139 ms před → 3,4–7,3 ms po** (JVM, opakované běhy).
- `:core:test`: **211 testů, 0 selhání**. Δ Lab beze změny (dev 30/30, final 49/50, 0 chyb).
- UI náhled (`shoot.js`): 34 obrazovek, bez chyb JavaScriptu.

## `:app:testDebugUnitTest` (Robolectric `DbMigrationTest`)
- **Lokálně nespuštěno:** Maven Central vrací z kontejneru HTTP 429.
- Výsledek z CI je v části „CI“ níže.

## CI (GitHub Actions, `build-apk.yml`)
Viz poslední sekci (doplněno po doběhnutí běhu).

## Neprovedeno
- Instalace a spuštění APK na zařízení nebo emulátoru (není dostupné).
- Měření RAM, energie, tepla a rychlosti inference.
