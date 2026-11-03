# Předání k nezávislému auditu (GPT-6)

Prozkoumej přiložený projekt AVIOR jako nezávislý softwarový architekt a auditor.

Nevycházej automaticky z toho, že závěry předchozího AI vývojáře jsou správné.

Ověř implementovanou architekturu, nalezené chyby, dostupné testy a jejich výsledky.

Identifikuj přehlédnutá rizika, logické chyby, neefektivní mechanismy a možnosti zjednodušení.

Porovnej skutečné schopnosti projektu s deklarovanými.

Posuď RANDY, STEP-0, PROOFCORE, paměť a inference vrstvu.

Rozliš potvrzené skutečnosti, vlastní technické závěry a neověřené hypotézy.

Navrhni pět konkrétních vylepšení s nejvyšším poměrem přínosu, rizika a implementačních nákladů.

Zvlášť prozkoumej prostor pro funkční jedinečnost AVIORu.

Netvrď, že je některá schopnost světově unikátní bez relevantního ověření.

Výsledkem musí být kritické technické hodnocení a konkrétní plán dalšího vývoje.

---

## Kde začít (doporučené pořadí čtení)
1. `AVIOR_TECHNICAL_AUDIT.md`: tvrzení předchozího vývojáře **včetně důkazů a omezení**. Ověř je, nepřebírej je.
2. `ARCHITECTURE.md`: tok dotazu a pravidla důvěry.
3. `TEST_RESULTS.md`: co skutečně proběhlo, kde a s jakým výsledkem.
4. **Zdroj** (`AVIOR_SOURCE_AUDIT.zip`):
   - `core/src/main/kotlin/cz/axiom/omega/core/agent/` (Reflex, Sentinel, Proof, Compare, Earl, Orchestrator),
   - `randy/`,
   - `app/src/main/java/cz/axiom/omega/jobs/OmegaService.kt`, `data/Db.kt`, `engine/LocalEngine.kt`, `cpp/omega_jni.cpp`.
5. **Testy:** `core/src/test/...` (DeltaLabTest, FaultlineTest, InvariantsTest, StressTest), `app/src/test/.../DbMigrationTest.kt`.

## Na co si dát pozor
- **Emulátor v CI je nestabilní:** qemu mizí 45–120 s po prvním spuštění AVIORu (bez aplikace přežije 180 s). Příčina neověřena – viz `docs/crucible/CRUCIBLE_RUNTIME_REPORT.md`. Posuď, zda nejde o chybu aplikace.
- **Žádné měření na skutečném telefonu.** Výkon llama.cpp, RAM, teplo a životní cyklus Androidu nikdo neověřil.
- **Δ Lab měří jen odpovědi bez mozku a sady napsal stejný AI vývojář.** Hrozí, že testy odrážejí jeho předpoklady. 3 položky závěrečné sady jsou „kontaminované“ opravou.
- Rozhodování o hloubce dělají tři vrstvy (E-ARL, Router, RANDY). Redundance je podezřelá, ale neměřená.
