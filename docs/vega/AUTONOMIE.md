# VEGA – návrh samovylepšujícího se systému

**Cíl:** VEGA sama pozná, kde selhává. Navrhne zlepšení a napíše si kód. Ověří ho a připraví aktualizaci. Nasazení i návrat zpět jsou bezpečné.

**Princip:** smyčka „pozoruj → navrhni → ověř → nasaď → sleduj“. Běží ve **třech vrstvách** podle rizika.

**Pevná hranice:** VEGA nikdy nenasadí neověřenou změnu sama sobě. Android navíc instalaci nového APK bez potvrzení uživatele nedovolí.

## 1. Pozorování (v telefonu, bez sítě)

VEGA si do lokálního deníku `selftune/journal` zapisuje signály slabin. Nikam ho neposílá.

- **Selhání:**
  - chyby nástrojů a výjimky, včetně pádu na regexu typu `(?U)`;
  - stav NEVYHODNOCENO u ověřovače citací;
  - výpadky modelu.
- **Opravy od uživatele:**
  - „ne, myslel jsem…“;
  - oprava sloupce nebo parametru;
  - přeformulovaný dotaz do 60 s po odpovědi;
  - Stop během odpovědi.
- **Špatný záměr:** zpráva přišla do rozhovoru, ale následující věta uživatele ukazuje, že chtěl nástroj (nebo naopak).
- **Pomalé kroky:** p95 doby nástroje nad limitem.

Každý signál obsahuje kontext (bez obsahu souborů a klíčů), čas a verzi.

## 2. Vrstva A – data a pravidla (VEGA sama, okamžitě, vratně)

| Co | Příklad | Ověření před aktivací |
|---|---|---|
| příklady pro router záměrů | „sleva se počítá špatně, oprav to“ → oprava kódu | celá sada RouterHeldOut + regresní testy v telefonu musí projít |
| úprava uloženého postupu | nový sloupec ve výpisu | postup se pustí na posledních 3 souborech, na kterých byl ověřen |
| práh a okno ověřovače | práh 0,91 → 0,93 | jen v povoleném rozsahu; validační sada v balíčku |
| texty a pokyny pro model | jak odpovídat u tarifu | A/B na uložených anonymních dotazech (jen když to uživatel povolí) |

Pravidla vrstvy A:

- Každá změna je **verze s historií**: Paměť / Vylepšení → „vrátit“.
- Aktivuje se, jen když neporuší žádný z uložených testů.

## 3. Vrstva B – nový kód nástroje (VEGA píše, telefon testuje, uživatel potvrdí)

VEGA už umí upravovat projekty (CodeAgent) a spouštět testy v izolovaném Workeru. Stejným mechanismem si napíše **vlastní nástroj v JavaScriptu**:

1. Z deníku vybere opakovanou slabinu. Příklad: „výpis z banky X má datum v jiném formátu“.
2. Ve vlastním projektu `vega-rozsireni` napíše nástroj a k němu testy z reálných (anonymizovaných) případů.
3. Testy proběhnou v sandboxu (bez sítě, limit času, nepodvrhnutelný výsledek).
4. Projde-li vše, uloží nástroj jako **rozšíření** s verzí, oprávněními (žádná síť, žádné soubory mimo vstup) a odkazem na testy.
5. Uživatel v kartě „Nové vylepšení“ vidí, co a proč, a výsledky testů. Jedním klepnutím ho zapne, nebo ho VEGA zapne sama, pokud to uživatel povolí pro „bezpečná rozšíření bez sítě“.

Rozšíření běží jen v sandboxu Workeru: bez sítě a bez přístupu k aplikaci, jen vstup → výstup. Pokazit se dá jen to, co nástroj sám vrátí, a zapnutí jde vrátit.

## 4. Vrstva C – změna samotné aplikace (repozitář + CI + aktualizace)

| Krok | Kdo | Co se děje |
|---|---|---|
| 1. Návrh | VEGA (s modelem) | Opakovaný problém, který nejde řešit ve vrstvě A ani B, popíše jako **zadání změny**: chování, test, kritérium. |
| 2. Kód | vývojová relace (Claude Code v repu) nebo VEGA | Zadání se zpracuje ve větvi `vega/auto-*`. Žádná větev se nemerguje bez zelených testů. |
| 3. Brána | CI | Testy jádra i aplikace, kontrola regexů pro Android, měření výkonu proti předchozí revizi, red team kontrola diffu. |
| 4. Aktualizace | CI | Podepsané APK s vyšším `versionCode`, SHA-256, poznámky ke změně. |
| 5. Nasazení | uživatel | VEGA nabídne aktualizaci: co se změní a jaké testy prošly. Instalaci potvrdí uživatel (vyžaduje to Android). |
| 6. Sledování | VEGA | Po instalaci porovná deník: ubylo signálů z bodu 1? Pokud se něco zhoršilo, nabídne návrat na předchozí APK (uložené otisky). |

**Bezpečnost vrstvy C:**

- **GitHub token:**
  - VEGA ho má jen pro vlastní větve `vega/auto-*`, nikdy pro hlavní větev;
  - je uložený v trezoru (Android Keystore);
  - jde odvolat.
- **Obsah zadání:** do zadání změny jdou jen anonymizované signály, nikdy obsah uživatelových souborů ani klíče.
- **Každá změna má:**
  - test, který před ní selhal a po ní prochází;
  - jasný popis;
  - otisk APK.

## 5. Co už existuje a co chybí

| Část | Stav |
|---|---|
| sandbox pro JS testy (nepodvrhnutelný) | ✓ hotovo |
| CodeAgent: úpravy, testy, opravná kola, výběr změn | ✓ hotovo |
| postupy s verzemi a historií | ✓ hotovo |
| CI: testy, podepsané APK, otisky | ✓ hotovo |
| deník signálů slabin (`SelfTune.observe`) | ✓ hotovo (1.4.0) |
| vrstva A: návrh a ověření příkladů routeru, karta „Vylepšení“ s historií a návratem | ✓ hotovo (1.4.0) |
| vrstva B: rozšíření jako JS nástroje v sandboxu + registr | ✓ hotovo (1.4.0) |
| vrstva C: zadání změny → větev → CI → nabídka aktualizace + návrat | ✓ hotovo (1.5.0) – čeká jen na token uživatele |


## Vrstva C v 1.5.0 – jak to funguje a jak to zapnout

**Spuštění:**
- větou v chatu: „vylepši se: …“ / „uprav aplikaci, aby …“;
- tlačítkem v Menu → Vylepšení → Úpravy aplikace;
- volitelně samo jednou denně z opakované slabiny (VÝCHOZÍ VYPNUTO).

**Postup:**
1. VEGA načte zdroj z GitHubu.
2. Model navrhne úpravu v izolované kopii.
3. **Pravidla v aplikaci:**
   - jen povolené cesty (`vega/src/main/java`, `vega/src/main/assets/ui`, `vegacore/src/main/kotlin`, testy);
   - chráněné soubory (trezor, most, síť, samoúpravy) nesmí měnit;
   - hlídač citlivých API v přidaných řádcích (klíče, síť, procesy, most, sandbox, samoúpravy).
4. VEGA udělá commit na `vega/auto-…`. Bez pull requestu, takže se nespustí workflow AVIORu.
5. **CI `vega-auto.yml` (push):**
   - job 1: hlídač (cesty bez přejmenování, jen běžné soubory, citlivá API), testy a sestavení BEZ secrets a klíče;
   - job 2: podpis JEN klíčem z prostředí `vega-sign`, bez kódu z větve.
6. VEGA stáhne APK proudem na disk a ověří otisk ze SHA256SUMS (chrání jen proti poškozenému přenosu).
7. **Instalace až po zobrazení změn (diff) v aplikaci:**
   - stejný balíček, stejný certifikát, vyšší verze;
   - potvrzení dá uživatel v systémovém dialogu.

**Návrat:**
- zahodit úpravu (smaže větev);
- nebo nainstalovat předchozí APK.

### Jednorázové nastavení (bez něj samoúpravy APK nepodepíšou)

1. **Token (fine-grained, jen repozitář Moje-axiom-omega):**

   | Oprávnění | Úroveň |
   |---|---|
   | Contents | Read and write |
   | Actions | Read |
   | **Workflows** | **Nepovolovat** |

2. **Ruleset** (Settings → Rules → Rulesets):
   - Cíl: všechny větve kromě `vega/auto-*`.
   - Pravidlo: zakázat vytváření a aktualizaci větví (výjimka jen vlastník).
   - **Proč:** token s Contents RW jinak technicky smí pushnout i jinam, kde běží workflow se secrets. Aplikace sama píše jen do `vega/auto-*`; ruleset to vynutí i na GitHubu.
3. **Podpisový klíč v prostředí `vega-sign`** (Settings → Environments):
   - **Secrets:** `VEGA_SIGN_KEYSTORE_B64`, `VEGA_SIGN_PASSWORD`, `VEGA_SIGN_ALIAS`.
   - **Deployment branches:** jen `vega/auto-*`.
   - **Současný stav:** nainstalovaná VEGA je podepsaná náhradním klíčem z cache Actions („AVIOR CI“). Ten vývojová relace z bezpečnostních důvodů nevynáší.
   - **Doporučené čisté řešení:** vlastní klíč.
     1. V počítači: `keytool -genkeypair -alias vega -keyalg RSA -keysize 2048 -validity 12000 -storetype PKCS12 -keystore vega.p12`.
     2. `base64 -w0 vega.p12` vlož do `VEGA_SIGN_KEYSTORE_B64` a heslo do `VEGA_SIGN_PASSWORD`. Alias `vega` dej do `VEGA_SIGN_ALIAS`.
     3. Stejný klíč dej i do repozitářových secrets `OMEGA_KEYSTORE_B64`, `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS` a `OMEGA_KEY_PASSWORD` (pak se jím podepisují i běžná sestavení).
     4. VEGA jednou přeinstaluj, protože jiný klíč nejde nainstalovat přes starý. Data VEGY se tím smažou.
   - Po přechodu na secrets doporučuji smazat záznam `avior-signing-v1` v Actions → Caches.

**Verze:** CI předává rostoucí versionCode (minuty od 1. 1. 2026). Každé sestavení, i samoúprava, jde nainstalovat přes předchozí.

**Co kontrola NEZARUČÍ:** hlídač citlivých API je heuristika a testy může model upravit. Proto instalace vyžaduje projít změny. Kód, který mění logiku, nainstaluj, jen když mu rozumíš nebo mu věříš.

**Bezpečnost dosud:** žádná samoúprava zatím neběžela (chybí token). Náhradní klíč tedy nebyl vystaven kódu z modelu.


## Po kole 15 – co musíš udělat ty (bez toho hranice samoúprav není úplná)

1. **Workflow AVIORu** (`build-apk.yml`, `build-library.yml`, `build-rag.yml`, `emulator.yml`) se dnes spustí i pushem do `vega/auto-*`.
   - Doplň do nich `branches: ["**", "!vega/auto-**"]`.
   - Hlavně `build-apk.yml` má produkční klíče `OMEGA_KEYSTORE_*`. Já jsem tyto soubory neměnil, protože patří AVIORu.
2. **Ruleset** nastav i na tagy (`v*` spouští build-apk), nejen na větve.
3. **Vlastní podpisový klíč** v prostředí `vega-sign` (viz výše) je povinný. Pak smaž v Actions → Caches cache `avior-signing-v1`, protože obsahuje dnešní náhradní klíč.
4. **Známá omezení:**
   - Každá úprava vychází z pracovní větve (`ghBase`), ne z naposledy nainstalované úpravy. Instalace úpravy B tak vrátí úpravu A, pokud A mezitím nebyla sloučena do pracovní větve.
   - Návrat ke staršímu APK Android nedovolí bez odinstalace.
