# STEP0_EVALUATION – nezávislá adversariální sada (MEASURED, JVM)

- Generátor `tools/step0eval/generate.py` (semínko 20261103) → `core/src/test/resources/step0eval/{dev,validation,holdout}.jsonl`.
- **Orákulum**: Python (`statistics`, `math`, `Decimal` half-up) a surová JSON CLDR – nepoužívá kód ani regexy AVIORu.
- **5 061 případů**, 31,2 % negativních/záludných: nejednoznačný číselný zápis, chybějící vstup (hypotéka bez sazby),
  falešné EV (sleva/DPH vedle kurzu), mimo doménu (kód wifi, ISO 9001, Atlantida), mimo rozsah (n! > 200, > 10¹²),
  dva záměry, úvahové/kreativní dotazy, „aktuální kurz“ (smí jen s výhradou).
- Rozdělení podle hashe id: dev 3 031 / validation 1 008 / holdout 1 022. Holdout jen `-Pstep0.holdout=true`.
- Harness `Step0EvalTest`: celá cesta `Reflex.answer` (práh Auto 85, pevný čas 3. 11. 2026 12:00 Europe/Prague).
- Release gate stanovený předem: precision accepted ≥ 99 % na dev i validation.

| sada | přijato | precision accepted (95% Wilson) | coverage | falešné jistoty | abstence správně | p50 / p95 / p99 µs |
|---|---|---|---|---|---|---|
| dev | 2 065 | 100 % (99,81–100) | 2 065 / 2 065 | 0 | 966 / 966 | 252 / 1 105 / 1 808 |
| validation | 700 | 100 % (99,45–100) | 700 / 700 | 0 | 308 / 308 | 186 / 856 / 1 146 |
| holdout | 716 | 100 % (99,47–100) | 716 / 716 | 0 | 306 / 306 | 181 / 1 328 / 1 840 |

**Postup ladění (poctivě):** první běh na dev/validation dal 97,9 % / 97,4 % (42 + 18 falešných jistot, mlčení u obalů).
Opravy E-V1…E-V4 a IsoReflex (Niger/Nigérie, kód v názvu) se dělaly podle **dev a validation**. Holdout poprvé
po opravách (100 %); podruhé jen jako regresní kontrola po čistě výkonnostní změně (beze změny výsledků).

**Limity – co tato čísla NEdokazují:**
- Sadu i implementaci napsal tentýž vývojář; šablony nejsou reálné dotazy uživatelů → 100 % je horní mez na této sadě,
  ne přesnost na skutečném provozu. Cíl ≥ 99,5 % na „reprezentativním“ holdoutu tím **není prokázán**.
- Skloňování názvů států je v sadě jen pro 34 genitivů; ostatní dotazy v 1. pádě.
- Latence: JVM ve vývojovém kontejneru, ne telefon. Peak RAM na zařízení: UNKNOWN.
- Testována cesta STEP-0 (`Reflex`); `RANDY → OmegaService → WebView` na zařízení: UNKNOWN (emulátor C-08).
- Starší autorova sada „1 500 / 1 500“ (`NoBrainEmulationTest`) měří jiné šablony; její 100 % neplatí pro reálné dotazy.
