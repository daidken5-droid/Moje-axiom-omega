<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AXIOM Ω"></p>

<h1 align="center">AXIOM Ω</h1>
<p align="center"><b>Samostatná česká AI pro Android · RANDY · STEP-0 · sebemodel · mozek v telefonu · offline Wikipedie</b></p>

Nástupce **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5**. Zachovává jejich DNA – protokol hloubky
**RANDY L0–L6**, deterministický **STEP-0**, **ASTRAEA +EV / Half-Kelly**, černo-titanový vzhled –
a přidává výkonný lokální mozek, funkční sebemodel, dlouhodobou paměť, celou offline Wikipedii a aktivní agenty.
Rozbor původní aplikace a seznam opravených chyb: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Novinky 3.5 – RANDY Supervisor

RANDY už není jen protokol hloubky L0–L6, ale **nadřazený řídicí modul** celého systému (`core/randy/Supervisor.kt`):

| Pravomoc | Co RANDY dělá |
|---|---|
| **Plánuje** | před každým dotazem sám aktivuje podfunkce: Ω variantu, režim (odpověď/agent), roli agenta, hloubku, průchody, přemýšlení, limit kroků a časový rozpočet |
| **Řídí zdroje** | slabá baterie, přehřátý telefon nebo málo RAM → úsporný režim; při nabíjení plný výkon; offline → místo webové rešerše offline knihovna |
| **Dohlíží** | hlídá agenta: opakované volání nástroje nahradí předchozím výsledkem, nástroj, který 2× selže, zablokuje, po vyčerpání času nařídí závěr |
| **Eskaluje** | nejistá odpověď (jistota < 45 %) → sám spustí Ω Think / Ω Nemesis a ponechá lepší výsledek |
| **Učí se** | měří čas, jistotu a tvé hodnocení ✓/✗ každé varianty; špatně hodnocené nebo pomalé varianty příště omezí |

Pravomoci volíš v Nastavení: **Poradní** (jen radí), **Řídící**, **Plná** (výchozí). Výslovnou volbu varianty, role
nebo hloubky RANDY nikdy nepřepíše – s plnou pravomocí jí jen ubere zdroje, když hrozí vybitá baterie nebo přehřátí.
Při psaní dotazu vidíš živý náhled, co RANDY aktivuje; u odpovědi v Podrobnostech jeho rozhodnutí a zásahy.

## Novinky 3.4

* **Ω varianty – podmodely** inspirované režimy předních AI: **Ω Flash** (okamžitě), **Ω Think** (hluboké přemýšlení),
  **Ω Deep Research** (report s citacemi), **Ω Code**, **Ω Studio** (vyrobí aplikaci), **Ω Office** (Word/PDF),
  **Ω Veritas** (ověřování tvrzení), **Ω Quant**, **Ω Nemesis** (red-team), **Ω Muse** (kreativa), **Ω Mentor**.
  Stejný lokální mozek, jiné nastavení myšlení (režim, role, hloubka RANDY, průchody, teplota, styl).
* **14 specializovaných agentů:** nově Programátor, Tvůrce aplikací, Ověřovatel faktů, Finanční analytik,
  Stavbyvedoucí, Datový analytik, Kancelář, Překladatel. Role se volí i automaticky podle zadání.
* **AXIOM vyrábí soubory:** TXT, MD, CSV, JSON, HTML, SVG, kód (Python, Kotlin, JS…), kalendář .ics, vizitka .vcf,
  **PDF**, **Word .docx**, **ZIP** – vše do složky *Stažené/AXIOM*, s tlačítky Otevřít / Sdílet.
* **AXIOM vyrábí Android aplikace (APK) přímo v telefonu** – bez počítače a SDK: AI napíše HTML/CSS/JS aplikaci,
  APK kovárna (`core/forge/ApkForge.kt`) ji vloží do předpřipravené šablony (modul `shell/`, 10 kB), přepíše balíček
  a název v binárním manifestu a podepíše APK (v2+v3, apksig) klíčem z Android Keystore. Stejný název = aktualizace.
  Ověřeno nástroji Android SDK (aapt2, zipalign, apksigner).
* **Titanový design:** kartáčovaný kov, kovové hrany karet, titanová tlačítka s odleskem, sklo v hlavičce a liště.

## Novinky 3.3

* **Celá česká Wikipedie offline jedním klepnutím** – 534 044 článků, předpřipravená databáze
  (sestavuje ji GitHub Actions, vydání `library-wiki-cs-20231101`): **1,47 GB stažení → 3,27 GB v telefonu**,
  ověřeno SHA-256, bez pomalého indexování v telefonu. Vyhledání v řádu milisekund, nejdřív podle názvu článku.
* **Nové balíčky znalostí:** *Vládní a tajné projekty* (Manhattan, MKUltra, COINTELPRO, Northwoods, PRISM,
  Area 51, Stargate, StB Akce Kámen/Asanace…) a *Konspirační teorie* (tvrzení vs. důkazy: Měsíc, chemtrails,
  11. září, JFK, vakcíny, 5G, QAnon, HAARP… + checklist, jak tvrzení ověřit).
* **Evoluce UI:** pozdrav podle denní doby, karty návrhů, sbalitelné „Přemýšlel X s“, citace jako čipy,
  kopírování bloků kódu, „Odpovědět znovu“, tlačítko dolů, diktování hlasem, haptika, plynulé animace.
* **8 GB telefony:** správné rozpoznání RAM (Android hlásí ~7,5 GB), menší kontext při nedostatku volné RAM,
  sémantický modul se při nedostatku paměti vypne a uvolní; doporučený mozek Gemma 4 E4B.
* **Opravy:** fulltext (FTS4 předpony), řazení výsledků z knihovny, opakované načítání mozku při úpravě kontextu,
  spouštění buildu knihovny na GitHubu.

## Co umí

* **Výkonný mozek v telefonu je primární.** AXIOM myslí lokálně (llama.cpp, flash attention, KV cache Q8,
  kontext až 16k). Cloudy (AXIOM Node, Kimi K3, Claude) jsou jen volitelné doplňky – AXIOM je plnohodnotný bez nich.
* **Ω Auto – AI si sama vybere moduly:** odpověď, nebo aktivního agenta, hloubku RANDY a nástroje.
  Výslovná volba uživatele (`/agent`, `/chat`, `/konzilium`, `L4:`, ruční výběr mozku) má vždy přednost.
* **Aktivní agenti se specializacemi:** Rešeršér, Analytik, Plánovač, Pisatel, Bezpečnostní auditor, Učitel.
  Nástroje: offline knihovna, web, čtení stránek, Wikipedie, Hugging Face, přesné výpočty a STEP-0,
  paměť, poznámky – a bezpečnostní sada. Běží na pozadí, kroky jsou vidět.
* **Bezpečnostní sada pro etické testování** (pasivní a obranná): audit HTTP hlaviček, cookies a TLS,
  DNS + SPF/DMARC, subdomény z Certificate Transparency, RDAP/WHOIS, zranitelnosti z OSV.dev (CVE/GHSA,
  balíček+verze), hledání uniklých klíčů, JWT dekodér, hashe, kódování, síla hesla + únik přes HIBP
  (k-anonymita). Jen pro systémy, které vlastníš nebo máš písemné povolení.

## Mozky (všechny běží v telefonu)

| Mozek | RAM | Poznámka |
|---|---|---|
| **OMEGA Max · Qwen3.6-35B-A3B** | 16+ GB | MoE 35B (3B aktivních) – nejsilnější |
| **OMEGA Ultra · Gemma 4 26B-A4B** | 12+ GB | MoE 26B (4B aktivních) |
| **OMEGA Core · Gemma 4 E4B** | 8+ GB | nejnovější Google model pro zařízení (duben 2026) |
| **OMEGA Lite · Gemma 4 E2B** | 6+ GB | rychlá Gemma 4 |
| OMEGA Mini · Qwen3.5 2B, AXIOM Classic | 4 GB | starší telefony / kontinuita s 2.1 |
| **Jakýkoli GGUF** | — | Moduly → Hugging Face (hledání, odkaz, soubor) |

Aplikace doporučí nejsilnější mozek, který se vejde do RAM telefonu. Volitelně: AXIOM Node (zdarma server,
[server/](server)), vlastní server v LAN, Kimi K3, Claude.

## Offline knihovna (několik GB)

Celá **česká Wikipedie** (534 tis. článků, 1,47 GB stažení → 3,27 GB), **slovenská** (242 tis.) a **Simple English**
(242 tis.) ke stažení do telefonu – samostatná databáze s fulltextem (prefixové hledání zvládá české tvary slov),
navazování po přerušení, kontrola místa. Odpovědi i agenti z ní čerpají bez internetu.

Proč není přímo v APK: APK (ZIP) má limit 4 GB, Android by soubor při instalaci rozbaloval podruhé (2× místo)
a každá aktualizace aplikace by znamenala stahovat gigabajty znovu. Předpřipravené knihovny staví
`.github/workflows/build-library.yml` (`tools/build_library.py`) – nový build spustí změna `library-requests/build.txt`.

## Rozšíření z Hugging Face

* **OMEGA Sense** (Qwen3-Embedding-0.6B) – sémantická paměť: offline databáze se prohledává podle
  významu, ne jen podle slov (hybridní hledání).
* **Vyhledávání modelů na HF** přímo v aplikaci, výpis kvantizací s velikostí a kontrolou RAM, ověřené stažení (SHA-256 z LFS).
* **Datasety z HF** (datasets-server) a **články z Wikipedie** k tématu → offline databáze.

## Podpis a aktualizace

Release APK se podepisuje vlastním klíčem z GitHub Secrets (`OMEGA_KEYSTORE_B64`, `OMEGA_KEYSTORE_PASSWORD`,
`OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`) – díky tomu jde každá nová verze nainstalovat přes starou.
Klíč se do repozitáře nikdy neukládá.

## Stažení APK

* **Actions → Build AXIOM Ω APK → artefakt `AXIOM-Omega-APK`** (každý push).
* **Releases** – při tagu `v*` (např. `git tag v3.0.0 && git push --tags`) se APK přiloží k vydání.
* Stabilní podpis (aktualizace bez odinstalace): přidej GitHub Secrets `OMEGA_KEYSTORE_B64`,
  `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`. Bez nich se release podepíše debug klíčem.

## Vývoj

```
./gradlew :core:test                                   # 76 unit testů (JVM, bez Androidu)
./gradlew :app:assembleDebug                           # plné APK včetně llama.cpp (NDK 27.2, CMake 3.22.1)
./gradlew :app:assembleDebug -Pomega.native=false      # rychlé APK bez nativní části
tools/ui-preview/build.sh && node tools/ui-preview/shoot.js /tmp   # náhled UI s mock mostem
```

Struktura: `core/` (čistý Kotlin – RANDY, STEP-0, sebemodel, enginy Kimi/Claude, stahování, balíčky),
`app/` (Android – WebView UI, most, služba, SQLite, Keystore, JNI `omega_jni.cpp` nad llama.cpp `b12032`),
`brains/` a `packs/` (katalogy), `legacy/` (původní Titanium generátor a logo).
