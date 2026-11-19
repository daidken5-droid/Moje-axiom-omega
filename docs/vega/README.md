# VEGA 1.0 – předávací přehled

VEGA je samostatná aplikace postavená podle zadání „VYKR / Opus Kit“. Je to osobní český parťák a kapesní sada nástrojů pro Pixel 9a.

- Instaluje se vedle AVIORu: má vlastní `applicationId` (`cz.axiom.vega`), ikonu a úložiště.
- Ověřené části AVIORu přebírá z jeho jádra (`:core`).

## Jméno

Zadání nabízelo značku VYKR, ale uživatel chtěl zkusit lepší jméno. Zvolil jsem **VEGA**:

- je to hvězda jako Avior, takže obě aplikace tvoří jednu rodinu;
- čte se česky bez váhání;
- znak z kitu (V s korálovou stopou) k němu sedí beze změny.

Jméno je jen v `vega/src/main/res/values/strings.xml` (`app_name`) a v UI (`index.html`). Přejmenování zpět na VYKR jsou dvě řádky.

## Výchozí revize AVIORu

VEGA vychází z commitu `fb39386` (AVIOR Delta IV · 1020) ve větvi `claude/reverse-engineer-ai-app-o5jhyd`. Novější zdroje nebyly k dispozici.

## Architektura

```
:core       (AVIOR, beze změny) cloudové adaptéry, CostGuard, normalizace češtiny, WebGround, LongInput, Reflex, Extract
:vegacore   (nové, čistý Kotlin)  nástroje · postupy · paměť · router · pracovní prostor · kód · výzva · NDEF
:vega       (nové, Android)       UI (WebView), most, NFC, výběr souborů, OCR/PDF, lokální index, výstupy
```

- **Jedna registrace nástroje** (`ToolDef`) obsahuje vstup, výsledný typ, dostupnost offline, oprávnění, chybové stavy a zrušitelnost.
- Chat (`Router` → `Ops`) i ruční nabídka volají stejné `ToolDef.run`. Test to ověřuje porovnáním obou výsledků.
- **Každý výsledek je běh** (`Run`) s kontrolním bodem po každém kroku:
  - každý mezivýsledek jde otevřít;
  - po chybě, zrušení nebo pádu aplikace jde pokračovat od posledního hotového kroku.
- **Uložený postup** má verzi, popis vstupu, výstup, oprávnění, historii změn a jde smazat.

## Co je převzato z AVIORu, co ne a proč

| Funkce AVIORu | Ve VEGA | Proč |
|---|---|---|
| Cloudové adaptéry (Groq, Cerebras, OpenRouter, DeepSeek, Claude SDK) a přepnutí při limitu | ano, z `:core` | Ověřené testy jádra AVIORu |
| CostGuard (povolení, rozpočet, odhad, záznam útraty) | ano; rozpočet v Kč, výchozí **0 Kč** a schválení konkrétního běhu po náhledu | Zadání |
| Normalizace češtiny, holé téma (`Topic.bare`), dlouhý vstup (`LongInput`) | ano | Router a rešerše |
| Webové podklady (`WebGround`, SSRF ochrana `Web`) | ano | Rešerše z názvu, čtení URL |
| Čtení PDF (PdfBox) a OCR v telefonu (ML Kit) | ano (`FileText`) | Lokální index s OCR |
| Deterministické odpovědi bez modelu (`Reflex`: čas, výpočty, převody) | ano | Rozhovor bez modelu a v air-gap režimu |
| Lokální mozek llama.cpp | **ne** | Online aplikace. Ušetří stovky MB APK a GB RAM; ruční nástroje model nepotřebují. |
| Offline Wikipedie a RAG index | **ne** (zatím) | Balíky mají jednotky GB. Lokální index pokrývá soubory uživatele. |
| RANDY, E-ARL, Pavučina, Strážce faktů | **ne** | Řídí lokální mozek a znalostní bázi AVIORu, ve VEGA nemají co řídit |

AVIOR zůstává beze změny. VEGA ho neovlivňuje: má vlastní moduly a samostatné CI workflow (`build-vega.yml`). Workflow AVIORu změny VEGA ignoruje.

## Pokrytí zadání

| Oblast | Stav |
|---|---|
| Ukázka prvního spuštění (výpis → nástroj → postup → jiný soubor po restartu) | Hotovo; ověřeno na skutečných souborech CSV (Windows-1250, úvodní řádky banky, uvozovky, desetinné čárky) |
| Dvě rovnocenné cesty (chat × ruční nástroj, stejná implementace) | Hotovo; test porovnává výsledky obou cest |
| Kapesní nástroje | Hotovo, viz seznam pod tabulkou |
| Spojení dvou nástrojů a uložení postupu | Hotovo: „Předat dál“ a „Uložit jako postup“; postup se spouští z domovské obrazovky i z chatu |
| Pokračování od posledního hotového kroku | Hotovo, i po serializaci (obnova po pádu) |
| Paměť s původem | Hotovo, podrobnosti pod tabulkou |
| Programování | Hotovo, podrobnosti pod tabulkou |
| Rešerše z holého názvu | Hotovo: pravopisné varianty, možné identity, zdroje s původním textem a datem; syntéza modelem s citacemi |
| Dlouhý vstup (více požadavků) | Hotovo: rozdělení nad limit, odpověď na každý požadavek, kontrola úplnosti, otevíratelné citované pasáže |
| NFC (NDEF) | Parser ověřen na fixture. Reader mode, chybějící hardware a vypnuté NFC jsou ošetřeny. **Fyzické čtení štítku NEOVĚŘENO.** |
| Předžalobní výzva (syntetický případ) | Hotovo: původ každé částky, sazba k rozhodnému datu, DOCX a PDF se zpětnou kontrolou obsahu |
| Cloud, cena, souhlas | Výchozí placená spotřeba 0 Kč; před odesláním souborů se ukáže rozsah a cíl; schválení konkrétního běhu |
| Identita a ikona | Adaptivní ikona: popředí z vektoru kitu (zmenšené do bezpečné zóny kruhové masky), inkoustové pozadí, jednobarevná varianta |

**Kapesní nástroje:**
- zpracování výpisu a součty po měsících;
- inspektor CSV s diagnostikou, filtrem a exportem;
- inspektor JSON (cesta, tabulka);
- diff textu a kódu (Myers);
- kontrolní součty;
- hledání v logu a rozbor pádu (Java, Kotlin, Python, JS);
- čtení veřejné URL;
- NDEF;
- lokální index s OCR.

**Paměť s původem:**
- preference, fakta projektu a postupy; dočasný chat;
- „pamatuj si“ se uloží hned; opakovaný vzorec je jen návrh;
- oprava jde vrátit;
- zapomenutí smaže i index a nevrátí se ani ze shrnutí;
- projekty jsou oddělené;
- tajné údaje a instrukce z webu se odmítají;
- přehled „Co si pamatuješ a proč?“.

**Programování:**
- založení projektu, import složky (pracuje se na kopii);
- symboly a volající, čtení konfigurace bez souborů s klíči;
- přesné úpravy s kontrolou verze, diff, návrat jedné změny bez ztráty jiných úprav, export ZIP;
- JS testy skutečně běží v izolovaném Workeru: bez sítě (CSP + odstraněné API), s časovým limitem;
- ostatní jazyky se kontrolují jen staticky a výstup to výslovně uvádí.

### Co není hotové nebo ověřené

- **Na Pixelu nic neověřeno.** Neměřil jsem start, paměť, odezvu ani skutečný NFC štítek.
- **Živé API neověřeno.** Bez klíčů jsem nezkoušel skutečné volání Groq, Claude ani dalších, cenu, kontext ani volání nástrojů. Cesty k modelu jsou napsané, ale nevyzkoušené.
- **Limit paměti** u běhu testů není vynucený, jen časový limit a zákaz sítě. WebView limit RAM pro Worker nenabízí.
- **Trénink vah neprobíhá.** „Učení“ je paměť, která mění systémový pokyn modelu. Změna chování po restartu je doložena testem paměti (stejný vstup → jiný kontext).
- **Výzva:** tabulka repo sazeb ČNB je ověřená do **30. 6. 2025**. Pro pozdější prodlení aplikace výslovně varuje a chce sazbu ručně. Data faktury se zadávají formulářem; automatické vytažení z PDF faktury zatím chybí.
- **Plán projektu s obnovou druhý den a přepočtem závislých výsledků** (zkouška 6, část 2) není samostatná funkce. Částečně ho pokrývá deník rozhodnutí projektu a další krok.
- **Porovnání výkonu s AVIORem za stejných podmínek** jsem nedělal. Vyžaduje telefon; AVIOR navíc nemá ekvivalentní nástroje.

## Ověření (kde a jak)

| Co | Kde | Výsledek |
|---|---|---|
| Jádro VEGA, 15 testů: CSV, výpis, postupy, pokračování, paměť, router, diff, součty, JSON, log, NDEF, pracovní prostor, kód, výzva, DOCX, pokrytí | JVM (`:vegacore:test`) | prošlo |
| Přijímací zkoušky aplikace, 3 testy: výpis → postup → restart → jiný soubor; paměť po restartu s opravou, zapomenutím a projekty; bez sítě a bez modelu (air-gap) | JVM + Android SQLite (Robolectric, `:vega:testDebugUnitTest`) | prošlo |
| UI: 6 obrazovek a skutečný běh JS testů ve Workeru pod CSP stránky (úspěch = 0, selhání = 1, nekonečná smyčka = 124, `fetch` nedostupný) | Chromium (Playwright) s náhradním mostem | prošlo |
| Sestavení release a debug APK | lokálně + CI `build-vega.yml` | release 31,5 MB, debug 49 MB |

**Výkon lokálních nástrojů (JVM v kontejneru, ne Pixel):**

| Nástroj | p50 | p95 |
|---|---|---|
| Výpis, 10 000 řádků | 34 ms | 51 ms |
| Inspektor CSV, 10 000 řádků | 11 ms | 32 ms |
| Diff, 5 000 řádků | 0,8 ms | 1,5 ms |
| SHA-256, 10 MB | 7 ms | 7 ms |
| Hledání v logu, 50 000 řádků | 3,9 ms | 4,3 ms |
| Router | 0,04 ms | 0,1 ms |

Ruční nástroje nedělají žádné cloudové volání.

## Reprodukce

```
./gradlew :vegacore:test :vega:testDebugUnitTest      # testy
./gradlew :vega:assembleRelease                       # APK (podpis z OMEGA_KEYSTORE_* nebo debug klíč)
```

Ukázková data jsou v `vegacore/src/test/resources/fixtures/`:
- `vypis-duben.csv` – Windows-1250, úvodní řádky banky, uvozovky;
- `vypis-kveten.csv`.

Syntetický případ výzvy: `Demand.sample()`.
