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
