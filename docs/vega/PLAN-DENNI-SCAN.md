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

## Pravidla
- Každá oprava s testem; plná sada zelená před commitem (commit jen řetězeně `&&` po testech).
- CI: po pushi ověřit poslední běh „Build VEGA APK“; červený = první úkol dalšího kola.
- Nic nepředstírat: telefon = NEOVĚŘENO, dokud to neověří člověk (`PHONE-CHECKLIST.md`).
- Placená volání 0 Kč; AVIOR (`app/`, `core/`) beze změn; žádné tajné klíče v repozitáři.
- Nalezená, ale neopravená chyba (rizikové / velké) → zápis do `PLAN-DEN.md` s návrhem, ne tichá změna.
