# Changelog – AVIOR

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
