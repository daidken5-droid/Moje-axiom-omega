<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AXIOM Ω"></p>

<h1 align="center">AXIOM Ω</h1>
<p align="center"><b>Samostatná česká AI pro Android · RANDY · STEP-0 · sebemodel · Kimi K3 · Claude · offline</b></p>

Nástupce **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5**. Zachovává jejich DNA – protokol hloubky
**RANDY L0–L6**, deterministický **STEP-0**, **ASTRAEA +EV / Half-Kelly**, černo-titanový vzhled –
a přidává funkční sebemodel, dlouhodobou paměť, offline databáze, Kimi K3 a Claude.
Rozbor původní aplikace a seznam opravených chyb: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Co umí (3.2)

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

Celá **česká Wikipedie** (534 tis. článků, ~5 GB), **slovenská** (242 tis.) a **Simple English** (242 tis.)
ke stažení do telefonu – samostatná databáze s fulltextem (prefixové hledání zvládá české tvary slov),
navazování po přerušení, kontrola místa. Odpovědi i agenti z ní čerpají bez internetu.

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
