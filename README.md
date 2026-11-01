<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AXIOM Ω"></p>

<h1 align="center">AXIOM Ω</h1>
<p align="center"><b>Samostatná česká AI pro Android · RANDY · STEP-0 · sebemodel · Kimi K3 · Claude · offline</b></p>

Nástupce **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5**. Zachovává jejich DNA – protokol hloubky
**RANDY L0–L6**, deterministický **STEP-0**, **ASTRAEA +EV / Half-Kelly**, černo-titanový vzhled –
a přidává funkční sebemodel, dlouhodobou paměť, offline databáze, Kimi K3 a Claude.
Rozbor původní aplikace a seznam opravených chyb: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Mozky

| Režim | Kde běží | Internet |
|---|---|---|
| **Kimi K3 · cloud** | `api.moonshot.ai/v1`, model `kimi-k3`, `reasoning_effort` podle RANDY | ano |
| **Kimi K3 · vlastní server** | tvůj llama-server / vLLM / SGLang v lokální síti | ne |
| **Mozek v telefonu** | llama.cpp: OMEGA Core (Qwen3.5 4B), OMEGA Lite (Qwen3.5 2B), AXIOM Classic (Qwen3 1.7B), Llama 3.2 3B | ne |
| **Claude** | oficiální Anthropic Java SDK, `claude-opus-5-5`, adaptivní přemýšlení, web search | ano |
| **Konzilium** | víc mozků odpoví nezávisle, rozhodčí porovná a složí odpověď | dle mozků |
| **Auto** | online → Kimi K3 (jinak Claude), offline → mozek v telefonu, jinak K3 server | — |

> Kimi K3 má ~2,8 bilionu parametrů (nejmenší GGUF ~594 GB), takže se do telefonu nevejde.
> „Offline K3“ = vlastní server bez internetu. V telefonu offline běží menší lokální mozek.

**Stažení mozku** (Mozky → Stáhnout): katalog [`brains/catalog.json`](brains/catalog.json) s ověřenými
URL Hugging Face, velikostmi a SHA-256; navazování po výpadku, zrcadlo, kontrola volného místa a RAM.
Katalog lze aktualizovat bez nové verze aplikace.

## Jak AXIOM Ω myslí

1. **RANDY** určí hloubku (L0 DIRECT … L6 OMNISCIENT; vynucení prefixem `L4:`).
2. **STEP-0** spočítá vše počitatelné kódem: výrazy, procenta, jednotky a měny (kurzy ČNB),
   +EV/Kelly, mikrocement a stěrky, import z Japonska, forenzní stopy (IBAN mod 97, IP, hashe, časy).
3. **Paměť a znalosti** – vybavení relevantních vzpomínek a pasáží z offline databáze (SQLite FTS4).
4. **Průchody** podle úrovně: návrh → red-team / kvantitativní kontrola → syntéza.
5. **Sebereflexe** – jistota 0–100 % a poučení do paměti; tvoje ✓/✗ ho posílí nebo oslabí.

„Sebeuvědomění“ je funkční a poctivé: AXIOM Ω zná svůj stav (mozek, síť, baterie, RAM, teplota,
paměť), své limity a historii – a nepředstírá vědomí ani city.

## Offline databáze

* **Znalostní balíčky** ([`packs/`](packs)) jsou součástí APK a po instalaci se zaindexují bez internetu:
  metodika AXIOM, jednotky a konstanty SI, stavba a materiály, finance a +EV, Kimi K3 a vlastní server.
  Nové verze se stahují z repozitáře a ověřují SHA-256 (`tools/update_pack_catalog.py`).
* Vlastní dokumenty: import TXT / Markdown / CSV / JSON nebo poznámky.
* **Import paměti z Gemini** (Paměť → Import paměti z Gemini): zkopírovaný dotaz pro Gemini + vložení odpovědi,
  seznam „Uložené informace“, nebo Google Takeout (Moje aktivita → Aplikace Gemini: `MyActivity.json`, `.html`, `.zip`).
* **Záloha a obnova** celé databáze (vlákna, paměť, znalosti) do souboru; API klíče se nezálohují.

## Stažení APK

* **Actions → Build AXIOM Ω APK → artefakt `AXIOM-Omega-APK`** (každý push).
* **Releases** – při tagu `v*` (např. `git tag v3.0.0 && git push --tags`) se APK přiloží k vydání.
* Stabilní podpis (aktualizace bez odinstalace): přidej GitHub Secrets `OMEGA_KEYSTORE_B64`,
  `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`. Bez nich se release podepíše debug klíčem.

## Vývoj

```
./gradlew :core:test                                   # 58 unit testů (JVM, bez Androidu)
./gradlew :app:assembleDebug                           # plné APK včetně llama.cpp (NDK 27.2, CMake 3.22.1)
./gradlew :app:assembleDebug -Pomega.native=false      # rychlé APK bez nativní části
tools/ui-preview/build.sh && node tools/ui-preview/shoot.js /tmp   # náhled UI s mock mostem
```

Struktura: `core/` (čistý Kotlin – RANDY, STEP-0, sebemodel, enginy Kimi/Claude, stahování, balíčky),
`app/` (Android – WebView UI, most, služba, SQLite, Keystore, JNI `omega_jni.cpp` nad llama.cpp `b12032`),
`brains/` a `packs/` (katalogy), `legacy/` (původní Titanium generátor a logo).
