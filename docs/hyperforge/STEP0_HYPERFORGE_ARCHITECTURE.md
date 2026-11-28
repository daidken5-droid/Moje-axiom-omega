# STEP-0 HYPERFORGE – skutečná architektura (1014)

Pořadí v `Reflex.answerExact` (první, kdo odpoví nad prahem a projde Strážcem, vyhrává):
1. **Exact Engine** `core/.../step0/ExactMath.kt` – jen nad zněním se stejným číselným zápisem jako původní dotaz.
2. Practical → Instant → Everyday (stávající deterministika; převody měn nesou datum kurzu).
3. Facts → Politics (stávající tabulky; Facts zemí s křížovou kontrolou proti Fact Store).
4. **IsoReflex** nad **Verified Fact Store** `core/.../facts/`.
5. kalendář → paměť → paměť odpovědí (s kontrolou identity) → Srovnávač / WikiReflex (offline Wikipedie).

## Answer Contract (`core/.../agent/AnswerContract.kt`) – IMPLEMENTED, TESTED
`EXACT | VERIFIED_FACT | PERSONAL_MEMORY | ESTIMATE | NEEDS_FRESH_SOURCE | ABSTAIN` + `reason`, `evidenceRefs`, `asOf`,
`assumptions`, `elapsedNs`, `cachePolicy`. Typ určuje **modul**, který odpověděl; `weakenTo` smí jen oslabit.
Cesta: `Reflex` → `OmegaService.instant` (meta `contract`) → WebView štítek (typ + stav k datu). Vrstvy nad reflexem
kontrakt nečtou ani nemění (ověřeno čtením kódu); test na zařízení UNKNOWN.

## Verified Fact Store + Data Compiler – IMPLEMENTED, TESTED
- `tools/factc/fetch_cldr.sh` – připnutý Unicode CLDR 49 (`cldr-json@7d2b9cb11cec…`), `compile_cldr_iso.py` – validace
  schématu (regex kódů), konflikty kódů, duplicity (entita, predikát), 12 nezávislých zlatých vzorků, nejednoznačné názvy
  → `factstore/iso.tsv` (1 753 faktů, 249 území) + `iso.manifest.json` (SHA-256 souboru i zdrojů) + licence Unicode.
- Záznam: `factId, entityId, predicate, value, language, source, asOf, validUntil, reliabilityClass, license, revision, checksum`.
- `FactStore.load` ověří SHA-256 proti manifestu; nesoulad / chyba schématu = prázdný obchod → reflex mlčí. Prošlý fakt
  (`validUntil` < dnes) se nevrátí (kódy +3 roky, měna +1 rok).
- Aktualizace: znovu `fetch_cldr.sh <dir> <nový commit>` + `compile_cldr_iso.py` → diff TSV v gitu = auditovatelná změna.
- Zamítnutý zdroj: `datasets/country-codes` (licence ISO/Statoids/OSN nejasná).

## Intent/Entity Guard – částečně IMPLEMENTED
- `IsoReflex.resolve`: celý název v otázce, české koncovky, přesná shoda rozhoduje remízu, jinak null.
- `Reflex.compatible`: čísla, negace, jednotky, vlastní jména u paměti odpovědí.
- `Reflex.sameNumbers`, kontrola složené otázky (`Pavucina.compound`).
- DESIGNED, ne IMPLEMENTED: obecný detektor časového rámce a víceotázek mimo stávající `compound`.

## Abstence
- Reflex vrací null = převezme mozek / zdroj. Exact Engine u chybějícího vstupu vrací ABSTAIN s jistotou 50 (pod prahy) –
  text „co chybí“ je k dispozici, ale nezablokuje mozek.

## Rozpočty
- `iso.tsv` 272 kB nekomprimovaně (v APK zip). Fact Store se načte líně při prvním dotazu na kód/měnu.
- Limity vstupů: dotaz ≤ 400 znaků (Exact), ≤ 160 (ISO), seznam ≤ 200 čísel, prvočísla ≤ 10¹², n! ≤ 200, C(n,k) n ≤ 1000.
