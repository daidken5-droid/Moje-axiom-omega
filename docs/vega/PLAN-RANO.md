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
