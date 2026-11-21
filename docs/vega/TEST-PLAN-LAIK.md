# VEGA – tvrdý test plán z pohledu laika (jako komerční aplikace)

Cíl: ověřit, že úplný laik (nezná „API klíč“, „token“, „NFC“) appku zvládne bez návodu, nic ho nezablokuje a nic
mu neublíží. Priorita podle dopadu na uživatele. Každý scénář má **kroky → očekávání → status**.

Statusy: ✅ ověřeno (test/Chromium) · 📱 jen na telefonu · ⚠ nalezena chyba (viz Opravy) · ⬜ neprověřeno

## A. První spuštění a onboarding
1. Čistá instalace, žádný klíč. → Úvod ukáže „Zapnout rozhovor zdarma“, ne žargon. ✅
2. Průvodce Gemini: 3 kroky, klíč „AIza…“ nejde splést s „Alza“, po ověření „Hotovo“. ✅
3. Vložím nesmysl místo klíče. → Česká hláška „nevypadá jako klíč“, ne pád. ✅
4. Zavřu a otevřu appku. → Je přihlášeno, úvod bez průvodce. 📱

## B. Běžný chat (správné směrování)
5. „Napiš omluvný e-mail šéfovi“, „co je inflace“, „kolik je 15 % z 2400“. → Odpověď/výpočet, ne nástroj/projekt. ✅
6. „Přepiš to formálněji“, „Pomoz mi s výpisem“. → Rozhovor, ne rešerše. ✅
7. „100 eur na koruny“. → Kurz ČNB (online) / nabídka ručního kurzu (offline). ✅
8. Dlouhé čekání > 20 s. → „Pořád čekám… ■ zastavit“. ✅
9. Výpadek sítě uprostřed odpovědi. → Rozepsané uložené, „Pokračovat“. 📱

## C. Soubory
10. Přiložím PDF/foto účtenky/DOCX + „shrň“. → Skutečně se přečte (OCR), ne binární smetí. ✅ (jádro) / 📱 (OCR)
11. Bankovní CSV (Windows-1250, středník, „1 234,50“). → Tabulka příjmů/výdajů, souhrnné řádky se nepočítají jako chyba. ✅
12. Výpis v PDF. → Jasný návod „stáhni CSV“ pro ČS/ČSOB/Fio/KB. ✅
13. Export výsledku. → Český Excel ho otevře (desetinná čárka), nabídka Otevřít/Sdílet. ✅

## D. Hardware (nové)
14. Vytvořím kartu „Vrtačka, INV-00123“, zaškrtnu 1 pole ke sdílení, aktivuji. → Hláška „aktivní, sdílí se jen…“. ✅ (jádro)
15. Druhý telefon: Přečíst kartu. → Zobrazí přesně aktivní kartu; po deaktivaci už ne. 📱 (protokol ✅)
16. Zápis na štítek: malá kapacita. → „Nevejde se“, bez poškození. 📱 (logika ✅)
17. NFC inspektor na náhodném štítku. → Typ karty, UID, NDEF; u chráněných „nezasahuji“. ✅ / 📱
18. WiFi: moje připojení, sítě v okolí, QR, kontrola hesla. → QR se nikam neuloží, slabé heslo označeno. ✅
19. Bluetooth: hledat zařízení. → Seznam + RSSI, nebo „zapni Bluetooth“. 📱 (logika ✅)
20. Root: Ověřit root na nerootnutém telefonu. → „Root zamítnut/nedostupný“, appka běží dál. 📱

## E. Navigace a správa
21. Tlačítko Domů z projektu/nastavení. → Úvodní obrazovka. ✅
22. Smazat/přejmenovat konverzaci. → Funguje, potvrzení u mazání. ✅
23. Systémové Zpět. → Do předchozí konverzace, ne ven z appky nečekaně. ✅
24. Velké systémové písmo, Android 15 (lišty/klávesnice). → Nic se neořízne, pole není zakryté. 📱

## F. Režimy a bezpečí
25. Zapnu „Bez internetu“. → Výpočty/nástroje jdou; hláška říká, jak ho vypnout. ✅
26. „Pouze vlastní modely“ bez serveru. → Hláška radí vypnout, ne „přidej klíč“. ✅
27. Placený model bez povolení. → Nepoužije se potají, odpoví zdarma. ✅
28. Samoúprava bez GitHubu / „uprav aplikaci Word“. → Nespustí se. ✅

## G. Odolnost a chyby
29. Nečekaná chyba mostu/jádra. → Česká hláška, ne „NullPointerException“. ✅
30. Poškozený soubor chatu/paměti. → Nezničí data, obnoví se. ✅
31. Plné úložiště při exportu. → Srozumitelná hláška, nic se nerozbije. ✅

## H. Výkon
32. Dlouhá odpověď (streamování). → Plynulé, bez trhání. ✅ (naměřeno)
33. 300 zpráv v chatu, přepínání. → Bez zasekání (cache zpráv). ✅

Nalezené chyby a opravy tohoto kola jsou níže v „Opravy kola laik-HW“.


## Opravy kola laik-HW (2.0.0)
- Projetí nových obrazovek Hardware v Chromiu (karta, aktivace/deaktivace, editor, WiFi nástroje, zápis NDEF, root, události) → **žádná chyba aplikace**. (Jedna hláška v logu byl zastaralý testovací soubor, ne appka.)
- Měření uložené do podkladů pro výzkum ve výchozím stavu **vynechává SSID, IP a BSSID** (citlivé identifikátory).
- Automatický regresní běh: jádro + aplikace zelené.

## Revize od GPT – přeneseno do 2.2.0
Z přiloženého auditu (AUDIT-VEGA.md) přeneseny bezpečné a konkrétní opravy (moje větev byla novější, převzato jen smysluplné):
- **WebView tvrdě bez sítě:** `blockNetworkLoads=true`, neobsloužený požadavek → 403, navigace jen na přesnou lokální vstupní stránku (obrana mostu WebView↔nativní).
- **Soukromí příloh:** sdílený soubor z jiné aplikace se při příjmu neindexuje (`indexNow=false`); dočasný chat příchozí přílohu uvolní a vysvětlí, že používá jen text; v dočasném chatu nejde odeslat příloha.
- **Životní cyklus příloh:** `take()` ověří celou sadu ID pod jedním zámkem a při chybějící příloze srozumitelně odmítne; vstup/formulář se čistí až po převzetí mostem (odmítnuté odeslání nezahodí text); sdílený text se připíše na nový řádek.
- **Import složky:** počítají se skutečně přečtené bajty (strop 2 MB/soubor, 80 MB/projekt), limit hloubky, počtu položek i duplicitních cest; velké soubory se započtou do hlášení.
- **PDF/OCR:** poznámka o přečteném rozsahu stran, bitmapa se uvolní ve `finally`, min. 1 px; zápis indexu v jedné transakci s rollbackem.
- **Ověření klíče v režimu Bez sítě:** skončí před počítáním požadavků, UI ukáže „neověřeno“ + „Ověřit znovu“.
- **WiFi QR:** pole hesla je skryté, s tlačítkem Ukázat/Skrýt (i pro čtečku obrazovky).
- **Export PDF:** dlouhý odstavec vyšší než stránka se rozdělí po řádcích přes stránky (dřív se ořízl).
- Přidán offline UI test `tools/ui-tests/composer.cjs` (8 scénářů logiky mostu).

**Záměrně odloženo** (větší/rizikové, mimo bezpečné přenesení): změna podpisu a CI workflow, oddělený samostatný export + gradle wrapper (v monorepu wrapper je), rozsáhlý refaktor Ops.kt/app.js, offline diktování, přílohy proudem do souboru se stabilním ID a index podle ID, reorganizace nastavení podle úkolů. Zapsáno jako další kroky.

## Kolo „den na Play“ (2.3.0)
34. Rozepíšu zprávu v chatu A, přepnu do chatu B a zpět. → Text i přílohy zůstanou v A, B je prázdný. ✅ (UI test)
35. Úvodní obrazovka: „Zeptat se / Pracovat se souborem / Najít v dokumentech“. → Pole se zaměří / výběr souboru / předvyplněné hledání. ✅ (Chromium)
36. Bez sítě + mikrofon. → Bez místního rozpoznávání hláška „napiš text“, nic se nespustí. 📱
37. Rešerše s ověřovačem. → Štítek „doloženo / částečně / nedoloženo“, nepodložené věty vynechány, rozpor vidět. ✅ (jádro) / 📱
38. Rešerše bez ověřovače. → Štítek „neověřený koncept“, odpověď normálně čitelná. ✅ (Chromium)
39. „Sítě v okolí“ poprvé. → Vysvětlení „Proč WiFi potřebuje polohu“ před systémovým dialogem. ✅ (kód) / 📱
40. Verze z Google Play: samoúprava. → Změna se připraví, instalace se nenabízí („přijde aktualizací v Obchodě Play“). ✅ (build play)
