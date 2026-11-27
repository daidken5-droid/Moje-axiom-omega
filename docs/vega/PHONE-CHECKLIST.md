# VEGA – kontrola na telefonu (feature freeze 22. 11. 2026)

Všech 14 scénářů označených 📱 v [`TEST-PLAN-LAIK.md`](TEST-PLAN-LAIK.md) a čtyři měření. Na hostu (JVM testy,
Chromium) to ověřit nejde – sloupec **Výsledek** vyplň na skutečném telefonu (✅ / ⚠ + krátký popis).

Zařízení: ____________________ · Android: ______ · APK (commit / SHA-256): ____________________ · Datum: __________

## Scénáře

| # | Scénář (kroky) | Očekávání | Výsledek |
|---|---|---|---|
| 4 | Zavřu a otevřu aplikaci. | Je přihlášeno, úvod bez průvodce. | |
| 9 | Výpadek sítě uprostřed odpovědi (zapnout režim letadlo během psaní). | Rozepsaný text uložený, nabídka „Pokračovat“. | |
| 10 | Přiložím PDF / fotku účtenky / DOCX + „shrň“. | Text se skutečně přečte (u fotky OCR), žádné binární smetí. | |
| 15 | Druhý telefon: přečíst virtuální kartu (Nastavení › Pro pokročilé › Hardware). | Zobrazí přesně aktivní kartu; po deaktivaci už ne. | |
| 15b | Velká karta (hodně vyplněných polí, přes 256 B) + druhý telefon se starší i novou VEGA (27. 11.: čtení po částech). | Přečte se celá, všechna pole; stará čtečka i stará karta fungují dál. NEOVĚŘENO | |
| 16 | Zápis na NFC štítek s malou kapacitou. | Hláška „nevejde se“, štítek nepoškozený. | |
| 16b | (27. 11.) Vygenerovat WiFi heslo v Hardware › WiFi QR. | 6 slov + symbol + číslo, kontrola hesla „silné“, QR se načte druhým telefonem. NEOVĚŘENO | |
| 16c | (27. 11.) Štítek s adresou obsahující skrytý znak (zapsat z PC, např. `https://evil.cz/` + U+202E + `zc.knab`). | Adresa se ukáže se ⟦U+202E⟧ a varováním, nenabídne se k otevření. Běžná adresa se otevřít dá. NEOVĚŘENO | |
| 16d | (27. 11.) BLE sken u zařízení se svislítkem nebo dlouhým názvem; WiFi sken u sítě se svislítkem v názvu. | Tabulka drží 3 / 5 sloupců, název zkrácený, nic se nerozpadne. NEOVĚŘENO | |
| 17 | NFC inspektor na náhodném štítku („+“ › Pokročilé). | Typ karty, UID, NDEF; u chráněných „nezasahuji“. | |
| 19 | Bluetooth: hledat zařízení (Pro pokročilé › Hardware). | Seznam se signálem (RSSI), nebo výzva „zapni Bluetooth“. | |
| 20 | Root: „Ověřit root“ na nerootnutém telefonu. | „Root zamítnut / nedostupný“, aplikace běží dál. | |
| 24 | Velké systémové písmo, Android 15 (lišty, klávesnice). | Nic se neořízne, pole pro psaní není zakryté. | |
| 36 | Bez sítě + mikrofon. | Bez místního rozpoznávání hláška „napiš text“, nic se nespustí. | |
| 37 | Rešerše s ověřovačem citací. | Štítek „doloženo / částečně / nedoloženo“, nepodložené věty vynechány, rozpor vidět. | |
| 39 | „WiFi audit“ poprvé (Pro pokročilé). | Vysvětlení „Proč WiFi potřebuje polohu“ před systémovým dialogem. | |
| 40 | Samoúprava: hotové sestavení (nejdřív zapnout přepínač „Samoúpravy aplikace“ v Pro pokročilé – výchozí je vypnuto). | Nabídne „Projít změny a nainstalovat“, instalaci potvrdí uživatel v systému. | |
| 1a | Výchozí stav po čisté instalaci: samoúpravy vypnuté, v „+“ ani v menu není Hardware / WiFi / Projekty, úvod nejvýš 6 akcí. | Odpovídá popisu. | |

> Pozn.: plán má 13 řádků s 📱 a hlavičku se statusem; čtrnáctý řádek (1a) ověřuje stav po dnešním feature freeze.

## Ranní přehled zpráv (05:30)

Příprava: Nastavení › Ranní přehled zpráv › zapnout, povolit oznámení; když se ukáže „Povolit přesný čas“, povolit.
Telefon nechat přes noc normálně (nabíjet, ne vypínat). Jednou během testu telefon restartovat (ověří obnovu plánu).

| Den | Čas notifikace (má být 05:30) | Témat / zdrojů | Věty mají odkazy, nic bez zdroje | Poznámka (restart, Doze, offline) | Výsledek |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |

Doplňkové: v noci vypnout data i WiFi → v 05:30 notifikace „Ranní přehled se nepodařil“ s tlačítkem „Zkusit znovu“, po zapnutí
internetu a klepnutí přijde přehled. Výsledek: ______

## Měření

| Měření | Postup | Cíl | Výsledek |
|---|---|---|---|
| Studený start | `adb shell am force-stop cz.axiom.vega` → `adb shell am start -W -n cz.axiom.vega/.MainActivity` → hodnota `TotalTime`, 3× a medián | < 1 500 ms | |
| 1. odpověď offline | Režim Bez sítě, nový chat, „kolik je 15 % z 2400“ → stopky od odeslání do zobrazení odpovědi, 3× a medián | < 500 ms | |
| RAM | Po 5 výměnách v chatu: `adb shell dumpsys meminfo cz.axiom.vega` → řádek `TOTAL PSS` | < 250 MB | |
| Velikost APK | `ls -l VEGA-<sha>.apk` z artefaktu CI (release) a velikost po instalaci (Nastavení telefonu › Aplikace › VEGA › Úložiště) | release < 60 MB | |

Referenční sestavení na hostu (ne telefon): release 39,8 MB, debug 74,7 MB (22. 11., lokální build bez podpisu z CI).

## Odpověď se zhasnutou / zamčenou obrazovkou (hlášení uživatele 25. 11., oprava WorkService)

| Krok | Postup na Pixelu 9a | Očekávání | Výsledek |
|---|---|---|---|
| Zamknout během odpovědi | S modelem v cloudu napiš „napiš podrobný plán stěhování na 10 bodů“, hned po odeslání zamkni telefon, počkej 1 minutu, odemkni | V liště oznámení během práce „VEGA dokončuje odpověď…“; po odemčení je celá odpověď v chatu, nic „nezamrzlo“ | NEOVĚŘENO |
| Obrazovka zhasne sama | Totéž, ale nech obrazovku zhasnout časovačem | Totéž | NEOVĚŘENO |
| Oznámení zmizí | Po dokončení odpovědi | Oznámení „VEGA dokončuje odpověď…“ zmizí do ~1 s; u rychlého výpočtu („kolik je 12 × 250“) se vůbec neukáže | NEOVĚŘENO |
| Baterie | Nastavení › Baterie › VEGA po dni běžného používání | Žádné dlouhé držení probuzení (wake lock nejvýš po dobu odpovědi, strop 15 min) | NEOVĚŘENO |

## Autonomie na Pixelu 9a (doporučení S3, 25. 11.) – telefon = NEOVĚŘENO

Ke každému běhu zapiš: **revize** (commit z názvu APK, např. `ce6543a`), **zařízení** (Pixel 9a), **verze Androidu** (Nastavení › O telefonu), **výsledek**.
Stabilní scénáře se pak převedou do emulátorového CI.

| Scénář | Postup | Co zaznamenat | Výsledek |
|---|---|---|---|
| Naučené pravidlo a vrácení | Opakovaně oprav VEGA u stejného typu dotazu („ne, chci to jako tabulku“), až nabídne pravidlo; zapni ho. Polož stejný dotaz → Menu › Autonomie › Vrátit → stejný dotaz → zavři VEGA ze seznamu aplikací → otevři → stejný dotaz | Odpověď na stejný dotaz před aktivací, po aktivaci a po vrácení; vrácení přetrvá restart | NEOVĚŘENO |
| Verze rozšíření | Aktivuj rozšíření, vyzkoušej druhou verzi, vrať první; restartuj aplikaci | Verze uvedená v UI odpovídá skutečnému výstupu nástroje před i po restartu | NEOVĚŘENO |
| Selhání testu nové verze | Nová verze rozšíření s chybou (nebo zhasni obrazovku během testu) | Předchozí funkční verze zůstane dostupná; UI popíše stav rozpracované změny | NEOVĚŘENO |
| Rešerše v pozadí | Spusť rešerši („rešerše: uzavírka mostu v Ústí“), přepni do jiné aplikace / zamkni telefon; podruhé ukonči VEGA ze seznamu aplikací během rešerše | Po návratu lze otevřít dokončený výsledek (oznámení „VEGA dokončuje odpověď…“); nedokončený běh má srozumitelný stav a „Pokračovat“ | NEOVĚŘENO |
| Samoaktualizace | Nech vypnuté (výchozí); samostatně: zapnout v pokročilém nastavení, přerušit stahování (letadlový režim) | Obnova po přerušení, žádná poloviční instalace | NEOVĚŘENO |

## Výkon na cílovém telefonu (doporučení T3) – telefon = NEOVĚŘENO

Stejná sada místních dokumentů (např. 20 PDF/TXT, ~5 MB celkem – zapsat přesnou velikost vstupu). Měřit zvlášť **první** a **opakovaný** běh.

| Měření | Postup | Výsledek |
|---|---|---|
| Čas k prvnímu výsledku | Od odeslání otázky nad dokumenty do první zobrazené části odpovědi (stopky) | NEOVĚŘENO |
| Čas k úplnému rozboru | Do konce odpovědi / karty „Síla doložení“ | NEOVĚŘENO |
| Max. paměť | `adb shell dumpsys meminfo cz.axiom.vega` během rozboru → `TOTAL PSS` | NEOVĚŘENO |

## Ranní přehled v Doze (hlášení uživatele 25. 11.: „ráno problém poslat ranní zprávy, internet má dostupný“)

| Krok | Postup | Očekávání | Výsledek |
|---|---|---|---|
| Noc | Telefon na noc bez nabíječky, VEGA zavřená, ranní přehled zapnutý, režim Bez sítě VYPNUTÝ | V 05:20 krátce oznámení „VEGA stahuje ranní přehled…“, v 05:30 oznámení s tématy; v chatu „Ranní přehled d. m.“ | NEOVĚŘENO |
| Bez sítě zapnutý | Totéž se zapnutým režimem Bez sítě ve VEGA | Oznámení výslovně: přehled AI nepoužívá, blokuje ho jen režim Bez sítě | NEOVĚŘENO |
| Bez přesných budíků | Nastavení telefonu › Aplikace › VEGA › Budíky a připomenutí = vypnuto; noc jako výše | Přehled přijde i tak (pojistná úloha se sítí) – případně o něco později než 05:30, oznámení jen jednou | NEOVĚŘENO |
| Diagnostika (adb) | `adb shell dumpsys deviceidle force-idle`, pak `adb shell cmd alarm` nebo počkat na 05:20 | Přehled se stáhne i ve vynuceném Doze | NEOVĚŘENO |
