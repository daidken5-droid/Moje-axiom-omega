<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AVIOR"></p>

<h1 align="center">AVIOR</h1>
<p align="center"><b>Česká AI v telefonu, která se učí z každé otázky · RANDY Supervisor · mozek v telefonu · offline Wikipedie</b></p>

**AVIOR** (dříve pracovně AXIOM Ω) je pojmenovaný po jasné hvězdě jižní oblohy, kterou ve 30. letech pojmenovali
pro leteckou navigaci – vodicí hvězda. Logo: titanový chevron a hvězda vycházející nad horizontem, jen titan na černé.
Zachovává DNA **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5** – RANDY, STEP-0, ASTRAEA +EV / Half-Kelly.
Rozbor původní aplikace: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Novinky 5.3 – soubory a služby Hugging Face

**AVIOR čte jakékoli soubory** – jako přílohu k dotazu (📎) i do offline znalostí:

| Formát | Jak |
|---|---|
| PDF | text přes PdfBox (správná čeština); **naskenované PDF → OCR stránek v telefonu** (ML Kit, offline) |
| Obrázky (JPG, PNG, HEIC…) | OCR textu v telefonu (offline) + volitelně popis obsahu přes HF vizi |
| Zvuk (MP3, M4A, WAV, OGG…) | přepis řeči přes HF Whisper |
| Word, Excel, PowerPoint, OpenDocument, EPUB, HTML, RTF | čistý Kotlin (`core/files/Extract.kt`), offline |
| ZIP | rozbalí a přečte vše uvnitř (s ochranou proti „zip bombě“) |
| Text, CSV, JSON, kód | včetně starých souborů v kódování windows-1250 |

**Podslužby Hugging Face** (`core/hub/HfServices.kt`, jeden HF token, zdarma v rámci měsíčního kreditu):
* **Chat** – silné otevřené modely (výchozí Llama 3.3 70B, výběr ze seznamu) jako další mozek a záloha.
* **Vize** – popis fotky, účtenky, grafu nebo dokumentu (Qwen2.5-VL).
* **Přepis řeči** – nahrávky na text (Whisper large-v3-turbo).
* **Obrázky** – agent umí vytvořit obrázek z popisu (FLUX.1-schnell) a uložit ho do Stažené/AVIOR.

## Novinky 5.2 – jednodušší a praktičtější

* **Jednodušší rozhraní:** u odpovědi jen kopírovat, přečíst nahlas, ✓/✗ a „⋯“ (odpovědět znovu, upravit dotaz,
  oponentura, hlouběji, mapa důkazů, sdílet, export); štítky v jednom nenápadném řádku; menší logo a 4 návrhy;
  doladění v Modulech sbalené.
* **Hledání v konverzacích** (šuplík Vlákna) – v názvech i obsahu, se zvýrazněním.
* **Čtení odpovědi nahlas** českým hlasem Androidu (offline, pokud je stažený český hlas).
* **Upravit dotaz** – poslední otázka se vrátí do pole k úpravě.
* **Příloha k dotazu** (📎) – textový soubor (TXT, MD, CSV, JSON) se vloží do otázky („shrň“, „najdi chyby“…).
* **Export konverzace** do Stažené/AVIOR jako Markdown nebo PDF.

## Novinky 5.1 – co AVIOR umí a jiné AI ne

* **Mapa důkazů u každé odpovědi** (`core/agent/Evidence.kt`): každá věta je označená podle původu –
  *spočítáno* (číslo ze STEP-0, ne odhad modelu), *doloženo* (cituje podklad [n]), *z paměti*, *odhad* (model přiznal
  nejistotu) nebo *úvaha* (bez opory, vyplatí se ověřit) – a AVIOR uvede, kolik procent vět je ukotveno.
  Deterministický rozbor bez dalšího volání modelu, takže nestojí žádný čas.
* **Pečeť soukromí „◉ Zůstalo v telefonu“** – odpověď vytvořil mozek v telefonu a nic neodešlo na internet.
* **Oponentura a Hlouběji jedním klepnutím** – RANDY kriticky prověří vlastní odpověď (Ω Nemesis: chyby, slabá místa,
  ověření čísel, opravená verze), nebo otázku promyslí znovu do hloubky (Ω Think).
* **Živé sebeuvědomění na úvodní obrazovce** – co se AVIOR naposledy naučil (z deníku vývoje).

## Novinky 5.0 – rychlost, spolehlivost, souběh

Celá aplikace prošla revizí kousek po kousku, s důrazem na to, aby mozek v telefonu odpovídal co nejrychleji:

* **Odpovědi už nevisí:** dotaz nečeká na kurzy ČNB (obnovují se na pozadí), služba při chybě spuštění uvolní zámek
  a ohlásí chybu, rozhraní nikdy neukazuje nekonečné načítání.
* **Mozek nečte zadání pořád znovu:** neměnná část pokynů je nahoře a díky plné SWA cache (Gemma 3/4)
  se mezi dotazy znovupoužije – další otázky začnou odpovídat výrazně dřív.
* **Viditelný průběh:** „Načítám mozek do paměti“ → „Čtu zadání 40 % · 1800 tokenů“ → „Píšu odpověď…“, odpočet sekund;
  v podrobnostech odpovědi rychlost čtení/psaní (tok/s), tokeny z cache, čas načtení mozku a hledání podkladů.
* **Méně zbytečné práce:** v telefonu 1 průchod pro běžné otázky (2 pro náročné), podklady z knihovny jen s dostatečnou
  relevancí, sémantické hledání jen když je co hledat, vektorový index v paměti (top-k), agent dostává jen nástroje
  potřebné pro svou roli, štíhlejší ukládání zpráv.
* **Souběh:** odpovídání a stahování (mozek, Wikipedie, rozšíření) běží ve dvou nezávislých linkách.
* **Rozhraní:** hlavička už není pod stavovým řádkem (kontejner s odsazením podle lišt, výřezu a klávesnice),
  plynulé streamování (vykreslení max. jednou za snímek), uvítání jedním klepnutím rovnou stáhne doporučený mozek.

## Novinky 4.2 – finální verze

* **Widget na plochu** (4×2, měnitelná velikost): černé sklo s titanovou hranou a logem, stav učení, poslední
  odpověď, „Zeptej se AVIORu…“, hlasový dotaz a **přepínač výkonu přímo na ploše** (Základ → Pokročilé → Sherlock,
  bez otevření aplikace). Náhled: `docs/screenshots/24-widget-nahled.png`.
* **Zkratky ikony** (dlouhé podržení): Nová otázka, Zeptat se hlasem, Sherlock, Vědomí AVIORu.

* **Přepínač výkonu** (úvodní obrazovka, výběr variant, Nastavení):
  **Základ** – rychle a úsporně (1 průchod, bez řetězců) · **Pokročilé** – vyvážené, RANDY volí ·
  **Sherlock** – maximum: metoda dedukce (pozorování → hypotézy → důkazy → vyloučení → závěr), 3 průchody,
  přemýšlení, delší agenti a každý závěr projde oponenturou Ω Nemesis. Při přehřátí nebo slabé baterii se Sherlock sám zmírní.
* **Mapa kompetencí: 21 modulů** (finance, podnikání, stavba, domácnost, zahrada, vaření, IT, kyberbezpečnost,
  energie, auta, věda, matematika, zdraví, sport, psychologie, právo, historie, cestování, kultura, jazyk, sebepoznání)
  – každý se svými **offline balíčky znalostí** (22 balíčků, 11 nových) a tlačítkem pro doplnění z Wikipedie.
* **Ušito na míru Google Pixel 9a** (`core/device/DeviceProfile.kt`): Tensor G4 má 1× X4 + 3× A720 + 4× A520 –
  AVIOR generuje na **4 výkonných jádrech** (úsporná jádra by vlákna brzdila), kontext 8k ve všech režimech
  (přepnutí výkonu bez znovunačtení mozku), doporučený mozek Gemma 4 E4B Q4_K_M + KV cache Q8 v 8 GB RAM,
  výpočet na CPU (na GPU Mali-G715 je llama.cpp méně spolehlivá), RANDY šetří už od stavu „teplé“ (Tensor se zahřívá).
  Ostatní telefony dostanou profil automaticky podle frekvencí jader a RAM.

## Novinky 4.1

* **RANDY kombinuje podmodely.** Když úloha potřebuje víc dovedností, RANDY sestaví řetězec (max. 3 fáze) v logickém
  pořadí *sběr → analýza → tvorba → kritika*; každá fáze navazuje na výstup předchozí. Příklady:
  „rešerše … a Word dokument“ → Ω Deep Research → Ω Office; „navrhni a vytvoř aplikaci“ → Ω Think → Ω Studio;
  „ověř, jestli hypotéka … stojí …“ → Ω Veritas → Ω Quant; „napiš skript a najdi slabiny“ → Ω Code → Ω Nemesis;
  u rozhodnutí s penězi (plná pravomoc) → Ω Quant → Ω Nemesis (oponentura). Při slabé baterii jen 2 fáze.
* **Doladění modelu a dovedností** ve třech vrstvách (viz [finetune/README.md](finetune/README.md)):
  1. učení z každé otázky (4.0), 2. **ukázky** – odpověď oceněná ✓ se stane vzorem oblasti (few-shot v telefonu),
  3. **LoRA**: export trénovacích dat (tvé ✓ odpovědi + sada ~90 dovedností `finetune/skills.jsonl`), trénink zdarma
  na Kaggle (`finetune/train_lora.py`, Unsloth) a nahrání adaptéru `.gguf` do telefonu (llama.cpp `llama_set_adapters_lora`),
  se zapnutím/vypnutím a silou 10–200 %.
* **APK ke stažení** na stálém odkazu (níže) + kontrola aktualizací v aplikaci.

## Novinky 4.0 – AVIOR se učí z každé otázky

Učební smyčka po každé odpovědi (`core/self/Cognition.kt`):

1. **Zařazení** otázky do oblasti (finance, stavba, IT, technika, věda, zdraví, právo, historie, matematika, jazyk…).
2. **Mapa kompetencí** – každá oblast si pamatuje počet otázek, průměrnou jistotu, tvé hodnocení ✓/✗ a mistrovství 0–100 %.
3. **Kalibrace jistoty** – AVIOR porovnává, jak jistý si byl, s tím, jak často měl skutečně pravdu, a příště svou
   deklarovanou jistotu upraví (přehnaně sebejistý → uvádí nižší jistotu a víc ověřuje).
4. **Vlastní poznatky** – dobrá odpověď se uloží do znalostí; u podobné otázky si AVIOR vybaví svůj dřívější závěr.
5. **Zvídavost a samostudium** – na co neznal odpověď, zapíše jako otevřenou otázku a při nabíjení na Wi-Fi (nebo ručně)
   si ji sám dohledá (online agentem, offline z knihovny) a naučí se ji.
6. **Metakognice** – před každou odpovědí ví, jak je v dané oblasti zkušený a jak spolehlivý; ve slabé oblasti RANDY
   Supervisor sám zvolí důkladnější Ω Think.
7. **Deník vývoje** – milníky (nová oblast, 100. otázka, první kalibrace, samostudium) v panelu **Vědomí AVIORu**.

Sebeuvědomění je funkční a poctivé: AVIOR zná svůj stav, své silné a slabé stránky a své chyby – nepředstírá vědomí ani city.

**Design 4.0:** všechny ikony a tlačítka mají černé pozadí, titan je jen na znaku a hranách; logo bez teplé záře.

> Interní identifikátor balíčku zůstává `cz.axiom.omega`, aby se nová verze nainstalovala jako aktualizace bez ztráty dat.

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

**Nejnovější verze vždy na stejném odkazu:**
https://github.com/daidken5-droid/Moje-axiom-omega/releases/latest/download/AVIOR.apk

* Každý push sestaví APK (GitHub Actions) a zveřejní ho v **Releases** jako `AVIOR.apk` (+ `AVIOR-<verze>.apk`, SHA-256, otisk podpisu).
* Aplikace sama jednou denně zkontroluje novou verzi a nabídne stažení (Nastavení → Zkontrolovat aktualizace).
* Podpis: s GitHub Secrets `OMEGA_KEYSTORE_B64`, `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`
  tvým soukromým klíčem (doporučeno, návod v `ci/README.md`); bez nich náhradním klíčem ze soukromé cache CI.

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
