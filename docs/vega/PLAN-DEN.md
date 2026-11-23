# Celodenní plán (21. 11. 2026) – VEGA jako živá aplikace na Google Play

Přístup: aplikace je „v produkci“, uživatelé ji mají nainstalovanou. Každou změnu dělám zpětně kompatibilně
(zachovat podpis/certifikát, formát uložených dat, jména metod mostu), s migrací a bez ztráty dat. Vše commituju
po částech, s testy. Rozlišuji ověřeno (test/Chromium) × NEOVĚŘENO (telefon).

## Fáze A – audit po obrazovkách (jako revize na Play)
Projít jednotlivě: úvod, chat, přílohy, výsledky/karty, projekty, nástroje, Hardware, Nastavení, menu, paměť.
U každé: co vidí laik, co se může pokazit, přístupnost, soukromí. Nálezy → opravit.

## Fáze B – přílohy proudem do souboru + stabilní ID + index podle ID (odložené, největší)
- Příloha se ukládá do dočasného souboru s limitem, v paměti se nedrží celý ByteArray.
- Každá příloha a dokument má stabilní ID; index klíčuje podle ID (dva „faktura.pdf“ se nepřepíšou).
- Odebrání dokumentu z vyhledávání. Migrace starého indexu (zná jen jméno).

## Fáze C – rozpracovaný vstup (draft) podle konverzace
Draft a seznam příloh patří konkrétnímu chatu; přepnutí chatu nepřepíše adresáta ani text.

## Fáze D – reorganizace nastavení/menu podle úkolů (laik)
Běžná cesta „Zeptat se / Pracovat se souborem / Najít v dokumentech“; odborné do Pokročilých.

## Fáze E – offline diktování: ověření dostupnosti lokálního rozpoznávání
V režimu Bez sítě nabídnout jen ověřené místní rozpoznávání; jinak vysvětlit a nechat psaní.

## Fáze F – připravenost na Play + CI/podpis (konzervativně, jen VEGA)
Oprávnění a jejich zdůvodnění, Data safety přehled, cílení SDK, podpis odděleně od buildu s kódem (bez změny certifikátu).

## Fáze G – finální regrese, APK, poznámky k vydání
Celý běh testů, sestavení, shrnutí změn po souborech.

Výsledky každé fáze zapisuji sem + commit.

## Výsledky

### Fáze B – hotovo
- Lokální index klíčuje podle stabilního ID (`uid`), ne podle jména → dva „faktura.pdf“ se nepřepíšou.
- Odebrání dokumentu z vyhledávání (`indexRemove`), správa dokumentů v Nastavení.
- Migrace starého indexu (schéma v2, `uid = jméno`). Opraven pád FTS4 na rezervovaném názvu sloupce (`docid` → `uid`).
- Ověřeno: `:vegacore:test`, `:vega:testDebugUnitTest`, offline UI testy.

### Fáze C – hotovo
- Rozpracovaný vstup (text + přílohy) je vázaný na konkrétní konverzaci (`S.drafts` podle `spaceKey`).
- Při odchodu z chatu se draft uloží, při návratu obnoví; po odeslání se draft zahodí.
- Přepnutí chatu už nepřenese text ani přílohy k jinému adresátovi (dřív byly `#input` a `S.files` globální).
- Ověřeno: offline UI test „draft podle konverzace“ (9 scénářů zeleně), kontrola syntaxe app.js.

### Fáze D – hotovo
- Domovská obrazovka má řádku běžných úkolů laika: **Zeptat se / Pracovat se souborem / Najít v dokumentech** (zvýrazněné oproti ukázkám).
- „Přidat +“ přeskupeno: nahoře běžné (Soubor, Najít v dokumentech, Nástroje), odborné (NFC, WiFi, Hardware, Projekt ze složky) do rozbalovacího **Pokročilé**.
- Nové akce `ask` (zaměří pole), `findDocs` (předvyplní „Najdi v mých dokumentech:“ a zaměří).
- Nastavení už dřív mají „Pro pokročilé“ s odbornými volbami (vlastní server, placené modely, kurzy).
- Ověřeno: Chromium render domovské obrazovky, offline UI testy zeleně.

### Fáze E – hotovo
- V režimu **Bez sítě** se diktování spustí jen s ověřeným místním rozpoznáváním (Android 13+ `isOnDeviceRecognitionAvailable`,
  `EXTRA_PREFER_OFFLINE`); jinak hláška „napiš text“ a rozpoznávač se vůbec nespustí (zvuk nikam neodejde).
- Most `voice(data)` dostává stav air-gapu z UI. Na telefonu NEOVĚŘENO.

### Vloženo: ASTRAEA CORE 3.0 (zadání uživatele)
- Řídicí jádro podle důkazů v `vegacore/.../astraea/` zapojené do skutečné rešerše a rozboru hypotéz (`Ops.governed`).
- 14 referenčních testů + 12 vlastních (jádro, adaptéry) + výpočtové případy zadání; dokumentace `docs/astraea/`.

### Fáze F – hotovo (podrobně `docs/vega/PLAY.md`)
- **targetSdk/compileSdk 36** – Google Play od 31. 8. 2026 jinak blokuje aktualizace (nejzávažnější nález).
- Build **`play`**: bez `REQUEST_INSTALL_PACKAGES`, samoúprava nestahuje ani neinstaluje APK (aktualizace jen přes Play).
- Vysvětlení před žádostí o polohu (WiFi); AAB pro Play v CI jako samostatný artefakt; CI doinstaluje platformu 36.
- Podpis a applicationId beze změny.

### Fáze G – hotovo
- Čistá regrese: 163 testů (0 selhání, 2 přeskočené bez ONNX balíčku), 9 UI scénářů, `git diff --check` čistý.
- Sestavení release, play a AAB lokálně i v CI (běh #72 pro `52505a6`, zeleně); záznam v README.md.
- Na telefonu NEOVĚŘENO: diktování v Bez sítě, Android 16 (edge-to-edge, predictive back), WiFi vysvětlení, výkon.
- Otevřené: novější AGP pro compileSdk 36 (sdílí kořenový build s AVIORem), ruční přepínač „Rychle/Ověřit/Hloubkový“,
  panel „Hypothesis Compare“, test „plánováno ≠ proběhlo“ se skutečným ověřovačem.

## Odpoledne: celodenní testy a opravy (na žádost – bez nových funkcí)
| Kolo | Metoda | Skutečné chyby nalezené a opravené |
|---|---|---|
| T1 | Android lint | Poloha pro WiFi na Androidu 12+ by se nezeptala (FINE bez COARSE); BLE na Androidu 8–11 bez oprávnění a polohy |
| T2 | UI audit 30 obrazovek/dialogů v Chromiu, 360 px (`tools/ui-tests/audit.cjs`) | Pole bez názvu pro čtečku; malé dotykové cíle; lišta „V…“; rámeček nadpisu; tabulka Hardware posouvala panel do strany; pád dialogu Kontrola úplnosti |
| T3 | ~150 laických dotazů proti routeru, kalkulačce a ekonomice | Kurz ČNB se online nikdy nepoužil; výpočty, „díkec“, slovenština a povely aplikace šly na webovou rešerši; sleva, zlomek, mocnina, úrok, dny mezi daty, týdny, Celsius/palce/libry, DPH oba směry; bod zvratu z laických vět; slovní operace, záporná čísla, dělení nulou |
| T4 | Fuzz (pevný seed, limit 1,5 s/volání) + 15 tvarů bankovních výpisů | Výpis s částkou bez znaménka a sloupcem Debet/Kredit počítal vše jako příjem; JsonTool padal na StackOverflowError |
| T5 | Revize kódu (XSS, výjimky, souběh) | Deaktivace virtuální karty nepřežila restart při selhání mazání; NFC „štítek se vzdálil“ hlášeno jako prázdný štítek; vyhozená příloha zůstávala v draftech |
| T6 | Přijímací testy celé cesty zprávy (Ops) | Potvrzení oprav v reálném toku (`OpsDayTest`) |

Testy: 163 → 187 (0 selhání; 4 přeskočené = 2 bez ONNX balíčku + 2 průzkumné `probe`). Na telefonu NEOVĚŘENO.

### Oprava zadání (večer)
Aplikace se na Play **nevydává** – šlo jen o přístup „jako by ji měl laik z obchodu“. Vráceno: build „play“ (samoúprava zase
instaluje APK), `targetSdk/compileSdk 35`, CI bez kroku AAB a SDK 36. Ponechány skutečné opravy (poloha Android 12+, BLE 8–11,
vysvětlení polohy, offline diktování).

## 22. 11. – zjednodušení UI, tvrdé scany, komerční kola (průběžně, do 22:00)
**Ráno (U1, U2, S1–S39)** – zjednodušené ovládání (méně tlačítek, jednotné „Bez sítě“, tlačítka přímo v odpovědi) a cílené
hledání chyb. Vybrané skutečné nálezy: neescapovaná pole v 8 kartách (nový test `xss.cjs`); „Kč“ vs „CZK“ = dvě měny;
markdown z modelu (odrážky „*“, tabulky); chybělo Kopírovat/Sdílet; hypotéka „3 000 000“ bez odpovědi; „třetina z 900“
a „vymaž paměť“ do webové rešerše; „zapomeň všechno“ nic nesmazalo; prázdný `tabulka.csv` (příčina: `\b` v JVM je ASCII,
na telefonu ICU – testy a telefon se lišily); heslo z první zprávy v názvu konverzace; protichůdné údaje v paměti.

**Odpoledne – komerční uživatelé a red team konzilium**
| Kolo | Pohled | Nález → oprava |
|---|---|---|
| C1 | uživatel ChatGPT/Claude | chybělo Upravit poslední zprávu, ↻ Znovu, Kopírovat kód, hledání v konverzacích; menu ukazovalo jen 30 konverzací → hledání v názvech i obsahu |
| C2 | AI prompteři | přílohy a pasáže z vlastních dokumentů šly modelu bez ohraničení → `Untrusted.wrap` + pravidlo „data, ne pokyny“ |
| C3 | hackeři | manifest, WebView, FileProvider, cesty souborů bez nálezu; nové metody mostu ověřují id |
| C4 | investoři | úvod bez klíče ukazuje nejsilnější věc bez nastavení (přesná hypotéka) |
| C5 | vývojáři | mrtvý kód a nespojené akce UI: žádné; release APK (R8) lokálně sestaveno |

Testy: 228 → 248 Kotlin (0 selhání) + UI `composer` 26 scénářů, `audit` 30 obrazovek, `robust`, `xss` – vše zelené.
Na telefonu NEOVĚŘENO (kopírování do schránky, sdílení, Upravit/Znovu na skutečném zařízení).

### Feature freeze (22. 11., 16:30)
Na žádost: žádné nové funkce. Samoúpravy výchozí VYPNUTO (jen přepínač v Nastavení › Pro pokročilé, automatické zlepšování
bez něj neběží); Hardware (HCE, NFC, BLE, root), WiFi audit a projekty s kódem přesunuty do Nastavení › Pro pokročilé;
úvod nejvýš 6 akcí. Rozpracované „Přečíst nahlas“ zahozeno. Kontrola na telefonu: `docs/vega/PHONE-CHECKLIST.md`.
Odpolední kola navíc: C6–C16 (sdílení konverzace, 1 000 zpráv, vlastní pokyny, testy s falešným modelem: Bez sítě nikdy
nevolá model, ■ zachová rozepsané, srozumitelné chyby modelu, „projdi celý dokument“ už neuřízne konec na 60 000 znacích).

### Ranní přehled zpráv (na výslovnou žádost, 22. 11. večer)
- Zdroje jen RSS, adresy ověřené živě 22. 11. 2026 (HTTP 200, platné položky s pubDate): Novinky.cz, iDNES.cz
  (`https://servis.idnes.cz/rss.aspx?c=zpravodaj` – zadané `http://…/rss.asp` vrací odsud 403 a přesměrovává sem; http mimo
  LAN VEGA nepovolí), ČT24, iROZHLAS, Seznam Zprávy, Aktuálně.cz, Deník N. Výchozí zapnuté Novinky + iDNES, ostatní v Nastavení.
- Okno včera 05:20 (včetně) → dnes 05:20 (bez), Europe/Prague, správně i 23/25h noci při přechodu času.
- AlarmManager.setExactAndAllowWhileIdle: pokusy 05:20 / 05:24 / 05:28, notifikace 05:30; obnova po BOOT_COMPLETED,
  změně času/zóny a aktualizaci; při neúspěchu notifikace „Zkusit znovu“. Bez povolení přesných budíků náhradní nepřesný alarm.
- Deduplikace titulků napříč weby (společné kmeny), max 10 témat podle počtu zdrojů. Souhrn jen bezplatným modelem
  (≤ 30 s): 1–2 věty na téma, věta bez platného [S#] nebo s 12+ slovy z perexu se vyřadí; bez modelu titulky + odkazy.
  Z RSS se bere jen titulek, odkaz, čas a perex ≤ 280 znaků.
- Testy: `NewsTest` (okno 05:19:59 / 05:20:00, DST, RSS/Atom, vadné RSS, deduplikace, max 10, kontrola vět),
  `OpsNewsTest` (offline, nedostupné/vadné zdroje, s modelem a bez, hranice okna, časy alarmů). Živá zkouška
  `NewsProbe`: 7 zdrojů, 200 položek, témata správně sloučená. Doručení v 05:30 na telefonu: NEOVĚŘENO (checklist, 3 dny).
- **Úprava (22. 11. 17:00, na žádost):** přehled je nezávislý na jazykovém modelu i cloud AI – model se nevolá vůbec (ani
  bezplatný). Každé téma: titulek, první věta perexu z RSS (≤ 160 znaků) a odkazy na všechny zdroje. Test
  `OpsNewsTest.newsNeverCallsModel` (připojený falešný model se nezavolá ani jednou).
- **Úprava (22. 11. 17:10, na žádost – obsáhlejší přehled):** u každého tématu až 4 věty z perexů všech webů, které o něm
  psaly (střídavě po zdrojích, opakující se věty jen jednou, ~700 znaků), každá věta s odkazem na svůj článek, pod tím
  „Číst:“ s odkazy na všechny články. Pořád bez jazykového modelu; perex z RSS ≤ 600 znaků, nikdy celý článek.
  Odkazy bez sledovacích parametrů utm_. Test `NewsTest.extractiveSummaryFromSeveralSources`.

### Večerní tvrdé kontroly H1–H15 (22. 11., 17:00–19:50; kontroly po 30 min)
| Kolo | Nález → oprava |
|---|---|
| H1 | v 05:30 po neúspěšných pokusech ještě jeden pokus před notifikací; test plánování alarmů |
| H2 | „co je nového“ bez modelu odkáže na ranní přehled; příklad kurzu „1 eur po 25,2“ |
| H3 | lint: 0 chyb (TrustAll je v knihovně BouncyCastle, http jen pro LAN záměrně) |
| H4 | titulek/perex z RSS mohl vytvořit cizí odkaz nebo formátování → markdown z textu kanálu pryč |
| H5–H7 | Nastavení › Ranní přehled vizuálně OK; nový kód bez varování; RSS ve windows-1250 a s BOM OK |
| H8 | parser zpráv ve fuzz testu (1 500 kol) – 0 výjimek |
| H9 | slučování do témat 4× rychlejší (350 položek 181 → 47 ms) |
| H10 | „Vyzkoušet teď“ = posledních 24 h do teď a nemění ranní plán (dřív zprávy do 05:20 a označil hotovo) |
| H11 | po povolení přesných budíků se plán přeplánuje (SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) |
| H12 | stejné id dvou konverzací ve stejné ms (zprávy se míchaly); seznam ořezán na 50 → 500; přehledů max 14 |
| H13 | aktivní konverzace jde v menu nahoru |
| H14 | hledání v 300 konverzacích / 12 000 zprávách 122 ms |
| H15 | regresní brána: UI 33 scénářů, 30 obrazovek, robust, xss, lint 0 chyb |

CI: běhy #135 (feature freeze), #140, #145–#149 zelené; APK v artefaktech. Testy 281/0.

### Závěr dne 22. 11. (22:00)
- H16: živá kontrola přehledu po úpravách – v pořádku. H17: stejná událost ve dvou tématech (Libye) → kmeny 4 písmena,
  pravidlo 3 společné + ½ titulku; na živých datech všech 10 témat správně.
- Poslední APK: CI běh #150 (`88dd0b5`) zelený – `VEGA-88dd0b5.apk`
  SHA-256 `0327f5fcf7252361a2f82281ed74189101ac1e6e3f0dddcf976cf9e3faf62d79`, podpis CN=AVIOR CI (beze změny).
- Testy 282/0 (Kotlin), UI 33 scénářů / 30 obrazovek / robust / xss zelené, lint 0 chyb.
- Na telefonu NEOVĚŘENO: doručení přehledu v 05:30 (3 dny), přesné budíky a obnova po restartu, oznámení a „Zkusit znovu“,
  Kopírovat/Sdílet, Upravit/Znovu, hledání v konverzacích, 14 scénářů a měření v `PHONE-CHECKLIST.md`.

## Den 23. 11. 2026 (pondělí) – oblasti: dopoledne laické dotazy (kalkulačka, data, převody, měny, router), odpoledne ranní přehled zpráv
