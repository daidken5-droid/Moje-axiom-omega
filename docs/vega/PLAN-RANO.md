# Ranní plán do 8:30 (21. 11. 2026) – laické testy, hledání chyb, vylepšení

Start 6:08 Praha. Tři fáze, průběžné commity. Žádné placené volání (default 0 Kč), nic nefalšovat,
rozlišovat ověřeno (test/Chromium) × NEOVĚŘENO (telefon).

## Fáze 1 (~60 min, do ~7:10): laické testy + otázky jako chatbot
- Baterie 120+ reálných českých laických vět přes skutečný Router + Quick + currency + offline.
- Chatbotí toky v Chromiu: úvod/průvodce, přepínání modelu, Hardware, Nastavení, mazání chatů, QR, zápis NDEF.
- Kontrola: žádný běžný dotaz nespadne do nástroje/projektu; výpočty správně; hlášky česky; žádná JS chyba.
- Nálezy zapsat a hned opravit (pokud drobné), větší do Fáze 2.

## Fáze 2 (~60 min, do ~8:10): deep scan kódu + opravy
- Projít nové i starší moduly (Hardware, NeuralWeb, Rag, Ops bridge, Router, Quick, Statement/Csv).
- Hledat: pády, úniky zdrojů, špatné hranice regexů (ICU), chybějící ošetření, výkon, bezpečnost.
- Opravit, doplnit testy, regresní běh zelený.

## Fáze 3 (~30 min, do ~8:30): vylepšení pro laiky
- 2–4 konkrétní vylepšení, která laik pozná; implementovat to, co je bezpečné a rychlé.
- Finální testy, APK, shrnutí.

Výsledky každé fáze: do tohoto souboru + commit.


## Výsledky Fáze 1 (laické testy) – hotovo ~6:40
- 92 reálných laických vět přes skutečný Router + Quick + currency + offline. Po opravách: **92/92 správně** (žádný běžný dotaz nespadl do nástroje/projektu/rešerše).
- Opraveno: „měj se“ a „jsi chytrá?“ se braly jako rešerše; „levný hash brown“ spouštěl nástroj hash. Doplněn test `ranniLaikRegrese`.
- UI toky v Chromiu (domů, +, Hardware, model, menu, nastavení, WiFi nástroje, editor karty): **bez chyby**.

## Výsledky Fáze 2 (deep scan + opravy) – hotovo ~7:40
- `NeuralWeb.related`: jeden uzel bez sousedů ukončoval celý skok aktivace → opraveno (`continue`).
- BLE sken se nezastavoval při opuštění obrazovky → `onPause` ho teď ukončí a uvolní; hlásí přerušení.
- `NdefFormatable` se po zápisu nezavíral → doplněn `close()`.
- Regresní běh: jádro + aplikace zelené.
