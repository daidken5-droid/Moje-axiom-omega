# VEGA – denní deep scan a hledání chyb (každý den 06:00–20:00, Europe/Prague)

Platí od 23. 11. 2026 až do odvolání. Režim **feature freeze**: jen opravy skutečných chyb, testy a zjednodušení;
nové funkce jen na výslovnou žádost. Ranní přehled zpráv musí zůstat nezávislý na jazykovém modelu i cloud AI.

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

## Pravidla
- Každá oprava s testem; plná sada zelená před commitem (commit jen řetězeně `&&` po testech).
- CI: po pushi ověřit poslední běh „Build VEGA APK“; červený = první úkol dalšího kola.
- Nic nepředstírat: telefon = NEOVĚŘENO, dokud to neověří člověk (`PHONE-CHECKLIST.md`).
- Placená volání 0 Kč; AVIOR (`app/`, `core/`) beze změn; žádné tajné klíče v repozitáři.
- Nalezená, ale neopravená chyba (rizikové / velké) → zápis do `PLAN-DEN.md` s návrhem, ne tichá změna.
