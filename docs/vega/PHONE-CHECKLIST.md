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
| 16 | Zápis na NFC štítek s malou kapacitou. | Hláška „nevejde se“, štítek nepoškozený. | |
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

## Měření

| Měření | Postup | Cíl | Výsledek |
|---|---|---|---|
| Studený start | `adb shell am force-stop cz.axiom.vega` → `adb shell am start -W -n cz.axiom.vega/.MainActivity` → hodnota `TotalTime`, 3× a medián | < 1 500 ms | |
| 1. odpověď offline | Režim Bez sítě, nový chat, „kolik je 15 % z 2400“ → stopky od odeslání do zobrazení odpovědi, 3× a medián | < 500 ms | |
| RAM | Po 5 výměnách v chatu: `adb shell dumpsys meminfo cz.axiom.vega` → řádek `TOTAL PSS` | < 250 MB | |
| Velikost APK | `ls -l VEGA-<sha>.apk` z artefaktu CI (release) a velikost po instalaci (Nastavení telefonu › Aplikace › VEGA › Úložiště) | release < 60 MB | |

Referenční sestavení na hostu (ne telefon): release 39,8 MB, debug 74,7 MB (22. 11., lokální build bez podpisu z CI).
