<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AXIOM Ω"></p>

<h1 align="center">AXIOM Ω</h1>
<p align="center"><b>Samostatná česká AI pro Android · RANDY · STEP-0 · sebemodel · Kimi K3 · Claude · offline</b></p>

Nástupce **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5**. Zachovává jejich DNA – protokol hloubky
**RANDY L0–L6**, deterministický **STEP-0**, **ASTRAEA +EV / Half-Kelly**, černo-titanový vzhled –
a přidává funkční sebemodel, dlouhodobou paměť, offline databáze, Kimi K3 a Claude.
Rozbor původní aplikace a seznam opravených chyb: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Co umí (3.1)

* **Ω Auto – AI si sama vybere moduly.** Router podle zadání rozhodne, jestli odpovědět, nebo spustit
  aktivního agenta; který mozek použít (jednoduché věci v telefonu, náročné na nejsilnějším dostupném)
  a jak hluboko myslet (RANDY L0–L6). Výslovná volba uživatele (`/agent`, `/chat`, `/konzilium`, `L4:`,
  ruční výběr mozku) má vždy přednost. U odpovědi je vidět, proč se tak rozhodl.
* **Aktivní agenti** (záložka Agenti nebo `/agent …`): sám plánuje a v krocích používá nástroje –
  web (DuckDuckGo), čtení stránek, Wikipedie, Hugging Face, přesné výpočty a STEP-0, offline databáze,
  paměť, ukládání poznámek. Běží na pozadí i se zhasnutým displejem, úkoly se ukládají.
* **Nezávislost:** funguje úplně bez Claude – v telefonu (Gemma 4), na Kimi K3, na vlastním serveru
  nebo na zdarma AXIOM Node. Claude je jen jeden z volitelných mozků.

## Mozky

| Mozek | Kde | Poznámka |
|---|---|---|
| **OMEGA Core · Gemma 4 E4B** | telefon 8+ GB | nejnovější Google model pro zařízení (duben 2026), doporučeno |
| **OMEGA Lite · Gemma 4 E2B** | telefon 6+ GB | rychlejší Gemma 4 |
| **OMEGA Max · Qwen3.6-35B-A3B** | telefon 16+ GB / Node | MoE 35B, aktivní 3B – nejsilnější, co se vejde |
| OMEGA Mini · Qwen3.5 2B, AXIOM Classic | telefon 4 GB | pro starší telefony / kontinuita s 2.1 |
| **Kimi K3** | `api.moonshot.ai` | 2,8T parametrů – v telefonu fyzicky neběží |
| **AXIOM Node** | zdarma: HF Spaces / Oracle Free | vlastní cloud server, viz [server/](server) |
| Vlastní server, Claude | LAN / Anthropic API | volitelné |
| **Jakýkoli GGUF** | Hugging Face hledání, odkaz, soubor v telefonu | Moduly → Hugging Face |

## Rozšíření z Hugging Face

* **OMEGA Sense** (Qwen3-Embedding-0.6B) – sémantická paměť: offline databáze se prohledává podle
  významu, ne jen podle slov (hybridní hledání).
* **Vyhledávání modelů na HF** přímo v aplikaci, výpis kvantizací s velikostí a kontrolou RAM, ověřené stažení (SHA-256 z LFS).
* **Datasety z HF** (datasets-server) a **články z Wikipedie** k tématu → offline databáze.

## Stažení APK

* **Actions → Build AXIOM Ω APK → artefakt `AXIOM-Omega-APK`** (každý push).
* **Releases** – při tagu `v*` (např. `git tag v3.0.0 && git push --tags`) se APK přiloží k vydání.
* Stabilní podpis (aktualizace bez odinstalace): přidej GitHub Secrets `OMEGA_KEYSTORE_B64`,
  `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`. Bez nich se release podepíše debug klíčem.

## Vývoj

```
./gradlew :core:test                                   # 66 unit testů (JVM, bez Androidu)
./gradlew :app:assembleDebug                           # plné APK včetně llama.cpp (NDK 27.2, CMake 3.22.1)
./gradlew :app:assembleDebug -Pomega.native=false      # rychlé APK bez nativní části
tools/ui-preview/build.sh && node tools/ui-preview/shoot.js /tmp   # náhled UI s mock mostem
```

Struktura: `core/` (čistý Kotlin – RANDY, STEP-0, sebemodel, enginy Kimi/Claude, stahování, balíčky),
`app/` (Android – WebView UI, most, služba, SQLite, Keystore, JNI `omega_jni.cpp` nad llama.cpp `b12032`),
`brains/` a `packs/` (katalogy), `legacy/` (původní Titanium generátor a logo).
