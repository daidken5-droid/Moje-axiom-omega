# AUDIT_FIXES – HYPERFORGE (Delta IV, 1014)

Vstupní dokument `AVIOR_GPT6_NEZAVISLY_AUDIT.md` **nebyl k zadání přiložen** – ověřována byla konkrétní tvrzení jmenovaná
v zadání HYPERFORGE, každé proti aktuálnímu kódu. Status: TESTED = regresní test v repozitáři prochází.

| ID | Problém (ověřeno v kódu) | Soubor | Reprodukce | Oprava | Regresní test | Status |
|---|---|---|---|---|---|---|
| A-1 | Nová odpověď B dědila ✓, původ USER a vyšší jistotu od A (stejná otázka) – starý test to dokonce vyžadoval | `app/.../data/Db.kt` `putAnswer` | uložit A s ✓, pak B | dědění jen při shodném (normalizovaném) textu; transakce, `@Synchronized` | `DbMigrationTest.replacedAnswerDoesNotInheritVerification`, `concurrentWritesKeepVerificationBoundToText` (Robolectric) | TESTED |
| A-2 | ✓ uživatele označen „ověřená odpověď“ | `core/.../agent/Proof.kt` | – | štítek „potvrdil jsi – osobní potvrzení, ne nezávislé ověření“; kontrakt PERSONAL_MEMORY | `SentinelNoBrainTest` | TESTED |
| B-1 | `[n]` = „doloženo“ i při neexistujícím / nesouvisejícím zdroji | `core/.../agent/Evidence.kt` | „… [3]“ při 1 zdroji | SOURCED jen když zdroj n existuje a sdílí obsahové slovo; jinak CITED | `EvidenceIntegrityTest` (4 testy) | TESTED |
| B-2 | Shoda jednoho čísla = celá věta „spočítáno“ | `Evidence.kt` | „36 km a cesta trvá…“ | COMPUTED jen celá věta o výsledku, jinak PARTIAL | `numberFromAnotherEquationIsNotComputed`, `mixedCorrectAndWrongClaimIsPartial` | TESTED |
| B-3 | Paměť o uživateli se počítala do „ukotvenosti“ | `Evidence.kt` | – | MEMORY mimo `grounded`; UI „heuristická klasifikace, ne ověření“ | `memoryDoesNotRaiseGrounding`, `EvidenceTest` | TESTED |
| C-1 | Oprava uživatele uložena jako `MemoryKind.FACT` (váha 0,95) | `app/.../jobs/OmegaService.kt` | „Špatně, …“ | `MemoryKind.CORRECTION` (nepotvrzené, reflex z něj neodpovídá), revizní záznam s původní odpovědí | `CorrectionIntegrityTest` | TESTED (jádro); zapojení v OmegaService UNKNOWN (bez zařízení) |
| C-2 | „podle …“ / URL automaticky přijato proti doložené odpovědi | `Proof.onCorrection` | „Špatně, podle matriky…“ | stav PENDING: doložená odpověď zůstává, oprava čeká jako nepotvrzená | `FaultlineTest.f04_*` | TESTED |
| D-1 | Cloudová záloha = gzip + base64 (čitelná) | `app/.../data/CloudBackup.kt` | otevřít dataset | AES-256-GCM, PBKDF2-HMAC-SHA256 310k, AAD hlavička s verzí; stará záloha jen s upozorněním; obnova odmítne neplatnou SQLite | `BackupCryptoTest` (4 testy) | TESTED (krypto); upload/obnova proti HF UNKNOWN |
| E-1 | Root povel schvalitelný z WebView (`phoneRun(approved=true)`) | `app/.../bridge/OmegaBridge.kt` | XSS by zavolal `send` + `phoneRun` | nativní `AlertDialog` pro root povel, smazat vše, vymazat paměť, obnovu z cloudu, root mazání cache | – (UI zařízení) | IMPLEMENTED, neotestováno na zařízení |
| S-1 | `Step0.ev` bral libovolné % jako pravděpodobnost | `core/.../step0/Step0.kt` | „Sleva 20 % a kurz 2,0“ | jen výslovná pravděpodobnost | `Step0ContractTest.evNeedsExplicitProbability` | TESTED |
| S-2 | Vestavěné kurzy se tvářily aktuálně | `core/.../step0/Rates.kt` | „1 USD = 21,6 Kč (vestavěné orientační)“ | datace, „NENÍ aktuální, ověř na cnb.cz“, `stale()`, kontrakt ESTIMATE | `fallbackRatesAreVisiblyDatedAndNotCurrent`; eval `fresh` | TESTED |
| S-3 | Kalkulačky s pevnými předpoklady bez výhrady | `Step0.build/importJp` | – | „ORIENTAČNÍ ODHAD“, doprava jako PŘEDPOKLAD, sazba DPH se stavem | `Step0Test.importIncludesShippingInVatBase` | TESTED |
| S-4 | Paměť odpovědí přenášela odpověď na jiný rok / číslo / negaci / jednotku / jméno | `Reflex.cached` | „Praha 2020“ → „Praha 2010“ | `Reflex.compatible` | `CacheIdentityTest` | TESTED |
| S-5 | Politický názor by v kontraktu vypadal jako fakt | `AnswerContract` | – | pohled = ESTIMATE „není to fakt“ | `opinionIsNeverFact` | TESTED |
| E-V1 | „třípísmenný kód země X“ → telefonní předvolba; „Malawi“ → Malta; „Americká Samoa“ → USA (76 falešných jistot v evalu) | `core/.../agent/Facts.kt` | eval `iso` | „kód země“ ≠ předvolba; křížová kontrola s Verified Fact Store (konflikt = zdržet se) | eval dev/val/holdout 0 falešných | TESTED |
| E-V2 | Obecná procenta přebila finanční dotaz („5 % z 100000 = 5000“) | `Reflex.answerExact` | „kolik budu mít za 10 let při 5 % …“ | Exact Engine první | `ExactEngineTest.financeExplicitInputsOnly` | TESTED |
| E-V3 | `unwrapped()` přijal variantu s useknutým slovem („měsíčně“ → „mesic“) | `Reflex.unwrapped` | týž dotaz | hranice slov | `metamorphicWrappersDoNotChangeResult` | TESTED |
| E-V4 | Normalizace měnila čísla („4,8,15“ → „4,8, 15“) | `Reflex` + `QuestionNorm` | „průměr 4,8,15“ | přesné výpočty jen nad zněním se stejným číselným zápisem (`sameNumbers`) | `ExactEngineTest.statistics` | TESTED |
