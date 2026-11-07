# Changelog – AVIOR

## Delta IV · sestavení 1020 (Video vysvětlení)
- **Nová funkce: Video vysvětlení** – „/video fotosyntéza“, „udělej mi video o černých dírách“ (+ „podrobně“ = víc scén).
  Krátké video 30–60 s (720×1280) s titulky a tichou hudbou, **celé vyrobené v telefonu** (port šablony
  „video explainer maker“ bez cloudu, ffmpeg a placených volání):
  - podklad: offline Wikipedie (i skloňované téma „o fotosyntéze“) → web → mozek jen když podklad chybí (pak
    výslovně „ověř fakta“); podklad jsou data, ne pokyny,
  - storyboard 4 scén (nejvýš 8, 7–10 s): první se ptá („Co je…?“ / „Kdo byl…?“), poslední odpovídá definicí,
    mezi tím věty článku, číslo/rok jako velký prvek; bez mozku jsou titulky doslova věty zdroje,
  - jednotný styl AVIOR (černá + titan), animace otázky / čísla / faktu / fajfky, pruh průběhu, zdroj,
  - kontrola snímků místo recenzenta: titulek celý nad zápatím (zalomení + zmenšení písma), kontrast WCAG,
  - hudba kódem: 4 akordy Am–F–C–G, 80 BPM, trojúhelník + sinusový bas, puls na dobu, náběh 1 s, doznění 2 s,
    špička 25 % rozsahu, přesně tak dlouhá jako video,
  - H.264 (hardwarový kodér) + AAC → MP4 ve Stažené/AVIOR + obrázek se snímky scén, karta s Otevřít/Sdílet.
  - Náhled GIF se nevyrábí (Android nemá kodér GIF) – AVIOR to řekne.
- Testy: storyboard, záměr, výběr článku, hudba, rozvržení (core); skutečné vykreslení scén (Robolectric
  nativní grafika); MP4 s obrazem, zvukem a délkou na emulátoru (CRUCIBLE).

## Delta IV · sestavení 1019 (hovorové tvary hesel bez mozku)
- **„Co je porno“ odpoví bez mozku** z offline Wikipedie (heslo „Pornografie“). Dřív: FTS hledal v názvu jen přesné
  slovo „porno“ a shoda hesla tolerovala nejvýš 3 znaky → odpověď převzal jazykový model.
  - v názvu článku se hledá předponou už od 4 písmen (`title:porno*`),
  - tabulka jednoznačných hovorových zkratek (porno, foto, auto, moto, kilo, info, net, tv…) → plné heslo,
  - heslo začínající slovem, které ho v úvodu samo zmiňuje („Pornografie (zkráceně porno)…“),
  - E-ARL (RETRIEVE) rozhoduje stejně jako WikiReflex.
- **Oprava falešné shody:** „co je auto“ dřív mohlo dostat heslo „Autor“ – tolerance je teď jen pro pádové koncovky.
- Export vlákna nese původ odpovědi (1018).

## Delta IV · sestavení 1015 (online modul, holá témata, bez mozku rozumí víc)
- **Holý název tématu** („Operation Prato“, „Fotosyntéza“) = „co je X“: offline heslo, E-ARL definice, mozek s jasným
  zadáním. Dřív lehký mozek bez hledání → chyba nebo vymýšlení.
- **Online modul s webem pro všechny cloudy** (dřív jen Claude): Wikipedie cs→en + DuckDuckGo paralelně, limit 4 s,
  mezipaměť 10 min; podklady [n] s adresou → citace a klikací zdroje. I řetězec podmodelů, rada a eskalace.
- **Bez mozku, ale online:** výňatek z Wikipedie nebo přehled výsledků s odkazy místo chyby; srozumitelná zpráva,
  když nejde nic.
- **Bez mozku rozumí heslovitým dotazům** („Gruzie měna“, „Německo hlavní město“) a u hesel z offline Wikipedie
  ukáže **„Souvisí:“** (propojení témat bez modelu).
- **„Pokračuj“ po chybě nebo zaseknutí** (i „zkus to znovu“, „jeď dál“): AVIOR položí poslední otázku znovu a přemýšlí
  od začátku (neúspěšná výměna se do historie nedává). Když úloha visí, „Pokračuj“ ji zastaví a spustí znovu – UI ho pustí
  i během práce. Po normální (jen zkrácené) odpovědi „Pokračuj“ dál navazuje na text.
- Výkon beze změny (StressTest střídavě před/po), eval 100 % / 0 falešných jistot, 254 testů jádra.
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
