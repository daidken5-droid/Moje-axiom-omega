# Changelog – AVIOR

## Delta IV · sestavení 1015 (online modul, holá témata, bez mozku rozumí víc)
- **Holý název tématu** („Operation Prato“, „Fotosyntéza“) = „co je X“: offline heslo, E-ARL definice, mozek s jasným
  zadáním. Dřív lehký mozek bez hledání → chyba nebo vymýšlení.
- **Online modul s webem pro všechny cloudy** (dřív jen Claude): Wikipedie cs→en + DuckDuckGo paralelně, limit 4 s,
  mezipaměť 10 min; podklady [n] s adresou → citace a klikací zdroje. I řetězec podmodelů, rada a eskalace.
- **Bez mozku, ale online:** výňatek z Wikipedie nebo přehled výsledků s odkazy místo chyby; srozumitelná zpráva,
  když nejde nic.
- **Bez mozku rozumí heslovitým dotazům** („Gruzie měna“, „Německo hlavní město“) a u hesel z offline Wikipedie
  ukáže **„Souvisí:“** (propojení témat bez modelu).
- Výkon beze změny (StressTest střídavě před/po), eval 100 % / 0 falešných jistot, 255 testů jádra.
- Neověřeno: živý web z telefonu (z cloudového prostředí Wikimedia omezuje sdílenou IP a DuckDuckGo blokuje boty).

## Delta IV · sestavení 1014 (HYPERFORGE: integrita + STEP-0)
Podrobně: `docs/hyperforge/` (AUDIT_FIXES, STEP0_HYPERFORGE_ARCHITECTURE, STEP0_EVALUATION, BENCHMARK_BEFORE_AFTER,
SECURITY_REVIEW, RUNTIME_C08, BUILD_AND_TEST).
- **M1 integrita:** ✓ patří konkrétnímu textu odpovědi (B nezdědí ověření A); mapa důkazů jen s existujícím souvisejícím
  zdrojem a jako „heuristická klasifikace“; oprava od uživatele = nepotvrzená (CORRECTION), ne fakt; „podle …“ nic nedokazuje.
- **M2 bezpečnost:** cloudová záloha šifrovaná koncově (AES-256-GCM + heslo); root povely a nevratné akce jen přes nativní dialog.
- **M3 STEP-0:** typovaný Answer Contract; EV jen s výslovnou pravděpodobností; vestavěné kurzy datované a „NENÍ aktuální“;
  paměť odpovědí hlídá rok, číslo, negaci, jednotku a jméno; názor nikdy jako fakt.
- **M4 vertikály:** Verified Fact Store + datový kompilátor (ISO 3166-1 / 4217 z Unicode CLDR 49, 249 území, kontrolní součet);
  Exact Engine (statistika, teorie čísel, geometrie, soustavy, datové jednotky, BMI); finance jen s výslovnými vstupy.
- **M5 eval:** 5 061 případů s nezávislým orákulem (31 % záludných): precision accepted 100 % na dev/validation/holdout,
  0 falešných jistot (dřívější STEP-0: 106). Opraveny 4 staré chyby nalezené evalem (předvolba místo ISO kódu, jiný stát,
  procenta přebíjela finanční dotaz, normalizace měnila čísla).
- **C-08:** emulátor padá SIGSEGV v hostitelské binárce qemu 37.2.13 (core dump); experiment se starší binárkou.

## Delta IV · sestavení 1009 (emulace uživatelů – modul bez mozku)
- **Nový test `NoBrainEmulationTest`:**
  - 1 500 generovaných dotazů v 17 rodinách s nezávisle spočítaným správným výsledkem (orákulum),
  - hovorové obměny: bez diakritiky, „prosím tě“, „hele“, „Ahoj,“, otazník,
  - plus 300 mozkových otázek s čísly, kde reflex nesmí odpovědět.
- **Nalezeno a opraveno:**
  - **P1 – obal mění odpověď:** „prosím tě, sleva 20 % z 8100“ → jen „= 1620“ (výše slevy, bez ceny po slevě). Příčina: zdvořilý obal rozbil přesný vzor a obecný vzor procent vyhrál. Oprava: holá otázka bez obalu má přednost (`Reflex.unwrapped`). Platí pro všechny reflexy, ne jen slevu.
  - **Dělení dvojtečkou** „629 : 3“ (český zápis) → počítá se. „10:30“ bez mezer zůstává čas.
  - **Záporné teploty** „-13 °C na °F“ → převádí se. Záporné délky a hmotnosti dál jdou mozku.
- **Výsledek:**
  - před: 1 409/1 500 správně, 20 chybných (sleva), 71 mlčel (dělení, záporné teploty),
  - po: 1 500/1 500 správně,
  - 0 falešných reflexů z 300.
  - (Dalších 60 „chybných“ v prvním běhu byla chyba orákula testu, které nečetlo znaménko minus – opraveno v testu.)

## Delta IV · sestavení 1008 (mrtvý kód + optimalizace)
- **Mrtvý kód:**
  - odstraněno 6 metod mostu JS↔Kotlin, které UI od verze 3.1 nevolá (`renameThread`, `brainRefresh`, `kbAdd`, `memImportPrompt`, `stopSpeaking`, `hfStatus`) – menší útočná plocha mostu,
  - odstraněny nepoužité `Sentinel.lifeYears` a `BrainCatalog.REMOTE_URL`.
- **Skrytá chyba (P2):** starý stažený katalog mozků (`files/brains/catalog.json` z doby, kdy šel obnovit ze sítě) úplně nahrazoval katalog z APK, takže nové mozky z aktualizací by se nezobrazily. Základem je teď vždy katalog z APK; vlastní mozky zůstávají.
- **Výkon paměti odpovědí:**
  - příčina: klíče uložených otázek se tokenizovaly u každého dotazu a podobnost se počítala dvakrát (líná sekvence),
  - oprava: klíče jsou předpočítané (`Entry.keys`) a podobnost se počítá jednou,
  - p95 při 2 000 záznamech **139 ms → 3–7 ms** (JVM),
  - test `AnswerMemoryPerfTest`.

## Delta IV · sestavení 1006 (CONVERGENCE, dokončení)
- **R-01:** migrace DB v4 už neskrývá chyby. Sloupce se zjistí přes `PRAGMA table_info` a doplní jen chybějící. Napůl migrovaná databáze se opraví při otevření.
- Nové testy datové vrstvy na skutečné Android SQLite (Robolectric) a jejich spouštění v CI: `DbMigrationTest`.
- `CHANGELOG.md` a `docs/REPRODUCE.md`.

## Delta IV · sestavení 1005 (CONVERGENCE)
- **CV-1:** `memory_save` agenta ukládá epizodu, ne fakt. Text modelu se nestane jistotou o uživateli.
- **CV-2:** nejisté datum („asi 1045“) bez falešné přesnosti. Sdílený `Sentinel.UNCERTAIN`.
- Testy invariantů B, C, D, E a test proti falešné přesnosti.

## Delta IV · sestavení 1004 (FAULTLINE Ω)
- **F-01:** částečné ověření už není důkaz celé odpovědi.
- **F-02:** kvadratický regex složené otázky (3,9 s → 44 ms), reflex přijímá max. 2 000 znaků.
- **F-04:** doložený závěr se nemění kvůli nepodloženému „Špatně“. Se zdrojem se přehodnotí.
- **F-05:** revizní smlouva tabulky funkcí s mandátem.

## Delta IV · sestavení 1003 (PROOFCORE + Δ Lab)
- **Δ Lab:** vývojová a závěrečná sada, metriky, brána proti základně, souhrn v CI.
- **PROOFCORE:** původ odpovědí (user / wiki / model), revizní smlouva (DB v4), kladné ověření Strážcem.
- **Opravy:** desetinná čárka v normalizaci, kalkulačka odmítá „3 5“.

## Delta III · 1002
- Δ Srovnávač bez mozku, Wikizdroje s RAG indexem, Strážce faktů v2, UI FRESH, volba cloudu a modelu, paušály.
