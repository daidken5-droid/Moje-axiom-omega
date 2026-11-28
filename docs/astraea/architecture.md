# ASTRAEA CORE 3.0 ve VEGĚ – architektura

Jedno orchestrující jádro v čistém Kotlinu (`vegacore/src/main/kotlin/cz/axiom/vega/astraea/AstraeaCore.kt`), žádní „agenti“
ani další LLM volání navíc. Staré názvy jsou kompetence, ne procesy:

| Historický modul | Kde je v kódu |
|---|---|
| PRAXIS / AXIOM | `Request` (cíl, omezení, souhlas) → `Report` (výsledek, stav, audit) |
| HADES | `AstraeaCore.route()` FAST / VERIFIED / DEEP + stávající `Router.route()` (typ úkolu) |
| RANDY Ω+ | rozpočet (`sourceLimit`, `maxRevisions ≤ 1`, `deadlineMillis`, `checkpoint`), revize jen když má šanci |
| VERITAS | stávající `Grounding` (atomizace, ONNX ověřovač) + `check()` v jádře (agregace, KONFLIKT, neznámé ID) |
| SATAN | `critique()` – konkrétní seznam vad pro jedinou revizi; lepší kandidát se ponechá |
| PLATO | `Ops.hypothesis` → jádro v režimu rozbor (DEEP, audit, verdikty; text s alternativami zůstává) |
| YGGDRASIL | porty `GenerationBackend`, `SourceConnector`, `EvidenceJudge` + `VegaAdapters` |
| EARL Ω | `Length` v `Request` (kontrakt; stylový renderer zůstává stávající) |
| VANTA | bezpečné vykreslení, abstence, „neověřený koncept“ místo předstírání |
| GENESIS | neimplementováno jako runtime – změny šablon jdou přes verzované commity a testy |

## Tok

```
chat0 → decide() (výpočty bez modelu) → Router.route()
  ├─ Research   → Ops.governed(strictRender = true)   [VERIFIED]
  ├─ Hypothesis → Ops.governed(strictRender = false)  [DEEP, rozbor]
  └─ Talk, Tool… → beze změny (FAST; výpočty dál deterministicky)

Ops.governed:
  Permission Guard (jádro: onlineApproved && !offlineOnly)
  → SourceConnector (už stažené zdroje, původ přes Evidence)
  → GenerationBackend (Ops.llm: souhlas, ceny, řetěz poskytovatelů)
  → Claim Extraction (Grounding.claims)
  → VERITAS (Grounding.Checker přes MemoChecker)
  → SATAN (max 1 revize, jen s načteným ověřovačem, v časovém rozpočtu)
  → Evidence-Safe Renderer → Audit → karta „sources“ (audit + Opora ve zdroji)
```

## Pravidla portů (fail-closed)

- Online backend/konektor se bez souhlasu nezavolá; rozhoduje jádro, ne obsah zdroje ani model.
- Výslovně zvolený backend se nenahradí jiným.
- Neznámé ID citace, výjimka ověřovače, chybějící ověřovač → `UNEVALUATED`, nikdy `SUPPORTS`.
- Výjimka konektoru → kód v auditu; výjimka generování → `FAILED` + skutečná příčina pro hostitele (Ops ji ukáže česky).
- Zrušení (`CancelledException`) jádro nikdy nespolkne.
- Bez ověřovače: reference se zdrží (`strictWithoutJudge = true`); VEGA ukáže text jako **neověřený koncept**
  (`UNVERIFIED`), aby rešerše bez volitelného 150MB balíčku fungovala – nic se nevydává za doložené.
