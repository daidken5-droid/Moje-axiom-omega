# VEGA – denní deep scan a hledání chyb (každý den 06:00–20:00, Europe/Prague)

Platí od 23. 11. 2026 až do odvolání. **Feature freeze zrušen uživatelem 23. 11. 2026 večer** („kompletně volná ruka“):
kromě oprav i vylepšení a nové funkce, pokud laikovi reálně pomůžou – vždy s testy a zelenou plnou sadou. Ranní přehled zpráv musí zůstat nezávislý na jazykovém modelu i cloud AI.

## Rozvrh dne

| Čas | Co se děje |
|---|---|
| 05:58 | **Start dne** – stav větve a posledního CI, výběr dnešních oblastí podle rotace níže, zápis „Den D“ do `PLAN-DEN.md` |
| :17 a :47 každou hodinu 06–19 | **Kontrola chodu** – stojí-li práce (limit, restart), navázat; jinak nic nepřerušovat. Každá kontrola = aspoň jedno kolo scanu |
| 20:02 | **Uzávěrka** – poslední CI zelené, `PLAN-DEN.md` (nálezy dne, testy, APK + SHA-256, co je NEOVĚŘENO na telefonu), shrnutí česky |

Jedno **kolo scanu** (cca 20–30 min): vybrat oblast → průzkum (probe / fuzz / revize kódu / živá data) → u každé
skutečné chyby test, který bez opravy selže → oprava → plná sada (`:vegacore:test :vega:testDebugUnitTest` + UI
`composer`, `audit`, `robust`, `xss`) → commit + push na `claude/reverse-engineer-ai-app-o5jhyd`. Bez nálezu se
zapíše „bez nálezu“ a co bylo prověřeno.

**Tvrdý refaktoring (na žádost uživatele od 25. 11., platí každý den):** aspoň jednou za dopoledne a jednou za
odpoledne kolo refaktoringu v dané oblasti – duplicity (stejná logika na víc místech → jedno místo), dlouhé funkce,
roztroušené konstanty, ruční kopie v `Ops.kt` / `MainActivity.kt` / `app.js`. Postup: před změnou ochranný test
(chování, které se nesmí změnit, + každá chyba nalezená cestou s testem, který bez opravy selže) → refaktoring po
malých krocích → plná sada (Kotlin + UI) zelená → samostatný commit „refaktoring: …“. Chování pro uživatele se
nemění, jen pokud refaktoring odhalí chybu – ta se opraví a popíše. Velký rozklad (např. rozdělení `Ops.kt`) jen
postupně po modulech, každý krok zvlášť otestovaný a pushnutý.

## Doporučení pro vývoj (od 25. 11. 2026, na žádost uživatele – přednost v denních i večerních kolech)

Zdroj: `docs/vega/DOPORUCENI-VYVOJ-2026-11-25.md`. Cíl: uživatel vloží několik českých článků / dokumentů, položí spornou
otázku a u každé části odpovědi otevře přesnou pasáž; tři články z jedné agenturní zprávy = VEGA to ukáže; doložená
událost × nejasný motiv = čtenář pozná rozdíl. Každé kolo dne i noci nejdřív posune další bod tohoto seznamu
(stejný postup: test, který bez změny selže → změna → plná sada → commit), teprve potom rotace oblastí níže.

| # | Krok | Hotovo, když |
|---|---|---|
| S1 | *(hotovo 25. 11., `31965c2`)* **Nezávislost zdrojů** (`Evidence.parse/lines/assess`, `astraea/VegaAdapters.originOf`): výslovné převzetí × společný odkaz × pouhá zmínka × neznámý původ; stejná agentura u dvou přepisů = jedna linie; neznámý původ = „nezávislost neurčena“; výsledek nezávislý na pořadí vstupů (web i místní) | 5 přejímacích případů z doporučení v testech `Evidence.assess` + web adaptér + jeden průchod od dotazu po zobrazené odůvodnění |
| S2 | *(rozpracováno 25. 11.: pasáže u hodnocení + úloha „most“ nad místními texty i uloženými webovými stránkami hotovo, u webu datum vydání × načtení; výchozí přehled = odpověď + hlavní rozpor, podrobný audit v kartě hotovo; živá rešerše: zvýraznění pasáže i bez ověřovače (odhad), datum vydání × načtení hotovo – S2 hotovo, zbývá jen ověření na telefonu)* **Rešerše od otázky po důkaz** (`Ops.research` / `governed`): u závěru původ, citovaná pasáž, rozsah podpory; přímo × odvozeno × sporné; klepnutí otevře úryvek (název, u webu datum vydání a datum načtení); výchozí přehled = odpověď + hlavní rozpor | přejímací úloha „most“: 3 místní texty v Bez sítě (datum doloženo, příčina otevřená) + stejné nad uloženými webovými podklady |
| S3 | *(25. 11.: scénáře zapsány v PHONE-CHECKLIST.md – čeká na ověření uživatelem na Pixelu)* **Autonomie na Pixelu 9a** – scénáře pro telefon (naučené pravidlo + vrácení přes restart, verze rozšíření, selhání testu, rešerše v pozadí) | scénáře v `PHONE-CHECKLIST.md` (telefon = NEOVĚŘENO do ověření uživatelem); stabilní převést do emulátorového CI |
| T1 | *(25. 11. noc: krok 1 – sestavení karet výsledku rešerše a hodnocení v `EvidenceCards.kt`; 27. 11.: krok 2 – strážné testy rešerše `OpsResearchGuardTest` (Bez sítě, web vypnutý, nic nenalezeno, bez modelu, duplicity, strop dotazů) a sběr podkladů vyjmut do `Research.collect`; noc 27./28. 11.: krok 3 – texty odpovědí Hardware vyjmuty do `HwReplies.kt` se strážným snímkem `OpsHwSnapshotTest`)* Rozdělit `Ops.kt` podle ověřeného chování (nejdřív získání podkladů a sestavení výsledku rešerše) | po pokrytí rešerše testy (S2), zachovat Bez sítě / bez modelu / zrušení |
| T2 | *(25. 11.: měřicí sada RouterParaphraseMetricTest – 4 chybná spuštění nalezena a opravena, metrika 0 z 21; sadu rozšiřovat; 27. 11.: sada rozšířena na 47 otázek / 16 příkazů / 9 otázek na VEGA – opraveny 2 akce spuštěné otázkou (NFC), 8 otázek odcházejících jako rešerše na web, zdvořilý příkaz „Můžeš si zapamatovat, že…?“)* Router podle naměřených chyb: sada českých parafrází, „Jak se zapíná rozšíření?“ × „Zapni rozšíření“, počet akcí spuštěných informační otázkou | měřicí test s počtem chyb |
| T3 | *(25. 11.: měřicí scénář v PHONE-CHECKLIST.md)* Výkon na cílovém telefonu: čas k prvnímu výsledku, k úplnému rozboru, max. paměť, první × opakovaný běh | měřicí scénář připravený pro telefon |

## Rotace oblastí (podle dne v týdnu)

| Den | Dopoledne (06–13) | Odpoledne (13–20) |
|---|---|---|
| Po | Laické dotazy: kalkulačka, data, převody, měny, router (probe 50+ nových formulací) | Ranní přehled zpráv: živé RSS, slučování témat, okno, alarmy |
| Út | Výpisy z banky, CSV, PDF/OCR, přílohy, export (fuzz + nové tvary bank) | UI: audit 360 px, robust, xss, toky (Upravit/Znovu/Kopírovat/hledání) |
| St | Paměť, konverzace, úložiště (poškozené soubory, limity, souběh) | Bezpečnost: most JS↔Kotlin, injekce z příloh/RSS/webu, logy, oprávnění |
| Čt | Cesta s modelem (falešný model): chyby, stop, dlouhé dokumenty, náklady | Výkon: velké konverzace, 300+ dokumentů, start aplikace, paměť |
| Pá | Hardware (NFC, WiFi, BLE, HCE) – logika a hraniční vstupy | Regrese celého dne týdne + lint + varování kompilátoru + mrtvý kód |
| So | Komerční uživatel (zvyklý na ChatGPT/Claude) – co chybí / mate | Red team konzilium: vývojář, prompter, hacker, investor |
| Ne | Dokumentace vs. skutečnost (hinty, cesty v UI, texty) | Týdenní souhrn, `PHONE-CHECKLIST.md` – co lze připravit pro telefon |

## Noční kola (23. 11. 20:17 → 24. 11. 03:47, na žádost uživatele)

| Čas | Oblast |
|---|---|
| 20–22 | Odložené funkce: synonyma ve slučování zpráv („paliva“ × „pohonné hmoty“, „atomovka“ × „jaderná zbraň“), kalkulačka – spotřeba a cena cesty, medián, zaokrouhlení, „půl kila“, kJ ↔ kcal |
| 22–00 | Výpisy z banky, CSV, PDF/OCR, přílohy (fuzz + nové tvary bank) |
| 00–02 | UI toky a přístupnost (360 px, velké písmo, čtečka obrazovky, Upravit/Znovu/Kopírovat/hledání) |
| 02–03:47 | Bezpečnost (most JS↔Kotlin, injekce z příloh/RSS/webu) + regrese noci; **03:47 uzávěrka noci** do `PLAN-DEN.md` |

## Noční kola (24. 11. 20:17 → 25. 11. 03:47, na žádost uživatele „klasická večerní kola jako včera“)

| Čas | Oblast |
|---|---|
| 20–22 | Ranní přehled zpráv (slučování, zdroje, notifikace, okno 05:20→05:20) a kalkulačka / převody bez modelu – nové tvary dotazů laika |
| 22–00 | Router a kontext: co si přivlastní výpis / kalkulačka / paměť / projekt (sondy jako kola 27–28), záměny témat, navazující dotazy |
| 00–02 | Hardware sekce (NFC, NDEF, WiFi, BLE) a soubory (DOCX, PDF, fotky dokumentů) – hraniční vstupy, texty pro laika |
| 02–03:47 | Bezpečnost (most JS↔Kotlin, injekce z příloh / RSS / webu, export) + regrese noci; **03:47 uzávěrka noci** do `PLAN-DEN.md` |

## Noční kola (27. 11. 20:17 → 28. 11. 03:47, na dotaz uživatele „Co večerní testy“)

| Čas | Oblast |
|---|---|
| 20–22 | Doporučení T1/T2 (refaktoring Ops.kt se strážnými testy, router – parafráze) |
| 22–00 | Předžalobní výzva, výpisy z banky, CSV, PDF – neobvyklé zápisy částek a dat |
| 00–02 | UI toky a přístupnost (360 px, velké písmo, chybové stavy formulářů) |
| 02–03:47 | Bezpečnost (most JS↔Kotlin, cizí text) + regrese noci; **03:47 uzávěrka noci** do `PLAN-DEN.md` |

## Pravidla
- Každá oprava s testem; plná sada zelená před commitem (commit jen řetězeně `&&` po testech).
- CI: po pushi ověřit poslední běh „Build VEGA APK“; červený = první úkol dalšího kola.
- Nic nepředstírat: telefon = NEOVĚŘENO, dokud to neověří člověk (`PHONE-CHECKLIST.md`).
- Placená volání 0 Kč; AVIOR (`app/`, `core/`) beze změn; žádné tajné klíče v repozitáři.
- Nalezená, ale neopravená chyba (rizikové / velké) → zápis do `PLAN-DEN.md` s návrhem, ne tichá změna.
