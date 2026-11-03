# AVIOR – skutečná architektura (stav: Delta IV, sestavení 1006)

Popisuje **implementovaný** kód, ne záměry. Cesty jsou relativní ke kořeni repozitáře.

## Moduly sestavení
- `core/` – čistý Kotlin/JVM (8,9 tis. řádků), testovatelný bez Androidu. Veškerá logika rozhodování, reflexů, ověřování a cloudu.
- `app/` – Android (Kotlin 6,0 tis. řádků + `app/src/main/cpp/omega_jni.cpp`):
  - aktivita s WebView UI (`app/src/main/assets/ui/` – `index.html`, `app.js`, `app.css`),
  - most JS↔Kotlin `bridge/OmegaBridge.kt`,
  - služba v popředí `jobs/OmegaService.kt`,
  - SQLite `data/Db.kt` a `data/LibraryDb.kt`,
  - lokální inference `engine/LocalEngine.kt` (llama.cpp přes JNI, tag b12032).

## Tok dotazu (`OmegaService.ask`)
1. **Reflex – bez mozku** (`core/.../agent/Reflex.kt`), deterministicky:
   - pořadí: Practical → Instant → Everyday → Facts → Politics → kalendář → vzpomínky → paměť odpovědí → Srovnávač → WikiReflex,
   - odpoví jen nad prahem jistoty (Auto 85, Flash 70),
   - kandidáty, které tvrdí ověřitelné údaje, kontroluje Strážce (`Sentinel.kt`); vyvrácenou uloženou odpověď zahodí.
2. **Revize při opravě:** `Proof.onCorrection` – doložený závěr se neměnil kvůli „Špatně“ bez zdroje.
3. **E-ARL** (`agent/Earl.kt`): REFLEX / RETRIEVE / LIGHT / BRAIN / DEEP podle signálů z `Pavucina.kt`.
4. **Bez mozku i bez cloudu:** extrakce z offline knihovny (`OfflineAnswer.kt`).
5. **Governor** (`device/Governor.kt`): rozpočet podle baterie, tepla a RAM.
6. **RANDY Supervisor** (`randy/Supervisor.kt`, `randy/Randy.kt`): plán podmodelů a hloubky.
7. **Orchestrator** (`agent/Orchestrator.kt`) / **ActiveAgent** (`agent/ActiveAgent.kt`):
   - mozek = `LocalEngine`, nebo cloud `llm/FreeCloud.kt` (řetěz poskytovatelů se zálohou), `ClaudeEngine`, `KimiEngine`,
   - DLP štít (`security/`) před cloudem, air-gap v `llm/Http.kt`.
8. **Po odpovědi:**
   - úklid textu (`Conversation.clean`),
   - **audit Strážce** (`Sentinel.audit`) → `meta.delta` (rozpor) / `meta.proof` (potvrzeno),
   - uložení do paměti odpovědí s **původem** (`Proof.originOf`) a **revizní smlouvou** (`Proof.contract`), jen pokud nic nebylo vyvráceno.

## Data
- `omega.db` (verze 4):
  - tabulky `threads`, `messages`, `memory`, `docs` + FTS `chunks`, `packs`, `vectors`, `tasks`,
  - `answers(question, answer, conf, good, at, origin, revise)`,
  - migrace: `Db.onUpgrade` + samooprava `Db.onOpen` (PRAGMA).
- **Offline knihovny:**
  - předpřipravené SQLite FTS4 z Wikimedia (`LibraryDb.kt`, `core/.../library/Library.kt`),
  - RAG index int8 (`core/.../rag/Rag.kt`),
  - sestavují je workflow `build-library.yml` a `build-rag.yml`.

## Pravidla důvěry (PROOFCORE)
| Původ | Význam | Použití bez mozku |
|---|---|---|
| USER | uživatel dal ✓ | i přeformulovaná otázka, jistota 96·podobnost |
| WIKI | Strážce kladně ověřil údaj, na který se otázka ptá; krátká odpověď; nic nevyvráceno | jistota 92·podobnost |
| MODEL | jen text modelu | jen tatáž otázka, jistota 80 (Auto ji nepoužije) |

Revize: úřady, ceny a počty po 90 dnech; MODEL po 180 dnech; ostatní po 3 letech (`Proof.contract`).
Poznámky agenta: vždy „Poznámka AVIORu:“ (`Proof.noteTitle`), `memory_save` → EPIZODA.

## Hranice bezpečnosti
- **WebView** (`MainActivity.kt`):
  - jen `https://appassets.androidplatform.net` přes `WebViewAssetLoader`,
  - jiná navigace jde do externího prohlížeče (jen https),
  - `allowFileAccess=false`, CSP v `index.html`.
- **Most:** vykreslování escapuje text (`app.js: esc/inline/md`); odkazy jen https přes `data-open`.
- **Exportované komponenty:** Launcher/ASSIST/SEND aktivita (sdílený text jde jen do konceptu, neodesílá se) a služby asistenta. Ty jsou chráněné systémovými oprávněními; `AviorRecognitionService` je prázdný stub.
