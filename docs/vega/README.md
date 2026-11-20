# VEGA 1.6 – předávací přehled

## Kola 10–16 + vlastní server + ověřovač citací (1.5.2 → 1.6.0)

**Finální APK:** `VEGA-10ae983.apk` (1.6.0, versionCode 465839), SHA-256 `acdb265ccbda147f0edef9b43d9c5a72b11b45591226897f9dd34ea92af4cc14`, certifikát `4557a2ce…cdb4` (instaluje se přes předchozí verze).

**Testy:** 107 testů jádra i aplikace zelených. Testovalo se jen na hostiteli (JVM + Robolectric, Chromium, chroot s obrazem llama.cpp). Na telefonu nic ověřeno není.

| Kolo | Oblast | Nejvážnější nález → oprava | Co uvidíš |
|---|---|---|---|
| 10 | UI a přístupnost | list se zavíral během běžící úlohy; odkazy v markdownu bez kontroly schématu | tlačítko „↓ Nová odpověď“, větší dotykové cíle |
| 11 | peníze a modely | placený krok šlo schválit nad strop běhu; OpenRouter nabízel placené modely | účtenka po placeném kroku |
| 12 | projekty s kódem | test bez kontroly „prošel“; smyčka oprav volala model zbytečně | české výsledky testů, „⚠ Změnily se i TESTY“ |
| 13 | kvalita odpovědí | skrytý text stránek (display:none) se dostal k modelu; tvrzení bez citace se tvářila ověřeně | „podloženo x z y tvrzení“, datum zdroje |
| 14 | výkon | každá aktualizace odpovědi znovu vykreslovala celý text | plynulé psaní: vykreslení 9kB odpovědi 2,3 s → 0,04 s (naměřeno) |
| 15 | samoúpravy | testy od modelu mohly přepsat soubory před sestavením APK; `.gitattributes` vypnul hlídač | srozumitelný důvod selhání + odkaz na CI |
| 16 | závěr | regresní běh všech testů, verze 1.6.0 | – |

**Vlastní AI server** (`tools/vega-server`):
- Jde o llama.cpp s modelem Qwen3-4B (Apache 2.0) a povinným klíčem. Běží zdarma na Hugging Face Space nebo na tvém PC.
- VEGA se k němu připojí přes **Nastavení → Vlastní model → Párovací kód**.
- Ověřeno klientem VEGA proti skutečnému serveru (stejný obraz jako pro HF, 2 vlákna jako free CPU):
  - česká odpověď, ~4 tokeny/s;
  - bez klíče 401.
- NEOVĚŘENO: nasazení na tvém HF účtu a spojení z telefonu.

**Ověřovač citací:**
- Balíček vydává CI jako GitHub Release `overovac-referencni-1` (149 MB). Je sestavený deterministicky a otisk modelu sedí s tím, který VEGA zná.
- Ve VEGA: **Nastavení → Ověřovač citací → Stáhnout (150 MB)**. Nainstaluje se jen balíček se známým otiskem.
- Ověřeno, že stažený balíček VEGA načte a vyhodnotí (záměna osoby → ODPORUJE, zápor → ODPORUJE).
- Známá slabina modelu: „plánovalo se“ vs. „uskutečnilo se“ vyhodnotí jako PODPORUJE.

**Musíš udělat ty:** viz `docs/vega/AUTONOMIE.md`, oddíl „Po kole 15“ (workflow AVIORu, ruleset na tagy, vlastní podpisový klíč).


## Shrnutí: 9 kol red-team kontroly + vylepšení (1.4.0 → 1.5.2)

**Finální APK:** `VEGA-46851df.apk` (1.5.2)
- **SHA-256:** `51d3595e8cff0f0df1c50cc687804ecac9dc8e22089c6d894d1db1e456e22b47`
- **Certifikát:** `4557a2ce…cdb4`, stejný jako u všech předchozích verzí, takže jde nainstalovat přes ně.
- **Testy:** jádro 75 a aplikace 25, vše zelené.
- **Kde se testovalo:** jen hostitel (JVM + Robolectric, Chromium). Na telefonu ani v emulátoru se v noci nic neověřovalo.

| Kolo | Oblast | Nejvážnější nález → oprava |
|---|---|---|
| 1 | nové jádro (síť, LLM, výpočty) | **SSRF:** přes DNS / IPv6 do lokální sítě.<br>**Rozpočet:** souběžná placená volání ho mohla přečerpat. |
| 2 | autonomie (návrh rozšíření) | odmítnutí se počítalo jako chyba nástroje; souhlas neuváděl, že jde ven deník |
| 3 | most JS ↔ Kotlin, sdílení | **Klíče:** dočasná chyba Keystore mazala API klíč.<br>**Projekt:** `../..` jako „projekt“.<br>**Pád rendereru** se opakoval dokola. |
| 4 | projekty a soubory | **klíče služebních účtů Google / Firebase šly modelu**; obejití `.env` mezerou; diff shazoval paměť; windows-1250 se poškodil |
| 5 | paměť, důkazy, výpočty | **Tarif:** nad 12 měsíců počítán špatně (o 1 680 Kč místo 480 Kč).<br>**Kalkulace:** „a kdyby“ ji přepsalo.<br>**Zapomenutí** mazalo ve všech projektech. |
| 6 | router, výpisy bank, postupy | **tiše špatné součty:** změněná hlavička četla zůstatek, různé měny se sčítaly, duplicitní soubor zdvojil CELKEM |
| 7 | samoúpravy aplikace | **KRITICKÉ:**<br>• kód modelu by se podepsal a nainstaloval bez přezkoumání;<br>• podpisový klíč byl dosažitelný z běhu s kódem modelu. |
| 8 | toky laika, odolnost | **Regrese:** úklid mazal konverzace projektů.<br>**Ztráta práce:** po zabití procesu.<br>**Výpadek sítě:** končil anglickým toastem. |
| 9 | závěrečná kontrola celku | **Ztráta konverzací:** po obnově poškozeného seznamu by úklid smazal všechny staré.<br>**Hlídač samoúprav:** šel obejít přes `gh().headers`.<br>**Výpisy bez hlavičky:** první pohyb chyběl v součtu (starší chyba). |

### Opravdová vylepšení (co uživatel pozná)
- **Rychlé odpovědi bez modelu** (přesně, offline, bez kvóty):
  - DPH (s / bez, 21 / 12 %), procenta a procentní změna;
  - datum za N dní / týdnů / měsíců s dnem v týdnu, den v týdnu, věk a výročí;
  - převody plochy, kuchyňských měr, dat (GB / GiB), času a energie.
- **Výpisy:** součty po měnách, rozpoznání amerických tisíců (`1,500`), výpisy bez hlavičky, návrat uloženého postupu na předchozí verzi.
- **Spolehlivost:**
  - „Pokračovat“ i po zabití aplikace;
  - chyby zůstávají v chatu;
  - poškozená data aplikaci nezaseknou;
  - srozumitelné hlášky pro Wi-Fi s přihlášením a plné úložiště.
- **Autonomie:**
  - deník slabin;
  - naučená pravidla routeru;
  - vlastní rozšíření v sandboxu;
  - **samoúpravy aplikace** („vylepši se: …“) s hlídačem, CI bez klíčů a instalací až z přehledu změn.

### Co je potřeba od uživatele (samoúpravy)
Návod je v `AUTONOMIE.md`:
1. Token jen Contents RW + Actions Read (bez Workflows).
2. Ruleset omezující větve na `vega/auto-*`.
3. Podpisový klíč v prostředí `vega-sign`.

Bez toho samoúpravy připraví změnu, ale APK nepodepíšou.

### Rozhodnutí, která jsem udělal
- **DNS rebinding:** známá mezera s nízkým rizikem, zdokumentovaná. Plná oprava (OkHttp s vlastním resolverem) až s testem na telefonu.
- **`Ops.kt` nerozděluji:** přesun 1 600 řádků bez testu na telefonu je riziko bez přínosu pro uživatele.

---


## Ranní shrnutí noci 19./20. 11. 2026 (1.4.0 → 1.4.5)

**Kde se testovalo:** všechny testy běžely na hostiteli (JVM + Robolectric, Chromium). Nic se nezkoušelo na Pixelu 9a ani v emulátoru. Podrobnosti ke každému bodu jsou v `PLAN-NOC.md`.

| Verze | APK (SHA-256, začátek) | Hlavní obsah |
|---|---|---|
| 1.3.1 | `806d6c8b…` | pád u každé zprávy na Androidu (regexový příznak `(?U)`) |
| 1.4.0 | `0cde2a3a…` | **Nové jádro:** VEGA je bez AVIORu.<br>**Autonomie:** deník slabin, naučená pravidla routeru, rozšíření v sandboxu.<br>**Scan:** SSRF, rozpočet, úklid úložiště.<br>**UI a výkon:** kontrast, rychlejší chat. |
| 1.4.1 | `f1d2dcc3…` | **Kontrola kolo 2 a 3:**<br>• souhlas a deník u návrhu rozšíření;<br>• pád rendereru;<br>• klíč při chybě Keystore;<br>• id projektu;<br>• klíč vlastního serveru;<br>• strop sdílení. |
| 1.4.2 | `6aa5b63a…` | **Kontrola kolo 4:**<br>• klíče služebních účtů Google / Firebase a tajný obsah mimo model, testy i export;<br>• obejití `.env` mezerou;<br>• diff bez pádu paměti;<br>• windows-1250 bez poškození;<br>• lineární CSV;<br>• zámek projektu. |
| 1.4.3 | `eae025c7…` | **Čtení čísel:** `1,500` = 1 500 (podle tvaru celého sloupce).<br>**CodeAgent:** úprava se neaplikuje dvakrát. |
| 1.4.5 | `3e4e0f62…` | **Kontrola kolo 5:**<br>• tarif nad 12 měsíců;<br>• měsíční cena z ročního čísla;<br>• „a kdyby“ nepřepíše kalkulaci;<br>• kopie URL ≠ nezávislé zdroje;<br>• zapomenutí bez kopií a jen v aktuálním rozsahu;<br>• tajné údaje v paměti;<br>• ověřovač nevynechá popírající větu;<br>• Econ v haléřích. |

Všechna APK mají stejný podpisový certifikát (`4557a2ce…cdb4`), takže jdou instalovat přes sebe. Počet testů: jádro 51 → 61, aplikace 21 → 25, všechny zelené.

### Co je NEOVĚŘENO na telefonu
- vzhled karty Vylepšení a tlačítka „Navrhnout rozšíření“ (ověřeno jen v Chromiu na 360 px);
- sandbox rozšíření ve WebView telefonu (ověřeno v Chromiu);
- `Ops.home()` a výkon na Pixelu;
- ověřovač citací s novým oknem (logika ověřená čtením kódu, ne skutečným modelem).

### Co čeká na rozhodnutí uživatele
- **Vrstva C autonomie** (VEGA navrhne změnu aplikace → větev `vega/auto-*` → CI → APK): potřebuje GitHub token omezený na tyto větve.
- **DNS rebinding** (známá mezera s nízkým rizikem): plná oprava znamená přechod síťové vrstvy na OkHttp s vlastním resolverem a test na telefonu.
- **Rozdělení `Ops.kt`** (1 500 řádků) na menší soubory: bezpečné jen s možností vyzkoušet na telefonu.

---

## Novinky 1.4.0 (noc 19./20. 11. 2026)

**Prostředí:**
- Všechna měření a testy proběhly na hostiteli: Linux kontejner, JVM + Robolectric, Chromium přes Playwright.
- **Na Pixelu 9a ani v emulátoru se v této noci nic neměřilo.**
- Podrobnosti ke každému bodu jsou v `PLAN-NOC.md`.

**Vlastní jádro:** VEGA už nezávisí na `:core` (AVIOR). Vlastní balíčky jsou `cz.axiom.vega.base/net/llm/web/calc/files`.

**Záplata 1.3.1:** pád „Invalid value for match mode flags“ u každé zprávy. Příčinou byl příznak `(?U)`, který Android (ICU) nezná. Dvě hlídací kontroly to teď nepustí zpátky.

### Autonomie – VEGA se učí a rozšiřuje sama

Návrh je v `AUTONOMIE.md`. Stav:

| Vrstva | Co dělá | Pojistky |
|---|---|---|
| Deník slabin (`SelfTune`) | **Co zapisuje:** chyby, Stop, kroky nad 45 s, opravy („ne, myslel jsem…“) a přeformulování do 90 s.<br>**Komu:** připíše je oblasti, která zprávu zpracovala. | **Soukromí:** jen v telefonu, anonymizované (klíče, e-maily, URL, IBAN, čísla), bez obsahu souborů.<br>**Rozsah:** 30 dní. |
| A – naučená pravidla routeru | **Odkud:** z oprav uživatele se naučí „tyto výrazy = tato akce“.<br>**Zapnutí:** samo po 2 dokladech. | **Kontrola:** nesmí změnit žádný z 41 kontrolních případů.<br>**Dosah:** nahrazuje jen obecný rozhovor / rešerši.<br>**Vratnost:** historie, Vrátit. |
| B – rozšíření (vlastní JS nástroje) | **Vznik:** „napiš rozšíření, které…“ → VEGA napíše `tool.js` + testy → „ulož jako rozšíření“.<br>**Kde ho najdeš:** v nástrojích pod „Rozšíření“. | **Uložení:** jen otisk, který prošel testy.<br>**Kde běží:** izolovaný Worker bez sítě, s limitem času.<br>**Správa:** nové rozšíření je vypnuté, 5 verzí, Vrátit verzi. |
| C – změna samotné aplikace (větev → CI → APK) | návrh hotový | **Stav:** neimplementováno.<br>**Vyžaduje:** GitHub token omezený na větve `vega/auto-*`.<br>**Instalace:** potvrzuje vždy uživatel (vyžaduje to Android). |

Všechno je v aplikaci pod **Menu → Vylepšení**.

### Hloubkový scan a opravy

**Bezpečnost:**
- SSRF přes DNS (jméno → 127.0.0.1 / LAN) a přes IPv4 v IPv6;
- hlavičky se nepřenášejí na přesměrování jinam;
- atomická rezervace rozpočtu (souběžná placená volání nepřečerpají strop).

**Data:**
- seznam chatů se ořezával na 50, ale data vypadlých chatů zůstávala navždy;
- úklid při startu maže i staré mezivýsledky a osiřelé přílohy;
- chybějící nebo rozbitý seznam chatů = nic se nemaže.

**Správnost:**
- „dnes je hezky“ už nevrací datum;
- `\b` vedle češtiny se v telefonu chovalo jinak než v testu („najdi v mých souborech…“).

**Web:** DuckDuckGo – opakovaný pokus, stránka „anomaly“ bez chyby, test parseru na skutečném HTML.

**UI:**
- karta Vylepšení na 360 px;
- spinner NFC podle skutečného poslechu;
- kontrast primárních tlačítek 3,16 → 4,93:1 (WCAG AA).

**Výkon:** chat vykresluje posledních 80 zpráv. Při 300 zprávách trvá vykreslení 15 ms místo 50–70 ms (Chromium, hostitel).

**Testy:** jádro 50, aplikace 21 – vše zelené.

---

# VEGA 1.3 – předávací přehled

VEGA je samostatná aplikace postavená podle zadání „VYKR / Opus Kit“. Je to osobní český parťák a kapesní sada nástrojů pro Pixel 9a.

- Instaluje se vedle AVIORu: má vlastní `applicationId` (`cz.axiom.vega`), ikonu a úložiště.
- Ověřené části AVIORu přebírá z jeho jádra (`:core`).

## Jméno

Zadání nabízelo značku VYKR, ale uživatel chtěl zkusit lepší jméno. Zvolil jsem **VEGA**:

- je to hvězda jako Avior, takže obě aplikace tvoří jednu rodinu;
- čte se česky bez váhání;
- znak z kitu (V s korálovou stopou) k němu sedí beze změny.

Jméno je jen v `vega/src/main/res/values/strings.xml` (`app_name`) a v UI (`index.html`). Přejmenování zpět na VYKR jsou dvě řádky.

## Novinky 1.3 (tento blok zadání)

Revize a prostředí:

- **Poslední revize kódu:** `01a45a1`.
- **APK z CI:** běh pro `055a7cd`. Následuje běh pro `01a45a1`, který přidává opravu routeru.
- **Prostředí všech měření:** hostitel (Linux kontejner, 4 CPU), JVM + Robolectric. Model Qwen2.5-1.5B-Instruct Q4_K_M běží v llama.cpp serveru na CPU.
- **Na Pixelu 9a ani v emulátoru se neměřilo nic.**

### 1. Vlastní modely, navázání, tlačítko, programování, záměr, měření

| Schopnost | Stav před změnou | Zásah v kódu | Ověření a výsledek |
|---|---|---|---|
| Pouze vlastní modely | chybělo | `Engines.pick/single`, pojistka v `Ops.run`, počítadlo požadavků | OpsSpecTest: úloha dokončena; při výpadku uložen stav a nabídnuto pokračování; 0 komerčních požadavků |
| Navázání na projekt | chybělo | `saveState`, `continueWork`, `userChanges` | Po restartu správný projekt, diff ručních změn, pravidlo projektu neproniklo jinam |
| „Udělej mi z toho tlačítko“ | chybělo | `makeButton`, `buttonTry`, `buttonSave`, parser podle názvů sloupců | Hlavní souvislý průchod: oprava parseru → kontrola zůstatku 4/4 → tlačítko → restart offline → přeházené sloupce, hodnoty = očekávaný výsledek |
| Cyklus programování | 1 kolo, bez výběru změn | až 3 kola se skutečným výstupem testu, `keepChanges`, checkpoint | Zástupný model: projde. Skutečný model 1,5B: 3 kola, test dál selhává (nesplněno) |
| Záměr | částečně | Hypothesis, CompareFiles, oprava kdekoli ve větě | Held-out 34/34; 2 příklady přidané až po měření |
| Změna zadání za běhu | chybělo | `RevTask` (revize, mezivýsledky podle otisku, zahození opožděné odpovědi) | Test přes chat: výměna prostředního CSV, nezměněné soubory zpracovány jen jednou |

Měření „model sám × tentýž model ve VEGA“ (kritéria v `vega/src/test/resources/eval/cases.json` byla commitnutá před během):

| Případ | Model sám | Model ve VEGA | Pozn. |
|---|---|---|---|
| výpočet 48 × 37 | ✗ | ✓ (0,1 s) | Deterministické jádro |
| vysvětlení inflace | ✓ | ✓ | |
| dlouhé zadání, 34 000 znaků | ✗ (nevešlo se do kontextu) | ✓ (161 s, pokryto 3/3) | |
| rešerše | ✗ (bez webu) | ✓ (539 s, 2 stažené zdroje) | Malý model se zacyklil až do limitu tokenů |
| oprava programu | ✗ | ✗ | Po opravě routeru 3 kola, model chybu neopravil |
| data z výpisu | ✗ | ✓ (1,8 s, zůstatek 11/11) | |
| předžalobní výzva | ✗ | NEOVĚŘENO | PDF v Robolectricu „document is closed“ – omezení prostředí |
| návrat k projektu | ✗ | ✓ (po opravě routeru) | |

Další údaje k měření:

- Cena 0 Kč, komerční požadavky 0.
- Komerční asistent neměřen: bez klíče a bez schváleného rozpočtu.
- Výkon proti výchozí revizi 7128ce3 (stejné funkce, medián ze 3 běhů): výpis 10 000 řádků 36,9 → 39,5 ms (v šumu), alokace 47,5 → 47,1 MB. Router a ostatní nástroje beze změny.

### 2. Oponentura, důkazy, hloubka, pravdivý export

Výchozí revize byla `69b6c0c`. Závěrečné vstupy: `VegaEval2Test`, se stejným modelem a stejnými oprávněními.

| Schopnost | Stav před změnou | Zásah | Závěrečné kroky (výchozí → upravená) |
|---|---|---|---|
| Oponentura a oprava závěru | model počítal „zisk 8 400 Kč“ | `Econ` (bod zvratu), parametry v rozsahu projektu, CSV se zpětným čtením | 0/5 → 5/5 |
| Práce s důkazy | procenta bez základu | `Evidence` (linie podle původu, prvotní doklad, stejná kritéria) | 0/4 → 4/4 (před poslední opravou motivu 2/4) |
| Přiměřená hloubka | model se spletl u tarifu | součin s jednotkou, `Econ.tariff` (hranice, podmínky) | 0/3 → 3/3 |
| Pravdivý stav exportu | chat neexportoval, zápis nebyl atomický | atomický `Out.save`, „Export se NEPODAŘIL“ + Zkusit znovu | 0/2 → 2/2 |

Výchozí revize v kroku „15 × 320“ odpověděla věcně správně („4800 Kč“), ale předem stanovené kritérium vyžadovalo zápis „4 800 Kč“. Oprava důkazů (motiv podporuje jen doklad se shodným důvodem) vznikla až po pohledu na závěrečný výsledek. Výsledky jsou v `docs/vega/mereni/`.

### 3. Ověřovač opory ve zdroji

Podrobnosti jsou v `tools/verifier/PROTOKOL.md`. Shrnutí:

- **Model:** referenční NLI `MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli@0a71e92` (MIT).
- **Mobilní balíček:** 141 MB (int8 jen tabulka embeddingů), shoda po exportu 59/59.
- **Tokenizer v Kotlinu:** shoda 40/40.
- **Rychlost na hostiteli:** p50 8 ms na dvojici.
- **Dotrénovaný pilot** (CPU, 115 dvojic) nesplnil předem dané pravidlo nasazení, proto se nenasazuje.
- **Data:** štítky jsou jen návrhy modelu, takže měření jsou předběžná.
- **Placený běh na GPU:** neproběhl, plán je v GPU_PLAN.md.
- **Výchozí stav aplikace:** žádné váhy. Citace se zobrazí jako „nevyhodnoceno“, dokud uživatel nenainstaluje balíček.

### Deep scan a red team (konzilium 3 recenzentů)

Byli to tři nezávislí recenzenti: bezpečnost, správnost/souběh a mrtvý kód. Všechny opravy mají regresní test v `ScanRegressionTest`, nebo je ověřil skript v prohlížeči.

**Opraveno – bezpečnost:**

- Rešerše teď respektuje přepínač „Veřejný web“.
- Výsledky JS testů nejdou podvrhnout přes přepsání iterace nebo `push` polí; prázdná sada už neprojde.
- Balíček ověřovače: práh a délka okna mají povolený rozsah, zip má strop velikosti, stará relace modelu se zavře a neznámý balíček je označen „původ neověřen“.
- Parser úprav kódu měl kubické zpětné hledání regexu (400 bloků trvalo 10 s); teď je to lineární.
- Pád rendereru WebView už neshodí aplikaci.

**Opraveno – správnost a souběh:**

- Porovnání výpisů: nekonečná smyčka po zveřejnění, ztráta revize při souběhu (zámek pro úlohu napříč instancemi), dopočet revize přišlé na konci běhu, přesné součty i nad 5 000 řádků.
- UI zůstávalo na „pracuji“ po synchronní odpovědi.
- Opravy zadání unášely běžné zprávy; teď se týkají jen běžící nebo čerstvé úlohy a celé věty.
- Export unášel pokyny ke kódu.
- Kalkulace brala „za 1 kus“ nebo „12 měsíců“ jako hodnotu a vyhodnocovala jako opravu i otázky typu „cena benzínu v roce 2024“.
- Tarif s cenou 0 dával Infinity.
- Bod zvratu mohl vyjít 0.
- Obnova úlohy zdvojovala vkládací úpravy.
- Dělení odpovědi na tvrzení: citace za tečkou, data, zkratky, domény.
- Důkazy:
  - ČTK × Reuters už nejsou jedna linie;
  - kopie téhož dokladu není dvojí potvrzení;
  - krátká URL nesloučí zdroje;
  - neplatné datum se nepočítá.
- Souběh v paměti, v postupech (`used`) a u nových chatů; testy z panelu projektu jdou přes frontu.
- „1,234“ v souboru s desetinnou tečkou je 1234.
- Oddělovač CSV už neberou jako pravidlo věty typu „nepoužívej středník“ ani zmínka o desetinné čárce.

**Odstraněn mrtvý kód:**

- metody mostu `clearFiles` a `index`, nepoužívané klíče nastavení;
- `start` a `startAct` a mrtvé větve `act`/`chipAct`;
- nedosažitelná větev knihovny;
- 13 nepoužitých CSS pravidel;
- `configured`, `forgetAll`, `TempContext`, `sameAs`, `LocalIndex.remove`;
- ladicí událost `intent`, parametr `meta`;
- nečtené `procedures`/`runs`/`pinned` v `home()` (načítaly se při každém vykreslení);
- 4 kopie čtení kroků postupu sloučené do jedné;
- `cache-path` ve FileProvideru, barva `titan`.

CSP v `index.html` je teď shodné s HTTP hlavičkou. Připnutý postup jde odepnout.

**Neopraveno (vědomě):**

- přesměrování http v `:core` (sdíleno s AVIORem);
- velké přílohy v záznamech běhů a růst `taskfile/` a `resume/` bez úklidu podle stáří;
- zip bomba v `:core` `Extract` (DOCX);
- `headerHash` ve formátu parseru zůstává kvůli zpětné kompatibilitě uložených postupů.

### Známé mezery

- Nic neověřeno na Pixelu 9a.
- Oprava programu malým vlastním modelem neuspěla.
- Výzva s PDF neověřena v Robolectricu.
- Ověřovač má slabinu „plán vs. uskutečnění“ a data bez lidské kontroly.
- Red team: přesměrování http v jádře (`Http.open`, sdílené s AVIOR) není ošetřené, protože AVIOR jsem neměnil.

### Reprodukce

```
./gradlew :vegacore:test :vega:testDebugUnitTest
echo http://127.0.0.1:8091/v1 > ~/.vega-eval-url && ./gradlew :vega:testDebugUnitTest --tests '*VegaEval2Test*'
cd tools/verifier && pip install -r requirements.txt && python validate_data.py && python smoke_cpu.py
```

## Novinky 1.1 – 1.2

### Nové UI („papír a signál“)

Rozhraní je navržené od nuly a s AVIORem nemá nic společného. Opírá se o principy [Shape of AI](https://www.shapeof.ai/), postupného odhalování a viditelných stavů.

- **Barvy.** Světlé, papírové pozadí, inkoustové písmo a jediná korálová signální barva. Tmavý režim se zapne podle systému.
- **Jedna plocha a jeden plovoucí vstup s „+“.** Přidání souboru, nástroje, NFC, složky jako projektu a předžalobní výzvy. Žádné spodní záložky.
- **Návrhy podle kontextu.** Pod vstupem jsou nejvýš 4 tlačítka, která dávají smysl právě teď.
  - Po CSV: uložený nástroj, Zpracovat výpis, Najít chyby.
  - V projektu: Spustit testy, Rozdíl a návrat.
  - Po výsledku: Uložit jako nástroj, Předat dál, Tohle je divné.
- **Výsledek nejdřív, detail na „⋯“.** Kroky, mezivýsledky, export a předání jsou až v nabídce.
- **Knihovna za tlačítkem nahoře.** Konverzace, Projekty, Nástroje, Paměť a Nastavení.
- **Nastavení na jednu obrazovku.** Gemini zdarma, kredit Pro na rozbalení, ostatní poskytovatelé pod „Pokročilé“.
- **Stálý vstup.** Psaný text nesmaže příchod odpovědi ani výběr souboru.
- **Systémové Zpět** nejdřív zavře list (u dotazu znamená „ne“), teprve pak schová aplikaci.

### Gemini

- **Zdarma.** Klíč z projektu bez fakturace. Před uložením se aplikace zeptá, jestli projekt opravdu fakturaci nemá.
  - Model se volí podle nabídky klíče, protože Gemini 4 Hydrogen Google zpřístupňuje postupně.
  - Ověření klíče hned po uložení ukáže, který model bude odpovídat.
- **Kredit Google AI Pro.** Předplatné samo API klíč nedává, obsahuje kredit asi 10 USD měsíčně, který se aktivuje v AI Studiu.
  - Klíč z placeného projektu patří do vlastního slotu s vlastním stropem v Kč.
  - Bez povolení se aplikace ptá u každého úkolu a ukáže strop celého úkolu.
- **Řetězec poskytovatelů.** Nejdřív všechno zdarma, placené až potom. Přihlášení předplatným bez API klíče neexistuje (Google ho třetím aplikacím nenabízí).

### Red team (2 kola, 3 + 2 nezávislí auditoři)

Auditoři se dívali ze tří stran: bezpečnost, náklady a reálný uživatel. Opravy:
- **NFC.** Odkaz `javascript:` ze štítku se nevykreslí jako odkaz.
- **CSP.** Posílá se jako HTTP hlavička a bez `unsafe-inline`.
- **Tajné soubory** se kontrolují po normalizaci cesty, takže se nedostanou do modelu, testů, ZIP exportu ani rozdílu.
- **Přísné hlídání nákladů.**
  - Odhad se rezervuje před voláním; zrušené nebo přerušené volání se započítá.
  - Po zaplaceném pokusu se u záložního poskytovatele neplatí znovu.
  - Strop schváleného úkolu platí pro celý úkol.
  - Společný strop placených poskytovatelů je skutečně společný.
- **Výsledky testů** v izolovaném Workeru nejde podvrhnout: nonce i zamčené `expect` a `test`.
- **Přílohy** patří jen ke své zprávě.
- **Žádné ztracené události.** Sdílení při studeném startu ani otevřený dotaz po obnovení stránky se neztratí.
- **Nedoběhlý test** se nezapíše jako selhání a nespustí placené „opravné“ kolo.

**Regresní testy:** GeminiTest, GeminiStrictTest, VegaRedTeamTest, VegaCostTest.

**Hranice:** testy kódu projektu jsou odolné proti podvrhu, ale ne neprolomitelné. Vlastní testy projektu mohou být napsané tak, aby lhaly.

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
