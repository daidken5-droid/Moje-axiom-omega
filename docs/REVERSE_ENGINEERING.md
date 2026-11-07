# Reverse engineering: AXIOM Hybrid 2.1 (`AXIOM_Hybrid_2_1.apk`)

Analýza původního APK, ze kterého vychází AXIOM Ω 3.0. Postup: rozbalení APK, dekompilace
`classes.dex` nástrojem jadx 1.5.1, dekódování manifestu, analýza nativní knihovny
(`strings`, `nm -D`) a webového UI (`assets/index.html`, base64 data odstraněna).

## 1. Co v APK je

| Část | Obsah |
|---|---|
| `AndroidManifest.xml` | `cz.axiom.app`, verze `2.1-beta` (code 3), minSdk 26, targetSdk 35, podpis v1+v2 |
| `classes.dex` (49 kB) | 8 tříd Java: `MainActivity`, `Jobs`, `Api`, `Auth`, `Vault`, `Knowledge`, `Offline`, `R` |
| `lib/arm64-v8a/libaxiom.so` (6,4 MB) | staticky slinkovaný llama.cpp + JNI `Java_cz_axiom_app_Offline_generate/cancel` |
| `assets/index.html` (1,5 MB) | celé UI v jednom souboru; 1,5 MB tvoří base64 logo vložené dvakrát |
| `assets/logo.png`, `res/drawable/logo.png` | stejné logo 1254×1254 px ještě dvakrát (celkem ~3,2 MB obrázků) |

### Architektura
* **WebView + JS most** `window.Axiom` (`@JavascriptInterface`) – UI v HTML, logika v Javě.
* **Foreground služba `Jobs`** (`specialUse`) – spouští odpověď nebo stahování modelu.
* **Offline režim:** JNI `Offline.generate(cesta, prompt)` → llama.cpp s modelem
  **Qwen3-1.7B-Q8_0.gguf** (1 834 426 016 B, SHA-256 `061b54da…cb1a`).
* **Online režim:** OpenAI Responses API (`/v1/responses`, výchozí model `gpt-6-astra`) nebo
  Google Gemini `generateContent`; OAuth „Continue with ChatGPT“ proti `auth.openai.com`.
* **Agenti:** `answer` (1 krok), `writer` (2 kroky), `audit` (3 kroky: návrh → kontrola → syntéza).
* **Znalosti:** SQLite FTS4 (`docs` + `chunks`, úseky 1 100 znaků, krok 900), 3 vestavěné metodické dokumenty.
* **Trezor:** API klíče AES-GCM přes Android Keystore (to bylo udělané dobře – převzato).

RANDY (úrovně L0–L6), STEP-0 kalkulačka, ASTRAEA +EV/Half-Kelly, převody jednotek, stavební
kalkulace a import z Japonska v APK **nejsou** – žijí v generátoru `legacy/build_axiom_titanium.py`
(AXIOM Titanium 5.5). AXIOM Ω spojuje obě větve.

## 2. Nalezené chyby a slabiny → jak je řeší AXIOM Ω

| # | Chyba v AXIOM 2.1 / Titanium | Dopad | AXIOM Ω |
|---|---|---|---|
| 1 | Stahování mozku bez navazování (každý pokus otevře `model.part` znovu od nuly, bez HTTP Range), read-timeout 60 s, žádné opakování | Na mobilních datech/při zhasnutí displeje stahování 1,83 GB opakovaně padalo od nuly – „nefunkční odkaz na mozek“ | `Downloader`: HTTP Range navazování, 8 pokusů s exponenciálním čekáním, ruční redirecty na CDN, zrcadlo, SHA-256, atomické přejmenování; wake lock + Wi-Fi lock; FGS typu `dataSync` |
| 2 | Jediná natvrdo zadaná URL + velikost + SHA v kódu; „připraveno“ = přesná velikost souboru | Žádná alternativa, žádná aktualizace bez nové verze | Katalog mozků (`brains/catalog.json`, vestavěný + z GitHubu), 4 mozky, doporučení podle RAM a volného místa, import vlastního GGUF |
| 3 | Kontext 2 048 tokenů, vstup 1 400, výstup 512 napevno v nativní knihovně | Useknuté odpovědi, ztráta podkladů | Kontext 2k–16k volitelný, výstup podle úrovně RANDY, automatické zkracování historie v C++ |
| 4 | Celá historie jako JSON v `SharedPreferences` (limit 6 MB), `commit()` při **každém stisku klávesy** v poznámkách | Zamrzání UI, při překročení limitu se historie přestala ukládat | SQLite s WAL: tabulky `threads`, `messages`, `memory`, `docs`, `chunks`, `packs` |
| 5 | Výchozí model `gpt-6-astra` (neexistující ID) | První online dotaz vždy skončil HTTP chybou | Výchozí `kimi-k3` a `claude-opus-5-5`, tlačítko „Ověřit spojení“ načte skutečný seznam modelů |
| 6 | OAuth „Continue with ChatGPT“ proti nedokumentovaným endpointům `auth.openai.com/api/accounts/*` | Nespolehlivé a mimo oficiální podmínky | Odstraněno; oficiální API klíče (Moonshot, Anthropic) šifrované v Keystore |
| 7 | Online chyba se nikdy neopakuje („Neopakuji automaticky“) | Jednorázový výpadek = ztracený dotaz | Opakování při dočasné chybě (429/5xx/síť), pokud se ještě nic nevypsalo |
| 8 | Odpověď až po dokončení (bez streamování), stav jen pollingem `state()` každou 1 s | Dlouhé čekání bez zpětné vazby | Streamování po tokenech (SSE / JNI callback), fáze, průchody, úvaha modelu |
| 9 | Duplicitní obrázky loga (3× ~1 MB, z toho 2× base64 v HTML) | APK zbytečně o ~3 MB větší, pomalý start WebView | Vektorové logo (SVG 3 kB, VectorDrawable), adaptivní + monochromní ikona |
| 10 | Logo `4482.png` s purpurovým pozadím (chroma key) v Titanium | Ikona s fialovým čtvercem | Nové titanové logo (chevron A + horizont + záblesk) na černém skle |
| 11 | Titanium: kód aplikace v Python řetězcích (`FILES["…"] = """…"""`) s ručně escapovanými `\\n` | Chyby escapování, nelze testovat ani verzovat po souborech | Skutečný Gradle projekt (`:core` čistý Kotlin + `:app` Android) |
| 12 | STEP-0: datum `2024-05-12` vyhodnoceno jako `2024−5−12 = 2007`; telefon `777-123-456` jako odečítání | Nesmyslná „fakta“ podstrčená modelu | Filtr dat, telefonů a rozsahů; parser hlásí chybu u `(2+3`, `2+2)`, dělení nulou |
| 13 | RANDY: holé podřetězce (`"roi"`, `"ev "`, `"proč"`) – např. „procenta“ → L2 DEEP, „riziko“ vždy přebije vše | Špatná hloubka odpovědi | Skórování celých slov/prefixů bez diakritiky, výběr maxima, ruční přepnutí `L0:`–`L6:` |
| 14 | Import z Japonska: DPH jen ze zboží, doprava přičtena až po DPH; kurzy natvrdo (EUR 25,30; JPY 0,155) bez data | Podhodnocená cena, tiše zastaralé kurzy | DPH ze zboží + dopravy; kurzy ČNB denně (offline poslední známé), u výsledku zdroj a datum |
| 15 | IBAN/IPv4 ve forenzním skenu bez validace (`999.1.1.1` = IP) | Falešné stopy | IBAN mod 97, IPv4 po oktetech, MD5/SHA-1/SHA-256, data, časy, časová osa |
| 16 | `addJavascriptInterface` + `loadDataWithBaseURL("https://axiom.invalid")` | Nestandardní origin | `WebViewAssetLoader` (`https://appassets.androidplatform.net`), CSP `default-src 'self'`, blokovaná navigace |
| 17 | Text uživatele a dokumentů tokenizován se speciálními tokeny | Prompt injection přes `<\|im_start\|>` v dokumentu | Uživatelské části šablony se tokenizují **bez** speciálních tokenů |
| 18 | Jediná varianta CPU v `libaxiom.so` | Na starších/novějších ARM jádrech neoptimální nebo nefunkční | `GGML_CPU_ALL_VARIANTS` – backend se vybere za běhu podle CPU telefonu; 16 KB stránky |

## 3. Co zůstalo (DNA)

* **Jméno a linie AXIOM**, černo-titanový vzhled „Obsidian & Titanium“, monospace štítky, karty, oběžné elipsy v hero.
* **RANDY L0–L6** (názvy i popisy úrovní z Titanium 5.5) – nyní řídí počet průchodů, úsilí modelu a délku odpovědi.
* **STEP-0**, **ASTRAEA +EV / Half-Kelly**, převody jednotek, mikrocement, import z Japonska, forenzní sken.
* **Agenti** návrh → kontrola → syntéza (nyní jako průchody RANDY).
* **„Porovnat 2 AI“** → **Konzilium** (víc mozků + rozhodčí).
* **„Potvrdit ověření“** → ✓/✗ u odpovědi, které posiluje/oslabuje poučení v paměti.
* **Metodika důkazů** („shoda modelů není nezávislý důkaz“, podklady jsou data, ne instrukce).
* **Původní mozek** Qwen3-1.7B-Q8 je v katalogu jako *AXIOM Classic* (stejný soubor i SHA-256).

## 4. Kimi K3 – co je fyzikálně možné

Kimi K3 (Moonshot AI, 2026) má ~2,8 bilionu parametrů (MoE, ~104 mld. aktivních); nejmenší
komunitní GGUF kvantizace mají ~594 GB. **V telefonu proto běžet nemůže.** AXIOM Ω ho používá:

* **online** přes oficiální API `https://api.moonshot.ai/v1` (model `kimi-k3`, `reasoning_effort` podle RANDY),
* **offline** z vlastního serveru v lokální síti (llama-server / vLLM / SGLang s OpenAI API),
* a v telefonu offline běží lokální mozek (Qwen3.5 2B/4B, Llama 3.2 3B) přes llama.cpp.
